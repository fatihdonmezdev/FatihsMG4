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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Best-effort MongoDB sync through the web API. Never blocks local persistence. */
final class ConsumptionCloudClient {
    private static final String TAG = "FatihsMG4.Mongo";
    private static final long CONSUMPTION_SYNC_INTERVAL_MS = 30 * 60_000L;
    private static final long CHARGE_RETRY_INTERVAL_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String PREFS = "consumption_cloud";
    private static final String INSTALLATION_ID = "installation_id";
    private static final String LAST_CONSUMPTION_DATE = "last_consumption_date";
    private static final int MAX_DAYS_PER_SYNC = 10;
    private static final int MAX_CHARGES_PER_SYNC = 10;

    private final String endpoint;
    private final String token;
    private final String installationId;
    private final SharedPreferences prefs;
    private long lastConsumptionAttemptMs;
    private long lastChargeAttemptMs;
    private String lastAttemptedChargeId = "";

    ConsumptionCloudClient(Context context) {
        endpoint = BuildConfig.CONSUMPTION_API_URL.trim();
        token = BuildConfig.CONSUMPTION_API_TOKEN.trim();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(INSTALLATION_ID, "");
        if (existing == null || existing.isEmpty()) {
            existing = UUID.randomUUID().toString();
            prefs.edit().putString(INSTALLATION_ID, existing).commit();
        }
        installationId = existing;
    }

    /**
     * @param nowMs wall clock, recorded with the payload — the throttle below runs on a
     *              monotonic clock instead, so a backwards NTP correction cannot stall
     *              syncing for however long the jump was.
     */
    void syncIfDue(ConsumptionTracker tracker, ChargeSessionTracker charges, long nowMs) {
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (endpoint.isEmpty() || token.isEmpty()) return;
        syncPendingChargesIfDue(charges, elapsed);
        if (lastConsumptionAttemptMs != 0L
                && elapsed - lastConsumptionAttemptMs < CONSUMPTION_SYNC_INTERVAL_MS) return;
        lastConsumptionAttemptMs = elapsed;
        try {
            syncDailyConsumption(tracker, nowMs);
        } catch (Exception e) {
            Log.w(TAG, "Consumption sync unavailable");
        }
    }

    /** Drains the backlog a few days per cycle; one a cycle took an hour per fortnight. */
    private void syncDailyConsumption(ConsumptionTracker tracker, long nowMs) throws Exception {
        String saved = prefs.getString(LAST_CONSUMPTION_DATE, "");
        LocalDate lastUploaded = null;
        try { if (saved != null && !saved.isEmpty()) lastUploaded = LocalDate.parse(saved); }
        catch (RuntimeException ignored) { }
        for (int sent = 0; sent < MAX_DAYS_PER_SYNC; sent++) {
            LocalDate target = tracker.nextStoredDayAfter(lastUploaded);
            if (target == null) break;
            ConsumptionTracker.CloudSnapshot snapshot = tracker.cloudSnapshot(target);
            JSONObject body = consumptionBody(snapshot, nowMs);
            if (!send("/v1/consumption/daily", "PUT", body)) return;
            prefs.edit().putString(LAST_CONSUMPTION_DATE, target.toString()).commit();
            lastUploaded = target;
        }
        // Today's cumulative counters overwrite the same installation/date document every
        // 30 minutes. Sending totals rather than increments makes retries idempotent.
        ConsumptionTracker.CloudSnapshot today = tracker.cloudSnapshot(LocalDate.now());
        JSONObject current = consumptionBody(today, nowMs);
        send("/v1/consumption/daily", "PUT", current);
    }

    private JSONObject consumptionBody(ConsumptionTracker.CloudSnapshot snapshot, long nowMs)
            throws Exception {
        JSONObject body = new JSONObject()
                .put("installationId", installationId)
                .put("recordedAt", nowMs)
                .put("date", snapshot.date)
                .put("day", totals(snapshot.day))
                .put("lifetime", totals(snapshot.lifetime));
        if (snapshot.sohPercent != null) body.put("sohPercent", snapshot.sohPercent);
        return body;
    }

    private void syncPendingChargesIfDue(ChargeSessionTracker charges, long elapsed) {
        List<JSONObject> pending = charges.pendingUploads();
        if (pending.isEmpty()) return;
        String firstId = pending.get(0).optString("sessionId", "");
        boolean newlyCompleted = !firstId.equals(lastAttemptedChargeId);
        if (!newlyCompleted && lastChargeAttemptMs != 0L
                && elapsed - lastChargeAttemptMs < CHARGE_RETRY_INTERVAL_MS) return;
        lastAttemptedChargeId = firstId;
        lastChargeAttemptMs = elapsed;
        try {
            syncPendingCharges(charges, pending);
        } catch (Exception e) {
            Log.w(TAG, "Charging-session sync unavailable");
        }
    }

    private void syncPendingCharges(ChargeSessionTracker charges, List<JSONObject> pending)
            throws Exception {
        int sent = 0;
        for (JSONObject session : pending) {
            JSONObject body = new JSONObject(session.toString()).put("installationId", installationId);
            String id = session.getString("sessionId");
            if (!send("/v1/charging-sessions/" + id, "PUT", body)) return;
            charges.markUploaded(id);
            if (++sent >= MAX_CHARGES_PER_SYNC) return;
        }
    }

    private boolean send(String path, String method, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(trimSlash(endpoint) + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            InputStream response = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (response != null) try (InputStream ignored = response) { }
            if (status < 200 || status >= 300) {
                Log.w(TAG, "Sync rejected with HTTP " + status);
                return false;
            }
            return true;
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
