# 构建环境与可复现构建

## 环境要求（由 Agent 实施时选取并锁定的稳定基线）

| 组件 | 锁定版本 | 位置 |
|---|---|---|
| Gradle | 8.7 | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.5.2 | `android/gradle/libs.versions.toml` |
| Kotlin | 2.0.20 | 同上 |
| Compose BOM | 2024.09.02 | 同上 |
| KSP | 2.0.20-1.0.24 | 同上 |
| Room | 2.6.1 | 同上 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 | `android/app/build.gradle.kts` |
| JDK | 17 | `android/app/build.gradle.kts` |
| NDK | 26.3.11579264（建议） | 本地安装 |
| Rust | stable / edition 2021 | `native/Cargo.toml` |
| UniFFI | 0.28 | `native/Cargo.toml` |
| cargo-ndk | 最新兼容版 | 本地安装 |

> **诚实声明**：以上版本是相互兼容的稳定组合，但**本机无 Android SDK / NDK / Rust 工具链，未做编译验证**。规格书明确要求「不得臆造未经验证的版本组合」，因此首次构建若遇到版本冲突，**以实际构建结果为准**调整 `libs.versions.toml`，并在本文件记录最终验证过的组合。

## 一次性环境准备

```bash
# Rust 侧
rustup toolchain install stable
cargo install cargo-ndk
cargo install uniffi_bindgen --version 0.28 --features kotlin

# Android 侧：Android Studio 安装 SDK 35 + NDK 26.3.11579264 + JDK 17
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/26.3.11579264
```

## 可复现 Debug APK 构建（一条命令链）

```bash
./scripts/build-rust.sh          # Rust 单测 + arm64-v8a .so + Kotlin 绑定
cd android && ./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\scripts\build-rust.ps1
cd android; .\gradlew assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（arm64-v8a）。

## 测试

```bash
cd native && cargo test --workspace --all-targets   # Rust 单测 + 集成测试
cd android && ./gradlew testDebugUnitTest          # Kotlin JVM 单测
```

## 验证检查表（首次构建后填写）

- [ ] `cargo test --workspace --all-targets` 全绿
- [ ] UniFFI Kotlin 绑定生成成功，枚举项命名与 `RustBridge.kt` 一致
- [ ] `assembleDebug` 成功，APK 仅含 arm64-v8a `.so`
- [ ] Kotlin JVM 单测通过
- [ ] 无 Root 真机可启动、可显示电池数据
- [ ] Root 设备可扫描 sysfs，日志可见原始值与路径

## 版本升级说明

升级任一组件时：先在 `libs.versions.toml` 或 `Cargo.toml` 修改 → 跑全部测试 → 在本文件「验证检查表」记录实测结果 → 更新 `PROGRESS.md`。
