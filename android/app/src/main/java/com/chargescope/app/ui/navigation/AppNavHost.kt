package com.chargescope.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chargescope.app.R
import com.chargescope.app.ui.device.DeviceCapabilitiesScreen
import com.chargescope.app.ui.history.HistoryScreen
import com.chargescope.app.ui.protocol.ProtocolAnalysisScreen
import com.chargescope.app.ui.realtime.RealtimeScreen
import com.chargescope.app.ui.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: Int) {
    data object Realtime : Screen("realtime", "实时监控", R.drawable.ic_dashboard)
    data object Protocol : Screen("protocol", "协议分析", R.drawable.ic_analytics)
    data object History : Screen("history", "历史记录", R.drawable.ic_history)
    data object Device : Screen("device", "设备能力", R.drawable.ic_phone)
    data object Settings : Screen("settings", "设置", R.drawable.ic_settings)
}

private val items = listOf(
    Screen.Realtime, Screen.Protocol, Screen.History, Screen.Device, Screen.Settings,
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(painterResource(screen.icon), contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Realtime.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.Realtime.route) { RealtimeScreen() }
            composable(Screen.Protocol.route) { ProtocolAnalysisScreen() }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Device.route) { DeviceCapabilitiesScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
