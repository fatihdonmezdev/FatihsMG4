package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;

import com.evsuite.hardware.telemetry.EnergySnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;

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
    private Totals lifetime, week, month, tripA, tripB;
    private String weekId, monthId;
    private long lastMs;
    private Float lastSpeed, lastPower, lastSoc;

    private ConsumptionTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lifetime = load("life"); week = load("week"); month = load("month");
        tripA = load("a"); tripB = load("b");
        weekId = prefs.getString("week_id", "");
        monthId = prefs.getString("month_id", "");
        rollPeriods(System.currentTimeMillis());
    }

    synchronized void sample(EnergySnapshot s) {
        long now = s.getTimestampMs();
        rollPeriods(now);
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
                week = add(week, km, kwh, hours, dsoc);
                month = add(month, km, kwh, hours, dsoc);
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
            case WEEK: return week; case MONTH: return month; case TRIP_A: return tripA;
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
        save(e, "life", lifetime); save(e, "week", week); save(e, "month", month);
        save(e, "a", tripA); save(e, "b", tripB);
        e.putString("week_id", weekId).putString("month_id", monthId);
        e.commit();
    }

    private void rollPeriods(long nowMs) {
        LocalDate date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate();
        WeekFields iso = WeekFields.ISO;
        String currentWeek = date.get(iso.weekBasedYear()) + "-W" + date.get(iso.weekOfWeekBasedYear());
        String currentMonth = date.getYear() + "-" + date.getMonthValue();
        boolean changed = false;
        if (!currentWeek.equals(weekId)) { weekId = currentWeek; week = zero(); changed = true; }
        if (!currentMonth.equals(monthId)) { monthId = currentMonth; month = zero(); changed = true; }
        if (changed) persist();
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
