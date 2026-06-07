package com.xmhzj.dlgclockble.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmhzj.dlgclockble.BleManager
import com.xmhzj.dlgclockble.ConnectionState

@Composable
fun MainScreen(onDisconnect: () -> Unit) {
    val connectionState by BleManager.connectionState.collectAsState()
    val deviceName by BleManager.deviceName.collectAsState()
    val voltage by BleManager.voltage.collectAsState()
    val systemTime by BleManager.systemTime.collectAsState()
    val logs by BleManager.logs.collectAsState()
    val firmwareProgress by BleManager.firmwareProgress.collectAsState()
    val isSyncing by BleManager.isSyncing.collectAsState()
    val isUpdating by BleManager.isUpdating.collectAsState()
    val isSending90 by BleManager.isSending90.collectAsState()
    val functionsReady = connectionState == ConnectionState.SERVICE_READY

    val firmwareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { BleManager.sendFirmware(it) }
    }

    // 断开连接时返回
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            onDisconnect()
        }
    }

    val logListState = rememberLazyListState()
    // 自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Color(0xFFF0F9FF))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 关闭按钮
            IconButton(onClick = { BleManager.disconnect() }) {
                Text("✕", fontSize = 24.sp, color = Color(0xFFEF4444))
            }
            // 标题
            Text(
                text = "HM CLOCK Control Center",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3B82F6),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            // 占位，保持标题居中
            Spacer(modifier = Modifier.size(48.dp))
        }

        // 主卡片
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 按钮网格 (2列)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FuncButton(
                        text = if (isSyncing) "同步中..." else "同步时间",
                        color = Color(0xFFF59E0B),
                        enabled = functionsReady && !isSyncing,
                        onClick = { BleManager.syncTime() },
                        modifier = Modifier.weight(1f)
                    )
                    FuncButton(
                        text = if (isUpdating) "升级中..." else "固件升级",
                        color = Color(0xFF8B5CF6),
                        enabled = functionsReady && !isUpdating,
                        onClick = { firmwareLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FuncButton(
                        text = if (isSending90) "发送中..." else "切换时制",
                        color = Color(0xFF10B981),
                        enabled = functionsReady && !isSending90,
                        onClick = { BleManager.switchTimeFormat() },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 设备信息区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF9FAFB), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    InfoRow(label = "设备名称", value = deviceName)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "当前电压", value = voltage)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow(label = "系统时间", value = systemTime)
                }

                // 固件进度条
                if (firmwareProgress >= 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("固件升级进度", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { firmwareProgress / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(12.dp),
                            color = Color(0xFF3B82F6),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("$firmwareProgress%", fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 日志区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设备日志", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    TextButton(onClick = { BleManager.clearLog() }) {
                        Text("清空日志", fontSize = 12.sp)
                    }
                }

                LazyColumn(
                    state = logListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(Color(0xFF1F2937), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            color = Color(0xFF4ADE80),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FuncButton(
    text: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = color.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF6B7280), fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color(0xFF1F2937))
    }
}
