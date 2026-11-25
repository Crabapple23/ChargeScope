# ChargeScope

Android 快充状态识别与充电分析工具。**Kotlin + Jetpack Compose + Rust + UniFFI**，本地优先，Root 仅作可选只读增强。

> 当前状态：**P0–P4 源码骨架已落盘**；本机无法编译（无 Android SDK / NDK / Rust 工具链，且本机外部进程启动异常）。请在具备完整工具链的机器按 `docs/BUILD.md` 完成首次构建与验证。

## 核心原则

- 区分「设备支持」「充电器支持」「当前实际协商」三个不同概念
- 缺失值一律 `null`，绝不用 0 伪装有效读数
- 电池侧功率与 USB 输入功率分开命名、分开存储
- 不凭功率/温度/SOC 斜率确认 PD/PPS/QC/UFCS/厂商私有协议
- Root 功能默认关闭且只读：不写 sysfs、不改内核参数、不控制充电策略
- 所有数值带单位、来源、侧别、质量状态；协议结果必须同时展示状态与限制说明

## 目录

```
ChargeScope/
├── docs/                     规格书、冻结契约、构建/架构/数据字典/设备兼容
├── android/                  Gradle 工程（Compose UI / 采集 / Room / 服务）
├── native/                   Rust workspace（7 个 crate + UniFFI）
├── scripts/                  可复现构建脚本（bash + PowerShell）
├── samples/                  CSV / JSON 导出样例
├── .github/workflows/        CI
└── PROGRESS.md               ★ 跨会话进度节点（每次工作前先读）
```

## 快速开始（在具备工具链的机器上）

1. 阅读 `docs/CONTRACT.md`（冻结模型与 FFI 契约）和 `PROGRESS.md`。
2. 安装 Android Studio（SDK 35、NDK 26.3.11579264、JDK 17）、Rust stable、`cargo-ndk`、`uniffi-bindgen`。
3. 设置 `ANDROID_NDK_HOME`，运行 `scripts/build-rust.sh`（或 `.ps1`）。
4. `cd android && ./gradlew assembleDebug`。
5. APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

## MVP 范围

包含：无 Root 基础采集、可选 Root 只读扫描、统一模型与质量标记、Rust 证据分析引擎、五个 Compose 页面、前台服务、房间持久化、CSV/JSON 导出、Rust/Kotlin 单元测试。

不包含：写充电控制节点、完整 PD PDO/APDO 读取、云端账号/同步、广告/订阅、多 ABI/多厂商深度适配。

## 证据等级（UI 不显示百分比概率）

| 等级 | 含义 |
|---|---|
| L4 | 驱动/控制器明确报告协议名称或握手结果 |
| L3 | 输入侧电压/电流、动态调压、协商状态 |
| L2 | 功率曲线、温度、SOC、充电速率（不得单独确认协议） |
| L1 | 机型/SoC/充电 IC/规格（仅缩小候选范围） |

结果状态：`CONFIRMED` / `HIGH_CONFIDENCE` / `POSSIBLE` / `INDETERMINATE` / `UNKNOWN` / `UNSUPPORTED`。

## 许可证与隐私

本地处理，不申请 INTERNET 权限，不上传数据。导出时默认脱敏并提示隐私风险。
