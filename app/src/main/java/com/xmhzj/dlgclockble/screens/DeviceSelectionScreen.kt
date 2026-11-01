package com.xmhzj.dlgclockble.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.xmhzj.dlgclockble.BleManager
import com.xmhzj.dlgclockble.ConnectionState
import com.xmhzj.dlgclockble.ScannedDevice
import kotlinx.coroutines.delay

@SuppressLint("MissingPermission")
@Composable
fun DeviceSelectionScreen(navController: NavController) {
    val devices by BleManager.scannedDevices.collectAsState()
    val isScanning by BleManager.isScanning.collectAsState()
    val connectionState by BleManager.connectionState.collectAsState()
    var filterDlg by remember { mutableStateOf(true) }

    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // 初始按系统实际授权状态初始化，避免已授权时还闪"缺权限"提示
    val appContext = LocalContext.current.applicationContext
    var permissionGranted by remember {
        mutableStateOf(blePermissions.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.all { it }
    }

    // 蓝牙开关状态：监听系统广播保持同步(用户在快捷面板开关蓝牙也能实时反映)
    var bluetoothEnabled by remember { mutableStateOf(BleManager.isBluetoothEnabled()) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    bluetoothEnabled = state == BluetoothAdapter.STATE_ON
                }
            }
        }
        // ACTION_STATE_CHANGED是受保护系统广播，targetSdk 34无需指定exported标志
        appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { appContext.unregisterReceiver(receiver) }
    }

    // 蓝牙未开启时拉起系统"允许开启蓝牙"弹窗，同意后接着自动开始扫描
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 刚点完同意时bluetoothLeScanner可能尚未就绪，稍候再扫
            Handler(Looper.getMainLooper()).postDelayed({ BleManager.startScan() }, 500)
        }
    }
    val requestEnableBt = {
        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(blePermissions)
    }

    // 连接成功后跳转。只在状态"变为"SERVICE_READY时跳转：
    // 从设备页返回本页时若连接仍在(如未走disconnect的路径)，状态本来就是SERVICE_READY，
    // 不做变化检测会把设备页立刻再压回来，back键表现为关不掉页面
    var lastConnectionState by remember { mutableStateOf(BleManager.connectionState.value) }
    LaunchedEffect(connectionState) {
        val previous = lastConnectionState
        lastConnectionState = connectionState
        if (connectionState == ConnectionState.SERVICE_READY && previous != ConnectionState.SERVICE_READY) {
            navController.navigate("main") {
                // 保持 device_selection 页面在栈中，不清除它
                popUpTo("device_selection") { inclusive = false }
            }
        }
    }

    // 连接中的back取消连接而不是退出App
    BackHandler(enabled = connectionState == ConnectionState.CONNECTING) {
        BleManager.disconnect()
    }

    // OTA升级完成断开返回后自动重新扫描，刷新列表里的版本号(普通断开不触发)。
    // 设备重启到恢复广播约需1~2秒，延迟后再扫，避免设备还没开始广播就扫完了
    LaunchedEffect(Unit) {
        val handle = navController.currentBackStackEntry?.savedStateHandle
        if (handle?.get<Boolean>("auto_scan") == true) {
            handle["auto_scan"] = false
            delay(2000)
            if (!BleManager.isScanning.value) BleManager.startScan()
        }
    }

    val filteredDevices = if (filterDlg) {
        devices.filter { it.device.name?.startsWith("DCK") == true }
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
                when {
                    // 没权限时点按钮重新申请(拒绝过一次后系统不再弹框，需去设置手动开)
                    !permissionGranted -> permissionLauncher.launch(blePermissions)
                    // 蓝牙没开时先弹系统框提示开启，同意后自动开始扫描
                    !bluetoothEnabled -> requestEnableBt()
                    isScanning -> BleManager.stopScan()
                    else -> BleManager.startScan()
                }
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
            Text("只显示DCK设备")
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
            text = if (!permissionGranted) "缺少蓝牙权限，点击“开始扫描”重新授权(或到系统设置开启)"
            else if (!bluetoothEnabled) "蓝牙未开启，点击“开始扫描”开启蓝牙"
            else if (isScanning) "正在扫描设备..."
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
                    // connectGatt/device.name在Android 12+缺CONNECT权限会抛SecurityException
                    when {
                        !permissionGranted -> permissionLauncher.launch(blePermissions)
                        !bluetoothEnabled -> requestEnableBt()
                        else -> BleManager.connect(item.device, item.version)
                    }
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
