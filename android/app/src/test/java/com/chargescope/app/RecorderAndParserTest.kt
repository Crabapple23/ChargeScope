package com.chargescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

class RecorderAndParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val header =
        "wall_ms,elapsed_ms,level_pct,status,plugged,voltage_mv,temp_decic,current_ua," +
            "current_avg_ua,charge_counter_uah,battery_power_mw_est,input_voltage_uv,input_current_ua,usb_type"

    private fun writeGz(name: String, lines: List<String>): File {
        val f = tmp.newFile(name)
        GZIPOutputStream(FileOutputStream(f), 4096, true).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                for (l in lines) {
                    w.write(l)
                    w.write("\n")
                }
            }
        }
        return f
    }

    @Test fun mergeCsvFilesDeduplicatesHeadersAndMergesLegacyAndSessions() {
        val legacy = tmp.newFile("records.csv").apply {
            writeText("$header\n1000,10,50,2,1,4200,320,-2000000,,3000000,8400.0,,,\n")
        }
        val s1 = writeGz("rec_1.csv.gz", listOf(header, "2000,20,51,2,1,4210,321,-2100000,,3010000,8841.0,,,"))
        val s2 = writeGz("rec_2.csv.gz", listOf(header, "3000,30,52,2,1,4220,322,-2200000,,3020000,9284.0,,,"))

        val out = ByteArrayOutputStream()
        assertTrue(Recorder.mergeCsvFiles(listOf(legacy, s1, s2), out))
        val resultLines = out.toString(Charsets.UTF_8.name()).trim().lines()
        assertEquals(4, resultLines.size)
        assertEquals(header, resultLines[0])
        assertTrue(resultLines[1].startsWith("1000,"))
        assertTrue(resultLines[2].startsWith("2000,"))
        assertTrue(resultLines[3].startsWith("3000,"))
        assertEquals(3L, Recorder.countCsvRows(listOf(legacy, s1, s2)))
    }

    @Test fun mergeCsvFilesSkipsCorruptedSessionWithoutLosingSubsequentData() {
        val corrupted = tmp.newFile("rec_bad.csv.gz").apply {
            writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x01, 0x02, 0x03))
        }
        val healthy = writeGz("rec_ok.csv.gz", listOf(header, "4000,40,80,2,1,4350,340,-1500000,,4000000,6525.0,,,"))

        val out = ByteArrayOutputStream()
        assertTrue(Recorder.mergeCsvFiles(listOf(corrupted, healthy), out))
        val lines = out.toString(Charsets.UTF_8.name()).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("4000,"))
        assertEquals(1L, Recorder.countCsvRows(listOf(corrupted, healthy)))
    }

    @Test fun mergeCsvFilesReturnsFalseWhenEmpty() {
        val out = ByteArrayOutputStream()
        assertFalse(Recorder.mergeCsvFiles(emptyList(), out))
        assertEquals(0, out.size())
    }

    @Test fun rootScannerInterpretValidatesUidAndParsesHealthAndDumpsys() {
        val raw = """
            #uid=2000
            @@dumpsys
              AC powered: true
              Max charging voltage: 9200000
              Max charging current: 6100000
            @@health
            soh=91
            cycle_count=128
            charge_full=4550000
        """.trimIndent()

        val ok = RootScanner.interpret(raw, setOf("2000", "0"))
        assertNull(ok.error)
        assertEquals(2, ok.nodes.size)
        assertEquals("9200000", ok.nodes[0].props["max_charging_voltage"])
        assertEquals("91", ok.nodes[1].props["soh"])
        assertEquals("128", ok.nodes[1].props["cycle_count"])

        val rejected = RootScanner.interpret(raw, setOf("0"))
        assertNotNull(rejected.error)
        assertTrue(rejected.nodes.isEmpty())
    }

    @Test fun chargeTimeRemainingPrefersSystemApiAndFallsBackToSocRate() {
        val charging = Snapshot(
            elapsedMs = 0, wallMs = 0, levelPct = 50, status = 2, plugged = 1, health = 2,
            voltageMv = 4200, tempDeci = 320, currentUa = -2_000_000L, currentAvgUa = null,
            chargeCounterUah = 2_500_000L, chargeTimeRemainingMs = 1_800_000L,
        )
        val sysEta = Analyzer.estimateChargeTimeRemainingMs(charging, Metrics(10, 60_000, 1.0, 8000.0, 8500.0, 320.0))
        assertEquals(1_800_000L to true, sysEta)

        val fallbackEta = Analyzer.estimateChargeTimeRemainingMs(
            charging.copy(chargeTimeRemainingMs = null),
            Metrics(10, 60_000, 1.0, 8000.0, 8500.0, 320.0),
        )
        assertEquals(3_000_000L to false, fallbackEta)

        assertNull(Analyzer.estimateChargeTimeRemainingMs(charging.copy(status = 3), null))
    }

    @Test fun batteryCycleCountReadsHealthAndVendorNodes() {
        val s1 = Snapshot(
            elapsedMs = 0, wallMs = 0, levelPct = 60, status = 2, plugged = 1, health = 2,
            voltageMv = 4200, tempDeci = 300, currentUa = -1_000_000L, currentAvgUa = null,
            chargeCounterUah = null, rootNodes = listOf(RootNode("health", mapOf("cycle_count" to "88"))),
        )
        assertEquals(88, Analyzer.batteryCycleCount(s1))

        val s2 = s1.copy(rootNodes = listOf(RootNode("qcom-battery", mapOf("cycle_count" to "205"))))
        assertEquals(205, Analyzer.batteryCycleCount(s2))
    }
}
