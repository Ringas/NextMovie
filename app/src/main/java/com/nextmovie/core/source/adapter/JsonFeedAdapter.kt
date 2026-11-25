package com.nextmovie.core.source.adapter

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯数据适配器：把「一段 JSON 列表」或「苹果 CMS 兼容 provide/vod」当成源，
 * 不加载 JAR / JS。
 *
 * 识别方式（[matches]）：
 * - `api` 以 `adapter:json` / `adapter:cms` 开头
 * - 或 `api` 为 http(s) 且站点 `type` 被路由判为 ADAPTER（由 SourceKind 决定）
 *
 * `ext` 可为空；若为 URL 则优先拉取该地址作为列表；否则请求 `api`。
 */
class JsonFeedAdapter(private val http: Http) : BaseSiteAdapter() {

    override suspend fun home(site: Site): List<Vod> = loadList(site)

    override suspend fun categories(site: Site): List<Pair<String, String>> {
        val raw = fetchRaw(site) ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("class") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("type_id").ifBlank { o.optString("type_pid") }
            val name = o.optString("type_name")
            if (id.isNotBlank() && name.isNotBlank()) out += id to name
        }
        return out
    }

    override suspend fun category(site: Site, tid: String, page: String): List<Vod> {
        val base = endpoint(site)
        if (!base.startsWith("http")) return home(site)
        val url = base.toHttpBuilder()
            .setQueryParameter("ac", "videolist")
            .setQueryParameter("t", tid)
            .setQueryParameter("pg", page)
            .build().toString()
        val raw = runCatching { http.text(url, site.headers) }.getOrNull() ?: return emptyList()
        return parseList(raw)
    }

    override suspend fun detail(site: Site, id: String): VodDetail? {
        val base = endpoint(site)
        if (base.startsWith("http") && looksLikeCms(base)) {
            val url = base.toHttpBuilder()
                .setQueryParameter("ac", "videolist")
                .setQueryParameter("ids", id)
                .build().toString()
            val raw = runCatching { http.text(url, site.headers) }.getOrNull() ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val item = root.optJSONArray("list")?.optJSONObject(0) ?: return null
            val vod = VodParser.fromJson(item) ?: return null
            return VodDetail(vod, VodParser.playLines(item))
        }
        // 静态列表里按 id 找
        val hit = loadList(site).firstOrNull { it.id == id } ?: return null
        val line = com.nextmovie.core.model.PlayLine(
            name = "默认",
            episodes = listOf(com.nextmovie.core.model.Episode(name = hit.name, url = hit.id))
        )
        return VodDetail(hit, listOf(line))
    }

    override suspend fun search(site: Site, key: String): List<Vod> {
        val base = endpoint(site)
        if (base.startsWith("http") && looksLikeCms(base)) {
            val url = base.toHttpBuilder()
                .setQueryParameter("wd", key)
                .setQueryParameter("ac", "videolist")
                .build().toString()
            val raw = runCatching { http.text(url, site.headers) }.getOrNull() ?: return emptyList()
            return parseList(raw)
        }
        val q = key.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return loadList(site).filter {
            it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
        }
    }

    override suspend fun play(site: Site, flag: String, id: String): PlayResult? =
        PlayResult(url = id, parse = 0, headers = site.headers)

    private suspend fun loadList(site: Site): List<Vod> {
        val raw = fetchRaw(site) ?: return emptyList()
        return parseList(raw)
    }

    private suspend fun fetchRaw(site: Site): String? {
        val ext = site.ext.trim()
        if (ext.startsWith("http://") || ext.startsWith("https://")) {
            return runCatching { http.text(ext, site.headers) }.getOrNull()
        }
        if (ext.startsWith("{") || ext.startsWith("[")) return ext
        val api = endpoint(site)
        if (api.startsWith("http")) {
            return runCatching { http.text(api, site.headers) }.getOrNull()
        }
        return null
    }

    private fun endpoint(site: Site): String {
        val api = site.api.trim()
        return when {
            api.startsWith("adapter:json", true) ->
                api.removePrefix("adapter:json").removePrefix(":").trim().ifBlank { site.ext }
            api.startsWith("adapter:cms", true) ->
                api.removePrefix("adapter:cms").removePrefix(":").trim().ifBlank { site.ext }
            else -> api
        }
    }

    private fun looksLikeCms(url: String): Boolean =
        url.contains("provide/vod", true) || url.contains("api.php", true)

    private fun parseList(raw: String): List<Vod> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            val out = ArrayList<Vod>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                VodParser.fromJson(o)?.let { out += it }
            }
            return out
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        return VodParser.list(root)
    }

    private fun String.toHttpBuilder() = this.toHttpUrl().newBuilder()

    companion object {
        fun matches(site: Site): Boolean {
            val api = site.api.lowercase()
            if (api.startsWith("adapter:json") || api.startsWith("adapter:cms")) return true
            return false
        }
    }
}
