package com.alpha.shield;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lsparanoid.Obfuscate;

/**
 * GestureCore v2 — Alpha edition.
 *
 * Improvements over Flash:
 *   - Double-tap via two sequential dispatchGesture calls (50 ms gap)
 *   - Pinch / spread: multi-stroke two-finger GestureDescription (API 24+)
 *   - Drag with willContinue + continueStroke (API 26+): finger-down hold then move
 *   - TYPE v2: append mode + backspace/enter/delete handling; clipboard paste for
 *     complex unicode that ACTION_SET_TEXT may not handle on some ROMs
 *   - Scroll: normalized coords (same Bug-5 fix) + velocity duration parameter
 *   - PATH_GESTURE: smooth bezier-style timing from inter-point distances
 *   - Volume: AudioManager ADJUST_RAISE / ADJUST_LOWER
 *   - All Flash Bug-2 (show_touches) and Bug-3 (1ms→8ms) fixes retained
 */
@Obfuscate
public class GestureCore {

    private static final String TAG = "GC";

    // Min stroke duration safe across MIUI / OneUI / ColorOS
    private static final long MIN_STROKE_MS = 8;
    // Debug overlay — NEVER true in production
    private static final boolean DEBUG_MARKERS = false;

    public interface ScreenWakeDelegate {
        void wakeUpScreen();
    }

    private final AccessibilityService service;
    private final ScreenWakeDelegate   wake;
    private final Handler              handler = new Handler(Looper.getMainLooper());

    public GestureCore(AccessibilityService service, ScreenWakeDelegate wake) {
        this.service = service;
        this.wake    = wake;
    }

    // ── Screen dimensions helper ───────────────────────────────────────────────

    private int screenW() {
        return service.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return service.getResources().getDisplayMetrics().heightPixels;
    }

    /** Converts normalised [0,1] x to pixel, clamped. */
    private int px(double norm, int dim) {
        return (int) Math.max(0, Math.min(dim - 1, norm * dim));
    }

    // ── Dispatch helpers ───────────────────────────────────────────────────────

