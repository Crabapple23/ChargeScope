package com.chargescope.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Collector {
    private val BATTERY_FILTER = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    /** Unprivileged battery data from public Android APIs. Cheap: sticky broadcast + a few binder calls. */
    fun battery(ctx: Context): Snapshot {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val i = ctx.registerReceiver(null, BATTERY_FILTER)
            ?: return Snapshot(elapsed, wall, null, null, 0, null, null, null, null, null, null)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100L / scale).toInt().takeIf { it in 0..100 } else null
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it in 1..10_000 }
        val temp = if (i.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).takeIf { it in -500..1500 }
        } else null

        fun prop(id: Int, zeroIsMissing: Boolean): Long? {
            val v = bm.getLongProperty(id)
            return if (v == Long.MIN_VALUE || (zeroIsMissing && v == 0L)) null else v
        }

        return Snapshot(
            elapsedMs = elapsed,
            wallMs = wall,
            levelPct = pct,
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1).takeIf { it > 0 },
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1).takeIf { it > 0 },
            voltageMv = mv,
            tempDeci = temp,
            currentUa = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, false),
            currentAvgUa = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, false),
            chargeCounterUah = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, true),
        )
    }
}

/**
 * Privileged read-only channel for root (Magisk / KernelSU / APatch via persistent `su`).
 * Keeps one interactive `su` session open while root mode is enabled to avoid repeated
 * `su` forks and root-daemon IPC. Executes only fixed read-only scripts; nothing is written.
 */
object RootScanner {
    class Result(val nodes: List<RootNode>, val error: String?)

    private const val END = "@@CS_END@@"
    private const val FIELDS =
        "type status online present capacity voltage_now current_now current_avg power_now " +
            "voltage_max current_max usb_type real_type charge_type temp input_current_limit " +
            "constant_charge_current charge_counter charge_full charge_full_design soh cycle_count " +
            "quick_charge_type pd_active pd_verifed pd_verified pd_authenticated apdo_max " +
            "tx_adapter adapter_type fastcharge_mode pd_type pe_type " +
            "vbus_now ibus_now sc_bus_voltage sc_bus_current ti_bus_voltage ti_bus_current bus_voltage bus_current"

    private const val VENDOR_FIELDS =
        "quick_charge_type pd_active pd_verifed pd_verified pd_authenticated apdo_max power_max " +
            "adapter_svid tx_adapter adapter_type real_type soh cycle_count vbus_now ibus_now " +
            "bq2597x_bus_voltage bq2597x_bus_current fastchg_type voocchg_ing"

