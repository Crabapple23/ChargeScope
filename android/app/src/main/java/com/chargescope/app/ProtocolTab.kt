package com.chargescope.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal fun ProtocolTab() {
    val ui by Sampler.state.collectAsState()
    val a = ui.analysis
    if (a == null) { Text(tr("等待首次采样…", "Waiting for first sample…")); return }
    Section(tr("结论：${a.status.label}", "Conclusion: ${a.status.name}")) {
        Text(a.headline, style = MaterialTheme.typography.titleSmall)
        KV(tr("驱动报告协议", "Driver Protocol"), a.driver ?: tr("未报告", "None"))
        KV(tr("预测当前协议", "Predicted Current Protocol"), a.predicted ?: tr("无法预测", "Unknown"))
        a.protocolSpec?.let { spec ->
            Text(tr("协议规格说明（参考上限，不代表当前实际功率）", "Protocol Spec (Reference ceiling, not live power)"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(spec, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (a.description.isNotEmpty()) {
        Section(tr("描述", "Summary")) { Text(a.description) }
    }
    if (a.candidates.isNotEmpty()) {
        Section(tr("候选协议（仅缩小范围，★ 为与本机品牌一致）", "Candidates (★ matches device brand)")) { a.candidates.forEach { Text("• $it") } }
    }
    if (a.candidateSpecs.isNotEmpty()) {
        Fold(
            tr("候选协议档位说明（参考上限）", "Candidate Tier Specs (Reference)"),
            tr("${a.candidateSpecs.size} 条", "${a.candidateSpecs.size}"),
        ) {
            a.candidateSpecs.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    Fold(tr("证据（L4 驱动 > L3 输入 > L2 功率 > L1 品牌）", "Evidence (L4 > L3 > L2 > L1)"), tr("${a.evidence.size} 条", "${a.evidence.size}")) {
        if (a.evidence.isEmpty()) Text(tr("暂无证据", "No evidence")) else a.evidence.sortedByDescending { it.level }.forEach {
            Text("L${it.level}  ${it.text}")
        }
    }
    if (a.limitations.isNotEmpty()) {
        Fold(
            tr("限制说明", "Limitations"),
            tr("${a.limitations.size} 条", "${a.limitations.size}"),
        ) {
            a.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    ui.rootError?.let { Section(tr("特权通道状态", "Privileged Channel")) { Text(it) } }
}
