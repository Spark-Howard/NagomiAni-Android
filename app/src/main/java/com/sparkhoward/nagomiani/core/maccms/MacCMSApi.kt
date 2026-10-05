package com.sparkhoward.nagomiani.core.maccms

import com.sparkhoward.nagomiani.core.Http
import com.sparkhoward.nagomiani.core.matching.MediaMatching
import com.sparkhoward.nagomiani.core.model.OnlineEpisode
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * MacCMS（苹果 CMS V10）采集站客户端，与 mac 版 MacCMSProvider 端点/解析完全同构。
 * 端点：{site}/api.php/provide/vod/?ac=list|detail
 * 剧集文法：vod_play_url 组间 "$$$"、集间 "#"、每集 "name$url"
 */
class MacCMSApi(private val siteURL: String) {

    /** providerID = 站点主机名（与 mac 版一致，作为 seriesKey 组成部分跨会话稳定） */
    val providerID: String = runCatching { java.net.URI(siteURL).host ?: siteURL }.getOrDefault(siteURL)

    /**
     * 实际请求的 API base：内置站是裸域名（如 https://cj.lziapi.com），
     * 必须补全 /api.php/provide/vod/；用户自加站点经 normalizeAPIBase 已带路径（幂等）。
     */
    private val apiBase: String = MacCMSApi.normalizeAPIBase(siteURL) ?: siteURL.trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        /** 用户粘贴站点主页/任意路径/完整 API 地址 → 规范化 API base（与 mac 版 normalizeAPIBase 一致） */
        fun normalizeAPIBase(input: String): String? {
            var text = input.trim()
            if (text.isEmpty()) return null
            if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
            val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            val path = uri.path.orEmpty()
            return when {
                path.contains("provide") -> text.trimEnd('/')
                path.isEmpty() || path == "/" -> "${text.trimEnd('/')}/api.php/provide/vod/"
                else -> "${text.trimEnd('/')}/api.php/provide/vod/"
            }
        }

        /** 内置片源站（不可移除，与 mac 版一致） */
        val builtinSites = listOf(
            "量子资源" to "https://cj.lziapi.com",
            "极速资源" to "https://jszyapi.com",
            "爱坤资源" to "https://ikunzyapi.com",
            "暴风资源" to "https://bfzyapi.com",
        )

