package com.nextmovie.core.model

import java.util.Locale

/** Descriptive fields can appear before a source has returned playable episodes. */
internal fun Vod.withMetadataFrom(fallback: Vod): Vod = copy(
    pic = pic.ifBlank { fallback.pic },
    backdrop = backdrop.ifBlank { fallback.backdrop },
    year = year.ifBlank { fallback.year },
    area = area.ifBlank { fallback.area },
    typeName = typeName.ifBlank { fallback.typeName },
    content = content.ifBlank { fallback.content },
    score = score.ifBlank { fallback.score },
    doubanScore = doubanScore.ifBlank { fallback.doubanScore },
    resolution = resolution.ifBlank { fallback.resolution },
    audio = audio.ifBlank { fallback.audio },
    remark = remark.ifBlank { fallback.remark },
    tmdb = tmdb ?: fallback.tmdb
)

internal fun Vod.ratingBadges(): List<String> = buildList {
    fun addRating(label: String, value: Double?) {
        if (value != null && value > 0 && value <= 10) add(String.format(Locale.ROOT, "%s %.1f", label, value))
    }
    addRating("豆瓣", doubanScore.toDoubleOrNull())
    addRating("片源评分", score.toDoubleOrNull())
    addRating("TMDB", tmdb?.voteAverage)
}

internal fun Vod.mediaBadges(episodeName: String = "", lineName: String = ""): List<String> {
    val fields = listOf(episodeName, lineName, resolution, audio, remark)
    val sources = fields.filter { it.isNotBlank() }
    val height = listOf("4K" to "(?:4k|2160p?|uhd)", "1440p" to "1440p", "1080p" to "1080p?", "720p" to "720p?")
    fun matches(input: String, expression: String) =
        Regex("(?<![a-z0-9])(?:$expression)(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(input)
    return buildList {
        sources.firstNotNullOfOrNull { input -> height.firstOrNull { matches(input, it.second) }?.first }?.let { add(it) }
        val tags = sources.joinToString(" ")
        when {
            matches(tags, "dolby[ ._-]*vision|dovi|杜比视界") -> add("Dolby Vision")
            matches(tags, "hdr10[+](?![a-z0-9])") -> add("HDR10+")
            matches(tags, "hdr10|hdr") -> add("HDR")
        }
        when {
            matches(tags, "atmos|全景声") -> add("Dolby Atmos")
            matches(tags, "dts[ ._-]*hd") -> add("DTS-HD")
            matches(tags, "dts") -> add("DTS")
            matches(tags, "truehd") -> add("Dolby TrueHD")
            matches(tags, "e[ ._-]*ac3|ddp|dd[+]") -> add("Dolby Digital Plus")
            matches(tags, "ac3") -> add("Dolby Digital")
        }
        if (matches(tags, "7[.]1")) add("7.1")
        else if (matches(tags, "5[.]1")) add("5.1")
    }
}
