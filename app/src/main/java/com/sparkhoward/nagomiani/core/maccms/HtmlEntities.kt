package com.sparkhoward.nagomiani.core.maccms

/**
 * HTML 实体解码（MacCMS 站点数据常用数值/十六进制实体，且存在双重转义）。
 * 与 mac 版逻辑一致：数值 + 十六进制 + 容忍缺失分号，最多三轮（处理 &amp;#39; 双重转义）。
 */
object HtmlEntities {

    private val numeric = Regex("""&#(x?)([0-9a-fA-F]+);?""")
    private val named = listOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "nbsp" to " ", "hellip" to "…", "mdash" to "—",
        "middot" to "·", "times" to "×",
    )

    fun decode(input: String): String {
        var text = input
        repeat(3) {
            val decoded = decodeOnce(text)
            if (decoded == text) return decoded
            text = decoded
        }
        return text
    }

    private fun decodeOnce(text: String): String {
        val afterNumeric = numeric.replace(text) { m ->
            val code = (if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toIntOrNull(16)
            else m.groupValues[2].toIntOrNull(10))?.takeIf { it in 1..0x10FFFF }
            if (code != null) String(Character.toChars(code)) else m.value
        }
        var result = afterNumeric
        for ((name, ch) in named) {
            result = result.replace("&$name;", ch)
        }
        return result
    }
}
