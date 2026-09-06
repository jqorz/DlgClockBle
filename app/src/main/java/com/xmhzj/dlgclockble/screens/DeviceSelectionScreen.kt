package com.xmhzj.dlgclockble.screens

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.xmhzj.dlgclockble.BleManager
import com.xmhzj.dlgclockble.ConnectionState
import com.xmhzj.dlgclockble.ScannedDevice

@SuppressLint("MissingPermission")
@Composable
fun DeviceSelectionScreen(navController: NavController) {
    val devices by BleManager.scannedDevices.collectAsState()
    val isScanning by BleManager.isScanning.collectAsState()
    val connectionState by BleManager.connectionState.collectAsState()
    var filterDlg by remember { mutableStateOf(true) }
    var permissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    // 连接成功后跳转
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.SERVICE_READY) {
            val name = BleManager.deviceName.value
            navController.navigate("main") {
                // 保持 device_selection 页面在栈中，不清除它
                popUpTo("device_selection") { inclusive = false }
            }
        }
    }

    val filteredDevices = if (filterDlg) {
        devices.filter { it.device.name?.startsWith("DLG") == true }
    } else {
        devices
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "选择BLE设备",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        // 扫描按钮
        Button(
            onClick = {
                if (isScanning) BleManager.stopScan() else BleManager.startScan()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(if (isScanning) "停止扫描" else "开始扫描")
        }

        // 过滤选项
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Checkbox(
                checked = filterDlg,
                onCheckedChange = { filterDlg = it }
            )
            Text("只显示DLG设备")
        }

        // 进度条
        if (isScanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        // 状态文字
        Text(
            text = if (isScanning) "正在扫描设备..."
            else if (devices.isNotEmpty()) "扫描完成，共发现 ${filteredDevices.size} 个设备"
            else "点击按钮扫描附近设备",
            fontSize = 14.sp,
            color = Color(0xFF666666),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 设备列表
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(filteredDevices, key = { it.device.address }) { item ->
                DeviceItem(item = item) {
                    BleManager.connect(item.device, item.version)
                }
            }
        }
    }

    // 连接中弹窗
    if (connectionState == ConnectionState.CONNECTING) {
        ConnectingDialog(
            deviceName = BleManager.deviceName.value,
            onCancel = { BleManager.disconnect() }
        )
    }
}

@Composable
private fun DeviceItem(item: ScannedDevice, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = item.device.name ?: "未知设备",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF333333)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.device.address,
                fontSize = 12.sp,
                color = Color(0xFF999999)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.version?.let { "固件 v$it" } ?: "版本未知",
                fontSize = 12.sp,
                color = Color(0xFF2E7D32)
            )
        }
    }
}

@Composable
private fun ConnectingDialog(deviceName: String, onCancel: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("正在连接: $deviceName")
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Text("取消")
            }
        }
    }
}
