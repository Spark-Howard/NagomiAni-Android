package com.sparkhoward.nagomiani.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.R
import com.sparkhoward.nagomiani.core.update.UpdateCenter
import com.sparkhoward.nagomiani.ui.theme.NagomiCard
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiPillAction
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader
import com.sparkhoward.nagomiani.ui.theme.NagomiSegmented
import com.sparkhoward.nagomiani.ui.theme.NagomiTextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/Spark-Howard/NagomiAni-Android"

/**
 * 设置中心：外观（主题模式 + AMOLED）/ 通知 / 应用内代理 / 存储（图片缓存）/ 关于。
 * 收敛此前散落各页的设置（代理原在追番页、两个通知开关原在番库/追番页）；
 * 弹幕与连播等播放上下文设置仍在播放器内（用时就近调）。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = AppGraph.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary)
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        // ── 外观 ──
        NagomiSectionHeader("外观", Icons.Filled.Palette, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val themeMode by store.themeMode.collectAsState(initial = "system")
        val amoled by store.themeAmoled.collectAsState(initial = false)
        SettingCard {
            Text("主题模式", fontSize = 13.sp)
            NagomiSegmented(
                options = listOf("system", "light", "dark"),
                selected = themeMode,
                label = { when (it) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" } },
                onSelect = { scope.launch { store.setThemeMode(it) } },
                modifier = Modifier.padding(top = 6.dp),
            )
            SwitchRow(
                title = "纯黑深色（AMOLED）",
                hint = "深色模式下用纯黑背景，OLED 屏更省电",
                checked = amoled,
                enabled = themeMode != "light",
            ) { scope.launch { store.setThemeAmoled(it) } }
        }

        // ── 通知 ──
        NagomiSectionHeader("通知", Icons.Filled.NotificationsNone, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val updateNotify by store.updateNotifyEnabled.collectAsState(initial = true)
        val dailyAiring by store.dailyAiringEnabled.collectAsState(initial = true)
        SettingCard {
            SwitchRow(
                title = "番库新集提醒",
                hint = "每日比对云端番库的片源集数，有新集发系统通知",
                checked = updateNotify,
            ) { scope.launch { store.setUpdateNotifyEnabled(it) } }
            SwitchRow(
                title = stringResource(R.string.notify_toggle_label),
                hint = stringResource(R.string.notify_toggle_hint),
                checked = dailyAiring,
            ) { scope.launch { store.setDailyAiringEnabled(it) } }
        }

        // ── 网络 ──
        NagomiSectionHeader("网络", Icons.Filled.Language, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SettingCard {
            var proxyDraft by remember { mutableStateOf("") }
            var proxyMessage by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { proxyDraft = store.networkProxyRaw.first() }
            Text(
                "bgm.tv 在部分网络下被 DNS 污染。填电脑代理的局域网地址（host:port，如 192.168.1.5:7890，"
                    + "电脑代理需开「允许局域网连接」）即可让 API/登录/封面走代理；清空保存恢复直连。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = proxyDraft,
                    onValueChange = { proxyDraft = it },
                    placeholder = { Text("192.168.1.5:7890", fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NagomiColors.accent,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                NagomiPillAction("保存", Icons.Filled.Check) {
                    val raw = proxyDraft.trim()
                    if (raw.isNotEmpty()) {
                        val idx = raw.lastIndexOf(':')
                        val host = if (idx > 0) raw.substring(0, idx).trim() else ""
                        val port = if (idx > 0) raw.substring(idx + 1).trim().toIntOrNull() else null
                        if (host.isEmpty() || port == null || port !in 1..65535) {
                            proxyMessage = "格式不对：应为 host:port，例如 192.168.1.5:7890"
                            return@NagomiPillAction
                        }
                    }
                    scope.launch {
                        store.setNetworkProxy(raw) // 生效由 NagomiApp 统一应用（OkHttp/WebView/Coil）
                        proxyMessage = if (raw.isEmpty()) "已恢复直连" else "代理已保存并生效"
                    }
                }
            }
            proxyMessage?.let {
                Text(it, fontSize = 12.sp, color = NagomiColors.watchedGreen, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // ── 存储 ──
        NagomiSectionHeader("存储", Icons.Filled.Storage, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SettingCard {
            var cacheSize by remember { mutableStateOf<Long?>(null) }
            var clearing by remember { mutableStateOf(false) }
            var cacheMessage by remember { mutableStateOf<String?>(null) }
            val loader = context.imageLoader
            suspend fun measure(): Long? = withContext(Dispatchers.IO) { loader.diskCache?.size }
            LaunchedEffect(Unit) { cacheSize = measure() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("封面图片缓存", fontSize = 14.sp)
                    Text(
                        cacheSize?.let { "%.1f MB".format(it / 1048576.0) } ?: "统计中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NagomiPillAction(
                    "清理",
                    Icons.Filled.Delete,
                    enabled = !clearing && (cacheSize ?: 0) > 0 && cacheMessage == null,
                ) {
                    clearing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
                        cacheSize = measure()
                        clearing = false
                        cacheMessage = "已清理 ✓"
                    }
                }
            }
            cacheMessage?.let {
                Text(it, fontSize = 12.sp, color = NagomiColors.watchedGreen, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                "离线缓存的下载内容在「缓存」页管理，这里只清封面图片缓存。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── 关于 ──
        NagomiSectionHeader("关于", Icons.Filled.Info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SettingCard {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
            }
            var checkMessage by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("版本", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("v$version", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NagomiColors.accent)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NagomiTextAction("检查更新", Icons.Filled.Refresh) {
                    UpdateCenter.checkForUpdate(context)
                    checkMessage = "已开始检查：发现新版本会弹出更新窗口，无提示即已是最新"
                }
                Spacer(Modifier.weight(1f))
                NagomiTextAction("项目主页", Icons.Filled.OpenInNew) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
                }
            }
            checkMessage?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Spacer(Modifier.size(24.dp))
    }
}

/** 开关行：标题 + 说明 + 樱粉 Switch（通知/主题等设置项通用） */
@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = NagomiColors.accent),
            modifier = Modifier.heightIn(min = 26.dp),
        )
    }
}

/** 设置卡片容器：粉描边卡片 + 内边距（与详情页区块同视觉） */
@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    NagomiCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}
