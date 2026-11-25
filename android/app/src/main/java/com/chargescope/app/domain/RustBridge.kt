package com.chargescope.app.domain

import com.chargescope.app.domain.model.AnalysisBundle
import com.chargescope.app.domain.model.AnalysisStatus
import com.chargescope.app.domain.model.ChargingMetrics
import com.chargescope.app.domain.model.ChargingSnapshot
import com.chargescope.app.domain.model.DataQuality
import com.chargescope.app.domain.model.DataSource
import com.chargescope.app.domain.model.DeviceProfile
import com.chargescope.app.domain.model.EvidenceConflict
import com.chargescope.app.domain.model.EvidenceLevel
import com.chargescope.app.domain.model.Protocol
import com.chargescope.app.domain.model.ProtocolAnalysis
import com.chargescope.app.domain.model.ProtocolCandidate
import com.chargescope.app.domain.model.ProtocolEvidence
import com.chargescope.app.domain.model.Side
import com.chargescope.app.domain.model.SourceRecord
import uniffi.chargescope.analyze as ffiAnalyze
import uniffi.chargescope.computeMetrics as ffiComputeMetrics
import uniffi.chargescope.normalizeSourceRecord as ffiNormalize
import uniffi.chargescope.algorithmVersion as ffiAlgorithmVersion

/**
 * Single boundary between Kotlin domain types and UniFFI-generated bindings.
 * UI must never import uniffi.chargescope.* directly.
 *
 * UniFFI Kotlin backend emits enum entries in SCREAMING_SNAKE_CASE
 * (Rust `BatterySide` -> Kotlin `BATTERY_SIDE`), verified against the official
 * UniFFI proc-macro Enum documentation. If a different naming is observed after
 * running scripts/build-rust.*, update ONLY this file's mapping helpers.
 */
object RustBridge {

    fun algorithmVersion(): String = ffiAlgorithmVersion()

    fun normalizeRaw(
        raw: String,
        field: String,
        sourcePath: String,
        side: Side,
        elapsedMs: Long,
    ): SourceRecord = toDomain(ffiNormalize(raw, field, sourcePath, toFfi(side), elapsedMs))

    fun metrics(snapshots: List<ChargingSnapshot>): ChargingMetrics =
        toDomain(ffiComputeMetrics(snapshots.map(::toFfi)))

    fun analyze(
        snapshots: List<ChargingSnapshot>,
        profile: DeviceProfile?,
        nowElapsedMs: Long,
    ): AnalysisBundle = toDomain(ffiAnalyze(snapshots.map(::toFfi), profile?.let(::toFfi), nowElapsedMs))

    // ---------- Kotlin domain -> UniFFI ----------

    private fun toFfi(s: Side): uniffi.chargescope.Side = when (s) {
        Side.BATTERY_SIDE -> uniffi.chargescope.Side.BATTERY_SIDE
        Side.INPUT_SIDE -> uniffi.chargescope.Side.INPUT_SIDE
        Side.UNKNOWN_SIDE -> uniffi.chargescope.Side.UNKNOWN_SIDE
    }

    private fun toFfi(q: DataQuality): uniffi.chargescope.DataQuality = when (q) {
        DataQuality.VALID -> uniffi.chargescope.DataQuality.VALID
        DataQuality.STALE -> uniffi.chargescope.DataQuality.STALE
        DataQuality.OUT_OF_RANGE -> uniffi.chargescope.DataQuality.OUT_OF_RANGE
        DataQuality.PARSE_ERROR -> uniffi.chargescope.DataQuality.PARSE_ERROR
        DataQuality.PERMISSION_DENIED -> uniffi.chargescope.DataQuality.PERMISSION_DENIED
        DataQuality.MISSING -> uniffi.chargescope.DataQuality.MISSING
    }

    private fun toFfi(d: DataSource): uniffi.chargescope.DataSource = when (d) {
        DataSource.ANDROID_API -> uniffi.chargescope.DataSource.ANDROID_API
        DataSource.SYSFS -> uniffi.chargescope.DataSource.SYSFS
        DataSource.ROOT_SHELL -> uniffi.chargescope.DataSource.ROOT_SHELL
        DataSource.DEVICE_ADAPTER -> uniffi.chargescope.DataSource.DEVICE_ADAPTER
    }

    private fun toFfi(l: EvidenceLevel): uniffi.chargescope.EvidenceLevel = when (l) {
        EvidenceLevel.L1 -> uniffi.chargescope.EvidenceLevel.L1
        EvidenceLevel.L2 -> uniffi.chargescope.EvidenceLevel.L2
        EvidenceLevel.L3 -> uniffi.chargescope.EvidenceLevel.L3
        EvidenceLevel.L4 -> uniffi.chargescope.EvidenceLevel.L4
    }

