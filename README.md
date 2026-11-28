# 充了个电 (ChargeScope)

面向 Android 的充电状态监测与快充协议证据分析工具。采用纯 Kotlin + Jetpack Compose 开发，**无联网权限、纯本地运行、只读监测不干预充电**。

---

## 核心原则

- **缺失不伪装**：无法读取的指标统一显示 `—`，不用 `0` 伪装。
- **证据驱动的协议判断**：按证据等级分层（`L4 驱动报告 > L3 输入侧电参 > L2 功率特征 > L1 品牌匹配`）。仅当内核驱动明确报告协议名（L4，需 Shizuku 或 Root 只读 sysfs）时才标记「已确认」；输入侧高压仅作「可能」参考；单凭功率、温度或电量不会确认协议。
- **只读与最小权限**：无 `INTERNET` 权限；特权通道默认关闭，仅执行固定只读探测（`/sys/class/power_supply` 与系统充电服务），不写入任何系统节点；权限仅在首次引导页或实际需要时按需申请。
- **绝不干预充电**：安全阈值仅用于自动停止后台采样记录，不会修改系统充电电流或通断策略。

---

## 主要功能

- **实时监测**：可折叠电池卡片（默认展示电量、状态、电池侧功率、温度、系统驱动健康度、预计充满时间，展开查看电压/电流/输入功率/转换效率/估算健康/设计容量/可用上限容量）及蓝/橙高对比实时功率曲线。
- **双电芯设备模式**：在引导页与数据页提供 2S 串联双电芯开关，开启后全链路按双电芯算法换算功率、曲线与等效容量；关闭时自动检测双电芯特征并弹窗询问。
- **快充协议分析与报告导出**：结论主卡（状态胶囊、驱动/预测双指标块、规格参考框）、标签化分析摘要、候选协议与逐协议档位卡片、`L4–L1` 分色证据徽章；长按结论卡可免权限生成 1080px 高清 PNG 报告图直存系统相册 `Pictures/ChargeScope/`。
- **协议识别存档**：出现「已确认 / 可能 / 证据冲突」结果时自动存档，拔出充电器时写入峰值；支持双击「协议」Tab 手动存档，长按支持编辑备注、保存报告图片、保存并分享及单条删除。
- **后台采样与安全阈值**：支持手动记录与插电自动记录，内置温度/时长/电量/文件体积四项阈值，兼容 Android 15 `dataSync` 前台服务超时优雅停服；会话分片 GZIP 压缩存储，支持 Android 11+ SAF 快捷导出目录。
- **特权增强（可选，只读）**：支持 **Shizuku**（授权后自动触发连接检测）与 **Root**（Magisk / KernelSU / APatch），提供「一键深度扫描」与按重要度排序的逐节点折叠键值表。
- **双安装包与个性化**：
  - **标准版（`standard`）**：零第三方 UI 依赖，内置 **Material 3** 与 **MIUIX（轻量风味）** 风格；
  - **MIUIX 完整版（`miuix`）**：独立包名（可与标准版共存），引入开源 `miuix-android` 原生组件库；
  - 支持浅色 / 深色 / 跟随系统 / **定时切换深浅色**、莫奈动态取色、OLED 纯黑、悬浮液态玻璃底栏、4 档字号、预测性返回动画及中英双语。

---

## 目录结构

```text
android/app/src/
├── main/java/com/chargescope/app/
│   ├── MainActivity.kt              # 入口 Activity 与主导航壳
│   ├── SamplingService.kt           # 前台采样服务与安全阈值监控
│   ├── core/
│   │   ├── Model.kt                 # 不可变数据模型与 UiState
│   │   ├── Analyzer.kt              # 协议证据分层分析与电池算法
│   │   ├── Collector.kt             # 公开 BatteryManager 采集 + RootScanner
│   │   ├── Prefs.kt                 # SharedPreferences 持久化
│   │   ├── Recorder.kt              # 会话分片 GZIP CSV 记录器
│   │   ├── Sampler.kt               # 采样状态机与存档协调器
│   │   └── Storage.kt               # AppLog / Archive / Report 文本日志
│   ├── export/
│   │   └── ReportImage.kt           # PNG 报告卡片渲染、MediaStore 相册保存与分享
│   ├── privileged/
│   │   ├── Shizuku.kt               # Shizuku 通道桥接与自动重连
│   │   └── ShellService.kt          # Shizuku UserService 只读采集端
│   └── ui/
│       ├── UiCommon.kt              # 通用自适应组件与格式化工具
│       ├── theme/MiuixTheme.kt      # MIUIX 主题配色、底栏与液态玻璃底栏
│       ├── screens/
│       │   ├── OnboardingScreen.kt  # 4 步首次引导页
│       │   └── SettingsScreen.kt    # 软件设置页与 AppPrefs/AppSettings
│       └── tabs/
│           ├── RealtimeTab.kt       # 实时监测页
│           ├── ProtocolTab.kt       # 协议分析页
│           ├── ArchiveTab.kt        # 识别存档页
│           ├── DataTab.kt           # 数据与特权配置页
│           └── DeviceTab.kt         # 设备信息与节点明细页
├── standard/java/com/chargescope/app/MiuixLibBridge.kt  # 标准版零依赖桥接
├── miuix/java/com/chargescope/app/MiuixLibBridge.kt     # MIUIX 完整版原生组件桥接
└── test/java/com/chargescope/app/                       # 纯 JVM 单元测试
```

---

## 下载与安装

- **正式签名版（推荐）**：前往 [GitHub Releases](https://github.com/Crabapple23/ChargeScope/releases) 下载：
  - `ChargeScope-v*.apk`（标准版，`com.chargescope.hanhan.app`）
  - `ChargeScope-v*-miuix.apk`（MIUIX 完整版，`com.chargescope.hanhan.app.miuix`，可与标准版共存）
- **开发测试版**：前往 [GitHub Actions](https://github.com/Crabapple23/ChargeScope/actions) 下载对应 Debug Artifact。

> 系统要求：Android 8.0（API 26）及以上。从 Debug 版切换至正式签名版时需先卸载旧版，正式版之间可直接覆盖升级。

---

## 本地构建与测试

环境要求：JDK 17、Android SDK 35。

```bash
cd android
./gradlew assembleStandardDebug assembleMiuixDebug     # 构建标准版与 MIUIX 完整版 Debug APK
./gradlew testStandardDebugUnitTest testMiuixDebugUnitTest  # 运行单元测试
```

### 云端自动构建与发包

- 推送到 `main` 分支：由 [.github/workflows/android.yml](.github/workflows/android.yml) 自动构建标准版 + MIUIX 完整版 Debug APK 并运行单元测试。
- 推送 `v*` 标签（如 `v1.1.0`）：由 [.github/workflows/release.yml](.github/workflows/release.yml) 运行单元测试、签名构建双 Release APK，并自动发布到 GitHub Releases。
