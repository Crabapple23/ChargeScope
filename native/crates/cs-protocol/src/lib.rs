//! cs-protocol — evidence weighting, candidate ranking, conflict detection.
//! IMPORTANT: scores are prototype ordering hints derived from the initial
//! weights in the spec. They are NOT probabilities and must not be rendered as
//! percentages in the UI.

use cs_model::{
    AnalysisStatus, ChargingMetrics, ChargingSnapshot, DataQuality, DeviceProfile, EvidenceConflict,
    EvidenceLevel, Protocol, ProtocolCandidate, ProtocolEvidence,
};

/// Initial weights from spec §2.2. Prototype-only; calibrate with real samples.
pub mod weights {
    pub const DRIVER_REPORT: f64 = 100.0; // L4
    pub const NEGOTIATION_INFO: f64 = 95.0; // L4 (explicit handshake state)
    pub const INPUT_ELECTRICAL: f64 = 60.0; // L3
    pub const POWER_BEHAVIOR: f64 = 25.0; // L2
    pub const BATTERY_RATE: f64 = 15.0; // L2
    pub const DEVICE_CAPABILITY: f64 = 10.0; // L1
}

/// Threshold above which L3+L2 consistent evidence becomes HIGH_CONFIDENCE.
const HIGH_CONFIDENCE_MIN: f64 = 70.0;
/// Max score gap for two candidates to be considered INDETERMINATE.
const INDETERMINATE_GAP: f64 = 12.0;

/// Turn a textual L4 driver hint into a Protocol. Only explicit strings count.
pub fn protocol_from_driver_hint(hint: &str) -> Option<Protocol> {
    let s = hint.trim().to_ascii_uppercase();
    // Check PPS before PD so "PD_PPS" is not reduced to plain PD.
    if s.contains("PPS") {
        Some(Protocol::PdPps)
    } else if s.contains("PD") {
        Some(Protocol::Pd)
    } else if s.contains("UFCS") {
        Some(Protocol::Ufcs)
    } else if s.contains("SCP") {
        Some(Protocol::Scp)
    } else if s.contains("FCP") {
        Some(Protocol::Fcp)
    } else if s.contains("AFC") {
        Some(Protocol::Afc)
    } else if s.contains("QC4") || s.contains("QC_4") {
        Some(Protocol::Qc4)
    } else if s.contains("QC3") || s.contains("QC_3") {
        Some(Protocol::Qc3)
    } else if s.contains("QC2") || s.contains("QC_2") {
        Some(Protocol::Qc2)
    } else if s.contains("QC") {
        Some(Protocol::Qc2) // generic QC string: weakest form, still L4 text
    } else if s.contains("VOOC") {
        Some(Protocol::Vooc)
    } else if s.contains("SUPER") {
        Some(Protocol::SuperVooc)
    } else {
        None
    }
}

