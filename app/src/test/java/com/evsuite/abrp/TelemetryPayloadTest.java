package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * [T-913] Telemetry payload construction — pure JVM, no car and no Android needed.
 *
 * The rule under test: a property that could not be read is OMITTED. Emitting 0 instead
 * tells ABRP the car really is at 0 % / 0 km/h and corrupts the user's route plan.
 */
public class TelemetryPayloadTest {

    /** Everything readable, no location. */
    private TelemetryPayload fullCar() {
        TelemetryPayload t = new TelemetryPayload(1_700_000_000L);
        t.soc       = 63;
        t.speedKmh  = 48.4f;
        t.rangeKm   = 210;
        t.powerKw   = -14.5f;
        t.charging  = false;
        t.dcfc      = false;
        t.parked    = false;
        t.cabinTemp = 21.5f;
        return t;
    }

    /** Nothing readable at all — the car link is down. */
    private String noCar() {
        return new TelemetryPayload(1_700_000_000L).build();
    }

    @Test
    public void utcIsAlwaysPresent() throws Exception {
        JSONObject json = new JSONObject(noCar());
        assertEquals(1, json.length());
        assertEquals(1_700_000_000L, json.getLong("utc"));
    }

    @Test
    public void unreadablePropertiesAreOmittedNotZeroed() {
        String json = noCar();
        for (String field : new String[]{
                "soc", "speed", "est_battery_range", "power",
                "is_charging", "is_dcfc", "is_parked", "cabin_temp",
                "batt_temp", "capacity", "odometer", "hvac_setpoint",
                "tire_pressure_fl", "tire_pressure_fr",
                "tire_pressure_rl", "tire_pressure_rr"}) {
            assertFalse("le champ " + field + " aurait dû être omis", json.contains(field));
        }
    }

    @Test
    public void aSingleUnreadablePropertyDoesNotSuppressTheOthers() throws Exception {
        // SOC throws (null) but speed reads fine: speed must still be sent.
        TelemetryPayload t = new TelemetryPayload(1L);
        t.speedKmh = 48.4f;
        JSONObject json = new JSONObject(t.build());
        assertFalse("soc illisible aurait dû être omis", json.has("soc"));
        assertEquals(48, json.getInt("speed"));
    }

    @Test
    public void aGenuineZeroIsStillSent() throws Exception {
        // 0 % battery is a real reading and must not be confused with "unreadable".
        TelemetryPayload t = new TelemetryPayload(1L);
        t.soc      = 0;
        t.speedKmh = 0f;
        t.rangeKm  = 0;
        JSONObject json = new JSONObject(t.build());
        assertEquals(0, json.getInt("soc"));
        assertEquals(0, json.getInt("speed"));
        assertEquals(0, json.getInt("est_battery_range"));
    }

    @Test
    public void readablePropertiesAreEmitted() throws Exception {
        JSONObject json = new JSONObject(fullCar().build());
        assertEquals(63, json.getInt("soc"));
        assertEquals(48, json.getInt("speed"));               // rounded
        assertEquals(210, json.getInt("est_battery_range"));
        assertEquals(-14.5, json.getDouble("power"), 0.001);
        assertEquals(0, json.getInt("is_charging"));
        assertEquals(0, json.getInt("is_dcfc"));
        assertEquals(0, json.getInt("is_parked"));
    }

