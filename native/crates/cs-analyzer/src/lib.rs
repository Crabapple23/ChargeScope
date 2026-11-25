//! cs-analyzer — orchestrates metrics + evidence + inference into one result.
//! Every public function is pure: same input yields same output.

use cs_core::{compute_metrics, ALGORITHM_VERSION};
use cs_device::has_sampling_capability;
use cs_model::{
    AnalysisBundle, AnalysisStatus, ChargingMetrics, ChargingSnapshot, DeviceProfile,
    ProtocolAnalysis, ProtocolEvidence,
};
use cs_protocol::{collect_evidence, decide_status, detect_conflicts, rank_candidates};

/// Build the human-readable explanation. Avoids any claim of certainty beyond
/// what `status` supports.
fn build_explanation(status: AnalysisStatus, primary: Option<&str>, evidence_count: usize) -> String {
    match status {
        AnalysisStatus::Confirmed => format!(
            "接口明确报告协议 {}（{} 项证据）。结论为直接识别，仍受该接口语义可信度约束。",
            primary.unwrap_or("未命名"), evidence_count
        ),
        AnalysisStatus::HighConfidence => format!(
            "多项独立证据（{} 项）一致指向 {}，属于强推断，非直接握手证明。",
            evidence_count,
            primary.unwrap_or("未知协议")
        ),
        AnalysisStatus::Possible => format!(
            "仅有兼容特征（{} 项），{:?} 仅为候选，无法确认当前协商。",
            evidence_count,
            primary.unwrap_or("无主候选")
        ),
        AnalysisStatus::Indeterminate => "现有证据在多个协议间无法区分，请补充输入侧实测或可验证的驱动节点。".into(),
        AnalysisStatus::Unknown => "数据不足以判断协议；这不表示设备不支持快充。".into(),
        AnalysisStatus::Unsupported => "当前没有可用的采集通道，无法进行任何分析。".into(),
    }
}

/// Limitations always shown alongside the result.
fn build_limitations(profile: Option<&DeviceProfile>, snapshots: &[ChargingSnapshot]) -> Vec<String> {
    let mut out = vec![
        "公开 Android API 无法读取 USB-C PD 完整 PDO/APDO 与协商报文。".to_string(),
        "功率峰值、温度与 SOC 斜率只能作为行为证据，不能确认具体协议。".to_string(),
        "证据权重仅用于排序，不是统计概率。".to_string(),
    ];

    let has_root = snapshots.iter().any(|s| s.root_available)
        || profile.map(|p| p.root_available).unwrap_or(false);
    if !has_root {
        out.push("Root 未启用：sysfs 输入侧与控制器节点不可用，推断能力受限。".to_string());
    }
    if profile.is_none() {
        out.push("无已验证的设备档案：L1 能力候选被跳过。".to_string());
    }
    let has_input = snapshots
        .iter()
        .any(|s| s.input_power_uw.is_some() || s.input_voltage_uv.is_some());
    if !has_input {
        out.push("未取得输入侧实测电气值；电池侧功率不等于 USB 输入功率。".to_string());
    }
    out
}

