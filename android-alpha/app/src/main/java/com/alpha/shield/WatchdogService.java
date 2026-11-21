package com.alpha.shield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * WatchdogService — Alpha's companion process guard.
 *
 * This is a lightweight foreground Service (not an AccessibilityService).
 * Android is very reluctant to kill foreground services — it's the highest-
 * priority layer available without system/root access.
 *
 * Responsibilities:
 *   - Runs as foreground service with minimal notification
 *   - Every 15 seconds: checks if CommandService is alive (via INSTANCE ref)
 *   - Every 30 seconds: checks if accessibility is still enabled
 *   - If CommandService dead: navigates to AccessibilitySettings to prompt re-enable
 *   - If service IS alive: no-op (< 1 ms per check)
 *   - onTaskRemoved: immediately schedules restart of everything
 *   - START_STICKY ensures the OS restarts this service itself after kills
 */
@Obfuscate
public class WatchdogService extends Service {

    private static final String TAG          = "WatchdogSvc";
    private static final String NOTIF_CH     = "alpha_wdg";
    private static final int    NOTIF_ID     = 2;
    private static final long   CHECK_FAST   = 15_000L;  // CommandService alive check
    private static final long   CHECK_SLOW   = 30_000L;  // Accessibility check

    private final Handler   handler    = new Handler(Looper.getMainLooper());
    private Runnable        fastCheck;
    private Runnable        slowCheck;
    private volatile boolean running   = false;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            initNotification();
            startChecks();
            Log.d(TAG, "watchdog armed");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        // Restart self immediately
        Intent restart = new Intent(getApplicationContext(), WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(restart);
        } else {
            getApplicationContext().startService(restart);
        }
        super.onDestroy();
        Log.d(TAG, "destroyed — restarting");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swiped app from recents — force restart
        SurvivalManager sm = new SurvivalManager(getApplicationContext());
        sm.armAlarmClock();
        onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Checks ────────────────────────────────────────────────────────────────

    private void startChecks() {
        fastCheck = new Runnable() {
            @Override public void run() {
                if (!running) return;
                checkCommandService();
                handler.postDelayed(this, CHECK_FAST);
            }
        };
        slowCheck = new Runnable() {
            @Override public void run() {
                if (!running) return;
                checkAccessibility();
                handler.postDelayed(this, CHECK_SLOW);
            }
        };
        handler.postDelayed(fastCheck, CHECK_FAST);
        handler.postDelayed(slowCheck, CHECK_SLOW);
    }

    private void checkCommandService() {
        if (!CommandService.isAlive()) {
            Log.w(TAG, "CommandService dead");
            // The accessibility service can't be started directly —
            // guide the user to re-enable it via notification + overlay
            new SurvivalManager(getApplicationContext())
                    .armAlarmClock(); // keep alarms live
        }
    }

    private void checkAccessibility() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean active = enabled != null
                    && enabled.contains(getPackageName());
            if (!active) {
                Log.w(TAG, "accessibility not active");
                // SurvivalManager ContentObserver handles resurrection notification,
                // but as a belt-and-suspenders measure we also try here
                try {
                    new SurvivalManager(getApplicationContext()).armAlarmClock();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "checkAccess: " + e.getMessage());
        }
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CH, "System",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, NOTIF_CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("System")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        startForeground(NOTIF_ID, n);
    }
}
