package __APP_ID__.ui

import __APP_ID__.AppContainer
import __APP_ID__.ui.common.appViewModel
import __APP_ID__.ui.home.HomeScreen
import __APP_ID__.ui.search.SearchScreen
import __APP_ID__.ui.search.SearchViewModel
import __APP_ID__.ui.settings.LogScreen
import __APP_ID__.ui.settings.SettingsScreen
import __APP_ID__.ui.theme.DarkTubeTheme
import __APP_ID__.ui.video.VideoScreen
import __APP_ID__.ui.video.VideoViewModel
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LOG = "settings/log"
    const val VIDEO_ARG = "id"
    const val VIDEO = "video/{$VIDEO_ARG}"
    fun video(id: String) = "video/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.SEARCH, "Search", Icons.Default.Search),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
fun DarkTubeApp(
    container: AppContainer,
    incomingVideoId: String?,
    onIncomingConsumed: () -> Unit,
) {
    DarkTubeTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val currentRoute = entry?.destination?.route

        LaunchedEffect(incomingVideoId) {
            if (incomingVideoId != null) {
                nav.navigate(Routes.video(incomingVideoId))
                onIncomingConsumed()
            }
        }

        Scaffold(
            bottomBar = {
                if (tabs.any { it.route == currentRoute }) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                composable(Routes.HOME) {
                    HomeScreen(onOpenVideo = { nav.navigate(Routes.video(it)) })
                }
                composable(Routes.SEARCH) {
                    val vm = appViewModel { SearchViewModel(container.extractor) }
                    SearchScreen(vm, onOpenVideo = { nav.navigate(Routes.video(it)) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(container.extractor.engine, onOpenLog = { nav.navigate(Routes.LOG) })
                }
                composable(Routes.LOG) {
                    LogScreen(onBack = { nav.popBackStack() })
                }
                composable(
                    Routes.VIDEO,
                    arguments = listOf(navArgument(Routes.VIDEO_ARG) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString(Routes.VIDEO_ARG).orEmpty()
                    val vm = appViewModel(key = "video-$id") { VideoViewModel(id, container.extractor) }
                    VideoScreen(vm, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
