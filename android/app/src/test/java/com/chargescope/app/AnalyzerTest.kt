package com.chargescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private fun snap(nodes: List<RootNode> = emptyList(), plugged: Int = 2, ua: Long? = -1_500_000L, mv: Int = 4000) = Snapshot(
        elapsedMs = 0, wallMs = 0, levelPct = 50, status = 2, plugged = plugged, health = 2,
        voltageMv = mv, tempDeci = 300, currentUa = ua, currentAvgUa = null, chargeCounterUah = null,
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
        assertTrue(a.protocolSpec?.contains("20V/5A") == true)
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

    @Test fun dcpPlusPdIsLayeredNotConflict() {
        // Real Xiaomi/MT6991 log: BC1.2 type is DCP while real_type reports USB_PD.
        val usb = RootNode("usb", mapOf("type" to "USB_DCP", "online" to "1", "present" to "1", "voltage_now" to "0", "real_type" to "USB_PD"))
        val chg = RootNode("primary_chg", mapOf("type" to "USB_DCP", "online" to "5", "usb_type" to "Unknown SDP CDP [DCP]"))
        val a = Analyzer.analyze(listOf(snap(listOf(usb, chg))))
        assertEquals(Status.CONFIRMED, a.status)
        assertEquals("USB PD", a.driver)
    }

    @Test fun gaugeAndChargerNodesAreNotInput() {
        val gauge = RootNode("mt6379-gauge1", mapOf("type" to "Unknown", "online" to "1", "present" to "1", "voltage_now" to "4000000", "current_now" to "1800"))
        val cp = RootNode("cp_master", mapOf("type" to "Mains", "present" to "0", "voltage_now" to "9000000"))
        val inp = Analyzer.input(snap(listOf(gauge, cp)))
        assertNull(inp.voltageUv)
        assertNull(inp.currentUa)
    }

    @Test fun infersFastChargingFromLogWithoutInputReadings() {
        // Real Xiaomi/MT6991 log: usb node has no readable voltage, but dumpsys advertises 22V/3.225A
        // and the battery takes ~30W, which is impossible at 5V within 3.2A.
        val usb = RootNode("usb", mapOf("type" to "USB_DCP", "online" to "1", "present" to "1", "voltage_now" to "0", "real_type" to "USB_PD"))
        val chg = RootNode("primary_chg", mapOf("type" to "USB_DCP", "online" to "5", "usb_type" to "Unknown SDP CDP [DCP]"))
        val dump = RootNode("dumpsys", mapOf("max_charging_voltage" to "22000000", "max_charging_current" to "3225000"))
        val a = Analyzer.analyze(listOf(snap(listOf(usb, chg, dump), ua = -7_101_000L, mv = 4312)), "Xiaomi")
        assertEquals(Status.CONFIRMED, a.status)
        assertEquals("USB PD", a.driver)
        assertTrue(a.predicted?.startsWith("USB PD PPS") == true)
        assertTrue(a.evidence.any { "反推" in it.text })
        assertTrue(a.candidates.any { "HyperCharge" in it })
        assertTrue(a.candidateSpecs.any { "120W" in it })
    }

    @Test fun powerBoundProvesHighVoltageEvenWithoutDriverName() {
        val dump = RootNode("dumpsys", mapOf("max_charging_voltage" to "9000000", "max_charging_current" to "3000000"))
        val a = Analyzer.analyze(listOf(snap(listOf(dump), ua = -7_000_000L, mv = 4300)))
        assertEquals(Status.POSSIBLE, a.status)
        assertTrue(a.evidence.any { "反推" in it.text })
    }

    @Test fun k70ProXiaomi120WConfirmedAndNotSamsungAfc() {
        val ucsi = RootNode("ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1", mapOf("type" to "USB", "online" to "1", "voltage_now" to "9000000", "current_now" to "1600000", "usb_type" to "C [PD] PD_PPS"))
        val usb = RootNode("usb", mapOf("type" to "USB_PD", "online" to "1", "voltage_now" to "8883000", "current_now" to "672000", "usb_type" to "PD [PD_PPS]"))
        val qcom = RootNode("qcom-battery", mapOf("quick_charge_type" to "4", "pd_verifed" to "1", "apdo_max" to "120", "soh" to "84"))
        val bat = RootNode("battery", mapOf("type" to "Battery", "charge_full" to "3266000", "charge_full_design" to "5000000"))
        val s = snap(listOf(ucsi, usb, qcom, bat), ua = -579_000L, mv = 4190)
        val a = Analyzer.analyze(listOf(s), "Xiaomi")
        assertEquals(Status.CONFIRMED, a.status)
        assertEquals("小米 HyperCharge / Mi PPS (120W)", a.driver)
        assertEquals("小米 HyperCharge / Mi PPS (120W)", a.predicted)
        assertTrue(a.protocolSpec?.contains("120W") == true)
        assertEquals(84, Analyzer.batteryHealthPct(s))
        assertEquals(8_883_000L, Analyzer.input(s).voltageUv)
    }

    @Test fun samsungSfcAndOppoVoocConfirmed() {
        val sec = RootNode("battery", mapOf("type" to "Battery", "hv_charger_status" to "3"))
        val a1 = Analyzer.analyze(listOf(snap(listOf(sec))), "samsung")
        assertEquals(Status.CONFIRMED, a1.status)
        assertEquals("三星 SFC 2.0 (45W PPS)", a1.driver)

        val oplus = RootNode("oplus_chg", mapOf("voocchg_ing" to "1"))
        val a2 = Analyzer.analyze(listOf(snap(listOf(oplus))), "OPPO")
        assertEquals(Status.CONFIRMED, a2.status)
        assertEquals("OPPO VOOC / SuperVOOC", a2.driver)
    }

    @Test fun trickleChargeSkipsLiveSpecTableMatch() {
        val usb = RootNode("usb", mapOf("type" to "USB", "online" to "1", "voltage_now" to "9000000", "current_now" to "700000"))
        val fullSnap = snap(listOf(usb)).copy(levelPct = 100)
        val a = Analyzer.analyze(listOf(fullSnap), "Xiaomi")
        assertTrue(a.limitations.any { "降流" in it })
        assertTrue(a.evidence.none { "规格表" in it.text })
        assertTrue(a.candidates.none { "三星 AFC" in it || "QC 2.0/3.0" in it })
    }

    @Test fun xiaomiTurbo3Shizuku9v3_6a1IsVendorHighCurrentNotFixedStep() {
        val dump = RootNode("dumpsys", mapOf("max_charging_voltage" to "9300000", "max_charging_current" to "6100000"))
        val a = Analyzer.analyze(listOf(snap(listOf(dump), ua = -2_928_000L, mv = 4385)), "Xiaomi")
        assertEquals(Status.POSSIBLE, a.status)
        assertTrue(a.candidates.any { "HyperCharge" in it || "魔改 PPS" in it })
        assertTrue(a.candidates.none { "QC 2.0" in it || "AFC" in it })
        assertTrue(a.evidence.none { "反推" in it.text })
        assertTrue(a.limitations.any { "SELinux" in it })
    }

    @Test fun honorShizukuDumpsysOnlyExplainsSelinuxAndSkipsSub5vBound() {
        val dump = RootNode("dumpsys", mapOf("max_charging_voltage" to "5000000", "max_charging_current" to "3000000"))
        val a = Analyzer.analyze(listOf(snap(listOf(dump), ua = 1645L, mv = 4039)), "HONOR")
        assertEquals(Status.UNKNOWN, a.status)
        assertTrue(a.evidence.none { "反推" in it.text })
        assertTrue(a.description.contains("SELinux"))
        assertTrue(a.limitations.any { "SELinux" in it })
    }

    @Test fun shizukuLearnedCapacityPlusPowerProfileGivesHealth() {
        val health = RootNode("health", mapOf("charge_full" to "4307000"))
        val s = snap(listOf(health)).copy(designCapacityMah = 5000)
        assertEquals(5000, Analyzer.designCapacityMah(s))
        assertEquals(4307, Analyzer.usableCapacityMah(s))
        assertEquals(86, Analyzer.batteryHealthPct(s))
    }

    @Test fun dualCellModeDoublesSingleCellPowerAndNormalizesCapacity() {
        val health = RootNode("health", mapOf("charge_full" to "2350000"))
        val s = snap(listOf(health), ua = -5_000_000L, mv = 4200).copy(designCapacityMah = 5000)
        // Single-cell: raw 4.2V * 5A = 21W, raw usable 2350 / 5000 = 47%
        assertEquals(21_000.0, Analyzer.powerMw(s, dualCell = false)!!, 0.01)
        assertEquals(2350, Analyzer.usableCapacityMah(s, dualCell = false))
        // Dual-cell: 8.4V * 5A = 42W, equivalent usable 4700 / 5000 = 94%
        assertEquals(42_000.0, Analyzer.powerMw(s, dualCell = true)!!, 0.01)
        assertEquals(4700, Analyzer.usableCapacityMah(s, dualCell = true))
        assertEquals(5000, Analyzer.designCapacityMah(s, dualCell = true))
        assertEquals(94, Analyzer.batteryHealthPct(s, dualCell = true))
        // When voltage is already reported as series (>5.5V), do not double again
        val s8v = s.copy(voltageMv = 8400)
        assertEquals(42_000.0, Analyzer.powerMw(s8v, dualCell = true)!!, 0.01)
    }

    @Test fun dualCellDetectorTriggersOnVoltageSlaveNodeOrHalfCapacity() {
        assertTrue(Analyzer.detectDualCellReason(snap(mv = 8400)) != null)
        assertTrue(Analyzer.detectDualCellReason(snap(listOf(RootNode("assist_battery", mapOf("status" to "Charging"))))) != null)
        val halfCap = snap(listOf(RootNode("health", mapOf("charge_full" to "2350000")))).copy(designCapacityMah = 5000)
        assertTrue(Analyzer.detectDualCellReason(halfCap) != null)
        val normal = snap(listOf(RootNode("health", mapOf("charge_full" to "4307000")))).copy(designCapacityMah = 5000)
        assertNull(Analyzer.detectDualCellReason(normal))
    }

    @Test fun parseRootOutput() {
        val nodes = RootScanner.parse("@@usb\ntype=USB\nonline=1\n@@battery\ncapacity=50\n")
        assertEquals(2, nodes.size)
        assertTrue(nodes[0].props["online"] == "1")
    }
}
