package com.chargescope.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** schemaVersion is stored so future migrations can be planned. */
const val SCHEMA_VERSION = 1

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val deviceModel: String?,
    val chargerNote: String? = null,
    val peakPowerUw: Long? = null,
    val averagePowerUw: Long? = null,
    val sampleCount: Int = 0,
    val algorithmVersion: String = "",
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Entity(
    tableName = "snapshots",
    indices = [Index("sessionId"), Index("timestampElapsedMs")],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampElapsedMs: Long,
    val wallClockTimeMs: Long,
    val socPercent: Int?,
    val batteryVoltageUv: Long?,
    val batteryCurrentUa: Long?,
    val batteryPowerUw: Long?,
    val batteryTemperatureDecic: Int?,
    val inputVoltageUv: Long?,
    val inputCurrentUa: Long?,
    val inputPowerUw: Long?,
    val usbType: String?,
    val chargeType: String?,
    val chargingStatus: String?,
    val rootAvailable: Boolean,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Entity(tableName = "evidence")
data class EvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val analyzedAtElapsedMs: Long,
    val status: String,
    val primaryCandidate: String?,
    val algorithmVersion: String,
    /** JSON of full ProtocolAnalysis incl. evidence/conflicts/limitations. */
    val analysisJson: String,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Entity(tableName = "device_profiles")
data class DeviceProfileEntity(
    @PrimaryKey val model: String,
    val socName: String?,
    val androidVersion: String?,
    val verifiedPathsJson: String,
    val supportedProtocolsJson: String,
    val note: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
)
