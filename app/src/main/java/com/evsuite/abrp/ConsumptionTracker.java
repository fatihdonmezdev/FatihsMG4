package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;

import com.evsuite.hardware.telemetry.EnergySnapshot;

/** Persistent, read-only trip/lifetime integration patterned after DriveHub_Dort. */
final class ConsumptionTracker {
    enum Period { START, MOTOR, LIFETIME, TRIP_A, TRIP_B }

    static final class Totals {
        final double km, kwh, hours, soc;
        Totals(double km, double kwh, double hours, double soc) {
            this.km = km; this.kwh = kwh; this.hours = hours; this.soc = soc;
        }
    }

    private static final String PREFS = "consumption_counters_v1";
    private static ConsumptionTracker instance;
    static synchronized ConsumptionTracker get(Context context) {
        if (instance == null) instance = new ConsumptionTracker(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private Totals start = zero(), motor = zero(), lifetime, tripA, tripB;
    private long lastMs;
    private Float lastSpeed, lastPower, lastSoc;
    private Boolean lastParked;

    private ConsumptionTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lifetime = load("life"); tripA = load("a"); tripB = load("b");
    }

    synchronized void sample(EnergySnapshot s) {
        long now = s.getTimestampMs();
        Boolean parked = s.getParked();
        if (Boolean.FALSE.equals(parked) && !Boolean.FALSE.equals(lastParked)) motor = zero();
        if (lastMs > 0 && now > lastMs) {
            double hours = (now - lastMs) / 3_600_000d;
            // Never bridge a process pause/sleep into invented driving data.
            if (hours <= 0.1d) {
                Float speed = s.getSpeedKmh();
                Float power = s.getBatteryPowerKw();
                double km = speed == null ? 0d : Math.max(0d,
                        ((lastSpeed == null ? speed : lastSpeed) + speed) * 0.5d) * hours;
                double kwh = power == null ? 0d :
                        ((lastPower == null ? power : lastPower) + power) * 0.5d * hours;
                double dsoc = (lastSoc == null || s.getSocPercent() == null ||
                        Boolean.TRUE.equals(s.getChargePortConnected())) ? 0d : lastSoc - s.getSocPercent();
                start = add(start, km, kwh, hours, dsoc);
                lifetime = add(lifetime, km, kwh, hours, dsoc);
                tripA = add(tripA, km, kwh, hours, dsoc);
                tripB = add(tripB, km, kwh, hours, dsoc);
                if (Boolean.FALSE.equals(parked)) motor = add(motor, km, kwh, hours, dsoc);
                persist();
            }
        }
        lastMs = now; lastSpeed = s.getSpeedKmh(); lastPower = s.getBatteryPowerKw();
        lastSoc = s.getSocPercent(); lastParked = parked;
    }

    synchronized Totals totals(Period period) {
        switch (period) {
            case START: return start; case MOTOR: return motor; case TRIP_A: return tripA;
            case TRIP_B: return tripB; default: return lifetime;
        }
    }

    synchronized void reset(Period period) {
        if (period == Period.TRIP_A) tripA = zero();
        else if (period == Period.TRIP_B) tripB = zero();
        else return;
        persist();
    }

    synchronized void persist() {
        SharedPreferences.Editor e = prefs.edit();
        save(e, "life", lifetime); save(e, "a", tripA); save(e, "b", tripB);
        e.commit();
    }

    private Totals load(String key) { return new Totals(bits(key,"km"), bits(key,"kwh"), bits(key,"h"), bits(key,"soc")); }
    private double bits(String key, String field) { return Double.longBitsToDouble(prefs.getLong(key + "_" + field, 0L)); }
    private static void save(SharedPreferences.Editor e, String key, Totals t) {
        e.putLong(key+"_km", Double.doubleToRawLongBits(t.km));
        e.putLong(key+"_kwh", Double.doubleToRawLongBits(t.kwh));
        e.putLong(key+"_h", Double.doubleToRawLongBits(t.hours));
        e.putLong(key+"_soc", Double.doubleToRawLongBits(t.soc));
    }
    private static Totals add(Totals t, double km, double kwh, double h, double soc) {
        return new Totals(t.km + km, t.kwh + kwh, t.hours + h, t.soc + soc);
    }
    private static Totals zero() { return new Totals(0,0,0,0); }
}
