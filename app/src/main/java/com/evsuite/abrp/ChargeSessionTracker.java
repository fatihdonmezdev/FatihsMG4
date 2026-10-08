package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.evsuite.hardware.telemetry.EnergySnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent DC charging session meter and offline upload queue.
 *
 * Only DC is metered — see {@link ChargingState#DC_MIN_KW}. Energy and duration are
 * integrated on {@link SystemClock#elapsedRealtime()} for the same reason
 * {@link ConsumptionTracker} is: the head unit's wall clock jumps when it picks up GPS or
 * NTP time, and one forward jump mid-charge used to invent both kWh and hours. Wall-clock
 * values are still recorded as the session's start and end, because that is what a phone
 * reading the database needs to place it on a timeline — but nothing is measured from them.
 */
final class ChargeSessionTracker {
    static final double CHARGING_LOSS_PERCENT = 10d;
    static final double GRID_ENERGY_FACTOR = 1d + CHARGING_LOSS_PERCENT / 100d;
    private static final String PREFS = "charging_sessions_v1";
    private static final String ACTIVE = "active";
    private static final String LAST = "last_completed";
    private static final String PENDING = "pending";
    private static final String PRICE = "price_per_kwh";
    private static final long END_GRACE_MS = 60_000L;
    private static final long CURVE_INTERVAL_MS = 60_000L;
    private static final int MAX_CURVE_POINTS = 720;

    /**
     * Longest interval still integrated, in hours (5 minutes against a 15-second tick).
     *
     * A head unit that slept or a service that was killed comes back with an arbitrarily
     * large gap. Charging almost certainly stopped somewhere inside it, so bridging it
     * would bill the whole gap at the last power reading. The gap is dropped instead.
     */
    private static final double MAX_STEP_HOURS = 5d / 60d;

    /** Sessions kept while offline. Each carries a curve, so the queue is not free. */
    private static final int MAX_PENDING = 50;

    private static ChargeSessionTracker instance;
    static synchronized ChargeSessionTracker get(Context context) {
        if (instance == null) instance = new ChargeSessionTracker(context.getApplicationContext());
        return instance;
    }

    static final class Point {
        final long timestampMs;
        final Float socPercent;
        final float powerKw;
        Point(long timestampMs, Float socPercent, float powerKw) {
            this.timestampMs = timestampMs; this.socPercent = socPercent; this.powerKw = powerKw;
        }
    }

    static final class Snapshot {
        final boolean charging;
        final long startedAtMs;
        final long durationSeconds;
        final Float startSocPercent;
        final Float currentSocPercent;
        final double energyKwh;
        final double gridEnergyKwh;
        final double pricePerKwh;
        final double totalCost;
        final List<Point> points;

        Snapshot(boolean charging, long startedAtMs, long durationSeconds,
                 Float startSocPercent, Float currentSocPercent, double energyKwh,
                 double pricePerKwh, List<Point> points) {
            this.charging = charging;
            this.startedAtMs = startedAtMs;
            this.durationSeconds = durationSeconds;
            this.startSocPercent = startSocPercent;
            this.currentSocPercent = currentSocPercent;
            this.energyKwh = energyKwh;
            this.gridEnergyKwh = gridEnergyKwh(energyKwh);
            this.pricePerKwh = pricePerKwh;
            this.totalCost = totalCost(energyKwh, pricePerKwh);
            this.points = points;
        }
    }

    private final SharedPreferences prefs;
    private Session active;
    private long endCandidateMs;

    private ChargeSessionTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        active = Session.fromJson(prefs.getString(ACTIVE, null));
    }

    synchronized void sample(EnergySnapshot snapshot) {
        long now = snapshot.getTimestampMs();
        long elapsed = SystemClock.elapsedRealtime();
        Float power = snapshot.getBatteryPowerKw();
        Float soc = snapshot.getSocPercent();
        boolean charging = ChargingState.isDcCharging(snapshot.getChargingStatus(),
                snapshot.getChargePortConnected(), power, snapshot.getSpeedKmh());

        if (charging) {
            endCandidateMs = 0L;
            if (active == null) active = new Session(UUID.randomUUID().toString(), now, soc);
            active.add(now, elapsed, soc, power);
            persistActive();
            return;
        }
        if (active == null) return;
        if (endCandidateMs == 0L) endCandidateMs = elapsed;
        if (elapsed - endCandidateMs < END_GRACE_MS) return;
        active.finish(now, soc, pricePerKwh());
        JSONObject completed = active.toJson();
        enqueue(completed);
        prefs.edit().putString(LAST, completed.toString()).remove(ACTIVE).commit();
        active = null;
        endCandidateMs = 0L;
    }

    synchronized Snapshot snapshot() {
        Session session = active != null ? active : Session.fromJson(prefs.getString(LAST, null));
        if (session == null) return new Snapshot(false, 0L, 0L, null, null,
                0d, pricePerKwh(), new ArrayList<>());
        double price = active != null ? pricePerKwh() : session.pricePerKwh;
        return new Snapshot(active != null, session.startedAtMs, session.activeSeconds(),
                session.startSoc, session.lastSoc, session.energyKwh, price,
                new ArrayList<>(session.points));
    }

    synchronized boolean setPricePerKwh(double value) {
        if (!Double.isFinite(value) || value < 0d || value > 1_000_000d) return false;
        return prefs.edit().putLong(PRICE, Double.doubleToRawLongBits(value)).commit();
    }

    synchronized double pricePerKwh() {
        return Double.longBitsToDouble(prefs.getLong(PRICE, 0L));
    }

    static double gridEnergyKwh(double batteryEnergyKwh) {
        return batteryEnergyKwh * GRID_ENERGY_FACTOR;
    }

    static double totalCost(double batteryEnergyKwh, double pricePerKwh) {
        return gridEnergyKwh(batteryEnergyKwh) * pricePerKwh;
    }

    synchronized List<JSONObject> pendingUploads() {
        JSONArray array = pendingArray();
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) result.add(item);
        }
        return result;
    }

    synchronized void markUploaded(String sessionId) {
        JSONArray old = pendingArray();
        JSONArray kept = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && !sessionId.equals(item.optString("sessionId"))) kept.put(item);
        }
        prefs.edit().putString(PENDING, kept.toString()).commit();
    }

    private void persistActive() {
        prefs.edit().putString(ACTIVE, active.toJson().toString()).commit();
    }

    /**
     * Appends a completed session, dropping the oldest once the queue is full.
     *
     * Unbounded, this grows forever whenever the cloud endpoint is unconfigured or
     * unreachable — and it is one SharedPreferences string, read into memory in full.
     */
    private void enqueue(JSONObject session) {
        JSONArray array = pendingArray();
        array.put(session);
        JSONArray kept = array;
        if (array.length() > MAX_PENDING) {
            kept = new JSONArray();
            for (int i = array.length() - MAX_PENDING; i < array.length(); i++) kept.put(array.opt(i));
        }
        prefs.edit().putString(PENDING, kept.toString()).commit();
    }

    private JSONArray pendingArray() {
        try { return new JSONArray(prefs.getString(PENDING, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static final class Session {
        final String id;
        final long startedAtMs;
        final Float startSoc;
        final List<Point> points = new ArrayList<>();
        Float lastSoc;
        Float lastPower;
        double energyKwh;
        double meteredSeconds;
        long endedAtMs;
        double pricePerKwh;
        long curveIntervalMs = CURVE_INTERVAL_MS;

        /**
         * Monotonic anchor of the previous sample, or 0 when there is none.
         *
         * Never persisted: {@code elapsedRealtime} is only comparable within one boot, so
         * a session restored from disk starts a fresh interval rather than integrating
         * against a reading from before the restart.
         */
        private long lastElapsedMs;

        Session(String id, long startedAtMs, Float startSoc) {
            this.id = id; this.startedAtMs = startedAtMs; this.startSoc = startSoc;
            this.lastSoc = startSoc;
        }

        void add(long nowMs, long elapsedMs, Float soc, Float power) {
            long previous = lastElapsedMs;
            lastElapsedMs = elapsedMs;
            double hours = previous > 0L ? (elapsedMs - previous) / 3_600_000d : 0d;
            if (hours > 0d && hours <= MAX_STEP_HOURS) {
                meteredSeconds += hours * 3600d;
                if (power != null && lastPower != null) {
                    energyKwh += Math.max(0d, -(power + lastPower) / 2d) * hours;
                }
            }
            if (soc != null) lastSoc = soc;
            if (power != null) lastPower = power;
            if (power != null && -power >= ChargingState.DC_MIN_KW &&
                    (points.isEmpty() || nowMs - points.get(points.size() - 1).timestampMs >= curveIntervalMs)) {
                points.add(new Point(nowMs, soc, Math.abs(power)));
                decimateIfFull();
            }
        }

        /**
         * Halves the sample rate instead of dropping the oldest point.
         *
         * Dropping the head cost the beginning of the curve — on a long charge the part
         * that actually has shape. Thinning keeps the whole session, at lower resolution.
         */
        private void decimateIfFull() {
            if (points.size() <= MAX_CURVE_POINTS) return;
            for (int i = points.size() - 2; i >= 0; i -= 2) points.remove(i);
            curveIntervalMs *= 2;
        }

        void finish(long endedAtMs, Float finalSoc, double price) {
            this.endedAtMs = endedAtMs;
            if (finalSoc != null) lastSoc = finalSoc;
            pricePerKwh = price;
        }

        /** Metered charging time, not wall-clock span: a dropped gap is not charging. */
        long activeSeconds() { return Math.max(0L, Math.round(meteredSeconds)); }

        JSONObject toJson() {
            try {
                JSONArray curve = new JSONArray();
                for (Point point : points) {
                    JSONObject p = new JSONObject().put("timestampMs", point.timestampMs)
                            .put("powerKw", point.powerKw);
                    if (point.socPercent != null) p.put("socPercent", point.socPercent);
                    curve.put(p);
                }
                JSONObject json = new JSONObject().put("sessionId", id).put("startedAt", startedAtMs)
                        .put("endedAt", endedAtMs).put("durationSeconds", activeSeconds())
                        .put("energyKwh", energyKwh)
                        .put("chargingLossPercent", CHARGING_LOSS_PERCENT)
                        .put("gridEnergyKwh", gridEnergyKwh(energyKwh))
                        .put("pricePerKwh", pricePerKwh)
                        .put("totalCost", totalCost(energyKwh, pricePerKwh))
                        .put("curve", curve)
                        .put("meteredSeconds", meteredSeconds)
                        .put("curveIntervalMs", curveIntervalMs);
                if (startSoc != null) json.put("startSocPercent", startSoc);
                if (lastSoc != null) json.put("endSocPercent", lastSoc);
                if (lastPower != null) json.put("lastPower", lastPower);
                return json;
            } catch (org.json.JSONException e) {
                throw new IllegalStateException("Could not serialize charging session", e);
            }
        }

        static Session fromJson(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            try {
                JSONObject json = new JSONObject(raw);
                Session session = new Session(json.getString("sessionId"), json.getLong("startedAt"),
                        json.has("startSocPercent") ? (float) json.getDouble("startSocPercent") : null);
                session.lastSoc = json.has("endSocPercent") ? (float) json.getDouble("endSocPercent") : session.startSoc;
                session.lastPower = json.has("lastPower") ? (float) json.getDouble("lastPower") : null;
                session.energyKwh = json.optDouble("energyKwh", 0d);
                session.meteredSeconds = json.optDouble("meteredSeconds", json.optLong("durationSeconds", 0L));
                session.endedAtMs = json.optLong("endedAt", 0L);
                session.pricePerKwh = json.optDouble("pricePerKwh", 0d);
                session.curveIntervalMs = json.optLong("curveIntervalMs", CURVE_INTERVAL_MS);
                JSONArray curve = json.optJSONArray("curve");
                if (curve != null) for (int i = 0; i < curve.length(); i++) {
                    JSONObject p = curve.getJSONObject(i);
                    session.points.add(new Point(p.getLong("timestampMs"),
                            p.has("socPercent") ? (float) p.getDouble("socPercent") : null,
                            (float) p.getDouble("powerKw")));
                }
                return session;
            } catch (Exception ignored) { return null; }
        }
    }
}
