package com.sparkhoward.nagomiani.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.SubjectInfobox
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.player.PlaybackBus
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader

/** 条目详情页：头部/评分/标签/infobox/简介 + 我的收藏 + 在线观看（跨片源） */
@Composable
fun SubjectDetailScreen(
    subjectID: Int,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    viewModel: SubjectDetailViewModel = viewModel(key = "detail-$subjectID"),
) {
    LaunchedEffect(subjectID) { viewModel.load(subjectID) }
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary)
            }
            Text("条目详情", style = MaterialTheme.typography.titleMedium)
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NagomiColors.accent)
            }
            state.subject == null -> Text(
                state.error ?: "加载失败",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            else -> DetailBody(state, viewModel, onPlay)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(state: SubjectDetailViewModel.State, viewModel: SubjectDetailViewModel, onPlay: () -> Unit) {
    val subject = state.subject!!
    var infoboxExpanded by remember { mutableStateOf(false) }
    var onlineExpanded by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 避让手势条/三键导航：内容较长时底部不再被屏幕按键遮挡
            .navigationBarsPadding()
    ) {
        // 头部
        Row(Modifier.padding(horizontal = 16.dp)) {
            AsyncImage(
                model = subject.images?.bestURL(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .width(130.dp)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(subject.displayName, style = MaterialTheme.typography.titleLarge)
                Text(subject.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                subject.rating?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★ ${"%.1f".format(r.score)}", color = NagomiColors.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        if (r.rank > 0) Text("  #${r.rank}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("  ${r.total} 人评分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                subject.collection?.let { c ->
                    Text(
                        "在看 ${c.doing} · 看过 ${c.collect} · 想看 ${c.wish} · 搁置 ${c.onHold} · 抛弃 ${c.dropped}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                subject.airDate?.let { Text("开播 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        // 我的收藏
        state.myCollection?.let { myRaw ->
            Column(Modifier.padding(16.dp)) {
                NagomiSectionHeader("我的收藏", Icons.Filled.ExpandMore)
                Row(Modifier.padding(top = 8.dp)) {
                    CollectionType.entries.forEach { type ->
                        val selected = myRaw == type.raw
                        Text(
                            type.label,
                            color = if (selected) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            modifier = Modifier
                                .clickable { viewModel.setCollection(type) }
                                .padding(end = 14.dp),
                        )
                    }
                    Text(
                        "取消收藏",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { viewModel.removeCollection() },
                    )
                }
                state.collectionMessage?.let { Text(it, fontSize = 12.sp, color = NagomiColors.watchedGreen) }
            }
        }

        // 标签（自动换行，窄屏不再裁切）
        if (subject.tags.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                subject.tags.take(5).forEach { NagomiBadge("${it.name} ${it.count}", MaterialTheme.colorScheme.primary) }
            }
        }

        // Infobox
        if (subject.infobox.isNotEmpty()) {
            Column(Modifier.padding(16.dp)) {
                NagomiSectionHeader("信息", Icons.Filled.ExpandMore)
                val rows = if (infoboxExpanded) subject.infobox else subject.infobox.take(4)
                rows.forEach { box -> InfoboxRow(box) }
                if (subject.infobox.size > 4) {
                    Text(
                        if (infoboxExpanded) "收起" else "展开全部 ${subject.infobox.size} 项",
                        color = NagomiColors.accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { infoboxExpanded = !infoboxExpanded }
                            .padding(top = 4.dp),
                    )
                }
            }
        }

        // 简介
        if (subject.summary.isNotBlank()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                NagomiSectionHeader("简介", Icons.Filled.ExpandMore)
                Text(
                    subject.summary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = if (infoboxExpanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // 在线观看
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                NagomiSectionHeader("在线观看", Icons.Filled.PlayCircle, Modifier.weight(1f))
                Icon(
                    if (onlineExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onlineExpanded = !onlineExpanded },
                )
            }
            if (onlineExpanded) {
                if (state.sourcesLoading) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.size(18.dp))
                        Text("  正在搜索片源…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 每站搜索状态（量子 2 · 极速 0 · 暴风 失败 …）+ 手动刷新
                state.sourcesStatus?.let {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (!state.sourcesLoading) {
                            Text(
                                "刷新",
                                fontSize = 12.sp,
                                color = NagomiColors.accent,
                                modifier = Modifier
                                    .clickable { viewModel.retrySources() }
                                    .padding(start = 8.dp),
                            )
                        }
                    }
                }
                state.sourcesError?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Text(
                            "  重试",
                            color = NagomiColors.accent,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { viewModel.retrySources() },
                        )
                    }
                }
                state.sources.forEach { (show, episodes) ->
                    var expanded by remember(show.id) { mutableStateOf(false) }
                    Column(Modifier.padding(top = 8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NagomiBadge(show.providerID, MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(show.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${episodes.size} 集", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (expanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                // 选集缓存模式：进入后点集数勾选，确认批量缓存（收起该站即退出模式）
                                var selecting by remember(show.id) { mutableStateOf(false) }
                                var selected by remember(show.id) { mutableStateOf(emptySet<Int>()) }
                                // 操作行：醒目"加入番库" + "缓存全部" + "选集缓存"
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    val bookmarked = state.bookmarkedKeys.contains(show.seriesKey)
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (bookmarked) NagomiColors.accentSoft else NagomiColors.accent)
                                            .clickable { if (!bookmarked) viewModel.bookmark(show) }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (bookmarked) Icons.Filled.Check else Icons.Filled.FavoriteBorder,
                                                null,
                                                tint = if (bookmarked) NagomiColors.accent else androidx.compose.ui.graphics.Color.White,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                if (bookmarked) "已在番库" else "加入番库",
                                                color = if (bookmarked) NagomiColors.accent else androidx.compose.ui.graphics.Color.White,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 5.dp),
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(999.dp))
                                            .border(1.dp, NagomiColors.accent, RoundedCornerShape(999.dp))
                                            .clickable { viewModel.cacheEpisodes(show, episodes) }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.Download,
                                                null,
                                                tint = NagomiColors.accent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                "缓存全部",
                                                color = NagomiColors.accent,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 5.dp),
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(999.dp))
                                            .border(
                                                1.dp,
                                                if (selecting) NagomiColors.accent else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(999.dp),
                                            )
                                            .clickable {
                                                selecting = !selecting
                                                if (!selecting) selected = emptySet()
                                            }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.PlaylistAdd,
                                                null,
                                                tint = NagomiColors.accent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                "选集缓存",
                                                color = NagomiColors.accent,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 5.dp),
                                            )
                                        }
                                    }
                                }
                                // 选集模式操作条：已选计数 + 全选 + 确认 + 取消
                                if (selecting) {
                                    val dlStates by viewModel.downloadStates.collectAsState()
                                    Row(
                                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "已选 ${selected.size} 集",
                                            color = NagomiColors.accent,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "全选",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clickable {
                                                    // 全选 = 排除未放送与已在缓存中（完成/下载中）的集
                                                    selected = episodes.filter { ep ->
                                                        val st = dlStates[ep.resumeKey]
                                                        ep.number !in state.unairedEpisodes &&
                                                            st?.let { it.isCompleted || it.isActive } != true
                                                    }.map { it.number }.toSet()
                                                }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                        )
                                        Text(
                                            "确认缓存",
                                            fontSize = 13.sp,
                                            color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            else NagomiColors.accent,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clickable(enabled = selected.isNotEmpty()) {
                                                    viewModel.cacheEpisodes(
                                                        show,
                                                        episodes.filter { selected.contains(it.number) },
                                                    )
                                                    selecting = false
                                                    selected = emptySet()
                                                }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
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
                                                .padding(start = 6.dp),
                                        )
                                    }
                                }
                                EpisodeGrid(
                                    show, episodes, state.watchedNumbers, state.unairedEpisodes,
                                    selectionMode = selecting,
                                    selectedNumbers = selected,
                                    onToggleSelect = { n ->
                                        selected = if (n in selected) selected - n else selected + n
                                    },
                                    viewModel, onPlay,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 集数方框网格：固定 4 列、从左到右从上到下；已看绿字、已缓存右下角小图标、下载中显示百分比；
 * 未放送的集（官方 airdate 未到）置灰并显示开播日期，禁点禁长按——资源站常提前挂先行配信/错标内容。
 * 选集缓存模式下点/长按都是切换勾选（不触发播放/单集缓存），选中的集高亮 + 右上角对勾
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeGrid(
    show: com.sparkhoward.nagomiani.core.model.OnlineShow,
    episodes: List<com.sparkhoward.nagomiani.core.model.OnlineEpisode>,
    watchedNumbers: Set<Int>,
    unairedAirdates: Map<Int, String>,
    selectionMode: Boolean,
    selectedNumbers: Set<Int>,
    onToggleSelect: (Int) -> Unit,
    viewModel: SubjectDetailViewModel,
    onPlay: () -> Unit,
) {
    val downloadStates by viewModel.downloadStates.collectAsState()
    // LazyVerticalGrid 不能放在 verticalScroll 里——用固定列数的手动分行
    episodes.chunked(4).forEach { rowEps ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (episode in rowEps) {
                val watched = watchedNumbers.contains(episode.number)
                val airdate = unairedAirdates[episode.number]
                val unaired = airdate != null
                val isSelected = selectionMode && selectedNumbers.contains(episode.number)
                val dl = downloadStates[episode.resumeKey]
                Box(Modifier.weight(1f).padding(bottom = 8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.6f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isSelected -> NagomiColors.accentSoft
                                    unaired -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    watched -> NagomiColors.watchedGreen.copy(alpha = 0.10f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                },
                            )
                            .border(
                                1.dp,
                                when {
                                    isSelected -> NagomiColors.accent
                                    watched -> NagomiColors.watchedGreen.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                RoundedCornerShape(8.dp),
                            )
                            .combinedClickable(
                                enabled = !unaired,
                                onClick = {
                                    if (selectionMode) onToggleSelect(episode.number)
                                    else viewModel.launchEpisode(show, episode, onLaunched = onPlay)
                                },
                                onLongClick = {
                                    if (selectionMode) onToggleSelect(episode.number)
                                    else viewModel.toggleEpisodeCache(show, episode)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${episode.number}",
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = when {
                                isSelected -> NagomiColors.accent
                                watched -> NagomiColors.watchedGreen
                                unaired -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        // 选集模式：选中集右上角对勾
                        if (isSelected) Icon(
                            Icons.Filled.Check,
                            null,
                            tint = NagomiColors.accent,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(13.dp),
                        )
                        // 未放送：底部标开播日期（MM-dd），到点自动解禁
                        if (unaired) Text(
                            airdate!!.takeLast(5),
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                        )
                        // 缓存状态角标（右下角）
                        when {
                            dl?.isCompleted == true -> Icon(
                                Icons.Filled.DownloadDone,
                                null,
                                tint = NagomiColors.watchedGreen,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(13.dp),
                            )
                            dl != null && dl.isActive -> Text(
                                // percentDownloaded 量纲 0–100（-1=未知），直接取整显示
                                "${dl.percent.coerceAtLeast(0f).toInt()}%",
                                fontSize = 9.sp,
                                color = NagomiColors.accent,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                            )
                        }
                    }
                }
            }
            // 行末补位（最后一行不足 4 个时保持方框等宽）
            repeat(4 - rowEps.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun InfoboxRow(box: SubjectInfobox) {
    // 值可能是：字符串、字符串数组、{v:"…"} 对象或其数组（别名/制作公司等）——统一展平
    val valueText = when (val v = box.value) {
        is kotlinx.serialization.json.JsonPrimitive -> v.content
        is kotlinx.serialization.json.JsonObject ->
            (v["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        is kotlinx.serialization.json.JsonArray -> v.mapNotNull { e ->
            when (e) {
                is kotlinx.serialization.json.JsonPrimitive -> e.content
                is kotlinx.serialization.json.JsonObject ->
                    (e["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                else -> null
            }
        }.joinToString(" / ")
        else -> ""
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(box.key, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(valueText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
