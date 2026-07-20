package com.xmhzj.dlgclockble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                                // 弹出当前页面，直接返回到设备选择页
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
