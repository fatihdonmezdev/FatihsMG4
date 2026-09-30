package com.evsuite.hardware.telemetry

import android.content.Context
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicClimate
import com.evsuite.hardware.saic.SaicHub

/** Injectable signal seam: production uses [EvHardwareEnergySignalSource], tests use a fake. */
interface EnergySignalSource {
    fun firmware(): FirmwareInfo.Gen
    fun socPercent(): Float?
    fun rangeKm(): Float?
    fun speedKmh(): Float?
    fun batteryPowerKw(): Float?
    fun outsideTempCelsius(): Float?
    fun cabinTempCelsius(): Float?
    fun batteryTempCelsius(): Float?
    fun batteryEnergyKwh(): Float?
    fun batteryCapacityKwh(): Float?
    fun odometerKm(): Float?
    fun chargePortConnected(): Boolean?
    fun chargingStatus(): Int?
    /** The vehicle's own counters since its last charge; null where the car keeps none. */
    fun vehicleConsumedKwh(): Float? = null
    fun vehicleRegeneratedKwh(): Float? = null
    fun parked(): Boolean?
    fun batteryVoltageV(): Float? = null
    fun batteryCurrentA(): Float? = null
    fun climate(): ClimateSnapshot
    fun tirePressures(): TirePressureSnapshot
    /** Probe-only values excluded from [EnergySnapshot] and normal consumers. */
    fun evidenceProbes(): List<TelemetryEvidenceProbe> = emptyList()
}

class EvHardwareEnergySignalSource(context: Context) : EnergySignalSource {
    init {
        val appContext = context.applicationContext
        EVHardware.init(appContext)
        SaicHub.connect(appContext)
    }

    override fun firmware() = FirmwareInfo.getGeneration()
    override fun socPercent() =
        SaicCharging.stateOfChargePercent() ?: EVHardware.getVendorBatterySocPercent()
    override fun rangeKm() =
        SaicCharging.rangeKm()?.toFloat()
            ?: EVHardware.getStandardRangeKm()
            ?: EVHardware.getVendorRangeKm()?.toFloat()
    override fun speedKmh() = EVHardware.getVehicleSpeedKmh()
    // The standard property first, the vendor pack pair second: SWI68 answers no
    // EV_INSTANTANEOUS_CHARGE_RATE but does publish the voltage and the current it is made of.
    override fun batteryPowerKw() =
        EVHardware.getBatteryPowerKw() ?: EVHardware.getVendorBatteryPowerKw()
    override fun outsideTempCelsius() =
        SaicClimate.outsideTempCelsius() ?: EVHardware.getOutsideTempCelsius()
    override fun cabinTempCelsius() = EVHardware.getCabinTemperatureCelsius()
    override fun batteryTempCelsius() = EVHardware.getBatteryTemperatureCelsius()
    override fun batteryEnergyKwh() = EVHardware.getBatteryEnergyKwh()
    override fun batteryCapacityKwh() = EVHardware.getBatteryCapacityKwh()
    override fun odometerKm() = EVHardware.getOdometerKm()
    override fun chargePortConnected() = EVHardware.isChargePortConnected()
    // The charging service first, the vendor property second: it answers on units where
    // the service does not bind.
    override fun chargingStatus() =
        SaicCharging.chargingStatus() ?: EVHardware.getVendorChargeStatus()
    override fun vehicleConsumedKwh() = SaicCharging.consumedKwhSinceCharge()
    override fun vehicleRegeneratedKwh() = SaicCharging.regeneratedKwhSinceCharge()
    override fun parked() = EVHardware.isVehicleInPark()
    override fun batteryVoltageV() = EVHardware.getVendorBatteryVoltageV()
    override fun batteryCurrentA() = EVHardware.getVendorBatteryCurrentA()
    override fun tirePressures() = TirePressureSnapshot(
        frontLeftKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.FRONT_LEFT),
        frontRightKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.FRONT_RIGHT),
        rearLeftKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.REAR_LEFT),
        rearRightKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.REAR_RIGHT),
    )
    override fun evidenceProbes() = listOf(
        TelemetryEvidenceProbe(
            TelemetryEvidenceRecorder.CANDIDATE_CURRENT_BATTERY_CAPACITY_WH,
            EVHardware.probeCurrentBatteryCapacityWh()?.toDouble(),
        )
    )
    override fun climate() = ClimateSnapshot(
        powerOn = SaicClimate.powerOn(),
        acOn = SaicClimate.acOn(),
        autoOn = SaicClimate.autoOn(),
        econOn = SaicClimate.econOn(),
        recirculationOn = SaicClimate.recirculationOn(),
        fanLevel = SaicClimate.fanLevel(),
        fanLevelMax = SaicClimate.fanLevelMax(),
        driverTargetCelsius = SaicClimate.driverTemp()?.toFloat()
            ?: EVHardware.getTemperatureSetCelsius(),
        // The passenger setpoint is its own signal only when the car publishes one: the vendor
        // service reads it with a default of 68, which its own converter maps to -1, and -1 is
        // dropped upstream. With a single zone the driver's setpoint is what both seats get,
        // so that is what the passenger reads — the same number, because it is the same air.
        passengerTargetCelsius = SaicClimate.passengerTemp()?.toFloat()
            ?: SaicClimate.driverTemp()?.toFloat()
            ?: EVHardware.getTemperatureSetCelsius(),
    )
}

