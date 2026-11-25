package com.nextmovie.core.cms

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * CMS 型站点（type 0 = XML，type 1/4 = JSON）客户端。
 * 这类站点不需要任何插件，只靠 `ac=` 系列 query 就能工作，适合作为 v0 的默认通路。
 */
class CmsSiteClient(
    private val http: Http,
    private val site: Site,
    private val extension: suspend () -> String = { site.ext }
) {

    private val detailAction: String get() = if (site.isHttpT4) "detail" else "videolist"

    suspend fun play(flag: String, id: String): PlayResult? =
        PlayResult.fromJson(JSONObject(request(mapOf("play" to id, "flag" to flag))))

    suspend fun fetchExtension(): String {
        val ext = site.ext.trim()
        val url = ext.toHttpUrlOrNull() ?: return ext
        val api = site.api.toHttpUrl()
        val headers = if (url.host == api.host && url.port == api.port && url.scheme == api.scheme) site.headers else emptyMap()
        return http.text(url.toString(), headers).trim()
    }

    suspend fun home(): List<Vod> = query(
        if (site.isHttpT4) mapOf("filter" to "true") else mapOf("ac" to "videolist", "pg" to "1")
    )

    /**
     * 分类导航：苹果CMS 的 `ac=list` 会带一个 `class` 数组（type_id / type_name）。
     * 拿不到就返回空，首页退化成「只有最近更新」，不阻塞主流程。
     */
    suspend fun categories(): List<Pair<String, String>> {
        val raw = request(if (site.isHttpT4) mapOf("filter" to "true") else mapOf("ac" to "list"))
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("class") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("type_id").ifBlank { item.optString("type_pid") }
            val name = item.optString("type_name")
            if (id.isNotBlank() && name.isNotBlank()) out += id to name
        }
        return out
    }

    suspend fun category(tid: String, page: Int): List<Vod> {
        val params = linkedMapOf("ac" to detailAction, "pg" to page.toString())
        if (site.isHttpT4) params["ext"] = "e30="
        if (tid.isNotBlank()) params["t"] = tid
        return query(params)
    }

    suspend fun search(keyword: String): List<Vod> {
        val params = linkedMapOf("wd" to keyword, "pg" to "1")
        if (site.isHttpT4) params["quick"] = "false" else params["ac"] = "videolist"
        return query(params)
    }

    suspend fun detail(id: String): VodDetail? {
        val raw = request(mapOf("ac" to detailAction, "ids" to id))
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root != null) {
            val list = root.optJSONArray("list")
            val item = list?.optJSONObject(0) ?: return null
            val vod = VodParser.fromJson(item) ?: return null
            return VodDetail(vod, VodParser.playLines(item))
        }
        return XmlCmsParser.details(raw).firstOrNull()
    }

    private suspend fun query(params: Map<String, String>): List<Vod> {
        val raw = request(params)
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root != null) return VodParser.list(root)
        return XmlCmsParser.details(raw).map { it.vod }
    }

    private suspend fun request(params: Map<String, String>): String {
        val extension = if (site.isHttpT4) extension() else ""
        val requestParams = if (extension.isNotEmpty()) params + ("extend" to extension) else params
        if (site.isHttpT4 && extension.length > 1000) return http.text(site.api, site.headers, referer = site.api, form = requestParams)
        val builder = site.api.toHttpUrl().newBuilder()
        requestParams.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        return http.text(builder.build().toString(), site.headers, referer = site.api)
    }
}
