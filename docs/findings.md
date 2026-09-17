# 技术事实记录

做这个项目的过程中，从两个平台的蓝牙栈里挖出来的一些事实。共同点是：**它们几乎都会静默失败**——不报错、不抛异常，只是"没有结果"。不知道这些就会一直怀疑自己代码写错了。

## 蓝牙 socket（Windows，Winsock）

- **用户态无法使用 L2CAP。** `socket(AF_BTH, SOCK_STREAM, BTHPROTO_L2CAP)` 能成功返回句柄，但之后 **`connect()` 和 `bind()` 无差别返回 `10050 WSAENETDOWN`**——连本机适配器自己的 SDP 服务都失败。说明微软栈根本没有实现这条路径，能建 socket 只是因为协议选择子的符号在头文件里存在。详见 [l2cap-dead-end.md](l2cap-dead-end.md)。

- **必须用 `SOCK_STREAM`。** `SOCK_SEQPACKET` 返回 `10044 WSAESOCKTNOSUPPORT`。Linux/BlueZ 的示例一律用 SEQPACKET，照搬过来必踩。

- **`SOCKADDR_BTH` 必须 `[StructLayout(Pack=1)]`。** 它在 `ws2bth.h` 里被包在 `#include <pshpack1.h>` 区块内，`sizeof == 30`。不打包会变成 40，`connect()` 报 `10049`。

- **`WSAGetLastError()` 是线程局部的。** 如果为了加超时把 `connect()` 丢到工作线程，错误码必须在**那个线程上**立刻读取，回主线程再读拿到的是别的值。

- **L2CAP socket 上无法请求认证加密。** `SO_BTH_AUTHENTICATE` / `SO_BTH_ENCRYPT` / `SO_BTH_MTU` 全部返回 `10042 WSAENOPROTOOPT`。加密只能依赖已配对的 link key 自动生效。

- 不需要管理员权限。

## BLE 广播扫描

- **Windows：`AllowExtendedAdvertisements` 必须显式打开。** 不打开的话，蓝牙 5 扩展广播会被**静默丢弃**——一条都收不到，而且不报任何错。打开前后的差别是"0 条"和"几分钟内上千条"。

- **`BluetoothLEAdvertisementWatcher` 会静默停止工作。** 跑一段时间后不再投递任何事件，但 `Status` 仍然是 `Started`，`Stopped` 事件也不一定触发。唯一可靠的恢复办法是**周期性重启扫描器**（看门狗）。极端情况下连重启都无效，需要手动开关一次系统蓝牙。

- **安卓：不能用"全收"扫描。** 这是本项目最耗时的一个坑。不加 `ScanFilter` 时，手机能收到附近 iPhone 的苹果广播（`0x10`/`0x12`/`0x02`），但 **AirPods 的 `0x07` 一条都收不到**；而同一时刻同一个房间，电脑上能收到。原文对比确认不是解析问题——收到的广播里根本没有 `4C 00 07` 这几个字节。

  **解法**：加一个苹果厂商 ID + `07 19` 前缀的 `ScanFilter`，让**蓝牙控制器硬件**去过滤。硬件过滤和"全收"是两条不同的投递路径，前者能拿到，后者拿不到。

- **`MATCH_NUM_MAX_ADVERTISEMENT` 必须显式设置。** 默认值是 `MATCH_NUM_ONE_ADVERTISEMENT`，也就是"每台设备每次扫描只上报一条"。电量这种需要持续更新的场景必须改成全量上报。

- **开了批量上报就必须主动催。** `setReportDelay(>0)` 会让结果攒在控制器缓冲区里，要周期性调用 `flushPendingScanResults()`，否则要等缓冲区满才吐出来。同时不能只重写 `onScanResult`，还得处理 `onBatchScanResults`。

- **安卓权限分两套模型，取决于 `targetSdk`：**
  - `targetSdk >= 31`：用 `BLUETOOTH_SCAN`（可加 `neverForLocation`）和 `BLUETOOTH_CONNECT`
  - `targetSdk < 31`：用旧的 `BLUETOOTH` / `BLUETOOTH_ADMIN` 加**运行时的定位权限**

  用旧模型时，`BLUETOOTH` 和 `BLUETOOTH_ADMIN` **不能**加 `maxSdkVersion="30"`——加了之后在 Android 12 及以上根本不会被授予，扫描直接抛 `SecurityException`。

## 苹果的协议

- **报文是 27 字节定长结构**：`[0]=0x07` 类型、`[1]=0x19` 长度（25），其后 25 字节载荷。厂商 ID 是 `0x004C`。

- **协议里没有"左耳"「右耳」这种字段。** 两只耳机各自广播同一个结构，用状态字节的一位表明自己是哪一侧；**低半字节永远是"正在广播的那只"**，高半字节是另一只。不按这个规则翻转，左右必然读反。字段是 **bitfield 排布**的，按"每字段一字节"去猜会读错。

- **电量是 4 bit 的档位，不是百分比。** 取值 `0..10`（对应 0%..100%，10% 一档）。**超过 10 表示"不可用"，和 0% 不是一回事。**

- **报文的尾部 16 字节是加密的**（AES），精确电量在里面。解密密钥要通过 AACP 协议主动索取，所以没有 AACP 连接就永远拿不到精确值。

- **`0x07` 报文里有多种变体。** 除了常规的电量广播，还有"连上设备时"发出的、载荷格式不同的帧——它的字节在标准偏移上全是垃圾，不滤掉会产生幽灵设备。可以靠"第 3 字节必须是 `0x01`"来区分。

- **广播很稀疏。** 耳机连着设备时大约**每分钟一条**，不是持续广播。所以展示层必须**保留最后一次读数并标注它有多旧**，不能因为暂时没收到就把数值抹掉。

- **LE 地址会轮换。** 每隔几分钟换一次随机地址，所以**不能按地址锁定设备**。区分是不是同一副耳机，要用"型号一致 + 电量变化不超过一档 + 信号没有突变"这类连续性校验——固定 RSSI 阈值是不够的，会把邻居的耳机认成自己的。

## 精确电量：三个平台都拿不到

AirPods 的精确电量只存在于苹果私有的 **AACP** 协议里，它在经典蓝牙 L2CAP PSM `0x1001` 上。

| 平台 | 障碍 |
| --- | --- |
| Windows | 用户态根本没有 L2CAP（见上） |
| 安卓（未 root） | 安卓蓝牙栈有 FCR 模式协商的 bug，`connect()` **静默挂起**而非报错。开源项目 LibrePods 用 Xposed + 原生 hook 绕过，**需要 root** |
| 安卓（部分 OEM） | ColorOS/OxygenOS 16、realme UI 7.0、Pixel on Android 16 QPR3 已修复。该 bug 官方在 **Android 17** 修复 |

**所以未 root 的安卓设备上，第三方应用能拿到的上限就是 BLE 广播里的粗粒度电量。** 这不是实现水平问题，是协议和平台层的边界。
