// ChargeScope root build file.
// Versions are locked in gradle/libs.versions.toml (Version Catalog).
// See docs/BUILD.md for why these versions were chosen and how to upgrade.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
