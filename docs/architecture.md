# ChargeScope 架构说明

## 分层与职责

```
Compose UI (ui/*)
   │ StateFlow 订阅，不在 Composable 内做阻塞 I/O
   ▼
Foreground Service (service/SamplingForegroundService)
   │ 协程调度，批量写库，窗口化分析
   ├── BatteryApiCollector ── Android 公开 API（无 Root）
   ├── RootBridge ── su 只读白名单（可选）
   └── RootSysfsCollector ── 动态扫描 power_supply
   ▼
Repository + Room ── 会话/快照/证据/设备档案持久化
   ▼
RustBridge ── 唯一 Kotlin↔UniFFI 边界
   ▼
Rust Core ── cs-model / cs-core / cs-collector / cs-protocol / cs-device / cs-analyzer / cs-ffi
```

## Rust crates

| crate | 职责 | 依赖 |
|---|---|---|
| `cs-model` | 冻结数据模型、serde、UniFFI 类型 | serde, uniffi |
| `cs-core` | 单位换算、功率、SOC 速率、stale、范围门禁 | cs-model |
| `cs-collector` | sysfs 字段语义与原始值解析（纯函数） | cs-model, cs-core |
| `cs-protocol` | 证据权重、候选排序、冲突、状态判定 | cs-model, cs-core |
| `cs-device` | 设备能力档案匹配（不含伪造路径） | cs-model |
| `cs-analyzer` | 编排 metrics + evidence → ProtocolAnalysis | 上述全部 |
| `cs-ffi` | UniFFI 导出层，保持薄 | cs-analyzer 等 |

## 数据流约束

1. **保留原始采集记录**：每个 `SourceRecord` 记录 `field / source / source_path / raw_value / normalized_value / unit / side / quality / timestamp`。
2. **缺失值用 `null`**：Rust `Option<T>`，Kotlin `T?`。
3. **侧别隔离**：`battery_voltage_uv` 等与 `input_voltage_uv` 等分开；`input_current_limit` 等配置值不是实测输入。
4. **窗口化分析**：服务用最近 `MAX_WINDOW=120` 个快照调用 Rust，不重算整段历史。
5. **批量写库**：`DB_BATCH_SIZE=12`，避免高频小事务；采集/分析/存储与 UI 解耦。
6. **Root 只读**：固定命令模板 + 路径白名单；不拼接未校验字符串；超时 2s；输出上限 64k 字符。

## 分析管线

```
snapshots + profile
   → fill_missing_battery_power（仅在单位/符号已知时）
   → compute_metrics（功率、SOC 速率、stale 比例，过滤重复样本）
   → collect_evidence（L1 能力 / L4 驱动报告 / L3 输入侧电气 / L2 行为）
   → rank_candidates（初始权重，仅排序）
   → detect_conflicts（冲突 L4）
   → decide_status（保守判定）
   → ProtocolAnalysis（状态 + 候选 + 证据 + 冲突 + 限制 + 算法版本）
```

## 为什么 Rust 不直接返回协议名

规格书要求结果必须包含：候选协议、状态、证据项、反证/冲突、置信等级、数据质量、无法判断原因。单一协议名无法表达这些，也无法满足「证据不足时输出 UNKNOWN/INDETERMINATE」的要求。

## 前台服务

- `foregroundServiceType="dataSync"`，常驻通知，请求周期 1–30 秒（默认 3 秒，实时 1 秒）。
- 采集周期只是**应用请求值**，不代表硬件每次都产生新读数；stale 比例用于观察。
- 不申请 INTERNET 权限，全部本地处理。