/** Builds a coherent snapshot from one shared source; every unavailable signal remains null. */
class EnergyTelemetryReader(private val source: EnergySignalSource) {
    constructor(context: Context) : this(EvHardwareEnergySignalSource(context))

    fun read(nowMs: Long = System.currentTimeMillis()) = EnergySnapshot(
        timestampMs = nowMs,
        firmware = source.firmware(),
        socPercent = source.socPercent()?.takeIf { it.isFinite() && it in 0f..100f },
        rangeKm = source.rangeKm()?.takeIf { it.isFinite() && it >= 0f },
        speedKmh = source.speedKmh()?.takeIf { it.isFinite() && it >= 0f },
        batteryPowerKw = source.batteryPowerKw()?.takeIf { it.isFinite() },
        outsideTempCelsius = source.outsideTempCelsius()?.takeIf { it.isFinite() },
        cabinTempCelsius = source.cabinTempCelsius()?.takeIf { it.isFinite() },
        batteryTempCelsius = source.batteryTempCelsius()?.takeIf { it.isFinite() },
        batteryEnergyKwh = source.batteryEnergyKwh()?.takeIf { it.isFinite() && it >= 0f },
        batteryCapacityKwh = source.batteryCapacityKwh()?.takeIf { it.isFinite() && it > 0f },
        odometerKm = source.odometerKm()?.takeIf { it.isFinite() && it >= 0f },
        chargePortConnected = source.chargePortConnected(),
        chargingStatus = source.chargingStatus(),
        vehicleConsumedKwh = source.vehicleConsumedKwh()?.takeIf { it.isFinite() && it >= 0f },
        vehicleRegeneratedKwh = source.vehicleRegeneratedKwh()?.takeIf { it.isFinite() && it >= 0f },
        parked = source.parked(),
        climate = source.climate(),
        tirePressures = source.tirePressures(),
        batteryVoltageV = source.batteryVoltageV()?.takeIf { it.isFinite() },
        batteryCurrentA = source.batteryCurrentA()?.takeIf { it.isFinite() },
    )

    /** Reads CP-004 candidates only when an unstable evidence capture explicitly requests them. */
    fun readEvidence(nowMs: Long = System.currentTimeMillis()) = TelemetryEvidenceSample(
        snapshot = read(nowMs),
        probes = source.evidenceProbes().map { probe ->
            probe.copy(value = probe.value?.takeIf { it.isFinite() })
        },
    )
}
