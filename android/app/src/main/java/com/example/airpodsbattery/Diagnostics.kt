package com.example.airpodsbattery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫描器跑在服务里，诊断页跑在 Activity 里，两者不在同一个实例上，所以日志放在这个共享对象里。
 */
/**
 * 扫描器的实时计数。用来区分两种完全不同的"暂无广播"：扫描器根本没工作（收到 0 条广播），
 * 还是扫描正常、只是耳机没在广播（收到很多条但没有苹果 0x07）。
 */
object ScannerStatus {
    @Volatile
    var text: String = "扫描器未启动"
}

object Diagnostics {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(message: String) {
        val line = "${formatter.format(Date())}  $message"
        synchronized(lines) {
            lines.addFirst(line)
            while (lines.size > MAX_LINES) lines.removeLast()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
