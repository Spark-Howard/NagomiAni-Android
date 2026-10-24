package com.sparkhoward.nagomiani.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nagomi")

/**
 * 应用持久化基座（mac 版 UserDefaults 各键 + resume.json 的 Android 对应物）。
 * 绑定表/设置/片源列表走 DataStore；续播记录与待同步队列也放这里（JSON 编码）。
 */
class AppStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private object Keys {
        val autoplayNext = booleanPreferencesKey("player.autoplayNext")
        val playerSpeed = floatPreferencesKey("player.speed")                 // 跨集/重启记忆的倍速
        val danmakuEnabled = booleanPreferencesKey("player.danmakuEnabled")
        val danmakuFontSize = doublePreferencesKey("danmaku.fontSize")
        val danmakuFontScaled = booleanPreferencesKey("danmaku.fontSizeScaled")
        val danmakuColorMode = stringPreferencesKey("danmaku.colorMode")
        val danmakuCustomColor = intPreferencesKey("danmaku.customColor")
        val danmakuOpacity = doublePreferencesKey("danmaku.opacity")
        val maccmsSites = stringSetPreferencesKey("online.maccms.sites")
        val siteHealth = stringPreferencesKey("online.site.health.json") // {providerID: SiteHealthEntry}
        val networkProxy = stringPreferencesKey("network.proxy")                  // "host:port" 或空
        val skippedUpdate = stringPreferencesKey("update.skippedVersion")         // 「下次再说」的 tag
        val bindings = stringPreferencesKey("bangumi.bindings.json")          // {seriesKey: subjectID}
        val boundNames = stringPreferencesKey("bangumi.boundNames.json")
        val cloudLibrary = stringPreferencesKey("online.library.entries.json") // [CloudEntry]
        val knownShows = stringPreferencesKey("online.known.shows.json")
        val pendingMarks = stringPreferencesKey("bangumi.pendingWatchedMarks.json")
        val resume = stringPreferencesKey("player.resume.json")               // {resumeKey: ResumeEntry}
        val updateNotify = booleanPreferencesKey("notify.episodeUpdates")
        val lastNotified = stringPreferencesKey("notify.episode.lastNotified.json") // {seriesKey: 已通知时的总集数}
        val history = stringPreferencesKey("player.history.json")             // {resumeKey: HistoryRecord}
        val historySeeded = booleanPreferencesKey("player.history.seeded")
        val dailyAiringEnabled = booleanPreferencesKey("notify.dailyAiring.enabled")
        val dailyAiringLastDate = stringPreferencesKey("notify.dailyAiring.lastDate") // 已通知的 JST 日期串
    }

    // MARK: - 播放器设置

    val autoplayNext: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoplayNext] ?: true }
    /** 倍速记忆（长按 3× 快进为临时变速，不写此键） */
    val playerSpeed: Flow<Float> = context.dataStore.data.map { it[Keys.playerSpeed] ?: 1.0f }
    val danmakuEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.danmakuEnabled] ?: true }
    /** 弹幕字号（sp）。默认 14；danmakuFontScaled 标记旧值（18/22/28 预设）是否已迁移到新刻度 */
    val danmakuFontSize: Flow<Double> = context.dataStore.data.map { it[Keys.danmakuFontSize] ?: 14.0 }
    val danmakuFontScaled: Flow<Boolean> = context.dataStore.data.map { it[Keys.danmakuFontScaled] ?: false }
    val danmakuColorMode: Flow<String> = context.dataStore.data.map { it[Keys.danmakuColorMode] ?: "original" }
    val danmakuCustomColor: Flow<Int> = context.dataStore.data.map { it[Keys.danmakuCustomColor] ?: 0xEC6A88.toInt() }
    val danmakuOpacity: Flow<Double> = context.dataStore.data.map { it[Keys.danmakuOpacity] ?: 1.0 }

    suspend fun setAutoplayNext(value: Boolean) = edit { it[Keys.autoplayNext] = value }
    suspend fun setPlayerSpeed(value: Float) = edit { it[Keys.playerSpeed] = value }
    suspend fun setDanmakuEnabled(value: Boolean) = edit { it[Keys.danmakuEnabled] = value }
    suspend fun setDanmakuFontSize(value: Double) = edit { it[Keys.danmakuFontSize] = value }
    suspend fun markDanmakuFontScaled() = edit { it[Keys.danmakuFontScaled] = true }
    suspend fun setDanmakuColorMode(value: String) = edit { it[Keys.danmakuColorMode] = value }
    suspend fun setDanmakuCustomColor(value: Int) = edit { it[Keys.danmakuCustomColor] = value }
    suspend fun setDanmakuOpacity(value: Double) = edit { it[Keys.danmakuOpacity] = value }

    // MARK: - 片源站点（内置 4 站不可删 + 用户自加）

    val userSites: Flow<Set<String>> = context.dataStore.data.map { it[Keys.maccmsSites] ?: emptySet() }

    suspend fun addUserSite(url: String) = edit { it[Keys.maccmsSites] = (it[Keys.maccmsSites] ?: emptySet()) + url }
    suspend fun removeUserSite(url: String) = edit { it[Keys.maccmsSites] = (it[Keys.maccmsSites] ?: emptySet()) - url }

    // MARK: - 片源健康（providerID=host → 最近一次测速；番库面板与详情页徽章共用）

    /** ok=false 时 latencyMs 无意义；testedAt 供将来判断陈旧度 */
    @Serializable
    data class SiteHealthEntry(val ok: Boolean, val latencyMs: Long, val testedAt: Long)

    val siteHealthMap: Flow<Map<String, SiteHealthEntry>> = context.dataStore.data.map { prefs ->
        prefs[Keys.siteHealth]?.let { runCatching { json.decodeFromString<Map<String, SiteHealthEntry>>(it) }.getOrNull() } ?: emptyMap()
    }

    suspend fun siteHealthOnce(): Map<String, SiteHealthEntry> = siteHealthMap.first()

    /** 合并写入（只更新本次探测到的站，保留其他站的旧记录） */
    suspend fun replaceSiteHealth(map: Map<String, SiteHealthEntry>) = edit { prefs ->
        if (map.isEmpty()) return@edit
        val merged = HashMap(prefs[Keys.siteHealth]?.let { runCatching { json.decodeFromString<Map<String, SiteHealthEntry>>(it) }.getOrNull() } ?: emptyMap())
        merged.putAll(map)
        prefs[Keys.siteHealth] = json.encodeToString(merged)
    }

    // MARK: - 应用内网络代理（bgm.tv 整域 DNS 污染时的出口；空串 = 直连）

    /** 原始串（"host:port" 或空），供 UI 展示 */
    val networkProxyRaw: Flow<String> = context.dataStore.data.map { it[Keys.networkProxy] ?: "" }

    suspend fun setNetworkProxy(raw: String) = edit { it[Keys.networkProxy] = raw.trim() }

    // MARK: - 应用内更新（「下次再说」记住的 tag，同版本不再打扰，出现更新版本重新提示）

    val skippedUpdateVersion: Flow<String> = context.dataStore.data.map { it[Keys.skippedUpdate] ?: "" }
    suspend fun skippedUpdateVersionOnce(): String = skippedUpdateVersion.first()
    suspend fun setSkippedUpdateVersion(value: String) = edit { it[Keys.skippedUpdate] = value }

    // MARK: - Bangumi 绑定表（seriesKey → subjectID，本地/云端/已看同步共用）

    suspend fun bindings(): Map<String, Int> =
        context.dataStore.data.first()[Keys.bindings]?.let { decodeMap(it) } ?: emptyMap()

    suspend fun bind(seriesKey: String, subjectID: Int, displayName: String?) = edit { prefs ->
        val ids = HashMap(prefs[Keys.bindings]?.let { decodeMap(it) } ?: emptyMap())
        ids[seriesKey] = subjectID
        prefs[Keys.bindings] = encodeMap(ids)
        val names = HashMap(prefs[Keys.boundNames]?.let { decodeStringMap(it) } ?: emptyMap())
        if (displayName != null) names[seriesKey] = displayName
        prefs[Keys.boundNames] = encodeStringMap(names)
    }

    suspend fun unbind(seriesKey: String) = edit { prefs ->
        val ids = HashMap(prefs[Keys.bindings]?.let { decodeMap(it) } ?: emptyMap())
        ids.remove(seriesKey)
        prefs[Keys.bindings] = encodeMap(ids)
        val names = HashMap(prefs[Keys.boundNames]?.let { decodeStringMap(it) } ?: emptyMap())
        names.remove(seriesKey)
        prefs[Keys.boundNames] = encodeStringMap(names)
    }

    suspend fun bindingSubjectID(seriesKey: String): Int? = bindings()[seriesKey]

    suspend fun boundName(seriesKey: String): String? =
        context.dataStore.data.first()[Keys.boundNames]?.let { decodeStringMap(it) }?.get(seriesKey)

    // MARK: - 云端番库（收藏的片源条目）

    @Serializable
    data class CloudEntry(
        val providerID: String,
        val showID: String,
        val title: String,
        val subtitle: String? = null,
        val coverURL: String? = null,
        val lastSeenEpisodeCount: Int = 0,
    ) {
        val seriesKey: String get() = "online:$providerID:$showID"
        fun asShow() = OnlineShowLite(providerID, showID, title, subtitle, coverURL)
    }

    /** 轻量 show（避免 store 依赖 ui/model 旧对象） */
    data class OnlineShowLite(
        val providerID: String, val showID: String, val title: String,
        val subtitle: String?, val coverURL: String?,
    )

    val cloudLibrary: Flow<List<CloudEntry>> = context.dataStore.data.map { prefs ->
        prefs[Keys.cloudLibrary]?.let { runCatching { json.decodeFromString<List<CloudEntry>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun cloudLibraryOnce(): List<CloudEntry> = cloudLibrary.first()

    suspend fun upsertCloudEntry(entry: CloudEntry) = edit { prefs ->
        val list = (prefs[Keys.cloudLibrary]?.let { runCatching { json.decodeFromString<List<CloudEntry>>(it) }.getOrNull() } ?: emptyList())
            .filterNot { it.seriesKey == entry.seriesKey } + entry
        prefs[Keys.cloudLibrary] = json.encodeToString(list)
    }

    suspend fun removeCloudEntry(seriesKey: String) = edit { prefs ->
        val list = (prefs[Keys.cloudLibrary]?.let { runCatching { json.decodeFromString<List<CloudEntry>>(it) }.getOrNull() } ?: emptyList())
            .filterNot { it.seriesKey == seriesKey }
        prefs[Keys.cloudLibrary] = json.encodeToString(list)
    }

    suspend fun cloudEntry(seriesKey: String): CloudEntry? = cloudLibraryOnce().firstOrNull { it.seriesKey == seriesKey }

    // MARK: - 已看过的番注册表（继续观看卡片解析标题/封面用）

    @Serializable
    data class KnownShow(
        val providerID: String, val showID: String, val title: String,
        val subtitle: String? = null, val coverURL: String? = null,
    ) {
        val seriesKey: String get() = "online:$providerID:$showID"
    }

    suspend fun knownShows(): List<KnownShow> =
        context.dataStore.data.first()[Keys.knownShows]
            ?.let { runCatching { json.decodeFromString<List<KnownShow>>(it) }.getOrNull() } ?: emptyList()

    suspend fun rememberShow(show: KnownShow) = edit { prefs ->
        val list = (prefs[Keys.knownShows]?.let { runCatching { json.decodeFromString<List<KnownShow>>(it) }.getOrNull() } ?: emptyList())
            .filterNot { it.seriesKey == show.seriesKey } + show
        prefs[Keys.knownShows] = json.encodeToString(list.takeLast(200))
    }

    suspend fun knownShow(seriesKey: String): KnownShow? = knownShows().firstOrNull { it.seriesKey == seriesKey }

    // MARK: - 断点续播（mac 版 resume.json 的 DataStore 版，键=resumeKey）

    @Serializable
    data class ResumeEntry(val position: Double, val duration: Double, val updatedAt: Long) {
        val isResumable: Boolean
            get() = position >= 15.0 && (duration <= 0 || position <= duration - 30.0)
    }

    val resumeAll: Flow<Map<String, ResumeEntry>> = context.dataStore.data.map { prefs ->
        prefs[Keys.resume]?.let { runCatching { decodeResume(it) }.getOrNull() } ?: emptyMap()
    }

    suspend fun resumeEntry(key: String): ResumeEntry? =
        resumeAll.first()[key]

    suspend fun updateResume(key: String, position: Double, duration: Double) = edit { prefs ->
        val map = HashMap(prefs[Keys.resume]?.let { runCatching { decodeResume(it) }.getOrNull() } ?: emptyMap())
        map[key] = ResumeEntry(position, duration, System.currentTimeMillis())
        // 容量上限 500，超出淘汰最旧（与 mac 版一致）
        val trimmed = map.entries.sortedByDescending { it.value.updatedAt }.take(500).associate { it.toPair() }
        prefs[Keys.resume] = encodeResume(trimmed)
    }

    suspend fun removeResume(key: String) = edit { prefs ->
        val map = (prefs[Keys.resume]?.let { runCatching { decodeResume(it) }.getOrNull() } ?: emptyMap()).toMutableMap()
        map.remove(key)
        prefs[Keys.resume] = encodeResume(map)
    }

    // MARK: - 观看历史（每集一条，看完不删——与 resume 的"播完即删"互补；统计与历史页共用）

    /**
     * watchSeconds = 累计真实观看秒数（回填的旧记录为 0，UI 显示进度条代替）；
     * finished = 本地口径看完（90% 判定或 EOF，不依赖 Bangumi 登录）
     */
    @Serializable
    data class HistoryRecord(
        val seriesKey: String,
        val providerID: String,
        val showID: String,
        val showTitle: String,
        val coverURL: String? = null,
        val episodeNumber: Int,
        val episodeTitle: String? = null,
        val lastPosition: Double = 0.0,
        val lastDuration: Double = 0.0,
        val watchSeconds: Double = 0.0,
        val finished: Boolean = false,
        val firstWatchedAt: Long,
        val lastWatchedAt: Long,
    ) {
        val progress: Float
            get() = if (lastDuration > 0) (lastPosition / lastDuration).toFloat().coerceIn(0f, 1f) else 0f
    }

    val historyAll: Flow<Map<String, HistoryRecord>> = context.dataStore.data.map { prefs ->
        prefs[Keys.history]?.let { runCatching { json.decodeFromString<Map<String, HistoryRecord>>(it) }.getOrNull() } ?: emptyMap()
    }

    suspend fun historyOnce(): Map<String, HistoryRecord> = historyAll.first()

    /** 播放器 5s 节流调用：upsert 合并观看增量；新建记录时从 knownShows/cloudLibrary 补封面。
     *  先读后写——不能在 edit{} 块内再读同一 DataStore（持锁重入有死锁风险） */
    suspend fun recordWatchProgress(
        resumeKey: String,
        seriesKey: String,
        showTitle: String,
        episodeNumber: Int,
        episodeTitle: String?,
        deltaSeconds: Double,
        position: Double,
        duration: Double,
        watchedAt: Long = System.currentTimeMillis(),
    ) {
        val old = historyOnce()[resumeKey]
        val cover = old?.coverURL ?: runCatching { knownShow(seriesKey)?.coverURL ?: cloudEntry(seriesKey)?.coverURL }.getOrNull()
        val record = HistoryRecord(
            seriesKey = seriesKey,
            providerID = seriesKey.removePrefix("online:").substringBefore(':'),
            showID = seriesKey.removePrefix("online:").substringAfter(':'),
            showTitle = showTitle.ifBlank { old?.showTitle ?: "" },
            coverURL = cover,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle ?: old?.episodeTitle,
            lastPosition = position,
            lastDuration = duration,
            watchSeconds = (old?.watchSeconds ?: 0.0) + deltaSeconds,
            finished = old?.finished ?: false,
            firstWatchedAt = old?.firstWatchedAt ?: watchedAt,
            lastWatchedAt = watchedAt,
        )
        edit { prefs ->
            val map = HashMap(prefs[Keys.history]?.let { runCatching { json.decodeFromString<Map<String, HistoryRecord>>(it) }.getOrNull() } ?: emptyMap())
            map[resumeKey] = record
            // 容量上限 1000，超出淘汰最旧（参照 resume 500 先例）
            val trimmed = if (map.size > HISTORY_MAX) {
                map.entries.sortedByDescending { it.value.lastWatchedAt }.take(HISTORY_MAX).associate { it.toPair() }
            } else map
            prefs[Keys.history] = json.encodeToString(trimmed)
        }
    }

    /** 补封面：新建记录当时 knownShows/cloudLibrary 可能还没注册，历史页打开时异步补一次 */
    suspend fun fillHistoryCovers() {
        val records = historyOnce()
        val missing = records.values.filter { it.coverURL == null }.map { it.seriesKey }.distinct()
        for (seriesKey in missing) {
            val cover = runCatching { knownShow(seriesKey)?.coverURL ?: cloudEntry(seriesKey)?.coverURL }.getOrNull() ?: continue
            edit { prefs ->
                val map = prefs[Keys.history]?.let { runCatching { json.decodeFromString<Map<String, HistoryRecord>>(it) }.getOrNull() } ?: return@edit
                val updated = map.mapValues { (_, r) -> if (r.seriesKey == seriesKey && r.coverURL == null) r.copy(coverURL = cover) else r }
                prefs[Keys.history] = json.encodeToString(updated)
            }
        }
    }

    suspend fun markHistoryFinished(resumeKey: String) = edit { prefs ->
        val map = HashMap(prefs[Keys.history]?.let { runCatching { json.decodeFromString<Map<String, HistoryRecord>>(it) }.getOrNull() } ?: return@edit)
        val old = map[resumeKey] ?: return@edit
        map[resumeKey] = old.copy(finished = true)
        prefs[Keys.history] = json.encodeToString(map)
    }

    /** 备份导入：按 lastWatchedAt 新者胜合并（不覆盖本机较新的记录），套用容量上限后写回 */
    suspend fun mergeImportedHistory(imported: Map<String, HistoryRecord>): Int {
        val merged = HashMap(historyOnce())
        for ((key, record) in imported) {
            val current = merged[key]
            if (current == null || record.lastWatchedAt > current.lastWatchedAt) merged[key] = record
        }
        val trimmed = if (merged.size > HISTORY_MAX) {
            merged.entries.sortedByDescending { it.value.lastWatchedAt }.take(HISTORY_MAX).associate { it.toPair() }
        } else merged
        edit { it[Keys.history] = json.encodeToString(trimmed) }
        return trimmed.size
    }

    /** 一次性回填：把升级前遗留的 resume 记录转成历史（时长不虚构、置 0，封面尽力补）。
     *  resume 的"播完即删"意味着完看的集不在其中——回填只为历史列表不空，统计从接入版本起积累 */
    suspend fun seedHistoryIfNeeded() {
        if (context.dataStore.data.first()[Keys.historySeeded] == true) return
        val resume = context.dataStore.data.first()[Keys.resume]?.let { runCatching { decodeResume(it) }.getOrNull() } ?: emptyMap()
        val existing = historyOnce()
        val seeded = HashMap(existing)
        for ((key, entry) in resume) {
            if (!key.startsWith("online:") || existing.containsKey(key)) continue
            val seriesKey = key.substringBeforeLast(':')
            val number = key.substringAfterLast(':').toIntOrNull() ?: continue
            val known = runCatching { knownShow(seriesKey) }.getOrNull()
            val cloud = runCatching { cloudEntry(seriesKey) }.getOrNull()
            seeded[key] = HistoryRecord(
                seriesKey = seriesKey,
                providerID = seriesKey.removePrefix("online:").substringBefore(':'),
                showID = seriesKey.removePrefix("online:").substringAfter(':'),
                showTitle = known?.title ?: cloud?.title ?: "",
                coverURL = known?.coverURL ?: cloud?.coverURL,
                episodeNumber = number,
                lastPosition = entry.position,
                lastDuration = entry.duration,
                watchSeconds = 0.0,
                firstWatchedAt = entry.updatedAt,
                lastWatchedAt = entry.updatedAt,
            )
        }
        edit { prefs ->
            prefs[Keys.history] = json.encodeToString(seeded)
            prefs[Keys.historySeeded] = true
        }
    }

    // MARK: - 离线"看过"待同步队列（容量 200，同 subject+episode 去重）

    @Serializable
    data class PendingMark(val subjectID: Int, val episodeNumber: Int, val queuedAt: Long)

    val pendingMarks: Flow<List<PendingMark>> = context.dataStore.data.map { prefs ->
        prefs[Keys.pendingMarks]?.let { runCatching { json.decodeFromString<List<PendingMark>>(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun enqueuePendingMark(subjectID: Int, episodeNumber: Int) = edit { prefs ->
        val list = (prefs[Keys.pendingMarks]?.let { runCatching { json.decodeFromString<List<PendingMark>>(it) }.getOrNull() } ?: emptyList())
        if (list.any { it.subjectID == subjectID && it.episodeNumber == episodeNumber }) return@edit
        val appended = list + PendingMark(subjectID, episodeNumber, System.currentTimeMillis())
        prefs[Keys.pendingMarks] = json.encodeToString(appended.takeLast(200))
    }

    suspend fun replacePendingMarks(list: List<PendingMark>) = edit { it[Keys.pendingMarks] = json.encodeToString(list) }

    // MARK: - 追番更新提醒

    /** 番库有新集时发系统通知 */
    val updateNotifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.updateNotify] ?: true }
    suspend fun setUpdateNotifyEnabled(value: Boolean) = edit { it[Keys.updateNotify] = value }

    /** 已通知基线（seriesKey → 通知时的总集数）：与「已看基线」（lastSeenEpisodeCount）独立——
     *  同一批新集只提醒一次；用户在番库展开才推进已看基线，通知不推进它 */
    val lastNotifiedCounts: Flow<Map<String, Int>> = context.dataStore.data.map { prefs ->
        prefs[Keys.lastNotified]?.let { runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull() } ?: emptyMap()
    }

    suspend fun lastNotifiedCountsOnce(): Map<String, Int> = lastNotifiedCounts.first()

    suspend fun recordNotifiedCounts(counts: Map<String, Int>) = edit { prefs ->
        if (counts.isEmpty()) return@edit
        val merged = HashMap(prefs[Keys.lastNotified]?.let { runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull() } ?: emptyMap())
        merged.putAll(counts)
        prefs[Keys.lastNotified] = json.encodeToString(merged)
    }

    // MARK: - 今日更新提醒（每日检查放送表 × 在看收藏）

    val dailyAiringEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.dailyAiringEnabled] ?: true }
    suspend fun setDailyAiringEnabled(value: Boolean) = edit { it[Keys.dailyAiringEnabled] = value }

    /** 已通知基线（JST 日期串）：同一天只提醒一次 */
    suspend fun dailyAiringLastDateOnce(): String =
        context.dataStore.data.first()[Keys.dailyAiringLastDate] ?: ""

    suspend fun setDailyAiringLastDate(dateKey: String) = edit { it[Keys.dailyAiringLastDate] = dateKey }

    // MARK: - 私有工具

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        const val HISTORY_MAX = 1000
    }

    private fun decodeMap(raw: String): Map<String, Int> =
        runCatching { json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())

    private fun encodeMap(map: Map<String, Int>): String = json.encodeToString(map)

    private fun decodeStringMap(raw: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

    private fun encodeStringMap(map: Map<String, String>): String = json.encodeToString(map)

    private fun decodeResume(raw: String): Map<String, ResumeEntry> =
        json.decodeFromString<Map<String, ResumeEntry>>(raw)

    private fun encodeResume(map: Map<String, ResumeEntry>): String = json.encodeToString(map)
}
