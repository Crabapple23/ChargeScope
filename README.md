# 充了个电 (ChargeScope)

面向 Android 的充电状态监测与快充协议证据分析工具。采用纯 Kotlin + Jetpack Compose（Material 3）开发，**无联网权限、纯本地运行、只读监测不干预充电**。

---

## 核心原则

- **缺失不伪装**：无法读取的指标统一显示 `—`，不用 `0` 伪装。
- **证据驱动的协议判断**：按证据等级分层（`L4 驱动报告 > L3 输入侧电参 > L2 功率特征 > L1 品牌匹配`）。仅当内核驱动明确报告协议名（L4，需 Shizuku 或 Root 只读 sysfs）时才标记「已确认」；输入侧高压仅作「可能」参考；单凭功率、温度或电量不会确认协议。
- **只读与最小权限**：无 `INTERNET` 权限；特权通道默认关闭，仅执行固定只读探测（`/sys/class/power_supply` 与 `dumpsys battery`），不写入任何系统节点；权限不在启动时强制弹窗，仅在首次引导页或实际需要时按需申请。
- **绝不干预充电**：安全阈值仅用于自动停止后台采样记录，不会修改系统充电电流或通断策略。

---

## 主要功能

- **实时监测**：电量、充放电状态、连接方式、电池端电压/电流、估算电池侧功率、输入侧功率与估算转换效率、温度、健康状态/健康度百分比（`soh` 或 `charge_full / charge_full_design`）/循环次数、预计充满时间（优先 Android 9+ `BatteryManager.computeChargeTimeRemaining()`，不支持时回退 SOC 速率估算），以及实时功率曲线。
- **快充协议分析**：展示驱动报告协议、预测协议、参考规格上限、候选协议范围、分层证据列表与局限性说明。
- **协议识别存档**：出现「已确认 / 可能 / 证据冲突」结果时自动存档，拔出充电器时更新峰值；支持双击「协议」Tab 或按钮手动存档、长按添加/编辑备注（有备注的存档优先置顶展示备注内容）以及单条删除。
- **后台采样与安全阈值**：
  - 支持手动后台记录与连接充电器自动记录，状态栏常驻通知支持点击返回应用与快捷停止；
  - 支持温度上限、单次最长时长、电量上限、文件体积上限四项安全阈值，触发后自动停止记录并同步发出 App 内提示、Toast 与独立状态栏提醒；
  - 按会话分片采用 GZIP 压缩记录（`sessions/rec_*.csv.gz`），导出时自动流式合并解压为标准 `.csv`，单次异常中断不影响其他会话。
- **特权增强（可选，只读）**：
  - **Shizuku**（免 Root 推荐）：通过 `UserService` 直接读取 sysfs 与 `dumpsys battery`；
  - **Root**（Magisk / KernelSU / APatch）：复用单个持久 `su` 会话降低开销；
  - 支持「一键深度扫描」全量可读充电/Type-C/厂商节点，结果自动附带在诊断日志导出中。
- **个性化与首次引导**：
  - 首次进入提供 4 步引导（使用须知 → 通知权限 → 存储权限 → Shizuku/Root 可选配置），随时可在设置页重开；
  - 支持浅色 / 深色 / 跟随系统、Android 12+ 莫奈动态取色、OLED 纯黑背景、4 档全局字号、预测性返回动画及中英文切换。

---

## 下载与安装

- **正式签名版（推荐）**：前往 [GitHub Releases](https://github.com/Crabapple23/ChargeScope/releases) 下载最新的 `ChargeScope-v*.apk`。
- **开发测试版**：前往 [GitHub Actions](https://github.com/Crabapple23/ChargeScope/actions) 下载 `chargescope-debug` Artifact。

> 系统要求：Android 8.0（API 26）及以上。从 Debug 版切换至正式签名版时需先卸载旧版，正式版之间可直接覆盖升级。

---

## 本地构建与测试

环境要求：JDK 17、Android SDK 35。

```bash
cd android
./gradlew assembleDebug        # 构建 Debug APK
./gradlew testDebugUnitTest    # 运行单元测试
```

### 云端自动构建与发包

- 推送到 `main` 分支：由 [.github/workflows/android.yml](.github/workflows/android.yml) 自动构建 Debug APK 并运行单元测试。
- 推送 `v*` 标签（如 `v1.0.0`）：由 [.github/workflows/release.yml](.github/workflows/release.yml) 运行单元测试、使用仓库 Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）签名构建 Release APK，并自动发布到 GitHub Releases。
