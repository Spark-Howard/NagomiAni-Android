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
import com.sparkhoward.nagomiani.ui.theme.minimumTouchTarget
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sparkhoward.nagomiani.core.model.CollectionModifyPayload
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.Episode
import com.sparkhoward.nagomiani.core.model.SubjectInfobox
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.player.PlaybackBus
import com.sparkhoward.nagomiani.ui.library.LatencyBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiAsyncImage
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader
import com.sparkhoward.nagomiani.ui.theme.NagomiSegmented
import com.sparkhoward.nagomiani.ui.theme.NagomiSkeletonBox

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
            state.loading -> DetailSkeleton()
            state.subject == null -> Text(
                state.error ?: "加载失败",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            else -> DetailBody(state, viewModel, onPlay)
        }
    }
}

/** 详情页首屏骨架：海报块 + 标题/副标题行 + 区块条，替代整页转圈 */
@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            NagomiSkeletonBox(Modifier.width(130.dp).aspectRatio(0.7f), RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp)) {
                NagomiSkeletonBox(Modifier.fillMaxWidth(0.75f).height(20.dp))
                NagomiSkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp).padding(top = 6.dp))
                NagomiSkeletonBox(Modifier.fillMaxWidth(0.4f).height(12.dp).padding(top = 6.dp))
            }
        }
        NagomiSkeletonBox(Modifier.fillMaxWidth(0.35f).height(16.dp).padding(top = 20.dp))
        repeat(3) {
            NagomiSkeletonBox(Modifier.fillMaxWidth().height(14.dp).padding(top = 10.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(state: SubjectDetailViewModel.State, viewModel: SubjectDetailViewModel, onPlay: () -> Unit) {
    val subject = state.subject!!
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var infoboxExpanded by remember { mutableStateOf(false) }
    // 分集批量操作确认（true=全部看过 / false=清除全部）
    var confirmBatch by remember { mutableStateOf<Boolean?>(null) }
    // 单集标记确认（防误触）：点格子先弹窗，确认后才标记/取消
    var pendingToggle by remember { mutableStateOf<Episode?>(null) }
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
            NagomiAsyncImage(
                model = subject.images?.bestURL(),
                contentDescription = null,
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

        // 我的收藏（五态按钮常显：未收藏也可直接标记；选择即 POST 同步 Bangumi——
        // 与追番页同款胶囊分段选择器风格；未登录只提示不同步）
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NagomiSectionHeader("我的收藏", Icons.Filled.FavoriteBorder, Modifier.weight(1f))
                // 当前收藏状态徽章：仅靠胶囊选中态不够醒目，直接亮出「在看/想看/未收藏」
                NagomiBadge(
                    state.myCollection?.let { CollectionType.fromRaw(it).label } ?: "未收藏",
                    if (state.myCollection != null) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (state.collectionSaving) {
                    CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.padding(end = 8.dp).size(16.dp))
                }
            }
            NagomiSegmented(
                options = CollectionType.entries.toList(),
                selected = state.myCollection?.let { CollectionType.fromRaw(it) },
                label = { it?.label ?: "" },
                onSelect = { type -> type?.let { viewModel.setCollection(it) } },
                modifier = Modifier.padding(top = 8.dp),
            )
            state.collectionMessage?.let { msg ->
                Text(
                    msg,
                    fontSize = 12.sp,
                    color = if (msg.startsWith("收藏失败") || msg.startsWith("未登录") || msg.startsWith("评分保存失败") || msg.startsWith("标记失败") || msg.startsWith("批量标记失败"))
                        MaterialTheme.colorScheme.error
                    else NagomiColors.watchedGreen,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // 收藏状态查询失败（401/网络错等）：不再吞错伪装成「未收藏」，如实亮出原因
            state.collectionError?.let { err ->
                Text(
                    err,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // 我的评分 + 短评（已登录且已收藏时显示；Bangumi 1-10 分制 = 5 星 ×2）
            if (state.isLoggedIn && state.myCollection != null) {
                // 草稿未动过时跟随服务端值（静默刷新不覆盖正在输入的内容）
                var rateDraft by remember { mutableStateOf<Int?>(null) }
                var commentDraft by remember { mutableStateOf<String?>(null) }
                val rate = rateDraft ?: (state.myRate ?: 0)
                val comment = commentDraft ?: (state.myComment ?: "")
                Column(Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("我的评分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (rate > 0) "$rate 分" else "未评分", fontSize = 12.sp, color = NagomiColors.accent)
                    }
                    Row {
                        (1..5).forEach { star ->
                            Icon(
                                if (star * 2 <= rate) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "${star * 2} 分",
                                tint = NagomiColors.accent,
                                modifier = Modifier
                                    .padding(top = 4.dp, end = 2.dp)
                                    .size(26.dp)
                                    .clickable { rateDraft = star * 2 },
                            )
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = comment,
                        onValueChange = { commentDraft = it },
                        placeholder = { Text("写点短评…（可空）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                        minLines = 1,
                        maxLines = 3,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NagomiColors.accent,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            "保存评分",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (rate > 0 || comment.isNotBlank()) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .minimumTouchTarget()
                                .clickable(enabled = rate > 0 || comment.isNotBlank()) {
                                    viewModel.saveRating(rate.takeIf { it > 0 }, comment)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (state.isLoggedIn) {
                Text(
                    "取消收藏",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .minimumTouchTarget()
                        .clickable { viewModel.removeCollection() }
                        .padding(top = 6.dp),
                )
            } else {
                Text(
                    "未登录 Bangumi：先到「追番」页登录，收藏状态才会同步",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // 分集标记（点格子切换「看过」；未登录点击只提示；未开播集置灰）
        if (state.episodesLoading || state.episodes.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NagomiSectionHeader("分集", Icons.Filled.Tv, Modifier.weight(1f))
                    if (state.episodes.isNotEmpty()) {
                        val watchedCount = state.episodes.count { it.id in state.watchedEpisodeIds }
                        // 「看到第 X 集」= 已看本篇的最大集号（回显服务端 ep_status 与本地标记的较大者）
                        val watchedMainSorts = state.episodes.filter { it.type == 0 && it.id in state.watchedEpisodeIds }.map { it.displaySort }
                        val latest = maxOf(watchedMainSorts.maxOrNull() ?: 0, state.myEpStatus ?: 0)
                        val progressText = buildString {
                            append("已看 $watchedCount / ${state.episodes.size}")
                            if (latest > 0) append(" · 看到第 $latest 集")
                        }
                        Text(
                            progressText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        if (state.isLoggedIn && !state.episodeSaving) {
                            Text(
                                "全部看过",
                                fontSize = 12.sp,
                                color = NagomiColors.accent,
                                modifier = Modifier
                                    .minimumTouchTarget()
                                    .clickable { confirmBatch = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            Text(
                                "清除",
                                fontSize = 12.sp,
                                color = if (watchedCount > 0) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .minimumTouchTarget()
                                    .clickable(enabled = watchedCount > 0) { confirmBatch = false }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (state.episodesLoading) {
                    CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.padding(top = 10.dp).size(18.dp))
                } else {
                    // 分集/看过记录查询失败：亮出原因（不再静默显示成「没有看过标记」）
                    state.episodeMarksError?.let { err ->
                        Text(
                            err,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    var episodesExpanded by remember { mutableStateOf(false) }
                    val shown = if (episodesExpanded) state.episodes else state.episodes.take(30)
                    shown.chunked(5).forEach { rowEps ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowEps.forEach { ep ->
                                EpisodeMarkChip(
                                    ep = ep,
                                    watched = ep.id in state.watchedEpisodeIds,
                                    unaired = viewModel.isUnaired(ep),
                                    enabled = !state.episodeSaving,
                                    modifier = Modifier.weight(1f),
                                ) { pendingToggle = ep }
                            }
                            repeat(5 - rowEps.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (state.episodes.size > 30) {
                        Text(
                            if (episodesExpanded) "收起" else "展开全部（共 ${state.episodes.size} 集）",
                            fontSize = 12.sp,
                            color = NagomiColors.accent,
                            modifier = Modifier
                                .clickable { episodesExpanded = !episodesExpanded }
                                .padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        // 单集标记确认（防误触）
        pendingToggle?.let { ep ->
            val watched = ep.id in state.watchedEpisodeIds
            val label = if (ep.type == 1) "SP${ep.ep?.toInt() ?: ""}" else "第 ${ep.displaySort} 集"
            AlertDialog(
                onDismissRequest = { pendingToggle = null },
                title = { Text(if (watched) "取消标记" else "标记为看过") },
                text = { Text("「${subject.displayName}」$label" + if (watched) " 已看过，要取消标记吗？" else " 标记为看过，并同步到 Bangumi？") },
                confirmButton = {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        pendingToggle = null
                        viewModel.toggleEpisode(ep)
                    }) {
                        Text(if (watched) "取消标记" else "标为看过", color = NagomiColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingToggle = null }) { Text("取消") }
                },
            )
        }

        // 分集批量确认
        confirmBatch?.let { markAll ->
            AlertDialog(
                onDismissRequest = { confirmBatch = null },
                title = { Text(if (markAll) "全部标为看过" else "清除全部标记") },
                text = {
                    Text(
                        if (markAll) "把本条目全部 ${state.episodes.size} 集（含 SP）标记为看过，并同步到 Bangumi。继续吗？"
                        else "清除本条目的全部分集「看过」标记（条目收藏状态不变）。继续吗？"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        confirmBatch = null
                        viewModel.markAllEpisodes(markAll)
                    }) {
                        Text("确认", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBatch = null }) { Text("取消") }
                },
            )
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
                            Spacer(Modifier.width(6.dp))
                            // 最近一次站点测速结果（番库「片源健康」面板测的，帮助选源）
                            LatencyBadge(viewModel.siteHealth.collectAsState().value[show.providerID])
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


/** 分集标记格子：已看=绿底绿框绿字；未开播=置灰禁点；默认灰底。文字=集号（SP 带前缀） */
@Composable
private fun EpisodeMarkChip(
    ep: Episode,
    watched: Boolean,
    unaired: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val clickable = Modifier.clickable(enabled = enabled && !unaired, onClick = onToggle)
    Box(
        modifier
            .then(clickable)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    watched -> NagomiColors.watchedGreen.copy(alpha = 0.10f)
                    unaired -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
            )
            .border(
                1.dp,
                when {
                    watched -> NagomiColors.watchedGreen.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.outline
                },
                RoundedCornerShape(6.dp),
            )
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (ep.type == 1) {
                ep.ep?.let { "SP${it.toInt()}" } ?: "SP" // SP 的 sort 从 1000 起（内部序号），显示用 ep 字段
            } else "${ep.displaySort}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                watched -> NagomiColors.watchedGreen
                unaired -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
