package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Best-effort MongoDB sync through the API in server/. Never blocks local persistence. */
final class ConsumptionCloudClient {
    private static final String TAG = "FatihsMG4.Mongo";
    private static final long SYNC_INTERVAL_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String PREFS = "consumption_cloud";
    private static final String INSTALLATION_ID = "installation_id";

    private final String endpoint;
    private final String token;
    private final String installationId;
    private long lastAttemptMs;

    ConsumptionCloudClient(Context context) {
        endpoint = BuildConfig.CONSUMPTION_API_URL.trim();
        token = BuildConfig.CONSUMPTION_API_TOKEN.trim();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(INSTALLATION_ID, "");
        if (existing == null || existing.isEmpty()) {
            existing = UUID.randomUUID().toString();
            prefs.edit().putString(INSTALLATION_ID, existing).commit();
        }
        installationId = existing;
    }

    void syncIfDue(ConsumptionTracker.CloudSnapshot snapshot, long nowMs) {
        if (endpoint.isEmpty() || token.isEmpty() || nowMs - lastAttemptMs < SYNC_INTERVAL_MS) return;
        lastAttemptMs = nowMs;
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject()
                    .put("installationId", installationId)
                    .put("recordedAt", nowMs)
                    .put("lifetime", totals(snapshot.lifetime));
            if (snapshot.sohPercent != null) body.put("sohPercent", snapshot.sohPercent);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(trimSlash(endpoint) + "/v1/consumption").openConnection();
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            try (InputStream ignored = status < 400
                    ? connection.getInputStream() : connection.getErrorStream()) { }
            if (status < 200 || status >= 300) Log.w(TAG, "Sync rejected with HTTP " + status);
        } catch (Exception e) {
            Log.w(TAG, "Consumption sync unavailable");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject totals(ConsumptionTracker.Totals totals) throws Exception {
        return new JSONObject().put("km", totals.km).put("kwh", totals.kwh)
                .put("hours", totals.hours).put("socDrop", totals.soc);
    }

    private static String trimSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
