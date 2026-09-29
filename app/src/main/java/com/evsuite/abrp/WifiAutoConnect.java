package com.evsuite.abrp;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/**
 * Brings Wi-Fi up and joins a network the head unit already knows.
 *
 * The point is the phone hotspot: the car has the credentials saved, but the driver still
 * has to walk into Settings and tap the network every time. This does that tap.
 *
 * <b>Why this works here and would not on a newer phone.</b> {@code getConfiguredNetworks}
 * and {@code enableNetwork} were deprecated in API 29 and, from that release, return an
 * empty list and refuse the call for ordinary apps — Android handed network choice to the
 * user through a system picker. This head unit runs Android 9 (API 28), where both are
 * still honoured, and the build is platform-signed, so it is the same UID family the
 * Settings app itself uses. Neither fact is portable: on a Pixel this class would find
 * nothing and change nothing. It is deliberately written to fail quietly if so.
 *
 * Nothing here creates or edits a network. It only enables one the unit was already
 * configured with, which is why no password ever passes through this app.
 */
final class WifiAutoConnect {

    private static final String TAG = "EVABRP.Wifi";

    /** Re-checking faster than this achieves nothing: a join takes seconds to settle. */
    private static final long MIN_ATTEMPT_INTERVAL_MS = 30_000L;

    private final WifiManager wifi;
    private long lastAttemptMs;

    WifiAutoConnect(Context context) {
        this.wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    /** The SSID the unit is associated with, or null when it is not on Wi-Fi. */
    String currentSsid() {
        try {
            if (wifi == null || !wifi.isWifiEnabled()) return null;
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            // The framework quotes the SSID and reports this placeholder when it will not
            // say — usually because location is off. Neither is a network name.
            ssid = ssid.replace("\"", "").trim();
            if (ssid.isEmpty() || "<unknown ssid>".equals(ssid) || "0x".equals(ssid)) return null;
            return ssid;
        } catch (Throwable t) {
            return null;
        }
    }

    boolean isWifiEnabled() {
        try {
            return wifi != null && wifi.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Saved network names, for the UI to show what it can reach. Empty if the OS refuses. */
    List<String> savedNetworkNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            if (wifi == null) return names;
            List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
            if (saved == null) return names;
            for (WifiConfiguration c : saved) {
                if (c == null || c.SSID == null) continue;
                String name = c.SSID.replace("\"", "").trim();
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Saved networks unavailable: permission denied");
        } catch (Throwable t) {
            Log.w(TAG, "getConfiguredNetworks refused: " + t.getMessage());
        }
        return names;
    }

    /**
     * One attempt to get onto a saved network, rate-limited.
     *
     * @param force ignore the rate limit — for the button, which a driver only presses
     *              because the automatic attempt has not worked yet.
     * @return what happened, for the log and the UI. Never null.
     */
    Result ensureConnected(boolean force) {
        if (wifi == null) return Result.UNSUPPORTED;

        String ssid = currentSsid();
        if (ssid != null) return Result.alreadyOn(ssid);

        long now = System.currentTimeMillis();
        if (!force && now - lastAttemptMs < MIN_ATTEMPT_INTERVAL_MS) return Result.WAITING;
        lastAttemptMs = now;

        try {
            if (!wifi.isWifiEnabled()) {
                // Also gone for ordinary apps from API 29, and also still honoured here.
                if (!wifi.setWifiEnabled(true)) return Result.CANNOT_ENABLE;
                // The radio needs a moment before it will associate; the next tick retries.
                return Result.ENABLING;
            }

            List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
            if (saved == null || saved.isEmpty()) return Result.NO_SAVED_NETWORKS;

            // A scan would let us prefer a network that is actually in range, but the results
            // are themselves throttled and location-gated. Asking the supplicant to pick from
            // what it knows is both simpler and what the Settings screen effectively does.
            wifi.reconnect();
            for (WifiConfiguration c : saved) {
                if (c == null) continue;
                // Second argument true: disable the others for this attempt, so the
                // supplicant commits to this one rather than drifting between them.
                if (wifi.enableNetwork(c.networkId, true)) {
                    String name = c.SSID == null ? "?" : c.SSID.replace("\"", "").trim();
                    Log.i(TAG, "enableNetwork accepted for " + name);
                    return Result.attempting(name);
                }
            }
            return Result.REFUSED;
        } catch (SecurityException e) {
            Log.w(TAG, "Wi-Fi connection refused: permission denied");
            return Result.REFUSED;
        } catch (Throwable t) {
            Log.w(TAG, "connect attempt failed: " + t.getMessage());
            return Result.REFUSED;
        }
    }

    /** Outcome of one attempt. {@link #connected} is the only one that means we are online. */
    static final class Result {
        final boolean connected;
        final String ssid;
        final String message;

        private Result(boolean connected, String ssid, String message) {
            this.connected = connected;
            this.ssid = ssid;
            this.message = message;
        }

        static Result alreadyOn(String ssid) { return new Result(true, ssid, "Bağlı: " + ssid); }
        static Result attempting(String ssid) { return new Result(false, ssid, "Bağlanılıyor: " + ssid); }

        static final Result UNSUPPORTED = new Result(false, null, "Wi-Fi yönetilemiyor");
        static final Result WAITING = new Result(false, null, "Bekleniyor…");
        static final Result ENABLING = new Result(false, null, "Wi-Fi açılıyor…");
        static final Result CANNOT_ENABLE = new Result(false, null, "Wi-Fi açılamadı");
        static final Result NO_SAVED_NETWORKS = new Result(false, null, "Kayıtlı ağ yok");
        static final Result REFUSED = new Result(false, null, "Sistem izin vermedi");
    }
}
