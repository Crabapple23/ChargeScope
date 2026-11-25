//! cs-model — ChargeScope frozen data model.
//! Mirrors docs/CONTRACT.md v0.1.0. Do NOT change field names/semantics here
//! without updating the contract and the Kotlin side in the same commit.

use serde::{Deserialize, Serialize};

// Each crate that uses UniFFI proc-macro derives must declare scaffolding.
uniffi::setup_scaffolding!();

/// Where a reading came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum DataSource {
    AndroidApi,
    Sysfs,
    RootShell,
    DeviceAdapter,
}

/// Quality flag attached to every raw reading.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum DataQuality {
    Valid,
    Stale,
    OutOfRange,
    ParseError,
    PermissionDenied,
    Missing,
}

/// Which side of the charging path a measurement belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum Side {
    BatterySide,
    InputSide,
    UnknownSide,
}

/// Evidence strength. L4 is strongest (direct driver report).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, uniffi::Enum)]
pub enum EvidenceLevel {
    L1,
    L2,
    L3,
    L4,
}

/// Result status. Never present these as probabilities.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum AnalysisStatus {
    Confirmed,
    HighConfidence,
    Possible,
    Indeterminate,
    Unknown,
    Unsupported,
}

/// Protocols considered by the inference engine. Listing a protocol does NOT
/// mean this build can recognise it; recognition requires verified evidence.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
pub enum Protocol {
    Pd,
    PdPps,
    Qc2,
    Qc3,
    Qc4,
    Ufcs,
    Scp,
    Fcp,
    Afc,
    MiPrivate,
    Vooc,
    SuperVooc,
    Other,
    None,
}

impl Protocol {
    /// Stable display name for UI. Not a probability, not a claim of detection.
    pub fn display_name(self) -> &'static str {
        match self {
            Protocol::Pd => "USB PD",
            Protocol::PdPps => "USB PD PPS",
            Protocol::Qc2 => "QC 2.0",
            Protocol::Qc3 => "QC 3.0",
            Protocol::Qc4 => "QC 4+",
            Protocol::Ufcs => "UFCS",
            Protocol::Scp => "SCP",
            Protocol::Fcp => "FCP",
            Protocol::Afc => "AFC",
            Protocol::MiPrivate => "小米私有快充",
            Protocol::Vooc => "VOOC",
            Protocol::SuperVooc => "SuperVOOC",
            Protocol::Other => "其他协议",
            Protocol::None => "未识别",
        }
    }
}

/// One raw reading with full provenance. Missing values are `None`, never 0.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct SourceRecord {
    pub field: String,
    pub source: DataSource,
    pub source_path: Option<String>,
    pub raw_value: Option<String>,
    pub normalized_value: Option<String>,
    pub unit: Option<String>,
    pub side: Side,
    pub quality: DataQuality,
    pub timestamp_elapsed_ms: i64,
}

/// A normalised point-in-time charging sample.
/// Battery-side and input-side fields are intentionally separate.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ChargingSnapshot {
    pub timestamp_elapsed_ms: i64,
    pub wall_clock_time_ms: i64,
    pub soc_percent: Option<i32>,
    pub battery_voltage_uv: Option<i64>,
    pub battery_current_ua: Option<i64>,
    pub battery_power_uw: Option<i64>,
    pub battery_temperature_decic: Option<i32>,
    pub input_voltage_uv: Option<i64>,
    pub input_current_ua: Option<i64>,
    pub input_power_uw: Option<i64>,
    pub usb_type: Option<String>,
    pub charge_type: Option<String>,
    pub charging_status: Option<String>,
    pub source_records: Vec<SourceRecord>,
    pub root_available: bool,
}

impl ChargingSnapshot {
    /// True when no side reports any usable numeric reading.
    pub fn has_any_measurement(&self) -> bool {
        self.soc_percent.is_some()
            || self.battery_voltage_uv.is_some()
            || self.battery_current_ua.is_some()
            || self.battery_power_uw.is_some()
            || self.battery_temperature_decic.is_some()
            || self.input_voltage_uv.is_some()
            || self.input_current_ua.is_some()
            || self.input_power_uw.is_some()
    }
}

/// A candidate protocol with supporting evidence strength. `score` is an
/// ordering hint from the initial weights, NOT a probability.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ProtocolCandidate {
    pub protocol: Protocol,
    pub score: f64,
    pub evidence_level: EvidenceLevel,
    pub reasons: Vec<String>,
}

/// A single evidence item contributing to (or against) a protocol conclusion.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ProtocolEvidence {
    pub protocol: Option<Protocol>,
    pub level: EvidenceLevel,
    pub source: String,
    pub observation: String,
    pub supports: bool,
    pub quality: DataQuality,
}

/// Two conflicting evidence items that cannot both hold.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct EvidenceConflict {
    pub protocol_a: Option<Protocol>,
    pub protocol_b: Option<Protocol>,
    pub description: String,
}

/// Full analysis output. The UI must render status + limitations, never a bare
/// "fast charging OK" label.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ProtocolAnalysis {
    pub status: AnalysisStatus,
    pub primary_candidate: Option<Protocol>,
    pub candidates: Vec<ProtocolCandidate>,
    pub evidence: Vec<ProtocolEvidence>,
    pub conflicts: Vec<EvidenceConflict>,
    pub explanation: String,
    pub limitations: Vec<String>,
    pub analyzed_at_elapsed_ms: i64,
    pub algorithm_version: String,
}

/// Device capability profile. `verified_paths` MUST be populated only from
/// recorded device testing; never fabricated.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct DeviceProfile {
    pub model: Option<String>,
    pub soc_name: Option<String>,
    pub android_version: Option<String>,
    pub root_available: bool,
    pub verified_paths: Vec<String>,
    pub supported_protocols: Vec<Protocol>,
    pub note: Option<String>,
}

/// Derived metrics over a window of snapshots.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ChargingMetrics {
    pub sample_count: i32,
    pub duration_ms: i64,
    pub soc_rate_percent_per_min: Option<f64>,
    pub avg_battery_power_uw: Option<i64>,
    pub peak_battery_power_uw: Option<i64>,
    pub avg_temperature_decic: Option<i32>,
    pub stale_ratio: f64,
}

/// Bundle returned by the engine so one FFI call carries everything the UI needs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct AnalysisBundle {
    pub metrics: ChargingMetrics,
    pub analysis: ProtocolAnalysis,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_without_measurements_is_detected() {
        let s = ChargingSnapshot {
            timestamp_elapsed_ms: 0,
            wall_clock_time_ms: 0,
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
        };
        assert!(!s.has_any_measurement());
    }

    #[test]
    fn protocol_display_names_are_stable() {
        assert_eq!(Protocol::PdPps.display_name(), "USB PD PPS");
        assert_eq!(Protocol::None.display_name(), "未识别");
    }

    #[test]
    fn serde_roundtrip_keeps_missing_values_as_null() {
        let rec = SourceRecord {
            field: "current_now".into(),
            source: DataSource::Sysfs,
            source_path: Some("/sys/class/power_supply/battery/current_now".into()),
            raw_value: None,
            normalized_value: None,
            unit: Some("uA".into()),
            side: Side::BatterySide,
            quality: DataQuality::PermissionDenied,
            timestamp_elapsed_ms: 42,
        };
        let json = serde_json::to_string(&rec).unwrap();
        assert!(json.contains("\"raw_value\":null"));
        let back: SourceRecord = serde_json::from_str(&json).unwrap();
        assert_eq!(back, rec);
    }
}
