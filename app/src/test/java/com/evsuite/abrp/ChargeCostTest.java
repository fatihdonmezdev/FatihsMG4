package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChargeCostTest {
    @Test
    public void costUsesStationEnergyDirectly() {
        assertEquals(50d, ChargeSessionTracker.gridEnergyKwh(50d), 0.000001d);
        assertEquals(500d, ChargeSessionTracker.totalCost(50d, 10d), 0.000001d);
    }
}