    // Fixed read-only script: power_supply + Type-C/PD stack + vendor dirs + dumpsys battery.
    private val SCRIPT = "echo \"#uid=\$(id -u)\"; " +
        "for d in /sys/class/power_supply/*; do " +
        "[ -e \"\$d\" ] || continue; " +
        "echo \"@@\${d##*/}\"; " +
        "for f in $FIELDS; do v=; { read -r v < \"\$d/\$f\"; } 2>/dev/null && [ -n \"\$v\" ] && echo \"\$f=\$v\"; done; " +
        "done; " +
        "for vd in /sys/class/qcom-battery /sys/class/xm_power /sys/class/oplus_chg; do " +
        "[ -d \"\$vd\" ] || continue; " +
        "echo \"@@\${vd##*/}\"; " +
        "for f in $VENDOR_FIELDS; do v=; { read -r v < \"\$vd/\$f\"; } 2>/dev/null && [ -n \"\$v\" ] && echo \"\$f=\$v\"; done; " +
        "done; " +
        "if [ -d /sys/class/typec/port0 ]; then " +
        "echo \"@@typec\"; " +
        "for f in power_role data_role usb_power_delivery_revision usb_typec_revision; do " +
        "v=; { read -r v < \"/sys/class/typec/port0/\$f\"; } 2>/dev/null && [ -n \"\$v\" ] && echo \"\$f=\$v\"; done; " +
        "for f in supports_usb_power_delivery usb_power_delivery_revision; do " +
        "v=; { read -r v < \"/sys/class/typec/port0-partner/\$f\"; } 2>/dev/null && [ -n \"\$v\" ] && echo \"partner_\$f=\$v\"; done; " +
        "for f in plug_type usb_power_delivery_revision; do " +
        "v=; { read -r v < \"/sys/class/typec/port0-cable/\$f\"; } 2>/dev/null && [ -n \"\$v\" ] && echo \"cable_\$f=\$v\"; done; " +
        "caps=; for c in /sys/class/typec/port0-partner/usb_power_delivery/source-capabilities/*; do " +
        "[ -d \"\$c\" ] || continue; " +
        "mv=; { read -r mv < \"\$c/voltage\"; } 2>/dev/null; " +
        "mxv=; { read -r mxv < \"\$c/maximum_voltage\"; } 2>/dev/null; " +
        "mnv=; { read -r mnv < \"\$c/minimum_voltage\"; } 2>/dev/null; " +
        "mxi=; { read -r mxi < \"\$c/maximum_current\"; } 2>/dev/null; " +
        "if [ -n \"\$mv\" ] && [ -n \"\$mxi\" ]; then caps=\"\$caps \${mv}mV/\${mxi}mA\"; " +
        "elif [ -n \"\$mnv\" ] && [ -n \"\$mxv\" ] && [ -n \"\$mxi\" ]; then caps=\"\$caps \${mnv}-\${mxv}mV/\${mxi}mA(PPS)\"; fi; " +
        "done; [ -n \"\$caps\" ] && echo \"source_caps=\$caps\"; " +
        "fi; " +
        "echo \"@@dumpsys\"; dumpsys battery 2>/dev/null; " +
        "echo $END\n"

    // On-demand read-only deep probe: enumerates all readable small sysfs files in charging dirs.
    private val DEEP_PROBE_SCRIPT = "echo \"#uid=\$(id -u)\"; " +
        "for root in /sys/class/power_supply/* /sys/class/typec/* /sys/class/qcom-battery /sys/class/xm_power /sys/class/oplus_chg; do " +
        "[ -d \"\$root\" ] || continue; " +
        "echo \"=== \$root ===\"; " +
        "for f in \"\$root\"/*; do " +
        "[ -f \"\$f\" ] && [ -r \"\$f\" ] || continue; " +
        "bn=\${f##*/}; " +
        "case \"\$bn\" in uevent|modalias|waiting_for_supplier|wakeup*) continue;; esac; " +
        "v=; { read -r v < \"\$f\"; } 2>/dev/null && [ -n \"\$v\" ] && echo \"\$bn=\$v\"; " +
        "done; " +
        "done; " +
        "echo $END\n"