    private fun toFfi(p: Protocol): uniffi.chargescope.Protocol = when (p) {
        Protocol.PD -> uniffi.chargescope.Protocol.PD
        Protocol.PD_PPS -> uniffi.chargescope.Protocol.PD_PPS
        Protocol.QC2 -> uniffi.chargescope.Protocol.QC2
        Protocol.QC3 -> uniffi.chargescope.Protocol.QC3
        Protocol.QC4 -> uniffi.chargescope.Protocol.QC4
        Protocol.UFCS -> uniffi.chargescope.Protocol.UFCS
        Protocol.SCP -> uniffi.chargescope.Protocol.SCP
        Protocol.FCP -> uniffi.chargescope.Protocol.FCP
        Protocol.AFC -> uniffi.chargescope.Protocol.AFC
        Protocol.MI_PRIVATE -> uniffi.chargescope.Protocol.MI_PRIVATE
        Protocol.VOOC -> uniffi.chargescope.Protocol.VOOC
        Protocol.SUPER_VOOC -> uniffi.chargescope.Protocol.SUPER_VOOC
        Protocol.OTHER -> uniffi.chargescope.Protocol.OTHER
        Protocol.NONE -> uniffi.chargescope.Protocol.NONE
    }

    private fun toFfi(r: SourceRecord): uniffi.chargescope.SourceRecord =
        uniffi.chargescope.SourceRecord(
            field = r.field,
            source = toFfi(r.source),
            sourcePath = r.sourcePath,
            rawValue = r.rawValue,
            normalizedValue = r.normalizedValue,
            unit = r.unit,
            side = toFfi(r.side),
            quality = toFfi(r.quality),
            timestampElapsedMs = r.timestampElapsedMs,
        )

    private fun toFfi(s: ChargingSnapshot): uniffi.chargescope.ChargingSnapshot =
        uniffi.chargescope.ChargingSnapshot(
            timestampElapsedMs = s.timestampElapsedMs,
            wallClockTimeMs = s.wallClockTimeMs,
            socPercent = s.socPercent,
            batteryVoltageUv = s.batteryVoltageUv,
            batteryCurrentUa = s.batteryCurrentUa,
            batteryPowerUw = s.batteryPowerUw,
            batteryTemperatureDecic = s.batteryTemperatureDecic,
            inputVoltageUv = s.inputVoltageUv,
            inputCurrentUa = s.inputCurrentUa,
            inputPowerUw = s.inputPowerUw,
            usbType = s.usbType,
            chargeType = s.chargeType,
            chargingStatus = s.chargingStatus,
            sourceRecords = s.sourceRecords.map(::toFfi),
            rootAvailable = s.rootAvailable,
        )

    private fun toFfi(p: DeviceProfile): uniffi.chargescope.DeviceProfile =
        uniffi.chargescope.DeviceProfile(
            model = p.model,
            socName = p.socName,
            androidVersion = p.androidVersion,
            rootAvailable = p.rootAvailable,
            verifiedPaths = p.verifiedPaths,
            supportedProtocols = p.supportedProtocols.map(::toFfi),
            note = p.note,
        )

    // ---------- UniFFI -> Kotlin domain ----------

    private fun toDomain(s: uniffi.chargescope.Side): Side = when (s) {
        uniffi.chargescope.Side.BATTERY_SIDE -> Side.BATTERY_SIDE
        uniffi.chargescope.Side.INPUT_SIDE -> Side.INPUT_SIDE
        uniffi.chargescope.Side.UNKNOWN_SIDE -> Side.UNKNOWN_SIDE
    }

    private fun toDomain(q: uniffi.chargescope.DataQuality): DataQuality = when (q) {
        uniffi.chargescope.DataQuality.VALID -> DataQuality.VALID
        uniffi.chargescope.DataQuality.STALE -> DataQuality.STALE
        uniffi.chargescope.DataQuality.OUT_OF_RANGE -> DataQuality.OUT_OF_RANGE
        uniffi.chargescope.DataQuality.PARSE_ERROR -> DataQuality.PARSE_ERROR
        uniffi.chargescope.DataQuality.PERMISSION_DENIED -> DataQuality.PERMISSION_DENIED
        uniffi.chargescope.DataQuality.MISSING -> DataQuality.MISSING
    }

    private fun toDomain(d: uniffi.chargescope.DataSource): DataSource = when (d) {
        uniffi.chargescope.DataSource.ANDROID_API -> DataSource.ANDROID_API
        uniffi.chargescope.DataSource.SYSFS -> DataSource.SYSFS
        uniffi.chargescope.DataSource.ROOT_SHELL -> DataSource.ROOT_SHELL
        uniffi.chargescope.DataSource.DEVICE_ADAPTER -> DataSource.DEVICE_ADAPTER
    }

