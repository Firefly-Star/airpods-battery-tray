package com.example.airpodsbattery

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 扫描 AirPods 的电量广播。
 *
 * 两件事让它比看起来麻烦：
 *
 *  * 附近每一副 AirPods 都发同样形状的报文，而且耳机每几分钟会轮换一次 LE 地址，所以没法靠
 *    地址把设备钉住。区分办法和 Windows 端一致：地址变化时要求型号一致、电量变化不超过一档、
 *    信号差不超过 50 dBm。
 *
 *  * 安卓的扫描会莫名停摆（和 Windows 那边一样）。看门狗在长时间收不到任何广播时重启扫描。
 */
class BleScanner(
    private val context: Context,
    private val onState: (AirPodsState) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private companion object {
        const val TAG = "BleScanner"
        // 耳机塞在口袋或隔着一层桌子就可能掉到 -70 以下，门槛设太高会把真报文也滤掉。
        // 防串台交给下面的连续性校验，而不是靠这里一刀切。
        const val MIN_RSSI = -85

        // 与 AirPodsAdvertisement 里的常量保持一致：类型字节 0x07、其后长度字节 25，总长 27。
        private const val PROXIMITY_PACKET_LENGTH = 27
        private const val PROXIMITY_TYPE: Byte = 0x07
        private const val PROXIMITY_REMAINING_LENGTH: Byte = 25
        const val MAX_RSSI_JUMP = 50
        const val MAX_LEVEL_JUMP = 1
        const val LOST_AFTER_MS = 10_000L
        const val WATCHDOG_AFTER_MS = 30_000L
    }

    private class TrackedSide {
        var advertisement: AirPodsAdvertisement? = null
        var seenAt: Long = 0
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val sides = arrayOf(TrackedSide(), TrackedSide()) // 0 = 左, 1 = 右
    private val handler = Handler(Looper.getMainLooper())
    private var lastAnyAdvertisementAt = 0L
    private var lastSeenAt: Long? = null
    private var scanning = false
    private var restartCount = 0
    private var totalAdvertisements = 0
    private var appleAdvertisements = 0
    private var proximityPairingAdvertisements = 0
    private val typeCounts = HashMap<Int, Int>()
    private val lengthMismatches = HashMap<Int, Int>()

    /** 去重后保留前若干条原始广播，用来确认手机到底收到了什么字节。 */
    private val rawDumps = LinkedHashSet<String>()
    private val rawLengthCounts = HashMap<Int, Int>()
    private var acceptedAdvertisements = 0
    private var rejectedAdvertisements = 0
    private var lastRejectionLoggedAt = 0L

    /**
     * 最后一次成功读数。数据源是稀疏的（耳机大部分时间不广播），如果把过期读数清成空，
     * 界面就会大部分时间显示"暂无广播"、偶尔闪一下数字，看起来像坏的。电量本来也不该
     * 因为暂时没广播就被抹掉——保留它，由界面标明"多久之前"。
     */
    private var lastState: AirPodsState? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onAnyResult()
            recordRaw(result)
            val manufacturer = result.scanRecord
                ?.getManufacturerSpecificData(AirPodsAdvertisement.APPLE_COMPANY_ID)
                ?: return

            appleAdvertisements++

            // 记下类型字节的分布。这里至关重要：0x07 才是 AirPods 的电量报文，
            // 0x10/0x12/0x0C 都是附近 iPhone、手表发的，长得一样但内容无关。
            val type = manufacturer.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
            typeCounts[type] = (typeCounts[type] ?: 0) + 1

            if (type == AirPodsAdvertisement.PROXIMITY_PAIRING_TYPE && manufacturer.size != AirPodsAdvertisement.PACKET_LENGTH) {
                lengthMismatches[manufacturer.size] = (lengthMismatches[manufacturer.size] ?: 0) + 1
            }

            val advertisement = AirPodsAdvertisement.parse(
                result.device.address.toLongAddress(),
                result.rssi,
                manufacturer,
            ) ?: return
            proximityPairingAdvertisements++
            accept(advertisement)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            // 批量上报默认关闭（reportDelay=0），但某些机型会强制走这条路，不处理就会全丢。
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("扫描失败，错误码 $errorCode")
        }
    }

    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            checkWatchdog()
            tickCount++
            if (tickCount % 15 == 0) {
                log(counters())
            }
            handler.postDelayed(this, 1000)
        }
    }

    val isScanning: Boolean get() = scanning

    fun start() {
        if (scanning) return
        if (adapter == null || !adapter.isEnabled) {
            log("蓝牙不可用或未开启")
            return
        }
        if (!hasScanPermission()) {
            log("缺少定位权限，安卓要求授予定位权限才能扫描 BLE")
            return
        }

        try {
            adapter.bluetoothLeScanner?.startScan(listOf(proximityPairingFilter), scanSettings, scanCallback)
            scanning = true
            lastAnyAdvertisementAt = System.currentTimeMillis()
            handler.post(ticker)
            log("扫描已启动")
        } catch (e: SecurityException) {
            log("启动扫描被拒：${e.message}")
        }
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(ticker)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        log("扫描已停止")
    }

    fun counters(): String {
        val types = typeCounts.entries.sortedByDescending { it.value }
            .joinToString(" ") { "0x%02X=%d".format(it.key, it.value) }
            .ifEmpty { "（尚无）" }
        val lengths = lengthMismatches.entries.sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}字节=%d".format(it.value) }
            .ifEmpty { "无" }
        val phy = "硬件过滤 + 全量上报"

        return "总广播 $totalAdvertisements · 苹果厂商数据 $appleAdvertisements · " +
            "0x07报文 $proximityPairingAdvertisements\n" +
            "采纳 $acceptedAdvertisements · 丢弃 $rejectedAdvertisements · 重启 $restartCount\n" +
            "扫描 PHY：$phy\n" +
            "苹果类型分布：$types\n" +
            "0x07 长度异常：$lengths\n" +
            "广播包长度分布：" + rawLengthCounts.entries.sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}字节=%d".format(it.value) }.ifEmpty { "（尚无）" }
    }

    /**
     * 只匹配 AirPods 的电量报文：苹果厂商 ID + 厂家数据以 `07 19`（类型 0x07、长度 25）开头。
     *
     * 这个过滤器可以交给蓝牙控制器**硬件执行**，走的是一条和"全收"完全不同的路径。实测中
     * 手机用"全收"扫描能收到附近 iPhone 的苹果广播，却一条 AirPods 报文都收不到，而同一个
     * 房间的电脑能收到——转向硬件过滤路径是唯一还没试过的方向。
     *
     * 掩码只有前两个字节为 1，其余为 0，所以后半段加密内容不影响匹配。
     */
    private val proximityPairingFilter: ScanFilter
        get() {
            val data = ByteArray(PROXIMITY_PACKET_LENGTH).apply {
                this[0] = PROXIMITY_TYPE
                this[1] = PROXIMITY_REMAINING_LENGTH
            }
            val mask = ByteArray(PROXIMITY_PACKET_LENGTH).apply {
                this[0] = 1
                this[1] = 1
            }
            return ScanFilter.Builder()
                .setManufacturerData(AirPodsAdvertisement.APPLE_COMPANY_ID, data, mask)
                .build()
        }

    // MATCH_NUM_MAX_ADVERTISEMENT 是关键：默认值是"每台设备每次扫描只上报一条"，
    // 配合 MATCH_MODE_AGGRESSIVE 才能把所有报文都拿到。
    private val scanSettings: ScanSettings
        get() = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

    /**
     * 把收到的原始广播字节记下来（去重，只留前 20 条）。
     *
     * 这是用来回答一个具体问题的：手机到底有没有收到 AirPods 那个包？如果原文里压根没有，
     * 那是扫描配置的问题；如果有但结构不对（比如被塞进 scan response、或长度超过 31 字节
     * 说明是扩展广播），那就是解析的问题。两种修法完全不同。
     */
    private fun recordRaw(result: ScanResult) {
        val raw = result.scanRecord?.bytes ?: return
        rawLengthCounts[raw.size] = (rawLengthCounts[raw.size] ?: 0) + 1
        if (rawDumps.size >= 20) return

        val hex = raw.toHex()
        if (!rawDumps.add(hex.take(80))) return

        // 主动扫描时安卓会把「广播包 + 扫描响应」拼在一起返回，实测这类记录正好 62 字节。
        // AirPods 的载荷可能在后半段，所以要整条打出来，分两行便于阅读。
        val half = raw.size / 2
        log("原文 len=${raw.size} rssi=${result.rssi} [前段] ${raw.copyOfRange(0, half).toPrintableHex()}")
        log("                    [后段] ${raw.copyOfRange(half, raw.size).toPrintableHex()}")
    }

    private fun onAnyResult() {
        lastAnyAdvertisementAt = System.currentTimeMillis()
        totalAdvertisements++
    }

    private fun accept(advertisement: AirPodsAdvertisement) {
        if (!looksLikeOurs(advertisement)) return

        val side = sides[if (advertisement.broadcastFromLeft) 0 else 1]
        side.advertisement = advertisement
        side.seenAt = System.currentTimeMillis()
        lastSeenAt = side.seenAt

        log(
            "${advertisement.mac()} rssi=${advertisement.rssi} " +
                "L=${advertisement.leftBattery ?: "--"} R=${advertisement.rightBattery ?: "--"} " +
                "C=${advertisement.caseBattery ?: "--"} " +
                "from=${if (advertisement.broadcastFromLeft) "L" else "R"}",
        )
    }

    /**
     * 决定一条广播是不是我们这副耳机发的。
     *
     * 地址轮换时不能只看型号——同型号的邻居会直接混进来。还要看电量有没有在一瞬间跳一大截
     * （电量只会一档一档地变），以及信号有没有突变。
     */
    private fun looksLikeOurs(advertisement: AirPodsAdvertisement): Boolean {
        if (advertisement.rssi < MIN_RSSI) {
            return reject("信号 ${advertisement.rssi} 低于门槛 $MIN_RSSI", advertisement)
        }

        val side = sides[if (advertisement.broadcastFromLeft) 0 else 1]
        val other = sides[if (advertisement.broadcastFromLeft) 1 else 0]

        val previous = side.advertisement
        if (previous != null) {
            if (previous.address != advertisement.address) {
                if (previous.modelId != advertisement.modelId) {
                    return reject("地址变化且型号不同（0x%04X → 0x%04X）".format(previous.modelId, advertisement.modelId), advertisement)
                }
                val jump = maxOf(
                    levelJump(previous.leftBattery, advertisement.leftBattery),
                    levelJump(previous.rightBattery, advertisement.rightBattery),
                    levelJump(previous.caseBattery, advertisement.caseBattery),
                )
                if (jump > MAX_LEVEL_JUMP) {
                    return reject("地址变化且电量跳变 $jump 档", advertisement)
                }
            }
            if (Math.abs(previous.rssi - advertisement.rssi) > MAX_RSSI_JUMP) {
                return reject("地址变化且信号突变（${previous.rssi} → ${advertisement.rssi}）", advertisement)
            }
        }

        if (other.advertisement?.let { Math.abs(it.rssi - advertisement.rssi) > MAX_RSSI_JUMP } == true) {
            return reject("与另一只耳的信号差过大", advertisement)
        }

        acceptedAdvertisements++
        return true
    }

    /** 记录丢弃原因，但限流——广播可能很密，不能每条都写日志。 */
    private fun reject(reason: String, advertisement: AirPodsAdvertisement): Boolean {
        rejectedAdvertisements++
        val now = System.currentTimeMillis()
        if (now - lastRejectionLoggedAt > 5000) {
            lastRejectionLoggedAt = now
            log(
                "丢弃 ${advertisement.mac()} rssi=${advertisement.rssi} 型号=0x%04X：%s"
                    .format(advertisement.modelId, reason),
            )
        }
        return false
    }

    private fun publish() {
        ScannerStatus.text = counters()

        val cutoff = System.currentTimeMillis() - LOST_AFTER_MS
        sides.forEach { if (it.seenAt < cutoff) it.advertisement = null }

        val model = pick { it.modelId != 0 }
        val left = pick { it.leftBattery != null }
        val right = pick { it.rightBattery != null }
        val box = pick { it.caseBattery != null }

        val fresh = AirPodsState(
            modelName = model?.modelName,
            left = left?.leftBattery,
            right = right?.rightBattery,
            case = box?.caseBattery,
            leftCharging = left?.leftCharging ?: false,
            rightCharging = right?.rightCharging ?: false,
            caseCharging = box?.caseCharging ?: false,
            lastSeenAt = lastSeenAt,
            lastRawHex = model?.raw?.joinToString("") { "%02X".format(it) },
            lastAddress = model?.mac(),
            lastRssi = model?.rssi,
        )

        val state = if (fresh.hasAnyReading) fresh else lastState ?: fresh
        lastState = state
        onState(state)
    }

    private fun checkWatchdog() {
        if (!scanning) return
        if (System.currentTimeMillis() - lastAnyAdvertisementAt < WATCHDOG_AFTER_MS) return

        log("$WATCHDOG_AFTER_MS ms 内没收到任何广播，重启扫描")
        restartCount++
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            adapter?.bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            lastAnyAdvertisementAt = System.currentTimeMillis()
        } catch (e: SecurityException) {
            log("重启扫描被拒：${e.message}")
        }
    }

    /** 两个侧面里挑持有该字段、且时间更近的那个。 */
    private fun pick(hasField: (AirPodsAdvertisement) -> Boolean): AirPodsAdvertisement? =
        sides.filter { it.advertisement?.let(hasField) == true }
            .maxByOrNull { it.seenAt }
            ?.advertisement

    private fun levelJump(a: Int?, b: Int?): Int =
        if (a == null || b == null) 0 else Math.abs(a - b) / 10

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun log(message: String) {
        Diagnostics.log(message)
        onLog(message)
    }
}
