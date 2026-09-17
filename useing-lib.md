# 依赖清单

记录项目中实际使用到的库与原生 API。每次引入新依赖（无论是代码引用的包，还是处理文件时为读取而安装的工具）都要更新本文件。

## src/AirPodsBleScan / src/AirPodsBleTray

**第三方库：无。** 用 WinRT 的蓝牙广播 API，通过把 `TargetFramework` 设为 `net9.0-windows10.0.19041.0` 获得投影，不需要额外的 NuGet 包（`Microsoft.Windows.SDK.Contracts` 只在较老的 SDK 上才需要）。

**WinRT API：**

| 类型 / 成员 | 用途 |
| --- | --- |
| `Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher` | 监听 BLE 广播。注意 `AllowExtendedAdvertisements` 必须显式打开，否则蓝牙 5 扩展广播会被静默丢弃，一条都收不到 |
| `BluetoothLEAdvertisement` / `BluetoothLEManufacturerData` / `BluetoothLEAdvertisementDataSection` | 读取厂商数据与原始 AD 结构 |
| `Windows.Devices.Enumeration.DeviceInformation.FindAllAsync` + `BluetoothLEDevice.GetDeviceSelector()` | 启动时的自检：确认 WinRT 蓝牙接口可用 |

**运行时自带：** `Windows.Storage.Streams.DataReader` 把 `IBuffer` 读成 `byte[]`。

## android/

**第三方库：无额外引入。** 只用 AndroidX 与 Kotlin 官方组件，版本沿用工作区里 `huami-step-app` 那套已验证的组合：

| 依赖 | 用途 |
| --- | --- |
| `androidx.core:core-ktx` / `activity-compose` | Activity 与 Compose 基础 |
| `androidx.compose:compose-bom` + `material3` | 界面 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 把 AAP 探测放到后台线程 |

**安卓平台 API（无需依赖）：**

| API | 用途 |
| --- | --- |
| `android.bluetooth.le.BluetoothLeScanner` | 扫描 BLE 广播。`targetSdk` 为 26 时走旧版权限模型，需要运行时授予 `ACCESS_FINE_LOCATION` |
| `android.bluetooth.BluetoothDevice#createInsecureL2capSocket(int)` | **隐藏 API**。联系 AirPods 私有 AAP 通道（PSM `0x1001`）的唯一途径，属于 `max-target-o` 级别，因此本项目 `targetSdk = 26` |
| `android.app.Service` + `NotificationChannel` | 前台服务与常驻通知 |
| `android.appwidget.AppWidgetProvider` | 桌面小组件 |
| `java.lang.reflect` | 调用上面那个隐藏 API |

**构建环境：** JDK 17（`C:\Program Files\BellSoft\LibericaJDK-17`）、Android SDK（`C:\Users\Summer\Android\Sdk`，含 android-35 与 build-tools 35.0.0）、Gradle wrapper 指向腾讯云镜像。
