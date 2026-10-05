package com.sparkhoward.nagomiani.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sparkhoward.nagomiani.core.download.DownloadUtil
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader

/** 番库页：继续观看卡片横排 + 云端番库列表（展开分集点播） */
@Composable
fun LibraryScreen(
    onOpenDetail: (Int) -> Unit,
    onPlay: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    // 取消收藏确认弹窗（移除云端条目时会一并清除该番续播记录，需二次确认）
    var confirmRemove by remember { mutableStateOf<LibraryViewModel.CloudRow?>(null) }

    // 每次回到番库页都重读数据：详情页新收藏/继续观看的进展要及时反映
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("番库", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
        }

        // 继续观看
        if (state.continueItems.isNotEmpty()) {
            item { NagomiSectionHeader("继续观看", Icons.Filled.Schedule, Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.continueItems, key = { it.resumeKey }) { item ->
                        ContinueCard(item) {
                            item.playback?.let {
                                com.sparkhoward.nagomiani.player.PlaybackBus.launch(it)
                                onPlay()
                            }
                        }
                    }
                }
            }
        }

        // 云端番库
        item { NagomiSectionHeader("云端番库", Icons.Filled.Tv, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) }
        if (state.cloudRows.isEmpty()) {
            item {
                Text(
                    "还没有云端番。在「搜索」打开条目 → 在线观看 → 收藏片源即可加入。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(state.cloudRows, key = { it.entry.seriesKey }) { row ->
            CloudRowItem(row, state, viewModel, onPlay, onRemoveRequest = { confirmRemove = row })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
        }

        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }

    // 取消收藏确认弹窗
    confirmRemove?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移除云端番库") },
            text = {
                Text("确定从云端番库移除「${row.entry.title}」吗？该番的续播记录也会一并清除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCloud(row)
                    confirmRemove = null
                }) {
                    Text("移除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ContinueCard(item: LibraryViewModel.ContinueItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(200.dp)
            .clickable { onClick() },
    ) {
        Box {
            AsyncImage(
                model = item.coverURL,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Icon(
                Icons.Filled.PlayCircle,
                null,
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
            )
        }
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(item.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        LinearProgressIndicator(
            progress = { item.progress },
            color = NagomiColors.accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text("${(item.progress * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CloudRowItem(
    row: LibraryViewModel.CloudRow,
    state: LibraryViewModel.State,
    viewModel: LibraryViewModel,
    onPlay: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    val expanded = state.expandedSeriesKey == row.entry.seriesKey
    // 下载状态实时流：判断哪些集已缓存（绿色角标）
    val downloadStates by DownloadUtil.states.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { viewModel.toggleExpand(row) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = row.coverURL,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(row.entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NagomiBadge("云端", NagomiColors.accent)
                    if (row.newCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        NagomiBadge("新集 ${row.newCount}", NagomiColors.watchedGreen)
                    }
                    row.episodeCount?.let {
                        Spacer(Modifier.width(6.dp))
                        Text("$it 集", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                row.boundName?.let {
                    Text("已关联「$it」", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Filled.Delete,
                "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { onRemoveRequest() },
            )
        }

        if (expanded) {
            if (state.loading && state.expandedEpisodes.isEmpty()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.size(16.dp))
                    Text("  加载分集…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 选集方块网格（与详情页/播放器面板同款：4 列方框，已看绿字）
            state.expandedEpisodes.chunked(4).forEach { rowEps ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowEps.forEach { episode ->
                        val isWatched = state.watchedNumbers.contains(episode.number)
                        val isCached = downloadStates["${row.entry.seriesKey}:${episode.number}"]?.isCompleted == true
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1.6f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isWatched) NagomiColors.watchedGreen.copy(alpha = 0.10f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .border(
                                    1.dp,
                                    if (isWatched) NagomiColors.watchedGreen.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { viewModel.launchEpisode(row, episode, onLaunched = onPlay) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${episode.number}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isWatched) NagomiColors.watchedGreen else MaterialTheme.colorScheme.onSurface,
                            )
                            // 已缓存：右下角绿色小角标（与详情页一致）
                            if (isCached) {
                                Icon(
                                    Icons.Filled.DownloadDone,
                                    contentDescription = "已缓存",
                                    tint = NagomiColors.watchedGreen,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(13.dp),
                                )
                            }
                        }
                    }
                    repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
