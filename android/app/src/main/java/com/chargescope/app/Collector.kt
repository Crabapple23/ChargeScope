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
 * Optional root, strictly read-only: one `su` process runs a fixed script that only reads
 * /sys/class/power_supply/<node>/<field>. Nothing is written; no user input reaches the shell.
 */
object RootScanner {
    class Result(val nodes: List<RootNode>, val error: String?)

    private const val FIELDS =
        "type status online present capacity voltage_now current_now current_avg power_now " +
            "voltage_max current_max usb_type real_type charge_type temp input_current_limit " +
            "constant_charge_current charge_counter"

    private val SCRIPT = "for d in /sys/class/power_supply/*; do " +
        "echo \"@@\${d##*/}\"; " +
        "for f in $FIELDS; do v=; { read -r v < \"\$d/\$f\"; } 2>/dev/null && echo \"\$f=\$v\"; done; " +
        "done"

    suspend fun scan(): Result = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", SCRIPT).redirectErrorStream(false).start()
            p.outputStream.close()
            coroutineScope {
                val out = async { p.inputStream.bufferedReader().use { it.readText() } }
                val err = async { p.errorStream.bufferedReader().use { it.readText() } }
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return@coroutineScope Result(emptyList(), "su 执行超时（可能在等待 Root 授权）")
                }
                val text = out.await()
                val nodes = parse(text)
                if (nodes.isEmpty()) {
                    Result(emptyList(), "Root 读取无结果：" + err.await().trim().take(100))
                } else Result(nodes, null)
            }
        } catch (e: Exception) {
            Result(emptyList(), "无法执行 su：" + (e.message ?: e.javaClass.simpleName))
        }
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
                if (idx > 0) props[line.substring(0, idx)] = line.substring(idx + 1).trim()
            }
        }
        flush()
        return nodes
    }
}
