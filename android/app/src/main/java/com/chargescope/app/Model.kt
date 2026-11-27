package com.chargescope.app

/** One power_supply node (or the synthetic "dumpsys" node) read through a privileged channel. */
data class RootNode(val name: String, val props: Map<String, String>)

/** One sample. Missing values are null, never 0. */
data class Snapshot(
    val elapsedMs: Long,
    val wallMs: Long,
    val levelPct: Int?,
    val status: Int?,
    val plugged: Int,
    val health: Int?,
    val voltageMv: Int?,
    val tempDeci: Int?,
    val currentUa: Long?,
    val currentAvgUa: Long?,
    val chargeCounterUah: Long?,
    val rootNodes: List<RootNode> = emptyList(),
    val chargeTimeRemainingMs: Long? = null,
    val designCapacityMah: Int? = null,
) {
    val isCharging: Boolean get() = status == 2
    val isPlugged: Boolean get() = plugged != 0
}

enum class Status(val label: String) {
    CONFIRMED("已确认"),
    CONFLICT("证据冲突"),
    POSSIBLE("可能"),
    UNKNOWN("无法判断"),
    NOT_CHARGING("未连接充电器"),
}

data class Evidence(val level: Int, val text: String)

data class Analysis(
    val status: Status,
    val headline: String,
    val evidence: List<Evidence>,
    val limitations: List<String>,
    /** Narrowed-down candidates (never a confirmation). */
    val candidates: List<String> = emptyList(),
    /** Stable identity of the result, used to de-duplicate archive entries. Empty = not archivable. */
    val key: String = "",
    /** Protocol(s) explicitly reported by the driver (L4), or null. */
    val driver: String? = null,
    /** Best predicted protocol from input-side evidence / spec table, or null. */
    val predicted: String? = null,
    /** Plain-language summary combining driver report and prediction. */
    val description: String = "",
    /** Electrical specification reference for the confirmed protocol (null if not confirmed). */
    val protocolSpec: String? = null,
    /** Gear/tier specifications for candidate/predicted protocols. */
    val candidateSpecs: List<String> = emptyList(),
)

data class Metrics(
    val count: Int,
    val durationMs: Long,
    val socRatePerMin: Double?,
    val avgPowerMw: Double?,
    val peakPowerMw: Double?,
    val avgTempDeci: Double?,
)

data class InputInfo(
    val voltageUv: Long?,
    val currentUa: Long?,
    val usbType: String?,
    /** Charger-advertised ceiling from `dumpsys battery` (not the negotiated value). */
    val advVoltageUv: Long? = null,
    val advCurrentUa: Long? = null,
)

/** Chart data in watts (unboxed FloatArray). NaN marks a missing point. */
class Series(
    val batteryW: FloatArray,
    val inputW: FloatArray,
    val spanSec: Int,
    val maxW: Float,
    val validCount: Int,
    val hasInput: Boolean,
) {
    companion object {
        val EMPTY = Series(FloatArray(0), FloatArray(0), 0, 0f, 0, false)
    }
}

data class ArchiveEntry(
    val wallMs: Long,
    val status: String,
    val headline: String,
    val key: String,
    val evidence: List<String>,
    val candidates: List<String>,
    val maxVinV: Double?,
    val maxInputW: Double?,
    val maxBatteryW: Double?,
    val levelPct: Int?,
    val device: String,
    val description: String = "",
    val specText: String = "",
    val note: String = "",
)

data class UiState(
    val latest: Snapshot? = null,
    val batteryCurrentMa: Double? = null,
    val batteryPowerW: Double? = null,
    val inputPowerW: Double? = null,
    val series: Series = Series.EMPTY,
    val analysis: Analysis? = null,
    val metrics: Metrics? = null,
    val recording: Boolean = false,
    val recordedRows: Long = 0,
    val rootError: String? = null,
    val notice: String? = null,
)
