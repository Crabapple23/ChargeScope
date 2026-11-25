package com.chargescope.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endTimeMs = :endTimeMs, peakPowerUw = :peak, averagePowerUw = :avg, sampleCount = :count WHERE id = :sessionId")
    suspend fun close(sessionId: Long, endTimeMs: Long, peak: Long?, avg: Long?, count: Int)

    @Query("SELECT * FROM sessions ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startTimeMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionEntity>
}

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<SnapshotEntity>)

    @Query("SELECT * FROM snapshots WHERE sessionId = :sessionId ORDER BY timestampElapsedMs ASC")
    fun observeForSession(sessionId: Long): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE sessionId = :sessionId ORDER BY timestampElapsedMs ASC")
    suspend fun forSession(sessionId: Long): List<SnapshotEntity>

    @Query("SELECT COUNT(*) FROM snapshots WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int
}

@Dao
interface EvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(evidence: EvidenceEntity): Long

    @Query("SELECT * FROM evidence WHERE sessionId = :sessionId ORDER BY analyzedAtElapsedMs DESC LIMIT 1")
    suspend fun latestForSession(sessionId: Long): EvidenceEntity?
}

@Dao
interface DeviceProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: DeviceProfileEntity)

    @Query("SELECT * FROM device_profiles WHERE model = :model")
    suspend fun find(model: String): DeviceProfileEntity?
}
