package com.evsuite.abrp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.evsuite.hardware.telemetry.EnergySnapshot;

import java.time.LocalDate;

/**
 * Persistent, read-only trip/lifetime integration.
 *
 * All five periods are integrated from one {@link ConsumptionMath} step, so they can only
 * ever disagree by what they cover, never by how they were measured. The counters live in
 * {@link KahanSum} accumulators in memory and reach storage on a 30-second throttle; the
 * store is new in 2.2.32, which is what resets the mixed-algorithm history inherited from
 * earlier builds.
 */
final class ConsumptionTracker {
    enum Period { LIFETIME, WEEK, MONTH, TRIP_A, TRIP_B }

    static final class Totals {
        final double km, kwh, hours, soc;
        Totals(double km, double kwh, double hours, double soc) {
            this.km = km; this.kwh = kwh; this.hours = hours; this.soc = soc;
        }
    }

    /**
     * Bumped from v1 deliberately. The old store holds totals accumulated under three
     * different algorithms; there is no factor that converts them into what this one
     * measures, so they are discarded rather than carried forward as a plausible lie.
     */
    private static final String PREFS = "consumption_counters_v2";
    private static final String LEGACY_PREFS = "consumption_counters_v1";
    private static final String SOH_PERCENT = "manual_soh_percent";
    /** Snapshot of today's counter at the last chunk send — the baseline for the next delta. */
    private static final String CHUNK_CHECKPOINT = "chunk_checkpoint_";

    /** How often the in-memory accumulators reach disk. Matches DriveHub_Dort. */
    private static final long PERSIST_INTERVAL_MS = 30_000L;

