# ChargeScope 数据字典

权威定义见 `CONTRACT.md` v0.1.0。本文件说明每个字段的来源、单位、侧别与质量语义。

## ChargingSnapshot

| 字段 | 类型 | 单位 | 侧别 | 来源 | 缺失语义 |
|---|---|---|---|---|---|
| `timestampElapsedMs` | Long | ms | — | `SystemClock.elapsedRealtime()` | 必有；速率计算只用此单调时钟 |
| `wallClockTimeMs` | Long | ms | — | `System.currentTimeMillis()` | 必有；仅展示/会话边界 |
| `socPercent` | Int? | % | 电池 | `EXTRA_LEVEL/EXTRA_SCALE` | null=无此 API |
| `batteryVoltageUv` | Long? | µV | 电池 | `EXTRA_VOLTAGE`×1000 | null=不可用/越界 |
| `batteryCurrentUa` | Long? | µA | 电池 | `BATTERY_PROPERTY_CURRENT_NOW` | null=设备不提供；保留符号 |
| `batteryPowerUw` | Long? | µW | 电池 | Rust 计算 `V×I` | null=单位或方向未校准 |
| `batteryTemperatureDecic` | Int? | 0.1°C | 电池 | `EXTRA_TEMPERATURE` | null=不可用/越界 |
| `inputVoltageUv` | Long? | µV | 输入 | Root sysfs（需验证） | null=无输入侧实测 |
| `inputCurrentUa` | Long? | µA | 输入 | Root sysfs（需验证） | null=无输入侧实测 |
| `inputPowerUw` | Long? | µW | 输入 | Root sysfs（需验证） | null=无输入侧实测 |
| `usbType` / `chargeType` | String? | — | 输入 | API / sysfs | null=接口不存在 |
| `chargingStatus` | String? | — | 电池 | API | null=未知状态 |
| `sourceRecords` | List | — | 混合 | 全通道 | 可空列表 |
| `rootAvailable` | Boolean | — | — | Root 探测 | false=无 Root |

## SourceRecord

| 字段 | 含义 | 枚举/示例 |
|---|---|---|
| `field` | 采集字段原名称 | `voltage_now` / `soc_percent` |
| `source` | 通道 | ANDROID_API / SYSFS / ROOT_SHELL / DEVICE_ADAPTER |
| `sourcePath` | API 名或 sysfs 路径 | `/sys/class/power_supply/battery/voltage_now` |
| `rawValue` | 原始字符串/数值 | `"4000000"` |
| `normalizedValue` | 标准化值（字符串保留精度） | `"4.0"` |
| `unit` | 单位 | uV / uA / uW / deciC / percent |
| `side` | 侧别 | BATTERY_SIDE / INPUT_SIDE / UNKNOWN_SIDE |
| `quality` | 质量 | VALID / STALE / OUT_OF_RANGE / PARSE_ERROR / PERMISSION_DENIED / MISSING |
| `timestampElapsedMs` | 单调时间戳 | Long |

## 质量枚举语义

| 值 | 何时使用 |
|---|---|
| VALID | 原始值存在、解析成功、在合理范围 |
| STALE | 读取超时或样本明显过期 |
| OUT_OF_RANGE | 数值超出物理合理范围（见 `cs-core::bounds`） |
| PARSE_ERROR | 原始字符串无法解析 |
| PERMISSION_DENIED | Root 路径读取被拒绝 |
| MISSING | 接口不存在或未返回 |

## 协议分析结果

`ProtocolAnalysis` 包含：`status`（六态）、`primaryCandidate`（可空）、`candidates`（排序分不是概率）、`evidence`（L1–L4、supports/反证、质量）、`conflicts`、`explanation`、`limitations`、`analyzedAtElapsedMs`、`algorithmVersion`。

## 导出

- **CSV**：快照表，每行一个 `ChargingSnapshot`，`source_records_json` 列保存原始记录（默认脱敏）。
- **JSON**：`schemaVersion` + 导出时间 + 脱敏标记 + 设备信息 + 快照数组 + 完整分析对象。
- 导出前 UI 必须显示隐私提示；默认脱敏路径中的数字 ID。
