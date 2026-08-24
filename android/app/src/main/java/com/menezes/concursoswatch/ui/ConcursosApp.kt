package com.menezes.concursoswatch.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun ConcursosApp(
    initialUri: String? = null,
    onInitialUriConsumed: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    ConcursosWatchTheme {
        val nav = rememberNavController()
        val current by nav.currentBackStackEntryAsState()
        val route = current?.destination?.route
        val tabs = listOf(
            NavItem("home", "Início", Icons.Filled.Home),
            NavItem("alerts", "Alertas", Icons.Filled.Notifications),
            NavItem("contests", "Concursos", Icons.Filled.Work),
            NavItem("favorites", "Salvos", Icons.Filled.Favorite),
            NavItem("settings", "Ajustes", Icons.Filled.Settings),
        )

        LaunchedEffect(initialUri) {
            val uri = initialUri?.let(Uri::parse) ?: return@LaunchedEffect
            when (uri.host) {
                "contest" -> uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { id ->
                    nav.navigate("detail/${Uri.encode(id)}") { launchSingleTop = true }
                }
                "alerts" -> nav.navigate("alerts") { launchSingleTop = true }
            }
            onInitialUriConsumed()
        }

        Scaffold(
            containerColor = AppBg,
            bottomBar = {
                if (route != "detail/{contestId}") {
                    NavigationBar(
                        containerColor = Color(0xFF0D0F15),
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = {
                                    if (route != item.route) {
                                        nav.navigate(item.route) {
                                            launchSingleTop = true
                                            restoreState = true
                                            popUpTo("home") { saveState = true }
                                        }
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, style = MaterialTheme.typography.bodySmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AppPurple,
                                    selectedTextColor = AppText,
                                    indicatorColor = AppPurpleSoft,
                                    unselectedIconColor = AppMuted2,
                                    unselectedTextColor = AppMuted2,
                                ),
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = androidx.compose.ui.Modifier.padding(paddingValues),
            ) {
                composable("home") {
                    HomeScreen(
                        vm = vm,
                        onOpenContests = { nav.navigate("contests") },
                        onOpenAlerts = { nav.navigate("alerts") },
                        onDetail = { nav.navigate("detail/${Uri.encode(it.id)}") },
                    )
                }
                composable("alerts") { AlertsScreen(vm) }
                composable("contests") { ContestListScreen(vm, false) { nav.navigate("detail/${Uri.encode(it.id)}") } }
                composable("favorites") { ContestListScreen(vm, true) { nav.navigate("detail/${Uri.encode(it.id)}") } }
                composable("settings") { SettingsScreen(vm) }
                composable(
                    route = "detail/{contestId}",
                    arguments = listOf(navArgument("contestId") { type = NavType.StringType }),
                ) { entry ->
                    ContestDetailScreen(
                        vm,
                        Uri.decode(entry.arguments?.getString("contestId") ?: ""),
                    ) { nav.popBackStack() }
                }
            }
        }
    }
}
