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
 * Persistent AC and DC charging session meter and offline upload queue.
 *
 * AC charging loss varies by current:
 *  - 6A  -> 21% loss
 *  - 10A -> 11% loss
 *  - 16A -> 5% loss (linear interpolation between breakpoints)
 *  - DC  -> 5% loss
 */
final class ChargeSessionTracker {
    static final double BATTERY_USABLE_KWH = 47.0d;
    static final double BATTERY_NOMINAL_KWH = 51.0d;
    static final float AC_DC_THRESHOLD_KW = ChargingState.AC_DC_THRESHOLD_KW; // 8.0 kW

    private static final String PREFS = "charging_sessions_v1";
    private static final String ACTIVE = "active";
    private static final String LAST = "last_completed";
    private static final String LAST_AC = "last_completed_ac";
    private static final String LAST_DC = "last_completed_dc";
    private static final String PENDING = "pending";
    private static final String PRICE = "price_per_kwh";
    private static final String PRICE_AC = "price_ac_per_kwh";
    private static final String PRICE_DC = "price_dc_per_kwh";
    private static final String PREF_LAST_KNOWN_SOC = "last_known_soc";
    private static final String PREF_LAST_KNOWN_SOC_TIME = "last_known_soc_time";
    private static final long END_GRACE_MS = 60_000L;
    private static final long CURVE_INTERVAL_MS = 60_000L;
    private static final int MAX_CURVE_POINTS = 720;
    private static final double MAX_STEP_HOURS = 5d / 60d;
    private static final int MAX_PENDING = 50;

    private static ChargeSessionTracker instance;
    static synchronized ChargeSessionTracker get(Context context) {
        if (instance == null) instance = new ChargeSessionTracker(context.getApplicationContext());
        return instance;
    }

    /**
     * AC charging loss calibrated from charger screen observations:
     * 6A -> 21%, 10A -> 11%, 16A -> 5%.
     */
    static double calculateAcLossPercent(double currentAmps) {
        if (currentAmps <= 0.0) return 10.0;
        if (currentAmps <= 6.0) return 21.0;
        if (currentAmps <= 10.0) {
            // (11 - 21) / (10 - 6) = -2.5% per A
            return 21.0 - (currentAmps - 6.0) * 2.5;
        }
        if (currentAmps <= 16.0) {
            // (5 - 11) / (16 - 10) = -1.0% per A
            return 11.0 - (currentAmps - 10.0) * 1.0;
        }
        return 5.0; // >= 16A (e.g. 32A 7kW)
    }

    static double calculateLossPercent(String type, Float powerKw, Float currentA) {
        if ("DC".equalsIgnoreCase(type) || (powerKw != null && Math.abs(powerKw) >= AC_DC_THRESHOLD_KW)) {
            return 5.0;
        }
        double amps = 0.0;
        if (currentA != null && currentA > 0.5f) {
            amps = currentA;
        } else if (powerKw != null && Math.abs(powerKw) > 0.1f) {
            amps = (Math.abs(powerKw) * 1000.0) / 230.0;
        } else {
            amps = 10.0;
        }
        return calculateAcLossPercent(amps);
    }

    static double gridEnergyKwh(double batteryEnergyKwh, double lossPercent) {
        return batteryEnergyKwh * (1.0 + (lossPercent / 100.0));
    }

    static double gridEnergyKwh(double batteryEnergyKwh) {
        return gridEnergyKwh(batteryEnergyKwh, 10.0);
    }

    static double totalCost(double gridEnergyKwh, double pricePerKwh) {
        return gridEnergyKwh * pricePerKwh;
    }

