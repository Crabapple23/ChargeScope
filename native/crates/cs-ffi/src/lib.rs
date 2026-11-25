//! cs-ffi — UniFFI surface exposed to Kotlin.
//! Keep this layer thin: type conversion + delegation only. No business logic.

use cs_analyzer::analyze as analyze_internal;
use cs_collector::normalize_reading;
use cs_core::{compute_metrics as compute_metrics_internal, ALGORITHM_VERSION};
use cs_model::{
    AnalysisBundle, ChargingMetrics, ChargingSnapshot, DataSource, DeviceProfile, Side,
    SourceRecord,
};

uniffi::setup_scaffolding!();

/// Version string reported in UI and exported diagnostics.
#[uniffi::export]
pub fn algorithm_version() -> String {
    ALGORITHM_VERSION.to_string()
}

/// Normalise one raw sysfs reading into a provenance-carrying record.
#[uniffi::export]
pub fn normalize_source_record(
    raw: String,
    field: String,
    source_path: String,
    side: Side,
    timestamp_elapsed_ms: i64,
) -> SourceRecord {
    let mut record = normalize_reading(
        &field,
        Some(raw),
        source_path,
        DataSource::Sysfs,
        timestamp_elapsed_ms,
    );
    // Only override when a verified device profile established the side.
    // UnknownSide means "keep Rust's field-derived default".
    if side != Side::UnknownSide {
        record.side = side;
    }
    record
}

/// Aggregate metrics over a window of snapshots.
#[uniffi::export]
pub fn compute_metrics(snapshots: Vec<ChargingSnapshot>) -> ChargingMetrics {
    compute_metrics_internal(&snapshots)
}

/// Full evidence-based analysis. Returns metrics + status + candidates +
/// evidence + conflicts + limitations in one call.
#[uniffi::export]
pub fn analyze(
    snapshots: Vec<ChargingSnapshot>,
    profile: Option<DeviceProfile>,
    now_ms: i64,
) -> AnalysisBundle {
    analyze_internal(&snapshots, profile.as_ref(), now_ms)
}

#[cfg(test)]
mod tests {
    use super::*;
    use cs_model::DataQuality;

    #[test]
    fn ffi_exports_are_callable() {
        assert!(algorithm_version().starts_with("cs-core/"));
        let record = normalize_source_record(
            "4000000".into(),
            "voltage_now".into(),
            "/sys/class/power_supply/battery/voltage_now".into(),
            Side::BatterySide,
            1,
        );
        assert_eq!(record.quality, DataQuality::Valid);
        assert_eq!(record.unit.as_deref(), Some("uV"));

        let empty: Vec<ChargingSnapshot> = vec![];
        let metrics = compute_metrics(empty.clone());
        assert_eq!(metrics.sample_count, 0);
        let bundle = analyze(empty, None, 9);
        assert_eq!(bundle.analysis.analyzed_at_elapsed_ms, 9);
    }
}
