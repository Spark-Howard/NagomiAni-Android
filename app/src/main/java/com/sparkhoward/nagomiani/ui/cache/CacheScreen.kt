package com.sparkhoward.nagomiani.ui.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download
import coil.compose.AsyncImage
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.download.DownloadUtil
import com.sparkhoward.nagomiani.player.PlaybackBus
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader

/** 缓存列表行：resumeKey = "online:站:番:集号"，番名/集号从中解析 */
private data class CacheRow(
    val resumeKey: String,
    val number: Int,
    val showTitle: String,
    val coverURL: String?,
    val item: DownloadUtil.ItemState,
) {
    val isCompleted: Boolean get() = item.isCompleted
}

/**
 * 离线缓存页：正在缓存（含暂停/失败的未完成记录）+ 已缓存 两个分区，
 * 数据 = DownloadUtil.states（media3 DownloadManager 索引，进程内实时更新）；
 * 标题优先用下载记录自带的番名，封面从已注册番剧（继续观看/云端番库）补齐。
 * 点已缓存的行直接离线播放（按 URL 命中本地缓存，缺的段联网补）。
 */
@Composable
fun CacheScreen(onPlay: () -> Unit = {}) {
    val states by DownloadUtil.states.collectAsState()
    // seriesKey → (番名, 封面)（knownShows/云端番库里注册过的才有封面）
    var showMeta by remember { mutableStateOf<Map<String, Pair<String, String?>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val meta = HashMap<String, Pair<String, String?>>()
        runCatching {
            AppGraph.store.knownShows().forEach { meta[it.seriesKey] = it.title to it.coverURL }
            AppGraph.store.cloudLibraryOnce().forEach { entry ->
                if (!meta.containsKey(entry.seriesKey)) meta[entry.seriesKey] = entry.title to entry.coverURL
            }
        }
        showMeta = meta
    }

    val rows = states.map { (key, item) ->
        val seriesKey = key.substringBeforeLast(':')
        val meta = showMeta[seriesKey]
        CacheRow(
            resumeKey = key,
            number = key.substringAfterLast(':').toIntOrNull() ?: 0,
            showTitle = meta?.first ?: item.title.orEmpty().ifEmpty { "未知番剧" },
            coverURL = item.coverURL ?: meta?.second,
            item = item,
        )
    }.sortedWith(compareBy({ it.showTitle }, { it.number }))
    val downloading = rows.filterNot { it.isCompleted }
    val completed = rows.filter { it.isCompleted }

    // 多选管理模式 + 待确认删除集合（resumeKey）；删除一律经确认弹窗
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmKeys by remember { mutableStateOf<Set<String>?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 固定顶栏（不随列表滚动）：标题 / 多选操作条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Text(
                    "已选 ${selected.size} 项",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NagomiColors.accent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "全选",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { selected = rows.map { it.resumeKey }.toSet() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "删除",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(enabled = selected.isNotEmpty()) { confirmKeys = selected }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "取消",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable {
                            selecting = false
                            selected = emptySet()
                        }
                        .padding(start = 8.dp),
                )
            } else {
                Text("离线缓存", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (rows.isNotEmpty()) {
                    Text(
                        "管理",
                        fontSize = 13.sp,
                        color = NagomiColors.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                selecting = true
                                selected = emptySet()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (selecting) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.fillMaxSize()) {

        if (rows.isEmpty()) {
            item {
                Text(
                    "暂无缓存。在条目详情页「缓存全部」「选集缓存」或长按集数方框即可添加。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (downloading.isNotEmpty()) {
            item { NagomiSectionHeader("正在缓存", Icons.Filled.Download, Modifier.padding(horizontal = 16.dp)) }
            items(downloading, key = { it.resumeKey }) { row ->
                CacheRowItem(
                    row,
                    onPlay = onPlay,
                    selectionMode = selecting,
                    isSelected = row.resumeKey in selected,
                    onToggleSelect = {
                        selected = if (row.resumeKey in selected) selected - row.resumeKey
                        else selected + row.resumeKey
                    },
                    onDeleteRequest = { confirmKeys = setOf(row.resumeKey) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        if (completed.isNotEmpty()) {
            item {
                NagomiSectionHeader(
                    "已缓存",
                    Icons.Filled.DownloadDone,
                    Modifier.padding(horizontal = 16.dp).padding(top = if (downloading.isEmpty()) 0.dp else 8.dp),
                )
            }
            items(completed, key = { it.resumeKey }) { row ->
                CacheRowItem(
                    row,
                    onPlay = onPlay,
                    selectionMode = selecting,
                    isSelected = row.resumeKey in selected,
                    onToggleSelect = {
                        selected = if (row.resumeKey in selected) selected - row.resumeKey
                        else selected + row.resumeKey
                    },
                    onDeleteRequest = { confirmKeys = setOf(row.resumeKey) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
        }
    }

    // 删除确认弹窗（单条/批量共用）
    confirmKeys?.let { keys ->
        val first = rows.firstOrNull { it.resumeKey == keys.first() }
        AlertDialog(
            onDismissRequest = { confirmKeys = null },
            title = { Text("删除缓存") },
            text = {
                Text(
                    if (keys.size == 1 && first != null) {
                        "确定删除「${first.showTitle} 第 ${first.number} 集」的缓存吗？删除后需重新缓存。"
                    } else {
                        "确定删除已选的 ${keys.size} 项缓存吗？删除后需重新缓存。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    keys.forEach { DownloadUtil.removeDownload(it) }
                    confirmKeys = null
                    selecting = false
                    selected = emptySet()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmKeys = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CacheRowItem(
    row: CacheRow,
    onPlay: () -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                when {
                    selectionMode -> onToggleSelect()
                    row.isCompleted -> {
                        // 已缓存：按原始 URL 装配播放——命中本地离线内容，缺的段联网补
                        DownloadUtil.playbackFor(row.resumeKey)?.let { playback ->
                            PlaybackBus.launch(playback)
                            onPlay()
                        }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            // 选择指示圈
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) NagomiColors.accent else Color.Transparent)
                    .border(1.5.dp, if (isSelected) NagomiColors.accent else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        AsyncImage(
            model = row.coverURL,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(row.showTitle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${row.number} 集",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!row.isCompleted && row.item.state == Download.STATE_FAILED) {
                    Text(
                        "  ·  失败，删除后可重新缓存",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!row.isCompleted) {
                LinearProgressIndicator(
                    // percentDownloaded 量纲 0–100（-1 = 未知，如清单尚未解析完），夹到合法区间
                    progress = { row.item.percent.coerceIn(0f, 100f) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = NagomiColors.accent,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(
                statusLabel(row),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (row.isCompleted) NagomiColors.watchedGreen else NagomiColors.accent,
            )
        }
        if (!selectionMode) {
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除缓存",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLabel(row: CacheRow): String = when {
    row.item.state == Download.STATE_COMPLETED -> "已缓存"
    row.item.state == Download.STATE_STOPPED -> "已暂停"
    row.item.state == Download.STATE_FAILED -> "失败"
    // 并发上限（maxParallelDownloads=3）之外的集在排队等待
    row.item.state == Download.STATE_QUEUED -> "排队中"
    // 清单尚未解析完时 percent = -1（未知），不能显示成负数百分比
    row.item.percent < 0f -> "准备中"
    else -> "${row.item.percent.toInt()}%"
}
