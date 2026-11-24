package com.nextmovie.core.poster

/**
 * L0：源站图片 URL 的尺寸升级规则。
 *
 * 大多数 CMS 站点的 `vod_pic` 本来就是豆瓣图床，只是给了小尺寸路径
 * （`s_ratio_poster`、`spic`、`img4~9`）。把 URL 参数换掉即可拿到同一张图的
 * 高清版本——不额外发请求、不做匹配，是零成本的第一层画质提升。
 *
 * 所有规则都是幂等的：对同一个 URL 反复调用结果不变，可以安全地在仓库层
 * 和 UI 层各调一次。
 */
object PosterUrlRules {

    private val DOUBAN_HOST = Regex("https?://(?:img[3-9]|qnmob\\d*-sign)\\.doubanio\\.com/")

    private const val DOUBAN_REFERER = "https://movie.douban.com/"
    private const val BILI_REFERER = "https://www.bilibili.com/"

    /**
     * 源站常见的"URL 带请求头"写法：`https://img...jpg@Referer=https://api.douban.com/@User-Agent=Mozilla/...`。
     * 这不是合法的图片地址，直接交给 Coil 会 400，必须先剥掉，把参数变成真正的请求头。
     */
    private val HEADER_MARK = listOf("@Referer=", "@referer=", "@User-Agent=", "@user-agent=", "@Origin=", "@Cookie=")

    fun stripHeaderSuffix(rawUrl: String): String {
        val url = rawUrl.trim()
        val cut = HEADER_MARK.map { url.indexOf(it) }.filter { it > 0 }.minOrNull() ?: return url
        return url.substring(0, cut)
    }

    /** 从 `@Referer=..@User-Agent=..` 里解析出请求头，供 Coil 用。 */
    fun headersFrom(rawUrl: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        rawUrl.split("@").drop(1).forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim().trimEnd('/')
            if (key.equals("Referer", true) || key.equals("User-Agent", true) || key.equals("Origin", true)) {
                out[key.lowercase().replaceFirstChar { it.uppercase() }] = value
            }
        }
        return out
    }

    /** 列表用中等尺寸，详情/背景用大尺寸。 */
    fun upgrade(rawUrl: String, detail: Boolean): String {
        var url = stripHeaderSuffix(rawUrl)
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) url = "https:$url"

        if (url.contains("doubanio.com")) {
            url = DOUBAN_HOST.replace(url, "https://img1.doubanio.com/")
            // 带 token 的查询串对图床无意义，且会破坏 CDN 缓存
            val q = url.indexOf('?')
            if (q > 0 && url.substring(0, q).endsWith(".jpg")) url = url.substring(0, q)
            url = if (detail) {
                url.replace("/s_ratio_poster/", "/l_ratio_poster/")
                    .replace("/m_ratio_poster/", "/l_ratio_poster/")
                    .replace("/view/photo/spic/", "/view/photo/lpic/")
                    .replace("/view/photo/mpic/", "/view/photo/lpic/")
                    .replace("/view/photo/s/", "/view/photo/l/")
                    .replace("/view/photo/m/", "/view/photo/l/")
                    .replace("/view/photo/large/", "/view/photo/l/")
            } else {
                url.replace("/s_ratio_poster/", "/m_ratio_poster/")
                    .replace("/view/photo/spic/", "/view/photo/mpic/")
                    .replace("/view/photo/s/", "/view/photo/m/")
            }
        }

        if (url.contains("image.tmdb.org/t/p/")) {
            url = if (detail) {
                url.replace("/t/p/w92/", "/t/p/w780/")
                    .replace("/t/p/w154/", "/t/p/w780/")
                    .replace("/t/p/w185/", "/t/p/w780/")
                    .replace("/t/p/w300/", "/t/p/w780/")
                    .replace("/t/p/w342/", "/t/p/w780/")
                    .replace("/t/p/w500/", "/t/p/w780/")
            } else {
                url.replace("/t/p/w92/", "/t/p/w500/")
                    .replace("/t/p/w154/", "/t/p/w500/")
                    .replace("/t/p/w185/", "/t/p/w500/")
                    .replace("/t/p/w300/", "/t/p/w500/")
                    .replace("/t/p/w342/", "/t/p/w500/")
            }
        }

        if (url.contains("lain.bgm.tv") || url.contains("bgm.tv/pic/cover/")) {
            url = if (detail) {
                url.replace("/pic/cover/c/", "/pic/cover/l/")
                    .replace("/pic/cover/m/", "/pic/cover/l/")
                    .replace("/pic/cover/s/", "/pic/cover/l/")
                    .replace("/r/400/", "/r/800/")
            } else {
                url.replace("/pic/cover/s/", "/pic/cover/m/")
                    .replace("/pic/cover/c/", "/pic/cover/m/")
            }
        }

        if (url.contains("hdslb.com") || url.contains("biliimg.com")) {
            // B站图床的 `@480w_270h_1c.webp` 这类后缀由调用方决定，这里统一去掉，
            // 交给 Coil 按控件尺寸降采样，避免后缀写法不兼容导致 404
            val at = url.indexOf('@')
            if (at > 0) url = url.substring(0, at)
        }

        if (url.contains("bcebos.com") || url.contains("bcevod.com")) {
            val q = url.indexOf('?')
            if (q > 0) url = url.substring(0, q)
        }

        return url
    }

    /** 豆瓣系图床会按 UA 判客户端：非浏览器 UA 返回 418，必须伪装成浏览器。 */
    fun needsBrowserUa(url: String): Boolean =
        url.contains("doubanio.com") || url.contains("douban.com")

    /** 部分图床有防盗链，需要带 Referer 才能取到图。 */
    fun refererFor(url: String): String? = when {
        url.contains("doubanio.com") -> DOUBAN_REFERER
        url.contains("hdslb.com") || url.contains("biliimg.com") -> BILI_REFERER
        else -> null
    }

    /**
     * 源图是否已经足够清晰：豆瓣 ratio/lpic、TMDB w500+、B站原图、bgm 大图。
     * 命中时跳过 L1，既省请求（B站 有 IP 级频控），又能保留源站原本更贴合的海报。
     */
    fun looksDecent(url: String): Boolean =
        url.contains("/l_ratio_poster/") ||
            url.contains("/m_ratio_poster/") ||
            url.contains("/view/photo/lpic/") ||
            url.contains("/view/photo/l/") ||
            url.contains("/t/p/w500/") ||
            url.contains("/t/p/w780/") ||
            url.contains("/t/p/original/") ||
            url.contains("/pic/cover/l/") ||
            url.contains("hdslb.com/bfs/bangumi/image/")

    /** 粗略判断这张图是不是“缩略图路径”，用于决定详情页要不要再升级一次。 */
    fun looksSmall(url: String): Boolean =
        url.contains("s_ratio_poster") ||
            url.contains("/view/photo/spic/") ||
            url.contains("/view/photo/s/") ||
            url.contains("/t/p/w92/") ||
            url.contains("/t/p/w154/") ||
            url.contains("/t/p/w185/")
}
