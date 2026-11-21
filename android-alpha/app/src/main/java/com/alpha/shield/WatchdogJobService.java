package com.alpha.shield;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * WatchdogJobService — JobScheduler-backed persistence.
 *
 * JobScheduler jobs with setPersisted(true) survive device reboots and are
 * rescheduled by Android automatically. This is the most reliable non-alarm
 * mechanism to restart Alpha when other layers fail.
 *
 * On job execution:
 *   1. Restart WatchdogService if not alive
 *   2. Reschedule this job for next run via SurvivalManager
 *   3. Report jobFinished(false) so Android reschedules on its own too
 */
@Obfuscate
public class WatchdogJobService extends JobService {

    private static final String TAG = "WatchdogJob";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "job fired");
        try {
            if (!CommandService.isAlive()) {
                Intent i = new Intent(getApplicationContext(), WatchdogService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplicationContext().startForegroundService(i);
                } else {
                    getApplicationContext().startService(i);
                }
            }
            // Re-arm JobScheduler and alarms for next cycle
            new SurvivalManager(getApplicationContext()).armAlarmClock();
        } catch (Exception e) {
            Log.e(TAG, "job error: " + e.getMessage());
        }
        // false = job is complete (don't need reschedule callback)
        // Android will reschedule automatically due to setPersisted(true) + deadline
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Return true = reschedule this job if it was stopped early
        return true;
    }
}