    private boolean dispatch(GestureDescription g) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return service.dispatchGesture(g, null, null);
    }

    private boolean dispatch(GestureDescription g,
                             AccessibilityService.GestureResultCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        return service.dispatchGesture(g, cb, null);
    }

    // ── TAP ───────────────────────────────────────────────────────────────────

    /**
     * @param nx normalised x [0,1]
     * @param ny normalised y [0,1]
     */
    public void tap(double nx, double ny) {
        int x = px(nx, screenW()), y = px(ny, screenH());
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, MIN_STROKE_MS))
                .build();
        if (!dispatch(g)) Log.w(TAG, "TAP dispatch failed");
        if (DEBUG_MARKERS) showMarker(x, y, 300);
    }

    // ── DOUBLE TAP ────────────────────────────────────────────────────────────

    public void doubleTap(double nx, double ny) {
        tap(nx, ny);
        handler.postDelayed(() -> tap(nx, ny), 50);
    }

    // ── LONG PRESS ────────────────────────────────────────────────────────────

    public void longPress(double nx, double ny, int durationMs) {
        int x = px(nx, screenW()), y = px(ny, screenH());
        int dur = Math.max(400, Math.min(durationMs, 3000));
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                .build();
        dispatch(g);
    }

    // ── SWIPE ─────────────────────────────────────────────────────────────────

    public void swipe(double nx1, double ny1, double nx2, double ny2, int durationMs) {
        int sw = screenW(), sh = screenH();
        int x1 = px(nx1, sw), y1 = px(ny1, sh);
        int x2 = px(nx2, sw), y2 = px(ny2, sh);
        int dur = Math.max((int) MIN_STROKE_MS, Math.min(durationMs, 3000));
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                .build();
        dispatch(g);
    }

    // ── PINCH / SPREAD ────────────────────────────────────────────────────────

    /**
     * Two-finger pinch or spread.
     * @param centerX  normalised pivot X
     * @param centerY  normalised pivot Y
     * @param fromDist starting finger separation (normalised, relative to screen width)
     * @param toDist   ending finger separation
     * @param durationMs
     */
    public void pinch(double centerX, double centerY,
                      double fromDist, double toDist, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        int sw = screenW(), sh = screenH();
        int cx = px(centerX, sw), cy = px(centerY, sh);
        int halfFrom = (int)(fromDist * sw / 2.0);
        int halfTo   = (int)(toDist   * sw / 2.0);
        int dur = Math.max(100, Math.min(durationMs, 2000));

        // Finger 1: left side → converge
        Path p1 = new Path();
        p1.moveTo(cx - halfFrom, cy);
        p1.lineTo(cx - halfTo,   cy);
        // Finger 2: right side → converge
        Path p2 = new Path();
        p2.moveTo(cx + halfFrom, cy);
        p2.lineTo(cx + halfTo,   cy);

        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, dur))
                .addStroke(new GestureDescription.StrokeDescription(p2, 0, dur))
                .build();
        dispatch(g);
    }

    // ── DRAG ──────────────────────────────────────────────────────────────────

    /**
     * Drag: finger-down at (sx,sy), hold for holdMs, then drag to (ex,ey) over dragMs.
     * Uses willContinue + continueStroke (API 26+). Falls back to swipe on older API.
     */
    public void drag(double nsx, double nsy, double nex, double ney,
                     int holdMs, int dragMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Fallback: simple slow swipe
            swipe(nsx, nsy, nex, ney, holdMs + dragMs);
            return;
        }
        int sw = screenW(), sh = screenH();
        int sx = px(nsx, sw), sy = px(nsy, sh);
        int ex = px(nex, sw), ey = px(ney, sh);
        int hold = Math.max(300, holdMs);
        int move = Math.max(100, dragMs);

        // Phase 1: hold finger down
        Path holdPath = new Path();
        holdPath.moveTo(sx, sy);
        holdPath.lineTo(sx, sy);
        GestureDescription.StrokeDescription holdStroke =
                new GestureDescription.StrokeDescription(holdPath, 0, hold, true);

        GestureDescription holdGesture = new GestureDescription.Builder()
                .addStroke(holdStroke)
                .build();

        dispatch(holdGesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                // Phase 2: drag to destination
                Path dragPath = new Path();
                dragPath.moveTo(sx, sy);
                dragPath.lineTo(ex, ey);
                GestureDescription.StrokeDescription dragStroke =
                        holdStroke.continueStroke(dragPath, 0, move, false);
                GestureDescription dragGesture = new GestureDescription.Builder()
                        .addStroke(dragStroke)
                        .build();
                dispatch(dragGesture);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                // Fall back if hold cancelled
                swipe(nsx, nsy, nex, ney, move);
            }
        });
    }

    // ── PATH GESTURE ──────────────────────────────────────────────────────────

    /**
     * Multi-point path gesture. Points are normalised [0,1] coords.
     * Duration is calculated from inter-point pixel distances for smooth velocity.
     */
    public void pathGesture(JSONArray points) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            if (points == null || points.length() < 2) return;
            int sw = screenW(), sh = screenH();

            // Build path and measure total pixel distance for duration scaling
            int[] xs = new int[points.length()];
            int[] ys = new int[points.length()];
            double totalDist = 0;
            for (int i = 0; i < points.length(); i++) {
                JSONObject pt = points.getJSONObject(i);
                xs[i] = px(pt.getDouble("x"), sw);
                ys[i] = px(pt.getDouble("y"), sh);
                if (i > 0) {
                    int dx = xs[i] - xs[i-1], dy = ys[i] - ys[i-1];
                    totalDist += Math.sqrt(dx*dx + dy*dy);
                }
            }

            Path p = new Path();
            p.moveTo(xs[0], ys[0]);
            for (int i = 1; i < points.length(); i++) p.lineTo(xs[i], ys[i]);

            // ~1ms per 3px of movement, min 60ms, max 2000ms
            long dur = Math.max(60, Math.min(2000, (long)(totalDist / 3.0)));

            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, dur))
                    .build();
            dispatch(g);
        } catch (Exception e) {
            Log.e(TAG, "pathGesture: " + e.getMessage());
        }
    }

    // ── SCROLL ────────────────────────────────────────────────────────────────

    /** Convenience: scroll at position, positive deltaY = scroll down (finger moves up). */
    public void scroll(double nx, double ny, double deltaY, int durationMs) {
        double d = Math.max(-0.6, Math.min(0.6, deltaY));
        double endY = Math.max(0.01, Math.min(0.99, ny - d));
        swipe(nx, ny, nx, endY, Math.max(80, durationMs));
    }

    // ── TYPE ──────────────────────────────────────────────────────────────────

    /**
     * Type text into focused/editable node.
     * Handles: append mode, backspace, delete, enter, tab, unicode via clipboard paste.
     */
    public void type(String text) {
        if (text == null || text.isEmpty()) return;

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            Log.e(TAG, "type: no root window");
            return;
        }

        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) target = findEditable(root);

        if (target != null && target.isEditable()) {
            performType(target, text);
            target.recycle();
        } else {
            // No editable found — try clipboard inject anyway
            injectViaClipboard(text);
        }
        root.recycle();
    }

    private void performType(AccessibilityNodeInfo node, String text) {
        // Special control sequences
        if ("\b".equals(text)) {
            // Backspace: get current text, remove last char
            CharSequence cur = node.getText();
            String s = cur != null ? cur.toString() : "";
            if (!s.isEmpty()) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        s.substring(0, s.length() - 1));
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            }
            return;
        }
        if ("\u007f".equals(text)) {
            // Delete forward — same as backspace for simplicity
            performType(node, "\b");
            return;
        }
        if ("\n".equals(text)) {
            // Enter: try IME action first, then inject newline
            if (!node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)) {
                appendText(node, "\n");
            }
            return;
        }
        if ("\t".equals(text)) {
            appendText(node, "\t");
            return;
        }

        // Check if text is pure ASCII printable — direct append
        boolean isSimple = isPureAsciiPrintable(text);
        if (isSimple) {
            appendText(node, text);
        } else {
            // Unicode / complex: use clipboard paste for reliability
            injectViaClipboard(text);
        }
    }

    private void appendText(AccessibilityNodeInfo node, String add) {
        CharSequence cur = node.getText();
        String existing = cur != null ? cur.toString() : "";
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + add);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private void injectViaClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    service.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("", text));

            // Re-find focused node and paste
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return;
            AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) node = findEditable(root);
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                node.recycle();
            }
            root.recycle();
        } catch (Exception e) {
            Log.e(TAG, "clipboard inject: " + e.getMessage());
        }
    }

    private boolean isPureAsciiPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo res = findEditable(child);
            if (res != null) return res;
        }
        return null;
    }

    // ── CLIPBOARD ─────────────────────────────────────────────────────────────

    public void setClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    service.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("", text));
        } catch (Exception e) {
            Log.e(TAG, "setClipboard: " + e.getMessage());
        }
    }

    public String getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager)
                    service.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                return text != null ? text.toString() : "";
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ── NAVIGATION ────────────────────────────────────────────────────────────

    public void back()          { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); }
    public void home()          { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
    public void recents()       { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); }
    public void notifications() { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); }
    public void quickSettings() { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); }

    public void lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
    }

    public void powerMenu() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        }
    }

    public void wakeUp() {
        if (wake != null) wake.wakeUpScreen();
    }

    // ── VOLUME ────────────────────────────────────────────────────────────────

    public void volumeUp() {
        audioAdjust(AudioManager.ADJUST_RAISE);
    }

    public void volumeDown() {
        audioAdjust(AudioManager.ADJUST_LOWER);
    }

    private void audioAdjust(int direction) {
        try {
            AudioManager am = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (Exception e) {
            Log.e(TAG, "volume: " + e.getMessage());
        }
    }

    // ── SWIPE-TO-UNLOCK (helper used by LockManager) ──────────────────────────

    public void swipeUpToUnlock() {
        int sw = screenW(), sh = screenH();
        swipe(0.5, 0.80, 0.5, 0.20, 300);
    }

    // ── DEBUG MARKER (unreachable in production) ──────────────────────────────

    public void showMarker(int x, int y, int durationMs) {
        if (!DEBUG_MARKERS || service == null) return;
        handler.post(() -> {
            try {
                WindowManager wm = (WindowManager)
                        service.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;
                View v = new View(service) {
                    final Paint outer = makePaint(Color.parseColor("#00F0FF"), Paint.Style.STROKE, 8f);
                    final Paint inner = makePaint(Color.parseColor("#FF0077"), Paint.Style.FILL, 1f);
                    Paint makePaint(int c, Paint.Style s, float sw) {
                        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                        p.setColor(c); p.setStyle(s); p.setStrokeWidth(sw); return p;
                    }
                    @Override protected void onDraw(Canvas canvas) {
                        float cx = getWidth() / 2f, cy = getHeight() / 2f;
                        canvas.drawCircle(cx, cy, cx - 6f, outer);
                        canvas.drawCircle(cx, cy, cx / 3.5f, inner);
                    }
                };
                int size = 100;
                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        size, size, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.LEFT;
                p.x = x - size / 2; p.y = y - size / 2;
                wm.addView(v, p);
                handler.postDelayed(() -> { try { wm.removeView(v); } catch (Exception ignored) {} }, durationMs);
            } catch (Exception ignored) {}
        });
    }
}