    static double totalCost(double batteryEnergyKwh, double lossPercent, double pricePerKwh) {
        return gridEnergyKwh(batteryEnergyKwh, lossPercent) * pricePerKwh;
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
        final double stationEnergyKwh;
        final double gridEnergyKwh;
        final double lossPercent;
        final double pricePerKwh;
        final double totalCost;
        final String chargingType;
        final Double latitude;
        final Double longitude;
        final List<Point> points;

        Snapshot(boolean charging, long startedAtMs, long durationSeconds,
                 Float startSocPercent, Float currentSocPercent, double energyKwh,
                 double stationEnergyKwh, double gridEnergyKwh,
                 double lossPercent, double pricePerKwh, String chargingType,
                 Double latitude, Double longitude, List<Point> points) {
            this.charging = charging;
            this.startedAtMs = startedAtMs;
            this.durationSeconds = durationSeconds;
            this.startSocPercent = startSocPercent;
            this.currentSocPercent = currentSocPercent;
            this.energyKwh = energyKwh;
            this.stationEnergyKwh = stationEnergyKwh;
            this.gridEnergyKwh = gridEnergyKwh;
            this.lossPercent = lossPercent;
            this.pricePerKwh = pricePerKwh;
            this.totalCost = totalCost(this.gridEnergyKwh, pricePerKwh);
            this.chargingType = chargingType != null ? chargingType : "AC";
            this.latitude = latitude;
            this.longitude = longitude;
            this.points = points;
        }

        Snapshot(boolean charging, long startedAtMs, long durationSeconds,
                 Float startSocPercent, Float currentSocPercent, double energyKwh,
                 double lossPercent, double pricePerKwh, String chargingType,
                 Double latitude, Double longitude, List<Point> points) {
            this(charging, startedAtMs, durationSeconds, startSocPercent, currentSocPercent,
                    energyKwh, 0d, gridEnergyKwh(energyKwh, lossPercent), lossPercent, pricePerKwh,
                    chargingType, latitude, longitude, points);
        }

        Snapshot(boolean charging, long startedAtMs, long durationSeconds,
                 Float startSocPercent, Float currentSocPercent, double energyKwh,
                 double pricePerKwh, List<Point> points) {
            this(charging, startedAtMs, durationSeconds, startSocPercent, currentSocPercent,
                    energyKwh, 0d, gridEnergyKwh(energyKwh, 10.0), 10.0, pricePerKwh,
                    "AC", null, null, points);
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private Session active;
    private long endCandidateMs;

    private ChargeSessionTracker(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        active = Session.fromJson(prefs.getString(ACTIVE, null));
    }

    synchronized void sample(EnergySnapshot snapshot) {
        long now = snapshot.getTimestampMs();
        long elapsed = SystemClock.elapsedRealtime();
        Float power = snapshot.getBatteryPowerKw();
        Float stationPower = snapshot.getStationPowerKw();
        Float soc = snapshot.getSocPercent();
        Integer status = snapshot.getChargingStatus();
        Boolean portConnected = snapshot.getChargePortConnected();
        Float speed = snapshot.getSpeedKmh();
        Float currentA = snapshot.getBatteryCurrentA();

        boolean charging = ChargingState.isCharging(status, portConnected, power, speed);

        if (charging) {
            endCandidateMs = 0L;
            if (active == null) {
                float absP = power != null ? Math.abs(power) : 0f;
                String type = ChargingState.chargingType(power, status);
                Double lat = VehicleLocationProvider.get(context).getLatitude();
                Double lon = VehicleLocationProvider.get(context).getLongitude();
                active = new Session(UUID.randomUUID().toString(), now, soc, absP, type, lat, lon);
            }
            active.add(now, elapsed, soc, power, currentA, stationPower, status, context);
            persistActive();
            if (soc != null && soc > 0f) {
                saveLastKnownSoc(soc, now);
            }
            return;
        }

        if (active == null) {
            if (soc != null && soc > 0f) {
                checkOfflineCharge(now, soc, speed);
                saveLastKnownSoc(soc, now);
            }
            return;
        }

        boolean isMoving = speed != null && Math.abs(speed) > ConsumptionMath.SPEED_DEAD_BAND_KMH;
        boolean longGap = (active.lastElapsedMs > 0L && (elapsed - active.lastElapsedMs > 120_000L || elapsed < active.lastElapsedMs));

        if (endCandidateMs == 0L) endCandidateMs = elapsed;

        if (!isMoving && !longGap && (elapsed - endCandidateMs < END_GRACE_MS)) {
            return;
        }

        finalizeSessionInternal(now, soc);
    }

    synchronized boolean finalizeActiveIfEnded(long nowMs) {
        if (active == null) return false;
        finalizeSessionInternal(nowMs, active.lastSoc);
        return true;
    }

    private void finalizeSessionInternal(long now, Float soc) {
        if (active == null) return;
        double price = priceForType(active.chargingType);
        Float finalSoc = (soc != null && soc > 0f) ? soc : active.lastSoc;
        active.finish(now, finalSoc, price);
        JSONObject completed = active.toJson();
        enqueue(completed);

        String typeKey = "DC".equalsIgnoreCase(active.chargingType) ? LAST_DC : LAST_AC;
        prefs.edit()
                .putString(LAST, completed.toString())
                .putString(typeKey, completed.toString())
                .remove(ACTIVE)
                .commit();

        if (finalSoc != null && finalSoc > 0f) {
            saveLastKnownSoc(finalSoc, now);
        }

        active = null;
        endCandidateMs = 0L;
    }

    private void checkOfflineCharge(long now, float currentSoc, Float speed) {
        if (speed != null && Math.abs(speed) > ConsumptionMath.SPEED_DEAD_BAND_KMH) return;
        float lastSoc = prefs.getFloat(PREF_LAST_KNOWN_SOC, -1f);
        long lastTime = prefs.getLong(PREF_LAST_KNOWN_SOC_TIME, 0L);
        if (lastSoc > 0f && currentSoc >= lastSoc + 3.0f) {
            float deltaSoc = currentSoc - lastSoc;
            double energyKwh = (deltaSoc / 100.0) * BATTERY_USABLE_KWH;
            double price = priceAcPerKwh();
            long durationMs = Math.round((energyKwh / 6.6) * 3600_000.0);
            long startTs = lastTime > 0 ? Math.min(lastTime, now - durationMs) : (now - durationMs);

            Session synthetic = new Session(UUID.randomUUID().toString(), startTs, lastSoc, 6.6f, "AC", null, null);
            synthetic.finish(now, currentSoc, price);
            JSONObject completed = synthetic.toJson();
            enqueue(completed);

            prefs.edit()
                    .putString(LAST, completed.toString())
                    .putString(LAST_AC, completed.toString())
                    .commit();
        }
    }

    private void saveLastKnownSoc(float soc, long now) {
        prefs.edit()
                .putFloat(PREF_LAST_KNOWN_SOC, soc)
                .putLong(PREF_LAST_KNOWN_SOC_TIME, now)
                .apply();
    }

    synchronized Snapshot snapshot(String filterType) {
        Session session = null;
        if (active != null) {
            if (filterType == null || filterType.equalsIgnoreCase(active.chargingType)) {
                session = active;
            }
        }
        if (session == null) {
            if ("AC".equalsIgnoreCase(filterType)) {
                session = Session.fromJson(prefs.getString(LAST_AC, null));
                if (session != null && !"AC".equalsIgnoreCase(session.chargingType)) {
                    session = null;
                }
                if (session == null) {
                    Session last = Session.fromJson(prefs.getString(LAST, null));
                    if (last != null && "AC".equalsIgnoreCase(last.chargingType)) {
                        session = last;
                    }
                }
            } else if ("DC".equalsIgnoreCase(filterType)) {
                session = Session.fromJson(prefs.getString(LAST_DC, null));
                if (session != null && !"DC".equalsIgnoreCase(session.chargingType)) {
                    session = null;
                }
                if (session == null) {
                    Session last = Session.fromJson(prefs.getString(LAST, null));
                    if (last != null && "DC".equalsIgnoreCase(last.chargingType)) {
                        session = last;
                    }
                }
            } else {
                session = Session.fromJson(prefs.getString(LAST, null));
                if (session == null) session = Session.fromJson(prefs.getString(LAST_AC, null));
                if (session == null) session = Session.fromJson(prefs.getString(LAST_DC, null));
            }
        }

        if (session == null) {
            String defaultType = filterType != null ? filterType.toUpperCase() : "AC";
            return new Snapshot(false, 0L, 0L, null, null,
                    0d, 0d, 0d, 10.0, priceForType(defaultType), defaultType, null, null, new ArrayList<>());
        }

        boolean isActive = active != null && active.id.equals(session.id);
        double price = isActive ? priceForType(session.chargingType) : session.pricePerKwh;
        double effGrid = session.effectiveGridKwh();
        double effLoss = session.effectiveLossPercent();
        return new Snapshot(isActive, session.startedAtMs, session.activeSeconds(),
                session.startSoc, session.lastSoc, session.energyKwh, session.stationEnergyKwh,
                effGrid, effLoss, price, session.chargingType, session.latitude, session.longitude,
                new ArrayList<>(session.points));
    }

    synchronized Snapshot snapshot() {
        return snapshot(null);
    }

    synchronized double priceAcPerKwh() {
        long bits = prefs.getLong(PRICE_AC, 0L);
        if (bits != 0L) return Double.longBitsToDouble(bits);
        long legacy = prefs.getLong(PRICE, 0L);
        return legacy != 0L ? Double.longBitsToDouble(legacy) : 3.50d;
    }

    synchronized double priceDcPerKwh() {
        long bits = prefs.getLong(PRICE_DC, 0L);
        if (bits != 0L) return Double.longBitsToDouble(bits);
        long legacy = prefs.getLong(PRICE, 0L);
        return legacy != 0L ? Double.longBitsToDouble(legacy) : 8.50d;
    }

    synchronized boolean setPriceAcPerKwh(double value) {
        if (!Double.isFinite(value) || value < 0d || value > 1_000_000d) return false;
        return prefs.edit().putLong(PRICE_AC, Double.doubleToRawLongBits(value)).commit();
    }

    synchronized boolean setPriceDcPerKwh(double value) {
        if (!Double.isFinite(value) || value < 0d || value > 1_000_000d) return false;
        return prefs.edit().putLong(PRICE_DC, Double.doubleToRawLongBits(value)).commit();
    }

    synchronized boolean setPricePerKwh(double value) {
        return setPriceAcPerKwh(value) && setPriceDcPerKwh(value);
    }

    synchronized double pricePerKwh() {
        return priceDcPerKwh();
    }

    synchronized double priceForType(String chargingType) {
        return "AC".equalsIgnoreCase(chargingType) ? priceAcPerKwh() : priceDcPerKwh();
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
        Float initialPowerKw;
        String chargingType;
        Double latitude;
        Double longitude;
        Float lastSoc;
        Float lastPower;
        Float lastCurrentA;
        Float lastStationPower;
        double energyKwh;
        double stationEnergyKwh;
        double meteredSeconds;
        long endedAtMs;
        double pricePerKwh;
        double lossPercent = 10.0;
        long curveIntervalMs = CURVE_INTERVAL_MS;
        long lastElapsedMs;

        Session(String id, long startedAtMs, Float startSoc, Float initialPowerKw,
                String chargingType, Double latitude, Double longitude) {
            this.id = id;
            this.startedAtMs = startedAtMs;
            this.startSoc = startSoc;
            this.initialPowerKw = initialPowerKw;
            this.chargingType = chargingType != null ? chargingType : "AC";
            this.latitude = latitude;
            this.longitude = longitude;
            this.lastSoc = startSoc;
            this.lossPercent = calculateLossPercent(this.chargingType, initialPowerKw, null);
        }

        void add(long nowMs, long elapsedMs, Float soc, Float power, Float currentA, Float stationPower, Integer status, Context ctx) {
            long previous = lastElapsedMs;
            lastElapsedMs = elapsedMs;
            double hours = previous > 0L ? (elapsedMs - previous) / 3_600_000d : 0d;
            if (hours > 0d && hours <= MAX_STEP_HOURS) {
                meteredSeconds += hours * 3600d;
                if (power != null && lastPower != null) {
                    energyKwh += Math.max(0d, -(power + lastPower) / 2d) * hours;
                }
                if (stationPower != null && lastStationPower != null) {
                    stationEnergyKwh += Math.max(0d, (stationPower + lastStationPower) / 2d) * hours;
                } else if (stationPower != null && stationEnergyKwh == 0d) {
                    stationEnergyKwh += Math.max(0d, stationPower) * hours;
                }
            }
            if (soc != null) lastSoc = soc;
            if (currentA != null && Math.abs(currentA) > 0.5f) {
                lastCurrentA = Math.abs(currentA);
            }
            if (stationPower != null) {
                lastStationPower = stationPower;
            }
            if (power != null) {
                lastPower = power;
                float absP = Math.abs(power);
                if (initialPowerKw == null || initialPowerKw < 0.1f) {
                    initialPowerKw = absP;
                }
                if (absP >= AC_DC_THRESHOLD_KW || (status != null && status == 10)) {
                    chargingType = "DC";
                }
            }
            this.lossPercent = calculateLossPercent(chargingType, initialPowerKw, lastCurrentA);

            if ((latitude == null || longitude == null) && ctx != null) {
                Double lat = VehicleLocationProvider.get(ctx).getLatitude();
                Double lon = VehicleLocationProvider.get(ctx).getLongitude();
                if (lat != null && lon != null) {
                    latitude = lat;
                    longitude = lon;
                }
            }
            if (power != null && -power >= 0.2f &&
                    (points.isEmpty() || nowMs - points.get(points.size() - 1).timestampMs >= curveIntervalMs)) {
                points.add(new Point(nowMs, soc, Math.abs(power)));
                decimateIfFull();
            }
        }

        double effectiveGridKwh() {
            if (stationEnergyKwh > 0.05d && stationEnergyKwh >= energyKwh) {
                return stationEnergyKwh;
            }
            return gridEnergyKwh(energyKwh, lossPercent);
        }

        double effectiveLossPercent() {
            if (stationEnergyKwh > 0.05d && energyKwh > 0.05d && stationEnergyKwh >= energyKwh) {
                return ((stationEnergyKwh - energyKwh) / energyKwh) * 100d;
            }
            return lossPercent;
        }

        private void decimateIfFull() {
            if (points.size() <= MAX_CURVE_POINTS) return;
            for (int i = points.size() - 2; i >= 0; i -= 2) points.remove(i);
            curveIntervalMs *= 2;
        }

        void finish(long endedAtMs, Float finalSoc, double price) {
            this.endedAtMs = endedAtMs;
            if (finalSoc != null) lastSoc = finalSoc;
            pricePerKwh = price;

            this.lossPercent = calculateLossPercent(chargingType, initialPowerKw, lastCurrentA);

            // Handle head unit sleep during charging:
            // Calculate battery-side energy using 47.0 kWh usable capacity.
            if (startSoc != null && lastSoc != null && lastSoc > startSoc) {
                float deltaSoc = lastSoc - startSoc;
                double socEnergyKwh = (deltaSoc / 100.0) * BATTERY_USABLE_KWH;
                if (socEnergyKwh > this.energyKwh) {
                    this.energyKwh = socEnergyKwh;
                }
            }

            // Estimate duration if sleep prevented full integration:
            float pRef = (initialPowerKw != null && initialPowerKw > 0.5f) ? initialPowerKw
                    : (lastPower != null && Math.abs(lastPower) > 0.5f) ? Math.abs(lastPower)
                    : ("DC".equals(chargingType) ? 40.0f : 6.6f);

            double estSeconds = (pRef > 0.5f && energyKwh > 0.0) ? (energyKwh / pRef) * 3600.0 : 0.0;
            double wallSeconds = (endedAtMs > startedAtMs) ? (endedAtMs - startedAtMs) / 1000.0 : 0.0;

            if (estSeconds > meteredSeconds) {
                if (wallSeconds > 0) {
                    meteredSeconds = Math.min(wallSeconds, Math.max(meteredSeconds, estSeconds));
                } else {
                    meteredSeconds = Math.max(meteredSeconds, estSeconds);
                }
            }

            fillSyntheticPointsIfNeeded(pRef);
        }

        private void fillSyntheticPointsIfNeeded(float pRef) {
            if (energyKwh <= 0.0 || startSoc == null || lastSoc == null || lastSoc <= startSoc) return;
            long spanMs = Math.round(meteredSeconds * 1000d);
            if (spanMs <= 0) return;

            long lastPointTs = points.isEmpty() ? startedAtMs : points.get(points.size() - 1).timestampMs;
            if (endedAtMs - lastPointTs > 10 * 60_000L || points.size() < 3) {
                long stepMs = Math.max(60_000L, spanMs / 30);
                long currentTs = lastPointTs + stepMs;
                long chargeEndTs = Math.min(endedAtMs, startedAtMs + spanMs);

                while (currentTs < chargeEndTs) {
                    float progress = (float) (currentTs - startedAtMs) / (float) spanMs;
                    progress = Math.max(0f, Math.min(1f, progress));
                    float interpolatedSoc = startSoc + (lastSoc - startSoc) * progress;
                    points.add(new Point(currentTs, interpolatedSoc, pRef));
                    currentTs += stepMs;
                }
                points.add(new Point(chargeEndTs, lastSoc, pRef));
                if (endedAtMs > chargeEndTs + 60_000L) {
                    points.add(new Point(endedAtMs, lastSoc, 0f));
                }
                decimateIfFull();
            }
        }

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
                double effGrid = effectiveGridKwh();
                double effLoss = effectiveLossPercent();
                JSONObject json = new JSONObject().put("sessionId", id).put("startedAt", startedAtMs)
                        .put("endedAt", endedAtMs).put("durationSeconds", activeSeconds())
                        .put("energyKwh", energyKwh)
                        .put("stationEnergyKwh", stationEnergyKwh)
                        .put("chargingLossPercent", effLoss)
                        .put("gridEnergyKwh", effGrid)
                        .put("pricePerKwh", pricePerKwh)
                        .put("totalCost", effGrid * pricePerKwh)
                        .put("chargingType", chargingType != null ? chargingType : "AC")
                        .put("curve", curve)
                        .put("meteredSeconds", meteredSeconds)
                        .put("curveIntervalMs", curveIntervalMs);
                if (startSoc != null) json.put("startSocPercent", startSoc);
                if (lastSoc != null) json.put("endSocPercent", lastSoc);
                if (lastPower != null) json.put("lastPower", lastPower);
                if (initialPowerKw != null) json.put("initialPowerKw", initialPowerKw);
                if (latitude != null) json.put("latitude", latitude);
                if (longitude != null) json.put("longitude", longitude);
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
                        json.has("startSocPercent") ? (float) json.getDouble("startSocPercent") : null,
                        json.has("initialPowerKw") ? (float) json.getDouble("initialPowerKw") : null,
                        json.optString("chargingType", "AC"),
                        json.has("latitude") ? json.getDouble("latitude") : null,
                        json.has("longitude") ? json.getDouble("longitude") : null);
                session.lastSoc = json.has("endSocPercent") ? (float) json.getDouble("endSocPercent") : session.startSoc;
                session.lastPower = json.has("lastPower") ? (float) json.getDouble("lastPower") : null;
                session.energyKwh = json.optDouble("energyKwh", 0d);
                session.stationEnergyKwh = json.optDouble("stationEnergyKwh", 0d);
                session.lossPercent = json.optDouble("chargingLossPercent", 10.0);
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
