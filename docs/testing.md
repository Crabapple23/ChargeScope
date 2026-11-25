# 测试策略与自动化

## Rust（已编写，待运行）

```bash
cd native && cargo test --workspace --all-targets
```

覆盖：
- 单位换算：`µA × µV → µW`、正负电流、缺失值、异常范围
- SOC 速率：单调时钟、时间回退、越界 SOC、重复样本不产生伪速率
- stale 检测与质量门禁
- sysfs 解析：尾随单位/换行、垃圾字符串、未知字段保留原始值
- 限制字段（`input_current_limit` 等）不进入输入功率
- 协议证据：L4 直接报告确认、冲突 → INDETERMINATE、L2-only 不确认、无数据 UNKNOWN/UNSUPPORTED
- 跨 crate 集成：`native/crates/cs-analyzer/tests/smoke.rs`

## Kotlin（已编写，待运行）

```bash
cd android && ./gradlew testDebugUnitTest
```

覆盖：缺失值为 null 不伪装 0、来源记录保留、状态标签不含概率、证据等级顺序。

## 集成测试（待补）

- UniFFI 数据传递（生成绑定后验证 `RustBridge` 映射往返）
- Room 持久化（会话关闭、快照批量写、证据 JSON 往返）
- 前台服务前后台切换（需真机）

## 构建测试

- 首期 ABI：arm64-v8a（必须）
- x86_64（仅当计划支持模拟器时加入）
- CI 见 `.github/workflows/android.yml`

## 真机验证矩阵

见 `docs/device-compatibility.md`。协议识别的真值必须来自可信外部证据，不得用应用自身算法输出。

## 关键验收问题（发布前逐项确认）

- [ ] 无 Root 时应用仍启动并提供有意义数据
- [ ] 任何缺失/无权限字段安全降级，不崩溃
- [ ] 每个数值有单位、来源、侧别与质量状态
- [ ] 严格区分电池侧功率与 USB 输入功率
- [ ] 不把设备支持能力误报为当前协商协议
- [ ] 证据不足时输出 UNKNOWN / INDETERMINATE
- [ ] Root 路径全部只读
- [ ] 采集与分析不阻塞 UI
- [ ] Kotlin-Rust FFI 有稳定契约与测试
- [ ] APK 可在目标真机安装、启动并完成一次会话记录
