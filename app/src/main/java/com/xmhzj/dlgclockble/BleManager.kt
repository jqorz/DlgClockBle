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
import java.util.TimerTask
import java.util.UUID
import kotlin.concurrent.Volatile
import kotlin.math.min

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, SERVICE_READY
}

@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "BleManager"

    private const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    private const val CTRL_POINT_UUID = "0000ff03-0000-1000-8000-00805f9b34fb"
    private const val ADC1_VALUE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    private const val LONG_VALUE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var ctrlPointChar: BluetoothGattCharacteristic? = null
    private var adc1ValueChar: BluetoothGattCharacteristic? = null
    private var longValueChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
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

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

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

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        _scannedDevices.value = emptyList()

        val scanner = adapter.bluetoothLeScanner ?: return
        _isScanning.value = true

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val deviceName = device.name
                if (deviceName != null && deviceName.isNotEmpty()) {
                    val current = _scannedDevices.value
                    if (current.none { it.address == device.address }) {
                        _scannedDevices.value = current + device
                    }
                }
            }
        }

        val filters = listOf(ScanFilter.Builder().build())
        scanner.startScan(
            filters,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback!!
        )

        mainHandler.postDelayed({
            if (scanCallback != null) stopScan()
        }, 5000)
    }

    fun stopScan() {
        scanCallback?.let { callback ->
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            scanCallback = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        _isScanning.value = false
    }

    // --- Connection ---

    fun connect(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        _deviceName.value = device.name ?: device.address
        addLog("正在连接设备: ${_deviceName.value}")

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
        addLog("设备已断开连接")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=0x${status.toString(16)} newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                addLog("已连接 (status=0x${status.toString(16)})")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "BLE DISCONNECTED status=0x${status.toString(16)}")
                addLog("BLE断开! status=0x${status.toString(16)}")
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                if (service != null) {
                    ctrlPointChar = service.getCharacteristic(UUID.fromString(CTRL_POINT_UUID))
                    adc1ValueChar = service.getCharacteristic(UUID.fromString(ADC1_VALUE_UUID))
                    longValueChar = service.getCharacteristic(UUID.fromString(LONG_VALUE_UUID))

                    if (adc1ValueChar != null) {
                        gatt.setCharacteristicNotification(adc1ValueChar, true)
                        val descriptor = adc1ValueChar!!.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            gatt.writeDescriptor(descriptor)
                        }
                        gatt.readCharacteristic(adc1ValueChar)
                    }

                    if (longValueChar != null) {
                        longValueChar!!.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        gatt.setCharacteristicNotification(longValueChar, true)
                        val longDescriptor = longValueChar!!.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        if (longDescriptor != null) {
                            longDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            gatt.writeDescriptor(longDescriptor)
                        }
                        gatt.readCharacteristic(longValueChar)
                    }

                    addLog("服务已就绪")
                    _connectionState.value = ConnectionState.SERVICE_READY
                } else {
                    addLog("错误: 找不到服务UUID")
                    disconnect()
                }
            }
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite SUCCESS uuid=$uuid")
            } else {
                Log.e(TAG, "onCharacteristicWrite FAILED uuid=$uuid status=0x${status.toString(16)}")
                addLog("写入失败: uuid=$uuid status=0x${status.toString(16)}")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("descriptor写入失败: $status")
            }
        }
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

                        val calBuf = ByteArray(3)
                        calBuf[0] = 0x92.toByte()
                        calBuf[1] = (diff and 0xFF).toByte()
                        calBuf[2] = ((diff shr 8) and 0xFF).toByte()
                        writeCharacteristic(longValueChar, calBuf)
                        addLog("自动校准完成: 误差${diff}秒")
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
                val inputStream = context?.contentResolver?.openInputStream(uri) ?: throw Exception("无法打开文件")
                val firmBuf = readAllBytes(inputStream)
                inputStream.close()

                val firmSize = firmBuf.size
                addLog("固件大小: $firmSize")

                val firmMagic = byteArrayOf(
                    0x79.toByte(), 0x13.toByte(), 0xa5.toByte(), 0xf9.toByte(),
                    0x86.toByte(), 0xec.toByte(), 0x5a.toByte(), 0x06.toByte()
                )
                val pos = findPattern(firmBuf, firmMagic)
                if (pos == -1) {
                    addLog("无效固件: 未找到版本号!")
                    return@Thread
                }

                val firmVer = firmBuf[pos + 9] * 256 + firmBuf[pos + 8]
                val firmCrc = crc32(firmBuf)
                addLog("固件版本: $firmVer 大小: $firmSize CRC: ${Integer.toHexString(firmCrc)}")

                val buf = ByteArray(136)
                buf[0] = 0xa0.toByte()
                ByteBuffer.wrap(buf, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(firmSize.toShort())
                writeCharacteristic(longValueChar, buf)
                Thread.sleep(200)

                var sent = 0
                val totalSize = firmSize + 64

                var i = 0
                while (i < totalSize) {
                    Arrays.fill(buf, 0xff.toByte())
                    if (i == 0) {
                        ByteBuffer.wrap(buf, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x00aa5170)
                        ByteBuffer.wrap(buf, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmSize)
                        ByteBuffer.wrap(buf, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmCrc)
                        ByteBuffer.wrap(buf, 36, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-0x5af10000 + firmVer)
                        buf[40] = 0
                        buf[0] = 0xa1.toByte()
                        System.arraycopy(firmBuf, sent, buf, 72, 64)
                        writeCharacteristic(longValueChar, buf)
                        sent += 64
                    } else {
                        buf[0] = 0xa1.toByte()
                        val copyLen = min(128, firmBuf.size - sent)
                        if (copyLen <= 0) break
                        System.arraycopy(firmBuf, sent, buf, 8, copyLen)
                        writeCharacteristic(longValueChar, buf)
                        sent += copyLen
                    }
                    Thread.sleep(30)

                    val progress = min((100.0 * sent / totalSize).toInt(), 100)
                    _firmwareProgress.value = progress
                    i += if (i == 0) 64 else 128
                }

                buf[0] = 0xa2.toByte()
                Arrays.fill(buf, 1, 4, 0.toByte())
                writeCharacteristic(longValueChar, buf)
                addLog("升级完成")
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

    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?, data: ByteArray) {
        if (characteristic == null || bluetoothGatt == null) {
            addLog("错误: characteristic或gatt为null")
            return
        }
        val hexStr = data.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
        addLog("发送数据 [${data.size} bytes]: $hexStr")
        synchronized(gattLock) {
            try {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt!!.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    characteristic.setValue(data)
                    bluetoothGatt!!.writeCharacteristic(characteristic)
                }
            } catch (e: Exception) {
                addLog("写入失败: ${e.message}")
            }
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
