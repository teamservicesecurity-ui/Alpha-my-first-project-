package com.alpha.shield;

import android.app.Application;
import android.content.Context;

import androidx.work.Configuration;
import androidx.work.WorkManager;

import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class AlphaApplication extends Application implements Configuration.Provider {

    private static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
        // WorkManager is initialised on-demand via getWorkManagerConfiguration
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .build();
    }

    public static Context get() { return ctx; }
}
