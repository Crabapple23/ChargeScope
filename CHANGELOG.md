# 变更日志

## 0.1.0-P0 (2026-11-24)

- 冻结数据模型与 FFI 契约 v0.1.0（`docs/CONTRACT.md`）
- 建立 Kotlin + Compose Gradle 工程与 Rust 7-crate workspace
- UniFFI 最小调用链：`algorithm_version` / `normalize_source_record` / `compute_metrics` / `analyze`
- 无 Root 电池采集 + 可选 Root 只读 sysfs 动态扫描
- Rust 分析引擎：单位换算、功率/SOC 速率、质量门禁、L1–L4 证据、候选排序、冲突与保守状态判定
- Compose MVP 五页面 + 前台采样服务
- Room 持久化、DataStore 设置、CSV/JSON 脱敏导出
- Rust/Kotlin 单元测试、CI 工作流、构建脚本、设备兼容模板、样例导出
- **状态**：源码落盘完成；因本机工具链限制，未在本机编译/打包/真机验证。详见 `PROGRESS.md`。
