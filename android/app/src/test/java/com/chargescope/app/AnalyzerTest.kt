package com.chargescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private fun snap(nodes: List<RootNode> = emptyList(), plugged: Int = 2, ua: Long? = -1_500_000L) = Snapshot(
        elapsedMs = 0, wallMs = 0, levelPct = 50, status = 2, plugged = plugged, health = 2,
        voltageMv = 4000, tempDeci = 300, currentUa = ua, currentAvgUa = null, chargeCounterUah = null,
        rootNodes = nodes,
    )

    @Test fun classifyBasics() {
        assertEquals("USB PD PPS", Analyzer.classify("PD_PPS")?.label)
        assertEquals("QC 3.0", Analyzer.classify("USB_HVDCP_3")?.label)
        assertEquals("DCP 标准充电口", Analyzer.classify("DCP")?.label)
        assertNull(Analyzer.classify("PD_DRP"))
        assertNull(Analyzer.classify("UNKNOWN"))
    }

    @Test fun activeTokenFromBrackets() {
        assertEquals("PD", Analyzer.activeToken("SDP DCP CDP [PD] PD_PPS"))
        assertNull(Analyzer.activeToken("SDP DCP CDP"))
    }

    @Test fun powerFromMicroAmps() {
        assertEquals(6000.0, Analyzer.powerMw(snap())!!, 0.01)
    }

    @Test fun powerOnlyIsNeverConfirmed() {
        val a = Analyzer.analyze(listOf(snap(ua = -5_000_000L)))
        assertEquals(Status.UNKNOWN, a.status)
    }

    @Test fun driverReportedProtocolConfirms() {
        val node = RootNode("usb", mapOf("type" to "USB_PD", "usb_type" to "SDP DCP [PD] PD_PPS", "online" to "1"))
        val a = Analyzer.analyze(listOf(snap(listOf(node))))
        assertEquals(Status.CONFIRMED, a.status)
    }

    @Test fun highVoltageOnlyIsPossible() {
        val node = RootNode("usb", mapOf("type" to "USB", "voltage_now" to "9000000", "online" to "1"))
        val a = Analyzer.analyze(listOf(snap(listOf(node))))
        assertEquals(Status.POSSIBLE, a.status)
    }

    @Test fun notPluggedIsNotCharging() {
        assertEquals(Status.NOT_CHARGING, Analyzer.analyze(listOf(snap(plugged = 0))).status)
    }

    @Test fun specTableMatchGivesCandidates() {
        val node = RootNode(
            "usb",
            mapOf("type" to "USB", "voltage_now" to "20000000", "current_now" to "5000000", "online" to "1"),
        )
        val a = Analyzer.analyze(listOf(snap(listOf(node))), "samsung")
        assertEquals(Status.POSSIBLE, a.status)
        assertTrue(a.candidates.any { "20V/5A" in it })
    }

    @Test fun dumpsysSectionIsParsed() {
        val n = RootScanner.parse("@@dumpsys\n  AC powered: false\n  Max charging voltage: 9000000\n")
        assertEquals("9000000", n[0].props["max_charging_voltage"])
    }

    @Test fun parseRootOutput() {
        val nodes = RootScanner.parse("@@usb\ntype=USB\nonline=1\n@@battery\ncapacity=50\n")
        assertEquals(2, nodes.size)
        assertTrue(nodes[0].props["online"] == "1")
    }
}
