//! cs-core — pure computation: unit conversion, power, rate, stale detection.
//! No Android dependencies. Every function is deterministic and unit-tested.

use cs_model::{ChargingMetrics, ChargingSnapshot, DataQuality, SourceRecord};

/// Algorithm version reported to UI and exported JSON. Bump on any rule change.
pub const ALGORITHM_VERSION: &str = "cs-core/0.1.0";

/// Reasonable physical bounds used to flag implausible readings.
/// These are quality gates, not protocol evidence.
pub mod bounds {
    pub const SOC_MAX: i32 = 100;
    pub const SOC_MIN: i32 = 0;
    pub const VOLTAGE_UV_MIN: i64 = 2_000_000; // 2.0 V
    pub const VOLTAGE_UV_MAX: i64 = 22_000_000; // 22 V (covers 20V PD)
    pub const CURRENT_UA_ABS_MAX: i64 = 15_000_000; // 15 A
    pub const POWER_UW_MAX: i64 = 300_000_000; // 300 W
    pub const TEMP_DECIC_MIN: i32 = -400; // -40 °C
    pub const TEMP_DECIC_MAX: i32 = 800; // 80 °C
}

/// Battery-side power in µW. Only computed when both operands exist and pass
/// bounds. Direction/sign must already be calibrated by the collector; this
/// function does not guess.
pub fn battery_power_uw(voltage_uv: Option<i64>, current_ua: Option<i64>) -> Option<i64> {
    let v = voltage_uv?;
    let i = current_ua?;
    if !(bounds::VOLTAGE_UV_MIN..=bounds::VOLTAGE_UV_MAX).contains(&v) {
        return None;
    }
    if i.abs() > bounds::CURRENT_UA_ABS_MAX {
        return None;
    }
    let power = (v as i128 * i as i128) / 1_000_000_i128;
    if power <= 0 || power > bounds::POWER_UW_MAX as i128 {
        return None;
    }
    Some(power as i64)
}

/// SOC change rate in %/min using a monotonic clock. Returns None on
/// non-positive dt, time rollback, out-of-range SOC or implausible rate.
pub fn soc_rate_percent_per_min(
    soc1: Option<i32>,
    t1_ms: i64,
    soc2: Option<i32>,
    t2_ms: i64,
) -> Option<f64> {
    let s1 = soc1?;
    let s2 = soc2?;
    let dt = t2_ms - t1_ms;
    if dt <= 0 {
        return None; // includes time rollback
    }
    if !(bounds::SOC_MIN..=bounds::SOC_MAX).contains(&s1)
        || !(bounds::SOC_MIN..=bounds::SOC_MAX).contains(&s2)
    {
        return None;
    }
    let rate = (s2 - s1) as f64 / (dt as f64 / 60_000.0);
    if !rate.is_finite() || rate.abs() > 100.0 {
        return None; // impossible SOC slope
    }
    Some(rate)
}

/// A reading is stale if older than `threshold_ms` relative to `now_ms`.
pub fn is_stale(timestamp_ms: i64, now_ms: i64, threshold_ms: i64) -> bool {
    now_ms.saturating_sub(timestamp_ms) > threshold_ms.max(0)
}

/// True if two snapshots look like the same repeated reading (no new data).
pub fn is_duplicate(a: &ChargingSnapshot, b: &ChargingSnapshot) -> bool {
    a.soc_percent == b.soc_percent
        && a.battery_voltage_uv == b.battery_voltage_uv
        && a.battery_current_ua == b.battery_current_ua
        && a.battery_temperature_decic == b.battery_temperature_decic
        && a.input_voltage_uv == b.input_voltage_uv
        && a.input_current_ua == b.input_current_ua
}