    private fun toDomain(l: uniffi.chargescope.EvidenceLevel): EvidenceLevel = when (l) {
        uniffi.chargescope.EvidenceLevel.L1 -> EvidenceLevel.L1
        uniffi.chargescope.EvidenceLevel.L2 -> EvidenceLevel.L2
        uniffi.chargescope.EvidenceLevel.L3 -> EvidenceLevel.L3
        uniffi.chargescope.EvidenceLevel.L4 -> EvidenceLevel.L4
    }

    private fun toDomain(p: uniffi.chargescope.Protocol): Protocol = when (p) {
        uniffi.chargescope.Protocol.PD -> Protocol.PD
        uniffi.chargescope.Protocol.PD_PPS -> Protocol.PD_PPS
        uniffi.chargescope.Protocol.QC2 -> Protocol.QC2
        uniffi.chargescope.Protocol.QC3 -> Protocol.QC3
        uniffi.chargescope.Protocol.QC4 -> Protocol.QC4
        uniffi.chargescope.Protocol.UFCS -> Protocol.UFCS
        uniffi.chargescope.Protocol.SCP -> Protocol.SCP
        uniffi.chargescope.Protocol.FCP -> Protocol.FCP
        uniffi.chargescope.Protocol.AFC -> Protocol.AFC
        uniffi.chargescope.Protocol.MI_PRIVATE -> Protocol.MI_PRIVATE
        uniffi.chargescope.Protocol.VOOC -> Protocol.VOOC
        uniffi.chargescope.Protocol.SUPER_VOOC -> Protocol.SUPER_VOOC
        uniffi.chargescope.Protocol.OTHER -> Protocol.OTHER
        uniffi.chargescope.Protocol.NONE -> Protocol.NONE
    }

    private fun toDomain(s: uniffi.chargescope.SourceRecord): SourceRecord = SourceRecord(
        field = s.field,
        source = toDomain(s.source),
        sourcePath = s.sourcePath,
        rawValue = s.rawValue,
        normalizedValue = s.normalizedValue,
        unit = s.unit,
        side = toDomain(s.side),
        quality = toDomain(s.quality),
        timestampElapsedMs = s.timestampElapsedMs,
    )

    private fun toDomain(m: uniffi.chargescope.ChargingMetrics): ChargingMetrics = ChargingMetrics(
        sampleCount = m.sampleCount,
        durationMs = m.durationMs,
        socRatePercentPerMin = m.socRatePercentPerMin,
        avgBatteryPowerUw = m.avgBatteryPowerUw,
        peakBatteryPowerUw = m.peakBatteryPowerUw,
        avgTemperatureDecic = m.avgTemperatureDecic,
        staleRatio = m.staleRatio,
    )

    private fun toDomain(a: uniffi.chargescope.AnalysisStatus): AnalysisStatus = when (a) {
        uniffi.chargescope.AnalysisStatus.CONFIRMED -> AnalysisStatus.CONFIRMED
        uniffi.chargescope.AnalysisStatus.HIGH_CONFIDENCE -> AnalysisStatus.HIGH_CONFIDENCE
        uniffi.chargescope.AnalysisStatus.POSSIBLE -> AnalysisStatus.POSSIBLE
        uniffi.chargescope.AnalysisStatus.INDETERMINATE -> AnalysisStatus.INDETERMINATE
        uniffi.chargescope.AnalysisStatus.UNKNOWN -> AnalysisStatus.UNKNOWN
        uniffi.chargescope.AnalysisStatus.UNSUPPORTED -> AnalysisStatus.UNSUPPORTED
    }

    private fun toDomain(a: uniffi.chargescope.ProtocolAnalysis): ProtocolAnalysis = ProtocolAnalysis(
        status = toDomain(a.status),
        primaryCandidate = a.primaryCandidate?.let(::toDomain),
        candidates = a.candidates.map {
            ProtocolCandidate(
                protocol = toDomain(it.protocol),
                score = it.score,
                evidenceLevel = toDomain(it.evidenceLevel),
                reasons = it.reasons,
            )
        },
        evidence = a.evidence.map {
            ProtocolEvidence(
                protocol = it.protocol?.let(::toDomain),
                level = toDomain(it.level),
                source = it.source,
                observation = it.observation,
                supports = it.supports,
                quality = toDomain(it.quality),
            )
        },
        conflicts = a.conflicts.map {
            EvidenceConflict(
                protocolA = it.protocolA?.let(::toDomain),
                protocolB = it.protocolB?.let(::toDomain),
                description = it.description,
            )
        },
        explanation = a.explanation,
        limitations = a.limitations,
        analyzedAtElapsedMs = a.analyzedAtElapsedMs,
        algorithmVersion = a.algorithmVersion,
    )

    private fun toDomain(b: uniffi.chargescope.AnalysisBundle): AnalysisBundle = AnalysisBundle(
        metrics = toDomain(b.metrics),
        analysis = toDomain(b.analysis),
    )
}