    private static ConsumptionTracker instance;
    static synchronized ConsumptionTracker get(Context context) {
        if (instance == null) instance = new ConsumptionTracker(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Counter lifetime = new Counter();
    private final Counter tripA = new Counter();
    private final Counter tripB = new Counter();
    private final Counter today = new Counter();
    private LocalDate todayDate;
    /** Today's totals as of the last chunk send, per field. Delta = today - this. */
    private double chunkCpKm, chunkCpKwh, chunkCpHours, chunkCpSoc;

    /**
     * Monotonic. {@code EnergySnapshot.timestampMs} is wall clock, and the head unit's
     * clock jumps when it picks up GPS or NTP time — backwards, in which case intervals
     * were silently dropped, or forwards, in which case one jump invented hours of driving.
     */
    private long lastElapsedMs;
    private long lastPersistMs;
    private Float lastSpeed, lastPower, lastSoc;

    private ConsumptionTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        discardLegacyStore(context);
        repairSocColumn();
        lifetime.load(prefs, "life");
        tripA.load(prefs, "a");
        tripB.load(prefs, "b");
        todayDate = LocalDate.now();
        today.load(prefs, dayKey(todayDate));
        loadChunkCheckpoint();
        pruneOldDays();
    }

    synchronized void sample(EnergySnapshot s) {
        long now = SystemClock.elapsedRealtime();
        long previous = lastElapsedMs;
        lastElapsedMs = now;

        Float speed = s.getSpeedKmh();
        Float power = s.getBatteryPowerKw();
        Float soc = s.getSocPercent();
        boolean charging = ChargingState.isCharging(
                s.getChargingStatus(), s.getChargePortConnected(), power, speed);

        double hours = previous > 0L ? (now - previous) / 3_600_000d : 0d;
        ConsumptionMath.Step step = ConsumptionMath.integrate(
                hours, lastSpeed, speed, lastPower, power, lastSoc, soc, charging);

        // Only a real reading becomes the next interval's trapezoid partner; a failed read
        // must not erase the last known value and turn the next interval into a step change.
        if (speed != null) lastSpeed = speed;
        if (power != null) lastPower = power;
        if (soc != null) lastSoc = soc;

        if (step == null) return;

        rollDayIfNeeded();
        lifetime.add(step);
        tripA.add(step);
        tripB.add(step);
        today.add(step);

        if (now - lastPersistMs >= PERSIST_INTERVAL_MS) persist();
    }

    synchronized Totals totals(Period period) {
        switch (period) {
            case WEEK: return rollingDays(7);
            case MONTH: return rollingDays(30);
            case TRIP_A: return tripA.snapshot();
            case TRIP_B: return tripB.snapshot();
            default: return lifetime.snapshot();
        }
    }

    synchronized void reset(Period period) {
        if (period == Period.TRIP_A) tripA.reset();
        else if (period == Period.TRIP_B) tripB.reset();
        else return;
        persist();
    }

    synchronized void persist() {
        lastPersistMs = SystemClock.elapsedRealtime();
        SharedPreferences.Editor e = prefs.edit();
        lifetime.save(e, "life");
        tripA.save(e, "a");
        tripB.save(e, "b");
        today.save(e, dayKey(todayDate));
        // Lifetime as it stood at the end of this day, so a backlog uploaded days later
        // does not stamp every one of those days with today's total — which would draw a
        // flat line and then one cliff in anything plotting it.
        lifetime.save(e, lifeKey(todayDate));
        e.commit();
    }

    synchronized Totals totalsForDay(LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now()) || date.isBefore(LocalDate.now().minusMonths(4)))
            return zero();
        if (date.equals(todayDate)) return today.snapshot();
        return loadTotals(dayKey(date));
    }

    synchronized CloudSnapshot cloudSnapshot(LocalDate date) {
        rollDayIfNeeded();
        return new CloudSnapshot(date.toString(), totalsForDay(date), lifetimeAtEndOf(date), sohPercent());
    }

    /**
     * The delta accumulated since the last chunk send — what a 30-minute consumption chunk
     * should carry. Returns null when nothing has accumulated (no driving in this window).
     * Calling this advances the checkpoint, so the same delta is never sent twice.
     */
    synchronized CloudSnapshot takeChunkDelta() {
        rollDayIfNeeded();
        Totals current = today.snapshot();
        double dKm = current.km - chunkCpKm;
        double dKwh = current.kwh - chunkCpKwh;
        double dHours = current.hours - chunkCpHours;
        double dSoc = current.soc - chunkCpSoc;
        if (Math.abs(dKm) < 0.001 && Math.abs(dKwh) < 0.001
                && Math.abs(dHours) < 0.001 && Math.abs(dSoc) < 0.001) {
            return null;  // nothing new since the last chunk
        }
        saveChunkCheckpoint(current);
        Totals delta = new Totals(dKm, dKwh, dHours, dSoc);
        return new CloudSnapshot(todayDate.toString(), delta, lifetime.snapshot(), sohPercent());
    }

    private void loadChunkCheckpoint() {
        String p = CHUNK_CHECKPOINT + todayDate;
        chunkCpKm = bits(p, "km");
        chunkCpKwh = bits(p, "kwh");
        chunkCpHours = bits(p, "h");
        chunkCpSoc = bits(p, "soc");
    }

    private void saveChunkCheckpoint(Totals current) {
        chunkCpKm = current.km;
        chunkCpKwh = current.kwh;
        chunkCpHours = current.hours;
        chunkCpSoc = current.soc;
        String p = CHUNK_CHECKPOINT + todayDate;
        SharedPreferences.Editor e = prefs.edit();
        e.putLong(p + "_km", Double.doubleToRawLongBits(current.km));
        e.putLong(p + "_kwh", Double.doubleToRawLongBits(current.kwh));
        e.putLong(p + "_h", Double.doubleToRawLongBits(current.hours));
        e.putLong(p + "_soc", Double.doubleToRawLongBits(current.soc));
        e.commit();
    }

    /** Lifetime as of the close of {@code date}, falling back to the live counter. */
    private Totals lifetimeAtEndOf(LocalDate date) {
        if (date.equals(todayDate)) return lifetime.snapshot();
        Totals stored = loadTotals(lifeKey(date));
        boolean missing = stored.km == 0 && stored.kwh == 0 && stored.hours == 0 && stored.soc == 0;
        return missing ? lifetime.snapshot() : stored;
    }

    /**
     * Sets the local lifetime baseline from the cloud's last known lifetime, but only when
     * the cloud value is higher — an APK wipe zeroes the local counter, and without this the
     * car would re-upload a small lifetime that overwrites the real one in the cloud.
     * Called once after the first history fetch succeeds.
     */
    synchronized void syncLifetimeBaseline(Totals cloudLifetime) {
        Totals local = lifetime.snapshot();
        if (cloudLifetime.km > local.km) {
            lifetime.set(cloudLifetime.km, cloudLifetime.kwh, cloudLifetime.hours, cloudLifetime.soc);
            persist();
            Log.i("FatihsMG4.Consumption", "Lifetime baseline synced from cloud: "
                    + cloudLifetime.km + " km (was " + local.km + " km)");
        }
    }

    synchronized LocalDate nextStoredDayAfter(LocalDate lastUploaded) {
        rollDayIfNeeded();
        LocalDate latestComplete = LocalDate.now().minusDays(1);
        LocalDate best = null;
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("day_") || key.length() < 14) continue;
            try {
                LocalDate date = LocalDate.parse(key.substring(4, 14));
                if (date.isAfter(latestComplete) || (lastUploaded != null && !date.isAfter(lastUploaded))) continue;
                if (best == null || date.isBefore(best)) best = date;
            } catch (RuntimeException ignored) { }
        }
        return best;
    }

    static final class CloudSnapshot {
        final String date;
        final Totals day;
        final Totals lifetime;
        final Float sohPercent;

        CloudSnapshot(String date, Totals day, Totals lifetime, Float sohPercent) {
            this.date = date;
            this.day = day;
            this.lifetime = lifetime;
            this.sohPercent = sohPercent;
        }
    }

    synchronized Float sohPercent() {
        return prefs.contains(SOH_PERCENT) ? prefs.getFloat(SOH_PERCENT, 0f) : null;
    }

    synchronized boolean setSohPercent(float value) {
        if (!Float.isFinite(value) || value <= 0f || value > 100f) return false;
        return prefs.edit().putFloat(SOH_PERCENT, value).commit();
    }

    /** Flushes the day that just ended and opens the new one. */
    private void rollDayIfNeeded() {
        LocalDate date = LocalDate.now();
        if (date.equals(todayDate)) return;
        persist();
        todayDate = date;
        today.load(prefs, dayKey(date));
        loadChunkCheckpoint();  // new day → fresh checkpoint baseline
        pruneOldDays();
    }

    private Totals rollingDays(int days) {
        double km = 0, kwh = 0, hours = 0, soc = 0;
        LocalDate today_ = LocalDate.now();
        for (int i = 0; i < days; i++) {
            Totals day = totalsForDay(today_.minusDays(i));
            km += day.km; kwh += day.kwh; hours += day.hours; soc += day.soc;
        }
        return new Totals(km, kwh, hours, soc);
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

    /**
     * Zeroes the SOC column once, for 2.2.33.
     *
     * Until then a charge was detected from the charge-port property alone, which this
     * vehicle answers {@code false} to even when the cable is in, so every percent gained
     * on a charger was booked as negative consumption. Only this column is affected —
     * distance, energy and time never consulted the charge state — so the rest of the
     * store is sound and is kept.
     */
    private void repairSocColumn() {
        if (prefs.getBoolean("soc_charge_detection_v2", false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.endsWith("_soc")) editor.putLong(key, Double.doubleToRawLongBits(0d));
        }
        editor.putBoolean("soc_charge_detection_v2", true).commit();
    }

    /** Deletes the pre-2.2.32 store once, so its mixed-algorithm totals cannot be read back. */
    private void discardLegacyStore(Context context) {
        if (prefs.getBoolean("legacy_v1_discarded", false)) return;
        try {
            context.deleteSharedPreferences(LEGACY_PREFS);
        } catch (RuntimeException ignored) {
            // An older platform without the API: the v1 file is simply never read again.
        }
        prefs.edit().putBoolean("legacy_v1_discarded", true).commit();
    }

    private static String dayKey(LocalDate date) { return "day_" + date; }

    /** Deliberately shares the {@code day_<date>} prefix so pruning reaches it too. */
    private static String lifeKey(LocalDate date) { return "day_" + date + "_life"; }

    private Totals loadTotals(String key) {
        return new Totals(bits(key, "km"), bits(key, "kwh"), bits(key, "h"), bits(key, "soc"));
    }

    private double bits(String key, String field) {
        return Double.longBitsToDouble(prefs.getLong(key + "_" + field, 0L));
    }

    private static Totals zero() { return new Totals(0, 0, 0, 0); }

    /** The four compensated accumulators that make up one period. */
    private static final class Counter {
        private final KahanSum km = new KahanSum();
        private final KahanSum kwh = new KahanSum();
        private final KahanSum hours = new KahanSum();
        private final KahanSum soc = new KahanSum();

        void add(ConsumptionMath.Step step) {
            km.add(step.km);
            kwh.add(step.kwh);
            hours.add(step.hours);
            soc.add(step.socDrop);
        }

        void load(SharedPreferences prefs, String key) {
            km.setTotal(read(prefs, key, "km"));
            kwh.setTotal(read(prefs, key, "kwh"));
            hours.setTotal(read(prefs, key, "h"));
            soc.setTotal(read(prefs, key, "soc"));
        }

        void save(SharedPreferences.Editor e, String key) {
            e.putLong(key + "_km", Double.doubleToRawLongBits(km.get()));
            e.putLong(key + "_kwh", Double.doubleToRawLongBits(kwh.get()));
            e.putLong(key + "_h", Double.doubleToRawLongBits(hours.get()));
            e.putLong(key + "_soc", Double.doubleToRawLongBits(soc.get()));
        }

        void reset() {
            km.reset(); kwh.reset(); hours.reset(); soc.reset();
        }

        void set(double kmVal, double kwhVal, double hoursVal, double socVal) {
            km.setTotal(kmVal);
            kwh.setTotal(kwhVal);
            hours.setTotal(hoursVal);
            soc.setTotal(socVal);
        }

        Totals snapshot() {
            return new Totals(km.get(), kwh.get(), hours.get(), soc.get());
        }

        private static double read(SharedPreferences prefs, String key, String field) {
            return Double.longBitsToDouble(prefs.getLong(key + "_" + field, 0L));
        }
    }
}
