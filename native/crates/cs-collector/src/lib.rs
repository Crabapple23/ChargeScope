//! cs-collector — Sysfs value parsing and field semantics.
//! Pure Rust: receives raw strings collected by Kotlin, never executes shell
//! commands itself. No sysfs path is hardcoded as "the" path; callers pass the
//! discovered path and this crate classifies by field name.

use cs_core::bounds;
use cs_model::{DataQuality, DataSource, Side, SourceRecord};

/// Fields we know how to interpret. Anything else is recorded but not
/// normalised into a charging metric.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KnownField {
    Type,
    Status,
    Online,
    Present,
    Capacity,
    VoltageNow,
    CurrentNow,
    CurrentAvg,
    PowerNow,
    VoltageMax,
    CurrentMax,
    UsbType,
    RealType,
    ChargeType,
    Temp,
    InputCurrentLimit,
    InputVoltageLimit,
    ConstantChargeCurrent,
    ConstantChargeVoltage,
    ChargeCounter,
}

impl KnownField {
    pub fn from_name(name: &str) -> Option<Self> {
        match name.trim() {
            "type" => Some(Self::Type),
            "status" => Some(Self::Status),
            "online" => Some(Self::Online),
            "present" => Some(Self::Present),
            "capacity" => Some(Self::Capacity),
            "voltage_now" => Some(Self::VoltageNow),
            "current_now" => Some(Self::CurrentNow),
            "current_avg" => Some(Self::CurrentAvg),
            "power_now" => Some(Self::PowerNow),
            "voltage_max" => Some(Self::VoltageMax),
            "current_max" => Some(Self::CurrentMax),
            "usb_type" => Some(Self::UsbType),
            "real_type" => Some(Self::RealType),
            "charge_type" => Some(Self::ChargeType),
            "temp" => Some(Self::Temp),
            "input_current_limit" => Some(Self::InputCurrentLimit),
            "input_voltage_limit" => Some(Self::InputVoltageLimit),
            "constant_charge_current" => Some(Self::ConstantChargeCurrent),
            "constant_charge_voltage" => Some(Self::ConstantChargeVoltage),
            "charge_counter" => Some(Self::ChargeCounter),
            _ => None,
        }
    }

    pub fn default_unit(self) -> Option<&'static str> {
        match self {
            Self::VoltageNow | Self::VoltageMax | Self::InputVoltageLimit
            | Self::ConstantChargeVoltage => Some("uV"),
            Self::CurrentNow | Self::CurrentAvg | Self::CurrentMax
            | Self::InputCurrentLimit | Self::ConstantChargeCurrent => Some("uA"),
            Self::PowerNow => Some("uW"),
            Self::Temp => Some("deciC"),
            Self::Capacity => Some("percent"),
            Self::ChargeCounter => Some("uAh"), // must be verified per device
            _ => None,
        }
    }

    pub fn default_side(self) -> Side {
        match self {
            Self::InputCurrentLimit | Self::InputVoltageLimit | Self::UsbType
            | Self::RealType | Self::ChargeType => Side::InputSide,
            Self::VoltageNow | Self::CurrentNow | Self::CurrentAvg | Self::PowerNow
            | Self::Capacity | Self::Temp | Self::VoltageMax | Self::CurrentMax
            | Self::ChargeCounter => Side::BatterySide,
            Self::ConstantChargeCurrent | Self::ConstantChargeVoltage => Side::UnknownSide,
            _ => Side::UnknownSide,
        }
    }

    /// These are LIMIT/CONFIGURATION values, not live measurements. They must
    /// never be fed into input power calculations.
    pub fn is_limit_or_config(self) -> bool {
        matches!(
            self,
            Self::InputCurrentLimit
                | Self::InputVoltageLimit
                | Self::ConstantChargeCurrent
                | Self::ConstantChargeVoltage
                | Self::VoltageMax
                | Self::CurrentMax
        )
    }
}

/// Parse the first integer in a raw sysfs string (drivers sometimes append
/// units or newline). Returns None on garbage.
pub fn parse_first_i64(raw: &str) -> Option<i64> {
    let trimmed = raw.trim();
    let end = trimmed
        .char_indices()
        .find(|(_, c)| !(c.is_ascii_digit() || *c == '-' || *c == '+'))
        .map(|(i, _)| i)
        .unwrap_or(trimmed.len());
    if end == 0 {
        return None;
    }
    trimmed[..end].parse::<i64>().ok()
}

