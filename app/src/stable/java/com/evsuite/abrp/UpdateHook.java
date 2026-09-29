package com.evsuite.abrp;

import android.content.Context;

/**
 * Stable channel: no self-update, by construction.
 *
 * This is not a disabled feature — the updater class is not in the APK at all. A stable
 * build cannot be made to fetch and install code by flipping a preference, and there is
 * no update URL in it to attack.
 */
final class UpdateHook {

    private UpdateHook() { }

    /**
     * Mirrors the unstable seam's shape so shared UI code compiles in both flavours.
     * Nothing implements it here.
     */
    interface Listener {
        default void onProgress(int percent) { }
        void onResult(String message);
    }

    /** Does nothing. Stable users install updates themselves. */
    static void checkInBackground(Context context) { }

    /** Answers immediately that this channel does not self-update. */
    static void checkInBackground(Context context, Listener listener) {
        if (listener != null) listener.onResult("Bu sürümde otomatik güncelleme yok");
    }

    static boolean isSupported() { return false; }
}
