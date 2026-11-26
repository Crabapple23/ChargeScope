package com.chargescope.app

import android.os.Process
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Instantiated by Shizuku inside the privileged shell process (uid 2000).
 * Reads /sys/class/power_supply, /sys/class/typec, and vendor charging dirs directly via File I/O
 * (zero shell forks) and runs `dumpsys battery` directly.
 * Pass "deep" to `run()` for an on-demand enumeration of all readable small sysfs files.
 */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(script: String): String {
        if (script == "deep") return deepProbe()
        val sb = StringBuilder(4096)
        sb.append("#uid=").append(Process.myUid()).append('\n')

        val base = File("/sys/class/power_supply")
        val dirs = try { base.listFiles() } catch (_: Exception) { null }
        if (dirs != null) {
            for (d in dirs) {
                sb.append("@@").append(d.name).append('\n')
                for (f in FIELDS) {
                    val v = readFirstLine(File(d, f))
                    if (!v.isNullOrEmpty()) sb.append(f).append('=').append(v).append('\n')
                }
            }
        }
        for (vpath in VENDOR_DIRS) {
            val vd = File(vpath)
            if (!vd.isDirectory) continue
            sb.append("@@").append(vd.name).append('\n')
            for (f in VENDOR_FIELDS) {
                val v = readFirstLine(File(vd, f))
                if (!v.isNullOrEmpty()) sb.append(f).append('=').append(v).append('\n')
            }
        }
        val port0 = File("/sys/class/typec/port0")
        if (port0.isDirectory) {
            sb.append("@@typec\n")
            for (f in arrayOf("power_role", "data_role", "usb_power_delivery_revision", "usb_typec_revision")) {
                readFirstLine(File(port0, f))?.takeIf { it.isNotEmpty() }?.let { sb.append(f).append('=').append(it).append('\n') }
            }
            val partner = File("/sys/class/typec/port0-partner")
            for (f in arrayOf("supports_usb_power_delivery", "usb_power_delivery_revision")) {
                readFirstLine(File(partner, f))?.takeIf { it.isNotEmpty() }?.let { sb.append("partner_").append(f).append('=').append(it).append('\n') }
            }
            val cable = File("/sys/class/typec/port0-cable")
            for (f in arrayOf("plug_type", "usb_power_delivery_revision")) {
                readFirstLine(File(cable, f))?.takeIf { it.isNotEmpty() }?.let { sb.append("cable_").append(f).append('=').append(it).append('\n') }
            }
            val capsDir = File("/sys/class/typec/port0-partner/usb_power_delivery/source-capabilities")
            val caps = try { capsDir.listFiles() } catch (_: Exception) { null }
            if (caps != null && caps.isNotEmpty()) {
                val capSb = StringBuilder()
                for (c in caps) {
                    if (!c.isDirectory) continue
                    val mv = readFirstLine(File(c, "voltage"))
                    val mxv = readFirstLine(File(c, "maximum_voltage"))
                    val mnv = readFirstLine(File(c, "minimum_voltage"))
                    val mxi = readFirstLine(File(c, "maximum_current"))
                    if (!mv.isNullOrEmpty() && !mxi.isNullOrEmpty()) {
                        capSb.append(' ').append(mv).append("mV/").append(mxi).append("mA")
                    } else if (!mnv.isNullOrEmpty() && !mxv.isNullOrEmpty() && !mxi.isNullOrEmpty()) {
                        capSb.append(' ').append(mnv).append('-').append(mxv).append("mV/").append(mxi).append("mA(PPS)")
                    }
                }
                if (capSb.isNotEmpty()) sb.append("source_caps=").append(capSb.toString().trim()).append('\n')
            }
        }
        sb.append("@@dumpsys\n")
        try {
            val p = ProcessBuilder("dumpsys", "battery").redirectErrorStream(false).start()
            p.outputStream.close()
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line).append('\n')
                }
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    private fun deepProbe(): String {
        val sb = StringBuilder(8192)
        sb.append("#uid=").append(Process.myUid()).append('\n')
        val roots = ArrayList<File>()
        try { File("/sys/class/power_supply").listFiles()?.let { roots.addAll(it) } } catch (_: Exception) {}
        try { File("/sys/class/typec").listFiles()?.let { roots.addAll(it) } } catch (_: Exception) {}
        for (vp in VENDOR_DIRS) {
            val d = File(vp)
            if (d.isDirectory) roots += d
        }
        for (dir in roots) {
            if (!dir.isDirectory) continue
            sb.append("=== ").append(dir.path).append(" ===\n")
            val files = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in files) {
                if (!f.isFile || !f.canRead()) continue
                val n = f.name
                if (n == "uevent" || n == "modalias" || n == "waiting_for_supplier" || n.startsWith("wakeup")) continue
                val v = readFirstLine(f)
                if (!v.isNullOrEmpty()) sb.append(n).append('=').append(v.take(120)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun readFirstLine(f: File): String? = try {
        if (!f.canRead()) null else f.bufferedReader().use {
            it.readLine()?.replace("\u0000", "")?.trim()
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val FIELDS = arrayOf(
            "type", "status", "online", "present", "capacity",
            "voltage_now", "current_now", "current_avg", "power_now",
            "voltage_max", "current_max", "usb_type", "real_type",
            "charge_type", "temp", "input_current_limit",
            "constant_charge_current", "charge_counter",
            "charge_full", "charge_full_design", "soh", "cycle_count",
            "quick_charge_type", "pd_active", "pd_verifed", "pd_verified",
            "pd_authenticated", "apdo_max", "tx_adapter", "adapter_type",
            "fastcharge_mode", "pd_type", "pe_type",
            "vbus_now", "ibus_now", "sc_bus_voltage", "sc_bus_current",
            "ti_bus_voltage", "ti_bus_current", "bus_voltage", "bus_current",
        )
        val VENDOR_DIRS = arrayOf(
            "/sys/class/qcom-battery",
            "/sys/class/xm_power",
            "/sys/class/oplus_chg",
        )
        val VENDOR_FIELDS = arrayOf(
            "quick_charge_type", "pd_active", "pd_verifed", "pd_verified",
            "pd_authenticated", "apdo_max", "power_max", "adapter_svid",
            "tx_adapter", "adapter_type", "real_type", "soh", "cycle_count",
            "vbus_now", "ibus_now", "bq2597x_bus_voltage", "bq2597x_bus_current",
            "fastchg_type", "voocchg_ing",
        )
    }
}
