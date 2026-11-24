package com.nextmovie.ui.common

import android.content.Context
import coil.request.ImageRequest
import com.nextmovie.core.poster.PosterUrlRules

/**
 * 给 Coil 的 model：统一做 L0 尺寸升级，并按图床补 Referer。
 *
 * 豆瓣 / B站 图床有防盗链，不带 Referer 会 403；用 ImageRequest 而不是裸 URL，
 * 顺带让 Coil 的缓存 key 带上请求头。
 */
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

fun posterModel(context: Context, rawUrl: String, detail: Boolean = false): Any {
    val url = PosterUrlRules.upgrade(rawUrl, detail)
    if (url.isEmpty()) return ""
    val builder = ImageRequest.Builder(context).data(url)
    // 源站把请求头写在 URL 尾部（@Referer=..@User-Agent=..），这里还原成真正的请求头
    val baked = PosterUrlRules.headersFrom(rawUrl)
    val referer = baked["Referer"] ?: PosterUrlRules.refererFor(url)
    referer?.let { builder.addHeader("Referer", it) }
    // 豆瓣图床对非浏览器 UA 直接返回 418（实测：无 UA 418/13 字节，Chrome UA 200/正常图片），
    // 全局带一次浏览器 UA，其它图床也普遍更宽容。
    builder.addHeader("User-Agent", baked["User-Agent"] ?: BROWSER_UA)
    builder.listener(
        onSuccess = { _, _ -> android.util.Log.d("NextMoviePoster", "ok $url") },
        onError = { _, r -> android.util.Log.w("NextMoviePoster", "fail $url -> ${r.throwable}") }
    )
    return builder.build()
}
