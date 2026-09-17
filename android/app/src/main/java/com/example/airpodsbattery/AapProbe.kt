package com.example.airpodsbattery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 尝试打开 AirPods 的私有 AAP 通道（L2CAP PSM 0x1001），也就是精确电量的唯一来源。
 *
 * 在安卓上这必须走隐藏 API：公开的 `createInsecureL2capChannel` 把 PSM 上限卡在 0x00FF，
 * 而 `createInsecureL2capSocket` 属于 `max-target-o` 级别的非 SDK 接口——本项目的 targetSdk
 * 之所以是 26，就是为了让反射能碰到它。
 *
 * 拿到 socket 只是第一关。真正容易卡住的是 connect()：安卓的蓝牙栈有一个已知 bug
 * （`l2c_fcr_chk_chan_modes`）会拒绝 AirPods 要求的信道模式，而且**表现为静默挂起而不是报错**。
 * 所以这里把 connect 放到独立线程上，边等边报进度，好把"慢"和"卡死"区分开。
 */
object AapProbe {

    private const val AAP_PSM = 0x1001
    private const val CONNECT_TIMEOUT_MS = 60_000L
    private const val ATTEMPTS = 2

    private val HANDSHAKE = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    // 设定特性掩码（0x4D）与订阅通知（0x0F）。这两个常量的取值来自二手资料，未必正确——
    // 之所以照样发，是因为不管对不对，对端有没有回应本身就是有用的信息。
    private val SET_FEATURES = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7.toByte(), 0x00, 0x00, 0x00)
    private val REQUEST_NOTIFICATIONS = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    data class Outcome(val log: String, val connected: Boolean)

    fun run(adapter: BluetoothAdapter?): Outcome {
        val log = StringBuilder()

        if (adapter == null) return Outcome("蓝牙适配器不可用", false)

        val bonded = try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (e: SecurityException) {
            return Outcome("读取已配对设备被拒绝：${e.message}", false)
        }

        log.appendLine("已配对设备 ${bonded.size} 个")
        bonded.forEach {
            log.appendLine("  ${it.name}  ${it.address}  bondState=${it.bondState}  type=${it.type}")
        }

        val candidates = bonded.filter {
            val name = it.name.orEmpty().lowercase()
            name.contains("airpods") || name.contains("beats")
        }
        if (candidates.isEmpty()) {
            log.appendLine("没有名字像 AirPods 的已配对设备。请先在系统蓝牙设置里把耳机配对到本机。")
            return Outcome(log.toString(), false)
        }

        val device = candidates.first()
        log.appendLine()
        log.appendLine("选中：${device.name}  ${device.address}")

        val cachedUuids = try {
            device.uuids
        } catch (_: SecurityException) {
            null
        }
        if (cachedUuids.isNullOrEmpty()) {
            log.appendLine("设备的 SDP 服务缓存为空（需要主动发起 SDP 查询后才会有值）")
        } else {
            log.appendLine("SDP 缓存里的服务：")
            cachedUuids.forEach { log.appendLine("  ${it.uuid}") }
        }

        for (attempt in 1..ATTEMPTS) {
            log.appendLine()
            log.appendLine("=== 第 $attempt 次尝试 ===")
            val outcome = probeOnce(device, log)
            if (outcome.connected) return outcome
            if (attempt < ATTEMPTS) {
                log.appendLine("歇 2 秒后重试")
                Thread.sleep(2000)
            }
        }

        log.appendLine()
        log.appendLine("两次都没连上。如果日志里一直是「仍在连接中」，说明卡在信道模式协商——")
        log.appendLine("那正是需要 root 才能绕过的那个栈 bug。")
        return Outcome(log.toString(), false)
    }

    private fun probeOnce(device: BluetoothDevice, log: StringBuilder): Outcome {
        val socket = openSocket(device, log) ?: return Outcome(log.toString(), false)

        try {
            log.appendLine("连接 PSM 0x$AAP_PSM …")
            if (!connectWithHeartbeat(socket, log)) return Outcome(log.toString(), false)

            send(socket, HANDSHAKE, "握手包", log)
            var received = readFor(socket, 3000)

            if (received.isEmpty()) {
                log.appendLine("握手后 3 秒无回应，继续发特性掩码与订阅通知")
                send(socket, SET_FEATURES, "设定特性 0x4D", log)
                send(socket, REQUEST_NOTIFICATIONS, "订阅通知 0x0F", log)
                received = readFor(socket, 5000)
            }

            if (received.isEmpty()) {
                log.appendLine("仍未收到任何字节。")
            } else {
                log.appendLine("收到 ${received.size} 字节：")
                log.appendLine("  ${received.toPrintableHex()}")
            }
            return Outcome(log.toString(), true)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 把阻塞的 connect() 丢到独立线程，主线程边等边报进度。 */
    private fun connectWithHeartbeat(socket: BluetoothSocket, log: StringBuilder): Boolean {
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)

        val worker = Thread {
            try {
                socket.connect()
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        val startedAt = System.currentTimeMillis()
        while (true) {
            val remaining = CONNECT_TIMEOUT_MS - (System.currentTimeMillis() - startedAt)
            if (remaining <= 0) break

            if (finished.await(minOf(5000, remaining), TimeUnit.MILLISECONDS)) {
                val error = failure.get()
                if (error == null) {
                    log.appendLine("连接成功，耗时 ${System.currentTimeMillis() - startedAt} ms")
                    return true
                }
                log.appendLine("连接抛异常（耗时 ${System.currentTimeMillis() - startedAt} ms）：${unwrap(error)}")
                return false
            }
            log.appendLine("仍在连接中… 已等待 ${(System.currentTimeMillis() - startedAt) / 1000} 秒")
        }

        log.appendLine("等待 ${CONNECT_TIMEOUT_MS / 1000} 秒仍未连上，判定死锁")
        try {
            socket.close()
        } catch (_: Exception) {
        }
        return false
    }

    private fun openSocket(device: BluetoothDevice, log: StringBuilder): BluetoothSocket? {
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
