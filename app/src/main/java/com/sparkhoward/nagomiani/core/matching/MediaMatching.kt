package com.sparkhoward.nagomiani.core.matching

/**
 * 文件名/集名解析：与 mac 版 MediaMatching.episodeNumber 的正则表逐条对齐（优先级顺序即数组顺序）。
 * 用于 MacCMS 剧集名→集号、本地文件名→集号、dandanplay 集名→集号。
 */
object MediaMatching {

    private val patterns = listOf(
        Regex("""[Ss]\d{1,2}\s*[Ee]\s*0*(\d{1,3})\b"""),                      // S01E03
        Regex("""[Ss]\d{1,2}\s*[._-]?\s*0*(\d{1,3})(?!\d)"""),                // S2 01 / S2-01
        Regex("""\b[Ee][Pp]\.?\s*0*(\d{1,3})\b"""),                           // EP03
        Regex("""\b[Ee]\s*0*(\d{1,3})\b"""),                                  // E03
        Regex("""第\s*0*(\d{1,3})\s*[话話集]"""),                               // 第03话/集
        Regex("""#\s*0*(\d{1,3})\b"""),                                        // #03
        Regex("""\s-\s*0*(\d{1,3})(?!\d)(?=\s*\[|\s*$)"""),                    // - 01 [1080p]
        Regex("""\[\s*0*(\d{1,3})\s*\]"""),                                    // [03]
        Regex("""^0*(\d{1,3})(?:v\d+)?$"""),                                   // 01.mkv / 03v2
        Regex("""^0*(\d{1,2})(?!\d)\.(?!\d)"""),                               // 01.title（后随数字拒绝）
        Regex("""^0*(\d{1,2})(?!\d)\s*-(?=\s|$)"""),                           // 01 - title（防 3-gatsu）
        Regex("""\b0*(\d{1,3})v\d+(?=\s*\[|\s*$)"""),                          // 03v2 [1080p]
    )

    /** 返回集号（1–999），解析不到返回 null */
    fun episodeNumber(from: String): Int? {
        for (pattern in patterns) {
            val m = pattern.find(from) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..999) return n
        }
        return null
    }

    /** 从名称识别季号（S1 / Season 2 / 第3季 / 第二季 / Part 2 / 末尾罗马数字 / 末尾 0 / 续）——与 mac 版逐条对齐 */
    fun seasonNumber(from: String): Int? {
        matchGroup1("[Ss]\\s*0*(\\d{1,2})\\b", from)?.let { return it }
        matchGroup1("(?i)season\\s*0*(\\d{1,2})", from)?.let { return it }
        matchGroup1("第\\s*0*(\\d{1,2})\\s*季", from)?.let { return it }
        matchGroup1("(?i)part\\s*0*(\\d{1,2})", from)?.let { return it }
        chineseNumeralSeason(from)?.let { return it }
        romanNumeralSeason(from)?.let { return it }
        // 续作启发式：末尾 0（命运石之门0）或含"续" → 视为第 2 季
        if (Regex("(?<=[^0-9])0\\s*$").containsMatchIn(from)) return 2
        if (from.contains("续")) return 2
        return null
    }

    private fun matchGroup1(pattern: String, source: String): Int? =
        Regex(pattern).find(source)?.groupValues?.get(1)?.toIntOrNull()

    private fun chineseNumeralSeason(name: String): Int? {
        val m = Regex("第[一二三四五六七八九十]+季").find(name) ?: return null
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
        var total = 0
        for (ch in m.value) {
            val value = map[ch] ?: continue
            total = if (value == 10) (if (total == 0) 10 else total * 10)
            else (if (total >= 10) 10 + value else value)
        }
        return if (total == 0) null else total
    }

    private fun romanNumeralSeason(name: String): Int? {
        val m = Regex("\\s(I{1,3}|IV|V|VI{0,3}|IX|X)$").find(name) ?: return null
        val map = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10)
        return map[m.value.trim()]
    }
}
