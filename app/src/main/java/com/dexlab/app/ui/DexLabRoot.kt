package com.dexlab.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dexlab.app.ui.screens.AnalyzeScreen
import com.dexlab.app.ui.screens.CryptoLabScreen
import com.dexlab.app.ui.screens.CryptoScanScreen
import com.dexlab.app.ui.screens.DeepScreen
import com.dexlab.app.ui.screens.DexScreen
import com.dexlab.app.ui.screens.DiagnosticsScreen
import com.dexlab.app.ui.screens.DiffScreen
import com.dexlab.app.ui.screens.EntropyScreen
import com.dexlab.app.ui.screens.FilesScreen
import com.dexlab.app.ui.screens.HexScreen
import com.dexlab.app.ui.screens.LearnScreen
import com.dexlab.app.ui.screens.LessonScreen
import com.dexlab.app.ui.screens.LogcatScreen
import com.dexlab.app.ui.screens.ManifestScreen
import com.dexlab.app.ui.screens.NativeScreen
import com.dexlab.app.ui.screens.NetworkScreen
import com.dexlab.app.ui.screens.ProjectScreen
import com.dexlab.app.ui.screens.SettingsScreen
import com.dexlab.app.ui.screens.StringsScreen
import com.dexlab.app.ui.screens.ToolsScreen

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM = listOf(
    BottomItem("analyze", "Analyze", Icons.Filled.Search),
    BottomItem("tools", "Tools", Icons.Filled.Build),
    BottomItem("learn", "Learn", Icons.Filled.Info),
    BottomItem("logcat", "Logcat", Icons.Filled.List)
)

@Composable
fun DexLabRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottom = route in BOTTOM.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottom) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        BOTTOM.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = {
                                    nav.navigate(item.route) {
                                        popUpTo("analyze") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = {
                                    Text(
                                        item.label.uppercase(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "analyze",
            modifier = Modifier.padding(padding)
        ) {
            composable("analyze") { AnalyzeScreen(nav) }
            composable("tools") { ToolsScreen(nav) }
            composable("learn") { LearnScreen(nav) }
            composable("logcat") { LogcatScreen() }
            composable("settings") { SettingsScreen(nav) }
            composable("diagnostics") { DiagnosticsScreen(nav) }
            composable("project/{id}") { entry ->
                ProjectScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("manifest/{id}") { entry ->
                ManifestScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("dex/{id}") { entry ->
                DexScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("native/{id}") { entry ->
                NativeScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("deep/{id}") { entry ->
                DeepScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("diff/{id}") { entry ->
                DiffScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("files/{id}") { entry ->
                FilesScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("cryptoScan/{id}") { entry ->
                CryptoScanScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("lesson/{id}") { entry ->
                LessonScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable("hex") { HexScreen(nav) }
            composable("strings") { StringsScreen(nav) }
            composable("entropy") { EntropyScreen(nav) }
            composable("crypto") { CryptoLabScreen(nav) }
            composable("network") { NetworkScreen(nav) }
        }
    }
}

fun NavHostController.go(route: String) {
    navigate(route) { launchSingleTop = true }
}
