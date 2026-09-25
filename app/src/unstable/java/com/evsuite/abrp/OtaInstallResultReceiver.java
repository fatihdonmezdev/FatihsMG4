package com.evsuite.abrp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * Where a {@link PackageInstaller} session reports what it did — UNSTABLE BUILDS ONLY.
 *
 * The session commit is asynchronous, so unlike the {@code pm install -r} it replaced, the
 * install outcome cannot be the return value of the call that started it. It arrives here.
 *
 * STATUS_PENDING_USER_ACTION is the case worth understanding: the platform is saying it will
 * install, but wants the driver to confirm. That happens when the app is not granted a silent
 * install, and the only correct response is to launch the confirmation activity it hands us.
 */
public final class OtaInstallResultReceiver extends BroadcastReceiver {

    static final String ACTION_INSTALL_RESULT = "com.evsuite.abrp.OTA_INSTALL_RESULT";

    private static final String TAG = "EVABRP.Update";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "OTA install result: status=" + status + " msg=" + message);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    toast(context, context.getString(R.string.ota_install_failed, "no confirm intent"));
                    return;
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Throwable t) {
                    Log.e(TAG, "OTA confirm UI failed: " + t.getMessage());
                    toast(context, context.getString(R.string.ota_install_failed,
                            t.getClass().getSimpleName()));
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                toast(context, context.getString(R.string.ota_install_success));
                break;
            default:
                // The status message is the package manager's own words — INSTALL_FAILED_*
                // and friends. Surfaced verbatim, because guessing at it is what made the
                // previous failure unreadable.
                toast(context, context.getString(R.string.ota_install_failed,
                        message != null ? message : "status " + status));
                break;
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }
}
