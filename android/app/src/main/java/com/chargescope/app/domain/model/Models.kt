package com.chargescope.app.domain.model

/**
 * Kotlin mirror of docs/CONTRACT.md v0.1.0.
 * Field names and semantics MUST stay in sync with crate cs-model.
 * Null means unknown/missing — never substitute 0.
 */

enum class DataSource { ANDROID_API, SYSFS, ROOT_SHELL, DEVICE_ADAPTER }
enum class DataQuality { VALID, STALE, OUT_OF_RANGE, PARSE_ERROR, PERMISSION_DENIED, MISSING }
enum class Side { BATTERY_SIDE, INPUT_SIDE, UNKNOWN_SIDE }
enum class EvidenceLevel(val rank: Int) { L1(1), L2(2), L3(3), L4(4) }

enum class AnalysisStatus {
    CONFIRMED, HIGH_CONFIDENCE, POSSIBLE, INDETERMINATE, UNKNOWN, UNSUPPORTED;

    /** Human label; never a probability. */
    val label: String get() = when (this) {
        CONFIRMED -> "已确认（接口直接报告）"
        HIGH_CONFIDENCE -> "高可信推断"
        POSSIBLE -> "可能"
        INDETERMINATE -> "无法区分"
        UNKNOWN -> "未知"
        UNSUPPORTED -> "当前设备不支持采集"
    }
}

enum class Protocol {
    PD, PD_PPS, QC2, QC3, QC4, UFCS, SCP, FCP, AFC,
    MI_PRIVATE, VOOC, SUPER_VOOC, OTHER, NONE;

    val label: String get() = when (this) {
        PD -> "USB PD"
        PD_PPS -> "USB PD PPS"
        QC2 -> "QC 2.0"
        QC3 -> "QC 3.0"
        QC4 -> "QC 4+"
        UFCS -> "UFCS"
        SCP -> "SCP"
        FCP -> "FCP"
        AFC -> "AFC"
        MI_PRIVATE -> "小米私有快充"
        VOOC -> "VOOC"
        SUPER_VOOC -> "SuperVOOC"
        OTHER -> "其他协议"
        NONE -> "未识别"
    }
}

data class SourceRecord(
    val field: String,
    val source: DataSource,
    val sourcePath: String?,
    val rawValue: String?,
    val normalizedValue: String?,
    val unit: String?,
    val side: Side,
    val quality: DataQuality,
    val timestampElapsedMs: Long,
)

data class ChargingSnapshot(
    val timestampElapsedMs: Long = 0,
    val wallClockTimeMs: Long = 0,
    val socPercent: Int? = null,
    val batteryVoltageUv: Long? = null,
    val batteryCurrentUa: Long? = null,
    val batteryPowerUw: Long? = null,
    val batteryTemperatureDecic: Int? = null,
    val inputVoltageUv: Long? = null,
    val inputCurrentUa: Long? = null,
    val inputPowerUw: Long? = null,
    val usbType: String? = null,
    val chargeType: String? = null,
    val chargingStatus: String? = null,
    val sourceRecords: List<SourceRecord> = emptyList(),
    val rootAvailable: Boolean = false,
) {
    val hasAnyMeasurement: Boolean
        get() = socPercent != null || batteryVoltageUv != null || batteryCurrentUa != null ||
            batteryPowerUw != null || batteryTemperatureDecic != null ||
            inputVoltageUv != null || inputCurrentUa != null || inputPowerUw != null
}

data class ProtocolCandidate(
    val protocol: Protocol,
    val score: Double,
    val evidenceLevel: EvidenceLevel,
    val reasons: List<String>,
)

data class ProtocolEvidence(
    val protocol: Protocol?,
    val level: EvidenceLevel,
    val source: String,
    val observation: String,
    val supports: Boolean,
    val quality: DataQuality,
)

data class EvidenceConflict(
    val protocolA: Protocol?,
    val protocolB: Protocol?,
    val description: String,
)

data class ProtocolAnalysis(
    val status: AnalysisStatus,
    val primaryCandidate: Protocol?,
    val candidates: List<ProtocolCandidate>,
    val evidence: List<ProtocolEvidence>,
    val conflicts: List<EvidenceConflict>,
    val explanation: String,
    val limitations: List<String>,
    val analyzedAtElapsedMs: Long,
    val algorithmVersion: String,
)

data class DeviceProfile(
    val model: String? = null,
    val socName: String? = null,
    val androidVersion: String? = null,
    val rootAvailable: Boolean = false,
    val verifiedPaths: List<String> = emptyList(),
    val supportedProtocols: List<Protocol> = emptyList(),
    val note: String? = null,
)

data class ChargingMetrics(
    val sampleCount: Int = 0,
    val durationMs: Long = 0,
    val socRatePercentPerMin: Double? = null,
    val avgBatteryPowerUw: Long? = null,
    val peakBatteryPowerUw: Long? = null,
    val avgTemperatureDecic: Int? = null,
    val staleRatio: Double = 0.0,
)

data class AnalysisBundle(
    val metrics: ChargingMetrics,
    val analysis: ProtocolAnalysis,
)

data class ChargingSession(
    val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val deviceModel: String?,
    val chargerNote: String? = null,
    val peakPowerUw: Long? = null,
    val averagePowerUw: Long? = null,
    val sampleCount: Int = 0,
    val algorithmVersion: String = "",
)