        private val animeCategoryKeywords = listOf("动漫", "动画", "番剧", "剧场版")
        private val junkTitleKeywords = listOf("解说", "速看", "几分钟", "盘点")
    }

    // MARK: - 公开操作

    /** 最新/分类动漫列表（取分类树 → 动漫类目首页），供"片源浏览"与周历聚合兜底 */
    suspend fun listShows(): List<OnlineShow> = withContext(Dispatchers.IO) {
        val root = fetch("ac=list&pg=1") ?: return@withContext emptyList()
        val classes = root["class"]?.jsonArray ?: return@withContext emptyList()
        val animeTypeIDs = classes.mapNotNull { entry ->
            val obj = entry.jsonObject
            val name = obj.stringField("type_name") ?: return@mapNotNull null
            val parent = classes.firstOrNull { p ->
                p.jsonObject.stringField("type_id") == obj.stringField("type_pid")
            }?.jsonObject?.stringField("type_name")
            if (animeCategoryKeywords.any { kw -> name.contains(kw) || parent?.contains(kw) == true }) {
                obj.stringField("type_id")
            } else null
        }.take(4)

        val shows = ArrayList<OnlineShow>()
        for (typeID in animeTypeIDs) {
            val page = fetch("ac=list&t=$typeID&pg=1") ?: continue
            shows += decodeList(page)
        }
        shows.distinctBy { it.showID }
    }

    /** 关键词搜索（ac=detail 全字段，含播放地址） */
    suspend fun search(keyword: String): List<OnlineShow> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val root = fetch("ac=detail&wd=$encoded") ?: return@withContext emptyList()
        decodeList(root).filter { show -> junkTitleKeywords.none { show.title.contains(it) } }
    }

    /** 某番全部剧集（编号 + 多线路）。解析是重活（长番上千集），强制 IO 线程避免冻 UI */
    suspend fun episodes(show: OnlineShow): List<OnlineEpisode> = withContext(Dispatchers.IO) {
        val entry = detailEntry(show.showID) ?: return@withContext emptyList()
        decodeEpisodes(entry, show)
    }

    /** 取流：优先 streamHint，否则重新拉详情按集号/标题匹配 */
    suspend fun streamURL(episode: OnlineShow, number: Int, title: String?): StreamSource? = withContext(Dispatchers.IO) {
        val entry = detailEntry(episode.showID) ?: return@withContext null
        val eps = decodeEpisodes(entry, episode)
        val target = eps.firstOrNull { it.number == number }
            ?: title?.let { t -> eps.firstOrNull { it.title == t } }
            ?: return@withContext null
        val url = target.streamHint ?: return@withContext null
        val origin = runCatching { java.net.URI(siteURL).let { "${it.scheme}://${it.host}" } }.getOrDefault("")
        return@withContext StreamSource(
            url = url,
            httpHeaders = if (origin.isNotEmpty()) mapOf("Referer" to origin) else emptyMap(),
            userAgent = Http.BROWSER_UA,
            isHLS = url.substringBefore('?').contains(".m3u8"),
        )
    }

    /**
     * ac=detail 响应里 list[] 中 vod_id 匹配的那条。
     * 分集字段（vod_play_url 等）在 list 数组项里而非根对象；按 vod_id 精确匹配——
     * 站点异常时兜底取第一条会把别的番的分集挂到本番上（mac 版同语义）。
     */
    private suspend fun detailEntry(showID: String): JsonObject? = withContext(Dispatchers.IO) {
        val root = fetch("ac=detail&ids=${java.net.URLEncoder.encode(showID, "UTF-8")}") ?: return@withContext null
        runCatching {
            root["list"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.stringField("vod_id") == showID }
        }.getOrNull()
    }

    // MARK: - 解析

    private fun decodeList(root: JsonObject): List<OnlineShow> {
        val list = root["list"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj.stringField("vod_id") ?: return@mapNotNull null
            val name = HtmlEntities.decode(obj.stringField("vod_name") ?: return@mapNotNull null)
            val typeName = obj.stringField("type_name")?.let(HtmlEntities::decode)
            // 只保留动漫类目（type_name 含 动漫/动画/番剧/剧场版，mac 版同规则）——
            // 真人电影/剧集/综艺/体育等由此排除；类目下误挂的解说/盘点标题二次剔除
            if (typeName == null || animeCategoryKeywords.none { typeName.contains(it) }) return@mapNotNull null
            if (junkTitleKeywords.any { name.contains(it) }) return@mapNotNull null
            val remarks = obj.stringField("vod_remarks")?.let(HtmlEntities::decode)
            OnlineShow(
                providerID = providerID,
                showID = id,
                title = name,
                subtitle = listOfNotNull(typeName, remarks).takeIf { it.isNotEmpty() }?.joinToString(" · "),
                coverURL = obj.stringField("vod_pic"),
            )
        }
    }

    /** vod_play_url 解析：组间 $$$ → 集间 # → name$url；首选含 m3u8 的组，线路=各组同位 URL */
    private fun decodeEpisodes(detail: JsonObject, show: OnlineShow): List<OnlineEpisode> {
        val playURL = detail.stringField("vod_play_url") ?: return emptyList()
        val playFrom = detail.stringField("vod_play_from").orEmpty()
        val groups = playURL.split("$$$")
        val groupNames = playFrom.split("$$$")
        if (groups.isEmpty()) return emptyList()

        val preferredIndex = groupNames.indices
            .filter { it < groups.size }
            .firstOrNull { groupNames[it].contains("m3u8", ignoreCase = true) } ?: 0
        val mainGroup = groups.getOrElse(preferredIndex) { groups.first() }

        data class RawEpisode(val name: String, val url: String)
        fun parseGroup(group: String): List<RawEpisode> = group.split("#").mapNotNull { item ->
            val first = item.indexOf('$')
            if (first <= 0) null
            else RawEpisode(
                name = HtmlEntities.decode(item.substring(0, first)),
                url = HtmlEntities.decode(item.substring(first + 1)),
            )
        }.filter { it.url.startsWith("http") }

        // 每组只解析一次（长番上千集时这是 O(组数×长度)；逐集重复解析是 O(n²)，会冻死主线程）
        val parsedGroups = groups.map { parseGroup(it) }
        val episodes = parsedGroups.getOrElse(preferredIndex) { emptyList() }
        val usedNumbers = HashSet<Int>()
        var fallback = 1
        fun nextFallback(): Int {
            while (usedNumbers.contains(fallback)) fallback++
            return fallback++
        }
        return episodes.mapIndexed { index, raw ->
            // 集号优先从集名解析（OVA/PV 混排时按序递增兜底，跳过已用号）
            val parsed = MediaMatching.episodeNumber(raw.name) ?: nextFallback()
            usedNumbers += parsed
            val routes = parsedGroups.mapNotNull { g -> g.getOrNull(index)?.url }.distinct()
            OnlineEpisode(
                providerID = show.providerID,
                showID = show.showID,
                number = parsed,
                title = raw.name,
                streamHint = raw.url,
                routes = routes.ifEmpty { listOf(raw.url) },
            )
        }
    }

    // MARK: - 请求

    private suspend fun fetch(query: String): JsonObject? = withContext(Dispatchers.IO) {
        val base = apiBase
        val origin = runCatching { java.net.URI(siteURL).let { "${it.scheme}://${it.host}" } }.getOrDefault("")
        val request = Request.Builder()
            .url("$base?$query")
            .header("User-Agent", Http.BROWSER_UA)
            .apply { if (origin.isNotEmpty()) header("Referer", origin) }
            .build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    private fun JsonObject.stringField(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()
}
