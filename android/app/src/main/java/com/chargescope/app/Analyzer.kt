package com.chargescope.app

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Evidence-based analysis. Rules:
 * - L4: driver explicitly reports a protocol name (needs privileged sysfs access) -> may confirm.
 * - L3: input-side voltage/current, voltage pattern, spec-table match -> only "possible" + candidates.
 * - L2: battery-side power -> reference only, never confirms a protocol.
 * - L1: device brand -> only narrows candidates.
 * Spec numbers come from docs (USB PD / UFCS / QC / vendor tables).
 */
object Analyzer {

    internal data class Cls(val label: String, val family: String, val fast: Boolean, val rank: Int)

    private val PROTOCOL_KEYS = arrayOf("usb_type", "real_type", "type")
    private val FIXED_VOLTS = doubleArrayOf(9.0, 12.0, 15.0, 20.0, 28.0, 36.0, 48.0)

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

    /**
     * Only charger-facing supplies count as input nodes. Nodes of type Unknown/Battery/BMS
     * (fuel gauges, charge pumps, chargers) report battery-side values and must be ignored.
     */
    private fun isInputNode(n: RootNode): Boolean {
        val t = n.props["type"]?.uppercase() ?: return false
        val inputType = t.startsWith("USB") || t == "MAINS" || t == "WIRELESS"
        if (!inputType) return false
        return n.props["online"] != "0" && n.props["present"] != "0"
    }

