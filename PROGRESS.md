# ChargeScope 进度节点（跨会话续作用）

> **每次继续工作前先读本文件，结束后更新本文件。**
> 计划书：`ChargeScope开发实施方案与技术规格书.docx`（已转写为 `docs/` 下多份文档）。

## 当前状态

- 最后更新：2026-11-24
- 已完成阶段：**P0、P1、P2、P3、P4 源码骨架**（文件级产物）
- 未完成：**真实编译 / APK 构建 / 真机测试 / P5 设备适配实测 / P6 发布**
- 阻塞原因：本机外部进程启动崩溃（AccessViolationException，`bash`/`python`/`java` 等均无法运行）；无 Android SDK、NDK、Rust 工具链。**这是环境限制，不是代码问题。**

## 已完成内容清单

| 阶段 | 产物 | 状态 |
|---|---|---|
| P0 | `android/` Gradle 工程（版本目录锁定）、`native/` 7-crate Rust workspace、`cs-ffi` UniFFI 导出、`scripts/build-rust.*` | 源码完成，未编译 |
| P0 | `docs/CONTRACT.md` 冻结数据模型与 FFI 契约 | 完成 |
| P1 | `BatteryApiCollector.kt`（无 Root）、`RootBridge.kt`（只读白名单+超时）、`RootSysfsCollector.kt`（动态扫描） | 源码完成 |
| P2 | `cs-model` Option 模型、`Room` 四张表、`Repository.kt`、`SettingsStore.kt` | 源码完成 |
| P3 | `cs-core`（单位/功率/速率/stale）、`cs-collector`（解析）、`cs-protocol`（证据/候选/冲突/状态）、`cs-analyzer`、`cs-device` + Rust 单测 | 源码完成，未跑 |
| P4 | 五个 Compose 页面、前台服务、`RustBridge.kt`、CSV/JSON 导出、Kotlin 单测 | 源码完成 |
| P5 | 设备档案接口已留（`DeviceProfile`），但 K70 Pro 实测未做 | 待真机 |
| P6 | CI / 文档基本完成；签名、性能、稳定性、可复现构建验证未做 | 部分 |

## 关键设计决策（不要推翻）

1. **冻结契约**：`docs/CONTRACT.md` v0.1.0 是唯一权威。改字段必须同步 `cs-model` + Kotlin `domain/model` + `RustBridge`，并升版本。
2. **UniFFI 命名**：Kotlin 枚举项用 SCREAMING_SNAKE_CASE（Rust `BatterySide` → Kotlin `BATTERY_SIDE`），已按官方文档确认；所有映射集中在 `RustBridge.kt`。
3. **Rust 是权威**：单位换算、质量判定、协议推断在 Rust；Kotlin 只做平台采集与展示。
4. **Root 只读**：`RootBridge` 仅允许 `su -c id/ls/cat/getprop`，路径白名单 `/sys/class/power_supply|typec/`，禁止写操作。
5. **侧别隔离**：`battery_*` 与 `input_*` 分开；`input_*_limit` 等配置值不进入功率计算。
6. **状态保守**：行为证据不得升级为 CONFIRMED；冲突 → INDETERMINATE；无数据 → UNKNOWN；无采集能力 → UNSUPPORTED。

## 已知风险 / 需首次构建时验证

- `uniffi-bindgen` 与 `cs-model` 多 crate scaffolding 的实际链接行为需 `cargo build` 验证（已按官方文档在 `cs-model` 与 `cs-ffi` 各放一次 `setup_scaffolding!`）。
- Gradle / AGP / Kotlin / Compose / Room / KSP 版本为相互兼容的稳定基线，**未经本机编译验证**；首次构建若失败，以 `docs/BUILD.md` 的升级说明调整 `gradle/libs.versions.toml`。
- `RustBridge.kt` 依赖生成的 Kotlin 绑定；必须先跑 `scripts/build-rust.*` 才会生成 `android/app/build/generated/uniffi/kotlin`。
- `HistoryScreen.kt` 使用 `mutableStateOf`/`remember`，已导入；首次编译请确认 Compose 版本一致。
- `DeviceProfile` 在 P4 分析调用中暂传 `null`（`sampling service` 注释标明），P5 需接入设备档案。
- `ROOT_SCAN_INTERVAL_MS=10s`，采样默认 3s；Root 扫描不会每秒启动大量 `su`。

## 下一步（按优先级）

1. 在具备工具链的机器执行 `scripts/build-rust.sh` → 生成绑定与 `.so`。
2. `./gradlew assembleDebug` → 修复首个编译错误（预计是 UniFFI 生成类型命名或版本问题）。
3. 装到 arm64-v8a 真机，确认无 Root 路径可用；再逐项验证 Root 只读节点。
4. P5：按 `docs/device-compatibility.md` 做 K70 Pro 实测，填写 `verified_paths`（不要凭猜测填）。
5. P6：性能/耗电/隐私核查、签名、可复现构建。

## 工作日志

- 2026-11-24：读取规格书 DOCX（ZIP/XML 解析，本机无外部进程可用）；冻结契约；落盘 P0–P4 全部源码骨架、脚本、CI、文档与样例。
