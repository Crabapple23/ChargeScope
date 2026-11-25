//! Cross-crate integration smoke test.
//! Verifies the flow used by the Android app: normalise raw sysfs strings,
/// build snapshots, compute metrics, analyze, and serialise the result.

use cs_analyzer::analyze;
use cs_collector::normalize_reading;
use cs_core::compute_metrics;
use cs_model::{ChargingSnapshot, DataSource, DataQuality, Side};
use cs_protocol::protocol_from_driver_hint;

fn snapshot(ts: i64, soc: Option<i32>, v: Option<i64>, i: Option<i64>, charge_type: Option<&str>) -> ChargingSnapshot {
    ChargingSnapshot {
        timestamp_elapsed_ms: ts,
        wall_clock_time_ms: ts + 1_700_000_000_000,
        soc_percent: soc,
        battery_voltage_uv: v,
        battery_current_ua: i,
        battery_power_uw: None,
        battery_temperature_decic: Some(310),
        input_voltage_uv: None,
        input_current_ua: None,
        input_power_uw: None,
        usb_type: None,
        charge_type: charge_type.map(|s| s.to_string()),
        charging_status: Some("Charging".into()),
        source_records: vec![],
        root_available: false,
    }
}

#[test]
fn full_pipeline_without_root_stays_usable() {
    let samples = vec![
        snapshot(0, Some(50), Some(3_850_000), Some(1_800_000), None),
        snapshot(3_000, Some(51), Some(3_900_000), Some(1_900_000), None),
    ];
    let metrics = compute_metrics(&samples);
    assert_eq!(metrics.sample_count, 2);
    assert!(metrics.avg_battery_power_uw.is_some());

    let bundle = analyze(&samples, None, 6_000);
    assert!(bundle.analysis.limitations.iter().any(|l| l.contains("Root")));
    assert_eq!(bundle.analysis.algorithm_version, "cs-core/0.1.0");
    let json = serde_json::to_string(&bundle.analysis).expect("serialisable");
    assert!(json.contains("status"));
    assert!(json.contains("algorithm_version"));
}

#[test]
fn normalised_sysfs_records_flow_into_analysis() {
    let record = normalize_reading(
        "charge_type",
        Some("USB_PD".to_string()),
        "/sys/class/power_supply/usb/charge_type".into(),
        DataSource::Sysfs,
        1,
    );
    assert_eq!(record.quality, DataQuality::Valid);
    assert_eq!(record.side, Side::InputSide);
    assert_eq!(protocol_from_driver_hint("USB_PD"), Some(cs_model::Protocol::Pd));

    let mut s = snapshot(0, Some(60), Some(4_000_000), Some(2_000_000), None);
    s.source_records.push(record);
    let bundle = analyze(&[s], None, 1_000);
    assert_eq!(bundle.analysis.status, cs_model::AnalysisStatus::Confirmed);
}