    private val lock = Any()
    private val readerExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cs-root").apply { isDaemon = true }
    }
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Closes the persistent `su` session (e.g. when root mode is turned off or service stops). */
    fun close() {
        synchronized(lock) { destroySession() }
    }

    private fun destroySession() {
        val p = proc
        proc = null
        writer = null
        reader = null
        if (p != null) {
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
    }

    private fun ensureSession(): Boolean {
        val cur = proc
        if (cur != null && cur.isAlive) return true
        destroySession()
        val p = ProcessBuilder("su").redirectErrorStream(false).start()
        proc = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        reader = BufferedReader(InputStreamReader(p.inputStream))
        return true
    }

    private fun runScriptLocked(script: String, timeoutSec: Long): Pair<String?, String?> {
        try {
            ensureSession()
            val p = proc
            val w = writer
            val r = reader
            if (p == null || w == null || r == null) return null to "su 通道不可用"

            w.write(script)
            w.flush()

            val future = readerExec.submit(Callable<String?> {
                val sb = StringBuilder(4096)
                var out: String? = null
                while (true) {
                    val line = r.readLine() ?: break
                    if (line == END) {
                        out = sb.toString()
                        break
                    }
                    sb.append(line).append('\n')
                }
                out
            })

            val text = try {
                future.get(timeoutSec, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                destroySession()
                return null to "su 等待超时（请在 Root 管理器弹窗中授权后重试）"
            }

            if (text == null) {
                val errText = try { p.errorStream.bufferedReader().use { it.readText().trim().take(100) } } catch (_: Exception) { "" }
                destroySession()
                return null to ("su 被拒绝或未授权" + (if (errText.isEmpty()) "" else "：$errText"))
            }
            return text to null
        } catch (_: Exception) {
            destroySession()
            return null to "未检测到可用的 su（未 Root，或未在 Root 管理器中授权）"
        }
    }

    suspend fun scan(timeoutSec: Long = 4): Result = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val (text, err) = runScriptLocked(SCRIPT, timeoutSec)
            if (err != null || text == null) {
                Result(emptyList(), err ?: "Root 读取失败")
            } else {
                val res = interpret(text, setOf("0"))
                if (res.error != null && !text.contains("#uid=")) destroySession()
                res
            }
        }
    }

    /** On-demand deep scan of all readable charging/Type-C/vendor sysfs files. */
    suspend fun deepProbe(timeoutSec: Long = 10): Pair<String?, String?> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val (text, err) = runScriptLocked(DEEP_PROBE_SCRIPT, timeoutSec)
            if (err != null || text == null) null to (err ?: "深度扫描失败")
            else text.replace("\u0000", "") to null
        }
    }

    /** Validates the uid reported by the script and parses its output. */
    internal fun interpret(text: String, okUids: Set<String>): Result {
        val uid = text.lineSequence().firstOrNull { it.startsWith("#uid=") }?.substring(5)?.trim()
        if (uid != null && uid !in okUids) return Result(emptyList(), "未获得所需权限（uid=$uid）")
        val nodes = parse(text)
        return if (nodes.isEmpty()) Result(emptyList(), "未读到任何电源节点") else Result(nodes, null)
    }

    enum class Provider(val label: String) {
        MAGISK("Magisk"), KERNELSU("KernelSU"), APATCH("APatch")
    }

    private val PACKAGES = mapOf(
        "com.topjohnwu.magisk" to Provider.MAGISK,
        "io.github.huskydg.magisk" to Provider.MAGISK,
        "io.github.vvb2060.magisk" to Provider.MAGISK,
        "me.weishu.kernelsu" to Provider.KERNELSU,
        "com.rifsxd.ksunext" to Provider.KERNELSU,
        "io.github.sukisu.ultra" to Provider.KERNELSU,
        "me.bmax.apatch" to Provider.APATCH,
    )

    /** Which root manager app is installed (informational only; the call path is the same). */
    fun detectProvider(ctx: Context): Provider? {
        val pm = ctx.packageManager
        for ((pkg, prov) in PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return prov
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal fun parse(text: String): List<RootNode> {
        val nodes = ArrayList<RootNode>()
        var name: String? = null
        var props = LinkedHashMap<String, String>()
        fun flush() {
            val n = name
            if (n != null && props.isNotEmpty()) nodes += RootNode(n, props)
        }
        for (line in text.lineSequence()) {
            if (line.startsWith("@@")) {
                flush()
                name = line.substring(2).trim()
                props = LinkedHashMap()
            } else {
                // Support both `key=value` (sysfs) and `key: value` (raw `dumpsys battery`).
                val eq = line.indexOf('=')
                val sep = if (eq > 0) eq else if (name == "dumpsys") line.indexOf(':') else -1
                if (sep > 0) {
                    val k = line.substring(0, sep).trim().lowercase().replace(' ', '_')
                    val v = line.substring(sep + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) props[k] = v
                }
            }
        }
        flush()
        return nodes
    }
}
