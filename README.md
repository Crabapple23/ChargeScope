# ChargeScope

Android 充电状态与快充协议证据分析工具。纯 Kotlin + Jetpack Compose，无第三方运行时依赖，本地处理，无联网权限。

## 原则
- 缺失值显示“—”，不用 0 伪装。
- 协议只在驱动明确报告名称（L4，需 Root 读 sysfs）时才“确认”；输入侧高压仅为“可能”；功率/温度/SOC 不能确认协议。
- Root 默认关闭，仅通过固定脚本只读 `/sys/class/power_supply`，不写任何节点。

## 构建
推送到 `main` 即由 GitHub Actions 构建，APK 在运行页的 Artifacts（`chargescope-debug`）。
本地：JDK 17 + Android SDK 35，`cd android && ./gradlew assembleDebug`。
