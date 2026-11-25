package com.chargescope.app.collector

import android.os.SystemClock
import com.chargescope.app.domain.RustBridge
import com.chargescope.app.domain.model.DataQuality
import com.chargescope.app.domain.model.DataSource
import com.chargescope.app.domain.model.Side
import com.chargescope.app.domain.model.SourceRecord
import com.chargescope.app.root.RootBridge

/**
 * Dynamically scans power_supply nodes rather than hardcoding one model's path.
 * Every read keeps raw string + path + timestamp + quality. Missing nodes are
 * recorded as MISSING, never as 0, and never crash the collection flow.
 */
class RootSysfsCollector(private val root: RootBridge) {

    data class Result(
        val records: List<SourceRecord>,
        val discoveredNodes: List<String>,
        val errors: List<String>,
    )

    /** Candidate properties; all optional and must be verified per device. */
    private val candidateFields = listOf(
        "type", "status", "online", "present", "capacity",
        "voltage_now", "current_now", "current_avg", "power_now",
        "voltage_max", "current_max", "usb_type", "real_type", "charge_type",
        "temp", "input_current_limit", "input_voltage_limit",
        "constant_charge_current", "constant_charge_voltage", "charge_counter",
    )

    suspend fun scan(): Result {
        val errors = mutableListOf<String>()
        val listing = root.listPowerSupply()
        if (!listing.ok) {
            errors += "power_supply 目录读取失败: ${listing.error ?: listing.stderr.take(120)}"
            return Result(emptyList(), emptyList(), errors)
        }

        // Treat each symlink name as a candidate node; verify by reading.
        val nodes = listing.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('/') && it.length <= 64 }
            .distinct()
            .take(MAX_NODES)
            .toList()

        val records = mutableListOf<SourceRecord>()
        for (node in nodes) {
            for (field in candidateFields) {
                val path = "/sys/class/power_supply/$node/$field"
                if (!RootBridge.isSafeSysfsPath(path)) continue
                val now = SystemClock.elapsedRealtime()
                val result = root.readFile(path)
                if (!result.ok) {
                    records += SourceRecord(
                        field = field,
                        source = DataSource.SYSFS,
                        sourcePath = path,
                        rawValue = null,
                        normalizedValue = null,
                        unit = null,
                        side = Side.UNKNOWN_SIDE,
                        quality = if (result.error?.contains("超时") == true) {
                            DataQuality.STALE
                        } else {
                            DataQuality.PERMISSION_DENIED
                        },
                        timestampElapsedMs = now,
                    )
                    continue
                }
                val raw = result.stdout.trim()
                // Rust owns field semantics; Kotlin passes side hint as UNKNOWN
                // unless a verified device profile overrides it later.
                records += RustBridge.normalizeRaw(
                    raw = raw,
                    field = field,
                    sourcePath = path,
                    side = Side.UNKNOWN_SIDE,
                    elapsedMs = now,
                )
            }
        }
        return Result(records, nodes, errors)
    }

    companion object {
        private const val MAX_NODES = 8
    }
}
