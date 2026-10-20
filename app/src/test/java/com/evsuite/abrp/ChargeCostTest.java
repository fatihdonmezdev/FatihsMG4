package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChargeCostTest {

    @Test
    public void testAcLossCalibration() {
        // Calibrated from user charger screen observations:
        // 6A -> 21%, 10A -> 11%, 16A -> 5%
        assertEquals(21.0d, ChargeSessionTracker.calculateAcLossPercent(6.0d), 0.001d);
        assertEquals(16.0d, ChargeSessionTracker.calculateAcLossPercent(8.0d), 0.001d);
        assertEquals(11.0d, ChargeSessionTracker.calculateAcLossPercent(10.0d), 0.001d);
        assertEquals(8.0d, ChargeSessionTracker.calculateAcLossPercent(13.0d), 0.001d);
        assertEquals(5.0d, ChargeSessionTracker.calculateAcLossPercent(16.0d), 0.001d);
        assertEquals(5.0d, ChargeSessionTracker.calculateAcLossPercent(32.0d), 0.001d);
    }

    @Test
    public void testLossByTypeAndPower() {
        // DC charging (>= 8 kW or marked DC) has 5% loss
        assertEquals(5.0d, ChargeSessionTracker.calculateLossPercent("DC", 50.0f, null), 0.001d);
        assertEquals(5.0d, ChargeSessionTracker.calculateLossPercent("AC", 9.0f, null), 0.001d);

        // AC charging at 2.3 kW (~10A at 230V) -> 11% loss
        assertEquals(11.0d, ChargeSessionTracker.calculateLossPercent("AC", 2.3f, null), 0.1d);

        // AC charging at 1.38 kW (~6A at 230V) -> 21% loss
        assertEquals(21.0d, ChargeSessionTracker.calculateLossPercent("AC", 1.38f, null), 0.1d);

        // AC charging at 3.68 kW (~16A at 230V) -> 5% loss
        assertEquals(5.0d, ChargeSessionTracker.calculateLossPercent("AC", 3.68f, null), 0.1d);
    }

    @Test
    public void costCalculationsWithLoss() {
        // 50 kWh at 21% loss (6A) -> 60.5 kWh
        assertEquals(60.5d, ChargeSessionTracker.gridEnergyKwh(50.0d, 21.0d), 0.000001d);
        assertEquals(181.5d, ChargeSessionTracker.totalCost(50.0d, 21.0d, 3.0d), 0.000001d);

        // 50 kWh at 11% loss (10A) -> 55.5 kWh
        assertEquals(55.5d, ChargeSessionTracker.gridEnergyKwh(50.0d, 11.0d), 0.000001d);

        // 50 kWh at 5% loss (16A / DC) -> 52.5 kWh
        assertEquals(52.5d, ChargeSessionTracker.gridEnergyKwh(50.0d, 5.0d), 0.000001d);

        // Default legacy overload (10%)
        assertEquals(55.0d, ChargeSessionTracker.gridEnergyKwh(50.0d), 0.000001d);
        assertEquals(550.0d, ChargeSessionTracker.totalCost(50.0d, 10.0d, 10.0d), 0.000001d);

        // Station energy direct cost
        assertEquals(500.0d, ChargeSessionTracker.totalCost(50.0d, 10.0d), 0.000001d);
    }

    @Test
    public void testEffectiveGridEnergyPrefersStationTelemetry() {
        // When actual station telemetry is present, it is used directly
        ChargeSessionTracker.Snapshot snapshot = new ChargeSessionTracker.Snapshot(
                false, 1000L, 3600L, 20.0f, 80.0f,
                28.2d, 30.5d, 30.5d, 8.15d, 5.0d, "DC", null, null, new java.util.ArrayList<>()
        );
        assertEquals(30.5d, snapshot.gridEnergyKwh, 0.001d);
        assertEquals(152.5d, snapshot.totalCost, 0.001d);
    }
}
