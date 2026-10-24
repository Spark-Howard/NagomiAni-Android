package com.sparkhoward.nagomiani.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sparkhoward.nagomiani.core.history.HistoryStats
import com.sparkhoward.nagomiani.core.store.AppStore.HistoryRecord
import com.sparkhoward.nagomiani.ui.theme.NagomiAsyncImage
import com.sparkhoward.nagomiani.ui.theme.NagomiBadge
import com.sparkhoward.nagomiani.ui.theme.NagomiCard
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiSegmented
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 观看历史 + 统计（番库页进入的二级页）：
 * 历史 = 全部看过的集（可搜索/按在看筛选，按天分组，点行续播）；
 * 统计 = 概览卡 + 近 7 日分布柱状图 + 年度热力图 + 年度看番报告（TOP 番） + 数据备份。
 * 数据全本地（AppStore.player.history.json，旧 resume 一次性回填）。
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onPlay: () -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶栏
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("观看历史", style = MaterialTheme.typography.titleLarge)
        }

        NagomiSegmented(
            options = HistoryViewModel.Tab.entries.toList(),
            selected = state.tab,
            label = { it.label },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { viewModel.setTab(it) }

        when (state.tab) {
            HistoryViewModel.Tab.HISTORY -> HistoryTab(viewModel, onPlay)
            HistoryViewModel.Tab.STATS -> StatsTab(viewModel)
        }
    }
}

// MARK: - 历史列表

