# Build Rust core for arm64-v8a and generate Kotlin UniFFI bindings (Windows).
# Requires: rustup stable, cargo-ndk, Android NDK.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$native = Join-Path $root 'native'
$app = Join-Path $root 'android\app'

if (-not $env:ANDROID_NDK_HOME) { throw 'Set ANDROID_NDK_HOME to your NDK path first.' }

Write-Host '[1/4] cargo fmt check'
Push-Location $native; cargo fmt --all -- --check; Pop-Location

Write-Host '[2/4] cargo test (host)'
Push-Location $native; cargo test --workspace --all-targets; Pop-Location

Write-Host '[3/4] build cdylib for arm64-v8a'
Push-Location $native
cargo ndk -t arm64-v8a -o (Join-Path $app 'src\main\jniLibs') build --release -p cs-ffi
Pop-Location

Write-Host '[4/4] generate Kotlin bindings'
$outDir = Join-Path $app 'build\generated\uniffi\kotlin'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$so = Join-Path $app 'src\main\jniLibs\arm64-v8a\libchargescope.so'
& uniffi-bindgen generate --library $so --language kotlin `
  --config (Join-Path $native 'crates\cs-ffi\uniffi.toml') --out-dir $outDir
if ($LASTEXITCODE -ne 0) {
  throw 'uniffi-bindgen failed. Install once: cargo install uniffi_bindgen --version 0.28 --features kotlin'
}

Write-Host 'Done. Now: cd android; .\gradlew assembleDebug'
