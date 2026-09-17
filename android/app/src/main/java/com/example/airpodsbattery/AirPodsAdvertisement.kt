package com.example.airpodsbattery

/**
 * 一条苹果 0x07（AirPods「临近配对」）广播，也就是 AirPods 报电量的那条报文。
 *
 * 27 字节定长结构，字段布局与 Windows 端 C# 实现一致（两边都是从 AirPodsDesktop 的
 * `#pragma pack(1)` 结构体核对出来的）。
 *
 * 注意协议里**没有**「左耳」「右耳」这种字段：两只耳机各自广播同一个结构，用 [broadcastFromLeft]
 * 表明自己是哪一侧，低半字节永远是「发广播的那只」，高半字节是另一只。不按这个规则翻转，
 * 左右就必然读反。
 */
class AirPodsAdvertisement(
    val address: Long,
    val rssi: Int,
    val raw: ByteArray,
    val modelId: Int,
    val broadcastFromLeft: Boolean,
    private val currentLevel: Int,
    private val otherLevel: Int,
    private val caseLevel: Int,
    private val currentCharging: Boolean,
    private val otherCharging: Boolean,
    val caseCharging: Boolean,
    private val currentInEar: Boolean,
    private val otherInEar: Boolean,
    val bothInCase: Boolean,
    val lidClosed: Boolean,
) {
    val leftBattery: Int? get() = percent(if (broadcastFromLeft) currentLevel else otherLevel)
    val rightBattery: Int? get() = percent(if (broadcastFromLeft) otherLevel else currentLevel)
    val caseBattery: Int? get() = percent(caseLevel)

    val leftCharging: Boolean get() = if (broadcastFromLeft) currentCharging else otherCharging
    val rightCharging: Boolean get() = if (broadcastFromLeft) otherCharging else currentCharging

    val leftInEar: Boolean get() = !leftCharging && (if (broadcastFromLeft) currentInEar else otherInEar)
    val rightInEar: Boolean get() = !rightCharging && (if (broadcastFromLeft) otherInEar else currentInEar)

    val modelName: String
        get() = when (modelId) {
            0x2002 -> "AirPods 1"
            0x200F -> "AirPods 2"
            0x2013 -> "AirPods 3"
            0x200E -> "AirPods Pro"
            0x2014 -> "AirPods Pro 2"
            0x2024 -> "AirPods Pro 2 (USB-C)"
            0x2019 -> "AirPods 4"
            0x201B -> "AirPods 4 ANC"
            0x2027 -> "AirPods Pro 3"
            0x200A -> "AirPods Max"
            0x2012 -> "Beats Fit Pro"
            else -> "未知型号 0x%04X".format(modelId)
        }

    fun mac(): String = byteArrayOf(
        (address shr 40).toByte(), (address shr 32).toByte(), (address shr 24).toByte(),
        (address shr 16).toByte(), (address shr 8).toByte(), address.toByte(),
    ).joinToString(":") { "%02X".format(it) }

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        const val PROXIMITY_PAIRING_TYPE = 0x07
        const val PACKET_LENGTH = 27

        /** 电量半字节的取值上限，超过就表示「不可用」，和 0% 不是一回事。 */
        private const val MAX_LEVEL = 10

        fun parse(address: Long, rssi: Int, data: ByteArray): AirPodsAdvertisement? {
            if (data.size != PACKET_LENGTH) return null
            if (data[0].toInt() and 0xFF != PROXIMITY_PAIRING_TYPE) return null
            if (data[1].toInt() and 0xFF != PACKET_LENGTH - 2) return null

            val modelId = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
            val status = data[5].toInt() and 0xFF
            val pods = data[6].toInt() and 0xFF
            val box = data[7].toInt() and 0xFF
            val lid = data[8].toInt() and 0xFF

            return AirPodsAdvertisement(
                address = address,
                rssi = rssi,
                raw = data,
                modelId = modelId,
                broadcastFromLeft = status and 0b0010_0000 != 0,
                currentLevel = pods and 0x0F,
                otherLevel = pods shr 4,
                caseLevel = box and 0x0F,
                currentCharging = box and 0b0001_0000 != 0,
                otherCharging = box and 0b0010_0000 != 0,
                caseCharging = box and 0b0100_0000 != 0,
                currentInEar = status and 0b0000_0010 != 0,
                bothInCase = status and 0b0000_0100 != 0,
                otherInEar = status and 0b0000_1000 != 0,
                lidClosed = lid and 0b0000_1000 != 0,
            )
        }

        private fun percent(level: Int): Int? = if (level in 0..MAX_LEVEL) level * 10 else null
    }
}

/** 两只耳机各自的广播合并出来的当前状态。 */
data class AirPodsState(
    val modelName: String? = null,
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val lastSeenAt: Long? = null,
    val lastRawHex: String? = null,
    val lastAddress: String? = null,
    val lastRssi: Int? = null,
) {
    val hasAnyReading: Boolean get() = left != null || right != null || case != null

    fun summary(): String = when {
        !hasAnyReading -> "暂无广播"
        else -> buildString {
            append("左 ").append(left?.let { "$it%" } ?: "--")
            append("  右 ").append(right?.let { "$it%" } ?: "--")
            append("  盒 ").append(case?.let { "$it%" } ?: "--")
        }
    }
}
