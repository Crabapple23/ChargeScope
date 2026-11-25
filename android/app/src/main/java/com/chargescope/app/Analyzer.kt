package com.chargescope.app

import kotlin.math.abs

/**
 * Evidence-based analysis. Rules:
 * - L4: driver explicitly reports a protocol name (needs root-readable sysfs) -> may confirm.
 * - L3: input-side voltage/current -> only "possible".
 * - L2: battery-side power -> reference only, never confirms a protocol.
 */
object Analyzer {

    internal data class Cls(val label: String, val family: String, val fast: Boolean, val rank: Int)

    internal fun classify(raw: String): Cls? {
        val t = raw.uppercase().replace('-', '_').replace(' ', '_')
        if (t.isEmpty() || "DRP" in t) return null
        return when {
            "SUPERVOOC" in t -> Cls("SuperVOOC", "VOOC", true, 1)
            "VOOC" in t -> Cls("VOOC", "VOOC", true, 0)
            "WARP" in t -> Cls("Warp Charge", "WARP", true, 0)
            "UFCS" in t -> Cls("UFCS", "UFCS", true, 0)
            "PPS" in t -> Cls("USB PD PPS", "PD", true, 1)
            "HVDCP_3P5" in t || "QC3P5" in t || "QC_3P5" in t -> Cls("QC 3.5", "QC", true, 3)
            "HVDCP_3" in t || "QC3" in t || "QC_3" in t -> Cls("QC 3.0", "QC", true, 2)
            "HVDCP" in t || "QC2" in t || "QC_2" in t -> Cls("QC 2.0", "QC", true, 1)
            "MTK_PE" in t -> Cls("MediaTek Pump Express", "PE", true, 0)
            "SCP" in t -> Cls("Huawei SCP", "SCP", true, 0)
            "FCP" in t -> Cls("Huawei FCP", "FCP", true, 0)
            "PD" in t -> Cls("USB PD", "PD", true, 0)
            "DCP" in t -> Cls("DCP 标准充电口", "BASIC", false, 0)
            "CDP" in t -> Cls("CDP 充电下行口", "BASIC", false, 0)
            "SDP" in t -> Cls("SDP 标准下行口", "BASIC", false, 0)
            else -> null
        }
    }

    private val bracket = Regex("\\[([^\\]]+)\\]")

    /** Linux usb_type lists all options with the active one in [brackets]. */
    internal fun activeToken(v: String): String? {
        val m = bracket.find(v)
        if (m != null) return m.groupValues[1]
        return if (v.isBlank() || v.contains(' ')) null else v
    }

    private fun isInputNode(n: RootNode): Boolean {
        val t = n.props["type"]?.uppercase() ?: return false
        if (t == "BATTERY" || t == "BMS") return false
        return n.props["online"] != "0"
    }

    fun input(s: Snapshot): InputInfo {
        var v: Long? = null
        var i: Long? = null
        var type: String? = null
        for (n in s.rootNodes) {
            if (!isInputNode(n)) continue
            if (v == null) v = n.props["voltage_now"]?.trim()?.toLongOrNull()?.takeIf { it in 3_000_000L..25_000_000L }
            if (i == null) i = n.props["current_now"]?.trim()?.toLongOrNull()
            if (type == null) type = (n.props["usb_type"] ?: n.props["real_type"])?.let { activeToken(it) }
        }
        return InputInfo(v, i, type)
    }

    /** Battery current in mA (absolute). Some devices report mA instead of uA. */
    fun currentMa(s: Snapshot): Double? {
        val raw = s.currentUa ?: return null
        val a = abs(raw.toDouble())
        return if (s.isPlugged && a >= 1.0 && a < 20_000.0) a else a / 1000.0
    }

    /** Estimated battery-side power in mW (V x |I|). */
    fun powerMw(s: Snapshot): Double? {
        val v = s.voltageMv ?: return null
        val i = currentMa(s) ?: return null
        return v * i / 1000.0
    }

