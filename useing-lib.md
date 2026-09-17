# 依赖清单

记录项目中实际使用到的库与原生 API。每次引入新依赖（无论是代码引用的包，还是处理文件时为读取而安装的工具）都要更新本文件。

## src/AirPodsSpike

**第三方库：无。** 刻意不引入 NuGet 包。

- `32feet.NET` / `InTheHand.Net.Bluetooth` 虽然提供了 `BluetoothProtocolType.L2Cap`，但其 API 面围绕 RFCOMM 设计，不提供 AAP 所需的字节级收发与组帧能力，引入后仍要自己写同样的代码，收益为零。

**原生 API（P/Invoke `ws2_32.dll`）：**

| 函数 | 用途 |
| --- | --- |
| `WSAStartup` / `WSACleanup` | Winsock 生命周期 |
| `socket` | 创建 `AF_BTH` / `SOCK_STREAM` / `BTHPROTO_L2CAP` socket |
| `connect` | 连接 `SOCKADDR_BTH` 指定的蓝牙地址与 PSM |
| `send` / `recv` | 字节收发 |
| `WSAPoll` | 非阻塞轮询，兼作可取消的等待 |
| `closesocket` | 关闭 socket |
| `ioctlsocket` | 切换非阻塞模式（`FIONBIO`） |
| `WSAGetLastError` | 读取错误码（不依赖 `SetLastError`） |

**运行时自带：** .NET 9 BCL 的 `System.Diagnostics.Stopwatch`、`System.Text.Encoding` 等，非外部依赖。