/// Normalise a raw reading into a provenance-carrying SourceRecord.
/// Unknown fields are still preserved with raw_value and UnknownSide.
pub fn normalize_reading(
    field: &str,
    raw_value: Option<String>,
    source_path: String,
    source: DataSource,
    timestamp_elapsed_ms: i64,
) -> SourceRecord {
    let known = KnownField::from_name(field);
    let unit = known.and_then(|k| k.default_unit()).map(|s| s.to_string());
    let side = known.map(|k| k.default_side()).unwrap_or(Side::UnknownSide);

    let (normalized_value, quality) = match &raw_value {
        None => (None, DataQuality::Missing),
        Some(raw) if raw.trim().is_empty() => (None, DataQuality::Missing),
        Some(raw) => {
            if known.is_some() && unit.is_some() {
                match parse_first_i64(raw) {
                    Some(n) => {
                        let q = match known.unwrap() {
                            KnownField::Capacity => cs_core::classify_range(
                                n as f64,
                                bounds::SOC_MIN as f64,
                                bounds::SOC_MAX as f64,
                            ),
                            KnownField::Temp => cs_core::classify_range(
                                n as f64,
                                bounds::TEMP_DECIC_MIN as f64,
                                bounds::TEMP_DECIC_MAX as f64,
                            ),
                            KnownField::VoltageNow | KnownField::VoltageMax
                            | KnownField::InputVoltageLimit
                            | KnownField::ConstantChargeVoltage => cs_core::classify_range(
                                n as f64,
                                bounds::VOLTAGE_UV_MIN as f64,
                                bounds::VOLTAGE_UV_MAX as f64,
                            ),
                            KnownField::CurrentNow | KnownField::CurrentAvg
                            | KnownField::CurrentMax | KnownField::InputCurrentLimit
                            | KnownField::ConstantChargeCurrent => cs_core::classify_range(
                                n.abs() as f64,
                                0.0,
                                bounds::CURRENT_UA_ABS_MAX as f64,
                            ),
                            _ => DataQuality::Valid,
                        };
                        (Some(n.to_string()), q)
                    }
                    None => (None, DataQuality::ParseError),
                }
            } else {
                // Textual field (type/status/usb_type): preserve trimmed text.
                (Some(raw.trim().to_string()), DataQuality::Valid)
            }
        }
    };

    SourceRecord {
        field: field.to_string(),
        source,
        source_path: Some(source_path),
        raw_value,
        normalized_value,
        unit,
        side,
        quality,
        timestamp_elapsed_ms,
    }
}

/// Detect whether a charge_type string looks like a driver-reported protocol
/// name. Returns a protocol hint only when the string is explicit; no
/// inference from power levels happens here.
pub fn protocol_hint_from_charge_type(charge_type: &str) -> Option<&'static str> {
    let s = charge_type.to_ascii_lowercase();
    if s.contains("pps") {
        Some("PPS")
    } else if s.contains("pd") {
        Some("PD")
    } else if s.contains("ufcs") {
        Some("UFCS")
    } else if s.contains("scp") {
        Some("SCP")
    } else if s.contains("fcp") {
        Some("FCP")
    } else if s.contains("afc") {
        Some("AFC") // careful: substring may match other words; analyzer verifies
    } else if s.contains("qc") {
        Some("QC")
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(raw: &str, field: &str) -> SourceRecord {
        normalize_reading(
            field,
            Some(raw.to_string()),
            format!("/sys/class/power_supply/battery/{}", field),
            DataSource::Sysfs,
            123,
        )
    }

    #[test]
    fn parses_voltage_with_trailing_newline() {
        let r = rec("4000000\n", "voltage_now");
        assert_eq!(r.normalized_value.as_deref(), Some("4000000"));
        assert_eq!(r.unit.as_deref(), Some("uV"));
        assert_eq!(r.side, Side::BatterySide);
        assert_eq!(r.quality, DataQuality::Valid);
    }

    #[test]
    fn marks_out_of_range_reading() {
        let r = rec("999\n", "voltage_now"); // 999 uV < 2V
        assert_eq!(r.quality, DataQuality::OutOfRange);
    }

    #[test]
    fn limit_fields_are_never_measured_side_values() {
        let field = KnownField::from_name("input_current_limit").unwrap();
        assert!(field.is_limit_or_config());
        assert_eq!(field.default_side(), Side::InputSide);
        let r = rec("3000000", "input_current_limit");
        assert_eq!(r.unit.as_deref(), Some("uA"));
    }

    #[test]
    fn textual_field_is_preserved() {
        let r = rec("  USB_PD  \n", "charge_type");
        assert_eq!(r.normalized_value.as_deref(), Some("USB_PD"));
        assert_eq!(r.quality, DataQuality::Valid);
    }

    #[test]
    fn garbage_number_is_parse_error() {
        let r = rec("not-a-number", "current_now");
        assert_eq!(r.quality, DataQuality::ParseError);
        assert_eq!(r.normalized_value, None);
    }

    #[test]
    fn unknown_field_keeps_raw_with_unknown_side() {
        let r = rec("12345", "vendor_secret_node");
        assert_eq!(r.side, Side::UnknownSide);
        assert_eq!(r.unit, None);
        assert_eq!(r.raw_value.as_deref(), Some("12345"));
    }

    #[test]
    fn negative_current_keeps_sign() {
        let r = rec("-1500000", "current_now");
        assert_eq!(r.normalized_value.as_deref(), Some("-1500000"));
        assert_eq!(r.quality, DataQuality::Valid);
    }

    #[test]
    fn charge_type_hints_are_explicit_only() {
        assert_eq!(protocol_hint_from_charge_type("USB_PD_PPS"), Some("PPS"));
        assert_eq!(protocol_hint_from_charge_type("QC3.0"), Some("QC"));
        assert_eq!(protocol_hint_from_charge_type(""), None);
    }
}
