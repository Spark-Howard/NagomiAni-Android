package com.sparkhoward.nagomiani.ui.search

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.sparkhoward.nagomiani.ui.theme.minimumTouchTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sparkhoward.nagomiani.core.model.CollectionType
import com.sparkhoward.nagomiani.core.model.Subject
import com.sparkhoward.nagomiani.ui.theme.NagomiAsyncImage
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSectionHeader
import com.sparkhoward.nagomiani.ui.theme.NagomiSkeletonBox

/** 搜索页：搜索框 + 结果列表；无关键词时展示"新番放送（近一周 · 未来一周）"周历 */
@OptIn(ExperimentalMaterial3Api::class)
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
                // Material TextField（光标/选字/输入法行为稳定）；高度下限压扁搜索框，
                // 大字号时随文本撑高不裁切
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "搜索",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier
                    .minimumTouchTarget()
                    .clickable { viewModel.search() }
                    .padding(horizontal = 4.dp),
            )
        }

        if (state.keyword.isEmpty()) {
            // 下拉刷新：内容区下拉重拉放送表（与手动刷新同路，不重新定位翻页）
            PullToRefreshBox(
                isRefreshing = state.weeklyLoading && state.weekly.isNotEmpty(),
                onRefresh = { viewModel.loadWeekly() },
            ) {
                WeeklySection(state, viewModel, onOpenDetail)
            }
        } else {
            SearchResultSection(state, viewModel, onOpenDetail)
        }
    }
}

/**
 * 周历主体：刷新栏常驻屏幕上方不随内容滚走；内容区是「一天一页」的横滑 Pager
 * （手机桌面滑屏式翻页动画），初始页定位「今天」，左右滑看前一天/后一天的更新；
 * 离开今天页后右下角浮现「回到今天」，点击平滑翻回初始页。
 * 手动刷新不重新定位（用户可能正停在别处回看），刷新中在刷新栏下方出细进度条。
 */
