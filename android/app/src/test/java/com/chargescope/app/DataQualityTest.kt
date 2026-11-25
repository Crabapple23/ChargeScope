package com.chargescope.app

import com.chargescope.app.domain.model.AnalysisStatus
import com.chargescope.app.domain.model.ChargingSnapshot
import com.chargescope.app.domain.model.DataQuality
import com.chargescope.app.domain.model.DataSource
import com.chargescope.app.domain.model.EvidenceLevel
import com.chargescope.app.domain.model.Side
import com.chargescope.app.domain.model.SourceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for domain invariants that do not need Android runtime. */
class DataQualityTest {

    @Test
    fun `missing values are null not zero`() {
        val snapshot = ChargingSnapshot()
        assertEquals(null, snapshot.socPercent)
        assertEquals(null, snapshot.batteryPowerUw)
        assertEquals(null, snapshot.inputPowerUw)
        assertFalse(snapshot.hasAnyMeasurement)
    }

    @Test
    fun `partial battery data is a measurement`() {
        val snapshot = ChargingSnapshot(socPercent = 42)
        assertTrue(snapshot.hasAnyMeasurement)
    }

    @Test
    fun `source record preserves provenance`() {
        val record = SourceRecord(
            field = "voltage_now",
            source = DataSource.SYSFS,
            sourcePath = "/sys/class/power_supply/battery/voltage_now",
            rawValue = "4000000",
            normalizedValue = "4.0",
            unit = "V",
            side = Side.BATTERY_SIDE,
            quality = DataQuality.VALID,
            timestampElapsedMs = 1_000,
        )
        assertEquals(DataSource.SYSFS, record.source)
        assertEquals(Side.BATTERY_SIDE, record.side)
        assertEquals("4000000", record.rawValue)
    }

    @Test
    fun `status labels never claim probability`() {
        assertFalse(AnalysisStatus.POSSIBLE.label.contains("%"))
        assertFalse(AnalysisStatus.CONFIRMED.label.contains("%"))
    }

    @Test
    fun `evidence level ordering puts L4 strongest`() {
        assertTrue(EvidenceLevel.L4.rank > EvidenceLevel.L3.rank)
        assertTrue(EvidenceLevel.L1.rank < EvidenceLevel.L2.rank)
    }
}
