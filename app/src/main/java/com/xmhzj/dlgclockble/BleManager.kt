package com.xmhzj.dlgclockble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tyme.solar.SolarDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Calendar
import java.util.Timer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.TimerTask
import java.util.UUID
import kotlin.concurrent.Volatile
import kotlin.math.min

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, SERVICE_READY
}

// 扫描到的设备及其广播中的固件版本号(EPD_VERSION低16位，厂商AD数据携带，null=未解析到)
data class ScannedDevice(val device: BluetoothDevice, val version: Int?)

// 固件文件解析结果(版本号在镜像内魔数后偏移+8处，16位小端)
data class FirmwareInfo(val version: Int, val size: Int, val crc: Int)

@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "BleManager"

    private const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    private const val CTRL_POINT_UUID = "0000ff03-0000-1000-8000-00805f9b34fb"
    private const val ADC1_VALUE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    private const val LONG_VALUE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    // 固件镜像内嵌的版本号定位魔数，版本号位于魔数偏移+8(16位小端)
    private val FIRMWARE_MAGIC = byteArrayOf(
        0x79.toByte(), 0x13.toByte(), 0xa5.toByte(), 0xf9.toByte(),
        0x86.toByte(), 0xec.toByte(), 0x5a.toByte(), 0x06.toByte()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var ctrlPointChar: BluetoothGattCharacteristic? = null
    private var adc1ValueChar: BluetoothGattCharacteristic? = null
    private var longValueChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    // 扫描超时专用Runnable，stopScan只移除它；不能removeCallbacksAndMessages(null)，
    // 那会把mainHandler上其他排队的操作(syncTime/校准的读写在post)一起清掉导致静默丢写
    private val scanTimeoutRunnable = Runnable {
        if (scanCallback != null) stopScan()
    }
    // CCCD使能进度：描述符写必须串行(背靠背写第二个会被GATT栈当busy拒绝)，见enableNextNotification
    private var notifyIndex = 0
    private val gattLock = Any()
    private val deviceTimeLock = Any()
    @Volatile private var suppressDeviceTimeLog = false
    private var systemTimeTimer: Timer? = null
    private var context: Context? = null

    @Volatile
    private var deviceTimeBaseTimestamp: Long = 0

    @Volatile
    private var deviceTimeYear = 0

    @Volatile
    private var deviceTimeMonth = 0

    @Volatile
    private var deviceTimeDay = 0

    @Volatile
    private var deviceTimeHour = 0

    @Volatile
    private var deviceTimeMinute = 0

    @Volatile
    private var deviceTimeSecond = 0

    // --- StateFlows ---

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _voltage = MutableStateFlow("--")
    val voltage: StateFlow<String> = _voltage.asStateFlow()

    private val _systemTime = MutableStateFlow("")
    val systemTime: StateFlow<String> = _systemTime.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _firmwareProgress = MutableStateFlow(-1)  // -1 = hidden
    val firmwareProgress: StateFlow<Int> = _firmwareProgress.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _isSending90 = MutableStateFlow(false)
    val isSending90: StateFlow<Boolean> = _isSending90.asStateFlow()

    // 当前连接设备广播中的固件版本号(来自扫描列表)，null=未知
    private val _deviceVersion = MutableStateFlow<Int?>(null)
    val deviceVersion: StateFlow<Int?> = _deviceVersion.asStateFlow()

    private var lastSyncTimestamp: Long = 0

    fun init(appContext: Context) {
        context = appContext.applicationContext
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        lastSyncTimestamp = appContext.getSharedPreferences("calibration", Context.MODE_PRIVATE)
            .getLong("last_sync_timestamp", 0)
        startSystemTimeUpdate()
    }

    // --- Scanning ---

    // 蓝牙开关是否打开(供UI在扫描前检查提示)
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun startScan() {
        val adapter = bluetoothAdapter ?: run {
            addLog("蓝牙适配器不可用")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            addLog("蓝牙未开启，无法扫描")
            return
        }
        // 防重入：旧callback不停掉会泄漏，且旧的5s超时会提前杀掉新一轮扫描
        if (_isScanning.value) stopScan()
        _scannedDevices.value = emptyList()
        _isScanning.value = true

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val deviceName = device.name
                if (deviceName != null && deviceName.isNotEmpty()) {
                    val version = parseAdvVersion(result.scanRecord)
                    val current = _scannedDevices.value
                    val idx = current.indexOfFirst { it.device.address == device.address }
                    if (idx == -1) {
                        _scannedDevices.value = current + ScannedDevice(device, version)
                    } else if (version != null && current[idx].version != version) {
                        // 广播重复到达时刷新为最新版本
                        _scannedDevices.value = current.toMutableList().apply { set(idx, ScannedDevice(device, version)) }
                    }
                }
            }
        }

        val filters = listOf(ScanFilter.Builder().build())
        try {
            scanner.startScan(
                filters,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback!!
            )
        } catch (e: SecurityException) {
            addLog("扫描失败: 缺少蓝牙权限 (${e.message})")
            scanCallback = null
            _isScanning.value = false
            return
        }

        mainHandler.postDelayed(scanTimeoutRunnable, 5000)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        scanCallback?.let { callback ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: SecurityException) {
                // 无权限时stopScan也可能抛，忽略即可
            }
            scanCallback = null
        }
        _isScanning.value = false
    }

    // --- Connection ---

    fun connect(device: BluetoothDevice, fwVersion: Int? = null) {
        stopScan()
        // 上一次的gatt若还挂着(异常路径没走到disconnect)，先释放再连新的
        synchronized(gattLock) {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        notifyIndex = 0
        otaDelivered = false
        _deviceVersion.value = fwVersion
        _connectionState.value = ConnectionState.CONNECTING
        _deviceName.value = device.name ?: device.address
        addLog("正在连接设备: ${_deviceName.value}${fwVersion?.let { " (固件 v$it)" } ?: ""}")

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        synchronized(gattLock) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }

        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = ""
        _voltage.value = "--"
        _deviceVersion.value = null
        addLog("设备已断开连接")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=0x${status.toString(16)} newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                addLog("已连接 (status=0x${status.toString(16)})")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // 先协商MTU(默认23会把136字节OTA包拆成9个ATT事务，慢且易积压丢包)，
                // 协商完成再发现服务。设备端max_mtu=192，185足够单事务发包
                if (!gatt.requestMtu(185)) {
                    addLog("MTU协商请求失败，使用默认MTU")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "BLE DISCONNECTED status=0x${status.toString(16)}")
                addLog("BLE断开! status=0x${status.toString(16)}")
                disconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            addLog("MTU协商完成: $mtu (status=0x${status.toString(16)})")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 不处理的话会永远停在CONNECTING(界面转圈弹窗卡死)，必须落到断开让UI恢复
                addLog("服务发现失败: status=0x${status.toString(16)}")
                disconnect()
                return
            }
            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            if (service == null) {
                addLog("错误: 找不到服务UUID")
                disconnect()
                return
            }
            ctrlPointChar = service.getCharacteristic(UUID.fromString(CTRL_POINT_UUID))
            adc1ValueChar = service.getCharacteristic(UUID.fromString(ADC1_VALUE_UUID))
            longValueChar = service.getCharacteristic(UUID.fromString(LONG_VALUE_UUID))
            notifyIndex = 0
            enableNextNotification(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("descriptor写入失败: 0x${status.toString(16)}")
            }
            // CCCD逐个写：一个写完回调到达后再写下一个
            if (gatt != null) enableNextNotification(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value
                if (characteristic.uuid.toString() == ADC1_VALUE_UUID) {
                    val voltage = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val voltageValue = voltage / 1000.0
                    _voltage.value = String.format("%.2f V", voltageValue)
                    addLog("当前电压: ${_voltage.value}")
                } else if (characteristic.uuid.toString() == LONG_VALUE_UUID) {
                    val hexStr = value.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                    Log.d(TAG, "LONG_VALUE raw [${value.size}B]: $hexStr")

                    synchronized(deviceTimeLock) {
                        deviceTimeYear = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
                        deviceTimeMonth = value[2].toInt()
                        deviceTimeDay = value[3].toInt()
                        deviceTimeHour = value[4].toInt()
                        deviceTimeMinute = value[5].toInt()
                        deviceTimeSecond = value[6].toInt()
                        deviceTimeBaseTimestamp = System.currentTimeMillis()
                    }

                    if (!suppressDeviceTimeLog) {
                        synchronized(deviceTimeLock) {
                            addLog("设备时间: ${formatTime(deviceTimeYear, deviceTimeMonth, deviceTimeDay, deviceTimeHour, deviceTimeMinute, deviceTimeSecond)}")
                        }
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val uuid = characteristic?.uuid?.toString() ?: "unknown"
            // 完成OTA线程等待的写确认(Write Response)
            val latch = pendingWriteAck
            if (latch != null) {
                pendingWriteOk = (status == BluetoothGatt.GATT_SUCCESS)
                latch.countDown()
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "onCharacteristicWrite SUCCESS uuid=$uuid")
                } else {
                    Log.e(TAG, "onCharacteristicWrite FAILED uuid=$uuid status=0x${status.toString(16)}")
                }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite SUCCESS uuid=$uuid")
            } else {
                Log.e(TAG, "onCharacteristicWrite FAILED uuid=$uuid status=0x${status.toString(16)}")
                addLog("写入失败: uuid=$uuid status=0x${status.toString(16)}")
            }
        }
    }

    // 使能通知(写CCCD)必须逐个串行：背靠背两个writeDescriptor在多数GATT栈上第二个
    // 会因busy被拒，通知静默不生效。每写完一个在onDescriptorWrite回调里继续下一个，
    // 全部写完做初始读(电压/设备时间)并置SERVICE_READY
    private fun enableNextNotification(gatt: BluetoothGatt) {
        val chars = listOfNotNull(adc1ValueChar, longValueChar)
        while (notifyIndex < chars.size) {
            val c = chars[notifyIndex]
            notifyIndex++
            gatt.setCharacteristicNotification(c, true)
            val descriptor = c.getDescriptor(UUID.fromString(CCCD_UUID))
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (gatt.writeDescriptor(descriptor)) {
                    return  // 等onDescriptorWrite回调继续
                }
                addLog("写CCCD被拒(busy?)，继续: ${c.uuid}")
            }
        }
        // 写类型由writeCharacteristic按参数统一设置(WRITE_TYPE_DEFAULT)，
        // 设备端仅开通有响应写权限，不能预设NO_RESPONSE(无Write Response回调)
        adc1ValueChar?.let { gatt.readCharacteristic(it) }
        longValueChar?.let { gatt.readCharacteristic(it) }
        addLog("服务已就绪")
        _connectionState.value = ConnectionState.SERVICE_READY
    }

    // --- System time ---

    private fun startSystemTimeUpdate() {
        systemTimeTimer?.cancel()
        systemTimeTimer = Timer()
        systemTimeTimer!!.schedule(object : TimerTask() {
            override fun run() {
                val now = Calendar.getInstance()
                _systemTime.value = formatTime(
                    now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH),
                    now.get(Calendar.DAY_OF_MONTH),
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND)
                )
            }
        }, 0, 1000)
    }

    private fun formatTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String {
        return String.format("%d-%02d-%02d %02d:%02d:%02d", year, month + 1, day, hour, minute, second)
    }

    // --- Commands ---

    fun syncTime() {
        if (longValueChar == null || bluetoothGatt == null) {
            addLog("错误: 未连接设备或服务未就绪")
            return
        }
        _isSyncing.value = true
        addLog("同步时间中...")

        Thread {
            try {
                // --- 自动校准：在同步前检测是否需要校准 ---
                // 校准必须在同步之前执行，因为它测量的是上次同步以来的漂移
                // 同步会更新 lastSyncTimestamp，之后 calMinute 就变成 0 了
                val calMinute = if (lastSyncTimestamp == 0L) -1
                else ((System.currentTimeMillis() - lastSyncTimestamp) / 60000).toInt()

                if (calMinute >= 2880) {
                    addLog("检测到距上次对时已超过2天，自动校准中...")
                    suppressDeviceTimeLog = true
                    try {
                        mainHandler.post { bluetoothGatt?.readCharacteristic(longValueChar) }
                        Thread.sleep(500)

                        var devSecond = 0
                        var devMinute = 0
                        synchronized(deviceTimeLock) {
                            devSecond = deviceTimeSecond
                            devMinute = deviceTimeMinute
                        }

                        val now = Calendar.getInstance()
                        var sysMinute = now.get(Calendar.MINUTE)
                        val sysSecond = now.get(Calendar.SECOND)

                        var calcDevMinute = devMinute
                        if (calcDevMinute > sysMinute) {
                            if (calcDevMinute - sysMinute > 50) sysMinute += 60
                        } else {
                            if (sysMinute - calcDevMinute > 50) calcDevMinute += 60
                        }

                        val diff = (calcDevMinute * 60 + devSecond) - (sysMinute * 60 + sysSecond)

                        // 偏差超过10分钟说明设备刚重启/未对时(显示默认时间)，是无效样本：
                        // 正常漂移每天仅数秒。此时发0x92会污染固件校准值(旧固件甚至停走)，跳过只对时
                        if (diff in -600..600) {
                            val calBuf = ByteArray(3)
                            calBuf[0] = 0x92.toByte()
                            calBuf[1] = (diff and 0xFF).toByte()
                            calBuf[2] = ((diff shr 8) and 0xFF).toByte()
                            writeCharacteristic(longValueChar, calBuf)
                            addLog("自动校准完成: 误差${diff}秒")
                        } else {
                            addLog("设备时间偏差${diff}秒(可能刚重启未对时)，跳过校准仅同步时间")
                        }
                        Thread.sleep(500)
                    } finally {
                        suppressDeviceTimeLog = false
                    }
                }

                // --- 同步时间 ---
                val startTime = System.currentTimeMillis()
                val waitTime = 1000 - (startTime % 1000)
                if (waitTime > 10) Thread.sleep(waitTime)

                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val month = now.get(Calendar.MONTH)
                val day = now.get(Calendar.DAY_OF_MONTH)
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val second = now.get(Calendar.SECOND)
                val wday = now.get(Calendar.DAY_OF_WEEK) - 1

                addLog(String.format("准备同步: %d-%02d-%02d %02d:%02d:%02d wday=%d", year, month + 1, day, hour, minute, second, wday))

                val buf = ByteArray(12)
                buf[0] = 0x91.toByte()
                buf[1] = (year % 256).toByte()
                buf[2] = (year / 256).toByte()
                buf[3] = month.toByte()
                buf[4] = day.toByte()
                buf[5] = hour.toByte()
                buf[6] = minute.toByte()
                buf[7] = second.toByte()
                buf[8] = wday.toByte()

                val solarDay = SolarDay.fromYmd(year, month + 1, day)
                val lunarDay = solarDay.getLunarDay()
                val lunarMonthVal = lunarDay.month
                buf[9] = (lunarDay.year - 2020).toByte()
                buf[10] = if (lunarMonthVal < 0) {
                    ((-lunarMonthVal - 1) or 0x80).toByte()
                } else {
                    (lunarMonthVal - 1).toByte()
                }
                buf[11] = lunarDay.day.toByte()

                addLog(String.format("写入数据: %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                    buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7], buf[8], buf[9], buf[10], buf[11]))

                mainHandler.post { writeCharacteristic(longValueChar, buf) }
                // 等待设备端处理写入并更新BLE数据库
                Thread.sleep(1000)

                mainHandler.post { bluetoothGatt?.readCharacteristic(longValueChar) }
                Thread.sleep(500)

                val readYear: Int
                val readMonth: Int
                val readDay: Int
                val readHour: Int
                val readMinute: Int
                val readSecond: Int
                synchronized(deviceTimeLock) {
                    readYear = deviceTimeYear
                    readMonth = deviceTimeMonth
                    readDay = deviceTimeDay
                    readHour = deviceTimeHour
                    readMinute = deviceTimeMinute
                    readSecond = deviceTimeSecond
                }

                addLog(String.format("设备回传时间: %d-%02d-%02d %02d:%02d:%02d",
                    readYear, readMonth + 1, readDay, readHour, readMinute, readSecond))

                if (readYear != year || readMonth != month || readDay != day
                    || readHour != hour || readMinute != minute) {
                    addLog("警告: 设备时间与发送时间不一致，同步可能未生效")
                }
                lastSyncTimestamp = System.currentTimeMillis()
                context?.getSharedPreferences("calibration", Context.MODE_PRIVATE)?.edit()
                    ?.putLong("last_sync_timestamp", lastSyncTimestamp)?.apply()
                addLog("同步时间完成!")
            } catch (e: Exception) {
                addLog("同步时间失败: ${e.message}")
            } finally {
                _isSyncing.value = false
            }
        }.start()
    }

    fun sendFirmware(uri: Uri) {
        if (longValueChar == null || bluetoothGatt == null) {
            addLog("错误: 未连接设备或服务未就绪")
            return
        }
        _isUpdating.value = true
        _firmwareProgress.value = 0
        addLog("准备升级固件...")

        Thread {
            try {
                val firmBuf = context?.contentResolver?.openInputStream(uri)
                    ?.use { readAllBytes(it) } ?: throw Exception("无法打开文件")

                val firmSize = firmBuf.size
                addLog("固件大小: $firmSize")

                val firmInfo = parseFirmwareInfo(firmBuf) ?: run {
                    addLog("无效固件: 未找到版本号!")
                    return@Thread
                }
                val firmVer = firmInfo.version
                val firmCrc = firmInfo.crc
                addLog("固件版本: $firmVer 大小: $firmSize CRC: ${Integer.toHexString(firmCrc)}")

                // 镜像槽容量 = 0xC000(48KB) - 64B头 = 49088字节；超限说明选错了文件
                if (firmSize > 49088 || firmSize < 1024) {
                    addLog("错误: 固件大小 $firmSize 字节不合法(允许1024~49088)。请选择RAM运行版bin(如ble_app_peripheral_585.bin，约44KB)，不要选fw_multi_part_spi.bin(整块flash布局镜像)")
                    return@Thread
                }

                // 有响应写：兼容所有固件版本(无响应写需固件带PERM(WRITE_COMMAND)权限，
                // 但该权限的构建曾出现OTA后启动即死，已放弃)。每包等待设备Write Response
                addLog("传输模式: 有响应写(每包等设备确认)")

                val buf = ByteArray(136)
                buf[0] = 0xa0.toByte()
                ByteBuffer.wrap(buf, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(firmSize.toShort())
                writeWithRetry(longValueChar, buf)
                Thread.sleep(200)

                var sent = 0
                val totalSize = firmSize + 64
                // 固件OTA协议：flash每页256字节 = 0xa2(页前128字节) + 0xa3(页后128字节，收到0xa3才整页写入)
                // 首包0xa2的[8..40]为64字节image header，[72..135]为固件前64字节
                var frontHalf = true // 下一包是否为页前半(0xa2)

                var i = 0
                while (i < totalSize) {
                    Arrays.fill(buf, 0xff.toByte())
                    if (i == 0) {
                        ByteBuffer.wrap(buf, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x00aa5170)
                        ByteBuffer.wrap(buf, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmSize)
                        ByteBuffer.wrap(buf, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmCrc)
                        ByteBuffer.wrap(buf, 36, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-0x5af10000 + firmVer)
                        buf[40] = 0
                        System.arraycopy(firmBuf, sent, buf, 72, 64)
                        sent += 64
                    } else {
                        val copyLen = min(128, firmBuf.size - sent)
                        if (copyLen <= 0) break
                        System.arraycopy(firmBuf, sent, buf, 8, copyLen)
                        sent += copyLen
                    }
                    buf[0] = if (frontHalf) 0xa2.toByte() else 0xa3.toByte()
                    writeWithRetry(longValueChar, buf, logBytes = (i == 0))
                    frontHalf = !frontHalf
                    Thread.sleep(30)

                    val progress = min((100.0 * sent / totalSize).toInt(), 100)
                    _firmwareProgress.value = progress
                    i += if (i == 0) 64 else 128
                }

                // 最后一包若是页前半(0xa2)，补一包0xa3(0xff填充)把最后的半页刷进flash
                if (!frontHalf) {
                    Arrays.fill(buf, 0xff.toByte())
                    buf[0] = 0xa3.toByte()
                    writeWithRetry(longValueChar, buf, logBytes = false)
                    Thread.sleep(30)
                }

                // 全部固件数据已送达并确认。在此置"OTA完成"标记而不是等0xa4发完：
                // 新固件写完最后一页会立即重启断链，若断开回调先到、标记还没置上，
                // 列表页就不会自动重扫了。此后无论哪种方式断开都算升级后断开
                otaDelivered = true

                // 0xa4=升级结束(旧固件靠它复位；新固件写完最后一页会自行断开重启，
                // 此时0xa4撞上断链属正常，不能当失败)
                buf[0] = 0xa4.toByte()
                Arrays.fill(buf, 1, 4, 0.toByte())
                try {
                    writeWithRetry(longValueChar, buf)
                    addLog("升级完成，设备将自动重启")
                } catch (e: Exception) {
                    if (bluetoothGatt == null || _connectionState.value == ConnectionState.DISCONNECTED) {
                        addLog("升级完成，设备已重启(连接断开属正常)")
                    } else {
                        throw e
                    }
                }
            } catch (e: Exception) {
                addLog("升级失败: ${e.message}")
            } finally {
                _isUpdating.value = false
                _firmwareProgress.value = -1
            }
        }.start()
    }

    fun switchTimeFormat() {
        if (longValueChar == null || bluetoothGatt == null) {
            addLog("错误: 未连接设备或服务未就绪")
            return
        }
        _isSending90.value = true
        addLog("发送切换命令...")

        Thread {
            try {
                val buf = byteArrayOf(0x90.toByte())
                mainHandler.post { writeCharacteristic(longValueChar, buf) }
                Thread.sleep(300)
                addLog("已发送切换命令")
            } catch (e: Exception) {
                addLog("发送失败: ${e.message}")
            } finally {
                _isSending90.value = false
            }
        }.start()
    }

    // --- Helpers ---

    // --- 固件文件解析(升级前弹窗确认用) ---

    // 读取并解析固件文件：返回版本号/大小/CRC，无法解析返回null
    fun parseFirmwareInfo(uri: Uri): FirmwareInfo? {
        return try {
            val firmBuf = context?.contentResolver?.openInputStream(uri)
                ?.use { readAllBytes(it) } ?: return null
            parseFirmwareInfo(firmBuf)
        } catch (e: Exception) {
            Log.e(TAG, "读取固件文件失败: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            // 误选超大文件时readAllBytes可能OOM，按解析失败处理而不是崩溃
            Log.e(TAG, "固件文件过大，读取失败")
            null
        }
    }

    private fun parseFirmwareInfo(firmBuf: ByteArray): FirmwareInfo? {
        val pos = findPattern(firmBuf, FIRMWARE_MAGIC)
        // 版本号16位取魔数偏移+8..+9；魔数恰好在文件末尾时取不到，视为无效
        // (不判越界，截断文件在这里会直接IndexOutOfBoundsException崩溃)
        if (pos == -1 || pos + 10 > firmBuf.size) return null
        return FirmwareInfo(firmBuf[pos + 9] * 256 + firmBuf[pos + 8], firmBuf.size, crc32(firmBuf))
    }

    // 解析广播原始数据中的厂商AD(类型0xFF)。固件把EPD_VERSION低16位放在厂商数据前两字节(小端)
    private fun parseAdvVersion(record: android.bluetooth.le.ScanRecord?): Int? {
        val bytes = record?.bytes ?: return null
        var i = 0
        while (i + 1 < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > bytes.size) break
            if ((bytes[i + 1].toInt() and 0xFF) == 0xFF && len >= 3) {
                return (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
            }
            i += 1 + len
        }
        return null
    }

    private fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic?,
        data: ByteArray,
        logBytes: Boolean = true,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean {
        if (characteristic == null || bluetoothGatt == null) {
            addLog("错误: characteristic或gatt为null")
            return false
        }
        // OTA数据包有几百包，逐包打印字节会刷屏；logBytes=false时跳过字节dump
        if (logBytes) {
            val hexStr = data.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
            addLog("发送数据 [${data.size} bytes]: $hexStr")
        }
        synchronized(gattLock) {
            try {
                characteristic.setWriteType(writeType)
                // API33+的3参writeCharacteristic返回Int(BluetoothStatusCodes.SUCCESS)，旧版返回Boolean，统一成Boolean
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt!!.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.setValue(data)
                    bluetoothGatt!!.writeCharacteristic(characteristic)
                }
                if (!ok) Log.w(TAG, "writeCharacteristic rejected (busy?) uuid=${characteristic.uuid}")
                return ok
            } catch (e: Exception) {
                addLog("写入失败: ${e.message}")
                return false
            }
        }
    }

    // 待确认写操作桥接：OTA线程提交写后在onCharacteristicWrite收到Write Response时释放
    @Volatile private var pendingWriteAck: CountDownLatch? = null
    @Volatile private var pendingWriteOk = false

    // OTA固件数据是否已全部送达(最后一页Write Response已确认)。
    // 断开返回列表页时消费该标记，决定是否自动重扫刷新版本号；普通断开不重扫
    @Volatile private var otaDelivered = false

    // 读取并清除"OTA数据已全部送达"标记
    fun consumeOtaDelivered(): Boolean {
        val delivered = otaDelivered
        otaDelivered = false
        return delivered
    }

    // 带重试的写入：提交后等待设备回Write Response才发下一包。
    // 只依赖writeCharacteristic的返回值(仅表示已入队)会在栈内队列积压时静默丢包——
    // OTA末尾的0xa4命令就是这样丢的，设备收到0xa0后ota_state不清零，界面永久冻结。
    private fun writeWithRetry(
        characteristic: BluetoothGattCharacteristic?,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        logBytes: Boolean = true
    ) {
        var retry = 0
        while (true) {
            if (bluetoothGatt == null) throw IllegalStateException("连接已断开")
            val latch = CountDownLatch(1)
            pendingWriteAck = latch
            pendingWriteOk = false
            if (writeCharacteristic(characteristic, data, logBytes, writeType)) {
                // 有响应写：等设备的Write Response(重传由链路层保证，这里只等结果)
                if (latch.await(3, TimeUnit.SECONDS)) {
                    if (pendingWriteOk) {
                        pendingWriteAck = null
                        return
                    }
                    addLog("写入被拒绝(status!=0)，重试(${retry + 1})")
                } else {
                    addLog("写入响应超时，重试(${retry + 1})")
                }
            }
            pendingWriteAck = null
            if (++retry >= 50) throw IllegalStateException("BLE写入连续失败(已重试${retry}次)")
            Thread.sleep(20)
        }
    }

    private fun addLog(msg: String) {
        val now = Calendar.getInstance()
        val timestamp = String.format("%02d:%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND))
        val entry = "[$timestamp] $msg"
        Log.i(TAG, msg)
        _logs.value = _logs.value + entry
    }

    fun clearLog() {
        _logs.value = listOf("日志已清空")
    }

    private fun findPattern(target: ByteArray, pattern: ByteArray): Int {
        for (i in 0..target.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (target[i + j] != pattern[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -0x1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xff)
            for (i in 0..7) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor -0x12477ce0 else crc ushr 1
            }
        }
        return crc.inv()
    }

    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArray(1024)
        var bytesRead: Int
        val outputStream = ByteArrayOutputStream()
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        return outputStream.toByteArray()
    }
}
