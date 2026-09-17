package com.example.airpodsbattery

/** "AA:BB:CC:DD:EE:FF" -> 0xAABBCCDDEEFF，和 BLE 广播回调里给的地址格式一致。 */
fun String.toLongAddress(): Long =
    replace(":", "").replace("-", "").toLongOrNull(16) ?: 0L

// Kotlin 的 Byte 是有符号的。直接 "%02X".format(byte) 遇到高位为 1 的字节会输出负十进制数
// （-103 之类），必须先 and 0xFF 转成 0..255 的无符号值。
private fun Byte.hex(): String = "%02X".format(this.toInt() and 0xFF)

fun ByteArray.toHex(): String = joinToString("") { it.hex() }

fun ByteArray.toPrintableHex(): String = joinToString(" ") { it.hex() }
