package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSpeedScaleTest {

    @Test fun `SWI68 and SWI69 report km per hour and are not converted again`() {
        assertTrue(VehicleSpeedScale.reportsKmh(FirmwareInfo.Gen.SWI68))
        assertTrue(VehicleSpeedScale.reportsKmh(FirmwareInfo.Gen.SWI69))
        assertEquals(34.4f, VehicleSpeedScale.toKmh(34.4f, FirmwareInfo.Gen.SWI68), 0.001f)
        assertEquals(34.4f, VehicleSpeedScale.toKmh(34.4f, FirmwareInfo.Gen.SWI69), 0.001f)
    }

    @Test fun `an unproven generation keeps the specified conversion`() {
        FirmwareInfo.Gen.entries
            .filterNot { it == FirmwareInfo.Gen.SWI68 || it == FirmwareInfo.Gen.SWI69 }
            .forEach { generation ->
                assertFalse("$generation is not proven", VehicleSpeedScale.reportsKmh(generation))
                assertEquals(
                    "$generation must keep the AAOS conversion",
                    36.0f,
                    VehicleSpeedScale.toKmh(10.0f, generation),
                    0.001f,
                )
            }
    }

    @Test fun `reverse is a speed, not a negative one`() {
        assertEquals(12.0f, VehicleSpeedScale.toKmh(-12.0f, FirmwareInfo.Gen.SWI68), 0.001f)
        assertEquals(10.0f, VehicleSpeedScale.toKmh(-10.0f, FirmwareInfo.Gen.SWI69), 0.001f)
    }

    @Test fun `the drive that proved it now integrates to the route it followed`() {
        // The recorded trip: 245836 ms at a steady 34.4 km/h reads as 2.35 km, not 8.46 km.
        val hours = 245_836L / 3_600_000.0
        val kmh = VehicleSpeedScale.toKmh(34.4f, FirmwareInfo.Gen.SWI68)
        assertEquals(2.35, kmh * hours, 0.02)
    }

    @Test fun `standstill stays standstill in every generation`() {
        FirmwareInfo.Gen.entries.forEach {
            assertEquals(0.0f, VehicleSpeedScale.toKmh(0.0f, it), 0.0f)
        }
    }
}
