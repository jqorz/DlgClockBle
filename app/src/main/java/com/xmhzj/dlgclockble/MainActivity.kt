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
                                // 弹出当前页面，直接返回到设备选择页；置自动扫描标记，
                                // 列表页回来后自动扫描(升级重启断开即可看到新版本号)
                                navController.getBackStackEntry("device_selection")
                                    .savedStateHandle["auto_scan"] = true
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
