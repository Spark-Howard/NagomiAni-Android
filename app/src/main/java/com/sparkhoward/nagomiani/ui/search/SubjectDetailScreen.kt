package com.sparkhoward.nagomiani.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.Episode
import com.sparkhoward.nagomiani.core.model.SubjectInfobox
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader
import com.sparkhoward.nagomiani.ui.theme.NagomiSegmented

/** 条目详情页（合规版）：头部/评分/标签/infobox/简介 + 我的收藏管理。
 *  在线片源功能已整体移除——本页只做 Bangumi 条目浏览与收藏管理 */
@Composable
fun SubjectDetailScreen(
    subjectID: Int,
    onBack: () -> Unit,
    viewModel: SubjectDetailViewModel = viewModel(key = "detail-$subjectID"),
) {
    LaunchedEffect(subjectID) { viewModel.load(subjectID) }
    // 从网页端/后台回到 App 时静默刷新收藏态：在网页删除收藏后返回，徽章不能停在旧状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, subjectID) {
        var resumedOnce = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 首个 ON_RESUME 与 LaunchedEffect 的整页加载重叠，跳过避免重复请求
                if (resumedOnce) viewModel.refreshCollectionOf(subjectID) else resumedOnce = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
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
            else -> DetailBody(state, viewModel)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(state: SubjectDetailViewModel.State, viewModel: SubjectDetailViewModel) {
    val subject = state.subject!!
    var infoboxExpanded by remember { mutableStateOf(false) }
    // 分集批量操作确认（true=全部看过 / false=清除全部）
    var confirmBatch by remember { mutableStateOf<Boolean?>(null) }

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

        // 我的收藏（五态按钮常显：未收藏也可直接标记；选择即 POST 同步 Bangumi——
        // 与追番页同款胶囊分段选择器风格；未登录只提示不同步）
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NagomiSectionHeader("我的收藏", Icons.Filled.FavoriteBorder, Modifier.weight(1f))
                // 当前收藏状态徽章：仅靠胶囊选中态不够醒目，直接亮出「在看/未收藏」
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
            // 我的评分 + 短评（已登录且已收藏时显示；Bangumi 1-10 分制 = 5 星 ×2）
            if (state.isLoggedIn && state.myCollection != null) {
                // 草稿未动过时跟随服务端值（ON_RESUME 静默刷新不覆盖正在输入的内容）
                var rateDraft by remember { mutableStateOf<Int?>(null) }
                var commentDraft by remember { mutableStateOf<String?>(null) }
                val rate = rateDraft ?: (state.myRate ?: 0)
                val comment = commentDraft ?: (state.myComment ?: "")
                Column(Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("我的评分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            if (rate > 0) "$rate 分" else "未评分",
                            fontSize = 12.sp,
                            color = NagomiColors.accent,
                        )
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
                                .clickable(enabled = rate > 0 || comment.isNotBlank()) {
                                    viewModel.saveRating(rate.takeIf { it > 0 }, comment)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (state.isLoggedIn) {
                if (state.myCollection != null) {
                    var showRemoveDialog by remember { mutableStateOf(false) }
                    Text(
                        "移除收藏",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { showRemoveDialog = true }
                            .padding(top = 6.dp),
                    )
                    if (showRemoveDialog) {
                        val context = LocalContext.current
                        // Bangumi v0 无删除收藏端点（官方 PR 因服务端软删除未实现被搁置）——
                        // 不能发请求假装删除（空体 POST 是 upsert，未收藏时反而会新建「在看」），
                        // 只能如实说明并深链网页端；官方建议用「抛弃」状态代替删除
                        AlertDialog(
                            onDismissRequest = { showRemoveDialog = false },
                            title = { Text("移除收藏") },
                            text = {
                                Text("Bangumi 官方 API 暂不支持删除收藏记录（官方建议改用「抛弃」状态）。要彻底删除请到网页端操作，删除后回到本页会自动同步。")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRemoveDialog = false
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://bgm.tv/subject/${subject.id}")),
                                    )
                                }) { Text("去网页端删除") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRemoveDialog = false }) { Text("知道了") }
                            },
                        )
                    }
                }
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
                        Text(
                            "已看 $watchedCount / ${state.episodes.size}",
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
                                    .clickable { confirmBatch = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            Text(
                                "清除",
                                fontSize = 12.sp,
                                color = if (watchedCount > 0) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable(enabled = watchedCount > 0) { confirmBatch = false }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (state.episodesLoading) {
                    CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.padding(top = 10.dp).size(18.dp))
                } else {
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
                                ) { viewModel.toggleEpisode(ep) }
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
                    TextButton(onClick = { confirmBatch = null; viewModel.markAllEpisodes(markAll) }) {
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
        Spacer(Modifier.height(24.dp))
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
