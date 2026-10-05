# BatteryDetector

<img src="icon.png" width="128" alt="BatteryDetector">

一个 Android 电量监控应用。定时读取本机电量，在电量低于阈值时通过
Webhook 或局域网广播发出预警；接收端设备会以系统通知的形式弹出告警。

## 功能

- **电量监控**：按设定间隔读取电量、电压、温度、充电状态。标准 API 与 root 两种来源。
- **多种通知渠道**：可用局域网、Webhook、SMTP等多种推送方式。通知样式亦可自定义。

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

## 关于 R8

release 使用标准 R8（混淆 + 资源收缩），但**刻意关闭了 whole-program 优化**。
开启后曾导致每次启动崩溃：

```
java.lang.IllegalAccessError: Class kotlin.sequences.d extended by class y41 is inaccessible
  at kotlinx.coroutines.m.b
```

原因是该优化重写了 `kotlinx.coroutines` 的内部类层次。
