package com.nextmovie.core.spider.native

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * 通用「ext 已是 CMS HTTP」原生路径。
 * 认领：`api` 以 `native:` 开头，或 `csp_*` 且 ext 为 provide/vod 类 URL。
 */
class ExtHostNativeSpider(private val http: Http) : NativeSpider {

    override fun matches(site: Site): Boolean {
        if (site.api.startsWith("native:", true)) return true
        if (!site.api.startsWith("csp_")) return false
        val ext = site.ext.trim()
        return (ext.startsWith("http://") || ext.startsWith("https://")) &&
            (ext.contains("provide/vod", true) || ext.contains("api.php", true))
    }

    private fun base(site: Site): String {
        if (site.api.startsWith("native:", true)) {
            return site.api.removePrefix("native:").removePrefix("NATIVE:").trim().ifBlank { site.ext }
        }
        return site.ext.trim()
    }

    override suspend fun home(site: Site): List<Vod> =
        parseList(http.text(base(site), site.headers))

    override suspend fun categories(site: Site): List<Pair<String, String>> {
        val raw = http.text(q(base(site), mapOf("ac" to "list")), site.headers)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("class") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type_id")
            val name = o.optString("type_name")
            if (id.isBlank() || name.isBlank()) null else id to name
        }
    }

    override suspend fun category(site: Site, tid: String, page: Int): List<Vod> =
        parseList(
            http.text(
                q(base(site), mapOf("ac" to "videolist", "t" to tid, "pg" to page.toString())),
                site.headers
            )
        )

    override suspend fun detail(site: Site, id: String): VodDetail? {
        val raw = http.text(q(base(site), mapOf("ac" to "detail", "ids" to id)), site.headers)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val item = root.optJSONArray("list")?.optJSONObject(0) ?: return null
        val vod = VodParser.fromJson(item) ?: return null
        return VodDetail(vod, VodParser.playLines(item))
    }

    override suspend fun search(site: Site, key: String): List<Vod> =
        parseList(http.text(q(base(site), mapOf("wd" to key, "ac" to "videolist")), site.headers))

    override suspend fun play(site: Site, flag: String, id: String): PlayResult? =
        PlayResult(url = id, parse = if (id.startsWith("http")) 0 else 1)

    private fun parseList(raw: String): List<Vod> {
        val root = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return emptyList()
        return VodParser.list(root)
    }

    private fun q(base: String, extra: Map<String, String>): String {
        val b = base.toHttpUrl().newBuilder()
        extra.forEach { (k, v) -> b.setQueryParameter(k, v) }
        return b.build().toString()
    }
}
