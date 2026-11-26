package com.chargescope.app

import android.os.Process
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Instantiated by Shizuku inside the privileged shell process (uid 2000).
 * Reads /sys/class/power_supply directly via File I/O (zero shell forks) and runs
 * `dumpsys battery` directly without spawning `sh` or `sed`.
 */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(script: String): String {
        val sb = StringBuilder(2048)
        sb.append("#uid=").append(Process.myUid()).append('\n')
        val base = File("/sys/class/power_supply")
        val dirs = try { base.listFiles() } catch (_: Exception) { null }
        if (dirs != null) {
            for (d in dirs) {
                sb.append("@@").append(d.name).append('\n')
                for (f in FIELDS) {
                    val v = readFirstLine(File(d, f))
                    if (v != null) sb.append(f).append('=').append(v).append('\n')
                }
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

    private fun readFirstLine(f: File): String? = try {
        if (!f.canRead()) null else f.bufferedReader().use { it.readLine()?.trim() }
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
        )
    }
}
