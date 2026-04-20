package com.xmhzj.dlgclockble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb";
    private static final String CTRL_POINT_UUID = "0000ff03-0000-1000-8000-00805f9b34fb";
    private static final String ADC1_VALUE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb";
    private static final String LONG_VALUE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic ctrlPointChar;
    private BluetoothGattCharacteristic adc1ValueChar;
    private BluetoothGattCharacteristic longValueChar;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView deviceNameTextView;
    private TextView currentVoltageTextView;
    private TextView currentTimeTextView;
    private TextView systemTimeTextView;
    private LinearLayout progressContainer;
    private LinearLayout updateProgressBar;
    private TextView updateProgressText;
    private TextView logWindow;
    private Button connectButton;
    private Button setimeButton;
    private Button upfirmButton;
    private Button send90Button;
    private Button calibrationButton;
    private Button clearLogButton;
    private Button scanDevicesButton;
    private ArrayList<BluetoothDevice> scannedDevices = new ArrayList<>();

    private boolean isConnected = false;
    private String currentDeviceName = "";
    private Timer deviceTimeTimer;
    private long deviceTimeBaseTimestamp;
    private int deviceTimeYear, deviceTimeMonth, deviceTimeDay, deviceTimeHour, deviceTimeMinute, deviceTimeSecond;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
        checkPermissions();
        setupBluetooth();
        updateSystemTime();
        startSystemTimeUpdate();
        addLog("终端已启动，等待设备连接...");
    }

    private void initUI() {
        deviceNameTextView = findViewById(R.id.device_name);
        currentVoltageTextView = findViewById(R.id.current_voltage);
        currentTimeTextView = findViewById(R.id.current_time);
        systemTimeTextView = findViewById(R.id.system_time);
        progressContainer = findViewById(R.id.progress_container);
        updateProgressBar = findViewById(R.id.update_progress_bar);
        updateProgressText = findViewById(R.id.update_progress_text);
        logWindow = findViewById(R.id.log_window);
        connectButton = findViewById(R.id.connect_button);
        setimeButton = findViewById(R.id.setime_button);
        upfirmButton = findViewById(R.id.upfirm_button);
        send90Button = findViewById(R.id.send90_button);
        calibrationButton = findViewById(R.id.calibration_button);
        clearLogButton = findViewById(R.id.clear_log_btn);
        scanDevicesButton = findViewById(R.id.scan_devices_button);

        connectButton.setOnClickListener(v -> {
            if (isConnected) {
                disconnectDevice();
            } else {
                connectToDevice();
            }
        });

        setimeButton.setOnClickListener(v -> onSetTime());
        upfirmButton.setOnClickListener(v -> onUpdate());
        send90Button.setOnClickListener(v -> onSend90());
        calibrationButton.setOnClickListener(v -> onCalibration());
        clearLogButton.setOnClickListener(v -> clearLog());
        scanDevicesButton.setOnClickListener(v -> scanDevices());
    }

    private void addLog(String msg) {
        mainHandler.post(() -> {
            String timestamp = new Date().toString().substring(11, 19);
            logWindow.append("[" + timestamp + "] " + msg + "\n");
            // 自动滚动到底部
            ScrollView scrollView = (ScrollView) logWindow.getParent();
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void updateUIState(String connectButtonText, boolean canConnect, boolean functionsEnabled) {
        mainHandler.post(() -> {
            connectButton.setText(connectButtonText);
            connectButton.setEnabled(canConnect);
            setimeButton.setEnabled(functionsEnabled);
            upfirmButton.setEnabled(functionsEnabled);
            send90Button.setEnabled(functionsEnabled);
            calibrationButton.setEnabled(functionsEnabled);
        });
    }

    private void connectToDevice() {
        updateUIState("连接中...", false, false);
        addLog("请求设备...");

        // 启动蓝牙设备扫描
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter filter = new ScanFilter.Builder().build();
            filters.add(filter);
            scanner.startScan(filters,
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                    new ScanCallback() {
                        @Override
                        public void onScanResult(int callbackType, ScanResult result) {
                            BluetoothDevice device = result.getDevice();
                            String deviceName = device.getName();
                            if (deviceName != null && deviceName.startsWith("DLG-CLOCK")) {
                                addLog("设备名称: " + deviceName);
                                deviceNameTextView.setText(deviceName);
                                bluetoothGatt = device.connectGatt(MainActivity.this, false, gattCallback);
                                scanner.stopScan(this);
                            }
                        }
                    });
        }
    }

    private void disconnectDevice() {
        updateUIState("断开中...", false, false);
        addLog("断开连接...");

        if (deviceTimeTimer != null) {
            deviceTimeTimer.cancel();
            deviceTimeTimer = null;
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        isConnected = false;
        currentDeviceName = "";
        deviceNameTextView.setText("未连接");
        currentVoltageTextView.setText("--.- V");
        currentTimeTextView.setText("--:--:--");
        updateUIState("连接设备", true, false);
        addLog("设备已断开连接");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                updateUIState("正在获取服务...", false, false);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectDevice();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UUID.fromString(SERVICE_UUID));
                if (service != null) {
                    ctrlPointChar = service.getCharacteristic(UUID.fromString(CTRL_POINT_UUID));
                    adc1ValueChar = service.getCharacteristic(UUID.fromString(ADC1_VALUE_UUID));
                    longValueChar = service.getCharacteristic(UUID.fromString(LONG_VALUE_UUID));

                    if (adc1ValueChar != null) {
                        gatt.readCharacteristic(adc1ValueChar);
                    }

                    if (longValueChar != null) {
                        gatt.readCharacteristic(longValueChar);
                    }

                    addLog("服务已就绪");
                    updateUIState("断开连接", true, true);
                } else {
                    addLog("错误: 找不到服务UUID");
                    disconnectDevice();
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] value = characteristic.getValue();
                if (characteristic.getUuid().toString().equals(ADC1_VALUE_UUID)) {
                    int voltage = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort();
                    double voltageValue = voltage / 1000.0;
                    currentVoltageTextView.setText(String.format("%.2f V", voltageValue));
                    addLog("当前电压: " + String.format("%.2f V", voltageValue));
                } else if (characteristic.getUuid().toString().equals(LONG_VALUE_UUID)) {
                    deviceTimeYear = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort(0);
                    deviceTimeMonth = value[2];
                    deviceTimeDay = value[3];
                    deviceTimeHour = value[4];
                    deviceTimeMinute = value[5];
                    deviceTimeSecond = value[6];
                    deviceTimeBaseTimestamp = System.currentTimeMillis();

                    updateDeviceTime();
                    startDeviceTimeUpdate();
                    addLog("设备时间: " + formatTime(deviceTimeYear, deviceTimeMonth, deviceTimeDay, deviceTimeHour, deviceTimeMinute, deviceTimeSecond));
                }
            }
        }
    };

    private void updateDeviceTime() {
        runOnUiThread(() -> {
            long elapsedSeconds = (System.currentTimeMillis() - deviceTimeBaseTimestamp) / 1000;
            int totalSeconds = deviceTimeSecond + (int) elapsedSeconds;
            int second = totalSeconds % 60;
            int totalMinutes = deviceTimeMinute + totalSeconds / 60;
            int minute = totalMinutes % 60;
            int totalHours = deviceTimeHour + totalMinutes / 60;
            int hour = totalHours % 24;

            currentTimeTextView.setText(formatTime(deviceTimeYear, deviceTimeMonth, deviceTimeDay, hour, minute, second));
        });
    }

    private void startDeviceTimeUpdate() {
        deviceTimeTimer = new Timer();
        deviceTimeTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                updateDeviceTime();
            }
        }, 0, 1000);
    }

    private void updateSystemTime() {
        runOnUiThread(() -> {
            Date now = new Date();
            systemTimeTextView.setText(formatTime(now.getYear() + 1900, now.getMonth(), now.getDate(), now.getHours(), now.getMinutes(), now.getSeconds()));
        });
    }

    private void startSystemTimeUpdate() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                updateSystemTime();
            }
        }, 0, 1000);
    }

    private String formatTime(int year, int month, int day, int hour, int minute, int second) {
        month += 1;
        return String.format("%d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second);
    }

    private void onSetTime() {
        setimeButton.setEnabled(false);
        setimeButton.setText("同步中...");
        addLog("同步时间中...");

        new Thread(() -> {
            try {
                // 等待整秒时间
                while (System.currentTimeMillis() % 1000 != 0) {
                    Thread.sleep(1);
                }

                Date now = new Date();
                int year = now.getYear() + 1900;
                int month = now.getMonth();
                int day = now.getDate();
                int hour = now.getHours();
                int minute = now.getMinutes();
                int second = now.getSeconds();

                byte[] buf = new byte[12];
                buf[0] = (byte) 0x91;
                buf[1] = (byte) (year % 256);
                buf[2] = (byte) (year / 256);
                buf[3] = (byte) month;
                buf[4] = (byte) day;
                buf[5] = (byte) hour;
                buf[6] = (byte) minute;
                buf[7] = (byte) second;
                buf[8] = (byte) now.getDay();
                // 农历部分暂时设置为0
                buf[9] = 0;
                buf[10] = 0;
                buf[11] = 0;

                writeCharacteristic(longValueChar, buf);

                // 更新设备时间基准值
                deviceTimeYear = year;
                deviceTimeMonth = month;
                deviceTimeDay = day;
                deviceTimeHour = hour;
                deviceTimeMinute = minute;
                deviceTimeSecond = second;
                deviceTimeBaseTimestamp = System.currentTimeMillis();

                updateDeviceTime();
                addLog("同步时间成功!");
            } catch (Exception e) {
                addLog("同步时间失败: " + e.getMessage());
            } finally {
                mainHandler.post(() -> {
                    setimeButton.setEnabled(true);
                    setimeButton.setText("同步时间");
                });
            }
        }).start();
    }

    private void onSend90() {
        send90Button.setEnabled(false);
        send90Button.setText("发送中...");
        addLog("发送切换命令...");

        new Thread(() -> {
            try {
                byte[] buf = new byte[]{(byte) 0x90};
                writeCharacteristic(longValueChar, buf);
                addLog("已发送切换命令");
            } catch (Exception e) {
                addLog("发送失败: " + e.getMessage());
            } finally {
                mainHandler.post(() -> {
                    send90Button.setEnabled(true);
                    send90Button.setText("切换时制");
                });
            }
        }).start();
    }

    private void onUpdate() {
        // 打开文件选择器
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            Uri uri = data.getData();
            if (uri != null) {
                upfirmButton.setEnabled(false);
                upfirmButton.setText("升级中...");
                progressContainer.setVisibility(View.VISIBLE);
                addLog("准备升级固件...");

                new Thread(() -> {
                    try {
                        InputStream inputStream = getContentResolver().openInputStream(uri);
                        byte[] firmBuf = readAllBytes(inputStream);
                        inputStream.close();

                        int firmSize = firmBuf.length;
                        addLog("固件大小: " + firmSize);

                        // 检查固件魔术字
                        byte[] firmMagic = new byte[]{(byte) 0x79, (byte) 0x13, (byte) 0xa5, (byte) 0xf9, (byte) 0x86, (byte) 0xec, (byte) 0x5a, (byte) 0x06};
                        int pos = findPattern(firmBuf, firmMagic);
                        if (pos == -1) {
                            addLog("无效固件: 未找到版本号!");
                            return;
                        }

                        int firmVer = firmBuf[pos + 9] * 256 + firmBuf[pos + 8];
                        int firmCrc = crc32(firmBuf);
                        addLog("固件版本: " + firmVer + " 大小: " + firmSize + " CRC: " + Integer.toHexString(firmCrc));

                        // 发送升级命令
                        byte[] buf = new byte[136];
                        buf[0] = (byte) 0xa0;
                        ByteBuffer.wrap(buf, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) firmSize);
                        writeCharacteristic(longValueChar, buf);

                        int sent = 0;
                        int totalSize = firmSize + 64;

                        for (int i = 0; i < totalSize; i += 256) {
                            Arrays.fill(buf, (byte) 0xff);
                            if (i == 0) {
                                ByteBuffer.wrap(buf, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x00aa5170);
                                ByteBuffer.wrap(buf, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmSize);
                                ByteBuffer.wrap(buf, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(firmCrc);
                                ByteBuffer.wrap(buf, 36, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xa50f0000 + firmVer);
                                buf[40] = 0;
                                buf[0] = (byte) 0xa2;
                                System.arraycopy(firmBuf, sent, buf, 72, 64);
                                writeCharacteristic(longValueChar, buf);
                                sent += 64;
                            } else {
                                buf[0] = (byte) 0xa2;
                                System.arraycopy(firmBuf, sent, buf, 8, 128);
                                writeCharacteristic(longValueChar, buf);
                                sent += 128;
                            }
                            buf[0] = (byte) 0xa3;
                            System.arraycopy(firmBuf, sent, buf, 8, 128);
                            writeCharacteristic(longValueChar, buf);
                            sent += 128;

                            // 更新进度条
                            int progress = Math.min((int) (100.0 * sent / totalSize), 100);
                            int finalProgress = progress;
                            mainHandler.post(() -> {
                                updateProgressBar.setLayoutParams(new LinearLayout.LayoutParams(
                                        0, LinearLayout.LayoutParams.MATCH_PARENT, finalProgress / 100.0f
                                ));
                                updateProgressText.setText(finalProgress + "%");
                            });
                        }

                        buf[0] = (byte) 0xa4;
                        Arrays.fill(buf, 1, 4, (byte) 0);
                        writeCharacteristic(longValueChar, buf);
                        addLog("升级完成");
                    } catch (Exception e) {
                        addLog("升级失败: " + e.getMessage());
                    } finally {
                        mainHandler.post(() -> {
                            upfirmButton.setEnabled(true);
                            upfirmButton.setText("固件升级");
                            progressContainer.setVisibility(View.GONE);
                        });
                    }
                }).start();
            }
        }
    }

    private void onCalibration() {
        calibrationButton.setEnabled(false);
        addLog("开始时间校准...");

        new Thread(() -> {
            try {
                // 读取当前时间
                bluetoothGatt.readCharacteristic(longValueChar);
                Thread.sleep(100);

                long lastMinute = deviceTimeMinute;
                int calMinute = 0;

                // 等待分钟跳变
                addLog("等待分钟跳变......");
                while (true) {
                    bluetoothGatt.readCharacteristic(longValueChar);
                    Thread.sleep(100);
                    if (deviceTimeMinute != lastMinute) {
                        break;
                    }
                    lastMinute = deviceTimeMinute;
                }

                int second = deviceTimeSecond;
                Date now = new Date();
                int sysMinute = now.getMinutes();
                int sysSecond = now.getSeconds();
                addLog("设备时间: " + deviceTimeMinute + "分" + second + "秒");
                addLog("系统时间: " + sysMinute + "分" + sysSecond + "秒");

                if (calMinute == -1) {
                    addLog("请先对时!");
                } else if (calMinute < 2880) {
                    addLog("对时与校准间隔太短(小于两天)!");
                } else {
                    if (deviceTimeMinute > sysMinute) {
                        if (deviceTimeMinute - sysMinute > 50) {
                            sysMinute += 60;
                        }
                    } else {
                        if (sysMinute - deviceTimeMinute > 50) {
                            deviceTimeMinute += 60;
                        }
                    }

                    int diff = (deviceTimeMinute * 60 + second) - (sysMinute * 60 + sysSecond);
                    addLog("时间差: " + diff + "秒");

                    byte[] buf = new byte[4];
                    buf[0] = (byte) 0x92;
                    buf[1] = (byte) (diff % 256);
                    buf[2] = (byte) (diff / 256);
                    buf[3] = (byte) 0x00;
                    writeCharacteristic(longValueChar, buf);

                    addLog("校准完成");
                }
            } catch (Exception e) {
                addLog("校准失败: " + e.getMessage());
            } finally {
                mainHandler.post(() -> {
                    calibrationButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void clearLog() {
        logWindow.setText("日志已清空\n");
        addLog("日志已清空");
    }

    private void scanDevices() {
        scannedDevices.clear();
        addLog("开始扫描设备...");

        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter filter = new ScanFilter.Builder().build();
            filters.add(filter);
            scanner.startScan(filters,
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                    new ScanCallback() {
                        @Override
                        public void onScanResult(int callbackType, ScanResult result) {
                            BluetoothDevice device = result.getDevice();
                            String deviceName = device.getName();
                            if (deviceName != null && !deviceName.isEmpty() && !scannedDevices.contains(device)) {
                                scannedDevices.add(device);
                            }
                        }
                    });

            mainHandler.postDelayed(() -> {
                scanner.stopScan(new ScanCallback() { });
                int count = scannedDevices.size();
                addLog("扫描完成，共发现 " + count + " 个设备");
                showDeviceList();
            }, 5000);
        }
    }

    private void showDeviceList() {
        if (scannedDevices.isEmpty()) {
            Toast.makeText(this, "未发现任何设备", Toast.LENGTH_SHORT).show();
            return;
        }

        List<BluetoothDevice> sortedDevices = new ArrayList<>();
        List<BluetoothDevice> nrfDevices = new ArrayList<>();
        List<BluetoothDevice> otherDevices = new ArrayList<>();

        for (BluetoothDevice device : scannedDevices) {
            String name = device.getName();
            if (name != null && (name.startsWith("NRF") || name.startsWith("DLG"))) {
                nrfDevices.add(device);
            } else {
                otherDevices.add(device);
            }
        }

        sortedDevices.addAll(nrfDevices);
        sortedDevices.addAll(otherDevices);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择设备");

        String[] deviceNames = new String[sortedDevices.size()];
        for (int i = 0; i < sortedDevices.size(); i++) {
            BluetoothDevice device = sortedDevices.get(i);
            String name = device.getName();
            deviceNames[i] = name + " (" + device.getAddress() + ")";
        }

        final List<BluetoothDevice> finalSortedDevices = sortedDevices;
        builder.setItems(deviceNames, (dialog, which) -> {
            BluetoothDevice selectedDevice = finalSortedDevices.get(which);
            connectToSelectedDevice(selectedDevice);
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void connectToSelectedDevice(BluetoothDevice device) {
        updateUIState("连接中...", false, false);
        addLog("连接设备: " + device.getName() + " (" + device.getAddress() + ")");

        if (device.getName() != null) {
            deviceNameTextView.setText(device.getName());
        }

        bluetoothGatt = device.connectGatt(MainActivity.this, false, gattCallback);
    }

    private void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] data) {
        if (characteristic == null || bluetoothGatt == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            characteristic.setValue(data);
            bluetoothGatt.writeCharacteristic(characteristic);
        }
    }

    private int findPattern(byte[] target, byte[] pattern) {
        for (int i = 0; i <= target.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (target[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private int crc32(byte[] data) {
        int crc = 0xffffffff;
        for (byte b : data) {
            crc ^= b & 0xff;
            for (int i = 0; i < 8; i++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xedb88320;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return ~crc;
    }

    private void setupBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager.getAdapter();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws Exception {
        byte[] buffer = new byte[1024];
        int bytesRead;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        return outputStream.toByteArray();
    }
}