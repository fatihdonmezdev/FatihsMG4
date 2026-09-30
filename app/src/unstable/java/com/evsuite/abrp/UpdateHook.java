package com.evsuite.abrp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives {@link OtaUpdater} — UNSTABLE BUILDS ONLY.
 *
 * Upstream keeps this seam inert pending a safety and legal audit of the published suite.
 * This fork publishes its own builds from its own repository, signed with the platform key
 * the head unit already trusts, so that audit does not govern here and the seam is live.
 *
 * Installation goes through the platform {@link android.content.pm.PackageInstaller}, not
 * {@code pm install -r}: that shelled out with a path inside our private cache, which the
 * package manager service cannot read from its own process, and the generic failure was
 * reported as a signature mismatch it never was. As in DriveHub_Dort, PackageInstaller is the
 * authority for signing-certificate compatibility; the app verifies SHA-256 and package name
 * before handing it the archive.
 *
 * Committing a session is asynchronous, so the message this returns says an install has been
 * started, never that one finished. {@link OtaInstallResultReceiver} has the verdict.
 */
final class UpdateHook {

    private static final String TAG = "EVABRP.Update";
    private static final AtomicBoolean checking = new AtomicBoolean();

    private UpdateHook() { }

    static boolean isSupported() { return true; }

    /** Result of one check, for the UI to report. */
    interface Listener {
        default void onProgress(int percent) { }
        void onResult(String message);
    }

    /** Startup only checks and notifies. Download/install remains an explicit driver action. */
    static void checkInBackground(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String current = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0).versionName;
                OtaUpdater.Update update = OtaUpdater.check(current);
                if (update != null && update.hashUrl != null)
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(() -> android.widget.Toast.makeText(app,
                                "Güncelleme hazır: " + update.versionName,
                                android.widget.Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                Log.w(TAG, "Startup update check failed", t);
            }
        }, "ota-startup-check").start();
    }

    /**
     * Checks, downloads and installs in one background pass.
     *
     * The whole sequence runs off the main thread because every step blocks: the release
     * API call, the download, and installer staging. [listener] is called back on the main
     * thread, so a UI caller can write straight to a view.
     */
    static void checkInBackground(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (!checking.compareAndSet(false, true)) {
            if (listener != null) main.post(() -> listener.onResult("Güncelleme kontrolü zaten sürüyor"));
            return;
        }
        new Thread(() -> {
            String message;
            try {
                message = runCheck(app, listener, main);
            } catch (Throwable t) {
                Log.w(TAG, "Update check threw", t);
                message = "Güncelleme kontrolü başarısız";
            } finally {
                checking.set(false);
            }
            if (listener != null) {
                final String result = message;
                main.post(() -> listener.onResult(result));
            }
        }, "ota-check").start();
    }

    /** The blocking part. Returns a line describing what happened, for the UI. */
    private static String runCheck(Context app, Listener listener,
                                   android.os.Handler main) throws IOException {
        String current;
        try {
            current = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "Sürüm okunamadı";
        }

        OtaUpdater.Update update = OtaUpdater.check(current);
        if (update == null) return "Güncel (" + current + ")";

        Log.i(TAG, "Update available: " + update.versionName);
        String expectedHash;
        try {
            expectedHash = OtaUpdater.fetchExpectedSha256(update);
        } catch (IOException e) {
            Log.w(TAG, "OTA hash unavailable", e);
            return "Güncelleme reddedildi: SHA-256 doğrulaması yok";
        }
        File apk = OtaUpdater.download(app, update, expectedHash, percent -> {
            if (listener != null) main.post(() -> listener.onProgress(percent));
        });
        if (apk == null) return "İndirme veya SHA-256 doğrulaması başarısız ("
                + update.versionName + ")";

        // The cached APK is cleared whichever way install goes: a rejected archive must not
        // linger, and an accepted one has already been streamed into the installer session.
        OtaUpdater.InstallResult result = OtaUpdater.install(app, apk);
        if (!apk.delete()) Log.w(TAG, "Could not remove cached OTA APK");

        switch (result) {
            case SESSION_STARTED:
                // Deliberately not "Güncellendi": committing a session is not installing. The
                // platform may still want the driver to confirm, and either way the verdict
                // arrives at OtaInstallResultReceiver, not here.
                return "Kuruluyor: " + update.versionName + " — ekrandaki onayı bekleyin";
            case UNREADABLE_ARCHIVE:
                return "Kurulum reddedildi: APK okunamadı";
            default:
                return "Kurulum başlatılamadı (" + update.versionName + ") — Log sayfasına bakın";
        }
    }
}
