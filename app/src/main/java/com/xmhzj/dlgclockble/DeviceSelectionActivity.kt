package com.xmhzj.dlgclockble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("MissingPermission")
class DeviceSelectionActivity : AppCompatActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanButton: Button? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var deviceListRecyclerView: RecyclerView? = null
    private var deviceListAdapter: DeviceListAdapter? = null
    private var filterDlgCheckbox: CheckBox? = null

    private val scannedDevices = mutableListOf<BluetoothDevice>()
    private val filteredDevices = mutableListOf<BluetoothDevice>()
    private var isConnecting = false
    private var currentGatt: BluetoothGatt? = null
    private var connectingDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_selection)

        initUI()
        checkPermissions()
        setupBluetooth()
    }

    private fun initUI() {
        scanButton = findViewById(R.id.scan_button)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        deviceListRecyclerView = findViewById(R.id.device_list_recycler_view)
        filterDlgCheckbox = findViewById(R.id.filter_dlg_checkbox)

        deviceListAdapter = DeviceListAdapter(filteredDevices) { device ->
            connectToDevice(device)
        }
        deviceListRecyclerView?.layoutManager = LinearLayoutManager(this)
        deviceListRecyclerView?.adapter = deviceListAdapter

        filterDlgCheckbox?.setOnCheckedChangeListener { _, _ ->
            updateFilteredDevices()
        }

        scanButton?.setOnClickListener {
            if (scanCallback == null) {
                startScan()
            } else {
                stopScan()
            }
        }
    }

    private fun setupBluetooth() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        if (bluetoothAdapter == null) {
            statusText?.text = "设备不支持蓝牙"
            scanButton?.isEnabled = false
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_LONG).show()
        } else if (!bluetoothAdapter!!.isEnabled) {
            statusText?.text = "请开启蓝牙"
            Toast.makeText(this, "请开启蓝牙", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan() {
        scannedDevices.clear()
        filteredDevices.clear()
        deviceListAdapter?.notifyDataSetChanged()

        statusText?.text = "正在扫描设备..."
        progressBar?.visibility = View.VISIBLE
        scanButton?.text = "停止扫描"

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null) {
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val deviceName = device.name
                    if (deviceName != null && deviceName.isNotEmpty() && !scannedDevices.contains(device)) {
                        runOnUiThread {
                            scannedDevices.add(device)
                            updateFilteredDevices()
                        }
                    }
                }
            }

            val filters = mutableListOf<ScanFilter>()
            val filter = ScanFilter.Builder().build()
            filters.add(filter)
            scanner.startScan(
                filters,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback!!
            )

            // 5秒后自动停止扫描
            mainHandler.postDelayed({
                if (scanCallback != null) {
                    stopScan()
                }
            }, 5000)
        } else {
            statusText?.text = "无法启动扫描"
            progressBar?.visibility = View.GONE
            scanButton?.text = "开始扫描"
        }
    }

    private fun stopScan() {
        scanCallback?.let { callback ->
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            scanCallback = null
        }

        mainHandler.removeCallbacksAndMessages(null)

        statusText?.text = "扫描完成，共发现 ${filteredDevices.size} 个设备"
        progressBar?.visibility = View.GONE
        scanButton?.text = "开始扫描"
    }

    private fun updateFilteredDevices() {
        filteredDevices.clear()
        val showOnlyDlg = filterDlgCheckbox?.isChecked ?: true

        for (device in scannedDevices) {
            val deviceName = device.name
            if (!showOnlyDlg || (deviceName != null && deviceName.startsWith("DLG"))) {
                filteredDevices.add(device)
            }
        }

        deviceListAdapter?.notifyDataSetChanged()

        if (scanCallback == null) {
            statusText?.text = "扫描完成，共发现 ${filteredDevices.size} 个设备"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (isConnecting) return

        isConnecting = true
        scanButton?.isEnabled = false
        stopScan()

        val deviceLabel = device.name ?: device.address

        // 显示带转圈的连接弹窗
        val dialogView = layoutInflater.inflate(R.layout.dialog_connecting, null)
        dialogView.findViewById<TextView>(R.id.connecting_text)?.text = "正在连接: $deviceLabel"

        connectingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                cancelConnection()
            }
            .create()
        connectingDialog?.show()

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "设备已连接: $deviceLabel")
                        connectingDialog?.dismiss()
                        val intent = Intent(this@DeviceSelectionActivity, MainActivity::class.java)
                        intent.putExtra("device_address", device.address)
                        intent.putExtra("device_name", device.name ?: "未知设备")
                        startActivity(intent)
                        finish()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "连接失败或已断开")
                        runOnUiThread {
                            resetConnectionState()
                            Toast.makeText(this@DeviceSelectionActivity, "连接失败", Toast.LENGTH_SHORT).show()
                        }
                        gatt.close()
                    }
                }
            }
        }

        currentGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun cancelConnection() {
        currentGatt?.disconnect()
        currentGatt?.close()
        currentGatt = null
        resetConnectionState()
    }

    private fun resetConnectionState() {
        isConnecting = false
        connectingDialog?.dismiss()
        connectingDialog = null
        statusText?.text = "扫描完成，共发现 ${filteredDevices.size} 个设备"
        progressBar?.visibility = View.GONE
        scanButton?.isEnabled = true
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 1
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                statusText?.text = "权限被拒绝，无法扫描设备"
                Toast.makeText(this, "请授予蓝牙和定位权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        connectingDialog?.dismiss()
        connectingDialog = null
        currentGatt?.close()
        currentGatt = null
    }

    companion object {
        private const val TAG = "DeviceSelection"
    }
}
