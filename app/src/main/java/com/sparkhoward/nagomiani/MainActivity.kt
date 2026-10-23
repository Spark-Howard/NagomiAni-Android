package com.sparkhoward.nagomiani

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sparkhoward.nagomiani.core.update.UpdateCenter
import com.sparkhoward.nagomiani.ui.bangumi.BangumiScreen
import com.sparkhoward.nagomiani.ui.history.HistoryScreen
import com.sparkhoward.nagomiani.ui.search.SearchScreen
import com.sparkhoward.nagomiani.ui.search.SubjectDetailScreen
import com.sparkhoward.nagomiani.ui.theme.NagomiTheme
import com.sparkhoward.nagomiani.ui.widget.WidgetIntents

/** 主导航路由（合规版：无播放器/番库/缓存——在线片源功能已整体移除） */
object Routes {
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val BANGUMI = "bangumi"
    const val DETAIL = "detail/{subjectID}"
    const val LOGIN = "login"

    fun detail(subjectID: Int) = "detail/$subjectID"
}

/** 外部入口（小部件/长按快捷方式）的路由请求：route=search/history/bangumi/detail */
private data class RouteRequest(val route: String, val subjectID: Int? = null) {
    fun navigate(nav: NavHostController) {
        when (route) {
            "detail" -> subjectID?.takeIf { it > 0 }?.let { nav.navigate(Routes.detail(it)) { launchSingleTop = true } }
            else -> if (route == Routes.SEARCH || route == Routes.HISTORY || route == Routes.BANGUMI) {
                nav.navigate(route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    companion object {
        fun from(intent: Intent?): RouteRequest? {
            val route = intent?.getStringExtra(WidgetIntents.EXTRA_ROUTE) ?: return null
            if (route == "detail") {
                return RouteRequest(route, intent.getIntExtra(WidgetIntents.EXTRA_SUBJECT_ID, 0).takeIf { it > 0 })
            }
            return RouteRequest(route)
        }
    }
}

class MainActivity : ComponentActivity() {

    // 小部件/快捷方式的路由请求：onCreate 与 onNewIntent 都写入，NavHost 内 LaunchedEffect 消费
    private val routeRequest = mutableStateOf<RouteRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 只在首次创建消费路由请求：Activity 重建（深浅色切换/进程恢复）时系统会重投
        // 原始 Intent（route extra 仍在），再解析会把用户从当前页面弹回小部件当初的目标页
        if (savedInstanceState == null) {
            routeRequest.value = RouteRequest.from(intent)
        }
        setContent {
            NagomiTheme {
                NagomiNavHost(routeRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop：App 在前台/后台时点小部件或快捷方式走这里，不重建 Activity
        routeRequest.value = RouteRequest.from(intent)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun NagomiNavHost(routeRequest: androidx.compose.runtime.MutableState<RouteRequest?>) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 消费小部件/快捷方式的路由请求（onCreate 冷启 + onNewIntent 热启都会落到这里），导航后清空防重复
    val request by routeRequest
    LaunchedEffect(request) {
        request?.let {
            it.navigate(navController)
            routeRequest.value = null
        }
    }

    val activityContext = LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        // 应用内更新：启动静默检查 GitHub Releases（无新版本/网络失败均不打扰）
        UpdateCenter.checkForUpdate(activityContext)
        // 今日更新提醒：注册每日定时 + 启动补一次检查；Android 13+ 先请求通知权限
        com.sparkhoward.nagomiani.core.notify.DailyAiringNotifier.ensureScheduled(activityContext)
        com.sparkhoward.nagomiani.core.notify.DailyAiringNotifier.checkNow(activityContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    UpdateHost()

    val tabs = listOf(
        Tab(Routes.SEARCH, "搜索", Icons.Filled.Search),
        Tab(Routes.HISTORY, "历史", Icons.AutoMirrored.Filled.MenuBook),
        Tab(Routes.BANGUMI, "追番", Icons.Filled.Person),
    )
    val showTabs = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
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
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.SEARCH,
            ) {
                composable(Routes.SEARCH) {
                    SearchScreen(onOpenDetail = { navController.navigate(Routes.detail(it)) })
                }
                composable(Routes.HISTORY) {
                    // 底部 Tab 直达：无返回箭头；续播入口已随在线片源功能移除
                    HistoryScreen()
                }
                composable(Routes.BANGUMI) {
                    BangumiScreen(onOpenDetail = { navController.navigate(Routes.detail(it)) }, onOpenLogin = { navController.navigate(Routes.LOGIN) })
                }
                composable(
                    Routes.DETAIL,
                    arguments = listOf(navArgument("subjectID") { }),
                ) { entry ->
                    val subjectID = entry.arguments?.getString("subjectID")?.toIntOrNull() ?: 0
                    SubjectDetailScreen(
                        subjectID = subjectID,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.LOGIN) {
                    com.sparkhoward.nagomiani.ui.bangumi.LoginScreen(onDone = { navController.popBackStack() })
                }
            }
        }
    }
}

// MARK: - 应用内更新弹窗（发现新版本 → 下载进度 → 安装）

@Composable
private fun UpdateHost() {
    val updateState by UpdateCenter.state.collectAsState()
    if (updateState is UpdateCenter.State.Idle) return
    val context = LocalContext.current
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
