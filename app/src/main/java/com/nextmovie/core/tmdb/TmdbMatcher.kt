package com.nextmovie.core.tmdb

import com.nextmovie.core.match.TitleMatcher

/** TMDB 侧的匹配入口，规则集中在 [TitleMatcher]，这里只保留命名空间兼容。 */
object TmdbMatcher {

    const val ACCEPT_THRESHOLD = TitleMatcher.ACCEPT_THRESHOLD

    fun normalize(raw: String): String = TitleMatcher.normalize(raw)

    fun score(
        queryTitle: String,
        queryYear: String,
        candidateTitle: String,
        candidateYear: String
    ): Double = TitleMatcher.score(queryTitle, queryYear, candidateTitle, candidateYear)
}
