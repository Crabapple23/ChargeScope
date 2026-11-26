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

/** Chart data in watts. NaN marks a missing point. */
data class Series(val batteryW: List<Float>, val inputW: List<Float>, val spanSec: Int)

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
)

data class UiState(
    val latest: Snapshot? = null,
    val series: Series = Series(emptyList(), emptyList(), 0),
    val analysis: Analysis? = null,
    val metrics: Metrics? = null,
    val recording: Boolean = false,
    val recordedRows: Long = 0,
    val rootError: String? = null,
    val notice: String? = null,
)
