package com.evsuite.abrp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Restarts uploading when the head unit powers up.
 *
 * BOOT_COMPLETED alone was not enough. An MG4's head unit rarely cold-boots: switching the
 * car on usually resumes it, and the resume is announced with QUICKBOOT_POWERON instead.
 * Users reported uploading never starting with the car, and this is why — the app was
 * waiting for a broadcast their car does not always send. The other EVSuite apps already
 * listen to the whole family; this one now does too.
 *
 * MY_PACKAGE_REPLACED is in the list for a different reason: an update stops the service,
 * and nothing else would bring it back until the next power cycle.
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

        SharedPreferences prefs = context.getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("service_enabled", false);
        boolean autostart = prefs.getBoolean(UploadSettings.KEY_AUTOSTART, UploadSettings.DEFAULT_AUTOSTART);

        // Credentials come from SecurePrefs, not from the plaintext file. This receiver used
        // to read "token" out of abrp_prefs — where it has not lived since the credentials
        // were encrypted, because the migration deletes the plaintext copy. The check
        // therefore always failed, and uploading never started by itself on any car.
        SharedPreferences secure = SecurePrefs.get(context);
        boolean haveCreds = !secure.getString(SecurePrefs.KEY_TOKEN, "").trim().isEmpty()
                         && !secure.getString(SecurePrefs.KEY_API_KEY, "").trim().isEmpty();

        if (!enabled || !autostart || !haveCreds) {
            Log.i(TAG, action + " ignored (enabled=" + enabled
                    + " autostart=" + autostart + " credentials=" + haveCreds + ")");
            return;
        }

        Log.i(TAG, action + " — starting upload service");
        try {
            context.startForegroundService(new Intent(context, AbrpUploadService.class));
        } catch (Exception e) {
            // A start refused this early is not worth crashing the boot broadcast over: the
            // next power cycle, or opening the app, starts it just the same.
            Log.w(TAG, "startForegroundService failed: " + e.getMessage());
        }
    }
}