/// Compute aggregate metrics over a window. Duplicate samples are excluded
/// from rate calculation so repeated values are not mistaken for real change.
pub fn compute_metrics(snapshots: &[ChargingSnapshot]) -> ChargingMetrics {
    if snapshots.is_empty() {
        return ChargingMetrics {
            sample_count: 0,
            duration_ms: 0,
            soc_rate_percent_per_min: None,
            avg_battery_power_uw: None,
            peak_battery_power_uw: None,
            avg_temperature_decic: None,
            stale_ratio: 0.0,
        };
    }

    let first = &snapshots[0];
    let last = &snapshots[snapshots.len() - 1];
    let duration_ms = (last.timestamp_elapsed_ms - first.timestamp_elapsed_ms).max(0);

    // Stale ratio: use the newest sample as reference.
    let reference = last.timestamp_elapsed_ms;
    let stale_count = snapshots
        .iter()
        .filter(|s| is_stale(s.timestamp_elapsed_ms, reference, 10_000))
        .count();
    let stale_ratio = stale_count as f64 / snapshots.len() as f64;

    // SOC rate: skip trailing duplicates to avoid fake flat/zero slope.
    let mut rate = None;
    let mut anchor = first;
    for candidate in snapshots.iter().skip(1) {
        if !is_duplicate(anchor, candidate) {
            rate = soc_rate_percent_per_min(
                anchor.soc_percent,
                anchor.timestamp_elapsed_ms,
                candidate.soc_percent,
                candidate.timestamp_elapsed_ms,
            );
            anchor = candidate;
        }
    }

    let powers: Vec<i64> = snapshots
        .iter()
        .filter_map(|s| battery_power_uw(s.battery_voltage_uv, s.battery_current_ua))
        .collect();
    let avg_power = if powers.is_empty() {
        None
    } else {
        Some(powers.iter().sum::<i64>() / powers.len() as i64)
    };
    let peak_power = powers.iter().copied().max();

    let temps: Vec<i32> = snapshots
        .iter()
        .filter_map(|s| s.battery_temperature_decic)
        .filter(|t| (bounds::TEMP_DECIC_MIN..=bounds::TEMP_DECIC_MAX).contains(t))
        .collect();
    let avg_temp = if temps.is_empty() {
        None
    } else {
        Some(temps.iter().sum::<i32>() / temps.len() as i32)
    };

    ChargingMetrics {
        sample_count: snapshots.len() as i32,
        duration_ms,
        soc_rate_percent_per_min: rate,
        avg_battery_power_uw: avg_power,
        peak_battery_power_uw: peak_power,
        avg_temperature_decic: avg_temp,
        stale_ratio,
    }
}

/// Recompute battery power for snapshots whose collector left it as None.
/// Never overwrites an existing value.
pub fn fill_missing_battery_power(snapshots: &mut [ChargingSnapshot]) {
    for s in snapshots.iter_mut() {
        if s.battery_power_uw.is_none() {
            s.battery_power_uw = battery_power_uw(s.battery_voltage_uv, s.battery_current_ua);
        }
    }
}

/// Quality gate helper for a raw numeric reading.
pub fn classify_range(value: f64, min: f64, max: f64) -> DataQuality {
    if !value.is_finite() {
        DataQuality::ParseError
    } else if value < min || value > max {
        DataQuality::OutOfRange
    } else {
        DataQuality::Valid
    }
}

