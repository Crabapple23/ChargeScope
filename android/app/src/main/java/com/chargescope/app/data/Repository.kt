package com.chargescope.app.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.chargescope.app.data.db.AppDatabase
import com.chargescope.app.data.db.DeviceProfileEntity
import com.chargescope.app.data.db.EvidenceEntity
import com.chargescope.app.data.db.SnapshotEntity
import com.chargescope.app.data.db.SCHEMA_VERSION
import com.chargescope.app.data.db.SessionEntity
import com.chargescope.app.domain.RustBridge
import com.chargescope.app.domain.model.AnalysisBundle
import com.chargescope.app.domain.model.ChargingSession
import com.chargescope.app.domain.model.ChargingSnapshot
import com.chargescope.app.domain.model.DeviceProfile
import com.chargescope.app.domain.model.Protocol
import com.chargescope.app.domain.model.ProtocolAnalysis
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-first repository. No network, no account. Serialises full analysis to
 * JSON so evidence/conflicts/limitations survive process death.
 */
class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    private val gson = Gson()

    val sessions: Flow<List<ChargingSession>> = db.sessionDao().observeAll().map { list ->
        list.map {
            ChargingSession(
                id = it.id,
                startTimeMs = it.startTimeMs,
                endTimeMs = it.endTimeMs,
                deviceModel = it.deviceModel,
                chargerNote = it.chargerNote,
                peakPowerUw = it.peakPowerUw,
                averagePowerUw = it.averagePowerUw,
                sampleCount = it.sampleCount,
                algorithmVersion = it.algorithmVersion,
            )
        }
    }

    suspend fun startSession(): Long = db.sessionDao().insert(
        SessionEntity(
            startTimeMs = System.currentTimeMillis(),
            deviceModel = Build.MODEL,
            algorithmVersion = RustBridge.algorithmVersion(),
        )
    )

    suspend fun closeSession(sessionId: Long, peak: Long?, avg: Long?, count: Int) {
        db.sessionDao().close(sessionId, System.currentTimeMillis(), peak, avg, count)
    }

    suspend fun saveSnapshots(sessionId: Long, snapshots: List<ChargingSnapshot>) {
        if (snapshots.isEmpty()) return
        db.snapshotDao().insertAll(snapshots.map { it.toEntity(sessionId) })
    }

    suspend fun saveAnalysis(sessionId: Long, bundle: AnalysisBundle) {
        db.evidenceDao().insert(
            EvidenceEntity(
                sessionId = sessionId,
                analyzedAtElapsedMs = bundle.analysis.analyzedAtElapsedMs,
                status = bundle.analysis.status.name,
                primaryCandidate = bundle.analysis.primaryCandidate?.name,
                algorithmVersion = bundle.analysis.algorithmVersion,
                analysisJson = gson.toJson(bundle.analysis),
            )
        )
    }

    suspend fun snapshotsFor(sessionId: Long): List<ChargingSnapshot> =
        db.snapshotDao().forSession(sessionId).map { it.toDomain() }

    fun observeSnapshots(sessionId: Long): Flow<List<ChargingSnapshot>> =
        db.snapshotDao().observeForSession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun latestAnalysis(sessionId: Long): ProtocolAnalysis? =
        db.evidenceDao().latestForSession(sessionId)?.let {
            runCatching { gson.fromJson(it.analysisJson, ProtocolAnalysis::class.java) }.getOrNull()
        }

    suspend fun deviceProfile(model: String): DeviceProfile? =
        db.deviceProfileDao().find(model)?.let {
            DeviceProfile(
                model = it.model,
                socName = it.socName,
                androidVersion = it.androidVersion,
                rootAvailable = false,
                verifiedPaths = runCatching {
                    gson.fromJson(it.verifiedPathsJson, Array<String>::class.java).toList()
                }.getOrDefault(emptyList()),
                supportedProtocols = runCatching {
                    gson.fromJson(it.supportedProtocolsJson, Array<Protocol>::class.java).toList()
                }.getOrDefault(emptyList()),
                note = it.note,
            )
        }

    suspend fun saveDeviceProfile(profile: DeviceProfile) {
        val model = profile.model ?: return
        db.deviceProfileDao().insert(
            DeviceProfileEntity(
                model = model,
                socName = profile.socName,
                androidVersion = profile.androidVersion,
                verifiedPathsJson = gson.toJson(profile.verifiedPaths),
                supportedProtocolsJson = gson.toJson(profile.supportedProtocols),
                note = profile.note,
            )
        )
    }

    private fun ChargingSnapshot.toEntity(sessionId: Long) = SnapshotEntity(
        sessionId = sessionId,
        timestampElapsedMs = timestampElapsedMs,
        wallClockTimeMs = wallClockTimeMs,
        socPercent = socPercent,
        batteryVoltageUv = batteryVoltageUwSafe(batteryVoltageUv),
        batteryCurrentUa = batteryCurrentUa,
        batteryPowerUw = batteryPowerUw,
        batteryTemperatureDecic = batteryTemperatureDecic,
        inputVoltageUv = inputVoltageUv,
        inputCurrentUa = inputCurrentUa,
        inputPowerUw = inputPowerUw,
        usbType = usbType,
        chargeType = chargeType,
        chargingStatus = chargingStatus,
        rootAvailable = rootAvailable,
        schemaVersion = SCHEMA_VERSION,
    )

    // Defensive: never persist an out-of-range value even if an upstream
    // collector changes. Structural validation already happens in Rust.
    private fun batteryVoltageUwSafe(value: Long?): Long? = value?.takeIf { it in 2_000_000..22_000_000 }

    private fun SnapshotEntity.toDomain() = ChargingSnapshot(
        timestampElapsedMs = timestampElapsedMs,
        wallClockTimeMs = wallClockTimeMs,
        socPercent = socPercent,
        batteryVoltageUv = batteryVoltageUv,
        batteryCurrentUa = batteryCurrentUa,
        batteryPowerUw = batteryPowerUw,
        batteryTemperatureDecic = batteryTemperatureDecic,
        inputVoltageUv = inputVoltageUv,
        inputCurrentUa = inputCurrentUa,
        inputPowerUw = inputPowerUw,
        usbType = usbType,
        chargeType = chargeType,
        chargingStatus = chargingStatus,
        sourceRecords = emptyList(), // raw records are in the exported JSON bundle
        rootAvailable = rootAvailable,
    )
}
