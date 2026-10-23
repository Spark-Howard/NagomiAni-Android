package com.sparkhoward.nagomiani.core.online

import com.sparkhoward.nagomiani.core.matching.MediaMatching
import com.sparkhoward.nagomiani.core.model.Episode
import com.sparkhoward.nagomiani.core.model.OnlineShow
import com.sparkhoward.nagomiani.core.similarity.TitleSimilarity
import java.time.LocalDate

/**
 * 放送日历 × 资源站聚合/评分（与 mac 版 WeeklyAggregator 同构，纯函数可单测）。
 *
 * 详情页「在线观看」按需搜源的评分口径：
 * - **铁律：相似度比较前双侧过 cleanTitle**——资源站标题普遍带 [字幕组]/[1080p] 标签，
 *   不清洗则 Bangumi 正题永远匹配不上；
 * - **类型分流：TV 条目排除剧场版候选**——资源站把剧场版与 TV 版挂在同一"动漫"类目，
 *   且电影标题必含主名（"XX剧场版"），包含关系保底 0.85 挡不住，只能按关键词先行分流；
 * - 相似度低于 [MATCH_THRESHOLD] 的候选直接丢弃（体育/综艺等站内误搜结果由此排除）；
 * - 包含关系保底的短侧门槛收紧到 [CONTAINMENT_MIN_LENGTH]（默认 4 太松：4-5 字主名与
 *   同系列长标题——剧场版/国语版/合集——全拿 0.85，与正片并列）；
 * - 季号一致的小加分（放送中的番与资源站最近更新的多为同一季）。
 */
object WeeklyAggregator {
    /** 标题相似度达标才算"找到了片源" */
    const val MATCH_THRESHOLD = 0.4

    /** 季度一致的小加分 */
    const val SEASON_BONUS = 0.05

    /** 包含关系保底的短侧最小长度（片源评分用 6；弹幕匹配等调用方维持 TitleSimilarity 默认 4） */
    const val CONTAINMENT_MIN_LENGTH = 6

    /** 单次搜源的关键词上限（每个词打一轮全站搜索，超长名降级词也计入） */
    const val MAX_SEARCH_KEYWORDS = 6

    /** 剧场版标题特征（资源站候选匹配用；"movie" 小写比较） */
    private val movieTitleKeywords = listOf("剧场版", "劇場版", "映画", "movie")

    /** 剧场版平台/类目特征（Bangumi platform、资源站 subtitle 的类目段） */
    private val moviePlatformKeywords = listOf("剧场版", "劇場版", "映画", "电影", "movie")

    /** 是否剧场版/电影：platform/类目优先（TV 条目为 null/TV 直接落空），标题关键词兜底 */
    fun isMovieTitle(title: String, platform: String? = null): Boolean {
        if (platform != null && moviePlatformKeywords.any { platform.lowercase().contains(it) }) return true
        val t = title.lowercase()
        return movieTitleKeywords.any { t.contains(it) }
    }

    /** Bangumi 条目是否剧场版：中文名、日文原名、platform 任一命中即可（条目名常漏"剧场版"字样） */
    fun isMovieSubject(displayName: String, originalName: String?, platform: String?): Boolean =
        isMovieTitle(displayName, platform) || isMovieTitle(originalName.orEmpty())

    /** 长名切短的分隔符：资源站收录名通常只有主名段，"主名 副标题"式长名在分隔符处断开 */
    private val headSeparators =
        charArrayOf(' ', '　', '，', '、', '。', '：', ':', '·', '・', '～', '〜', '！', '？', '!', '?', '（', '）', '(', ')', '「', '」', '『', '』', '—', '－')

