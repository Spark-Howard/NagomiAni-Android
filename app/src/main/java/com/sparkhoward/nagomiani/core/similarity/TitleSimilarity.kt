package com.sparkhoward.nagomiani.core.similarity

/**
 * 标题相似度：与 mac 版 TitleSimilarity 完全同构。
 * normalize = 转小写 + 去空白与标点；similarity = 完全相等 1.0，否则字符二元组 Dice 系数；
 * 包含关系（被包含方长度 ≥ [containmentMinLength]，缺省 4）保底 0.85。
 */
object TitleSimilarity {

    fun normalize(title: String): String {
        val sb = StringBuilder(title.length)
        for (ch in title) {
            // 只保留字母数字（Character.isLetterOrDigit 对汉字返回 true，标点/空白全部剔除）
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * [containmentMinLength] 收紧包含保底的短侧门槛：4-5 字主名与同系列长标题
     * （剧场版/国语版/合集）全靠它拿保底分，片源评分传 6 让位给正片；其余调用方用缺省 4 不变。
     */
    fun similarity(a: String, b: String, containmentMinLength: Int = 4): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        // 包含关系：较短一方达到门槛视作高度相似（副标题/季名干扰的兜底）
        if (na.length >= containmentMinLength && nb.contains(na) || nb.length >= containmentMinLength && na.contains(nb)) return 0.85
        return diceBigram(na, nb)
    }

    private fun diceBigram(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val gramsA = HashMap<String, Int>()
        for (i in 0..a.length - 2) gramsA.merge(a.substring(i, i + 2), 1, Int::plus)
        var overlap = 0
        for (i in 0..b.length - 2) {
            val gram = b.substring(i, i + 2)
            val count = gramsA[gram] ?: continue
            if (count > 0) {
                overlap++
                gramsA[gram] = count - 1
            }
        }
        return 2.0 * overlap / (a.length - 1 + b.length - 1)
    }
}
