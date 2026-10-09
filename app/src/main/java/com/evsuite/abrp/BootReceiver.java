package com.evsuite.abrp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Starts the consumption tracking service when the head unit powers up.
 *
 * BOOT_COMPLETED alone was not enough. An MG4's head unit rarely cold-boots: switching the
 * car on usually resumed it, and the resume is announced with QUICKBOOT_POWERON instead.
 * MY_PACKAGE_REPLACED brings the service back after an update stops it.
 *
 * Always-on: no credential or toggle gate. The service tracks consumption and syncs to the
 * cloud unconditionally — that is its entire purpose.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "EVABRP.Boot";

    private static final Set<String> START_ACTIONS = new HashSet<>(Arrays.asList(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
    ));

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null || !START_ACTIONS.contains(action)) return;

        Log.i(TAG, action + " — starting consumption service");
        try {
            context.startForegroundService(new Intent(context, AbrpUploadService.class));
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService failed: " + e.getMessage());
        }
    }
}
