package com.example.airpodsbattery

/** "AA:BB:CC:DD:EE:FF" -> 0xAABBCCDDEEFF，和 BLE 广播回调里给的地址格式一致。 */
fun String.toLongAddress(): Long =
    replace(":", "").replace("-", "").toLongOrNull(16) ?: 0L

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun ByteArray.toPrintableHex(): String = joinToString(" ") { "%02X".format(it) }