@Composable
private fun WeeklySection(state: SearchViewModel.State, viewModel: SearchViewModel, onOpenDetail: (Int) -> Unit) {
    val slots = state.weekly
    // 今天页（初始页/回到今天的目标）；/calendar 异常缺今天时兜底到第 0 页
    val homePage = slots.indexOfFirst { it.isToday }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize()) {
        // 刷新栏：固定在屏幕上方
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
        // 已有数据时的手动刷新：细进度条，不打断浏览
        if (state.weeklyLoading && slots.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = NagomiColors.accent)
        }
        state.weeklyError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }

        if (slots.isEmpty()) {
            if (state.weeklyLoading) {
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NagomiColors.accent)
                }
            }
            return
        }

        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(initialPage = homePage, pageCount = { slots.size })
        var pagerWidthPx by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 日期选择条：点按直达任意一天（翻页手势的零精度替代），选中态跟随翻页
                DayStrip(
                    slots = slots,
                    currentPage = pagerState.currentPage,
                    homePage = homePage,
                    onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { pagerWidthPx = it.width }
                        // 自带手势关闭：横滑全部交给 dayPagerSwipe 的横向优先仲裁
                        .dayPagerSwipe(pagerState, pagerWidthPx, scope),
                    userScrollEnabled = false,
                ) { page ->
                    val slot = slots[page]
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (slot.day.items.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                Text("这一天没有新番更新", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            DaySection(slot, onOpenDetail)
                        }
                    }
                }
            }
            // 回到今天：仅在离开今天页时浮现，点击平滑翻回初始页
            // （全限定调用：外层 ColumnScope 的同名扩展在 Box 内不可用，需走顶层函数）
            androidx.compose.animation.AnimatedVisibility(
                visible = pagerState.currentPage != homePage,
                enter = fadeIn() + scaleIn(initialScale = 0.6f),
                exit = fadeOut() + scaleOut(targetScale = 0.6f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Surface(
                    onClick = { scope.launch { pagerState.animateScrollToPage(homePage) } },
                    shape = RoundedCornerShape(999.dp),
                    color = NagomiColors.accent,
                    contentColor = Color.White,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("回到今天", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}


/** 日期芯片的紧凑文案：今天/昨天/明天直呼，「10/23 周五」取自完整标签后半段 */
private fun chipLabel(slot: com.sparkhoward.nagomiani.core.online.WeeklyCalendar.Slot): String =
    when {
        slot.isToday -> "今天"
        slot.label.startsWith("昨天") -> "昨天"
        slot.label.startsWith("明天") -> "明天"
        else -> slot.label.substringAfter(" · ")
    }

@Composable
private fun DayStrip(
    slots: List<com.sparkhoward.nagomiani.core.online.WeeklyCalendar.Slot>,
    currentPage: Int,
    homePage: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentPage) {
        if (currentPage in slots.indices) listState.animateScrollToItem(currentPage) // 选中芯片滚入可视区
    }
    LazyRow(
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        items(slots.size) { index ->
            val slot = slots[index]
            val selected = index == currentPage
            val bg = when {
                selected -> NagomiColors.accent
                index == homePage -> NagomiColors.accentSoft
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
            val fg = when {
                selected -> Color.White
                index == homePage -> NagomiColors.accent
                slot.isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onBackground
            }
            Text(
                chipLabel(slot),
                fontSize = 12.sp,
                fontWeight = if (selected || index == homePage) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 横向优先的翻页手势仲裁（配合 userScrollEnabled=false 的 Pager）。
 * 默认轴向仲裁是「先越过触控斜率者赢」：横滑带一点弧度（纵向先过斜率）就被页内
 * 纵向滚动抢走，翻页失败。这里改为累计位移过斜率后判定——横向位移不小于纵向即认领：
 * 在 Initial pass 消费指针事件（内层 verticalScroll 看到已消费、不再滚动），用
 * dispatchRawDelta 让页面跟手，抬手按位移比例与甩动速度翻页；判为纵向则完全不干预。
 */
private fun Modifier.dayPagerSwipe(
    pagerState: androidx.compose.foundation.pager.PagerState,
    pageWidthPx: Int,
    scope: CoroutineScope,
): Modifier = pointerInput(pagerState, pageWidthPx) {
    val density = density
    val flingPx = 50.dp.toPx() // 与系统 minimumFlingVelocity 同量级（50dp/s）
    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        tracker.resetTracking()
        var totalX = 0f
        var totalY = 0f
        var claimed = false
        var swipeTotal = 0f // 认领后的横向累计（>0 朝后一页）
        val slop = viewConfiguration.touchSlop * 1.5f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!claimed) {
                if (event.changes.any { it.isConsumed }) break // 被弹窗等接管，退出
                totalX += change.positionChange().x
                totalY += change.positionChange().y
                if (!change.pressed) break // 原地点按：不干预，交给条目点击
                if (abs(totalX) < slop && abs(totalY) < slop) continue
                // 横向宽容度：横向达到纵向 75% 就认领翻页——默认「先过斜率者赢」
                // 会让带弧度的横滑（纵向先起手）总被纵向滚动抢走
                if (abs(totalX) < abs(totalY) * 0.75f) break
                claimed = true
            }
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            val dx = change.positionChange().x
            swipeTotal += -dx
            pagerState.dispatchRawDelta(-dx)
            if (!change.pressed) {
                val width = pageWidthPx.coerceAtLeast(1)
                val progress = swipeTotal / width
                val vx = tracker.calculateVelocity().x
                val target = when {
                    progress > 0.35f || (vx < -flingPx && progress > -0.4f) -> pagerState.currentPage + 1
                    progress < -0.35f || (vx > flingPx && progress < 0.4f) -> pagerState.currentPage - 1
                    else -> pagerState.currentPage
                }.coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                scope.launch { pagerState.animateScrollToPage(target) }
                break
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
        // 3 列海报网格：页面内部不做横向滚动——横向手势整体让给 Pager 翻页，
        // 否则内层 LazyRow 会吃掉横滑，只有缝隙能切换上/下一天
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val rows = slot.day.items.chunked(3)
            rows.forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { subject ->
                        SubjectCard(subject, onOpenDetail, Modifier.weight(1f))
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) } // 末行补位保持卡片等宽
                }
            }
        }
    }
}

@Composable
private fun SubjectCard(subject: Subject, onOpenDetail: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable { onOpenDetail(subject.id) }) {
        NagomiAsyncImage(
            model = subject.images?.bestURL(),
            contentDescription = subject.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            subject.displayName,
            fontSize = 12.sp,
            // 固定占两行：短标题与长标题的卡片等高，网格内行高不跳变（周历抖动根因）
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 高度下限占位：无评分/集数的条目同样占位保证整卡等高；大字号随文本撑高不裁切
            modifier = Modifier.heightIn(min = 16.dp),
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
        // 骨架屏：缩略图 + 双行文本条的呼吸占位，替代整页转圈
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(9) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NagomiSkeletonBox(Modifier.width(52.dp).height(72.dp), RoundedCornerShape(6.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        NagomiSkeletonBox(Modifier.fillMaxWidth(0.6f).height(15.dp))
                        NagomiSkeletonBox(Modifier.fillMaxWidth(0.4f).height(11.dp).padding(top = 4.dp))
                        NagomiSkeletonBox(Modifier.fillMaxWidth(0.3f).height(11.dp).padding(top = 4.dp))
                    }
                }
            }
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
        if (state.canLoadMore) {
            item(key = "loadMore") {
                // 滚到底部自动加载下一页（loadMore 内部去重防死循环），也保留点击手动触发
                LaunchedEffect(state.results.size) { viewModel.loadMore() }
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.size(18.dp))
                    } else {
                        Text(
                            "加载更多",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .minimumTouchTarget()
                                .clickable { viewModel.loadMore() },
                        )
                    }
                }
            }
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
        NagomiAsyncImage(
            model = subject.images?.bestURL(),
            contentDescription = null,
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
