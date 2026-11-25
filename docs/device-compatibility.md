# 设备兼容性与测试记录

## 原则

- 协议列表是研究与适配目标，**不代表首期均可被识别**。
- 设备适配必须有可复现测试记录。任何 sysfs 路径只有经过实测才能进入 `verified_paths`。
- **不得凭猜测合并规则**。当前 `cs-device` 中 K70 Pro 的 `verified_paths` 故意留空。

## 首期目标设备

Redmi K70 Pro / Snapdragon 8 Gen 3 / HyperOS 3 / Root 可选。

## 每次测试必须记录

| 项目 | 说明 |
|---|---|
| 设备型号 | `Build.MODEL` |
| SoC | `Build.HARDWARE` / 已知芯片 |
| Android / 系统版本 | `Build.VERSION.RELEASE`、HyperOS 版本 |
| 充电器 | 品牌、型号、额定输出、协议规格 |
| 线材 | 型号/标称能力；无法确认时注明 |
| 连接状态 | 插入/拔出/重连时间 |
| Root 状态 | 无 Root / 已授权 / 授权失败 |
| 采集节点 | 完整路径 + 原始字符串 + 单位 + 语义判断依据 |
| 原始日志 | 从应用导出的 CSV/JSON |
| 人工确认依据 | 控制器/驱动报告、专用设备或厂商诊断信息；**不能用本应用算法输出当真值** |

## 测试矩阵

| 测试条件 | 必须记录 |
|---|---|
| 无 Root / Root | 权限状态、可见字段、失败原因 |
| 不同充电器 | 品牌型号、额定输出、协议规格 |
| 不同线材 | 型号/标称能力；无法确认时注明 |
| 不同 SOC 区间 | 低、中、高 SOC 充电曲线 |
| 不同温度 | 电池温度与系统热状态 |
| 插拔与重连 | 事件时间、采集恢复时间、状态一致性 |
| 屏幕开关/后台 | 采样连续性、服务状态、耗电与通知 |

## K70 Pro 实测记录模板（尚未填写）

```
设备型号：
SoC / 系统版本：
充电器 / 线材：
Root 状态：
实测日期 / 测试人：

已验证节点（路径 | 原始值示例 | 单位 | 侧别 | 语义依据 | 是否可确认协议）：
（留空直到实测）

未验证/不支持节点：
（列出尝试过但不可读或语义不明的节点）

结论：
（哪些协议仅能作为候选，哪些有 L4 直接证据）
```

## 候选属性（均需逐设备验证语义）

```
type, status, online, present, capacity,
voltage_now, current_now, current_avg, power_now,
voltage_max, current_max, usb_type, real_type, charge_type,
temp, input_current_limit, input_voltage_limit,
constant_charge_current, constant_charge_voltage, charge_counter
```

⚠️ `input_current_limit` / `input_voltage_limit` / `constant_charge_current` 等通常是**限制/配置值**，不等于实时输入电流/电压。不同驱动可能使用不同单位、符号和测量侧别。

## 当前已知限制

- 无 Root 时 `input_*` 全部为 null，只能基于电池侧行为给出 POSSIBLE/INDETERMINATE/UNKNOWN。
- 尚无经实测验证的设备档案，L1 候选能力未启用。
- 协议识别基准标签须来自外部可信证据，不能用应用自身输出反向充当真值。