    @Test
    public void powerUsesDotDecimalSeparatorRegardlessOfDefaultLocale() throws Exception {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);   // comma locale
            assertTrue(fullCar().build().contains("-14.5"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void implausibleCabinTempIsOmitted() {
        // 0.0 means the property is unsupported on this VHAL.
        TelemetryPayload zero = new TelemetryPayload(1L);
        zero.cabinTemp = 0f;
        assertFalse(zero.build().contains("cabin_temp"));

        TelemetryPayload hot = new TelemetryPayload(1L);
        hot.cabinTemp = 120f;
        assertFalse(hot.build().contains("cabin_temp"));
    }

    @Test
    public void plausibleCabinTempIsSent() throws Exception {
        assertEquals(22, new JSONObject(fullCar().build()).getInt("cabin_temp"));  // 21.5 rounds up
    }

    // ---------- Fields added for the ABRP lower-priority set ----------

    @Test
    public void batteryTemperatureIsSentWhenPlausible() throws Exception {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.battTempC = 24.6f;
        assertEquals(25, new JSONObject(t.build()).getInt("batt_temp"));
    }

    @Test
    public void unsupportedBatteryTemperatureIsOmitted() {
        // A VHAL without the property answers 0.0; "pack at 0 °C" changes ABRP's plan.
        TelemetryPayload t = new TelemetryPayload(1L);
        t.battTempC = 0f;
        assertFalse(t.build().contains("batt_temp"));
    }

    @Test
    public void capacityIsSentInKwhWithTwoDecimals() throws Exception {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.capacityKwh = 61.734f;
        assertEquals(61.73, new JSONObject(t.build()).getDouble("capacity"), 0.001);
    }

    @Test
    public void zeroCapacityIsOmitted() {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.capacityKwh = 0f;
        assertFalse(t.build().contains("capacity"));
    }

    @Test
    public void odometerIsRoundedToWholeKm() throws Exception {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.odometerKm = 24518.7f;
        assertEquals(24519, new JSONObject(t.build()).getInt("odometer"));
    }

    @Test
    public void zeroOdometerIsOmitted() {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.odometerKm = 0f;
        assertFalse(t.build().contains("odometer"));
    }

    @Test
    public void hvacSetpointIsSentWhenSet() throws Exception {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.hvacSetpointC = 21f;
        assertEquals(21, new JSONObject(t.build()).getInt("hvac_setpoint"));
    }

    @Test
    public void unsupportedHvacSetpointIsOmitted() {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.hvacSetpointC = 0f;
        assertFalse(t.build().contains("hvac_setpoint"));
    }

    @Test
    public void stateOfEnergyIsSentInKwh() throws Exception {
        TelemetryPayload t = fullCar();
        t.soeKwh = 41.256f;
        assertEquals(41.26, new JSONObject(t.build()).getDouble("soe"), 0.001);
    }

    @Test
    public void unsupportedStateOfEnergyIsOmitted() {
        TelemetryPayload t = fullCar();
        t.soeKwh = 0f;
        assertFalse(t.build().contains("soe"));
    }

    @Test
    public void chargedEnergyIsSentIncludingZeroAtSessionStart() throws Exception {
        TelemetryPayload started = fullCar();
        started.kwhCharged = 0.0;
        assertEquals(0.0, new JSONObject(started.build()).getDouble("kwh_charged"), 0.001);

        TelemetryPayload running = fullCar();
        running.kwhCharged = 12.34;
        assertEquals(12.34, new JSONObject(running.build()).getDouble("kwh_charged"), 0.001);
    }

    @Test
    public void chargedEnergyIsOmittedOutsideACharge() {
        assertFalse(fullCar().build().contains("kwh_charged"));
    }

    @Test
    public void tyrePressuresAreSentPerWheelInKpa() throws Exception {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.tirePressureFlKpa = 240f;
        t.tirePressureFrKpa = 241.4f;
        t.tirePressureRlKpa = 250.6f;
        t.tirePressureRrKpa = 249f;
        JSONObject json = new JSONObject(t.build());
        assertEquals(240, json.getInt("tire_pressure_fl"));
        assertEquals(241, json.getInt("tire_pressure_fr"));
        assertEquals(251, json.getInt("tire_pressure_rl"));
        assertEquals(249, json.getInt("tire_pressure_rr"));
    }

    @Test
    public void aWheelWithoutASensorIsOmittedWithoutHidingTheOthers() throws Exception {
        // 0 kPa is not a flat tyre, it is a missing sensor: a flat still reads well above 0.
        TelemetryPayload t = new TelemetryPayload(1L);
        t.tirePressureFlKpa = 0f;
        t.tirePressureFrKpa = 240f;
        JSONObject json = new JSONObject(t.build());
        assertFalse(json.has("tire_pressure_fl"));
        assertEquals(240, json.getInt("tire_pressure_fr"));
    }

    // ---------- Location ----------

    @Test
    public void locationIsIndependentOfTheCarLink() throws Exception {
        // No car data at all, but a GPS fix: ABRP should still see the vehicle online.
        TelemetryPayload t = new TelemetryPayload(1L);
        t.lat = 48.85;
        t.lon = 2.35;
        JSONObject json = new JSONObject(t.build());
        assertEquals(48.85, json.getDouble("lat"), 0.0001);
        assertEquals(2.35, json.getDouble("lon"), 0.0001);
        assertFalse(json.has("soc"));
    }

    @Test
    public void missingLocationOmitsAllLocationFields() {
        String json = fullCar().build();
        assertFalse(json.contains("lat"));
        assertFalse(json.contains("lon"));
        assertFalse(json.contains("elevation"));
        assertFalse(json.contains("heading"));
    }

    @Test
    public void elevationAndHeadingAreOptional() throws Exception {
        TelemetryPayload flat = new TelemetryPayload(1L);
        flat.lat = 48.85;
        flat.lon = 2.35;
        JSONObject flatJson = new JSONObject(flat.build());
        assertFalse(flatJson.has("elevation"));
        assertFalse(flatJson.has("heading"));

        TelemetryPayload full = new TelemetryPayload(1L);
        full.lat       = 48.85;
        full.lon       = 2.35;
        full.elevation = 35.4;
        full.heading   = 271.6f;
        JSONObject fullJson = new JSONObject(full.build());
        assertEquals(35, fullJson.getInt("elevation"));
        assertEquals(272, fullJson.getInt("heading"));
    }

    @Test
    public void booleanFlagsAreEmittedAsIntegers() throws Exception {
        TelemetryPayload t = new TelemetryPayload(1L);
        t.soc      = 50;
        t.powerKw  = 62f;
        t.charging = true;
        t.dcfc     = true;
        t.parked   = true;
        JSONObject json = new JSONObject(t.build());
        assertEquals(1, json.getInt("is_charging"));
        assertEquals(1, json.getInt("is_dcfc"));
        assertEquals(1, json.getInt("is_parked"));
    }

    @Test
    public void thePayloadIsValidJson() throws Exception {
        // The hand-built string could not guarantee this; JSONObject can.
        new JSONObject(fullCar().build());
        new JSONObject(noCar());
    }

    @Test
    public void summaryListsSentValuesAndOmittedFields() {
        String summary = TelemetryPayload.summarize(fullCar().build());
        assertTrue(summary, summary.contains("soc=63"));
        assertTrue(summary, summary.contains("cabin_temp=22"));
        // Nothing gave us a position, so lat/lon must show up as omitted, not as 0.
        assertFalse(summary, summary.contains("lat="));
        assertTrue(summary, summary.contains("| omitted: "));
        assertTrue(summary, summary.substring(summary.indexOf("| omitted: ")).contains("lat"));
    }

    @Test
    public void summaryOfAnEmptyPayloadReportsEverythingOmitted() {
        String summary = TelemetryPayload.summarize(noCar());
        assertTrue(summary, summary.startsWith("utc=1700000000"));
        assertTrue(summary, summary.contains("| omitted: soc,speed,"));
    }
}
