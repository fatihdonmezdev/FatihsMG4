package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChargeCostTest {
    @Test
    public void costIncludesTenPercentChargingLoss() {
        assertEquals(55d, ChargeSessionTracker.gridEnergyKwh(50d), 0.000001d);
        assertEquals(550d, ChargeSessionTracker.totalCost(50d, 10d), 0.000001d);
    }
}
