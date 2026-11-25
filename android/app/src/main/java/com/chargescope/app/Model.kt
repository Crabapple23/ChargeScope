package com.chargescope.app

/** One power_supply node read through root (read-only). */
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
)

data class Metrics(
    val count: Int,
    val durationMs: Long,
    val socRatePerMin: Double?,
    val avgPowerMw: Double?,
    val peakPowerMw: Double?,
    val avgTempDeci: Double?,
)

data class InputInfo(val voltageUv: Long?, val currentUa: Long?, val usbType: String?)

data class UiState(
    val latest: Snapshot? = null,
    val powerSeries: List<Float> = emptyList(),
    val analysis: Analysis? = null,
    val metrics: Metrics? = null,
    val recording: Boolean = false,
    val recordedRows: Long = 0,
    val rootError: String? = null,
    val notice: String? = null,
)
