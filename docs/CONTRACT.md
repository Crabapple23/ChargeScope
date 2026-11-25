# ChargeScope 冻结契约（数据模型 + FFI）

> 状态：**FROZEN v0.1.0** ｜ 冻结日期：2026-11-24 ｜ 变更需在本文件记录并由主控 Agent 同步 Kotlin/Rust 两侧。
> 本文件是 P0 的产物，后续所有 crate 与 Kotlin 模块必须严格遵守。禁止两侧各自定义同名字段。

## 1. 命名与单位约定（不可协商）

| 概念 | 字段名 | 单位 | 说明 |
|---|---|---|---|
| 单调时钟 | `timestamp_elapsed_ms` | ms | `SystemClock.elapsedRealtime()`，用于速率计算，禁止用 wall clock 算速率 |
| 墙上时钟 | `wall_clock_time_ms` | ms | `System.currentTimeMillis()`，仅用于展示与会话边界 |
| SOC | `soc_percent` | percent | Int，0–100，越界写 `null` |
| 电池电压 | `battery_voltage_uv` | µV | 由 `EXTRA_VOLTAGE`(mV) × 1000 |
| 电池电流 | `battery_current_ua` | µA | 保留符号；方向未经校准时只存储不计算功率 |
| 电池功率 | `battery_power_uw` | µW | `V×I`，仅当单位/符号/方向已校准时计算 |
| 电池温度 | `battery_temperature_decic` | 0.1 °C | Android `EXTRA_TEMPERATURE` 原生单位 |
| 输入侧电压/电流/功率 | `input_*` | µV / µA / µW | **与电池侧分开命名、分开存储**；无实测时必须是 `null` |
| 缺失值 | — | — | 一律 `Option<T>` / Kotlin `T?`，**禁止用 0 伪装** |

### 1.1 侧别（side）
`BATTERY_SIDE` ｜ `INPUT_SIDE` ｜ `UNKNOWN_SIDE`

### 1.2 质量（quality）
`VALID` ｜ `STALE` ｜ `OUT_OF_RANGE` ｜ `PARSE_ERROR` ｜ `PERMISSION_DENIED` ｜ `MISSING`

### 1.3 来源（source）
`ANDROID_API` ｜ `SYSFS` ｜ `ROOT_SHELL` ｜ `DEVICE_ADAPTER`

## 2. Rust 侧权威定义（crate `cs-model`）

```rust
pub enum DataSource      { AndroidApi, Sysfs, RootShell, DeviceAdapter }
pub enum DataQuality     { Valid, Stale, OutOfRange, ParseError, PermissionDenied, Missing }
pub enum Side            { BatterySide, InputSide, UnknownSide }
pub enum EvidenceLevel   { L1, L2, L3, L4 }
pub enum AnalysisStatus  { Confirmed, HighConfidence, Possible, Indeterminate, Unknown, Unsupported }
pub enum Protocol        { Pd, PdPps, Qc2, Qc3, Qc4, Ufcs, Scp, Fcp, Afc,
                           MiPrivate, Vooc, SuperVooc, Other, None }

pub struct SourceRecord {
    pub field: String, pub source: DataSource,
    pub source_path: Option<String>, pub raw_value: Option<String>,
    pub normalized_value: Option<String>, pub unit: Option<String>,
    pub side: Side, pub quality: DataQuality, pub timestamp_elapsed_ms: i64,
}
pub struct ChargingSnapshot {
    pub timestamp_elapsed_ms: i64, pub wall_clock_time_ms: i64,
    pub soc_percent: Option<i32>,
    pub battery_voltage_uv: Option<i64>, pub battery_current_ua: Option<i64>,
    pub battery_power_uw: Option<i64>, pub battery_temperature_decic: Option<i32>,
    pub input_voltage_uv: Option<i64>, pub input_current_ua: Option<i64>,
    pub input_power_uw: Option<i64>,
    pub usb_type: Option<String>, pub charge_type: Option<String>,
    pub charging_status: Option<String>,
    pub source_records: Vec<SourceRecord>, pub root_available: bool,
}
pub struct ProtocolCandidate { pub protocol: Protocol, pub score: f64,
    pub evidence_level: EvidenceLevel, pub reasons: Vec<String> }
pub struct ProtocolEvidence { pub protocol: Option<Protocol>, pub level: EvidenceLevel,
    pub source: String, pub observation: String, pub supports: bool, pub quality: DataQuality }
pub struct EvidenceConflict { pub protocol_a: Option<Protocol>, pub protocol_b: Option<Protocol>,
    pub description: String }
pub struct ProtocolAnalysis { pub status: AnalysisStatus, pub primary_candidate: Option<Protocol>,
    pub candidates: Vec<ProtocolCandidate>, pub evidence: Vec<ProtocolEvidence>,
    pub conflicts: Vec<EvidenceConflict>, pub explanation: String,
    pub limitations: Vec<String>, pub analyzed_at_elapsed_ms: i64,
    pub algorithm_version: String }
pub struct DeviceProfile { pub model: Option<String>, pub soc_name: Option<String>,
    pub android_version: Option<String>, pub root_available: bool,
    pub verified_paths: Vec<String>, pub supported_protocols: Vec<Protocol>,
    pub note: Option<String> }
pub struct ChargingMetrics { pub sample_count: i32, pub duration_ms: i64,
    pub soc_rate_percent_per_min: Option<f64>, pub avg_battery_power_uw: Option<i64>,
    pub peak_battery_power_uw: Option<i64>, pub avg_temperature_decic: Option<i32>,
    pub stale_ratio: f64 }
```