/// Full analysis. `now_ms` should be the caller's monotonic clock; it is passed
/// explicitly so the function stays deterministic and testable.
pub fn analyze(
    snapshots: &[ChargingSnapshot],
    profile: Option<&DeviceProfile>,
    now_ms: i64,
) -> AnalysisBundle {
    let mut snapshots = snapshots.to_vec();
    cs_core::fill_missing_battery_power(&mut snapshots);

    let metrics: ChargingMetrics = compute_metrics(&snapshots);
    let evidence: Vec<ProtocolEvidence> = collect_evidence(&snapshots, profile, &metrics);
    let candidates = rank_candidates(&evidence);
    let conflicts = detect_conflicts(&candidates);

    let any_public_reading = snapshots.iter().any(|s| s.has_any_measurement());
    let capability = has_sampling_capability(profile, any_public_reading);
    let has_data = !snapshots.is_empty() && (any_public_reading || !evidence.is_empty());

    let status = decide_status(&candidates, &conflicts, has_data, capability);
    let primary = candidates.first().map(|c| c.protocol);
    let explanation = build_explanation(
        status,
        primary.map(|p| p.display_name()),
        evidence.len(),
    );
    let limitations = build_limitations(profile, &snapshots);

    AnalysisBundle {
        metrics,
        analysis: ProtocolAnalysis {
            status,
            primary_candidate: primary,
            candidates,
            evidence,
            conflicts,
            explanation,
            limitations,
            analyzed_at_elapsed_ms: now_ms,
            algorithm_version: ALGORITHM_VERSION.to_string(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use cs_model::{DataSource, Side, SourceRecord};

    fn empty_snapshot(ts: i64) -> ChargingSnapshot {
        ChargingSnapshot {
            timestamp_elapsed_ms: ts,
            wall_clock_time_ms: ts,
            soc_percent: None,
            battery_voltage_uv: None,
            battery_current_ua: None,
            battery_power_uw: None,
            battery_temperature_decic: None,
            input_voltage_uv: None,
            input_current_ua: None,
            input_power_uw: None,
            usb_type: None,
            charge_type: None,
            charging_status: None,
            source_records: vec![],
            root_available: false,
        }
    }

    #[test]
    fn empty_input_is_unsupported() {
        let bundle = analyze(&[], None, 0);
        assert_eq!(bundle.analysis.status, AnalysisStatus::Unsupported);
        assert!(bundle.analysis.limitations.len() >= 3);
        assert_eq!(bundle.analysis.algorithm_version, ALGORITHM_VERSION);
    }

    #[test]
    fn battery_only_data_yields_unknown_or_possible_not_confirmed() {
        let mut s = empty_snapshot(0);
        s.soc_percent = Some(55);
        s.battery_voltage_uv = Some(3_900_000);
        s.battery_current_ua = Some(1_500_000);
        let bundle = analyze(&[s], None, 1_000);
        assert_ne!(bundle.analysis.status, AnalysisStatus::Confirmed);
        assert_eq!(bundle.metrics.avg_battery_power_uw, Some(5_850_000));
    }

    #[test]
    fn explicit_charge_type_can_confirm() {
        let mut s = empty_snapshot(0);
        s.soc_percent = Some(55);
        s.charge_type = Some("USB_PD_PPS".into());
        let bundle = analyze(&[s], None, 1_000);
        assert_eq!(bundle.analysis.status, AnalysisStatus::Confirmed);
        assert_eq!(
            bundle.analysis.primary_candidate,
            Some(cs_model::Protocol::PdPps)
        );
    }

    #[test]
    fn limit_node_does_not_become_input_power() {
        let mut s = empty_snapshot(0);
        s.soc_percent = Some(55);
        s.source_records.push(SourceRecord {
            field: "input_current_limit".into(),
            source: DataSource::Sysfs,
            source_path: Some("/sys/class/power_supply/usb/input_current_limit".into()),
            raw_value: Some("3000000".into()),
            normalized_value: Some("3000000".into()),
            unit: Some("uA".into()),
            side: Side::InputSide,
            quality: cs_model::DataQuality::Valid,
            timestamp_elapsed_ms: 0,
        });
        let bundle = analyze(&[s], None, 1_000);
        assert_eq!(bundle.analysis.status, AnalysisStatus::Unknown);
        assert_eq!(bundle.analysis.primary_candidate, None);
        assert!(bundle.analysis.limitations.iter().any(|l| l.contains("输入侧")));
    }

    #[test]
    fn conflicts_are_reported_not_silently_resolved() {
        let mut s = empty_snapshot(0);
        s.soc_percent = Some(50);
        s.charge_type = Some("USB_PD".into());
        s.source_records.push(SourceRecord {
            field: "charge_type".into(),
            source: DataSource::Sysfs,
            source_path: Some("/sys/class/power_supply/usb/charge_type".into()),
            raw_value: Some("QC3.0".into()),
            normalized_value: Some("QC3.0".into()),
            unit: None,
            side: Side::InputSide,
            quality: cs_model::DataQuality::Valid,
            timestamp_elapsed_ms: 0,
        });
        let bundle = analyze(&[s], None, 1_000);
        assert_eq!(bundle.analysis.status, AnalysisStatus::Indeterminate);
        assert!(!bundle.analysis.conflicts.is_empty());
    }
}
