# BatteryDetector

一个 Android 电量监控应用。定时读取本机电量，在电量低于阈值时通过
Webhook、SMTP 邮件或局域网广播发出预警；接收端设备会以系统通知的形式弹出告警。

## 功能

- **电量监控**：按设定间隔读取电量、电压、温度、充电状态。标准 API 与 root 两种来源。
- **局域网同步**：UDP 广播 + 同端口 HTTP 监听，同一 Wi-Fi 下的设备互相预警。
- **多通道推送**：Webhook（Bark / Telegram / Gotify / 自建服务）、SMTP 邮件、局域网广播。
  三个通道相互独立，已配置的都会发送，任一成功即视为送达。
- **实时通知**：Android 16+ 上把收到的预警渲染为进度卡片，进度即对方设备电量，
  在通知栏与锁屏直接可见，并被系统提升为实时通知（状态栏胶囊 / ColorOS 流体云）。
- **HyperOS 超级岛**：小米设备上可尝试把预警显示为焦点通知（超级岛），依赖 Shizuku 授权。
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

## SMTP 邮件推送

设置 → 预警与推送 → 邮件推送。固定使用**隐式 TLS**（465 端口），无需额外依赖，
由 JDK 的 `SSLSocket` 直接实现。

| 字段 | 说明 |
|---|---|
| SMTP 服务器 | 如 `smtp.qq.com`、`smtp.163.com`、`smtp.gmail.com` |
| 端口 | 默认 465 |
| 发信账号 | 完整邮箱地址 |
| 授权码 / 密码 | **服务商生成的授权码，不是登录密码** |
| 发件人 | 留空则使用发信账号 |
| 收件人 | 目标邮箱 |

配置完成后可用「发送测试邮件」验证。失败时提示框会给出服务器的原始回执，
便于定位是认证失败、地址被拒还是连接问题。

> QQ / 163 等需要在网页端先开启 SMTP 服务并生成授权码，直接填登录密码会认证失败。

## 通知与实时通知

- **实时通知**：Android 16 起，预警会被提升为系统实时通知。这需要声明
  `POST_PROMOTED_NOTIFICATIONS` 权限 —— 缺少它时 `setRequestPromotedOngoing(true)`
  会被系统静默忽略，通知照常出现在通知栏，但不会成为实时通知。
- **实况通知开关**：设置 → 通知与权限 →「使用实况通知」。
- **超级岛**：设置 → 通知与权限 →「使用小米超级岛（实验性）」。仅在小米
  HyperOS 3.0 及以上可用，其他设备会显示为不可用；依赖 Shizuku 授权，且需要
  「网络绕行」生效（发送瞬间切断 XMSF 网络使其鉴权放行）。该路径未经充分验证，
  可能失效。

## 关于 R8

release 使用标准 R8（混淆 + 资源收缩），但**刻意关闭了 whole-program 优化**。
开启后曾导致每次启动崩溃：

```
java.lang.IllegalAccessError: Class kotlin.sequences.d extended by class y41 is inaccessible
  at kotlinx.coroutines.m.b
```

原因是该优化重写了 `kotlinx.coroutines` 的内部类层次。keep 规则位于
`app/src/main/keepRules/rules.keep`，其中最关键的一条是保留
`PrivilegedServiceImpl` —— Shizuku 以类名反射实例化它，一旦被改名，超级岛功能
**只在 release 构建下失效**，debug 下却正常，极难排查。

## 权限说明

- `POST_NOTIFICATIONS` —— 弹出预警通知
- `POST_PROMOTED_NOTIFICATIONS` —— 将预警提升为系统实时通知（Android 16+）
- `FOREGROUND_SERVICE` —— 常驻电量监控
- `RECEIVE_BOOT_COMPLETED` —— 开机后恢复后台服务
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` —— 避免后台服务被休眠挂起
- Shizuku（可选）—— 用于超级岛通知的网络绕行，非必需

## 许可

MIT
