//! cs-device — device capability profiles.
//! A profile describes what a device MAY support. It never proves what is
//! currently negotiated. `verified_paths` stays empty until real testing
//! records are added — see docs/device-compatibility.md.

use cs_model::{DeviceProfile, Protocol};

/// Known device profiles. Only model matching is performed; no sysfs paths are
/// asserted here. Verified paths must be filled from device test records.
pub fn profile_for_model(model: Option<&str>, android_version: Option<&str>) -> Option<DeviceProfile> {
    let model = model?;
    if model.contains("K70") {
        Some(DeviceProfile {
            model: Some(model.to_string()),
            soc_name: Some("Snapdragon 8 Gen 3".into()),
            android_version: android_version.map(|s| s.to_string()),
            root_available: false,
            verified_paths: vec![], // MUST stay empty until verified on device
            supported_protocols: vec![Protocol::Pd, Protocol::PdPps, Protocol::MiPrivate],
            note: Some("候选能力，来自公开规格；非当前协商证明，需 K70 Pro 实测验证".into()),
        })
    } else {
        None
    }
}

/// Merge a device profile with runtime root availability from the collector.
pub fn with_root(mut profile: DeviceProfile, root_available: bool) -> DeviceProfile {
    profile.root_available = root_available;
    profile
}

/// True if any usable sampling capability exists at all.
pub fn has_sampling_capability(
    profile: Option<&DeviceProfile>,
    any_public_api_reading: bool,
) -> bool {
    any_public_api_reading || profile.map(|p| p.root_available).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn k70_profile_has_no_fabricated_paths() {
        let p = profile_for_model(Some("Redmi K70 Pro"), Some("15")).unwrap();
        assert!(p.verified_paths.is_empty());
        assert!(p.supported_protocols.contains(&Protocol::MiPrivate));
        assert!(p.note.unwrap().contains("非当前协商证明"));
    }

    #[test]
    fn unknown_model_has_no_profile() {
        assert!(profile_for_model(Some("Unknown Phone"), Some("14")).is_none());
        assert!(profile_for_model(None, Some("14")).is_none());
    }

    #[test]
    fn capability_detection() {
        assert!(!has_sampling_capability(None, false));
        assert!(has_sampling_capability(None, true));
        let p = DeviceProfile {
            model: None,
            soc_name: None,
            android_version: None,
            root_available: true,
            verified_paths: vec![],
            supported_protocols: vec![],
            note: None,
        };
        assert!(has_sampling_capability(Some(&p), false));
    }
}
