package com.sparkhoward.nagomiani.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparkhoward.nagomiani.AppGraph
import com.sparkhoward.nagomiani.core.history.HistoryStats
import com.sparkhoward.nagomiani.core.store.AppStore.HistoryRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 观看历史 + 统计：单页双分页（历史列表 / 统计图表），数据全本地（HistoryRecord） */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store get() = AppGraph.store

    enum class Tab(val label: String) { HISTORY("历史"), STATS("统计") }
    enum class Filter(val label: String) { ALL("全部"), WATCHING("在看"), FINISHED("看完") }

    data class State(
        val tab: Tab = Tab.HISTORY,
        val query: String = "",
        val filter: Filter = Filter.ALL,
        val loading: Boolean = true,
        val message: String? = null,
    )

    val state = MutableStateFlow(State())
    val records = MutableStateFlow<List<HistoryRecord>>(emptyList())

    init {
        viewModelScope.launch {
            // 升级用户一次性回填旧 resume 记录（列表不空，时长不虚构）
            runCatching { store.seedHistoryIfNeeded() }
            store.historyAll.collect { list ->
                records.value = list.values.sortedByDescending { it.lastWatchedAt }
                state.value = state.value.copy(loading = false)
            }
        }
        viewModelScope.launch {
            runCatching { store.fillHistoryCovers() } // 新建记录时番还没注册的封面在此补齐
        }
    }

    fun setTab(tab: Tab) { state.value = state.value.copy(tab = tab) }
    fun setQuery(value: String) { state.value = state.value.copy(query = value) }
    fun setFilter(filter: Filter) { state.value = state.value.copy(filter = filter) }

    /** 搜索+筛选后的历史列表（UI 按天分组渲染） */
    fun filtered(): List<HistoryRecord> {
        val s = state.value
        return records.value.filter { r ->
            when (s.filter) {
                Filter.ALL -> true
                Filter.WATCHING -> !r.finished
                Filter.FINISHED -> r.finished
            } && (s.query.isBlank() || r.showTitle.contains(s.query.trim(), ignoreCase = true))
        }
    }

    /** 点历史行：在线片源功能已整体移除（合规版），历史仅作本地观看记录展示 */
    fun playRecord(row: HistoryRecord, onLaunched: () -> Unit) {
        showMessage("在线片源功能已下线，历史仅作观看记录")
    }

    private fun showMessage(text: String) {
        state.value = state.value.copy(message = text)
        viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            if (state.value.message == text) state.value = state.value.copy(message = null)
        }
    }

    fun summary() = HistoryStats.summarize(records.value)
    fun last7Days() = HistoryStats.last7Days(records.value, java.time.LocalDate.now())
    fun yearReport(year: Int) = HistoryStats.yearReport(records.value, year)
    fun availableYears() = HistoryStats.availableYears(records.value, currentYear = java.time.LocalDate.now().year)
    fun dailyHeat(year: Int) = HistoryStats.dailySeconds(records.value, year)

    // MARK: - 数据备份/恢复

    val backupMessage = MutableStateFlow<String?>(null)
    val backupBusy = MutableStateFlow(false)

    private fun showBackupMessage(text: String) {
        backupMessage.value = text
        viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            if (backupMessage.value == text) backupMessage.value = null
        }
    }

    /** 导出：生成 JSON 并拉起系统分享面板（文件经 FileProvider 共享） */
    fun exportBackup() {
        if (backupBusy.value) return
        backupBusy.value = true
        viewModelScope.launch {
            try {
                showBackupMessage(com.sparkhoward.nagomiani.core.backup.Backup.export(getApplication()))
            } catch (e: Exception) {
                showBackupMessage("导出失败：${e.message ?: "未知错误"}")
            }
            backupBusy.value = false
        }
    }

    /** 导入：解析用户所选文件并按「新记录胜出」合并 */
    fun importBackup(uri: android.net.Uri) {
        if (backupBusy.value) return
        backupBusy.value = true
        viewModelScope.launch {
            try {
                showBackupMessage(com.sparkhoward.nagomiani.core.backup.Backup.import(getApplication(), uri))
            } catch (e: Exception) {
                showBackupMessage("导入失败：${e.message ?: "未知错误"}")
            }
            backupBusy.value = false
        }
    }
}
