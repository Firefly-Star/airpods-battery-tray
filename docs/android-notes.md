# Android 端：怎么读到 AirPods 电量

## 结论

**可以，不需要 root。** 走 BLE 广播路线，实现见 `android/`。

本文记录排查过程和结论，重点在于**什么没用**——那部分才是最容易重新踩进去的。

## 两条路线，一死一活

### AAP / L2CAP：确认走不通

AirPods 的精确电量只存在于苹果私有的 AACP 协议里，跑在经典蓝牙 L2CAP PSM `0x1001` 上。Windows 上这条路被证明彻底堵死（见 [l2cap-dead-end.md](l2cap-dead-end.md)），安卓上则是另一堵墙：

- 公开 API `createInsecureL2capChannel` 把 PSM 上限卡在 `0x00FF`，`0x1001` 直接被拒
- 隐藏接口 `createInsecureL2capSocket(int)` 属于 `max-target-o` 级别，**targetSdk ≤ 26 的应用才被允许反射调用**

把 targetSdk 压到 26 之后，实测**隐藏 API 确实调通了**（拿到了 socket）。但接下来 `connect()` 会**静默挂起**：不报错、不拒绝，等满 60 秒仍在"连接中"。

这不是我们写错了。安卓蓝牙栈有一个 FCR（流控重传）模式协商的 bug（[Google issue 371713238](https://issuetracker.google.com/issues/371713238)，要到 Android 17 才修），表现为挂起而不是报错。开源项目 LibrePods 解决同一个问题用的是 **Xposed + 原生 hook**（`libl2c_fcr_hook.so` 改写 `l2c_fcr_chk_chan_modes`）——**必须 root**，且官方说明"不保证所有设备可用"。

LibrePods 的 README 里还列了不需要 root 的机型：ColorOS/OxygenOS 16、realme UI 7.0、Pixel on Android 16 QPR3。华为不在其中。

**代价**：放弃 L2CAP 之后 targetSdk 就不必再压低了，回到 35 用新权限模型即可。

### BLE 广播：可行

AirPods 会通过 BLE 广播苹果的「临近配对」报文（Apple Continuity，类型 `0x07`），电量就在里面。

## 排查过程里最花时间的一段

1. **全收扫描（不过滤）收不到 AirPods 的包。** 手机能收到附近 iPhone 的苹果广播（`0x10` 3599 条、`0x12`、`0x02`），但 `0x07` **一条都没有**；而同一时刻、同一个房间，电脑上 Windows 版每分钟都能收到几条。原文对比确认不是解析问题——手机收到的广播里根本没有 `4C 00 07` 这几个字节。

2. 试过但**无效**的改动：
   - 放宽 RSSI 门槛（`-60` → `-85`）：无关，`0x07` 为 0
   - 打开全部 PHY、允许非传统广播：无关
   - 加扫描过滤器（`07 19`）+ `MATCH_NUM_MAX_ADVERTISEMENT` + `MATCH_MODE_AGGRESSIVE`：仍然 0

3. **决定性的一步**：去读 CAPod 的源码。CAPod 是 LibrePods README 里明确推荐给**未 root** 用户的替代品，在 Play Store 有大量用户、实测能在这台手机上正常显示电量。

   对齐它之后就好了。改动是这几项（**一次性全改的，没有逐个二分，所以不知道哪一项是关键**）：
   - 权限模型换成安卓 12 之后的新模型：`targetSdk 35` + `BLUETOOTH_SCAN`（`neverForLocation`）+ `BLUETOOTH_CONNECT`，旧的几个权限 `maxSdkVersion="30"`
   - 扫描模式 `SCAN_MODE_LOW_LATENCY` → **`SCAN_MODE_BALANCED`**
   - 匹配模式 `MATCH_MODE_AGGRESSIVE` → **`MATCH_MODE_STICKY`**
   - 开启**批量上报**（`setReportDelay(1000)`）并每 2 秒 `flushPendingScanResults`——开了批量就必须主动催，否则结果压在控制器缓冲区里不出来
   - `MATCH_NUM_MAX_ADVERTISEMENT`（默认值是"每台设备每次扫描只上报一条"）

   参考代码（GPLv3）：`common/bluetooth/BleScanner.kt`、`pods/core/apple/ble/protocol/ProximityPairing.kt`。

## 数据源本身的限制

这些是协议决定的，代码解决不了：

- **广播很稀疏。** 耳机连着设备时大约每分钟一条。所以**必须保留最后一次读数并标注"多久之前"**，不能因为暂时没广播就把数字抹成"暂无"——那样界面会大部分时间空白、偶尔闪一下。
- **精度只有 10% 一档。** 每个电量字段是 4 bit（`0-9` → 0-90%，`A-E` → 100%）。
- **精确电量拿不到**，原因见上面 AAP 那一节。
- **报文尾部 16 字节是加密的**，解密密钥要从 AACP 取，所以即使拿到密文也没用。

## 协议要点

报文是 27 字节定长结构，字段布局与 Windows 端实现一致（`android/.../AirPodsAdvertisement.kt`）。

有一点容易搞错：**协议里没有"左耳""右耳"字段**。两只耳机各自广播同一个结构，用状态字节的第 5 位表明自己是哪一侧；低半字节永远是"发广播的那只"。不按这个规则翻转，左右必然读反。

另外 `0x07` 报文里还有一种是**连上设备时发的、载荷格式不同**的（CAPod 源码里提到的情况，它靠"第 3 字节必须是 `0x01`"过滤掉）——不滤掉会产生幽灵设备。
