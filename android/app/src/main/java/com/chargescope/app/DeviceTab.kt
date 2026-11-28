package com.chargescope.app

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private fun isNodeOffline(n: RootNode): Boolean =
    n.props["online"] == "0" || n.props["present"] == "0"

private fun nodePriority(n: RootNode): Int {
    if (isNodeOffline(n)) return 8
    val name = n.name.lowercase()
    return when {
        name == "usb" -> 0
        name == "battery" -> 1
        name == "bms" -> 2
        name == "typec" -> 3
        name == "health" -> 4
        name == "dumpsys" -> 5
        name.startsWith("qcom") || name.startsWith("xm_") || name.startsWith("oplus") ||
            name.startsWith("bq") || name.startsWith("sc") || name.startsWith("ucsi") -> 6
        else -> 7
    }
}

private fun nodeSummary(n: RootNode, isEn: Boolean): String {
    val cnt = if (isEn) "${n.props.size}" else "${n.props.size} 项"
    if (isNodeOffline(n)) return if (isEn) "Offline · $cnt" else "离线 · $cnt"
    val tag = when (n.name.lowercase()) {
        "usb" -> n.props["real_type"] ?: n.props["type"]
        "battery", "bms" -> n.props["capacity"]?.let { "$it%" } ?: n.props["status"]
        "health" -> n.props["soh"]?.let { "SOH $it%" }
            ?: n.props["cycle_count"]?.let { if (isEn) "$it cyc" else "${it}次" }
        "dumpsys" -> n.props["max_charging_voltage"]?.toLongOrNull()?.let { "%.1fV".format(it / 1e6) }
        else -> n.props["type"]?.takeIf { it != "Unknown" }
    }
    return if (!tag.isNullOrBlank()) "$tag · $cnt" else cnt
}

@Composable
internal fun DeviceTab(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val isEn = LocalIsEn.current
    val ui by Sampler.state.collectAsState()
    val s = ui.latest
    val screenInfo = remember(ctx) {
        val dm = ctx.resources.displayMetrics
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            dm.widthPixels to dm.heightPixels
        }
        val hz = if (Build.VERSION.SDK_INT >= 30) {
            try { ctx.display?.refreshRate } catch (_: Exception) { null }
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay?.refreshRate
        }
        "${w}×${h} · ${dm.densityDpi} dpi" + (hz?.let { " · %.0f Hz".format(it) } ?: "")
    }
    val kernelInfo = remember {
        val ver = System.getProperty("os.version")
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        when {
            !ver.isNullOrBlank() && abi != null -> "$ver ($abi)"
            !ver.isNullOrBlank() -> ver
            else -> null
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(tr("设备", "Device"), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = tr("软件设置", "App Settings"),
            )
        }
    }
    Section(tr("设备信息", "Device Info")) {
        KV(tr("品牌", "Brand"), if (Build.BRAND.equals(Build.MANUFACTURER, true)) Build.BRAND else "${Build.BRAND} (${Build.MANUFACTURER})")
        KV(tr("型号", "Model"), Build.MODEL)
        KV("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        KV("SoC", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
        KV(tr("屏幕", "Display"), screenInfo)
        KV(tr("内核", "Kernel"), kernelInfo)
    }

    val deepScanning by Sampler.deepScanning.collectAsState()
    val deepScan by Sampler.deepScan.collectAsState()
    val nodes = s?.rootNodes.orEmpty()
    val sortedNodes = remember(nodes) {
        nodes.sortedWith(compareBy<RootNode> { nodePriority(it) }.thenBy { it.name.lowercase() })
    }
    val totalProps = remember(nodes) { nodes.sumOf { it.props.size } }

    Section(tr("特权通道与深度扫描（只读）", "Privileged Channel & Deep Scan")) {
        if (nodes.isEmpty()) {
            Text(
                ui.rootError ?: tr("未启用（在“数据”页开启 Root 或 Shizuku）", "Disabled (enable Root or Shizuku in Data tab)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            KV(
                tr("当前已读节点", "Active Nodes"),
                tr("${nodes.size} 个节点 · $totalProps 项属性", "${nodes.size} nodes · $totalProps fields"),
            )
        }
        OutlinedButton(
            enabled = !deepScanning,
            onClick = { Sampler.startDeepScan(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (deepScanning) tr("深度扫描中…", "Deep Scanning…") else tr("一键深度扫描（全量充电节点）", "Deep Scan All Charging Nodes"))
        }
        Text(
            tr(
                "扫描本机可读的 power_supply / Type-C / 厂商快充节点，以及 Health / USB / batterystats / 充电属性，结果会自动附带在“导出诊断日志”中。",
                "Scans readable power_supply, Type-C, and vendor nodes, plus Health / USB / batterystats / charging properties. Included in exported diagnostic logs.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    deepScan?.let { report ->
        Fold(tr("深度扫描结果", "Deep Scan Output"), tr("${report.lines().size} 行", "${report.lines().size} lines")) {
            Text(report.take(4000), style = MaterialTheme.typography.bodySmall)
            if (report.length > 4000) {
                Text(
                    tr("（已截断，完整内容请点“数据 → 导出诊断日志”查看）", "(Truncated; export diagnostic log for full output)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (sortedNodes.isNotEmpty()) {
        Text(
            tr("已读节点明细（点击展开）", "Node Details (Tap to Expand)"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
        sortedNodes.forEach { n ->
            key(n.name) {
                Fold(title = n.name, summary = nodeSummary(n, isEn)) {
                    n.props.forEach { (k, v) ->
                        KV(k, v)
                    }
                }
            }
        }
    }
}
