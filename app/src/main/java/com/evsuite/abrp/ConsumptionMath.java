package com.evsuite.abrp;

/**
 * One integration step of distance, energy, time and SOC — pure, Android-free, unit-tested.
 *
 * This is DriveHub_Dort's algorithm, which has proven stable on this car over months, and
 * it is now the *only* algorithm: every period (lifetime, day, trip A, trip B) is fed from
 * the same step. Earlier builds integrated the lifetime counter from net DC power while
 * feeding the trip and day counters from either the vehicle's own cumulative kWh register
 * or a regeneration-discarding gross fallback. Three sources, three answers, none of them
 * agreeing with the car's dashboard — the counters were not measuring the same quantity.
 *
 * What the vehicle reports is net DC battery power: positive out of the pack, negative in.
 * Integrating it as-is means regeneration subtracts, which is exactly what the car's own
 * kWh figure does. Discarding the negative half (the old {@code max(0, kW)} fallback) is
 * what made our number read high.
 */
final class ConsumptionMath {

    /**
     * Longest gap we will still integrate across, in hours (6 minutes).
     *
     * A head unit that slept, a service that was killed, or a car that sat parked overnight
     * all show up as one enormous dt. Bridging it would invent a drive that never happened,
     * so the gap is dropped and the next sample starts a fresh interval.
     */
    static final double MAX_GAP_HOURS = 0.1d;

    /** Below this the speed signal is noise around a standstill, not motion. */
    static final float SPEED_DEAD_BAND_KMH = 2.5f;

    /** A reading past this is a decode artefact, not power the pack could ever deliver. */
    static final float MAX_PLAUSIBLE_KW = 3000f;

    /** Result of one interval. All fields are increments, never totals. */
    static final class Step {
        final double km, kwh, hours, socDrop;

        Step(double km, double kwh, double hours, double socDrop) {
            this.km = km;
            this.kwh = kwh;
            this.hours = hours;
            this.socDrop = socDrop;
        }
    }

    /**
     * Integrates one interval, or returns null when the interval carries no usable data.
     *
     * Every input is nullable because a failed vehicle read is not a zero: a missing power
     * reading suppresses the energy term for this interval only, and leaves distance and
     * time — which do not depend on it — intact.
     *
     * @param hours    elapsed time, from a monotonic clock
     * @param charging whether the charge port is connected; SOC gained on a charger is not
     *                 consumption and must not offset the drive's SOC drop
     */
    static Step integrate(double hours,
                          Float previousSpeedKmh, Float speedKmh,
                          Float previousPowerKw, Float powerKw,
                          Float previousSocPercent, Float socPercent,
                          boolean charging) {
        if (!(hours > 0d) || hours > MAX_GAP_HOURS) return null;

        // Distance: trapezoidal speed across the interval. The calibration factors correct
        // the head unit's speed signal against the odometer at motorway speeds, where the
        // reported value runs slightly low.
        float current = speedKmh != null ? Math.abs(speedKmh)
                : (previousSpeedKmh != null ? Math.abs(previousSpeedKmh) : 0f);
        float previous = previousSpeedKmh != null ? Math.abs(previousSpeedKmh) : current;
        float effectiveKmh = (previous + current) * 0.5f;
        if (effectiveKmh < SPEED_DEAD_BAND_KMH) effectiveKmh = 0f;
        else if (effectiveKmh > 90f) effectiveKmh *= 1.0035f;
        else if (effectiveKmh > 30f) effectiveKmh *= 1.0015f;
        double km = effectiveKmh * hours;

        // Energy: trapezoidal net DC power, regeneration included with its own sign.
        double kwh = 0d;
        if (powerKw != null && Math.abs(powerKw) <= MAX_PLAUSIBLE_KW) {
            if (current == 0f && powerKw < 0f) {
                // Standing still with power flowing in is charging or pre-conditioning
                // recovery, not a drive crediting itself energy it never spent.
                kwh = 0d;
            } else {
                double effectiveKw = previousPowerKw != null && Math.abs(previousPowerKw) <= MAX_PLAUSIBLE_KW
                        ? (previousPowerKw + powerKw) * 0.5d
                        : powerKw;
                kwh = effectiveKw * hours;
            }
        }

        double socDrop = (!charging && previousSocPercent != null && socPercent != null)
                ? previousSocPercent - socPercent
                : 0d;

        return new Step(km, kwh, hours, socDrop);
    }

    private ConsumptionMath() { }
}
