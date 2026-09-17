package com.example.airpodsbattery

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** 安卓 12 起要 BLUETOOTH_SCAN/CONNECT，之前要定位权限；13 起通知也要运行时申请。 */
    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    private fun Screen() {
        val context = this
        val scope = rememberCoroutineScope()

        var state by remember { mutableStateOf(BatteryStore.load(context)) }
        var logs by remember { mutableStateOf(Diagnostics.snapshot()) }
        var permission by remember { mutableStateOf(hasAllPermissions()) }
        var running by remember { mutableStateOf(BatteryService.isRunning) }
        var probing by remember { mutableStateOf(false) }
        var probeResult by remember { mutableStateOf<String?>(null) }
        var scannerStatus by remember { mutableStateOf(ScannerStatus.text) }

        LaunchedEffect(Unit) {
            while (true) {
                state = BatteryStore.load(context)
                logs = Diagnostics.snapshot()
                permission = hasAllPermissions()
                running = BatteryService.isRunning
                scannerStatus = ScannerStatus.text
                delay(1000)
            }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("AirPods 电量", style = MaterialTheme.typography.headlineSmall)

                Spacer(Modifier.height(12.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = if (state.hasAnyReading) state.summary() else "暂无广播",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append(state.modelName ?: "未识别到耳机")
                                state.lastRssi?.let { append("   信号 $it dBm") }
                                state.lastSeenAt?.let {
                                    append("   最后一次 ")
                                    append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)))
                                }
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!permission) {
                        Button(onClick = { requestPermissions.launch(requiredPermissions) }) {
                            Text("授予扫描权限")
                        }
                    }
                    if (running) {
                        OutlinedButton(onClick = { BatteryService.stop(context) }) { Text("停止服务") }
                    } else {
                        Button(onClick = { BatteryService.start(context) }) { Text("启动服务") }
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("诊断", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "定位权限：${if (permission) "已授予" else "未授予"}\n" +
                        "前台服务：${if (running) "运行中" else "未运行"}\n" +
                        "扫描计数：$scannerStatus",
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        probing = true
                        probeResult = null
                        scope.launch {
                            val adapter = BluetoothAdapter.getDefaultAdapter()
                            val outcome = withContext(Dispatchers.IO) { AapProbe.run(adapter) }
                            probeResult = outcome.log
                            probing = false
                        }
                    },
                    enabled = !probing,
                ) {
                    Text(if (probing) "探测中…" else "测试 AAP 通道（能否拿到精确电量）")
                }

                probeResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("运行日志", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {
                        val text = logs.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AirPods 日志", text))
                        Toast.makeText(context, "已复制 ${logs.size} 行", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, logs.joinToString("\n"))
                        }
                        context.startActivity(Intent.createChooser(share, "分享日志"))
                    }) { Text("分享") }
                }
                Spacer(Modifier.height(8.dp))

                if (logs.isEmpty()) {
                    Text("（暂无）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        logs.take(80).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
