package com.sparkhoward.nagomiani.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader

/** 搜索页：搜索框 + 结果列表；无关键词时展示"新番放送（近一周 · 未来一周）"周历 */
@Composable
fun SearchScreen(
    onOpenDetail: (Int) -> Unit,
    onPlay: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize()) {
        // 搜索框
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                // Material TextField（光标/选字/输入法行为稳定）；固定高度压扁搜索框
                androidx.compose.material3.TextField(
                    value = state.keyword,
                    onValueChange = viewModel::updateKeyword,
                    placeholder = { Text("搜索动漫条目…", fontSize = 14.sp) },
                    singleLine = true,
                    // 回车 = 直接搜索（键盘右下角变为"搜索"键），搜索后收起键盘
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            viewModel.search()
                        },
                    ),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "搜索",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.clickable { viewModel.search() },
            )
        }

        if (state.keyword.isEmpty()) {
            WeeklySection(state, viewModel, onOpenDetail)
        } else {
            SearchResultSection(state, viewModel, onOpenDetail)
        }
    }
}

@Composable
private fun WeeklySection(state: SearchViewModel.State, viewModel: SearchViewModel, onOpenDetail: (Int) -> Unit) {
    val listState = rememberLazyListState()
    // 两天一档（今天+明天为初始档，往上是更早的、往下是更晚的）；首次载入定位到含今天的档；
    // 手动刷新不重新定位（用户可能正停在别处回看）
    var anchoredToToday by rememberSaveable { mutableStateOf(false) }
    val sections = remember(state.weekly) { com.sparkhoward.nagomiani.core.online.WeeklyCalendar.twoDaySections(state.weekly) }
    LaunchedEffect(state.weekly) {
        if (!anchoredToToday && sections.isNotEmpty()) {
            anchoredToToday = true
            val index = sections.indexOfFirst { sec -> sec.any { it.isToday } }
            // LazyColumn 第 0 项是周历标题行，档位 item 从 1 起——定位要 +1 才落在含今天的档
            if (index >= 0) listState.scrollToItem(index + 1)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NagomiSectionHeader("新番放送（近一周 · 未来一周）", Icons.Filled.Search, Modifier.weight(1f))
                state.weeklyFetchedAt?.let {
                    Text("更新于 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    " 刷新",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { viewModel.loadWeekly() },
                )
            }
        }
        if (state.weeklyLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NagomiColors.accent)
                }
            }
        }
        state.weeklyError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
        sections.forEach { section ->
            item(key = "sec_" + section.first().date.toEpochDay()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    section.filter { it.day.items.isNotEmpty() }.forEachIndexed { idx, slot ->
                        if (idx > 0) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        DaySection(slot, onOpenDetail)
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySection(slot: com.sparkhoward.nagomiani.core.online.WeeklyCalendar.Slot, onOpenDetail: (Int) -> Unit) {
    if (slot.day.items.isEmpty()) return
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            slot.label,
            style = MaterialTheme.typography.titleMedium,
            // 今天高亮、未来常规、过去（已放送）次级色弱化
            color = when {
                slot.isToday -> NagomiColors.accent
                slot.isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onBackground
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(slot.day.items, key = { it.id }) { subject ->
                SubjectCard(subject, onOpenDetail)
            }
        }
    }
}

@Composable
private fun SubjectCard(subject: Subject, onOpenDetail: (Int) -> Unit) {
    Column(
        Modifier
            .width(110.dp)
            .clickable { onOpenDetail(subject.id) },
    ) {
        AsyncImage(
            model = subject.images?.bestURL(),
            contentDescription = subject.displayName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            subject.displayName,
            fontSize = 12.sp,
            // 固定占两行：短标题与长标题的卡片等高，横向拖动时行高不跳变（周历抖动根因）
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 固定评分行高度：无评分/集数的条目同样占位，保证整卡等高
            modifier = Modifier.height(16.dp),
        ) {
            subject.rating?.takeIf { it.score > 0 }?.let {
                Text("★ ${"%.1f".format(it.score)}", fontSize = 11.sp, color = NagomiColors.accent)
            }
            subject.eps?.let { Text(" · $it 集", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SearchResultSection(state: SearchViewModel.State, viewModel: SearchViewModel, onOpenDetail: (Int) -> Unit) {
    if (state.searching) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = NagomiColors.accent)
        }
        return
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        return
    }
    if (state.results.isEmpty()) {
        Text("没有找到相关条目", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        items(state.results, key = { it.id }) { subject ->
            SubjectRow(subject, state.collectionBadge[subject.id], onOpenDetail)
        }
    }
}

@Composable
fun SubjectRow(
    subject: Subject,
    badgeRaw: Int?,
    onOpenDetail: (Int) -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail(subject.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = subject.images?.bestURL(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .width(52.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(subject.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subject.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                subject.rating?.takeIf { it.score > 0 }?.let {
                    Text("★ ${"%.1f".format(it.score)}", fontSize = 12.sp, color = NagomiColors.accent)
                }
                subject.eps?.let { Text(" · $it 集", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                badgeRaw?.let { raw ->
                    Spacer(Modifier.width(6.dp))
                    val type = CollectionType.fromRaw(raw)
                    NagomiBadge(type.label, NagomiColors.accent)
                }
            }
        }
        trailing?.invoke()
    }
}
