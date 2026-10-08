package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ConsumptionMathTest {

    /** One minute, comfortably inside {@link ConsumptionMath#MAX_GAP_HOURS}. */
    private static final double DT = 1d / 60d;

    /** Float inputs widen to double exactly, but the arithmetic is done in float. */
    private static final double TOLERANCE = 1e-6;

    @Test
    public void nonPositiveOrOversizedIntervalsAreDropped() {
        assertNull(ConsumptionMath.integrate(0d, 50f, 50f, 20f, 20f, 80f, 79f, false));
        assertNull(ConsumptionMath.integrate(-1d, 50f, 50f, 20f, 20f, 80f, 79f, false));
        // A process pause must not be bridged into invented driving data.
        assertNull(ConsumptionMath.integrate(ConsumptionMath.MAX_GAP_HOURS + 0.001d,
                50f, 50f, 20f, 20f, 80f, 79f, false));
    }

    @Test
    public void distanceIsTheTrapezoidOfTheTwoSpeeds() {
        // 20 and 40 km/h average to 30, which sits on the uncalibrated side of the band.
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 20f, 40f, null, null, null, null, false);
        assertEquals(30d * DT, step.km, TOLERANCE);
        assertEquals(DT, step.hours, TOLERANCE);
    }

    @Test
    public void speedBelowTheDeadBandContributesNothingToDrivingTotals() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 2f, 2f, 8f, 8f, 80f, 79f, false);
        assertEquals(0d, step.km, TOLERANCE);
        assertEquals(0d, step.kwh, TOLERANCE);
        assertEquals(0d, step.hours, TOLERANCE);
        assertEquals(0d, step.socDrop, TOLERANCE);
    }

    @Test
    public void motorwayAndMidRangeSpeedsAreCalibrated() {
        ConsumptionMath.Step mid =
                ConsumptionMath.integrate(DT, 50f, 50f, null, null, null, null, false);
        assertEquals(50d * 1.0015d * DT, mid.km, TOLERANCE);

        ConsumptionMath.Step motorway =
                ConsumptionMath.integrate(DT, 120f, 120f, null, null, null, null, false);
        assertEquals(120d * 1.0035d * DT, motorway.km, TOLERANCE);
    }

    @Test
    public void energyIsTheTrapezoidOfNetPowerAndRegenerationSubtracts() {
        ConsumptionMath.Step drawing =
                ConsumptionMath.integrate(DT, 60f, 60f, 10f, 20f, null, null, false);
        assertEquals(15d * DT, drawing.kwh, TOLERANCE);

        // Regeneration keeps its sign: this is what the car's own kWh figure does, and
        // discarding it is what used to make our number read high.
        ConsumptionMath.Step regenerating =
                ConsumptionMath.integrate(DT, 60f, 60f, -20f, -20f, null, null, false);
        assertEquals(-20d * DT, regenerating.kwh, TOLERANCE);
    }

    @Test
    public void standstillPowerFlowingInIsNotCreditedToTheDrive() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 0f, 0f, -7f, -7f, null, null, false);
        assertEquals(0d, step.kwh, TOLERANCE);
    }

    @Test
    public void parkedClimateUseIsExcludedFromDrivingConsumption() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 0f, 0f, 2f, 2f, null, null, false);
        assertEquals(0d, step.kwh, TOLERANCE);
        assertEquals(0d, step.hours, TOLERANCE);
    }

    @Test
    public void anImplausiblePowerReadingSuppressesEnergyButNotDistance() {
        ConsumptionMath.Step step = ConsumptionMath.integrate(
                DT, 60f, 60f, 20f, ConsumptionMath.MAX_PLAUSIBLE_KW + 1f, null, null, false);
        assertEquals(0d, step.kwh, TOLERANCE);
        assertEquals(60d * 1.0015d * DT, step.km, TOLERANCE);
        assertEquals(DT, step.hours, TOLERANCE);
    }

    @Test
    public void aMissingPowerReadingSuppressesEnergyOnlyForThatInterval() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 60f, 60f, 20f, null, null, null, false);
        assertEquals(0d, step.kwh, TOLERANCE);
        assertEquals(DT, step.hours, TOLERANCE);
    }

    @Test
    public void aMissingSpeedReadingFallsBackToThePreviousOne() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 60f, null, 20f, 20f, null, null, false);
        assertEquals(60d * 1.0015d * DT, step.km, TOLERANCE);
        assertEquals(20d * DT, step.kwh, TOLERANCE);
    }

    @Test
    public void reverseIsDistanceTravelled() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, -10f, -10f, null, null, null, null, false);
        assertEquals(10d * DT, step.km, TOLERANCE);
    }

    @Test
    public void socDropIsCountedOnlyOffTheCharger() {
        ConsumptionMath.Step driving =
                ConsumptionMath.integrate(DT, 60f, 60f, 20f, 20f, 80f, 79.5f, false);
        assertEquals(0.5d, driving.socDrop, TOLERANCE);

        ConsumptionMath.Step charging =
                ConsumptionMath.integrate(DT, 0f, 0f, -40f, -40f, 50f, 52f, true);
        assertEquals(0d, charging.socDrop, TOLERANCE);
    }

    @Test
    public void aMissingSocEndpointYieldsNoDrop() {
        ConsumptionMath.Step step =
                ConsumptionMath.integrate(DT, 60f, 60f, 20f, 20f, null, 79f, false);
        assertEquals(0d, step.socDrop, TOLERANCE);
    }

    @Test
    public void aSteadyDriveIntegratesToTheExpectedAverage() {
        // 90 km/h at 15 kW for an hour: ~16.6 kWh/100 km, a plausible MG4 motorway figure.
        KahanSum km = new KahanSum();
        KahanSum kwh = new KahanSum();
        for (int i = 0; i < 3600; i++) {
            ConsumptionMath.Step step = ConsumptionMath.integrate(
                    1d / 3600d, 90f, 90f, 15f, 15f, null, null, false);
            km.add(step.km);
            kwh.add(step.kwh);
        }
        assertEquals(90d * 1.0015d, km.get(), 1e-4);
        assertEquals(15d, kwh.get(), 1e-9);
        assertEquals(16.64d, kwh.get() * 100d / km.get(), 0.01d);
    }

    @Test
    public void regenerationOffsetsTheDrawItFollows() {
        // Ten minutes pulling 30 kW, then ten minutes recovering 6 kW: the net is what the
        // dashboard shows, and the old gross-only fallback reported 5.0 kWh for this.
        KahanSum kwh = new KahanSum();
        for (int i = 0; i < 10; i++) {
            kwh.add(ConsumptionMath.integrate(DT, 60f, 60f, 30f, 30f, null, null, false).kwh);
        }
        for (int i = 0; i < 10; i++) {
            kwh.add(ConsumptionMath.integrate(DT, 60f, 60f, -6f, -6f, null, null, false).kwh);
        }
        assertEquals(4d, kwh.get(), 1e-4);
    }
}
