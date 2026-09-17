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
