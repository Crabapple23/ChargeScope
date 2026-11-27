package com.chargescope.app

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource

@Composable
internal fun DeviceTab(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
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
    Section(tr("特权通道读取的节点（只读）", "Privileged Nodes (Read-only)")) {
        val nodes = s?.rootNodes.orEmpty()
        if (nodes.isEmpty()) Text(ui.rootError ?: tr("未启用（在“数据”页开启 Root 或 Shizuku）", "Disabled (enable Root or Shizuku in Data tab)"))
        nodes.forEach { n ->
            Text(n.name, style = MaterialTheme.typography.titleSmall)
            Text(n.props.entries.joinToString("  ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            enabled = !deepScanning,
            onClick = { Sampler.startDeepScan(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (deepScanning) tr("深度扫描中…", "Deep Scanning…") else tr("一键深度扫描（全量充电节点）", "Deep Scan All Charging Nodes"))
        }
        Text(
            tr("扫描本机全部可读的 power_supply / Type-C / 厂商快充节点，结果会自动附带在“导出诊断日志”中。", "Scans all readable power_supply, Type-C, and vendor nodes. Included in exported diagnostic logs."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    deepScan?.let { report ->
        Fold(tr("深度扫描结果", "Deep Scan Output"), tr("${report.lines().size} 行", "${report.lines().size} lines")) {
            Text(report.take(4000), style = MaterialTheme.typography.bodySmall)
            if (report.length > 4000) {
                Text(tr("（已截断，完整内容请点“数据 → 导出诊断日志”查看）", "(Truncated; export diagnostic log for full output)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
