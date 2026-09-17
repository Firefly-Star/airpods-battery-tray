# AirPods 电量

在非苹果设备上显示 AirPods 的电量（左耳 / 右耳 / 充电盒）。

| 平台 | 形态 | 位置 |
| --- | --- | --- |
| Windows | 系统托盘图标 + 悬停提示 | [`src/AirPodsBleTray/`](src/AirPodsBleTray) |
| Android | 常驻通知 + 桌面小组件 | [`android/`](android) |

两个平台走的是同一条技术路线：**解析 AirPods 的 BLE 广播**，不连接、不配对、不需要 root / 管理员、不写驱动。

## 为什么需要它

Windows 只会为支持标准 BLE 电池服务（`0x180F`）的设备显示电量，而 AirPods 用的是苹果私有协议，所以「设置 → 蓝牙和其他设备」里永远看不到它的电量。这个工具补上这个缺口。

## 原理

AirPods 会通过 BLE 广播苹果的「临近配对」报文（Apple Continuity，类型 `0x07`），电量就在里面。工具做四件事：

1. 用 `BluetoothLEAdvertisementWatcher` 监听 BLE 广播
2. 按 27 字节的定长结构解析报文（字段布局见 [`AirPodsAdvertisement.cs`](src/AirPodsBleTray/AirPodsAdvertisement.cs)）
3. 每只耳机各自广播，且 LE 地址每隔几分钟会轮换一次，所以用「**型号一致 + 电量变化不超过 1 档 + 信号差不超过 50 dBm**」三重校验判断是不是同一副耳机
4. 扫描器会静默停止（Windows 的已知问题），所以有一个看门狗在检测到停止后 3 秒自动重启它

全程**不连接、不配对、不需要管理员权限、不写驱动**。

## 下载

到 [Releases](../../releases) 页面，两个文件二选一：

| 文件 | 下载体积 | 需要先装 .NET 吗 |
| --- | --- | --- |
| `AirPodsBleTray.exe` | 54 MB | **不需要**，双击直接运行 |
| `airpods-battery-tray-framework-dependent.zip` | **6 MB** | 需要 [.NET 9 桌面运行时](https://dotnet.microsoft.com/download/dotnet/9.0) |

两者功能完全相同，差别只在于有没有把 .NET 运行时打进包里。

## 用法

运行后托盘出现一个充电盒图标。**鼠标悬停**显示电量，**右键**可以查看当前收到的原始广播数据、打开日志目录或退出。

日志写在 `%LOCALAPPDATA%\AirPodsBleTray\advertisements.log`。

程序不会开机自启，也不会写注册表。想自启的话，把快捷方式放进 `shell:startup` 即可。

## 从源码构建

需要 .NET 9 SDK。

```bash
git clone https://github.com/Firefly-Star/airpods-battery-tray.git
cd airpods-battery-tray

# 构建全部
dotnet build

# 调试运行
dotnet run --project src/AirPodsBleTray

# 发布单文件自包含版本
dotnet publish src/AirPodsBleTray -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true \
  -o publish-single
```

## 项目结构

| 路径 | 说明 |
| --- | --- |
| `src/AirPodsBleTray/` | Windows 端产品本体，托盘程序 |
| `src/AirPodsBleScan/` | Windows 端诊断工具。当托盘显示「暂无广播」时，用它区分是扫描器挂了还是耳机确实没在广播 |
| `android/` | 安卓端，常驻通知 + 桌面小组件 |
| `docs/findings.md` | **技术事实记录**——两个平台蓝牙栈里那些"静默失败"的行为，以及苹果广播协议的细节 |
| `docs/l2cap-dead-end.md` | 为什么拿不到精确电量——苹果私有 AAP 协议在 Windows 用户态、以及安卓未 root 时都走不通 |
| `docs/android-notes.md` | 安卓端的排查过程，重点是**什么没用**（全收扫描、放宽信号门槛、改 PHY 都无效） |
| `useing-lib.md` | 依赖与所用原生 API 的清单 |

## 构建安卓端

需要 JDK 17 与 Android SDK（`android/local.properties` 里配 `sdk.dir`）。

```bash
cd android
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 正式包，需要 keystore.properties
```

`keystore.properties` 与 `*.keystore` 已 gitignore，格式见 `android/app/build.gradle.kts`。

## 已知限制

- **只有耳机广播时才读得到数据。** 戴着听歌时通常不广播，所以经常显示「暂无广播」。这是协议行为，不是程序故障。开盖、或把耳机从耳朵里取出来时会恢复。
- **电量精度是 10% 一档。** 协议里每个电量字段只有 4 bit（`0-9` → 0-90%，`A-E` → 100%），想要苹果设备上那种精确到个位的数值是做不到的——那走的是私有 AAP 协议，原因见 [`docs/l2cap-dead-end.md`](docs/l2cap-dead-end.md)。
- **同型号耳机的误认风险。** 无法从广播里做密码学身份绑定（需要的密钥同样只能从 AAP 拿到），只能用上述三重校验来降低风险。旁边若有电量恰好相同的同型号 AirPods，理论上可能串。
- **仅支持 Windows。** BLE 广播部分依赖 WinRT API。macOS 和 Linux 有其它现成方案。

## 致谢

报文的字段布局参考了 [AirPodsDesktop](https://github.com/SpriteOvO/AirPodsDesktop)（GPLv3）—— 这是一个纯用户态、不需要驱动的实现思路，本项目的解析逻辑据此重写。

## 许可

[GPLv3](LICENSE)