/// Filter source records that are actually usable as evidence.
pub fn usable_records(records: &[SourceRecord]) -> Vec<&SourceRecord> {
    records
        .iter()
        .filter(|r| r.quality == DataQuality::Valid && r.raw_value.is_some())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use cs_model::{DataSource, Side};

    fn snap(ts: i64, soc: Option<i32>, v: Option<i64>, i: Option<i64>) -> ChargingSnapshot {
        ChargingSnapshot {
            timestamp_elapsed_ms: ts,
            wall_clock_time_ms: ts + 1_000_000,
            soc_percent: soc,
            battery_voltage_uv: v,
            battery_current_ua: i,
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
    fn power_converts_uv_times_ua_to_uw() {
        // 4.0 V * 2.0 A = 8.0 W = 8_000_000 uW
        assert_eq!(battery_power_uw(Some(4_000_000), Some(2_000_000)), Some(8_000_000));
    }

    #[test]
    fn power_rejects_missing_and_negative_result() {
        assert_eq!(battery_power_uw(None, Some(2_000_000)), None);
        assert_eq!(battery_power_uw(Some(4_000_000), None), None);
        // Discharge current yields negative power; not a charging reading.
        assert_eq!(battery_power_uw(Some(4_000_000), Some(-2_000_000)), None);
    }

    #[test]
    fn power_rejects_out_of_range() {
        assert_eq!(battery_power_uw(Some(1_000_000), Some(2_000_000)), None); // 1 V
        assert_eq!(battery_power_uw(Some(4_000_000), Some(99_000_000)), None); // 99 A
    }

    #[test]
    fn soc_rate_uses_monotonic_clock() {
        // +2% over 60 s => 2 %/min
        let r = soc_rate_percent_per_min(Some(50), 0, Some(52), 60_000);
        assert_eq!(r, Some(2.0));
    }

    #[test]
    fn soc_rate_rejects_time_rollback_and_bad_soc() {
        assert_eq!(soc_rate_percent_per_min(Some(50), 100, Some(52), 50), None);
        assert_eq!(soc_rate_percent_per_min(Some(50), 0, Some(52), 0), None);
        assert_eq!(soc_rate_percent_per_min(Some(150), 0, Some(52), 60_000), None);
    }

    #[test]
    fn stale_detection() {
        assert!(is_stale(0, 11_000, 10_000));
        assert!(!is_stale(0, 9_000, 10_000));
    }

    #[test]
    fn duplicate_samples_do_not_create_fake_rate() {
        let samples = vec![
            snap(0, Some(50), Some(4_000_000), Some(2_000_000)),
            snap(3_000, Some(50), Some(4_000_000), Some(2_000_000)), // exact duplicate
            snap(6_000, Some(50), Some(4_000_000), Some(2_000_000)), // exact duplicate
        ];
        let m = compute_metrics(&samples);
        assert_eq!(m.sample_count, 3);
        assert_eq!(m.soc_rate_percent_per_min, None);
    }

    #[test]
    fn metrics_aggregate_power_and_temperature() {
        let mut a = snap(0, Some(40), Some(4_000_000), Some(2_000_000));
        let mut b = snap(3_000, Some(41), Some(4_000_000), Some(2_500_000));
        a.battery_temperature_decic = Some(320);
        b.battery_temperature_decic = Some(340);
        let m = compute_metrics(&[a, b]);
        assert_eq!(m.avg_battery_power_uw, Some(9_000_000)); // (8W + 10W)/2
        assert_eq!(m.peak_battery_power_uw, Some(10_000_000));
        assert_eq!(m.avg_temperature_decic, Some(330));
    }

    #[test]
    fn fill_missing_power_uses_valid_operands() {
        let mut samples = vec![snap(0, Some(50), Some(4_000_000), Some(1_000_000))];
        fill_missing_battery_power(&mut samples);
        assert_eq!(samples[0].battery_power_uw, Some(4_000_000));
    }

    #[test]
    fn range_classification() {
        assert_eq!(classify_range(f64::NAN, 0.0, 1.0), DataQuality::ParseError);
        assert_eq!(classify_range(2.0, 0.0, 1.0), DataQuality::OutOfRange);
        assert_eq!(classify_range(0.5, 0.0, 1.0), DataQuality::Valid);
    }

    #[test]
    fn usable_records_exclude_failed_quality() {
        let good = SourceRecord {
            field: "voltage_now".into(),
            source: DataSource::Sysfs,
            source_path: Some("/sys/class/power_supply/battery/voltage_now".into()),
            raw_value: Some("4000000".into()),
            normalized_value: Some("4.0".into()),
            unit: Some("uV".into()),
            side: Side::BatterySide,
            quality: DataQuality::Valid,
            timestamp_elapsed_ms: 0,
        };
        let mut bad = good.clone();
        bad.quality = DataQuality::PermissionDenied;
        let records = vec![good.clone(), bad];
        let usable = usable_records(&records);
        assert_eq!(usable.len(), 1);
        assert_eq!(usable[0], &good);
    }
}