    /**
     * 组装片源搜索关键词：中文名 → 日文原名 → 别名，逐词全站搜；
     * ≥12 字的长名追加首个分隔段做短词降级——MacCMS wd 是子串匹配，超长译名原文几乎必落空，
     * 站内收录名往往只有主名段。短段须含 ≥2 个 CJK 字符（过滤英文名切出的 "Reborn" 类词根）。
     */
    fun searchKeywords(displayName: String, originalName: String?, aliases: List<String>): List<String> {
        val raw = (listOfNotNull(displayName, originalName) + aliases)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        val out = ArrayList<String>()
        for (kw in raw) {
            if (out.size >= MAX_SEARCH_KEYWORDS) break
            out += kw
            if (out.size < MAX_SEARCH_KEYWORDS && kw.length >= 12) {
                val head = kw.split(*headSeparators).first().trim()
                if (head.length in 4 until kw.length && head.count { isCJK(it) } >= 2 && head !in out) {
                    out += head
                }
            }
        }
        return out
    }

    private fun isCJK(c: Char): Boolean =
        c.code in 0x3040..0x30FF || c.code in 0x3400..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0xFF66..0xFF9D

    /**
     * 放送门禁：从 Bangumi 分集表算出**尚未放送**的本篇集（airdate 晚于 [today]）→ 集号映射 airdate。
     * 资源站常提前上架先行配信/错标内容，官方日程未到的集在详情页置灰禁播，开播当天自动解禁。
     * airdate 缺失/精度不足（如 "2026-10"）/解析失败都不参与门禁——宁可放过，不可误杀；
     * SP/PV 等特殊集（type≠0）同样不门禁。
     */
    fun unairedAirdates(episodes: List<Episode>, today: LocalDate): Map<Int, String> =
        episodes.mapNotNull { ep ->
            if (ep.type != 0) return@mapNotNull null
            val raw = ep.airdate?.trim().orEmpty()
            val date = raw.takeIf { it.length >= 10 }
                ?.let { runCatching { LocalDate.parse(raw.substring(0, 10)) }.getOrNull() }
                ?: return@mapNotNull null
            if (date.isAfter(today)) ep.displaySort to raw else null
        }.toMap()

    /** 标题归一化：清洗字幕组/画质标签 + 相似度的空白标点归一 */
    fun normalize(title: String): String = TitleSimilarity.normalize(cleanTitle(title))

    /**
     * 为一个 Bangumi 条目标题在候选池里**评分排序**（搜索详情页「在线观看」按需搜源用）。
     * 与 mac 版同口径：双侧 cleanTitle 清洗 + 季号一致加分；低于阈值的丢弃，按分数降序。
     * [subjectIsMovie] 由调用方传条目判定（platform + 主名），缺省按标题关键词推断。
     */
    fun score(
        subjectTitle: String,
        candidates: List<OnlineShow>,
        subjectIsMovie: Boolean? = null,
    ): List<Pair<OnlineShow, Double>> {
        val subjectMovie = subjectIsMovie ?: isMovieTitle(subjectTitle)
        val subjectSeason = MediaMatching.seasonNumber(subjectTitle)
        return candidates.mapNotNull { candidate ->
            // TV 条目排除剧场版候选（subtitle 带站点类目/备注，一并判）。反向不排除：
            // 资源站收录电影时常省略"剧场版"字样（如"XX无限列车篇国语"），剧场版条目
            // 按 TV 名候选排除会误杀正主
            if (!subjectMovie && isMovieTitle(candidate.title, candidate.subtitle)) return@mapNotNull null
            var score = TitleSimilarity.similarity(subjectTitle, normalize(candidate.title), CONTAINMENT_MIN_LENGTH)
            val candidateSeason = MediaMatching.seasonNumber(candidate.title)
            if (subjectSeason != null && candidateSeason != null && subjectSeason == candidateSeason) {
                score += SEASON_BONUS
            }
            if (score < MATCH_THRESHOLD) return@mapNotNull null
            candidate to score
        }.sortedByDescending { it.second }
    }

    /** 清洗标题中的 [] 标签与常见画质/编码/语言标签（mac 版 BangumiMatcher.cleanTitle 同构） */
    fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\[[^\\]]*\\]"), "")
        t = t.replace(
            Regex("(?i)(2160p|1080p|720p|480p|4k|x264|x265|h264|h265|hevc|avc|aac|flac|10bit|bdrip|webrip|web-dl|dvdrip|tvrip|bluray|remux|jpn|chs|cht|big5|简日|繁日|简体|繁体|内嵌|外挂|合集)"),
            "",
        )
        return t.trim()
    }
}
