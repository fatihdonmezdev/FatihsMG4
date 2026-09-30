package com.evsuite.abrp;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the ABRP telemetry JSON payload.
 *
 * Kept free of Android types so it can be unit-tested on the JVM: the caller
 * pulls the values out of the car adapter and the Location, this class only
 * decides what ends up in the payload.
 *
 * Every vehicle field is nullable and a null field is OMITTED. A property that
 * cannot be read is not a zero: sending soc:0 because a getter threw tells ABRP
 * the battery is empty and wrecks the user's route plan.
 *
 * Fields are set by name rather than passed positionally. The payload now carries
 * 24 optional values and a positional build(...) call had become a wall of nulls
 * that no reader could check against the ABRP field list.
 */
final class TelemetryPayload {

    /** Only value that is always present. Epoch SECONDS, not milliseconds. */
    private final long utc;

    // --- High priority (ABRP: "many features will only be usable with enough data") ---
    Integer soc;             // %
    Float   speedKmh;        // km/h
    Float   powerKw;         // kW, ABRP sign: positive leaving the battery
    Boolean charging;
    Boolean dcfc;
    Boolean parked;
    Double  lat;             // °
    Double  lon;             // °

    // --- Lower priority ---
    Integer rangeKm;         // km, vehicle's own estimate
    Float   cabinTemp;       // °C
    Float   battTempC;       // °C, traction battery
    Float   capacityKwh;     // kWh, usable pack capacity
    Float   soeKwh;          // kWh, energy currently in the pack
    Double  kwhCharged;      // kWh, taken by the pack during the current charge session
    Float   odometerKm;      // km
    Float   hvacSetpointC;   // °C, climate setpoint
    Float   tirePressureFlKpa;
    Float   tirePressureFrKpa;
    Float   tirePressureRlKpa;
    Float   tirePressureRrKpa;
    Double  elevation;       // m
    Float   heading;         // °

    TelemetryPayload(long utc) {
        this.utc = utc;
    }

    /** Build the {@code tlm} JSON object. Only utc is always present. */
    String build() {
        JSONObject tlm = new JSONObject();
        try {
            tlm.put("utc", utc);

            putIfPresent(tlm, "soc", soc);
            putIfPresent(tlm, "speed", speedKmh == null ? null : Math.round(speedKmh));
            putIfPresent(tlm, "est_battery_range", rangeKm);
            if (powerKw != null) tlm.put("power", round2(powerKw));
            putIfPresent(tlm, "is_charging", boolToInt(charging));
            putIfPresent(tlm, "is_dcfc", boolToInt(dcfc));
            putIfPresent(tlm, "is_parked", boolToInt(parked));

            // Only send cabin_temp if we got a plausible reading — 0.0 likely
            // means the property isn't supported on this VHAL.
            if (isPlausibleTemp(cabinTemp)) {
                tlm.put("cabin_temp", Math.round(cabinTemp));
            }
            // Same guard for the pack: a VHAL that does not implement the property
            // answers 0.0, and "battery at 0 °C" is a reading ABRP acts on.
            if (isPlausibleTemp(battTempC)) {
                tlm.put("batt_temp", Math.round(battTempC));
            }
            // hvac_setpoint is a user setting, so 0 °C is not a real value either —
            // no cabin climate control is ever set to freezing.
            if (isPlausibleTemp(hvacSetpointC)) {
                tlm.put("hvac_setpoint", Math.round(hvacSetpointC));
            }

            // A pack cannot hold 0 kWh and a car cannot have driven 0 km by the time
            // it runs this app: both zeros mean "property not implemented".
            if (capacityKwh != null && capacityKwh > 0f) {
                tlm.put("capacity", round2(capacityKwh));
            }
            if (odometerKm != null && odometerKm > 0f) {
                tlm.put("odometer", Math.round(odometerKm));
            }
            // Same reasoning as capacity: a car that is running this app has some energy
            // in its pack, so 0 kWh is the VHAL saying it does not implement the property.
            if (soeKwh != null && soeKwh > 0f) {
                tlm.put("soe", round2(soeKwh));
            }
            // 0.0 IS meaningful here — it is a charge session that has just begun — so
            // This one is guarded on null only. EVHardware's charging-session meter sends
            // null between sessions.
            if (kwhCharged != null) {
                tlm.put("kwh_charged", kwhCharged.doubleValue());
            }

            putIfPresent(tlm, "tire_pressure_fl", kpa(tirePressureFlKpa));
            putIfPresent(tlm, "tire_pressure_fr", kpa(tirePressureFrKpa));
            putIfPresent(tlm, "tire_pressure_rl", kpa(tirePressureRlKpa));
            putIfPresent(tlm, "tire_pressure_rr", kpa(tirePressureRrKpa));

            if (lat != null && lon != null) {
                tlm.put("lat", lat.doubleValue());
                tlm.put("lon", lon.doubleValue());
                putIfPresent(tlm, "elevation", elevation == null ? null : Math.round(elevation));
                putIfPresent(tlm, "heading", heading == null ? null : Math.round(heading));
            }
        } catch (JSONException e) {
            // JSONObject.put only throws on NaN/Infinity values, which the guards above
            // already exclude. Returning just the timestamp keeps the upload alive.
            return "{\"utc\":" + utc + "}";
        }
        return tlm.toString();
    }