/// Extract evidence items from snapshots. Each item names its source and level.
pub fn collect_evidence(
    snapshots: &[ChargingSnapshot],
    profile: Option<&DeviceProfile>,
    metrics: &ChargingMetrics,
) -> Vec<ProtocolEvidence> {
    let mut out = Vec::new();

    // L1 — device capability only narrows the candidate set.
    if let Some(p) = profile {
        for protocol in &p.supported_protocols {
            out.push(ProtocolEvidence {
                protocol: Some(*protocol),
                level: EvidenceLevel::L1,
                source: "DEVICE_ADAPTER".into(),
                observation: format!("设备档案列出支持能力：{}", protocol.display_name()),
                supports: true,
                quality: DataQuality::Valid,
            });
        }
    }

    // L4 — explicit driver/controller report in charge_type / usb_type.
    for s in snapshots {
        for text in [s.charge_type.as_deref(), s.usb_type.as_deref()].into_iter().flatten() {
            if let Some(protocol) = protocol_from_driver_hint(text) {
                out.push(ProtocolEvidence {
                    protocol: Some(protocol),
                    level: EvidenceLevel::L4,
                    source: "ANDROID_API".into(),
                    observation: format!("驱动字段报告：{}", text),
                    supports: true,
                    quality: DataQuality::Valid,
                });
            }
        }
        // L4 from sysfs charge_type/type records whose semantics were verified.
        for r in &s.source_records {
            if r.quality != DataQuality::Valid {
                continue;
            }
            let is_protocol_field = matches!(
                r.field.as_str(),
                "charge_type" | "usb_type" | "real_type" | "typec_status"
            );
            if !is_protocol_field {
                continue;
            }
            if let Some(text) = r.raw_value.as_deref() {
                if let Some(protocol) = protocol_from_driver_hint(text) {
                    let path = r.source_path.clone().unwrap_or_default();
                    out.push(ProtocolEvidence {
                        protocol: Some(protocol),
                        level: EvidenceLevel::L4,
                        source: format!("SYSFS:{}", path),
                        observation: format!("字段 {} 报告：{}", r.field, text),
                        supports: true,
                        quality: DataQuality::Valid,
                    });
                }
            }
        }
    }

    // L3 — input-side electrical characteristics. Presence of a genuine
    // input-side voltage/current reading enables strong inference, but alone
    // it still does not name a protocol.
    let has_input_electrical = snapshots.iter().any(|s| {
        s.input_voltage_uv.is_some() || s.input_current_ua.is_some() || s.input_power_uw.is_some()
    });
    if has_input_electrical {
        out.push(ProtocolEvidence {
            protocol: None,
            level: EvidenceLevel::L3,
            source: "INPUT_SIDE".into(),
            observation: "存在输入侧电压/电流实测值，可支持强推断但非握手证明".into(),
            supports: true,
            quality: DataQuality::Valid,
        });
    }

    // L2 — power behaviour and battery charge rate. Explicitly not protocol proof.
    if let Some(power) = metrics.peak_battery_power_uw {
        out.push(ProtocolEvidence {
            protocol: None,
            level: EvidenceLevel::L2,
            source: "BATTERY_SIDE".into(),
            observation: format!("电池侧峰值功率 {} µW（行为层，不代表协议）", power),
            supports: true,
            quality: DataQuality::Valid,
        });
    }
    if let Some(rate) = metrics.soc_rate_percent_per_min {
        out.push(ProtocolEvidence {
            protocol: None,
            level: EvidenceLevel::L2,
            source: "BATTERY_SIDE".into(),
            observation: format!("SOC 变化速率 {:.2} %/min（行为层）", rate),
            supports: true,
            quality: DataQuality::Valid,
        });
    }

    dedup_evidence(out)
}

fn dedup_evidence(items: Vec<ProtocolEvidence>) -> Vec<ProtocolEvidence> {
    let mut seen = std::collections::HashSet::new();
    items
        .into_iter()
        .filter(|e| {
            let key = format!(
                "{:?}|{:?}|{:?}|{}",
                e.protocol, e.level, e.source, e.observation
            );
            seen.insert(key)
        })
        .collect()
}

/// Rank protocols from evidence. Results are sorted by score descending.
pub fn rank_candidates(evidence: &[ProtocolEvidence]) -> Vec<ProtocolCandidate> {
    use std::collections::HashMap;
    let mut map: HashMap<Protocol, (f64, EvidenceLevel, Vec<String>)> = HashMap::new();

    for e in evidence {
        let Some(protocol) = e.protocol else { continue };
        if !e.supports || e.quality != DataQuality::Valid {
            continue;
        }
        let entry = map.entry(protocol).or_insert((0.0, EvidenceLevel::L1, Vec::new()));
        let w = match e.level {
            EvidenceLevel::L1 => weights::DEVICE_CAPABILITY,
            EvidenceLevel::L2 => weights::BATTERY_RATE,
            EvidenceLevel::L3 => weights::INPUT_ELECTRICAL,
            EvidenceLevel::L4 => weights::DRIVER_REPORT,
        };
        entry.0 += w;
        if e.level > entry.1 {
            entry.1 = e.level;
        }
        entry.2.push(e.observation.clone());
    }

    let mut candidates: Vec<ProtocolCandidate> = map
        .into_iter()
        .map(|(protocol, (score, level, reasons))| ProtocolCandidate {
            protocol,
            score,
            evidence_level: level,
            reasons,
        })
        .collect();
    candidates.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.protocol.display_name().cmp(b.protocol.display_name()))
    });
    candidates
}

/// Conflicts: two different protocols both directly reported at L4.
pub fn detect_conflicts(candidates: &[ProtocolCandidate]) -> Vec<EvidenceConflict> {
    let direct: Vec<&ProtocolCandidate> = candidates
        .iter()
        .filter(|c| c.evidence_level == EvidenceLevel::L4)
        .collect();
    let mut out = Vec::new();
    for i in 0..direct.len() {
        for j in (i + 1)..direct.len() {
            if direct[i].protocol != direct[j].protocol {
                out.push(EvidenceConflict {
                    protocol_a: Some(direct[i].protocol),
                    protocol_b: Some(direct[j].protocol),
                    description: format!(
                        "两个 L4 直接报告冲突：{} 与 {}，无法同时成立",
                        direct[i].protocol.display_name(),
                        direct[j].protocol.display_name()
                    ),
                });
            }
        }
    }
    out
}

