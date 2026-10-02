# BatteryDetector

一个 Android 电量监控与局域网预警应用。定时读取本机电量，在电量低于阈值时通过
Webhook 或局域网广播发出预警；接收端设备会以系统通知的形式弹出告警。

## 功能

- **电量监控**：按设定间隔读取电量、电压、温度、充电状态。标准 API 与 root 两种来源。
- **局域网同步**：UDP 广播 + 同端口 HTTP 监听，同一 Wi-Fi 下的设备互相预警。
- **预警推送**：支持自定义 HTTP Webhook（Bark、Telegram 等）。
- **HyperOS 超级岛**：在小米 HyperOS 上把预警显示为焦点通知（超级岛）。
- **实况通知**：Android 16+ 上把收到的预警显示为进度卡片，进度即对方设备电量。
- **后台保活**：前台服务 + 看门狗闹钟，被杀后可自动恢复。

## 环境要求

| 项 | 版本 |
|---|---|
| compileSdk / targetSdk | 37 |
| minSdk | 30（Android 11）|
| JDK | 11+ |
| AGP | 9.4.1 |
| Kotlin | 2.2.10 |

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release（需要先配置签名，见下）
./gradlew assembleRelease
```

> 若在 AGP 9 上遇到 `error writing value of type 'org.gradle.api.internal.provider.DefaultProperty'`，
> 说明配置缓存与依赖不兼容，加上 `--no-configuration-cache`，或在
> Android Studio 的 Settings → Build Tools → Gradle 中关闭 configuration cache。

## 签名（发布前必做）

仓库**不包含签名配置**，`local.properties` 也不入库。首次发布需要在
Android Studio 里执行 **Build → Generate Signed App Bundle / APK → Create new…**
新建 keystore：

- **有效期填 25 年以上**
- **务必备份 keystore 文件与密码** —— 丢失后将无法再更新已上架的应用

## 关于 R8

release 使用标准 R8（混淆 + 资源收缩），但**刻意关闭了 whole-program 优化**。
开启后曾导致每次启动崩溃：

```
java.lang.IllegalAccessError: Class kotlin.sequences.d extended by class y41 is inaccessible
  at kotlinx.coroutines.m.b
```

原因是该优化重写了 `kotlinx.coroutines` 的内部类层次。keep 规则位于
`app/src/main/keepRules/rules.keep`，其中最关键的一条是保留
`PrivilegedServiceImpl` —— Shizuku 以类名反射实例化它，被改名会导致
超级岛功能**仅在 release 下失效**。

## 目录结构

```
app/src/main/java/com/blackback/batterydetector/
├── data/        偏好设置、数据模型、日志仓库
├── network/     局域网同步引擎、Webhook 推送
├── root/        root 方式读取电量
├── service/     前台服务、开机广播、看门狗
├── shizuku/     Shizuku 用户服务与超级岛通知
├── ui/          Compose 界面（首页 + 设置页）
└── utils/       厂商保活跳转、Shizuku 命令封装
```

## 权限说明

- `RECEIVE_BOOT_COMPLETED` —— 开机后恢复后台服务
- `POST_NOTIFICATIONS` —— 弹出预警通知
- `FOREGROUND_SERVICE` —— 常驻电量监控
- Shizuku（可选）—— 用于超级岛通知的网络绕行，非必需

## 许可

未指定。
