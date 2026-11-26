package com.chargescope.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Collector {
    /** Unprivileged battery data from public Android APIs. Cheap: sticky broadcast + a few binder calls. */
    fun battery(ctx: Context): Snapshot {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
 * Privileged read-only channel shared by root (Magisk / KernelSU / APatch via `su`) and Shizuku.
 * One fixed script is executed; it only reads /sys/class/power_supply/<node>/<field> and
 * `dumpsys battery`. Nothing is written and no user input reaches the shell.
 */
object RootScanner {
    class Result(val nodes: List<RootNode>, val error: String?)

    private const val FIELDS =
        "type status online present capacity voltage_now current_now current_avg power_now " +
            "voltage_max current_max usb_type real_type charge_type temp input_current_limit " +
            "constant_charge_current charge_counter"

    internal val SCRIPT = "echo \"#uid=\$(id -u)\"; " +
        "for d in /sys/class/power_supply/*; do " +
        "echo \"@@\${d##*/}\"; " +
        "for f in $FIELDS; do v=; { read -r v < \"\$d/\$f\"; } 2>/dev/null && echo \"\$f=\$v\"; done; " +
        "done; " +
        "echo \"@@dumpsys\"; dumpsys battery 2>/dev/null | sed 's/^ *//; s/: /=/'"

    suspend fun scan(timeoutSec: Long = 4): Result = withContext(Dispatchers.IO) {
        try {
            // Works for Magisk, KernelSU (Next/Suki) and APatch: all expose a standard `su`.
            // The script goes through stdin (not `-c`), which every su variant accepts.
            val p = ProcessBuilder("su").redirectErrorStream(false).start()
            p.outputStream.bufferedWriter().use {
                it.write(SCRIPT + "\n")
                it.write("exit\n")
            }
            coroutineScope {
                val out = async { p.inputStream.bufferedReader().use { it.readText() } }
                val err = async { p.errorStream.bufferedReader().use { it.readText() } }
                if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return@coroutineScope Result(emptyList(), "su 等待超时（请在 Root 管理器弹窗中授权后重试）")
                }
                val text = out.await()
                val r = interpret(text, setOf("0"))
                if (r.error != null && !text.contains("#uid=")) {
                    val why = err.await().trim().take(100)
                    Result(emptyList(), "su 被拒绝或未授权" + (if (why.isEmpty()) "" else "：$why"))
                } else r
            }
        } catch (e: Exception) {
            Result(emptyList(), "未检测到可用的 su（未 Root，或未在 Root 管理器中授权）")
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
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase().replace(' ', '_')
                    props[k] = line.substring(idx + 1).trim()
                }
            }
        }
        flush()
        return nodes
    }
}
