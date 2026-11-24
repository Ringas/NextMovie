package com.nextmovie.core.match

/**
 * 标题匹配打分（TMDB 与 B站 共用）。
 *
 * 规则（刻意保守，宁可留空也不贴错图）：
 *  - 标题归一化后全等 + 年份一致 → 1.0
 *  - 标题全等、年份缺失 → 0.9
 *  - 一方包含另一方 + 年份一致 → 0.78（低于阈值，仅用于排序，不放行）
 *  - 续集冲突（如「流浪地球」vs「流浪地球2」）→ 0.5，直接拒绝
 *  - 其余按编辑距离衰减，低于阈值直接放弃
 */
object TitleMatcher {

    const val ACCEPT_THRESHOLD = 0.80

    private val NOISE = Regex("[\\s·•:：\\-—_.,，。!！?？'\"《》<>\\[\\]()（）]+")
    private val SEASON_SUFFIX =
        Regex("(第[一二三四五六七八九十\\d]+季|Season\\s*\\d+|S\\d{1,2})", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        var s = raw.trim()
        s = SEASON_SUFFIX.replace(s, "")
        s = NOISE.replace(s, "")
        return s.lowercase()
    }

    fun score(
        queryTitle: String,
        queryYear: String,
        candidateTitle: String,
        candidateYear: String
    ): Double {
        val a = normalize(queryTitle)
        val b = normalize(candidateTitle)
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val yearKnown = queryYear.length == 4 && candidateYear.length == 4
        val yearMatch = yearKnown && queryYear == candidateYear

        // 续集歧义：仅差结尾数字时视为不同作品
        val sequelConflict = a != b &&
            ((a.startsWith(b) && a.last().isDigit()) || (b.startsWith(a) && b.last().isDigit()))

        return when {
            sequelConflict -> 0.5
            a == b && yearMatch -> 1.0
            a == b && !yearKnown -> 0.9
            a == b && yearKnown -> 0.55
            (a.contains(b) || b.contains(a)) && yearMatch &&
                (minOf(a.length, b.length) >= 0.6 * maxOf(a.length, b.length)) -> 0.78
            else -> {
                val similarity = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
                when {
                    yearMatch && similarity >= 0.82 -> similarity
                    !yearKnown && similarity >= 0.92 -> similarity
                    else -> 0.0
                }
            }
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
