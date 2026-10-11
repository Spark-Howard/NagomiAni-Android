package com.sparkhoward.nagomiani

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.sparkhoward.nagomiani.core.update.UpdateCenter
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
        handleNotifyIntent(intent)
        setContent {
            NagomiTheme {
                NagomiNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotifyIntent(intent)
    }

    /** 通知点按路由：切到番库页（onCreate / onNewIntent 两路统一）。
     *  画中画中点通知：先把任务带回全屏（对小窗内的 Activity startActivity 自身即触发
     *  系统还原全屏），展开产生的 onNewIntent 带 pipExpand 标记后完成跳转（防循环）——
     *  否则番库页会渲染在四分之一屏的系统小窗里 */
    private fun handleNotifyIntent(intent: Intent?) {
        if (intent?.getStringExtra(com.sparkhoward.nagomiani.core.notify.EpisodeNotify.EXTRA_OPEN_TAB) !=
            com.sparkhoward.nagomiani.core.notify.EpisodeNotify.OPEN_TAB_LIBRARY
        ) {
            return
        }
        if (com.sparkhoward.nagomiani.player.PlaybackBus.inPip.value && !intent.getBooleanExtra(EXTRA_PIP_EXPAND, false)) {
            startActivity(
                intent.putExtra(EXTRA_PIP_EXPAND, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            return
        }
        NavEvents.openLibrary.value += 1
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 回桌面保留「画中画」：正在播放时自动切系统画中画，用户回 App 即还原。
        // 小窗态先回到播放器路由——PiP 渲染的是 Activity 当前页，必须保证是画面而非底层列表
        if (com.sparkhoward.nagomiani.player.PlaybackBus.isPlaying.value) {
            NavEvents.enterPipHook?.invoke()
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(16, 9))
                        .build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.sparkhoward.nagomiani.player.PlaybackBus.inPip.value = isInPictureInPictureMode
    }
}

/** 通知点按 → 切到番库页的事件通道；onUserLeaveHint 借由 hook 借道导航回播放器路由 */
private object NavEvents {
    val openLibrary = kotlinx.coroutines.flow.MutableStateFlow(0)
    var enterPipHook: (() -> Unit)? = null
}

private const val EXTRA_PIP_EXPAND = "pipExpand"

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun NagomiNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val playingNow by com.sparkhoward.nagomiani.player.PlaybackBus.isPlaying.collectAsState()
    val hasPlayback by com.sparkhoward.nagomiani.player.PlaybackBus.pending.collectAsState()
    val miniActive by com.sparkhoward.nagomiani.player.PlaybackBus.miniActive.collectAsState()

    // Android 13+ 通知权限：缓存前台服务的进度通知依赖它（未授权时后台缓存仍工作，只是通知不可见）
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        // 拉起下载服务：载入缓存索引（缓存页/角标展示）并恢复上次被杀中断的下载
        com.sparkhoward.nagomiani.core.download.DownloadUtil.ensureServiceStarted(activityContext)
        // 追番更新提醒：注册每日后台检查（幂等，KEEP 策略）
        com.sparkhoward.nagomiani.core.notify.EpisodeNotify.ensureScheduled(activityContext)
        // 应用内更新：启动静默检查 GitHub Releases（无新版本/网络失败均不打扰）
        com.sparkhoward.nagomiani.core.update.UpdateCenter.checkForUpdate(activityContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 通知点按「更新到第 N 集」→ 跳番库页（onCreate/onNewIntent 两路都汇到这里）
    LaunchedEffect(Unit) {
        NavEvents.openLibrary.collect { count ->
            if (count > 0) navController.navigate(Routes.LIBRARY) { launchSingleTop = true }
        }
    }

    // 回桌面自动画中画：小窗态先回播放器路由（PiP 渲染的是 Activity 当前页）
    androidx.compose.runtime.DisposableEffect(Unit) {
        NavEvents.enterPipHook = {
            com.sparkhoward.nagomiani.player.PlaybackBus.miniActive.value = false
            navController.navigate(Routes.PLAYER) { launchSingleTop = true }
        }
        onDispose { NavEvents.enterPipHook = null }
    }

    UpdateHost()

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
                // 回到播放中：有播放会话、不在播放器页且不在悬浮小窗时显示
                if (showTabs && hasPlayback != null && !miniActive) {
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
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.SEARCH,
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
                composable(
                    Routes.PLAYER,
                    // 返回/进小窗立即消失：默认约 700ms 的淡出会让视频画面滞留一秒
                    exitTransition = { androidx.compose.animation.ExitTransition.None },
                    popExitTransition = { androidx.compose.animation.ExitTransition.None },
                    popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                ) {
                    PlayerScreen(
                        onClose = { navController.popBackStack() },
                        // 应用内悬浮小窗：置位后 pop 返回上一层，视频在根层小窗继续播
                        onMinimize = {
                            com.sparkhoward.nagomiani.player.PlaybackBus.miniActive.value = true
                            navController.popBackStack()
                        },
                    )
                }
                composable(Routes.LOGIN) {
                    com.sparkhoward.nagomiani.ui.bangumi.LoginScreen(onDone = { navController.popBackStack() })
                }
            }
            // 应用内悬浮小窗：覆盖在所有页面之上（不遮底部导航栏）
            com.sparkhoward.nagomiani.player.MiniPlayerOverlay(
                onExpand = { navController.navigate(Routes.PLAYER) },
            )
        }
    }
}

