package com.xmhzj.dlgclockble

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tyme.solar.SolarDay
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Calendar
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.concurrent.Volatile
import kotlin.math.min

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var ctrlPointChar: BluetoothGattCharacteristic? = null
    private var adc1ValueChar: BluetoothGattCharacteristic? = null
    private var longValueChar: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var deviceNameTextView: TextView? = null
    private var currentVoltageTextView: TextView? = null
    private var currentTimeTextView: TextView? = null
    private var systemTimeTextView: TextView? = null
    private var progressContainer: LinearLayout? = null
    private var updateProgressBar: LinearLayout? = null
    private var updateProgressText: TextView? = null
    private var logWindow: TextView? = null
    private var connectButton: Button? = null
    private var setimeButton: Button? = null
    private var upfirmButton: Button? = null
    private var send90Button: Button? = null
    private var calibrationButton: Button? = null
    private var clearLogButton: Button? = null
    private var scanDevicesButton: Button? = null
    private val scannedDevices: MutableList<BluetoothDevice> = Collections.synchronizedList<BluetoothDevice?>(ArrayList<BluetoothDevice?>())
    private val deviceTimeLock = Any()
    private var currentDeviceName = ""
    private var lastSyncTimestamp: Long = 0
    private lateinit var firmwareLauncher: ActivityResultLauncher<Intent>
    private var deviceTimeTimer: Timer? = null
    private var systemTimeTimer: Timer? = null
    private val gattLock = Any()

    @Volatile
    private var destroyed = false

    @Volatile
    private var isConnected = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        firmwareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    onFirmwareSelected(uri)
                }
            }
        }

        initUI()
        lastSyncTimestamp = getSharedPreferences("calibration", MODE_PRIVATE).getLong("last_sync_timestamp", 0)
        checkPermissions()
        setupBluetooth()
        updateSystemTime()
        startSystemTimeUpdate()
        addLog("终端已启动，等待设备连接...")
    }

    private fun initUI() {
        deviceNameTextView = findViewById<TextView>(R.id.device_name)
        currentVoltageTextView = findViewById<TextView>(R.id.current_voltage)
        currentTimeTextView = findViewById<TextView>(R.id.current_time)
        systemTimeTextView = findViewById<TextView>(R.id.system_time)
        progressContainer = findViewById<LinearLayout>(R.id.progress_container)
        updateProgressBar = findViewById<LinearLayout>(R.id.update_progress_bar)
        updateProgressText = findViewById<TextView>(R.id.update_progress_text)
        logWindow = findViewById<TextView>(R.id.log_window)
        connectButton = findViewById<Button>(R.id.connect_button)
        setimeButton = findViewById<Button>(R.id.setime_button)
        upfirmButton = findViewById<Button>(R.id.upfirm_button)
        send90Button = findViewById<Button>(R.id.send90_button)
        calibrationButton = findViewById<Button>(R.id.calibration_button)
        clearLogButton = findViewById<Button>(R.id.clear_log_btn)
        scanDevicesButton = findViewById<Button>(R.id.scan_devices_button)

        connectButton!!.setOnClickListener(View.OnClickListener { v: View? ->
            if (isConnected) {
                disconnectDevice()
            } else {
                connectToDevice()
            }
        })

        setimeButton!!.setOnClickListener(View.OnClickListener { v: View? -> onSetTime() })
        upfirmButton!!.setOnClickListener(View.OnClickListener { v: View? -> onUpdate() })
        send90Button!!.setOnClickListener(View.OnClickListener { v: View? -> onSend90() })
        calibrationButton!!.setOnClickListener(View.OnClickListener { v: View? -> onCalibration() })
        clearLogButton!!.setOnClickListener(View.OnClickListener { v: View? -> clearLog() })
        scanDevicesButton!!.setOnClickListener(View.OnClickListener { v: View? -> scanDevices() })
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                updateUIState("正在获取服务...", false, false)
                // 请求更短的连接间隔以提高响应速度
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                doDisconnectDevice()
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
                        longValueChar!!.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
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
                    updateUIState("断开连接", true, true)
                } else {
                    addLog("错误: 找不到服务UUID")
                    disconnectDevice()
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.getValue()
                if (characteristic.getUuid().toString() == ADC1_VALUE_UUID) {
                    val voltage = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
                    val voltageValue = voltage / 1000.0
                    // 使用mainHandler在UI线程更新UI
                    mainHandler.post(Runnable {
                        currentVoltageTextView!!.setText(String.format("%.2f V", voltageValue))
                    })
                    addLog("当前电压: " + String.format("%.2f V", voltageValue))
                } else if (characteristic.getUuid().toString() == LONG_VALUE_UUID) {
                    synchronized(deviceTimeLock) {
                        deviceTimeYear = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
                        deviceTimeMonth = value[2].toInt()
                        deviceTimeDay = value[3].toInt()
                        deviceTimeHour = value[4].toInt()
                        deviceTimeMinute = value[5].toInt()
                        deviceTimeSecond = value[6].toInt()
                        deviceTimeBaseTimestamp = System.currentTimeMillis()
                    }

                    startDeviceTimeUpdate()
                    synchronized(deviceTimeLock) {
                        addLog(
                            "设备时间: " + formatTime(
                                deviceTimeYear,
                                deviceTimeMonth,
                                deviceTimeDay,
                                deviceTimeHour,
                                deviceTimeMinute,
                                deviceTimeSecond
                            )
                        )
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("数据写入成功")
            } else {
                addLog("数据写入失败: " + status.toString(16))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog(" descriptor写入成功")
            } else {
                addLog(" descriptor写入失败: " + status)
            }
        }
    }

    private fun updateUIState(connectButtonText: String?, canConnect: Boolean, functionsEnabled: Boolean) {
        mainHandler.post(Runnable {
            connectButton!!.setText(connectButtonText)
            connectButton!!.setEnabled(canConnect)
            setimeButton!!.setEnabled(functionsEnabled)
            upfirmButton!!.setEnabled(functionsEnabled)
            send90Button!!.setEnabled(functionsEnabled)
            calibrationButton!!.setEnabled(functionsEnabled)
        })
    }

    private fun connectToDevice() {
        updateUIState("连接中...", false, false)
        addLog("请求设备...")

        // 启动蓝牙设备扫描
        val scanner = bluetoothAdapter!!.getBluetoothLeScanner()
        if (scanner != null) {
            val filters: MutableList<ScanFilter?> = ArrayList<ScanFilter?>()
            val filter = ScanFilter.Builder().build()
            filters.add(filter)
            scanner.startScan(
                filters,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val device = result.getDevice()
                        val deviceName = device.getName()
                        if (deviceName != null && deviceName.startsWith("DLG-CLOCK")) {
                            addLog("设备名称: " + deviceName)
                            deviceNameTextView!!.setText(deviceName)
                            bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
                            scanner.stopScan(this)
                        }
                    }
                })
        }
    }

    private var scanCallback: ScanCallback? = null

    private fun addLog(msg: String) {
        if (destroyed) return
        mainHandler.post(Runnable {
            if (destroyed || logWindow == null) return@Runnable
            val now = Calendar.getInstance()
            val timestamp = String.format("%02d:%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND))
            logWindow!!.append("[" + timestamp + "] " + msg + "\n")
            Log.i(TAG, msg)
            // 自动滚动到底部
            val scrollView = logWindow!!.getParent() as ScrollView
            scrollView.post(Runnable { scrollView.fullScroll(ScrollView.FOCUS_DOWN) })
        })
    }

    private fun disconnectDevice() {
        mainHandler.post(Runnable {
            doDisconnectDevice()
        })
    }

    private fun doDisconnectDevice() {
        if (destroyed) return
        updateUIState("断开中...", false, false)
        addLog("断开连接...")

        if (deviceTimeTimer != null) {
            deviceTimeTimer!!.cancel()
            deviceTimeTimer = null
        }

        synchronized(gattLock) {
            if (bluetoothGatt != null) {
                bluetoothGatt!!.disconnect()
                bluetoothGatt!!.close()
                bluetoothGatt = null
            }
        }

        isConnected = false
        currentDeviceName = ""
        deviceNameTextView!!.setText("未连接")
        currentVoltageTextView!!.setText("--.- V")
        currentTimeTextView!!.setText("--:--:--")
        updateUIState("连接设备", true, false)
        addLog("设备已断开连接")
    }

    private fun startDeviceTimeUpdate() {
        deviceTimeTimer = Timer()
        deviceTimeTimer!!.schedule(object : TimerTask() {
            override fun run() {
                updateDeviceTime()
            }
        }, 0, 1000)
    }

    private fun updateSystemTime() {
        runOnUiThread(Runnable {
            val now = Calendar.getInstance()
            systemTimeTextView!!.setText(
                formatTime(
                    now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH),
                    now.get(Calendar.DAY_OF_MONTH),
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND)
                )
            )
        })
    }

    private fun startSystemTimeUpdate() {
        systemTimeTimer = Timer()
        systemTimeTimer!!.schedule(object : TimerTask() {
            override fun run() {
                updateSystemTime()
            }
        }, 0, 1000)
    }

    private fun formatTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String {
        var month = month
        month += 1
        return String.format("%d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second)
    }

    private fun updateDeviceTime() {
        val year: Int
        val month: Int
        val day: Int
        val hour: Int
        val minute: Int
        val second: Int
        val baseTimestamp: Long

        synchronized(deviceTimeLock) {
            year = deviceTimeYear
            month = deviceTimeMonth
            day = deviceTimeDay
            hour = deviceTimeHour
            minute = deviceTimeMinute
            second = deviceTimeSecond
            baseTimestamp = deviceTimeBaseTimestamp
        }

        runOnUiThread(Runnable {
            val elapsedSeconds = (System.currentTimeMillis() - baseTimestamp) / 1000
            val totalSeconds = second + elapsedSeconds.toInt()
            val sec = totalSeconds % 60
            val totalMinutes = minute + totalSeconds / 60
            val min = totalMinutes % 60
            val totalHours = hour + totalMinutes / 60
            val hr = totalHours % 24
            val extraDays = totalHours / 24
            // 简单日期进位: 使用Calendar处理跨月/跨年
            val cal = Calendar.getInstance()
            cal.set(year, month, day + extraDays, hr, min, sec)
            currentTimeTextView!!.setText(formatTime(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
                hr, min, sec
            ))
        })
    }

    private fun onSetTime() {
        setimeButton!!.setEnabled(false)
        setimeButton!!.setText("同步中...")
        addLog("同步时间中...")

        if (longValueChar == null || bluetoothGatt == null) {
            addLog("错误: 未连接设备或服务未就绪")
            setimeButton!!.setEnabled(true)
            setimeButton!!.setText("同步时间")
            return
        }

        Thread(Runnable {
            try {
                // 等待整秒时间
                val startTime = System.currentTimeMillis()
                val waitTime = 1000 - (startTime % 1000)
                if (waitTime > 10) {
                    Thread.sleep(waitTime)
                }

                val now = Calendar.getInstance()
                val year = now.get(Calendar.YEAR)
                val month = now.get(Calendar.MONTH)
                val day = now.get(Calendar.DAY_OF_MONTH)
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val second = now.get(Calendar.SECOND)
                val wday = now.get(Calendar.DAY_OF_WEEK) - 1  // Calendar: 1=Sun, protocol: 0=Sun

                addLog(String.format("当前时间: %d-%02d-%02d %02d:%02d:%02d", year, month + 1, day, hour, minute, second))

                val buf = ByteArray(12)
                buf[0] = 0x91.toByte()
                buf[1] = (year % 256).toByte()
                buf[2] = (year / 256).toByte()
                buf[3] = month.toByte() // 月份从0开始（0=1月，符合协议）
                buf[4] = day.toByte()
                buf[5] = hour.toByte()
                buf[6] = minute.toByte()
                buf[7] = second.toByte()
                buf[8] = wday.toByte()
                // 农历部分（通过 tyme4kt 计算真实农历）
                val solarDay = SolarDay.fromYmd(year, month + 1, day)
                val lunarDay = solarDay.getLunarDay()
                val lunarMonthVal = lunarDay.month  // 负值表示闰月（如 -2 = 闰二月）
                buf[9] = (lunarDay.year - 2020).toByte()  // 农历年索引: 0-31
                buf[10] = if (lunarMonthVal < 0) {
                    ((-lunarMonthVal - 1) or 0x80).toByte()  // 闰月: bit7=1
                } else {
                    (lunarMonthVal - 1).toByte()             // 常规月: 0-11
                }
                buf[11] = lunarDay.day.toByte()  // 农历日期: 1-30

                addLog("正在写入时间数据... ")


                // 在主线程执行写入操作
                mainHandler.post(Runnable {
                    writeCharacteristic(longValueChar, buf)
                })

                // 等待写入完成
                Thread.sleep(500)

                // 更新设备时间基准值
                synchronized(deviceTimeLock) {
                    deviceTimeYear = year
                    deviceTimeMonth = month
                    deviceTimeDay = day
                    deviceTimeHour = hour
                    deviceTimeMinute = minute
                    deviceTimeSecond = second
                    deviceTimeBaseTimestamp = System.currentTimeMillis()
                }

                updateDeviceTime()
                lastSyncTimestamp = System.currentTimeMillis()
                getSharedPreferences("calibration", MODE_PRIVATE).edit()
                    .putLong("last_sync_timestamp", lastSyncTimestamp)
                    .apply()
                addLog("同步时间成功!")
            } catch (e: Exception) {
                addLog("同步时间失败: " + e.message)
                e.printStackTrace()
            } finally {
                mainHandler.post(Runnable {
                    setimeButton!!.setEnabled(true)
                    setimeButton!!.setText("同步时间")
                })
            }
        }).start()
    }

    private fun onUpdate() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        firmwareLauncher.launch(intent)
    }

    private fun onFirmwareSelected(uri: Uri) {
        upfirmButton!!.setEnabled(false)
        upfirmButton!!.setText("升级中...")
        progressContainer!!.setVisibility(View.VISIBLE)
        addLog("准备升级固件...")

        Thread(Runnable {
            try {
                val inputStream = getContentResolver().openInputStream(uri)
                val firmBuf = readAllBytes(inputStream!!)
                inputStream.close()

                val firmSize = firmBuf.size
                addLog("固件大小: " + firmSize)

                // 检查固件魔术字
                val firmMagic = byteArrayOf(
                    0x79.toByte(), 0x13.toByte(), 0xa5.toByte(), 0xf9.toByte(),
                    0x86.toByte(), 0xec.toByte(), 0x5a.toByte(), 0x06.toByte()
                )
                val pos = findPattern(firmBuf, firmMagic)
                if (pos == -1) {
                    addLog("无效固件: 未找到版本号!")
                    return@Runnable
                }

                val firmVer = firmBuf[pos + 9] * 256 + firmBuf[pos + 8]
                val firmCrc = crc32(firmBuf)
                addLog("固件版本: " + firmVer + " 大小: " + firmSize + " CRC: " + Integer.toHexString(firmCrc))

                // 发送升级命令
                val buf = ByteArray(136)
                buf[0] = 0xa0.toByte()
                ByteBuffer.wrap(buf, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(firmSize.toShort())
                writeCharacteristic(longValueChar, buf)

                var sent = 0
                val totalSize = firmSize + 64

                var i = 0
                while (i < totalSize) {
                    Arrays.fill(buf, 0xff.toByte())
                    if (i == 0) {
                        // 首包: header元数据 + 前64字节固件
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
                        // 后续包: 每包128字节固件数据
                        buf[0] = 0xa1.toByte()
                        val copyLen = min(128, firmBuf.size - sent)
                        if (copyLen <= 0) break
                        System.arraycopy(firmBuf, sent, buf, 8, copyLen)
                        writeCharacteristic(longValueChar, buf)
                        sent += copyLen
                    }

                    // 更新进度条
                    val progress = min((100.0 * sent / totalSize).toInt(), 100)
                    val finalProgress = progress
                    mainHandler.post(Runnable {
                        updateProgressBar!!.setLayoutParams(
                            LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.MATCH_PARENT, finalProgress / 100.0f
                            )
                        )
                        updateProgressText!!.setText(finalProgress.toString() + "%")
                    })
                    i += if (i == 0) 64 else 128
                }

                buf[0] = 0xa2.toByte()
                Arrays.fill(buf, 1, 4, 0.toByte())
                writeCharacteristic(longValueChar, buf)
                addLog("升级完成")
            } catch (e: Exception) {
                addLog("升级失败: " + e.message)
            } finally {
                mainHandler.post(Runnable {
                    upfirmButton!!.setEnabled(true)
                    upfirmButton!!.setText("固件升级")
                    progressContainer!!.setVisibility(View.GONE)
                })
            }
        }).start()
    }

    private fun onSend90() {
        send90Button!!.setEnabled(false)
        send90Button!!.setText("发送中...")
        addLog("发送切换命令...")

        Thread(Runnable {
            try {
                val buf = byteArrayOf(0x90.toByte())
                mainHandler.post(Runnable {
                    writeCharacteristic(longValueChar, buf)
                })
                Thread.sleep(300)
                addLog("已发送切换命令")
            } catch (e: Exception) {
                addLog("发送失败: " + e.message)
            } finally {
                mainHandler.post(Runnable {
                    send90Button!!.setEnabled(true)
                    send90Button!!.setText("切换时制")
                })
            }
        }).start()
    }

    private fun clearLog() {
        logWindow!!.setText("日志已清空\n")
        addLog("日志已清空")
    }

    private fun onCalibration() {
        calibrationButton!!.setEnabled(false)
        calibrationButton!!.setText("校准中...")
        addLog("开始时间校准...")

        if (bluetoothGatt == null || longValueChar == null) {
            addLog("错误: 未连接设备或服务未就绪")
            calibrationButton!!.setEnabled(true)
            return
        }

        Thread(Runnable {
            try {
                // 读取当前时间
                bluetoothGatt!!.readCharacteristic(longValueChar)
                Thread.sleep(200)

                val initialMinute: Int
                synchronized(deviceTimeLock) {
                    initialMinute = deviceTimeMinute
                }
                val calMinute = if (lastSyncTimestamp == 0L) {
                    -1  // 从未对时
                } else {
                    ((System.currentTimeMillis() - lastSyncTimestamp) / 60000).toInt()
                }

                // 等待分钟跳变（最多等待70秒）
                addLog("等待分钟跳变，初始分钟: " + initialMinute)
                var waitCount = 0
                while (waitCount < 700) {
                    bluetoothGatt!!.readCharacteristic(longValueChar)
                    Thread.sleep(100)
                    val currentMinute: Int
                    synchronized(deviceTimeLock) {
                        currentMinute = deviceTimeMinute
                    }
                    if (currentMinute != initialMinute) {
                        addLog("检测到分钟跳变: " + initialMinute + " -> " + currentMinute)
                        break
                    }
                    waitCount++
                }

                if (waitCount >= 700) {
                    addLog("等待超时，未检测到分钟跳变")
                    return@Runnable
                }

                val second: Int
                val devMinute: Int
                synchronized(deviceTimeLock) {
                    second = deviceTimeSecond
                    devMinute = deviceTimeMinute
                }
                val now = Calendar.getInstance()
                var sysMinute = now.get(Calendar.MINUTE)
                val sysSecond = now.get(Calendar.SECOND)
                addLog("设备时间: " + devMinute + "分" + second + "秒")
                addLog("系统时间: " + sysMinute + "分" + sysSecond + "秒")

                if (calMinute == -1) {
                    addLog("请先对时!")
                } else if (calMinute < 2880) {
                    addLog("对时与校准间隔太短(小于两天)!")
                } else {
                    var calcDevMinute = devMinute
                    if (calcDevMinute > sysMinute) {
                        if (calcDevMinute - sysMinute > 50) {
                            sysMinute += 60
                        }
                    } else {
                        if (sysMinute - calcDevMinute > 50) {
                            calcDevMinute += 60
                        }
                    }

                    val diff = (calcDevMinute * 60 + second) - (sysMinute * 60 + sysSecond)
                    addLog("时间差: " + diff + "秒")

                    val buf = ByteArray(3)
                    buf[0] = 0x92.toByte()
                    buf[1] = (diff and 0xFF).toByte()
                    buf[2] = ((diff shr 8) and 0xFF).toByte()
                    writeCharacteristic(longValueChar, buf)

                    addLog("校准完成")
                }
            } catch (e: Exception) {
                addLog("校准失败: " + e.message)
            } finally {
                mainHandler.post(Runnable {
                    calibrationButton!!.setEnabled(true)
                    calibrationButton!!.setText("时间校准")
                })
            }
        }).start()
    }

    private fun scanDevices() {
        scannedDevices.clear()
        addLog("开始扫描设备...")

        if (bluetoothAdapter == null) {
            addLog("错误: 蓝牙适配器为空")
            return
        }

        val scanner = bluetoothAdapter!!.getBluetoothLeScanner()
        if (scanner != null) {
            // 创建扫描回调
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.getDevice()
                    val deviceName = device.getName()
                    if (deviceName != null && !deviceName.isEmpty() && !scannedDevices.contains(device)) {
                        scannedDevices.add(device)
                    }
                }
            }

            val filters: MutableList<ScanFilter?> = ArrayList<ScanFilter?>()
            val filter = ScanFilter.Builder().build()
            filters.add(filter)
            scanner.startScan(
                filters,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback
            )

            mainHandler.postDelayed(Runnable {
                if (scanCallback != null) {
                    scanner.stopScan(scanCallback)
                    scanCallback = null
                }
                val count = scannedDevices.size
                addLog("扫描完成，共发现 " + count + " 个设备")
                showDeviceList()
            }, 5000)
        }
    }

    private fun showDeviceList() {
        if (scannedDevices.isEmpty()) {
            Toast.makeText(this, "未发现任何设备", Toast.LENGTH_SHORT).show()
            return
        }

        val sortedDevices: MutableList<BluetoothDevice> = ArrayList<BluetoothDevice>()
        val nrfDevices: MutableList<BluetoothDevice> = ArrayList<BluetoothDevice>()
        val otherDevices: MutableList<BluetoothDevice> = ArrayList<BluetoothDevice>()

        for (device in scannedDevices) {
            val name = device.getName()
            if (name != null && (name.startsWith("NRF") || name.startsWith("DLG"))) {
                nrfDevices.add(device)
            } else {
                otherDevices.add(device)
            }
        }

        sortedDevices.addAll(nrfDevices)
        sortedDevices.addAll(otherDevices)

        val builder = AlertDialog.Builder(this)
        builder.setTitle("选择设备")

        val deviceNames = arrayOfNulls<String>(sortedDevices.size)
        for (i in sortedDevices.indices) {
            val device = sortedDevices.get(i)
            val name = device.getName()
            deviceNames[i] = name + " (" + device.getAddress() + ")"
        }

        val finalSortedDevices = sortedDevices
        builder.setItems(deviceNames, DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
            val selectedDevice = finalSortedDevices.get(which)
            connectToSelectedDevice(selectedDevice)
        })

        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun connectToSelectedDevice(device: BluetoothDevice) {
        updateUIState("连接中...", false, false)
        addLog("连接设备: " + device.getName() + " (" + device.getAddress() + ")")

        if (device.getName() != null) {
            deviceNameTextView!!.setText(device.getName())
        }

        bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
    }

    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?, data: ByteArray) {
        if (characteristic == null || bluetoothGatt == null) {
            addLog("错误: characteristic或gatt为null")
            return
        }
        // 打印发送的每个字节
        val hexStr = StringBuilder()
        for (b in data) {
            hexStr.append(String.format("%02X ", b.toInt() and 0xFF))
        }
        addLog("发送数据 [" + data.size + " bytes]: " + hexStr.toString().trim { it <= ' ' })
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
                addLog("写入失败: " + e.message)
                e.printStackTrace()
            }
        }
    }

    private fun findPattern(target: ByteArray, pattern: ByteArray): Int {
        for (i in 0..target.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (target[i + j] != pattern[j]) {
                    match = false
                    break
                }
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
                if ((crc and 1) != 0) {
                    crc = (crc ushr 1) xor -0x12477ce0
                } else {
                    crc = crc ushr 1
                }
            }
        }
        return crc.inv()
    }

    private fun setupBluetooth() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.getAdapter()

        if (bluetoothAdapter == null) {
            addLog("错误: 设备不支持蓝牙")
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_LONG).show()
        } else if (!bluetoothAdapter!!.isEnabled()) {
            addLog("提示: 蓝牙未开启")
            Toast.makeText(this, "请开启蓝牙", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        // 清理定时器
        if (systemTimeTimer != null) {
            systemTimeTimer!!.cancel()
            systemTimeTimer = null
        }
        if (deviceTimeTimer != null) {
            deviceTimeTimer!!.cancel()
            deviceTimeTimer = null
        }

        // 断开蓝牙连接
        synchronized(gattLock) {
            if (bluetoothGatt != null) {
                bluetoothGatt!!.disconnect()
                bluetoothGatt!!.close()
                bluetoothGatt = null
            }
        }

        // 清空引用
        ctrlPointChar = null
        adc1ValueChar = null
        longValueChar = null
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf<String>(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 1
            )
        } else {
            requestPermissions(arrayOf<String>(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                addLog("权限被拒绝，部分功能可能无法使用")
                Toast.makeText(this, "请授予蓝牙和定位权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Throws(Exception::class)
    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArray(1024)
        var bytesRead: Int
        val outputStream = ByteArrayOutputStream()
        while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        return outputStream.toByteArray()
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
        private const val CTRL_POINT_UUID = "0000ff03-0000-1000-8000-00805f9b34fb"
        private const val ADC1_VALUE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
        private const val LONG_VALUE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    }
}