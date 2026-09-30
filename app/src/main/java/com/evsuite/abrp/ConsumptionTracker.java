package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;

import com.evsuite.hardware.telemetry.EnergySnapshot;

import java.time.LocalDate;

/** Persistent, read-only trip/lifetime integration patterned after DriveHub_Dort. */
final class ConsumptionTracker {
    enum Period { LIFETIME, WEEK, MONTH, TRIP_A, TRIP_B }

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
    private Totals lifetime, tripA, tripB;
    private long lastMs;
    private Float lastSpeed, lastPower, lastSoc;

    private ConsumptionTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lifetime = load("life"); tripA = load("a"); tripB = load("b");
        migrateCalendarCounters();
    }

    synchronized void sample(EnergySnapshot s) {
        long now = s.getTimestampMs();
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
                lifetime = add(lifetime, km, kwh, hours, dsoc);
                String dayKey = "day_" + LocalDate.now();
                SharedPreferences.Editor daily = prefs.edit();
                save(daily, dayKey, add(load(dayKey), km, kwh, hours, dsoc));
                daily.commit();
                pruneOldDays();
                tripA = add(tripA, km, kwh, hours, dsoc);
                tripB = add(tripB, km, kwh, hours, dsoc);
                persist();
            }
        }
        lastMs = now; lastSpeed = s.getSpeedKmh(); lastPower = s.getBatteryPowerKw();
        lastSoc = s.getSocPercent();
    }

    synchronized Totals totals(Period period) {
        switch (period) {
            case WEEK: return rollingDays(7); case MONTH: return rollingDays(30); case TRIP_A: return tripA;
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
        save(e, "life", lifetime);
        save(e, "a", tripA); save(e, "b", tripB);
        e.commit();
    }

    private Totals rollingDays(int days) {
        Totals result = zero();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            Totals day = load("day_" + today.minusDays(i));
            result = add(result, day.km, day.kwh, day.hours, day.soc);
        }
        return result;
    }

    synchronized Totals totalsForDay(LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now()) || date.isBefore(LocalDate.now().minusMonths(4)))
            return zero();
        return load("day_" + date);
    }

    private void pruneOldDays() {
        LocalDate oldest = LocalDate.now().minusMonths(4);
        SharedPreferences.Editor editor = null;
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("day_") || key.length() < 14) continue;
            try {
                LocalDate date = LocalDate.parse(key.substring(4, 14));
                if (date.isBefore(oldest)) {
                    if (editor == null) editor = prefs.edit();
                    editor.remove(key);
                }
            } catch (RuntimeException ignored) { }
        }
        if (editor != null) editor.commit();
    }

    /** Preserve anything gathered by 2.2.24 by treating its just-created counter as today. */
    private void migrateCalendarCounters() {
        if (prefs.getBoolean("rolling_days_migrated", false)) return;
        Totals old = load("week");
        SharedPreferences.Editor e = prefs.edit();
        if (old.km != 0 || old.kwh != 0 || old.hours != 0 || old.soc != 0)
            save(e, "day_" + LocalDate.now(), old);
        e.putBoolean("rolling_days_migrated", true).commit();
        pruneOldDays();
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
