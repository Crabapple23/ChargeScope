package com.chargescope.app.collector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.chargescope.app.domain.model.ChargingSnapshot
import com.chargescope.app.domain.model.DataQuality
import com.chargescope.app.domain.model.DataSource
import com.chargescope.app.domain.model.Side
import com.chargescope.app.domain.model.SourceRecord
import com.chargescope.app.util.NumberLimits
import kotlin.math.roundToInt

/**
 * Public-API battery collector. No Root required.
 * Every field carries source, raw value, unit, side and quality.
 * Never assumes a device reports current or energy counters.
 */
class BatteryApiCollector(private val context: Context) {

    data class Result(
        val snapshot: ChargingSnapshot,
        val errors: List<String>,
    )

    fun collect(rootAvailable: Boolean): Result {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val records = mutableListOf<SourceRecord>()

        val sticky: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            errors += "ACTION_BATTERY_CHANGED 不可用: ${t.javaClass.simpleName}"
            null
        }

        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (manager == null) errors += "BatteryManager 不可用"

        fun addApi(
            field: String,
            raw: Any?,
            normalized: String?,
            unit: String?,
            side: Side,
            quality: DataQuality = if (raw == null) DataQuality.MISSING else DataQuality.VALID,
        ) {
            records += SourceRecord(
                field = field,
                source = DataSource.ANDROID_API,
                sourcePath = "BatteryManager/$field",
                rawValue = raw?.toString(),
                normalizedValue = normalized,
                unit = unit,
                side = side,
                quality = quality,
                timestampElapsedMs = nowElapsed,
            )
        }

        // SOC: EXTRA_LEVEL / EXTRA_SCALE
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val soc = if (level != null && scale != null) {
            val pct = (level * 100f / scale).roundToInt()
            addApi("soc_percent", "$level/$scale", pct.toString(), "percent", Side.BATTERY_SIDE)
            pct.takeIf { it in 0..100 } ?: run {
                errors += "SOC 越界: $pct"
                null
            }
        } else {
            addApi("soc_percent", null, null, "percent", Side.BATTERY_SIDE)
            null
        }

        // Voltage: EXTRA_VOLTAGE is mV -> uV
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val voltageUv = voltageMv?.let { it * 1_000L }?.takeIf {
            it in NumberLimits.VOLTAGE_UV_MIN..NumberLimits.VOLTAGE_UV_MAX
        }
        addApi(
            "battery_voltage_uv",
            voltageMv,
            voltageUv?.toString(),
            "uV",
            Side.BATTERY_SIDE,
            if (voltageUv != null) DataQuality.VALID else DataQuality.OUT_OF_RANGE,
        )

        // Current: property may be unavailable, stale or device-dependent.
        val currentUa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && manager != null) {
            val raw = try {
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } catch (t: Throwable) {
                errors += "CURRENT_NOW 读取失败: ${t.javaClass.simpleName}"
                Int.MIN_VALUE
            }
            if (raw != Int.MIN_VALUE) {
                val q = if (kotlin.math.abs(raw.toLong()) <= NumberLimits.CURRENT_UA_ABS_MAX) {
                    DataQuality.VALID
                } else {
                    DataQuality.OUT_OF_RANGE
                }
                addApi("battery_current_ua", raw, raw.toString(), "uA", Side.BATTERY_SIDE, q)
                raw.toLong().takeIf { q == DataQuality.VALID }
            } else {
                addApi("battery_current_ua", null, null, "uA", Side.BATTERY_SIDE)
                null
            }
        } else {
            addApi("battery_current_ua", null, null, "uA", Side.BATTERY_SIDE)
            null
        }

        // Temperature: deci-Celsius, no conversion.
        val tempDeci = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val tempValid = tempDeci?.takeIf {
            it in NumberLimits.TEMP_DECIC_MIN..NumberLimits.TEMP_DECIC_MAX
        }
        addApi(
            "battery_temperature_decic",
            tempDeci,
            tempValid?.toString(),
            "deciC",
            Side.BATTERY_SIDE,
            if (tempValid != null) DataQuality.VALID else DataQuality.OUT_OF_RANGE,
        )

        // Charge counter / energy counter: units and reset behaviour are
        // device-dependent. We record raw values but do NOT derive power from
        // them in the MVP.
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val counter = try {
                manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            } catch (t: Throwable) {
                errors += "CHARGE_COUNTER 读取失败: ${t.javaClass.simpleName}"
                Long.MIN_VALUE
            }
            if (counter != Long.MIN_VALUE && counter != 0L) {
                addApi("charge_counter", counter, counter.toString(), "uAh(未校准)", Side.BATTERY_SIDE)
            }
        }

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val chargingStatus = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> null
        }
        addApi("charging_status", status, chargingStatus, null, Side.BATTERY_SIDE)

        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pluggedLabel = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "NONE"
        }
        addApi("plugged_type", plugged, pluggedLabel, null, Side.INPUT_SIDE)

        // Battery power is deliberately left null here. Rust fills it only when
        // voltage/current units and direction have been validated.
        val snapshot = ChargingSnapshot(
            timestampElapsedMs = nowElapsed,
            wallClockTimeMs = nowWall,
            socPercent = soc,
            batteryVoltageUv = voltageUv,
            batteryCurrentUa = currentUa,
            batteryPowerUw = null,
            batteryTemperatureDecic = tempValid,
            inputVoltageUv = null,
            inputCurrentUa = null,
            inputPowerUw = null,
            chargingStatus = chargingStatus,
            sourceRecords = records,
            rootAvailable = rootAvailable,
        )
        return Result(snapshot, errors)
    }
}