@Composable
private fun HistoryTab(viewModel: HistoryViewModel, onPlay: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val list = viewModel.filtered()

    LazyColumn(Modifier.fillMaxSize()) {
        // 搜索（胶囊容器 + 透明 TextField，与搜索页同款）
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp)),
            ) {
                TextField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    placeholder = { Text("搜索看过的番剧…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
            }
        }
        item {
            NagomiSegmented(
                options = HistoryViewModel.Filter.entries.toList(),
                selected = state.filter,
                label = { it.label },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { viewModel.setFilter(it) }
        }

        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NagomiColors.accent)
                }
            }
        } else if (list.isEmpty()) {
            item {
                Text(
                    if (state.query.isBlank() && state.filter == HistoryViewModel.Filter.ALL)
                        "还没有观看记录。去搜索一部番开始看吧～"
                    else "没有匹配的记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        // 按天分组（records 已按 lastWatchedAt 降序，groupBy 保持组内顺序与组出现顺序）
        val zone = ZoneId.systemDefault()
        list.groupBy { dayLabel(it.lastWatchedAt, zone) }.forEach { (label, rows) ->
            item(key = "header_$label") {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (label.startsWith("今天")) NagomiColors.accent else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            items(rows, key = { it.seriesKey + ":" + it.episodeNumber }) { row ->
                HistoryRow(row) { viewModel.playRecord(row, onLaunched = onPlay) }
            }
        }
        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryRow(row: HistoryRecord, onClick: () -> Unit) {
    NagomiCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NagomiAsyncImage(
                model = row.coverURL,
                contentDescription = null,
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    row.showTitle.ifBlank { "未知番剧" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "第 ${row.episodeNumber} 集",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (row.lastDuration > 0) {
                    LinearProgressIndicator(
                        // 看完一律满条：90% 判定置 finished 时 lastPosition≈0.9d，绿条缺角观感奇怪
                        progress = { if (row.finished) 1f else row.progress },
                        color = if (row.finished) NagomiColors.watchedGreen else NagomiColors.accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(999.dp)),
                    )
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (row.finished) {
                        NagomiBadge("已看完", NagomiColors.watchedGreen)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (row.watchSeconds >= 60) {
                        NagomiBadge(HistoryStats.formatDuration(row.watchSeconds), NagomiColors.accent)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        Instant.ofEpochMilli(row.lastWatchedAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm")),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// MARK: - 统计

@Composable
private fun StatsTab(viewModel: HistoryViewModel) {
    val summary = viewModel.summary()
    val week = viewModel.last7Days()
    var year by remember { mutableStateOf(LocalDate.now().year) }
    val years = viewModel.availableYears()
    val report = viewModel.yearReport(year)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        // 概览 2×2
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("总观看时长", HistoryStats.formatDuration(summary.totalSeconds), Modifier.weight(1f))
                StatCard("看过番剧", "${summary.showCount} 部", Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("看完集数", "${summary.finishedEpisodes} 集", Modifier.weight(1f))
                StatCard("观看天数", "${summary.watchedDays} 天", Modifier.weight(1f))
            }
        }

        // 近 7 日分布
        item {
            NagomiCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Insights, null, tint = NagomiColors.accent, modifier = Modifier.size(16.dp))
                        Text("近 7 日分布", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                    WeekBars(week, Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
        }

        // 年度热力图（年份选择器在此，与下方年度报告共用同一个 year 状态）
        item {
            NagomiCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = NagomiColors.accent, modifier = Modifier.size(16.dp))
                        Text("年度热力图", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        Text("$year 年", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (years.size > 1) {
                        NagomiSegmented(
                            options = years,
                            selected = year,
                            label = { "$it" },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { year = it }
                    }
                    val days = viewModel.dailyHeat(year)
                    if (days.isEmpty()) {
                        Text(
                            "$year 年还没有观看记录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        YearHeatmap(days, year, Modifier.fillMaxWidth().padding(top = 12.dp))
                        // 图例：少 → 多（与格子同一份色阶）
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.weight(1f))
                            Text("少", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            heatLevelColors().forEach { color ->
                                Box(
                                    Modifier
                                        .padding(start = 3.dp)
                                        .size(9.dp)
                                        .background(color, RoundedCornerShape(2.dp)),
                                )
                            }
                            Text("多", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp))
                        }
                    }
                }
            }
        }

        // 年度看番报告
        item {
            NagomiCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.History, null, tint = NagomiColors.accent, modifier = Modifier.size(16.dp))
                        Text(
                            "年度看番报告（$year）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 6.dp).weight(1f),
                        )
                    }
                    Row(Modifier.padding(top = 10.dp)) {
                        ReportMetric("${HistoryStats.formatDuration(report.totalSeconds)}", "总时长", Modifier.weight(1f))
                        ReportMetric("${report.watchedDays}", "观看天数", Modifier.weight(1f))
                        ReportMetric("${report.finishedEpisodes}", "看完集数", Modifier.weight(1f))
                    }
                    val maxTop = report.topShows.maxOfOrNull { it.seconds } ?: 0.0
                    if (report.topShows.isEmpty()) {
                        Text(
                            "$year 年还没有观看记录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Text(
                            "最常看的番",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        report.topShows.forEachIndexed { index, show ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("$index", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NagomiColors.accent)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        show.title,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    LinearProgressIndicator(
                                        progress = { if (maxTop > 0) (show.seconds / maxTop).toFloat() else 0f },
                                        color = NagomiColors.accent,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).clip(RoundedCornerShape(999.dp)),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${HistoryStats.formatDuration(show.seconds)} · ${show.episodeCount} 集",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        // 数据备份/恢复
        item {
            val backupMessage by viewModel.backupMessage.collectAsState()
            val backupBusy by viewModel.backupBusy.collectAsState()
            var confirmImport by remember { mutableStateOf(false) }
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let { viewModel.importBackup(it) } }

            NagomiCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Backup, null, tint = NagomiColors.accent, modifier = Modifier.size(16.dp))
                        Text("数据备份", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                    Text(
                        "导出观看历史与设置到文件（换机迁移），或从备份文件恢复。恢复按「新记录胜出」合并，不覆盖本机较新的数据。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.sparkhoward.nagomiani.ui.theme.NagomiPrimaryButton(
                            "导出备份",
                            modifier = Modifier.weight(1f),
                            enabled = !backupBusy,
                        ) { viewModel.exportBackup() }
                        com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton(
                            "导入恢复",
                            modifier = Modifier.weight(1f),
                            enabled = !backupBusy,
                        ) { confirmImport = true }
                    }
                    backupMessage?.let {
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = if (it.startsWith("已")) NagomiColors.watchedGreen else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (confirmImport) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmImport = false },
                    title = { Text("导入备份") },
                    text = { Text("将从所选文件合并观看记录与设置（本机较新的记录不会被覆盖）。继续吗？") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmImport = false
                            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                        }) {
                            Text("选择文件", color = NagomiColors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmImport = false }) { Text("取消") }
                    },
                )
            }
        }
        item {
            Text(
                "统计由本机积累；时长按实际播放秒数计（seek 不计）。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    NagomiCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = NagomiColors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ReportMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 近 7 日柱状图：accentSoft 柱 + 今天 accent 高亮，柱顶分钟数（0 不显示），下方周X标签 */@Composable
private fun WeekBars(slices: List<HistoryStats.DaySlice>, modifier: Modifier = Modifier) {
    val maxSeconds = HistoryStats.barScale(slices)
    Row(modifier.height(130.dp), verticalAlignment = Alignment.Bottom) {
        slices.forEach { slice ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                val minutes = HistoryStats.minutesOf(slice.seconds)
                Text(
                    if (minutes > 0) "${minutes}m" else "",
                    fontSize = 9.sp,
                    color = if (slice.isToday) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val frac = if (maxSeconds > 0) (slice.seconds / maxSeconds).toFloat() else 0f
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .fillMaxHeight(if (frac > 0f) maxOf(frac, 0.04f) else 0.02f)
                            .background(
                                if (slice.isToday) NagomiColors.accent else NagomiColors.accent.copy(alpha = 0.25f),
                                RoundedCornerShape(6.dp),
                            ),
                    )
                }
                Text(
                    if (slice.isToday) "今天" else "周" + "一二三四五六日"[slice.date.dayOfWeek.value - 1],
                    fontSize = 10.sp,
                    color = if (slice.isToday) NagomiColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 热力图五档颜色（图例与格子共用同一份）：0 无记录灰 / 1-4 accent 递进 */
@Composable
private fun heatLevelColors(): List<androidx.compose.ui.graphics.Color> {
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val accent = NagomiColors.accent
    return remember(empty, accent) {
        listOf(empty, accent.copy(alpha = 0.25f), accent.copy(alpha = 0.5f), accent.copy(alpha = 0.75f), accent)
    }
}

/**
 * 年度热力图（GitHub 打卡格风格）：列=周（从对齐周一的年初起），行=周一~周日，
 * fit-width 缩放；色阶 0 无记录 / 1 有记录 0 秒（回填旧数据）/ 2-4 按秒数三分。
 */
@Composable
private fun YearHeatmap(
    days: Map<LocalDate, HistoryStats.DayHeat>,
    year: Int,
    modifier: Modifier = Modifier,
) {
    val maxSeconds = days.values.maxOfOrNull { it.seconds } ?: 0.0
    val levelColors = heatLevelColors()
    // 网格起点 = 1月1日所在周的周一；列数覆盖到 12/31
    val gridStart = remember(year) {
        val first = LocalDate.of(year, 1, 1)
        first.minusDays((first.dayOfWeek.value - 1).toLong())
    }
    val last = remember(year) { LocalDate.of(year, 12, 31) }
    val weeks = remember(year, gridStart) {
        ((last.toEpochDay() - gridStart.toEpochDay()) / 7 + 1).toInt()
    }
    androidx.compose.foundation.Canvas(modifier.height(88.dp)) {
        val gap = 2.dp.toPx()
        val cellW = (size.width - gap * (weeks - 1)) / weeks
        val cellH = (size.height - gap * 6) / 7
        val side = minOf(cellW, cellH)
        val radius = side / 3f
        days.forEach { (date, heat) ->
            val col = ((date.toEpochDay() - gridStart.toEpochDay()) / 7).toInt()
            val row = date.dayOfWeek.value - 1
            if (col < 0 || col >= weeks) return@forEach
            val level = HistoryStats.heatLevel(heat, maxSeconds)
            drawRoundRect(
                color = levelColors[level],
                topLeft = Offset(col * (cellW + gap) + (cellW - side) / 2, row * (cellH + gap) + (cellH - side) / 2),
                size = Size(side, side),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

// MARK: - 工具

/** 分组头：今天 / 昨天 / M月d日 周X（用户本地时区——「我什么时候看的」） */
private fun dayLabel(epochMillis: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val weekday = "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日 $weekday"
    }
}
