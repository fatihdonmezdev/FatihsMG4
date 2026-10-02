package com.evsuite.abrp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChargingStateTest {

    @Test
    public void vendorStatusIsConclusiveOnItsOwn() {
        // The port says false and the power is unreadable: status still decides.
        assertTrue(ChargingState.isCharging(1, Boolean.FALSE, null, null));
        assertTrue(ChargingState.isCharging(10, Boolean.FALSE, null, null));
    }

    @Test
    public void anIdleStatusDoesNotByItselfRuleChargingOut() {
        // Status 0 with the cable in and power flowing in is still a charge; the vendor
        // code is one signal among several, not a veto.
        assertTrue(ChargingState.isCharging(0, Boolean.TRUE, -7f, 0f));
    }

    @Test
    public void inboundPowerAtAStandstillIsACharge() {
        // This is the case the old port-only test missed: plugged in, port reports false.
        assertTrue(ChargingState.isCharging(null, Boolean.FALSE, -7f, 0f));
        assertTrue(ChargingState.isCharging(null, null, -45f, 0f));
    }

    @Test
    public void inboundPowerWhileMovingIsRegenerationNotCharging() {
        assertFalse(ChargingState.isCharging(null, Boolean.FALSE, -20f, 60f));
        assertFalse(ChargingState.isCharging(null, null, -20f, 60f));
    }

    @Test
    public void thePortStillCountsWhenItDoesAnswer() {
        assertTrue(ChargingState.isCharging(null, Boolean.TRUE, -7f, 0f));
    }

    @Test
    public void outboundPowerIsNeverACharge() {
        assertFalse(ChargingState.isCharging(null, Boolean.TRUE, 20f, 60f));
        assertFalse(ChargingState.isCharging(0, Boolean.TRUE, 2f, 0f));
    }

    @Test
    public void noiseAroundZeroIsNotACharge() {
        assertFalse(ChargingState.isCharging(null, Boolean.TRUE, -0.1f, 0f));
        assertFalse(ChargingState.isCharging(null, Boolean.TRUE, 0f, 0f));
    }

    @Test
    public void unreadablePowerWithoutAStatusIsNotAssumedToBeACharge() {
        assertFalse(ChargingState.isCharging(null, Boolean.TRUE, null, 0f));
        assertFalse(ChargingState.isCharging(null, null, null, null));
    }

    @Test
    public void unreadableSpeedWithInboundPowerFallsBackToCharging() {
        // Conservative on purpose: without a speed we cannot tell a charge from regen, and
        // treating it as a charge only suppresses a SOC delta. The opposite choice invents
        // negative consumption, which is the bug this class was written to stop.
        assertTrue(ChargingState.isCharging(null, null, -7f, null));
    }

    @Test
    public void creepingSpeedIsStillAStandstill() {
        // Same dead band the integrator uses, so the two cannot disagree about motion.
        assertTrue(ChargingState.isCharging(null, null, -7f, 1f));
        assertFalse(ChargingState.isCharging(null, null, -7f, 10f));
    }
}
