package com.nextmovie.core.text

/**
 * 源站简介常年混着 HTML：`<p>`、`<br>`、`&nbsp;`、`&amp;` 之类。
 * 电视端一行字被标签占掉半行非常难看，这里统一净化。
 */
object TextClean {

    private val TAG = Regex("<[^>]*>")
    private val MULTI_SPACE = Regex("[ \\t\\u00A0\\u3000]+")
    private val MULTI_NEWLINE = Regex("\\n{3,}")

    private val ENTITIES = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&hellip;" to "…",
        "&mdash;" to "—"
    )

    fun plain(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("<br>", " ").replace("<br/>", " ").replace("<br />", " ")
        text = text.replace("<p>", " ").replace("</p>", " ")
        text = TAG.replace(text, "")
        for ((k, v) in ENTITIES) text = text.replace(k, v)
        // 形如 &#160; / &#x20; 的数字实体
        text = Regex("&#(x?)([0-9a-fA-F]+);").replace(text) { m ->
            val radix = if (m.groupValues[1].isNotEmpty()) 16 else 10
            runCatching { String(Character.toChars(m.groupValues[2].toInt(radix))) }.getOrDefault("")
        }
        // 源站爱在简介尾巴塞广告：“如果您喜欢嗨翻影院提供的《xxx》…” 直接截断
        text = text.substringBefore("如果您喜欢")
        text = text.substringBefore("更多精彩")
        text = MULTI_SPACE.replace(text, " ")
        text = MULTI_NEWLINE.replace(text, "\n\n")
        return text.trim()
    }
}