// MARK: - 应用内更新弹窗（发现新版本 → 下载进度 → 安装）

@Composable
private fun UpdateHost() {
    val updateState by UpdateCenter.state.collectAsState()
    if (updateState is UpdateCenter.State.Idle) return
    val context = androidx.compose.ui.platform.LocalContext.current
    when (val s = updateState) {
        is UpdateCenter.State.Available -> AlertDialog(
            onDismissRequest = { UpdateCenter.dismiss(context) },
            title = { Text("发现新版本 ${s.release.tagName.removePrefix("v")}") },
            text = {
                // 发布说明按 Markdown 渲染（标题/列表/粗体/链接），不再显示原文标记
                com.sparkhoward.nagomiani.ui.theme.MarkdownText(
                    s.release.body?.trim()?.take(1500)?.ifBlank { null } ?: "无更新说明",
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { UpdateCenter.startDownload(context, s.release) }) { Text("更新") }
            },
            dismissButton = {
                TextButton(onClick = { UpdateCenter.dismiss(context) }) { Text("下次再说") }
            },
        )

        is UpdateCenter.State.Downloading -> AlertDialog(
            onDismissRequest = {}, // 下载中不关闭（关闭也不取消下载，语义易误解）
            title = { Text("正在下载更新") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) (s.received.toFloat() / s.total) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (s.total > 0) "${fmtMB(s.received)} / ${fmtMB(s.total)}" else "${fmtMB(s.received)} / 未知大小",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateCenter.State.ReadyToInstall -> AlertDialog(
            onDismissRequest = { UpdateCenter.toIdle() },
            title = { Text("更新已就绪") },
            text = {
                Text(
                    "${s.release.tagName.removePrefix("v")} 下载完成，点「安装」唤起系统安装器。" +
                        "若系统询问，请允许「安装未知应用」。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { UpdateCenter.install(context) }) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { UpdateCenter.toIdle() }) { Text("稍后") }
            },
        )

        is UpdateCenter.State.Failed -> AlertDialog(
            onDismissRequest = { UpdateCenter.toIdle() },
            title = { Text("更新失败") },
            text = { Text(s.message, fontSize = 13.sp) },
            confirmButton = {
                if (s.release != null) {
                    TextButton(onClick = { UpdateCenter.startDownload(context, s.release) }) { Text("重试") }
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateCenter.toIdle() }) { Text("关闭") }
            },
        )

        UpdateCenter.State.Idle -> Unit
    }
}

private fun fmtMB(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