## 3. UniFFI 导出函数（`cs-ffi`，Kotlin 可见）

```rust
#[uniffi::export] fn algorithm_version() -> String;
#[uniffi::export] fn normalize_source_record(
    raw: String, field: String, source_path: String, side: Side,
    timestamp_elapsed_ms: i64) -> SourceRecord;
#[uniffi::export] fn compute_metrics(snapshots: Vec<ChargingSnapshot>) -> ChargingMetrics;
#[uniffi::export] fn analyze(snapshots: Vec<ChargingSnapshot>,
    profile: Option<DeviceProfile>) -> ProtocolAnalysis;
```

生成配置：`native/crates/cs-ffi/uniffi.toml` → Kotlin 包名 `uniffi.chargescope`。
Kotlin 侧只允许通过 `RustBridge.kt` 访问生成绑定，禁止 UI 直接引用 `uniffi.chargescope.*`。

## 4. 证据等级含义（与规格书 §2.2 对齐）

- L4：驱动/控制器明确报告协议名称或握手结果，且接口语义已验证 → 可 `CONFIRMED`
- L3：输入侧电压/电流、动态调压、协商状态 → 强推断
- L2：功率曲线、温度、SOC、充电速率 → **不得单独确认为具体协议**
- L1：机型/SoC/充电 IC/支持能力 → 仅缩小候选范围

初始权重仅用于原型排序（L4=100 / L3=60 / L2=25 / L1=10），**不是概率**，UI 禁止直接显示为百分比概率。

## 5. 结果状态判定（P3 实现依据）

1. 有已验证 L4 且无冲突 → `CONFIRMED`
2. L3 + L2 独立证据一致 → `HIGH_CONFIDENCE`
3. 仅有 L1 / L2 兼容特征 → `POSSIBLE`
4. 多协议得分接近且无 L3/L4 → `INDETERMINATE`
5. 有数据但无可用证据 → `UNKNOWN`
6. 无任何采集通道（无 Root、无公开 API 读数、无设备档案）→ `UNSUPPORTED`

## 6. 禁止事项（写入 CI 检查人工复核清单）

- 不得写任何 sysfs 节点；不得执行除 `su`、`cat`、`ls`、`getprop` 白名单外的命令
- 不得编造 sysfs 路径；K70 Pro 档案 `verified_paths` 初始为空，实测后才有内容
- 不得把 `input_current_limit` / `input_voltage_limit` / `constant_charge_current` 当作实测输入值
- 不得仅凭功率峰值、温度或 SOC 斜率确认协议
