# Keep Alpha entry points intact (accessed via reflection or JNI)
-keep class com.alpha.shield.CommandService { *; }
-keep class com.alpha.shield.SurvivalManager { *; }
-keep class com.alpha.shield.BootReceiver { *; }
-keep class com.alpha.shield.WatchdogService { *; }
-keep class com.alpha.shield.WatchdogJobService { *; }
-keep class com.alpha.shield.AdminReceiver { *; }
-keep class com.alpha.shield.SurvivalManager.AlphaWorker { *; }

# JNI: keep native method signatures exactly
-keepclasseswithmembernames class * {
    native <methods>;
}
# Keep ensureAlive called from C
-keepclassmembers class com.alpha.shield.SurvivalManager {
    public static void ensureAlive(android.content.Context);
}

# OkHttp / Gson
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.gson.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker { *; }

# Suppress noisy warnings
-dontwarn java.lang.invoke.**
-dontwarn javax.**
