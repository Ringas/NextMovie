package com.nextmovie.ui.common

/**
 * 历史条目上的「看到哪了」文案。
 *
 * 源站给的 episodeName 常常是 `[5.8GB]蚊子杯：最后一役.mp4 【最后一役】` 这种文件名字符串，
 * 直接糊到封面上既看不清也没信息量。这里只保留真正有意义的：第 N 集 / EPxx，
 * 都没有就给空串，由调用方回落到「已看 x%」。
 */
object HistoryText {

    private val EXT = Regex("\\.(mp4|mkv|avi|ts|m2ts|mov|flv|wmv|webm|rmvb)$", RegexOption.IGNORE_CASE)
    private val BRACKET_PREFIX = Regex("^\\[[^\\]]*\\]")
    private val NUM_PREFIX = Regex("^\\d+[_.\\-\\s]*")
    private val EPISODE = Regex("(第\\s*\\d+\\s*[集话期])|(EP?\\s*\\d+)", RegexOption.IGNORE_CASE)

    fun episodeLabel(episodeName: String, title: String): String {
        var raw = episodeName.trim()
        if (raw.isBlank()) return ""
        raw = raw.replace(BRACKET_PREFIX, "").trim()
        raw = raw.substringBefore("【").trim()
        raw = raw.substringBefore("[").trim()
        raw = raw.replace(EXT, "").trim()
        EPISODE.find(raw)?.value?.let { hit ->
            val clean = hit.replace(Regex("\\s"), "")
            // 源站常给 e01 / EP01，统一成中文「第 N 集」，遥控器上更像人话
            val ascii = Regex("^(EP?|e)\\d+$", RegexOption.IGNORE_CASE).find(clean)
            if (ascii != null) {
                val n = ascii.value.filter { it.isDigit() }.toIntOrNull()
                return if (n != null) "第 $n 集" else clean
            }
            return clean
        }
        raw = raw.replace(NUM_PREFIX, "").trim()
        if (raw.isBlank() || raw.equals(title.trim(), ignoreCase = true)) return ""
        return if (raw.length <= 12) raw else ""
    }
}
