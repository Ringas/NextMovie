package com.nextmovie.core.spider.native

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Wex 家族「轻量」原生路径。
 *
 * 完整 Wex Guard 含 Go 本地代理与多套加密配置（Marco 在 Node 侧按 adapter id 分支）。
 * 本实现只覆盖 **ext 已给出可直连 HTTP 源** 的子集：
 * - `ext` 为 MacCMS / provide/vod 风格 URL
 * - `ext` JSON 含 `api`/`url`/`host` 指向 JSON 列表接口
 * - `api` 为 `csp_Wex*` 且上述条件成立
 *
 * 不满足时 [matches] 仍为 true（认领 Wex），但方法内抛错 → [SpiderHost] 回退 JAR。
 * 这样「可直连」的站秒开，「必须 Go 代理」的站行为与原来一致。
 */
class WexHttpNativeSpider(private val http: Http) : NativeSpider {

    private data class Endpoint(val base: String, val headers: Map<String, String>)

    private val endpoints = ConcurrentHashMap<String, Endpoint>()

    override fun matches(site: Site): Boolean {
        val a = site.api
        return a.contains("Wex", true) || a.contains("wex", true)
    }

    override suspend fun home(site: Site): List<Vod> {
        val ep = resolve(site)
        val raw = http.text(ep.base, ep.headers)
        return parseList(raw)
    }

    override suspend fun categories(site: Site): List<Pair<String, String>> {
        val ep = resolve(site)
        val raw = http.text(withQuery(ep.base, mapOf("ac" to "list")), ep.headers)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("class") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("type_id")
            val name = o.optString("type_name")
            if (id.isNotBlank() && name.isNotBlank()) out += id to name
        }
        return out
    }

    override suspend fun category(site: Site, tid: String, page: Int): List<Vod> {
        val ep = resolve(site)
        val url = withQuery(
            ep.base,
            mapOf("ac" to "videolist", "t" to tid, "pg" to page.toString())
        )
        return parseList(http.text(url, ep.headers))
    }

    override suspend fun detail(site: Site, id: String): VodDetail? {
        val ep = resolve(site)
        val url = withQuery(ep.base, mapOf("ac" to "detail", "ids" to id))
        val raw = http.text(url, ep.headers)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val item = root.optJSONArray("list")?.optJSONObject(0) ?: return null
        val vod = VodParser.fromJson(item) ?: return null
        return VodDetail(vod, VodParser.playLines(item))
    }

    override suspend fun search(site: Site, key: String): List<Vod> {
        val ep = resolve(site)
        val url = withQuery(ep.base, mapOf("wd" to key, "ac" to "videolist"))
        return parseList(http.text(url, ep.headers))
    }

    override suspend fun play(site: Site, flag: String, id: String): PlayResult? {
        if (id.startsWith("http")) return PlayResult(url = id, parse = 0)
        return PlayResult(url = id, parse = 1)
    }

    private suspend fun resolve(site: Site): Endpoint {
        endpoints[site.runtimeKey]?.let { return it }
        val headers = site.headers.ifEmpty { mapOf("User-Agent" to Http.UA) }
        val base = discoverBase(site)
            ?: throw IllegalStateException("Wex 站点无可用直连 HTTP，回退插件路径")
        val ep = Endpoint(base, headers)
        endpoints[site.runtimeKey] = ep
        android.util.Log.i("NativeWex", "直连 ${site.name} → $base")
        return ep
    }

    private suspend fun discoverBase(site: Site): String? {
        val ext = site.ext.trim()
        if (ext.startsWith("http://") || ext.startsWith("https://")) {
            // 可能是配置中心 URL：尝试直接当 CMS，失败再拉 JSON 找 api 字段
            if (looksJsonApi(ext)) return ext
            val body = runCatching { http.text(ext, site.headers) }.getOrNull().orEmpty()
            if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                val fromJson = apiFromJson(body)
                if (fromJson != null) return fromJson
            }
            // 配置页本身可能就是 provide/vod
            if (body.contains("list") || body.contains("vod_name")) return ext
        }
        if (ext.startsWith("{")) {
            return apiFromJson(ext)
        }
        return null
    }

    private fun apiFromJson(text: String): String? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val candidates = listOf("api", "url", "host", "base", "vodApi", "cms")
        for (k in candidates) {
            val v = o.optString(k).trim()
            if (v.startsWith("http")) return v
        }
        o.optJSONObject("data")?.let { data ->
            for (k in candidates) {
                val v = data.optString(k).trim()
                if (v.startsWith("http")) return v
            }
        }
        return null
    }

    private fun looksJsonApi(url: String): Boolean =
        url.contains("provide/vod", true) ||
            url.contains("api.php", true) ||
            url.contains("/vod", true)

    private fun parseList(raw: String): List<Vod> {
        val t = raw.trim()
        if (t.startsWith("[")) {
            val arr = runCatching { org.json.JSONArray(t) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                VodParser.fromJson(arr.optJSONObject(it) ?: return@mapNotNull null)
            }
        }
        val root = runCatching { JSONObject(t) }.getOrNull() ?: return emptyList()
        return VodParser.list(root)
    }

    private fun withQuery(base: String, extra: Map<String, String>): String {
        val httpUrl = runCatching { base.toHttpUrl() }.getOrNull() ?: return base
        val b = httpUrl.newBuilder()
        extra.forEach { (k, v) -> b.setQueryParameter(k, v) }
        return b.build().toString()
    }
}
