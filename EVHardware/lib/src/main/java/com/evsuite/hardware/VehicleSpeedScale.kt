package com.evsuite.hardware

import kotlin.math.abs

/**
 * What `PERF_VEHICLE_SPEED` is actually in, per firmware generation.
 *
 * AAOS defines the property as metres per second, so every read was multiplied by 3.6. On
 * SWI68 that is wrong: the property already reports km/h, and multiplying a second time
 * scaled every derived distance by the same factor. A 2.4 km drive was recorded twice as
 * 8.46 km and 7.69 km — 124 and 129 km/h average over four minutes of town driving — and
 * dividing by 3.6 gives 2.35 km at 34 km/h, which is the drive that happened.
 *
 * The conversion is therefore evidence-gated like every other firmware-dependent read here.
 * A generation is listed only once a vehicle has shown it, and an unlisted generation keeps
 * the specified conversion: unproven, but wrong in the conservative direction, because a
 * speed read too high makes [VehicleWriteGate] refuse more often rather than less.
 */
object VehicleSpeedScale {

    /** Metres per second to km/h, for generations that really do report the specified unit. */
    private const val MPS_TO_KMH = 3.6f

    /**
     * Generations proven on a vehicle to report km/h directly from `PERF_VEHICLE_SPEED`.
     *
     * SWI68: bundle of 2026-09-04, firmware SWI68-29958-1300R67. Two recorded trips over a
     * route of known length, both over by the same 3.5x, with the adapter odometer as the
     * independent reference. Add a generation here only with the same kind of evidence.
     */
    private val REPORTS_KMH = setOf(FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI69)

    /** True when this generation needs no conversion at all. */
    fun reportsKmh(generation: FirmwareInfo.Gen): Boolean = generation in REPORTS_KMH

    /**
     * The raw property value as km/h.
     *
     * The property is signed — negative in reverse — and every consumer here asks whether the
     * car is moving, so the magnitude is what comes back.
     */
    fun toKmh(raw: Float, generation: FirmwareInfo.Gen): Float =
        if (reportsKmh(generation)) abs(raw) else abs(raw) * MPS_TO_KMH
}
