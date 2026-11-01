package com.xmhzj.dlgclockble

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xmhzj.dlgclockble.screens.DeviceSelectionScreen
import com.xmhzj.dlgclockble.screens.MainScreen
import com.xmhzj.dlgclockble.ui.theme.DlgClockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 设置状态栏颜色为浅色，显示深色图标
        window.statusBarColor = Color.parseColor("#FFF0F9FF")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        BleManager.init(applicationContext)

        setContent {
            DlgClockTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "device_selection"
                ) {
                    composable("device_selection") {
                        DeviceSelectionScreen(navController)
                    }
                    composable("main") {
                        MainScreen(
                            onDisconnect = {
                                // 弹出当前页面，直接返回到设备选择页。
                                // 仅OTA数据全部送达后的断开才置自动扫描标记：设备重启后以
                                // 新版本号重新广播，列表页自动重扫即可看到；普通断开
                                // (手动断开/走出范围/升级中途退出)保留现有列表不重扫
                                if (BleManager.consumeOtaDelivered()) {
                                    navController.getBackStackEntry("device_selection")
                                        .savedStateHandle["auto_scan"] = true
                                }
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 真正退出App时断开BLE；旋转等配置变更会重建Activity，isFinishing为false不影响连接
        if (isFinishing) BleManager.disconnect()
        super.onDestroy()
    }
}