    fun analyze(window: List<Snapshot>): Analysis {
        val last = window.lastOrNull()
            ?: return Analysis(Status.UNKNOWN, "暂无数据", emptyList(), emptyList())
        val limits = mutableListOf(
            "设备支持、充电器支持、当前实际协商是三个不同概念，本工具只报告当前可观测的证据。",
            "功率、温度、SOC 变化不能单独确认任何快充协议。",
        )
        if (!last.isPlugged) {
            return Analysis(Status.NOT_CHARGING, "未连接充电器", emptyList(), limits)
        }

        val evidence = mutableListOf<Evidence>()
        val l4 = LinkedHashMap<String, Cls>()
        for (n in last.rootNodes) {
            if (!isInputNode(n)) continue
            for (key in arrayOf("usb_type", "real_type", "type")) {
                val raw = n.props[key] ?: continue
                val tok = activeToken(raw) ?: continue
                val c = classify(tok) ?: continue
                val cur = l4[c.family]
                if (cur == null || c.rank > cur.rank) l4[c.family] = c
                evidence += Evidence(4, "${n.name}/$key = $tok → ${c.label}")
            }
        }

        var maxVin = 0L
        for (s in window.takeLast(15)) {
            val vin = input(s).voltageUv ?: continue
            if (vin > maxVin) maxVin = vin
        }
        val inp = input(last)
        if (inp.voltageUv != null) {
            val p = inp.currentUa?.let { inp.voltageUv * abs(it) / 1_000_000_000.0 }
            evidence += Evidence(
                3,
                "输入侧电压 %.2f V".format(inp.voltageUv / 1e6) +
                    (inp.currentUa?.let { "，电流 %.0f mA".format(abs(it) / 1000.0) } ?: "") +
                    (p?.let { "，约 %.1f W".format(it / 1000.0) } ?: ""),
            )
        }
        powerMw(last)?.let {
            if (it >= 15_000.0) {
                evidence += Evidence(2, "电池侧估算功率约 %.1f W（仅参考，不能确认协议）".format(it / 1000.0))
            }
        }

        if (last.rootNodes.isEmpty()) {
            limits += "未启用 Root 或无法读取 sysfs：无 Root 时只有电池侧数据，无法识别协议。"
        }

        return when {
            l4.size > 1 -> Analysis(
                Status.CONFLICT,
                "不同节点报告了不同协议：" + l4.values.joinToString(" / ") { it.label },
                evidence, limits,
            )
            l4.size == 1 -> {
                val c = l4.values.first()
                Analysis(
                    Status.CONFIRMED,
                    if (c.fast) "驱动报告：${c.label}" else "驱动报告：${c.label}（非快充）",
                    evidence, limits,
                )
            }
            maxVin > 5_500_000L -> Analysis(
                Status.POSSIBLE,
                "输入侧出现 %.1f V 高压，疑似发生快充协商，但无法确定具体协议".format(maxVin / 1e6),
                evidence, limits,
            )
            else -> Analysis(Status.UNKNOWN, "没有足够证据识别协议", evidence, limits)
        }
    }

    fun metrics(list: List<Snapshot>): Metrics {
        if (list.isEmpty()) return Metrics(0, 0, null, null, null, null)
        var pSum = 0.0; var pN = 0; var peak: Double? = null
        var tSum = 0.0; var tN = 0
        for (s in list) {
            powerMw(s)?.let { pSum += it; pN++; if (peak == null || it > peak!!) peak = it }
            s.tempDeci?.let { tSum += it; tN++ }
        }
        val first = list.firstOrNull { it.levelPct != null }
        val lastLvl = list.lastOrNull { it.levelPct != null }
        var rate: Double? = null
        if (first != null && lastLvl != null) {
            val minutes = (lastLvl.elapsedMs - first.elapsedMs) / 60_000.0
            if (minutes >= 1.0) rate = (lastLvl.levelPct!! - first.levelPct!!) / minutes
        }
        return Metrics(
            count = list.size,
            durationMs = list.last().elapsedMs - list.first().elapsedMs,
            socRatePerMin = rate,
            avgPowerMw = if (pN > 0) pSum / pN else null,
            peakPowerMw = peak,
            avgTempDeci = if (tN > 0) tSum / tN else null,
        )
    }
}
