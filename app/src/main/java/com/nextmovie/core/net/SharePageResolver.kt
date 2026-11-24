package com.nextmovie.core.net

import java.net.URI

/**
 * 「分享页」解析：CMS 的 m3u8 线路常常返回一个 HTML 播放页（如
 * `https://xxx/share/<id>`），真正的地址藏在页面脚本里，通常是
 * `const url = "/2026…/index.m3u8?sign=…"` 这种相对路径。
 *
 * 这里只做通用、无规则依赖的那一层：
 * 1) 抓页面（浏览器 UA，很多站会对 okhttp UA 直接 403）；
 * 2) 把 `\/`、`\u002F` 这类转义还原；
 * 3) 从若干常见写法里取第一条像播放地址的字符串；
 * 4) 相对路径按页面 origin 补全。
 *
 * 规则型解析（每个站点一套正则/AES）不在这一层做，交给后续的插件能力。
 */
object SharePageResolver {

    private val candidates = listOf(
        Regex("\"url\"\\s*:\\s*\"(.{4,600}?)\""),
        Regex("\"playUrl\"\\s*:\\s*\"(.{4,600}?)\""),
        Regex("\"m3u8\"\\s*:\\s*\"(.{4,600}?)\""),
        Regex("url\\s*=\\s*\"(.{4,600}?)\""),
        Regex("url\\s*=\\s*'(.*?m3u8.*?)'"),
        Regex("src\\s*=\\s*\"(.{4,600}?m3u8[^\"]*)\""),
        Regex("\"(/\\d{6,8}/[^\"]*?\\.(?:m3u8|mp4)[^\"]*)\"")
    )

    /** 判断这个地址像不像「网页」而不是媒体直链。 */
    fun looksLikePage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".flv") || lower.contains(".mkv") || lower.contains(".ts")
        ) return false
        return lower.startsWith("http")
    }

    /** 返回页面里解析出的播放地址；解析不出来返回 null。 */
    suspend fun resolve(http: Http, pageUrl: String, headers: Map<String, String> = emptyMap()): String? {
        val html = runCatching {
            http.text(pageUrl, headers = headers, ua = Http.BROWSER_UA, referer = pageUrl)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return extract(html, pageUrl)
    }

    /** 纯函数部分：从 HTML 文本里抠播放地址，便于单测。 */
    internal fun extract(html: String, pageUrl: String): String? {
        val text = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
        for (regex in candidates) {
            val raw = regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (raw.isBlank()) continue
            val full = absolutize(pageUrl, raw)
            if (full.startsWith("http")) return full
        }
        return null
    }

    /** 只认 http(s) 与同源相对路径；其它（如 blob:、data:）丢掉。 */
    private fun absolutize(base: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        if (candidate.startsWith("//")) {
            val scheme = runCatching { URI(base).scheme }.getOrNull() ?: "https"
            return "$scheme:$candidate"
        }
        if (candidate.startsWith("/")) {
            val origin = runCatching {
                val u = URI(base)
                "${u.scheme}://${u.authority}"
            }.getOrNull() ?: return candidate
            return origin + candidate
        }
        return candidate
    }
}
