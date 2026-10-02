package com.evsuite.abrp;

/**
 * Is the pack taking energy right now? Pure, unit-tested, and the single answer the
 * charging screen and the consumption counters both use.
 *
 * They used to answer it separately: the screen with a composite test, the counters with
 * the raw {@code EV_CHARGE_PORT_CONNECTED} property alone. That property is optional in
 * AAOS and this vehicle's VHAL answers {@code false} on a plugged-in car — see
 * {@link ChargingSignal}, which exists for the same reason. So the counters read a charge
 * as a drive, SOC climbed instead of falling, and every rising percent was booked as
 * negative consumption against the lifetime total.
 */
final class ChargingState {

    /** Charge in progress, as the vendor charging-status property reports it. */
    private static final int STATUS_AC_CHARGING = 1;
    private static final int STATUS_DC_CHARGING = 10;

    /** Below this (i.e. more negative than) the pack is taking energy, not reading noise. */
    static final float CHARGING_POWER_KW = -0.3f;

    /** At or under this the car is standing still; same dead band the integrator uses. */
    private static final float STANDSTILL_KMH = ConsumptionMath.SPEED_DEAD_BAND_KMH;

    /**
     * @param status        vendor charging status, null when unreadable
     * @param portConnected charge port property, null when unreadable — and false on this
     *                      car even when the cable is in, which is why it cannot stand alone
     * @param powerKw       battery power, negative into the pack, null when unreadable
     * @param speedKmh      road speed, null when unreadable
     */
    static boolean isCharging(Integer status, Boolean portConnected, Float powerKw, Float speedKmh) {
        if (status != null && (status == STATUS_AC_CHARGING || status == STATUS_DC_CHARGING)) {
            return true;
        }
        if (powerKw == null || powerKw >= CHARGING_POWER_KW) return false;

        // Power is flowing in. Either the port agrees, or the car is not moving — and a
        // stationary car cannot regenerate, so inbound power at a standstill is a charge.
        if (Boolean.TRUE.equals(portConnected)) return true;
        return speedKmh == null || Math.abs(speedKmh) < STANDSTILL_KMH;
    }

    private ChargingState() { }
}