    /**
     * True for a temperature that a sensor could actually have measured. Excludes exactly
     * 0.0, which is what an unimplemented VHAL property returns — see cabin_temp above.
     *
     * Package-private because the service asks the same question before deciding whether to
     * look for an outside temperature somewhere other than the car.
     */
    static boolean isPlausibleTemp(Float celsius) {
        return celsius != null && celsius > -50f && celsius < 80f && celsius != 0f;
    }

    /**
     * Rounded kPa, or null when the reading is outside anything a road tyre reports.
     * A flat tyre still reads well above zero, so 0 means the wheel has no sensor.
     */
    private static Integer kpa(Float pressure) {
        if (pressure == null || pressure < 50f || pressure > 500f) return null;
        return Math.round(pressure);
    }

    private static void putIfPresent(JSONObject json, String key, Object value)
            throws JSONException {
        if (value != null) json.put(key, value);
    }

    private static Integer boolToInt(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }

    /** ABRP expects kW with 2 decimals; avoids a locale-dependent String.format. */
    private static double round2(float value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Every field {@link #build()} can emit, in the order the summary lists them.
     * Used only to work out what was left out — see {@link #summarize(String)}.
     */
    private static final String[] ALL_FIELDS = {
            "utc", "soc", "speed", "power", "est_battery_range", "cabin_temp",
            "batt_temp", "hvac_setpoint", "capacity", "soe", "kwh_charged", "odometer",
            "is_charging", "is_dcfc", "is_parked", "lat", "lon", "elevation", "heading",
            "tire_pressure_fl", "tire_pressure_fr", "tire_pressure_rl", "tire_pressure_rr",
    };

    /**
     * One-line, human-readable digest of an upload: what was sent, and what was left out.
     *
     * Derived from the JSON that actually goes on the wire rather than from the fields
     * above, so it cannot drift away from the payload it claims to describe — which is
     * the whole point of having it when a value on the ABRP side looks wrong.
     */
    static String summarize(String tlmJson) {
        JSONObject tlm;
        try {
            tlm = new JSONObject(tlmJson);
        } catch (JSONException e) {
            return "unparseable payload: " + tlmJson;
        }
        StringBuilder sent = new StringBuilder();
        StringBuilder omitted = new StringBuilder();
        for (String field : ALL_FIELDS) {
            if (tlm.has(field)) {
                if (sent.length() > 0) sent.append(' ');
                sent.append(field).append('=').append(tlm.opt(field));
            } else {
                if (omitted.length() > 0) omitted.append(',');
                omitted.append(field);
            }
        }
        return sent + (omitted.length() == 0 ? "" : " | omitted: " + omitted);
    }
}
