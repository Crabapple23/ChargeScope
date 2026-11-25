#!/usr/bin/env bash
# Build Rust core for arm64-v8a and generate Kotlin UniFFI bindings.
# Requires: rustup stable, cargo-ndk, Android NDK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
APP="$ROOT/android/app"

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK path}"

echo "[1/4] cargo fmt check"
(cd "$NATIVE" && cargo fmt --all -- --check)

echo "[2/4] cargo test (host)"
(cd "$NATIVE" && cargo test --workspace --all-targets)

echo "[3/4] build cdylib for arm64-v8a"
(cd "$NATIVE" && cargo ndk -t arm64-v8a -o "$APP/src/main/jniLibs" build --release -p cs-ffi)

echo "[4/4] generate Kotlin bindings"
mkdir -p "$APP/build/generated/uniffi/kotlin"
# uniffi-bindgen is a separate CLI binary installed once via:
#   cargo install uniffi_bindgen --version 0.28 --features kotlin
uniffi-bindgen generate \
  --library "$APP/src/main/jniLibs/arm64-v8a/libchargescope.so" \
  --language kotlin \
  --config "$NATIVE/crates/cs-ffi/uniffi.toml" \
  --out-dir "$APP/build/generated/uniffi/kotlin" || {
  echo "ERROR: uniffi-bindgen not found or failed. Install once with:"
  echo "  cargo install uniffi_bindgen --version 0.28 --features kotlin"
  exit 1
}

echo "Done. Now run: cd android && ./gradlew assembleDebug"
