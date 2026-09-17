# 为什么不用 AAP / L2CAP 读 AirPods 电量

## 结论

**Windows 用户态无法连接 AirPods 的 AAP 通道。** 这个方向已被实测排除，产品改用 BLE 广播路线（见 `src/AirPodsBleTray`）。

本文记录验证过程和其中的技术要点，以免将来有人重新走一遍，或重新踩同样的坑。

## 背景

AirPods 通过苹果私有协议 **AAP**（亦称 AACP）上报精确电量（左耳 / 右耳 / 充电盒）。该协议运行在**经典蓝牙 L2CAP 层**，PSM 为 `0x1001`，在设备的 SDP 记录里对应服务 UUID `74ec2172-0bad-4d01-8f77-997b2be0722a`（显示名 "AAP Server"）。

Windows 不实现这个协议，所以「设置 → 蓝牙和其他设备」里永远看不到 AirPods 电量（它只显示支持标准 BLE 电池服务 `0x180F` 的设备）。

## 验证过程

探针程序 `src/AirPodsSpike`（已验证完毕后删除，可从 git 历史取回）。

目标设备：AirPods，经典蓝牙地址 `EC:73:79:5A:E3:AE`，已配对且处于连接状态。

### 连接实验矩阵

| 实验 | 目标 | 结果 |
| --- | --- | --- |
| A | 设备自身的 SDP 服务（PSM `0x0001`），对照组 | `10050 WSAENETDOWN`，6 ms |
| B | 目标 PSM `0x1001`，不带 UUID | `10050`，0 ms |
| C | 目标 PSM `0x1001` + AAP UUID | `10050`，0 ms |
| D | 由 SDP 解析 AAP UUID 对应的 PSM（`port=0`） | `10050`，0 ms |

### 追加对照

为了区分「某个 PSM 被挡」和「L2CAP socket 整体不可用」，又补了几组：

| 操作 | 结果 |
| --- | --- |
| 连本机蓝牙适配器自己的 SDP 服务（PSM `0x0001`） | `10050` |
| 连设备的 AVRCP（PSM `0x0017`，系统此刻正在使用） | `10050` |
| 连一个**根本不存在的**蓝牙地址 | `10050` |
| 在本机 `bind()` 申请一个动态 PSM | `10050` |

### 关键结果

**所有 `AF_BTH` L2CAP 操作无差别返回 `10050 WSAENETDOWN`，包括 `bind()`。**

如果只是 `0x1001` 被系统占用，SDP 和 AVRCP 应该能连上；如果只是目标地址不可达，本机 `bind()` 不该失败。无差别全挂说明：**微软蓝牙栈根本不服务 `AF_BTH` 的 L2CAP socket**——`socket()` 能创建成功，只是因为协议选择子的符号在头文件里存在，实际功能未实现。

旁证：

- 有技术报告指出微软蓝牙栈下「无法访问 L2CAP，尽管头文件里有 socket 原型」
- Windows 上的商业软件 MagicPods 读 AirPods 数据需要自带内核驱动（MagicAAP），而非纯用户态 L2CAP

## 已排除的其他可能

排查过程中逐一排除了：

- **沙箱 / 权限**：沙箱内外结果一致；进程为普通用户、Medium 完整性、无 AppContainer，TCP 连接正常
- **地址编码**：`SOCKADDR_BTH.btAddr` 是主机序、最左字节最高位，`"EC:73:79:5A:E3:AE"` → `0xEC73795AE3AE`（与 `BLUETOOTH_ADDRESS` 约定一致，已交叉验证）
- **设备状态**：确认耳机当时连接正常（音频端点活跃）
- **蓝牙栈故障**：适配器状态 OK、`bthserv` 运行中、经典蓝牙（A2DP/HFP）功能正常

## 技术要点

若将来真要写内核 / UMDF 驱动，以下事实可以省去重新摸索的时间。

### `0x1001` 可以注册

Windows 保留的 PSM 是 SDP `0x01`、RFCOMM `0x03`、BNEP `0x0F`、HID Control `0x11`、HID Data `0x13`。**`0x1001` 不在其中**，profile driver 可以注册。

### 驱动路线的大致形状

Bluetooth profile driver 通过 BRB（Bluetooth Request Block）与栈交互：`BRB_REGISTER_PSM` 注册 PSM，`BRB_L2CA_REGISTER_SERVER` / `BRB_L2CA_OPEN_CHANNEL_RESPONSE` 处理连接。可参考开源项目 BthPS3（PS3 手柄走 L2CAP，同名场景）。

### P/Invoke 要点（用户态，虽然最终没能连上）

- `SOCKADDR_BTH` 声明在 `#include <pshpack1.h>` 区块内，**必须 `[StructLayout(LayoutKind.Sequential, Pack=1)]`**，`sizeof == 30`。打包写错会变成 40，`connect()` 报 `10049`
- 地址族用 `AF_BTH = 32`，协议用 `BTHPROTO_L2CAP = 0x0100`
- **Windows 必须用 `SOCK_STREAM`，不能用 `SOCK_SEQPACKET`**（后者返回 `10044 WSAESOCKTNOSUPPORT`）。Linux / BlueZ 的示例都用 SEQPACKET，照搬必踩坑
- `SOCKET` 是 `IntPtr` 而非 `int`，`INVALID_SOCKET == -1`
- **`WSAGetLastError()` 是线程局部的**：把 `connect()` 放到工作线程并设超时的话，错误码必须在那个线程上读
- L2CAP socket 上 `SO_BTH_AUTHENTICATE` / `SO_BTH_ENCRYPT` / `SO_BTH_MTU` 全部返回 `10042 WSAENOPROTOOPT`——**无法在应用层请求认证加密**，只能依赖已配对的 link key
- 不需要管理员权限

## 相关

- 替代方案与实现：`src/AirPodsBleTray`
- 排查工具：`src/AirPodsBleScan`
