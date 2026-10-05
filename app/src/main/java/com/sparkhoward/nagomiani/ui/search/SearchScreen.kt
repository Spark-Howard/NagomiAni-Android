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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sparkhoward.nagomiani.core.model.CalendarDay
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader

/** 搜索页：搜索框 + 结果列表；无关键词时展示"最近更新（过去一周）"周历 */
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
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NagomiSectionHeader("最近更新（过去一周）", Icons.Filled.Search, Modifier.weight(1f))
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
        items(state.weekly, key = { it.weekday.id }) { day ->
            DaySection(day, onOpenDetail)
        }
    }
}

@Composable
private fun DaySection(day: CalendarDay, onOpenDetail: (Int) -> Unit) {
    if (day.items.isEmpty()) return
    val today = java.time.LocalDate.now().dayOfWeek.value
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            if (day.weekday.id == today) "今天" else "周" + "一二三四五六日".substring(day.weekday.id - 1, day.weekday.id),
            style = MaterialTheme.typography.titleMedium,
            color = if (day.weekday.id == today) NagomiColors.accent else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(day.items, key = { it.id }) { subject ->
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
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
