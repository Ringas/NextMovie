package com.nextmovie.core.cms

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayLine
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * CMS 型站点（type 0 = XML，type 1/4 = JSON）客户端。
 * 这类站点不需要任何插件，只靠 `ac=` 系列 query 就能工作，适合作为 v0 的默认通路。
 */
class CmsSiteClient(
    private val http: Http,
    private val site: Site
) {

    suspend fun home(): List<Vod> = query(mapOf("ac" to "videolist", "pg" to "1"))

    /**
     * 分类导航：苹果CMS 的 `ac=list` 会带一个 `class` 数组（type_id / type_name）。
     * 拿不到就返回空，首页退化成「只有最近更新」，不阻塞主流程。
     */
    suspend fun categories(): List<Pair<String, String>> {
        val raw = request(mapOf("ac" to "list"))
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
        val params = linkedMapOf("ac" to "videolist", "pg" to page.toString())
        if (tid.isNotBlank()) params["t"] = tid
        return query(params)
    }

    suspend fun search(keyword: String): List<Vod> {
        val params = linkedMapOf("wd" to keyword, "ac" to "videolist", "pg" to "1")
        return query(params)
    }

    suspend fun detail(id: String): VodDetail? {
        val raw = request(mapOf("ac" to "videolist", "ids" to id))
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root != null) {
            val list = root.optJSONArray("list")
            val item = list?.optJSONObject(0) ?: return null
            val vod = VodParser.fromJson(item) ?: return null
            return VodDetail(vod, VodParser.playLines(item))
        }
        val vod = parseXmlVideos(raw).firstOrNull() ?: return null
        return VodDetail(vod, emptyList())
    }

    private suspend fun query(params: Map<String, String>): List<Vod> {
        val raw = request(params)
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root != null) return VodParser.list(root)
        return parseXmlVideos(raw)
    }

    private suspend fun request(params: Map<String, String>): String {
        val separator = if (site.api.contains("?")) "&" else "?"
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return http.text(site.api + separator + query, site.headers, referer = site.api)
    }

    /** 极简 XML 兜底解析：v0 不引入 XML 依赖，按 `<video>` 分块抽字段。 */
    private fun parseXmlVideos(xml: String): List<Vod> {
        val blocks = Regex("<video>(.*?)</video>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        val out = ArrayList<Vod>()
        for (block in blocks) {
            val body = block.groupValues[1]
            fun tag(name: String): String {
                val m = Regex("<$name>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</$name>", RegexOption.DOT_MATCHES_ALL)
                    .find(body) ?: return ""
                return m.groupValues[1].trim()
            }
            val name = tag("vod_name").ifEmpty { tag("name") }
            if (name.isEmpty()) continue
            out += Vod(
                id = tag("vod_id").ifEmpty { tag("id") }.ifEmpty { name },
                name = name,
                pic = tag("vod_pic"),
                remark = tag("vod_remarks"),
                year = tag("vod_year"),
                area = tag("vod_area"),
                typeName = tag("vod_class"),
                actor = tag("vod_actor"),
                director = tag("vod_director"),
                content = tag("vod_content").ifEmpty { tag("des") }
            )
        }
        return out
    }
}
