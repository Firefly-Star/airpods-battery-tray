package com.example.airpodsbattery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 尝试打开 AirPods 的私有 AAP 通道（L2CAP PSM 0x1001），也就是精确电量的唯一来源。
 *
 * 这件事在安卓上得走隐藏 API：公开的 `createInsecureL2capChannel` 会把 PSM 上限卡在 0x00FF，
 * 而 `createInsecureL2capSocket` 属于 `max-target-o` 级别的非 SDK 接口——本项目的 targetSdk
 * 之所以是 26，就是为了让反射能碰到它。
 *
 * 就算反射成功，底层还有一个已知的蓝牙栈 bug（`l2c_fcr_chk_chan_modes`）会拒绝 AirPods 要求的
 * 信道模式。这一步就是用来把这两种失败区分开的，所以这里把所有中间状态都记下来。
 */
object AapProbe {

    private const val AAP_PSM = 0x1001
    private const val TIMEOUT_SECONDS = 20L

    private val HANDSHAKE = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    // 设定特性掩码（0x4D）与订阅通知（0x0F）。这两个常量的取值来自二手资料，未必正确——
    // 之所以照样发，是因为不管对不对，对端有没有回应本身就是有用的信息。
    private val SET_FEATURES = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x4D.toByte(), 0x00, 0xD7.toByte(), 0x00, 0x00, 0x00)
    private val REQUEST_NOTIFICATIONS = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    data class Outcome(val log: String, val connected: Boolean)

    fun run(adapter: BluetoothAdapter?): Outcome {
        val log = StringBuilder()

        if (adapter == null) {
            return Outcome("蓝牙适配器不可用", false)
        }

        val candidates = try {
            adapter.bondedDevices.orEmpty().filter {
                val name = it.name.orEmpty().lowercase()
                name.contains("airpods") || name.contains("beats")
            }
        } catch (e: SecurityException) {
            return Outcome("读取已配对设备被拒绝：${e.message}", false)
        }

        log.appendLine("已配对的设备共 ${adapter.bondedDevices?.size ?: 0} 个")
        if (candidates.isEmpty()) {
            log.appendLine("其中没有名字像 AirPods 的。请先在系统蓝牙设置里把耳机配对到本机。")
            return Outcome(log.toString(), false)
        }
        candidates.forEach { log.appendLine("  候选：${it.name}  ${it.address}") }

        val device = candidates.first()
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<Outcome> { probeOne(device, log) }
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log.appendLine("整体超时或异常：${e.javaClass.simpleName}: ${e.message}")
            Outcome(log.toString(), false)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeOne(device: BluetoothDevice, log: StringBuilder): Outcome {
        val socket = openSocket(device, log) ?: return Outcome(log.toString(), false)

        try {
            log.appendLine("连接 PSM 0x1001 …")
            socket.connect()
            log.appendLine("连接成功。这是一个重要信号：说明你的机器上没有那个蓝牙栈 bug。")

            send(socket, HANDSHAKE, "握手包", log)
            var received = readFor(socket, 3000)

            if (received.isEmpty()) {
                log.appendLine("握手后 3 秒内没有回应，继续发特性掩码与订阅通知")
                send(socket, SET_FEATURES, "设定特性 0x4D", log)
                send(socket, REQUEST_NOTIFICATIONS, "订阅通知 0x0F", log)
                received = readFor(socket, 5000)
            }

            if (received.isEmpty()) {
                log.appendLine("仍然没有收到任何字节。可能是那两个常量不对，也可能是对端不认这个连接。")
            } else {
                log.appendLine("收到 ${received.size} 字节：")
                log.appendLine("  ${received.toPrintableHex()}")
                val hasBattery = received.any { (it.toInt() and 0xFF) == 0x04 }
                log.appendLine(if (hasBattery) "其中出现了帧头 0x04，很可能包含电量数据。" else "暂未识别出电量帧。")
            }

            return Outcome(log.toString(), true)
        } catch (e: Exception) {
            log.appendLine("连接失败：${unwrap(e)}")
            log.appendLine("常见原因：隐藏 API 被拦截，或蓝牙栈拒绝了 AirPods 要求的信道模式。")
            return Outcome(log.toString(), false)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun openSocket(device: BluetoothDevice, log: StringBuilder): BluetoothSocket? {
        // 先试不需要认证的那个，再试要求安全连接的版本。
        for (name in listOf("createInsecureL2capSocket", "createL2capSocket")) {
            try {
                val method = BluetoothDevice::class.java.getMethod(name, Int::class.javaPrimitiveType)
                val socket = method.invoke(device, AAP_PSM) as BluetoothSocket
                log.appendLine("调用隐藏方法 $name($AAP_PSM) 成功，拿到 socket")
                return socket
            } catch (e: NoSuchMethodException) {
                log.appendLine("没有 $name 这个方法")
            } catch (e: Exception) {
                log.appendLine("调用 $name 失败：${unwrap(e)}")
            }
        }
        log.appendLine("两个隐藏方法都拿不到 socket —— 隐藏 API 限制可能仍然生效。")
        return null
    }

    private fun send(socket: BluetoothSocket, payload: ByteArray, label: String, log: StringBuilder) {
        try {
            socket.outputStream.write(payload)
            socket.outputStream.flush()
            log.appendLine("已发送 $label：${payload.toPrintableHex()}")
        } catch (e: Exception) {
            log.appendLine("发送 $label 失败：${unwrap(e)}")
        }
    }

    private fun readFor(socket: BluetoothSocket, millis: Int): ByteArray {
        val collected = ArrayList<Byte>()
        val deadline = System.currentTimeMillis() + millis
        val buffer = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            val available = try {
                socket.inputStream.available()
            } catch (_: Exception) {
                0
            }
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val read = try {
                socket.inputStream.read(buffer)
            } catch (_: Exception) {
                -1
            }
            if (read <= 0) break
            for (i in 0 until read) collected.add(buffer[i])
        }
        return collected.toByteArray()
    }

    private fun unwrap(e: Throwable): String {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