    fun input(s: Snapshot): InputInfo {
        var v: Long? = null
        var i: Long? = null
        var type: String? = null
        var advV: Long? = null
        var advI: Long? = null
        for (n in s.rootNodes) {
            if (n.name == "dumpsys") {
                advV = n.props["max_charging_voltage"]?.trim()?.toLongOrNull()?.takeIf { it in 3_000_000L..50_000_000L }
                advI = n.props["max_charging_current"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                continue
            }
            if (!isInputNode(n)) continue
            if (v == null) v = n.props["voltage_now"]?.trim()?.toLongOrNull()?.takeIf { it in 3_000_000L..50_000_000L }
            if (i == null) i = n.props["current_now"]?.trim()?.toLongOrNull()
            if (type == null) type = (n.props["usb_type"] ?: n.props["real_type"])?.let { activeToken(it) }
        }
        return InputInfo(v, i, type, advV, advI)
    }

    /** Input-side power in watts, when both voltage and current are readable. */
    fun inputPowerW(inp: InputInfo): Double? {
        val v = inp.voltageUv ?: return null
        val i = inp.currentUa ?: return null
        return v.toDouble() * abs(i.toDouble()) / 1e12
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

    // ---- Spec table (from the protocol reference document) -------------------------------

    private data class Profile(val brand: String, val name: String, val v: Double, val a: Double)

    private val PROFILES = arrayOf(
        Profile("PD", "USB PD 9V/3A (27W)", 9.0, 3.0),
        Profile("PD", "USB PD 15V/3A (45W)", 15.0, 3.0),
        Profile("PD", "USB PD 20V/3A (60W)", 20.0, 3.0),
        Profile("PD", "USB PD 20V/5A (100W)", 20.0, 5.0),
        Profile("PD", "USB PD EPR 28V/5A (140W)", 28.0, 5.0),
        Profile("PD", "USB PD EPR 36V/5A (180W)", 36.0, 5.0),
        Profile("PD", "USB PD EPR 48V/5A (240W)", 48.0, 5.0),
        Profile("UFCS", "UFCS 33W (11V/3A)", 11.0, 3.0),
        Profile("UFCS", "UFCS 44W (11V/4A)", 11.0, 4.0),
        Profile("UFCS", "UFCS 65W (10V/6.5A)", 10.0, 6.5),
        Profile("UFCS", "UFCS 65W (20V/3.25A)", 20.0, 3.25),
        Profile("QC", "QC 2.0/3.0 18W (9V/2A)", 9.0, 2.0),
        Profile("QC", "QC 2.0/3.0 36W (12V/3A)", 12.0, 3.0),
        Profile("小米", "小米 HyperCharge 33W (11V/3A)", 11.0, 3.0),
        Profile("小米", "小米 HyperCharge 67W (11V/6.1A)", 11.0, 6.1),
        Profile("小米", "小米 HyperCharge 67W (20V/3.25A)", 20.0, 3.25),
        Profile("小米", "小米 HyperCharge 90W (20V/4.5A)", 20.0, 4.5),
        Profile("小米", "小米 HyperCharge 120W (20V/6A)", 20.0, 6.0),
        Profile("OPPO", "OPPO VOOC 30W (5V/6A)", 5.0, 6.0),
        Profile("OPPO", "OPPO SuperVOOC 65W (10V/6.5A)", 10.0, 6.5),
        Profile("OPPO", "OPPO SuperVOOC 80W (11V/7.3A)", 11.0, 7.3),
        Profile("OPPO", "OPPO SuperVOOC 100W (11V/9.1A)", 11.0, 9.1),
        Profile("OPPO", "OPPO SuperVOOC 150W (20V/7.5A)", 20.0, 7.5),
        Profile("OPPO", "OPPO SuperVOOC 240W (20V/12A)", 20.0, 12.0),
        Profile("vivo", "vivo FlashCharge 44W (11V/4A)", 11.0, 4.0),
        Profile("vivo", "vivo FlashCharge 66W (11V/6A)", 11.0, 6.0),
        Profile("vivo", "vivo FlashCharge 80W (20V/4A)", 20.0, 4.0),
        Profile("vivo", "vivo FlashCharge 120W (20V/6A)", 20.0, 6.0),
        Profile("vivo", "vivo FlashCharge 200W (20V/10A)", 20.0, 10.0),
        Profile("华为", "华为 FCP 18W (9V/2A)", 9.0, 2.0),
        Profile("华为", "华为 SCP 22.5W (4.5V/5A)", 4.5, 5.0),
        Profile("华为", "华为 SCP 22.5W (10V/2.25A)", 10.0, 2.25),
        Profile("华为", "华为 SCP 40W (10V/4A)", 10.0, 4.0),
        Profile("华为", "华为 SCP 66W (11V/6A)", 11.0, 6.0),
        Profile("华为", "华为 SCP 88W (20V/4.4A)", 20.0, 4.4),
        Profile("华为", "华为 SCP 100W (20V/5A)", 20.0, 5.0),
        Profile("荣耀", "荣耀 SuperCharge 66W (11V/6A)", 11.0, 6.0),
        Profile("荣耀", "荣耀 SuperCharge 80W (20V/4A)", 20.0, 4.0),
        Profile("荣耀", "荣耀 SuperCharge 100W (20V/5A)", 20.0, 5.0),
        Profile("三星", "三星 AFC 15W (9V/1.67A)", 9.0, 1.67),
        Profile("三星", "三星 PPS 25W (9V/2.77A)", 9.0, 2.77),
        Profile("三星", "三星 PPS 45W (11V/4.05A)", 11.0, 4.05),
        Profile("三星", "三星 PPS 45W (9V/5A)", 9.0, 5.0),
        Profile("魅族/努比亚", "魅族 Super mCharge 80W (20V/4A)", 20.0, 4.0),
        Profile("魅族/努比亚", "红魔 NeoCharger 165W (20V/8.25A)", 20.0, 8.25),
    )

    private val PROTOCOL_SPECS = mapOf(
        "SuperVOOC" to "典型档位：10V/6.5A (65W)、11V/7.3A (80W)、11V/9.1A (100W)、20V/7.5A (150W)、20V/12A (240W)；串联双电芯 + 电荷泵分压；私有加密握手，需原厂/认证大电流线缆。",
        "VOOC" to "典型档位：5V/4A (20W)、5V/6A (30W) 低压大电流直充；需支持大电流的专用线缆。",
        "Warp Charge" to "VOOC 体系演进，典型档位：5V/6A (30W)、10V/6.5A (65W)；需原厂/认证大电流线缆。",
        "UFCS" to "融合快充：电压 3.4V–21V（高阶可扩展至 36V），调压步进 10–20mV、电流步进 50mA；通用档 33W (11V/3A) / 44W (11V/4A)，高阶互认档 65W / 100W；>3A 需高载流线缆。",
        "USB PD PPS" to "USB PD 3.0 可编程电源：3.3V–21V（标准上限 5A / 100W），电压步进 20mV、电流步进 50mA；强制 Type-C，>3A 需 5A E-Marker 线缆（部分厂商私有扩展可至 6A+）。",
        "USB PD" to "USB PD 固定档（SPR）：5V/3A、9V/3A、15V/3A、20V/3A (60W)、20V/5A (100W)；PD 3.1 EPR 可扩展至 28V/36V/48V 5A (140W–240W)；强制 Type-C，>60W 需 5A E-Marker 线缆。",
        "QC 3.5" to "高通 Quick Charge 3.5 / 3+：支持连续动态调压与高电流档位（典型 20W–40W+），向下兼容 QC 2.0/3.0。",
        "QC 3.0" to "高通 Quick Charge 3.0：3.6V–20V 连续可调，INOV 调压步进 200mV，最大 3A（典型 18W–27W）。",
        "QC 2.0" to "高通 Quick Charge 2.0：固定 5V / 9V / 12V / 20V 档位，最大 3A（典型 18W–36W），通过 D+/D- 电平切换。",
        "MediaTek Pump Express" to "联发科 Pump Express：支持多档高压动态调压（常见 5V / 7V / 9V / 12V），通过电流脉冲通信。",
        "Huawei SCP" to "华为/荣耀 SuperCharge：宽幅电荷泵直充，典型档位 4.5V/5A 或 10V/2.25A (22.5W)、10V/4A (40W)、11V/6A (66W)、20V/4.4A (88W)、20V/5A (100W)；>5A 需专用大电流线缆。",
        "Huawei FCP" to "华为 Fast Charge Protocol：典型档位 9V/2A (18W) 高压快充，通过 D+/D- 通信。",
        "DCP 标准充电口" to "USB BC 1.2 标准充电口（非快充）：5V，通常上限 1.5A–2.4A（约 7.5W–12W）。",
        "CDP 充电下行口" to "USB 充电下行口（非快充，常见于电脑大电流口）：5V，通常上限 1.5A（约 7.5W）。",
        "SDP 标准下行口" to "USB 标准数据口（非快充，常见于普通电脑口）：5V，通常上限 0.5A–0.9A（约 2.5W–4.5W）。",
    )

    private val BRAND_SHORT = mapOf(
        "小米" to "小米 HyperCharge / Mi PPS",
        "OPPO" to "OPPO VOOC / SuperVOOC",
        "vivo" to "vivo FlashCharge",
        "华为" to "华为 FCP / SCP",
        "荣耀" to "荣耀 SuperCharge",
        "三星" to "三星 AFC / PPS",
        "魅族/努比亚" to "魅族 Super mCharge / 红魔 NeoCharger",
    )

    private val BRAND_PROTOCOLS = mapOf(
        "小米" to "HyperCharge / Mi PPS（魔改 PD PPS，电流上限放宽至 6A 左右）",
        "OPPO" to "VOOC / SuperVOOC（私有加密握手）",
        "vivo" to "FlashCharge（私有通信协议，新款部分兼容 PPS/UFCS）",
        "华为" to "FCP / SCP（部分机型支持 UFCS）",
        "荣耀" to "Honor SuperCharge（SCP 演进，兼容 PPS/UFCS）",
        "三星" to "AFC / SFC（SFC 完全遵循 USB PD 3.0 PPS）",
        "魅族/努比亚" to "Super mCharge / NeoCharger（大电流 PPS）",
    )

    fun brandOf(maker: String?): String? {
        val m = maker?.lowercase() ?: return null
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> "小米"
            "oppo" in m || "oneplus" in m || "realme" in m -> "OPPO"
            "vivo" in m || "iqoo" in m -> "vivo"
            "huawei" in m -> "华为"
            "honor" in m -> "荣耀"
            "samsung" in m -> "三星"
            "meizu" in m || "nubia" in m || "zte" in m -> "魅族/努比亚"
            else -> null
        }
    }

    private fun match(v: Double, a: Double, brand: String?, suffix: String = ""): List<String> {
        val preferred = ArrayList<String>(4)
        val others = ArrayList<String>(4)
        for (p in PROFILES) {
            if (abs(v - p.v) / p.v <= 0.06 && abs(a - p.a) / p.a <= 0.10) {
                if (p.brand == brand) preferred += "★" + p.name + suffix
                else others += p.name + suffix
            }
        }
        if (preferred.isEmpty()) return others
        preferred.addAll(others)
        return preferred
    }

    /** Observes the input-voltage pattern: fixed steps (PD/QC2/AFC) vs adjustable (PPS/QC3/UFCS). */
    private fun voltagePattern(vols: LongArray, count: Int, ev: MutableList<Evidence>, cand: MutableSet<String>) {
        val levels = HashSet<Long>(8)
        var allFixed = true
        var stepCount = 0
        var minStep = Double.MAX_VALUE
        for (idx in 0 until count) {
            val uv = vols[idx]
            if (idx > 0) {
                val d = abs(uv - vols[idx - 1]) / 1000.0
                if (d >= 10.0 && d < 1000.0) {
                    stepCount++
                    if (d < minStep) minStep = d
                }
            }
            if (uv > 5_500_000L) {
                val v = uv / 1e6
                levels += (v * 10.0).roundToLong()
                if (allFixed && FIXED_VOLTS.none { abs(v - it) <= 0.35 }) allFixed = false
            }
        }
        if (levels.isEmpty()) return
        val sorted = levels.sorted()
        ev += Evidence(3, "观察到的高压档位：" + sorted.joinToString(" / ") { "%.1fV".format(it / 10.0) })
        cand += if (allFixed) {
            "USB PD 固定档 / QC 2.0 / AFC"
        } else {
            "PPS / QC 3.0 / UFCS / 厂商私有"
        }
        if (stepCount >= 2) {
            if (minStep <= 60.0) {
                ev += Evidence(3, "调压步进最小约 %.0f mV，接近 PPS / UFCS 的 20 mV 量级（受采样精度影响）".format(minStep))
                cand += "USB PD PPS / UFCS"
            } else if (minStep in 150.0..260.0) {
                ev += Evidence(3, "调压步进最小约 %.0f mV，接近 QC 3.0 的 200 mV 量级".format(minStep))
                cand += "QC 3.0"
            }
        }
    }

    fun analyze(window: List<Snapshot>, maker: String? = null): Analysis {
        val last = window.lastOrNull()
            ?: return Analysis(Status.UNKNOWN, "暂无数据", emptyList(), emptyList())
        val limits = mutableListOf(
            "设备支持、充电器支持、当前实际协商是三个不同概念，本工具只报告当前可观测的证据。",
            "功率、温度、SOC 变化不能单独确认任何快充协议。",
            "实际握手功率还受线缆（>3A 需 5A E-Marker）、充电器档位和温度限制。",
        )
        if (!last.isPlugged) {
            return Analysis(Status.NOT_CHARGING, "未连接充电器", emptyList(), limits)
        }

        val evidence = mutableListOf<Evidence>()
        val candidates = LinkedHashSet<String>()
        val brand = brandOf(maker)

        // L4: driver-reported protocol names.
        val l4 = LinkedHashMap<String, Cls>()
        for (n in last.rootNodes) {
            if (!isInputNode(n)) continue
            for (key in PROTOCOL_KEYS) {
                val raw = n.props[key] ?: continue
                val tok = activeToken(raw) ?: continue
                val c = classify(tok) ?: continue
                val cur = l4[c.family]
                if (cur == null || c.rank > cur.rank) l4[c.family] = c
                evidence += Evidence(4, "${n.name}/$key = $tok → ${c.label}")
            }
        }

        // DCP/CDP/SDP is the BC1.2 port-detection stage; fast protocols (PD, QC...) negotiate on top of it.
        // When both are reported they are layers, not a conflict: keep the fast protocol only.
        var basicDropped = false
        if (l4.values.any { it.fast } && l4.values.any { !it.fast }) {
            l4.values.removeAll { !it.fast }
            basicDropped = true
        }
        val start = (window.size - 60).coerceAtLeast(0)
        val span = window.size - start
        val vols = LongArray(span)
        var volCount = 0
        var maxVin = 0L
        var advV: Long? = null
        var advI: Long? = null
        var bestP = 0.0; var bestV = 0.0; var bestI = 0.0
        var lastInp = InputInfo(null, null, null)

        for (idx in start until window.size) {
            val x = input(window[idx])
            if (idx == window.size - 1) lastInp = x
            val v = x.voltageUv
            if (v != null) {
                vols[volCount++] = v
                if (v > maxVin) maxVin = v
                val i = x.currentUa
                if (i != null) {
                    val p = v.toDouble() * abs(i.toDouble())
                    if (p > bestP) { bestP = p; bestV = v / 1e6; bestI = abs(i) / 1e6 }
                }
            }
            if (x.advVoltageUv != null) { advV = x.advVoltageUv; advI = x.advCurrentUa }
        }

        if (lastInp.voltageUv != null) {
            val p = inputPowerW(lastInp)
            evidence += Evidence(
                3,
                "输入侧电压 %.2f V".format(lastInp.voltageUv!! / 1e6) +
                    (lastInp.currentUa?.let { "，电流 %.0f mA".format(abs(it) / 1000.0) } ?: "") +
                    (p?.let { "，约 %.1f W".format(it) } ?: ""),
            )
        }
        if (advV != null) {
            evidence += Evidence(
                3,
                "充电器宣告上限（dumpsys）：最大电压 %.1f V".format(advV / 1e6) +
                    (advI?.let { "，最大电流 %.2f A".format(it / 1e6) } ?: "") + "（上限值，不代表当前协商结果）",
            )
        }
        voltagePattern(vols, volCount, evidence, candidates)

        // ---- Inference without live input readings (many devices expose none) -------------------
        // Smoothed battery-side power over the last <=10 samples.
        var pbSum = 0.0; var pbN = 0
        for (idx in (window.size - 10).coerceAtLeast(0) until window.size) {
            powerMw(window[idx])?.let { pbSum += it; pbN++ }
        }
        val batAvgW = if (pbN > 0) pbSum / pbN / 1000.0 else null
        val advVv = advV?.let { it / 1e6 }
        var progAdv = false
        var derivedVmin = 0.0
        if (advVv != null && advVv > 5.5) {
            if (FIXED_VOLTS.any { abs(advVv - it) <= 0.35 }) {
                evidence += Evidence(3, "充电器宣告上限 %.0f V 正好落在固定档位，至少支持固定档快充".format(advVv))
                candidates += "USB PD 固定档 / QC 2.0 / AFC"
            } else {
                progAdv = true
                evidence += Evidence(
                    3,
                    "充电器宣告上限 %.1f V 不在 9/12/15/20/28V 固定档上，说明提供可编程调压范围（PPS / UFCS / 厂商私有）".format(advVv),
                )
                candidates += "PPS / UFCS / QC 3.0 / 厂商私有"
                if (advVv > 21.0) {
                    evidence += Evidence(3, "宣告 %.1f V 高于标准 USB PD PPS 的 21 V 上限，更像厂商扩展协议".format(advVv))
                }
            }
        }
        if (last.isCharging && advI != null && batAvgW != null && batAvgW >= 5.0) {
            val advA = advI / 1e6
            // Input current cannot exceed the advertised limit, and input power >= battery power,
            // so Vin >= Pbat / Iadv (lossless lower bound).
            derivedVmin = batAvgW / advA
            evidence += Evidence(
                3,
                "按电池侧平均功率约 %.1f W 反推：输入电流不超过宣告上限 %.2f A，输入电压至少约 %.1f V（未计损耗的下限）".format(batAvgW, advA, derivedVmin) +
                    if (derivedVmin > 5.5) "，说明已进入高压协商而不是 5V 基础充电" else "",
            )
            if (derivedVmin > 5.5 && candidates.isEmpty()) candidates += "高压快充协议（PD / PPS / UFCS / QC / 私有）"
            if (advVv != null && batAvgW > advVv * advA * 1.05) {
                limits += "电池侧功率已超过充电器宣告上限（%.0f W），宣告值可能不准确。".format(advVv * advA)
            }
        }
        val ext = brand?.let { BRAND_SHORT[it] }
        if (progAdv && ext != null && advVv != null && advVv > 21.0) {
            candidates += "★$ext（宣告电压超出标准 PPS 上限，疑为厂商扩展）"
        }

        val matched = when {
            bestP > 0 && bestV > 5.5 && bestI >= 0.5 -> match(bestV, bestI, brand)
            advV != null && advV > 5_500_000L && advI != null -> match(advV / 1e6, advI / 1e6, brand, "（宣告值）")
            else -> emptyList()
        }
        if (matched.isNotEmpty()) {
            evidence += Evidence(3, "电压/电流与规格表档位接近：" + matched.take(4).joinToString("、") + "（仅缩小范围）")
            candidates += matched.take(6)
        }

        // L2 / L1 references.
        val batMw = powerMw(last)
        if (batMw != null && batMw >= 15_000.0) {
            evidence += Evidence(2, "电池侧估算功率约 %.1f W（仅参考，不能确认协议）".format(batMw / 1000.0))
        }
        if (brand != null) {
            evidence += Evidence(1, "本机品牌 $brand，常见私有协议：${BRAND_PROTOCOLS[brand]}（仅缩小范围）")
        }
        if (last.rootNodes.isEmpty()) {
            limits += "未启用 Root / Shizuku 或无法读取 sysfs：只有电池侧数据，无法识别协议。"
        }
        if (volCount >= 2) limits += "电压步进判断受采样间隔和驱动上报精度影响，仅作参考。"
        if (basicDropped) {
            limits += "驱动同时报告了 DCP/CDP/SDP（BC1.2 充电口识别）和快充协议：前者是物理口类型，快充协议在其之上协商，二者不冲突，结论以快充协议为准。"
        }
        if (l4.values.any { it.fast } && lastInp.voltageUv == null && last.rootNodes.isNotEmpty()) {
            limits += "该机型的输入侧节点没有提供有效的电压/电流读数（常见为 voltage_now=0），无法验证实际协商的电压档位。"
        }

        val cl = candidates.toList()
        val driver = if (l4.isEmpty()) null else l4.values.joinToString(" / ") { it.label }
        var predicted = matched.firstOrNull()?.removePrefix("★")?.removeSuffix("（宣告值）") ?: cl.firstOrNull()?.removePrefix("★")
        // Driver says plain USB PD but the advertised ceiling is off the fixed PDO ladder => PPS-style.
        if (progAdv && l4["PD"]?.rank == 0 && advVv != null) {
            predicted = "USB PD PPS" + if (advVv > 21.0 && ext != null) {
                "（宣告 %.0f V 高于标准 PPS 上限 21 V，疑为 $ext 扩展）".format(advVv)
            } else {
                "（宣告电压超出固定档，推测支持可编程调压）"
            }
        }
        val consistent = l4.values.any { c ->
            predicted != null && (predicted.contains(c.family, true) || predicted.contains(c.label, true))
        }

        // Plain-language description combining both layers.
        val desc = StringBuilder(160)
        desc.append(
            if (driver != null) "驱动层（sysfs 的 usb_type / type 等）报告：$driver。"
            else "驱动层没有报告协议名称（无 Root/Shizuku，或该机型节点不提供）。",
        )
        if (predicted != null) {
            val basis = ArrayList<String>(2)
            if (bestV > 5.5) basis += "输入侧约 %.1f V".format(bestV) + (if (bestI > 0) "、%.2f A".format(bestI) else "")
            else if (maxVin > 5_500_000L) basis += "输入侧出现 %.1f V 高压".format(maxVin / 1e6)
            else if (derivedVmin > 5.5) basis += "按电池侧功率反推输入电压至少 %.1f V".format(derivedVmin) +
                (advVv?.let { "、充电器宣告上限 %.1f V".format(it) } ?: "")
            else if (advV != null) basis += "充电器宣告上限 %.1f V".format(advV / 1e6)
            desc.append("根据").append(if (basis.isEmpty()) "电压档位和规格表" else basis.joinToString("、") + "及规格表")
                .append("，预测为：$predicted。这只是缩小范围，不是确认。")
            if (driver != null) {
                desc.append(if (consistent) "预测与驱动报告一致。" else "预测与驱动报告不一致，请以驱动报告为准，预测仅供参考。")
            }
        } else {
            desc.append("输入侧数据不足，暂时无法预测具体协议。")
        }
        if (advV != null && advI != null) {
            desc.append("充电器宣告上限 %.1f V / %.2f A（约 %.0f W，上限值）。".format(advV / 1e6, advI / 1e6, advV.toDouble() * advI / 1e12))
        }
        if (batMw != null) {
            desc.append("当前电池侧估算功率约 %.1f W（仅参考，不能用来确认协议）。".format(batMw / 1000.0))
        }
        val description = desc.toString()

        fun mk(status: Status, headline: String, key: String, spec: String? = null) =
            Analysis(status, headline, evidence, limits, cl, key, driver, predicted, description, spec)

        return when {
            l4.size > 1 -> mk(
                Status.CONFLICT,
                "不同节点报告了不同协议：" + l4.values.joinToString(" / ") { it.label },
                "X:" + l4.values.joinToString("+") { it.label },
            )
            l4.size == 1 -> {
                val c = l4.values.first()
                mk(
                    Status.CONFIRMED,
                    if (c.fast) "驱动报告：${c.label}" else "驱动报告：${c.label}（非快充）",
                    "C:" + c.label,
                    PROTOCOL_SPECS[c.label],
                )
            }
            maxVin > 5_500_000L || matched.isNotEmpty() || derivedVmin > 5.5 || progAdv -> mk(
                Status.POSSIBLE,
                when {
                    maxVin > 5_500_000L -> "输入侧出现 %.1f V 高压，疑似快充协商".format(maxVin / 1e6)
                    derivedVmin > 5.5 -> "按功率反推输入电压至少 %.1f V，疑似高压快充".format(derivedVmin)
                    else -> "充电器宣告支持高压"
                } + (if (predicted != null) "；预测：$predicted" else "，但无法确定具体协议"),
                "P:" + (predicted ?: "hv"),
            )
            else -> mk(Status.UNKNOWN, "没有足够证据识别协议", "")
        }
    }

    fun metrics(list: List<Snapshot>): Metrics {
        if (list.isEmpty()) return Metrics(0, 0, null, null, null, null)
        var pSum = 0.0; var pN = 0; var peak: Double? = null
        var tSum = 0.0; var tN = 0
        var firstLvl: Snapshot? = null
        var lastLvl: Snapshot? = null
        for (i in list.indices) {
            val s = list[i]
            powerMw(s)?.let {
                pSum += it; pN++
                val pk = peak
                if (pk == null || it > pk) peak = it
            }
            s.tempDeci?.let { tSum += it; tN++ }
            if (s.levelPct != null) {
                if (firstLvl == null) firstLvl = s
                lastLvl = s
            }
        }
        var rate: Double? = null
        if (firstLvl != null && lastLvl != null) {
            val minutes = (lastLvl.elapsedMs - firstLvl.elapsedMs) / 60_000.0
            if (minutes >= 1.0) rate = (lastLvl.levelPct!! - firstLvl.levelPct!!) / minutes
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
