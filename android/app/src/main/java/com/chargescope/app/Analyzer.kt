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

    private val VBUS_KEYS = arrayOf("vbus_now", "bq2597x_bus_voltage", "sc_bus_voltage", "ti_bus_voltage", "bus_voltage")
    private val IBUS_KEYS = arrayOf("ibus_now", "bq2597x_bus_current", "sc_bus_current", "ti_bus_current", "bus_current")

    private fun toMicroVolts(raw: Long?): Long? = when {
        raw == null -> null
        raw in 3_000_000L..50_000_000L -> raw
        raw in 3_000L..50_000L -> raw * 1000L
        else -> null
    }

    private fun toMicroAmps(raw: Long?): Long? {
        if (raw == null || raw == 0L) return null
        val a = abs(raw)
        return when {
            a in 100_000L..25_000_000L -> raw
            a in 100L..25_000L -> raw * 1000L
            else -> null
        }
    }

    fun input(s: Snapshot): InputInfo {
        var v: Long? = null
        var i: Long? = null
        var fallbackV: Long? = null
        var fallbackI: Long? = null
        var type: String? = null
        var advV: Long? = null
        var advI: Long? = null
        for (n in s.rootNodes) {
            if (n.name == "dumpsys") {
                advV = n.props["max_charging_voltage"]?.trim()?.toLongOrNull()?.takeIf { it in 3_000_000L..50_000_000L }
                advI = n.props["max_charging_current"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                continue
            }
            // Explicit VBUS/IBUS fields on charge pumps or vendor nodes are always input-side.
            if (v == null) {
                for (k in VBUS_KEYS) {
                    val parsed = toMicroVolts(n.props[k]?.trim()?.toLongOrNull())
                    if (parsed != null) { v = parsed; break }
                }
            }
            if (i == null) {
                for (k in IBUS_KEYS) {
                    val parsed = toMicroAmps(n.props[k]?.trim()?.toLongOrNull())
                    if (parsed != null) { i = parsed; break }
                }
            }
            if (!isInputNode(n)) continue
            val isUcsi = n.name.startsWith("ucsi", ignoreCase = true)
            val nv = toMicroVolts(n.props["voltage_now"]?.trim()?.toLongOrNull())
            val ni = toMicroAmps(n.props["current_now"]?.trim()?.toLongOrNull())
            // Prefer real `usb` node readings over `ucsi-*` (which often reports requested PDO/limit).
            if (isUcsi) {
                if (fallbackV == null) fallbackV = nv
                if (fallbackI == null) fallbackI = ni
            } else {
                if (v == null) v = nv
                if (i == null) i = ni
            }
            if (type == null || n.name == "usb") {
                (n.props["usb_type"] ?: n.props["real_type"])?.let { activeToken(it) }?.let { type = it }
            }
        }
        return InputInfo(v ?: fallbackV, i ?: fallbackI, type, advV, advI)
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

    /** Battery health percentage: prefer fuel-gauge `soh` across battery/vendor nodes, else `charge_full / design`. */
    fun batteryHealthPct(s: Snapshot): Int? {
        var full: Long? = null
        var design: Long? = null
        for (n in s.rootNodes) {
            val t = n.props["type"]?.uppercase()
            val isBat = t == "BATTERY" || n.name == "battery" || n.name == "bms" ||
                n.name == "qcom-battery" || n.name == "xm_power" || n.name == "oplus_chg"
            if (!isBat) continue
            n.props["soh"]?.trim()?.toIntOrNull()?.takeIf { it in 1..100 }?.let { return it }
            if (full == null) full = n.props["charge_full"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            if (design == null) design = n.props["charge_full_design"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        }
        if (full != null && design != null) {
            val pct = (full * 100.0 / design).roundToLong().toInt()
            if (pct in 10..120) return pct.coerceAtMost(100)
        }
        return null
    }

    /** Battery cycle count from sysfs (null if unavailable). */
    fun batteryCycleCount(s: Snapshot): Int? {
        for (n in s.rootNodes) {
            val t = n.props["type"]?.uppercase()
            if (t != "BATTERY" && n.name != "battery" && n.name != "bms") continue
            n.props["cycle_count"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.let { return it }
        }
        return null
    }

    /** Estimated conversion efficiency (battery power / input power * 100), only when both sides are valid. */
    fun efficiencyPct(batW: Double?, inW: Double?): Double? {
        if (batW == null || inW == null || inW < 2.0) return null
        val eff = batW / inW * 100.0
        return if (eff in 45.0..102.0) eff.coerceAtMost(99.9) else null
    }

    /**
     * Estimated time remaining until full charge, as (remainingMs to fromSystemApi).
     * Prefers Android 9+ BatteryManager.computeChargeTimeRemaining(), falling back to SOC rate.
     * Returns null when not charging or no reliable estimate is available.
     */
    fun estimateChargeTimeRemainingMs(s: Snapshot?, m: Metrics?): Pair<Long, Boolean>? {
        if (s == null || !s.isCharging) return null
        val sysMs = s.chargeTimeRemainingMs
        if (sysMs != null && sysMs > 0L) return sysMs to true
        val level = s.levelPct ?: return null
        val rate = m?.socRatePerMin ?: return null
        if (level !in 0..99 || rate <= 0.05) return null
        val ms = ((100 - level) / rate * 60_000.0).toLong()
        return if (ms in 1L..(48L * 3600_000L)) ms to false else null
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
        "小米 HyperCharge / Mi PPS" to "小米澎湃秒充（底层基于魔改 USB PD PPS + 私有加密认证）：典型档位 33W (11V/3A)、67W (11V/6.1A 或 20V/3.25A)、90W (20V/4.5A)、120W (20V/6A)；需原厂或获授权 6A 线缆。",
        "OPPO VOOC / SuperVOOC" to "OPPO / 一加 / realme 私有闪充：30W (5V/6A)、65W (10V/6.5A)、80W (11V/7.3A)、100W (11V/9.1A)、150W (20V/7.5A)、240W (20V/12A)；需原厂/认证大电流线缆。",
        "三星 SFC" to "三星超快速充电（遵循标准 USB PD 3.0 PPS）：SFC 1.0 为 25W (9V/2.77A)，SFC 2.0 为 45W (11V/4.05A 或 9V/5A，需 5A E-Marker 线缆)。",
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

    private val CANDIDATE_TIER_SPECS = listOf(
        arrayOf("小米", "HyperCharge", "Mi PPS") to "小米 HyperCharge / Mi PPS：33W (11V/3A)、67W (11V/6.1A 或 20V/3.25A)、90W (20V/4.5A)、120W (20V/6A)；魔改 PPS，需原厂/认证 6A 线缆。",
        arrayOf("OPPO", "SuperVOOC", "VOOC") to "OPPO VOOC / SuperVOOC：30W (5V/6A)、65W (10V/6.5A)、80W (11V/7.3A)、100W (11V/9.1A)、150W (20V/7.5A)、240W (20V/12A)；需原厂/认证大电流线缆。",
        arrayOf("vivo", "FlashCharge") to "vivo / iQOO FlashCharge：44W (11V/4A)、66W (11V/6A)、80W (20V/4A)、120W (20V/6A)、200W (20V/10A)。",
        arrayOf("华为", "SCP", "FCP") to "华为 FCP / SCP：18W (9V/2A)、22.5W (4.5V/5A 或 10V/2.25A)、40W (10V/4A)、66W (11V/6A)、88W (20V/4.4A)、100W (20V/5A)。",
        arrayOf("荣耀") to "荣耀 SuperCharge：66W (11V/6A)、80W (20V/4A)、100W (20V/5A)；同时较好兼容宽幅 PPS 与 UFCS。",
        arrayOf("三星", "AFC", "SFC") to "三星 AFC / SFC：15W AFC (9V/1.67A)、25W PPS (9V/2.77A)、45W PPS (11V/4.05A 或 9V/5A，需 5A E-Marker 线)。",
        arrayOf("魅族", "红魔", "NeoCharger", "mCharge") to "魅族 / 红魔：80W (20V/4A) 至 165W (20V/8.25A)，基于大电流 PPS。",
        arrayOf("PPS") to "USB PD 3.0 PPS：3.3V–21V（标准最大 5A / 100W），步进 20mV / 50mA；>3A 需 5A E-Marker 线缆。",
        arrayOf("UFCS") to "UFCS 融合快充：通用档 33W (11V/3A)、44W (11V/4A)；高阶互认档 65W (10V/6.5A 或 20V/3.25A)、100W (20V/5A)；步进 10–20mV。",
        arrayOf("QC 3") to "QC 3.0：3.6V–20V 连续可调，步进 200mV，最大 3A（18W–27W）。",
        arrayOf("QC 2") to "QC 2.0：固定 5V / 9V / 12V / 20V，最大 3A（18W–36W）。",
        arrayOf("USB PD", "PD 固定") to "USB PD 固定档：5V/3A、9V/3A (27W)、15V/3A (45W)、20V/3A (60W)、20V/5A (100W)；PD 3.1 EPR 扩展至 28V/36V/48V 5A (140W–240W)。",
    )

    internal fun candidateSpecsFor(candidates: List<String>, predicted: String?): List<String> {
        if (candidates.isEmpty() && predicted == null) return emptyList()
        val combined = buildString {
            if (predicted != null) append(predicted).append(' ')
            for (c in candidates) append(c).append(' ')
        }
        val out = LinkedHashSet<String>()
        for ((keys, spec) in CANDIDATE_TIER_SPECS) {
            if (keys.any { combined.contains(it, ignoreCase = true) }) out += spec
        }
        return out.toList()
    }

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

    private val UNIVERSAL_BRANDS = setOf("PD", "UFCS", "QC")

    private fun match(v: Double, a: Double, brand: String?, suffix: String = ""): List<String> {
        val preferred = ArrayList<String>(4)
        val others = ArrayList<String>(4)
        for (p in PROFILES) {
            // Do not match another phone maker's proprietary protocol (e.g. Samsung AFC on a Xiaomi phone).
            if (brand != null && p.brand !in UNIVERSAL_BRANDS && p.brand != brand) continue
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

        // L4: driver-reported protocol names + Type-C/PD stack + vendor fast-charge flags.
        val l4 = LinkedHashMap<String, Cls>()
        for (n in last.rootNodes) {
            if (n.name == "typec") {
                val pdRev = n.props["partner_usb_power_delivery_revision"] ?: n.props["usb_power_delivery_revision"]
                val pdSup = n.props["partner_supports_usb_power_delivery"]
                if (pdSup == "yes" || (!pdRev.isNullOrBlank() && pdRev != "0.0")) {
                    val revLabel = if (!pdRev.isNullOrBlank() && pdRev != "0.0") " (PD $pdRev)" else ""
                    evidence += Evidence(4, "typec：充电器支持 USB PD$revLabel")
                    val cur = l4["PD"]
                    if (cur == null) l4["PD"] = Cls("USB PD", "PD", true, 0)
                }
                n.props["source_caps"]?.let { caps ->
                    evidence += Evidence(4, "typec 广播档位：$caps")
                    if (caps.contains("PPS", true)) {
                        l4["PD"] = Cls("USB PD PPS", "PD", true, 1)
                    }
                }
                val cablePlug = n.props["cable_plug_type"]
                val cableRev = n.props["cable_usb_power_delivery_revision"]
                if (!cablePlug.isNullOrBlank() || (!cableRev.isNullOrBlank() && cableRev != "0.0")) {
                    evidence += Evidence(3, "typec 线缆信息：" + listOfNotNull(cablePlug, cableRev?.let { "PD $it" }).joinToString(" / "))
                }
                continue
            }
            val verified = n.props["pd_verifed"] ?: n.props["pd_verified"] ?: n.props["pd_authenticated"]
            val apdoMax = (n.props["apdo_max"] ?: n.props["power_max"])?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val qct = n.props["quick_charge_type"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val svid = n.props["adapter_svid"]?.trim()?.lowercase()
            if (apdoMax != null) {
                evidence += Evidence(4, "${n.name}/apdo_max = ${apdoMax}W（驱动报告 APDO 最大功率）")
            }
            if (svid == "2717") {
                evidence += Evidence(4, "${n.name}/adapter_svid = 0x2717（小米官方 USB-IF 厂商 ID）")
            }
            if (qct != null) {
                val qLabel = when (qct) {
                    1 -> "基础快充"; 2 -> "闪充 / PD"; 3 -> "超级快充 / Mi PPS"; 4 -> "澎湃秒充 / HyperCharge"; else -> "类型 $qct"
                }
                evidence += Evidence(4, "${n.name}/quick_charge_type = $qct → $qLabel")
            }
            if (verified == "1" || (qct != null && qct >= 3 && (brand == "小米" || svid == "2717"))) {
                val miLabel = "小米 HyperCharge / Mi PPS" + (apdoMax?.let { " (${it}W)" } ?: "")
                evidence += Evidence(4, "${n.name}：已通过小米私有 PPS 加密认证 → $miLabel")
                val cur = l4["PD"]
                if (cur == null || 2 > cur.rank) l4["PD"] = Cls(miLabel, "PD", true, 2)
                candidates += "★$miLabel（已认证）"
            }
            // OPPO / OnePlus / realme VOOC / SuperVOOC
            if (n.props["voocchg_ing"]?.trim() == "1") {
                evidence += Evidence(4, "${n.name}/voocchg_ing = 1 → OPPO VOOC / SuperVOOC")
                val cur = l4["VOOC"]
                if (cur == null || 2 > cur.rank) l4["VOOC"] = Cls("OPPO VOOC / SuperVOOC", "VOOC", true, 2)
                candidates += "★OPPO VOOC / SuperVOOC（已认证）"
            }
            n.props["fastchg_type"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" }?.let { ft ->
                evidence += Evidence(4, "${n.name}/fastchg_type = $ft（厂商私有快充枚举，原始值）")
            }
            // Samsung SFC / AFC (`sec_battery` hv_charger_status: 1=AFC, 2=25W SFC 1.0, 3=45W SFC 2.0)
            n.props["hv_charger_status"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { hv ->
                val (sLabel, sFamily, sRank) = when (hv) {
                    3 -> Triple("三星 SFC 2.0 (45W PPS)", "PD", 2)
                    2 -> Triple("三星 SFC 1.0 (25W PPS)", "PD", 2)
                    1 -> Triple("三星 AFC / 高压快充", "AFC", 1)
                    else -> Triple("三星快充状态 $hv", "PD", 1)
                }
                evidence += Evidence(4, "${n.name}/hv_charger_status = $hv → $sLabel")
                val cur = l4[sFamily]
                if (cur == null || sRank > cur.rank) l4[sFamily] = Cls(sLabel, sFamily, true, sRank)
                candidates += "★$sLabel"
            }
            n.props["pd_max_power"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { mw ->
                val w = if (mw > 1000) mw / 1000 else mw
                evidence += Evidence(4, "${n.name}/pd_max_power = ${w}W")
            }
            // Huawei / Honor SCP / FCP
            if (n.props["scp_status"]?.trim() == "1") {
                evidence += Evidence(4, "${n.name}/scp_status = 1 → Huawei SCP")
                val cur = l4["SCP"]
                if (cur == null || 1 > cur.rank) l4["SCP"] = Cls("Huawei SCP", "SCP", true, 1)
            } else if (n.props["fcp_status"]?.trim() == "1") {
                evidence += Evidence(4, "${n.name}/fcp_status = 1 → Huawei FCP")
                val cur = l4["FCP"]
                if (cur == null || 1 > cur.rank) l4["FCP"] = Cls("Huawei FCP", "FCP", true, 1)
            }
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
        val advAa = advI?.let { it / 1e6 }
        var progAdv = false
        var derivedVmin = 0.0
        if (advVv != null && advVv > 5.5) {
            val highCurrentVendor = advAa != null && advAa > 5.05
            val onFixedVolt = FIXED_VOLTS.any { abs(advVv - it) <= 0.15 }
            if (onFixedVolt && !highCurrentVendor) {
                evidence += Evidence(3, "充电器宣告上限 %.0f V 正好落在固定档位，至少支持固定档快充".format(advVv))
                candidates += "USB PD 固定档 / QC 2.0 / AFC"
            } else {
                progAdv = true
                if (highCurrentVendor) {
                    evidence += Evidence(
                        3,
                        "充电器宣告上限 %.1f V / %.2f A（电流超过标准 5 A 上限），属于厂商私有高电流 / 魔改 PPS 协议".format(advVv, advAa),
                    )
                    val extName = brand?.let { BRAND_SHORT[it] }
                    if (extName != null) {
                        candidates += "★$extName（宣告电流 %.1f A 超出标准 5A 上限）".format(advAa)
                    }
                    candidates += "厂商私有高电流 / 魔改 PPS"
                } else {
                    evidence += Evidence(
                        3,
                        "充电器宣告上限 %.1f V 不在 9/12/15/20/28V 固定档上，说明提供可编程调压范围（PPS / UFCS / 厂商私有）".format(advVv),
                    )
                    candidates += "PPS / UFCS / QC 3.0 / 厂商私有"
                }
                if (advVv > 21.0) {
                    evidence += Evidence(3, "宣告 %.1f V 高于标准 USB PD PPS 的 21 V 上限，更像厂商扩展协议".format(advVv))
                }
            }
        } else if (advAa != null && advAa > 5.05) {
            progAdv = true
            evidence += Evidence(
                3,
                "充电器宣告电流上限 %.2f A 超过标准 5 A，疑似低压大电流私有快充（VOOC / SCP 等）".format(advAa),
            )
            val extName = brand?.let { BRAND_SHORT[it] }
            if (extName != null) candidates += "★$extName（宣告电流 %.1f A 超出标准 5A 上限）".format(advAa)
            candidates += "低压大电流私有快充（VOOC / SCP 等）"
        }
        if (last.isCharging && advI != null && batAvgW != null && batAvgW >= 5.0) {
            val advA = advI / 1e6
            if (advA > 0.1) {
                // Input current cannot exceed the advertised limit, and input power >= battery power,
                // so Vin >= Pbat / Iadv (lossless lower bound). Only report when it proves >5V charging.
                derivedVmin = batAvgW / advA
                if (derivedVmin > 5.5) {
                    evidence += Evidence(
                        3,
                        "按电池侧平均功率约 %.1f W 反推：输入电流不超过宣告上限 %.2f A，输入电压至少约 %.1f V（未计损耗的下限），说明已进入高压协商而不是 5V 基础充电"
                            .format(batAvgW, advA, derivedVmin),
                    )
                    if (candidates.isEmpty()) candidates += "高压快充协议（PD / PPS / UFCS / QC / 私有）"
                }
                if (advVv != null && batAvgW > advVv * advA * 1.05) {
                    limits += "电池侧功率已超过充电器宣告上限（%.0f W），宣告值可能不准确。".format(advVv * advA)
                }
            }
        }
        val ext = brand?.let { BRAND_SHORT[it] }
        if (progAdv && ext != null && advVv != null && advVv > 21.0) {
            candidates += "★$ext（宣告电压超出标准 PPS 上限，疑为厂商扩展）"
        }

        val trickle = (last.levelPct ?: 0) >= 95 || (bestP > 0 && bestI < 1.0)
        val matched = when {
            !trickle && bestP > 0 && bestV > 5.5 && bestI >= 1.0 -> match(bestV, bestI, brand)
            advV != null && advV > 5_500_000L && advI != null -> match(advV / 1e6, advI / 1e6, brand, "（宣告值）")
            else -> emptyList()
        }
        if (trickle && bestP > 0) {
            limits += "当前处于满电或低电流降流阶段（电量 ${last.levelPct ?: "—"}%），已跳过实时电流规格表匹配以避免误判。"
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
        val dumpsysOnly = last.rootNodes.isNotEmpty() && last.rootNodes.all { it.name == "dumpsys" }
        if (last.rootNodes.isEmpty()) {
            limits += "未启用 Root / Shizuku 或无法读取 sysfs：只有电池侧数据，无法识别协议。"
        } else if (dumpsysOnly) {
            limits += "当前仅获取到 dumpsys battery（未读到 /sys/class/power_supply 内核节点），通常是 Android 14+ SELinux 限制了 Shizuku (uid=2000) 读取 sysfs 文件；如需 L4 驱动协议确认与实时输入功率，需 Root 权限。"
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
        // If L4 confirmed a vendor-authenticated fast-charge protocol (rank >= 2), keep prediction aligned.
        val vendorConfirmed = l4.values.firstOrNull { it.rank >= 2 }
        if (vendorConfirmed != null) {
            predicted = vendorConfirmed.label
        }
        // Driver says plain USB PD but the advertised ceiling is off the fixed PDO ladder => PPS-style.
        var ppsReason: String? = null
        if (progAdv && l4["PD"]?.rank == 0 && advVv != null) {
            if (advVv > 21.0 && ext != null) {
                predicted = "USB PD PPS / $ext"
                ppsReason = "宣告 %.0f V 超出标准 PPS 21 V 上限".format(advVv)
            } else {
                predicted = "USB PD PPS"
                ppsReason = "宣告 %.1f V 不在固定档，支持可编程调压".format(advVv)
            }
        }
        val consistent = l4.values.any { c ->
            predicted != null && (predicted.contains(c.family, true) || predicted.contains(c.label, true))
        }

        // Concise multi-line summary: 1) driver, 2) prediction + reason, 3) power/ceiling.
        val lines = ArrayList<String>(3)
        lines += when {
            driver != null -> "驱动：已报告 $driver"
            dumpsysOnly -> "驱动：仅读到 dumpsys（SELinux 限制 Shizuku 读取 sysfs，需 Root 获取驱动协议名）"
            else -> "驱动：未报告协议名称（无特权或机型未暴露）"
        }
        if (predicted != null) {
            val reasons = ArrayList<String>(2)
            if (bestV > 5.5) reasons += "输入 %.1f V".format(bestV) + (if (bestI > 0) " / %.2f A".format(bestI) else "")
            else if (maxVin > 5_500_000L) reasons += "输入高压 %.1f V".format(maxVin / 1e6)
            else if (derivedVmin > 5.5) reasons += "反推输入 ≥ %.1f V".format(derivedVmin)
            if (ppsReason != null) reasons += ppsReason
            else if (reasons.isEmpty() && advVv != null) reasons += "参考宣告上限 %.1f V".format(advVv)
            if (driver != null) reasons += if (consistent) "与驱动一致" else "与驱动不一致，以驱动为准"
            val note = if (reasons.isEmpty()) "仅供参考" else reasons.joinToString("；") + "，仅供参考"
            lines += "推测：$predicted（$note）"
        } else {
            lines += "推测：输入侧数据不足，暂无法预测"
        }
        val pwr = ArrayList<String>(2)
        if (advV != null && advI != null) {
            pwr += "充电器上限 %.1f V / %.2f A（约 %.0f W）".format(advV / 1e6, advI / 1e6, advV.toDouble() * advI / 1e12)
        }
        if (batMw != null) {
            pwr += "电池侧约 %.1f W".format(batMw / 1000.0)
        }
        if (pwr.isNotEmpty()) lines += "功率：" + pwr.joinToString("；")
        val description = lines.joinToString("\n")

        val candSpecs = candidateSpecsFor(cl, predicted)

        fun mk(status: Status, headline: String, key: String, spec: String? = null) =
            Analysis(status, headline, evidence, limits, cl, key, driver, predicted, description, spec, candSpecs)

        return when {
            l4.size > 1 -> mk(
                Status.CONFLICT,
                "不同节点报告了不同协议：" + l4.values.joinToString(" / ") { it.label },
                "X:" + l4.values.joinToString("+") { it.label },
            )
            l4.size == 1 -> {
                val c = l4.values.first()
                val spec = PROTOCOL_SPECS[c.label]
                    ?: PROTOCOL_SPECS.entries.firstOrNull { c.label.startsWith(it.key) }?.value
                mk(
                    Status.CONFIRMED,
                    if (c.fast) "驱动报告：${c.label}" else "驱动报告：${c.label}（非快充）",
                    "C:" + c.label,
                    spec,
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
