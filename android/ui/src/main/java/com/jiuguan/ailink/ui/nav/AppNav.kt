package com.jiuguan.ailink.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jiuguan.ailink.core.PairingBus
import com.jiuguan.ailink.ui.accounts.AccountEditScreen
import com.jiuguan.ailink.ui.accounts.AccountsScreen
import com.jiuguan.ailink.ui.ai.AiProfileEditScreen
import com.jiuguan.ailink.ui.ai.AiProfilesScreen
import com.jiuguan.ailink.ui.home.HomeScreen
import com.jiuguan.ailink.ui.messages.ChatScreen
import com.jiuguan.ailink.ui.runtime.RuntimeScreen
import com.jiuguan.ailink.ui.messages.ConversationsScreen
import com.jiuguan.ailink.ui.settings.SettingsScreen
import com.jiuguan.ailink.ui.stats.StatsScreen

object Routes {
    const val HOME = "home"
    const val ACCOUNTS = "accounts"
    const val MESSAGES = "messages"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    const val ACCOUNT_EDIT = "account/edit?accountId={accountId}"
    const val AI_LIST = "ai"
    const val AI_EDIT = "ai/edit?profileId={profileId}"
    const val CHAT = "chat/{convId}"
    const val RUNTIME = "runtime"

    fun accountEdit(accountId: String = ""): String =
        if (accountId.isBlank()) "account/edit" else "account/edit?accountId=$accountId"

    fun aiEdit(profileId: String = ""): String =
        if (profileId.isBlank()) "ai/edit" else "ai/edit?profileId=$profileId"

    fun chat(convId: String): String = "chat/${android.net.Uri.encode(convId)}"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.HOME, "概览", Icons.Default.Home),
    TabItem(Routes.MESSAGES, "消息", Icons.Default.Email),
    TabItem(Routes.STATS, "统计", Icons.Default.List),
    TabItem(Routes.ACCOUNTS, "账号", Icons.Default.Person),
    TabItem(Routes.SETTINGS, "设置", Icons.Default.Settings)
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    // 深链 / 配对码导入后统一跳到账号编辑页
    LaunchedEffect(navController) {
        if (PairingBus.peek() != null) {
            navController.navigate(Routes.accountEdit())
        }
        PairingBus.incoming.collect {
            navController.navigate(Routes.accountEdit())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) { HomeScreen(navController) }
            composable(Routes.ACCOUNTS) { AccountsScreen(navController) }
            composable(Routes.MESSAGES) { ConversationsScreen(navController) }
            composable(Routes.STATS) { StatsScreen(navController) }
            composable(Routes.SETTINGS) { SettingsScreen(navController) }
            composable(Routes.RUNTIME) { RuntimeScreen(navController) }

            composable(
                route = Routes.ACCOUNT_EDIT,
                arguments = listOf(
                    navArgument("accountId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                AccountEditScreen(
                    nav = navController,
                    accountId = entry.arguments?.getString("accountId").orEmpty()
                )
            }

            composable(Routes.AI_LIST) { AiProfilesScreen(navController) }

            composable(
                route = Routes.AI_EDIT,
                arguments = listOf(
                    navArgument("profileId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                AiProfileEditScreen(
                    nav = navController,
                    profileId = entry.arguments?.getString("profileId").orEmpty()
                )
            }

            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument("convId") { type = NavType.StringType })
            ) { entry ->
                ChatScreen(
                    nav = navController,
                    convId = android.net.Uri.decode(
                        entry.arguments?.getString("convId").orEmpty()
                    )
                )
            }
        }
    }
}

private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}