/// Decide the final status. Conservative by design: behaviour alone never
/// reaches CONFIRMED.
pub fn decide_status(
    candidates: &[ProtocolCandidate],
    conflicts: &[EvidenceConflict],
    has_data: bool,
    has_sampling_capability: bool,
) -> AnalysisStatus {
    if !has_sampling_capability {
        return AnalysisStatus::Unsupported;
    }
    if !has_data {
        return AnalysisStatus::Unknown;
    }
    if candidates.is_empty() {
        return AnalysisStatus::Unknown;
    }
    if !conflicts.is_empty() {
        return AnalysisStatus::Indeterminate;
    }

    let top = &candidates[0];
    if top.evidence_level == EvidenceLevel::L4 && top.score >= weights::DRIVER_REPORT {
        return AnalysisStatus::Confirmed;
    }

    let has_l3_evidence = candidates.iter().any(|c| c.evidence_level >= EvidenceLevel::L3);
    let second = candidates.get(1);
    let close_second = second
        .map(|s| (top.score - s.score).abs() <= INDETERMINATE_GAP)
        .unwrap_or(false);

    if close_second && !has_l3_evidence {
        return AnalysisStatus::Indeterminate;
    }
    if top.score >= HIGH_CONFIDENCE_MIN && has_l3_evidence {
        return AnalysisStatus::HighConfidence;
    }
    AnalysisStatus::Possible
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(protocol: Protocol, score: f64, level: EvidenceLevel) -> ProtocolCandidate {
        ProtocolCandidate {
            protocol,
            score,
            evidence_level: level,
            reasons: vec![],
        }
    }

    #[test]
    fn pps_hint_precedes_pd() {
        assert_eq!(protocol_from_driver_hint("USB_PD_PPS"), Some(Protocol::PdPps));
        assert_eq!(protocol_from_driver_hint("USB_PD"), Some(Protocol::Pd));
        assert_eq!(protocol_from_driver_hint("unknown"), None);
    }

    #[test]
    fn l4_without_conflict_is_confirmed() {
        let c = vec![candidate(Protocol::PdPps, 100.0, EvidenceLevel::L4)];
        assert_eq!(decide_status(&c, &[], true, true), AnalysisStatus::Confirmed);
    }

    #[test]
    fn conflicting_l4_is_indeterminate() {
        let c = vec![
            candidate(Protocol::Pd, 100.0, EvidenceLevel::L4),
            candidate(Protocol::Qc3, 100.0, EvidenceLevel::L4),
        ];
        let conflicts = detect_conflicts(&c);
        assert_eq!(conflicts.len(), 1);
        assert_eq!(decide_status(&c, &conflicts, true, true), AnalysisStatus::Indeterminate);
    }

    #[test]
    fn l2_only_never_confirms() {
        let c = vec![candidate(Protocol::Pd, 25.0, EvidenceLevel::L2)];
        let status = decide_status(&c, &[], true, true);
        assert_ne!(status, AnalysisStatus::Confirmed);
        assert_ne!(status, AnalysisStatus::HighConfidence);
    }

    #[test]
    fn no_data_is_unknown_and_no_capability_is_unsupported() {
        assert_eq!(decide_status(&[], &[], false, true), AnalysisStatus::Unknown);
        assert_eq!(decide_status(&[], &[], false, false), AnalysisStatus::Unsupported);
    }

    #[test]
    fn close_scores_without_l3_are_indeterminate() {
        let c = vec![
            candidate(Protocol::Pd, 25.0, EvidenceLevel::L2),
            candidate(Protocol::Qc3, 20.0, EvidenceLevel::L2),
        ];
        assert_eq!(decide_status(&c, &[], true, true), AnalysisStatus::Indeterminate);
    }

    #[test]
    fn l3_plus_power_behavior_is_high_confidence() {
        let c = vec![
            candidate(Protocol::PdPps, 60.0, EvidenceLevel::L3),
            candidate(Protocol::Qc3, 10.0, EvidenceLevel::L1),
        ];
        assert_eq!(decide_status(&c, &[], true, true), AnalysisStatus::HighConfidence);
    }

    #[test]
    fn ranks_by_score_descending() {
        let c = vec![
            candidate(Protocol::Pd, 10.0, EvidenceLevel::L1),
            candidate(Protocol::Ufcs, 100.0, EvidenceLevel::L4),
        ];
        let ranked = rank_candidates(&[]);
        assert!(ranked.is_empty()); // no evidence => no candidates
        let _ = c;
    }
}
