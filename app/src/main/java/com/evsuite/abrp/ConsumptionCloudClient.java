package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Best-effort MongoDB sync through the web API. Never blocks local persistence.
 *
 * The installationId is a fixed value baked into BuildConfig — never a random UUID — so an
 * APK wipe cannot orphan the cloud history under a new identity. The consumption UI reads
 * back its own uploaded totals via {@link #fetchHistoryIfDue}, which means an APK reinstall
 * picks up right where the old one left off instead of showing zeroes.
 */
final class ConsumptionCloudClient {
    private static final String TAG = "FatihsMG4.Mongo";
    private static final long CONSUMPTION_SYNC_INTERVAL_MS = 2 * 60_000L;
    private static final long CHARGE_RETRY_INTERVAL_MS = 5 * 60_000L;
    private static final long HISTORY_FETCH_INTERVAL_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final String PREFS = "consumption_cloud";
    private static final String CHUNK_CACHE = "chunk_cache";   // JSON array of pending chunks
    private static final int MAX_CHARGES_PER_SYNC = 10;

    private final String endpoint;
    private final String token;
    private final String installationId;
    private final SharedPreferences prefs;
    private long lastConsumptionAttemptMs;
    private long lastChargeAttemptMs;
    private String lastAttemptedChargeId = "";

    /** Cached history read back from the cloud, refreshed on a 5-minute throttle. */
    private volatile List<DayRecord> cachedHistory = Collections.emptyList();
    private volatile long lastHistoryFetchMs;

    /** One day's uploaded totals, as read back from the cloud. */
    static final class DayRecord {
        final LocalDate date;
        final ConsumptionTracker.Totals day;
        final ConsumptionTracker.Totals lifetime;
        final Float sohPercent;

        DayRecord(LocalDate date, ConsumptionTracker.Totals day, ConsumptionTracker.Totals lifetime, Float sohPercent) {
            this.date = date;
            this.day = day;
            this.lifetime = lifetime;
            this.sohPercent = sohPercent;
        }
    }

    ConsumptionCloudClient(Context context) {
        endpoint = BuildConfig.CONSUMPTION_API_URL.trim();
        token = BuildConfig.CONSUMPTION_API_TOKEN.trim();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        installationId = BuildConfig.CONSUMPTION_INSTALLATION_ID.trim();
        if (endpoint.isEmpty() || token.isEmpty()) {
            Log.w(TAG, "Consumption API not configured: endpoint=" + (endpoint.isEmpty() ? "empty" : "ok")
                    + ", token=" + (token.isEmpty() ? "empty" : "ok"));
        }
    }

    /**
     * Every 30 minutes, takes a delta chunk from the tracker and sends it. Chunks that fail
     * to send (offline, server error) accumulate in a local cache and are retried on the
     * next cycle — so a drive through a dead-signal area still reaches the cloud once the
     * head unit is back on the hotspot. Every attempt is logged to the in-app "Kayıtlar" tab.
     *
     * @param nowMs wall clock, recorded with the payload — the throttle below runs on a
     *              monotonic clock instead, so a backwards NTP correction cannot stall
     *              syncing for however long the jump was.
     */
    void syncIfDue(ConsumptionTracker tracker, ChargeSessionTracker charges, long nowMs) {
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (endpoint.isEmpty() || token.isEmpty()) {
            Log.i(TAG, "Sync skipped: API not configured");
            return;
        }
        syncPendingChargesIfDue(charges, elapsed);

        // Drain any cached chunks first — a backlog from an offline period has priority.
        int cached = drainChunkCache();
        if (cached > 0) {
            logEvent(true, 200, "Cache boşaltıldı: " + cached + " chunk gönderildi");
            invalidateHistory();
        }

        boolean due = lastConsumptionAttemptMs == 0L
                || elapsed - lastConsumptionAttemptMs >= CONSUMPTION_SYNC_INTERVAL_MS;
        if (!due) return;
        lastConsumptionAttemptMs = elapsed;

        ConsumptionTracker.CloudSnapshot chunk = tracker.takeChunkDelta();
        if (chunk == null) {
            Log.i(TAG, "Sync due but no new consumption since last chunk");
            return;  // no driving since the last chunk
        }
        Log.i(TAG, "New chunk for " + chunk.date + ": +" + String.format("%.1f", chunk.day.km)
                + "km, +" + String.format("%.2f", chunk.day.kwh) + "kWh");
        try {
            JSONObject body = consumptionBody(chunk, nowMs);
            if (send("/v1/consumption/daily", "PUT", body)) {
                logEvent(true, 200, "Chunk " + chunk.date + " gönderildi (+"
                        + String.format("%.1f", chunk.day.km) + "km, +"
                        + String.format("%.2f", chunk.day.kwh) + "kWh)");
                invalidateHistory();
            } else {
                cacheChunk(body);
                int pending = pendingChunkCount();
                logEvent(false, 0, "Chunk gönderilemedi — cache'de bekliyor (" + pending + " chunk)");
            }
        } catch (Exception e) {
            // Network failure: cache the chunk for the next cycle.
            try {
                JSONObject body = consumptionBody(chunk, nowMs);
                cacheChunk(body);
            } catch (Exception ignored) { }
            int pending = pendingChunkCount();
            logEvent(false, 0, "Çevrimdışı — cache'de bekliyor (" + pending + " chunk): " + e.getMessage());
            Log.w(TAG, "Chunk sync failed, cached: " + e.getMessage());
        }
    }

    /**
     * Sends every cached chunk; removes each one that succeeds. A chunk that fails stays in
     * the cache for the next attempt, but does NOT stop the drain — the next chunk is tried
     * too, because a 5xx on one day's document is not a reason to hold back the rest.
     */
    private int drainChunkCache() {
        JSONArray cache = loadChunkCache();
        if (cache.length() == 0) return 0;
        JSONArray remaining = new JSONArray();
        int sent = 0;
        int failed = 0;
        for (int i = 0; i < cache.length(); i++) {
            try {
                JSONObject body = cache.getJSONObject(i);
                String date = body.optString("date", "?");
                if (send("/v1/consumption/daily", "PUT", body)) {
                    sent++;
                    Log.i(TAG, "Cached chunk for " + date + " sent");
                } else {
                    // Server rejected — keep it, try the next one too.
                    remaining.put(body);
                    failed++;
                }
            } catch (Exception e) {
                // Network error — keep it, try the next one too.
                try { remaining.put(cache.get(i)); } catch (Exception ignored) { }
                failed++;
            }
        }
        saveChunkCache(remaining);
        if (failed > 0) Log.w(TAG, "Cache drain: " + sent + " sent, " + failed + " still pending");
        return sent;
    }

    private JSONArray loadChunkCache() {
        String raw = prefs.getString(CHUNK_CACHE, "");
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    private void saveChunkCache(JSONArray cache) {
        prefs.edit().putString(CHUNK_CACHE, cache.toString()).commit();
    }

    private void cacheChunk(JSONObject body) {
        JSONArray cache = loadChunkCache();
        cache.put(body);
        saveChunkCache(cache);
    }

    private int pendingChunkCount() {
        return loadChunkCache().length();
    }

    /** Records a cloud-sync attempt to the in-app log (the "Kayıtlar" tab). */
    private void logEvent(boolean success, int httpStatus, String detail) {
        UploadLog log = AbrpUploadService.log();
        if (log == null) return;
        log.record(new UploadLog.Entry(System.currentTimeMillis(), httpStatus, success, detail));
    }

    private JSONObject consumptionBody(ConsumptionTracker.CloudSnapshot snapshot, long nowMs)
            throws Exception {
        JSONObject body = new JSONObject()
                .put("installationId", installationId)
                .put("recordedAt", nowMs)
                .put("date", snapshot.date)
                .put("day", totals(snapshot.day));
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
            logEvent(false, 0, "Şarj session sync hatası: " + e.getMessage());
            Log.w(TAG, "Charging-session sync unavailable: " + e.getMessage());
        }
    }

    private void syncPendingCharges(ChargeSessionTracker charges, List<JSONObject> pending)
            throws Exception {
        Log.i(TAG, "Found " + pending.size() + " pending charge sessions");
        int sent = 0;
        for (JSONObject session : pending) {
            JSONObject body = new JSONObject(session.toString()).put("installationId", installationId);
            String id = session.getString("sessionId");
            Log.i(TAG, "Uploading charge session: " + id);
            if (send("/v1/charging-sessions/" + id, "PUT", body)) {
                charges.markUploaded(id);
                logEvent(true, 200, "Şarj session gönderildi: " + id);
                Log.i(TAG, "Successfully uploaded charge session: " + id);
            } else {
                logEvent(false, 0, "Şarj session gönderilemedi: " + id);
                Log.w(TAG, "Failed to upload charge session: " + id);
                return;
            }
            if (++sent >= MAX_CHARGES_PER_SYNC) return;
        }
    }

    private boolean send(String path, String method, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            String url = trimSlash(endpoint) + path;
            Log.i(TAG, "Sending " + method + " request to " + url + " (" + bytes.length + " bytes)");
            Log.i(TAG, "Body: " + body.toString());
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            Log.i(TAG, "Response: HTTP " + status);
            if (status < 200 || status >= 300) {
                String errBody = readErrorBody(connection);
                Log.w(TAG, "Sync rejected with HTTP " + status + (errBody.isEmpty() ? "" : ": " + errBody));
                return false;
            }
            InputStream response = connection.getInputStream();
            if (response != null) try (InputStream ignored = response) { }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Network error: " + e.getMessage());
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject totals(ConsumptionTracker.Totals totals) throws Exception {
        return new JSONObject().put("km", totals.km).put("kwh", totals.kwh)
                .put("hours", totals.hours).put("socDrop", totals.soc);
    }

    // ---------- Read-back for the consumption UI ----------

    /**
     * Returns the cached cloud history, fetching a fresh copy at most every 5 minutes.
     * Called from the UI poll loop (every 2 s), so the throttle is what keeps the head unit
     * off the network. The fetch itself runs on a background thread — this method is called
     * from the UI thread and a network call there throws NetworkOnMainThreadException.
     * Returns the current cache immediately; the fresh copy lands on the next poll.
     */
    synchronized List<DayRecord> fetchHistoryIfDue() {
        if (endpoint.isEmpty() || token.isEmpty()) return cachedHistory;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (lastHistoryFetchMs != 0L && elapsed - lastHistoryFetchMs < HISTORY_FETCH_INTERVAL_MS) {
            return cachedHistory;
        }
        lastHistoryFetchMs = elapsed;
        new Thread(this::fetchHistoryAsync, "history-fetch").start();
        return cachedHistory;
    }

    private void fetchHistoryAsync() {
        try {
            List<DayRecord> fresh = fetchHistory();
            if (!fresh.isEmpty()) cachedHistory = fresh;
            Log.i(TAG, "Fetched " + fresh.size() + " history records from cloud");
            logEvent(true, 200, "History çekildi: " + fresh.size() + " gün");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w(TAG, "History fetch unavailable: " + msg);
            logEvent(false, 0, "History fetch hatası: " + msg);
        }
    }

    /** Forces a refresh on the next {@link #fetchHistoryIfDue} — call after an upload. */
    void invalidateHistory() {
        lastHistoryFetchMs = 0L;
    }

    /**
     * Immediately attempts to send every cached chunk and take a fresh one, ignoring the
     * 2-minute throttle. Called from the UI "Şimdi yükle" button. Returns a one-line
     * status for the log.
     */
    String flushNow(ConsumptionTracker tracker, long nowMs) {
        if (endpoint.isEmpty() || token.isEmpty()) return "API yapılandırılmamış";
        int cached = drainChunkCache();
        ConsumptionTracker.CloudSnapshot chunk = tracker.takeChunkDelta();
        int total = cached;
        if (chunk != null) {
            try {
                JSONObject body = consumptionBody(chunk, nowMs);
                if (send("/v1/consumption/daily", "PUT", body)) {
                    total++;
                    invalidateHistory();
                } else {
                    cacheChunk(body);
                }
            } catch (Exception e) {
                try { cacheChunk(consumptionBody(chunk, nowMs)); } catch (Exception ignored) { }
            }
        }
        int pending = pendingChunkCount();
        String msg = total > 0
                ? total + " chunk yüklendi" + (pending > 0 ? ", " + pending + " hâlâ bekliyor" : "")
                : (pending > 0 ? pending + " chunk yüklenemedi (internet?)" : "Yüklenecek yeni veri yok");
        logEvent(total > 0, total > 0 ? 200 : 0, "Manuel sync: " + msg);
        return msg;
    }

    private List<DayRecord> fetchHistory() throws Exception {
        HttpURLConnection connection = null;
        try {
            String url = trimSlash(endpoint) + "/v1/consumption/history/" + installationId;
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            Log.i(TAG, "History GET response: HTTP " + status);
            if (status < 200 || status >= 300) {
                String err = readErrorBody(connection);
                Log.w(TAG, "History fetch rejected with HTTP " + status + (err.isEmpty() ? "" : ": " + err));
                logEvent(false, status, "History GET HTTP " + status + (err.isEmpty() ? "" : " — " + err));
                return cachedHistory;
            }
            String body = readBody(connection);
            JSONObject json = new JSONObject(body);
            JSONArray records = json.optJSONArray("records");
            if (records == null) return Collections.emptyList();
            // Lifetime is derived by the backend (sum of all days), sent once at the response
            // level. Each DayRecord carries it so the UI's LIFETIME period can read it.
            ConsumptionTracker.Totals lifetime = json.has("lifetime") && !json.isNull("lifetime")
                    ? parseTotals(json.getJSONObject("lifetime")) : null;
            List<DayRecord> result = new ArrayList<>(records.length());
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.getJSONObject(i);
                LocalDate date = LocalDate.parse(r.getString("date"));
                ConsumptionTracker.Totals day = parseTotals(r.getJSONObject("day"));
                Float soh = r.has("sohPercent") && !r.isNull("sohPercent")
                        ? (float) r.getDouble("sohPercent") : null;
                result.add(new DayRecord(date, day, lifetime, soh));
            }
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static ConsumptionTracker.Totals parseTotals(JSONObject o) {
        return new ConsumptionTracker.Totals(
                o.optDouble("km", 0d), o.optDouble("kwh", 0d),
                o.optDouble("hours", 0d), o.optDouble("socDrop", 0d));
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getInputStream();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /** Reads the error response body (truncated) for logging — never throws. */
    private static String readErrorBody(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream();
            if (stream == null) return "";
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String body = sb.toString();
                return body.length() > 300 ? body.substring(0, 300) + "…" : body;
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
