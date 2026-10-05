package com.sparkhoward.nagomiani

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sparkhoward.nagomiani.player.PlayerScreen
import com.sparkhoward.nagomiani.ui.bangumi.BangumiScreen
import com.sparkhoward.nagomiani.ui.library.LibraryScreen
import com.sparkhoward.nagomiani.ui.search.SearchScreen
import com.sparkhoward.nagomiani.ui.search.SubjectDetailScreen
import com.sparkhoward.nagomiani.ui.theme.NagomiTheme

/** 主导航路由 */
object Routes {
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val BANGUMI = "bangumi"
    const val CACHE = "cache"
    const val DETAIL = "detail/{subjectID}"
    const val PLAYER = "player"
    const val LOGIN = "login"

    fun detail(subjectID: Int) = "detail/$subjectID"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NagomiTheme {
                NagomiNavHost()
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun NagomiNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val playingNow by com.sparkhoward.nagomiani.player.PlaybackBus.isPlaying.collectAsState()
    val hasPlayback by com.sparkhoward.nagomiani.player.PlaybackBus.pending.collectAsState()

    // Android 13+ 通知权限：缓存前台服务的进度通知依赖它（未授权时后台缓存仍工作，只是通知不可见）
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        // 拉起下载服务：载入缓存索引（缓存页/角标展示）并恢复上次被杀中断的下载
        com.sparkhoward.nagomiani.core.download.DownloadUtil.ensureServiceStarted(activityContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val tabs = listOf(
        Tab(Routes.SEARCH, "搜索", Icons.Filled.Search),
        Tab(Routes.LIBRARY, "番库", Icons.Filled.MenuBook),
        Tab(Routes.BANGUMI, "追番", Icons.Filled.Person),
        Tab(Routes.CACHE, "缓存", Icons.Filled.Download),
    )
    val showTabs = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                // 回到播放中：有播放会话且不在播放器页时显示
                if (showTabs && hasPlayback != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Routes.PLAYER) }
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            if (playingNow) "正在播放 · 点按回到播放器" else "已暂停 · 点按回到播放器",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (showTabs) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SEARCH,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SEARCH) {
                SearchScreen(onOpenDetail = { navController.navigate(Routes.detail(it)) }, onPlay = { navController.navigate(Routes.PLAYER) })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(onOpenDetail = { navController.navigate(Routes.detail(it)) }, onPlay = { navController.navigate(Routes.PLAYER) })
            }
            composable(Routes.BANGUMI) {
                BangumiScreen(onOpenDetail = { navController.navigate(Routes.detail(it)) }, onOpenLogin = { navController.navigate(Routes.LOGIN) })
            }
            composable(Routes.CACHE) {
                com.sparkhoward.nagomiani.ui.cache.CacheScreen(onPlay = { navController.navigate(Routes.PLAYER) })
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("subjectID") { }),
            ) { entry ->
                val subjectID = entry.arguments?.getString("subjectID")?.toIntOrNull() ?: 0
                SubjectDetailScreen(
                    subjectID = subjectID,
                    onBack = { navController.popBackStack() },
                    onPlay = { navController.navigate(Routes.PLAYER) },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(onClose = { navController.popBackStack() })
            }
            composable(Routes.LOGIN) {
                com.sparkhoward.nagomiani.ui.bangumi.LoginScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}
