package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;

import com.evsuite.hardware.telemetry.EnergySnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent charging session meter and offline upload queue. */
final class ChargeSessionTracker {
    private static final String PREFS = "charging_sessions_v1";
    private static final String ACTIVE = "active";
    private static final String LAST = "last_completed";
    private static final String PENDING = "pending";
    private static final String PRICE = "price_per_kwh";
    private static final long END_GRACE_MS = 60_000L;
    private static final long CURVE_INTERVAL_MS = 60_000L;
    private static final int MAX_CURVE_POINTS = 720;

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
            this.pricePerKwh = pricePerKwh;
            this.totalCost = energyKwh * pricePerKwh;
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
        Float power = snapshot.getBatteryPowerKw();
        Float soc = snapshot.getSocPercent();
        boolean charging = ChargingState.isCharging(snapshot.getChargingStatus(),
                snapshot.getChargePortConnected(), power, snapshot.getSpeedKmh());

        if (charging) {
            endCandidateMs = 0L;
            if (active == null) active = new Session(UUID.randomUUID().toString(), now, soc);
            active.add(now, soc, power);
            persistActive();
            return;
        }
        if (active == null) return;
        if (endCandidateMs == 0L) endCandidateMs = now;
        if (now - endCandidateMs < END_GRACE_MS) return;
        active.finish(endCandidateMs, soc, pricePerKwh());
        JSONObject completed = active.toJson();
        enqueue(completed);
        prefs.edit().putString(LAST, completed.toString()).commit();
        active = null;
        endCandidateMs = 0L;
        prefs.edit().remove(ACTIVE).commit();
    }

    synchronized Snapshot snapshot() {
        Session session = active != null ? active : Session.fromJson(prefs.getString(LAST, null));
        if (session == null) return new Snapshot(false, 0L, 0L, null, null,
                0d, pricePerKwh(), new ArrayList<>());
        long end = session.endedAtMs > 0 ? session.endedAtMs : session.lastSampleMs;
        double price = active != null ? pricePerKwh() : session.pricePerKwh;
        return new Snapshot(active != null, session.startedAtMs,
                Math.max(0L, (end - session.startedAtMs) / 1000L), session.startSoc,
                session.lastSoc, session.energyKwh, price, new ArrayList<>(session.points));
    }

    synchronized boolean setPricePerKwh(double value) {
        if (!Double.isFinite(value) || value < 0d || value > 1_000_000d) return false;
        return prefs.edit().putLong(PRICE, Double.doubleToRawLongBits(value)).commit();
    }

    synchronized double pricePerKwh() {
        return Double.longBitsToDouble(prefs.getLong(PRICE, 0L));
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

    private void enqueue(JSONObject session) {
        JSONArray array = pendingArray();
        array.put(session);
        prefs.edit().putString(PENDING, array.toString()).commit();
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
        long lastSampleMs;
        Float lastSoc;
        Float lastPower;
        double energyKwh;
        long endedAtMs;
        double pricePerKwh;

        Session(String id, long startedAtMs, Float startSoc) {
            this.id = id; this.startedAtMs = startedAtMs; this.startSoc = startSoc;
            this.lastSampleMs = startedAtMs; this.lastSoc = startSoc;
        }

        void add(long now, Float soc, Float power) {
            if (now > lastSampleMs && power != null && lastPower != null) {
                double hours = (now - lastSampleMs) / 3_600_000d;
                energyKwh += Math.max(0d, -(power + lastPower) / 2d) * hours;
            }
            if (now > lastSampleMs) lastSampleMs = now;
            if (soc != null) lastSoc = soc;
            if (power != null) lastPower = power;
            if (power != null && power < ChargingState.CHARGING_POWER_KW &&
                    (points.isEmpty() || now - points.get(points.size() - 1).timestampMs >= CURVE_INTERVAL_MS)) {
                points.add(new Point(now, soc, Math.abs(power)));
                if (points.size() > MAX_CURVE_POINTS) points.remove(0);
            }
        }

        void finish(long endedAtMs, Float finalSoc, double price) {
            this.endedAtMs = endedAtMs;
            if (finalSoc != null) lastSoc = finalSoc;
            pricePerKwh = price;
        }

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
                        .put("endedAt", endedAtMs).put("durationSeconds",
                                Math.max(0L, ((endedAtMs > 0 ? endedAtMs : lastSampleMs) - startedAtMs) / 1000L))
                        .put("energyKwh", energyKwh).put("pricePerKwh", pricePerKwh)
                        .put("totalCost", energyKwh * pricePerKwh).put("curve", curve)
                        .put("lastSampleMs", lastSampleMs);
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
                session.lastSampleMs = json.optLong("lastSampleMs", session.startedAtMs);
                session.lastSoc = json.has("endSocPercent") ? (float) json.getDouble("endSocPercent") : session.startSoc;
                session.lastPower = json.has("lastPower") ? (float) json.getDouble("lastPower") : null;
                session.energyKwh = json.optDouble("energyKwh", 0d);
                session.endedAtMs = json.optLong("endedAt", 0L);
                session.pricePerKwh = json.optDouble("pricePerKwh", 0d);
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
