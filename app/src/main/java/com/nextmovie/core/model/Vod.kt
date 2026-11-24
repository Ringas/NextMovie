package com.nextmovie.core.model

import org.json.JSONArray
import org.json.JSONObject

/** 统一影片模型，字段对应 CMS 的 vod_* 与爬虫返回的同名 JSON。 */
data class Vod(
    val id: String,
    val name: String,
    val pic: String = "",
    val remark: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val actor: String = "",
    val director: String = "",
    val content: String = "",
    val score: String = "",
    /** 横版背景图（B站剧照 / 源站自带），16:9 用 */
    val backdrop: String = "",
    val tag: String = "",
    val action: String = "",
    /** TMDB 补充信息，未命中的源保持 null */
    val tmdb: TmdbMeta? = null
) {
    val displayPic: String get() = tmdb?.posterUrl?.takeIf { it.isNotBlank() } ?: pic
    val displayBackdrop: String
        get() = tmdb?.backdropUrl?.takeIf { it.isNotBlank() }
            ?: backdrop.takeIf { it.isNotBlank() }
            ?: pic
    val displayOverview: String
        get() = com.nextmovie.core.text.TextClean.plain(content.ifBlank { tmdb?.overview.orEmpty() })
    val displayYear: String get() = year.ifBlank { tmdb?.year.orEmpty() }
}

data class TmdbMeta(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val year: String,
    val overview: String,
    val voteAverage: Double,
    val posterUrl: String,
    val backdropUrl: String
)

data class Episode(val name: String, val url: String)

data class PlayLine(val name: String, val episodes: List<Episode>)

/** 详情页所需的数据：影片信息 + 线路列表。 */
data class VodDetail(val vod: Vod, val lines: List<PlayLine>)

/** 从站点 JSON 里解析影片与播放线路。 */
object VodParser {

    private const val LINE_SEPARATOR = "$$$"
    private const val EPISODE_SEPARATOR = "#"

    fun list(root: JSONObject?): List<Vod> {
        val arr = root?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Vod>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val vod = fromJson(o) ?: continue
            out += vod
        }
        return out
    }

    fun fromJson(o: JSONObject): Vod? {
        val id = text(o, "vod_id", "id")
        val name = o.optString("vod_name", o.optString("name", "")).trim()
        if (id.isEmpty() && name.isEmpty()) return null
        return Vod(
            id = id.ifEmpty { name },
            name = name,
            pic = normalizePic(text(o, "vod_pic", "pic")),
            remark = text(o, "vod_remarks", "remark"),
            year = text(o, "vod_year", "year"),
            area = text(o, "vod_area", "area"),
            typeName = text(o, "vod_class", "type_name"),
            actor = text(o, "vod_actor", "actor"),
            director = text(o, "vod_director", "director"),
            // 简介字段各家不统一：CMS 用 vod_content，老 spider 用 des，Guard 系插件常见 vod_blurb。
            // 少认一个键，详情页就整段空白，所以这里全部兜一遍。
            content = text(o, "vod_content", "vod_blurb", "vod_desc", "des", "content", "brief"),
            score = text(o, "vod_score", "score"),
            tag = text(o, "vod_tag", "tag"),
            action = text(o, "action")
        )
    }

    /**
     * 生态里 `vod_id` 有时不是字符串而是数组（`["/voddetail/149953"]`），
     * 直接 optString 会拿到 `"[/voddetail/149953]"`，拼进 URL 就变成 host[ 开头，必挂。
     * 这里统一取第一个标量值。
     */
    private fun text(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            val s = when (v) {
                is org.json.JSONArray -> v.optString(0, "")
                is JSONObject -> v.optString("id", v.optString("value", ""))
                else -> v.toString()
            }.trim()
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    /** `vod_play_from` / `vod_play_url` 是生态里最稳定的两个字段。 */
    fun playLines(o: JSONObject): List<PlayLine> {
        val from = text(o, "vod_play_from", "play_from")
        val urls = text(o, "vod_play_url", "play_url")
        if (urls.isBlank()) return emptyList()
        val fromNames = if (from.isBlank()) List(urls.split(LINE_SEPARATOR).size) { "线路${it + 1}" } else from.split(LINE_SEPARATOR)
        val urlGroups = urls.split(LINE_SEPARATOR)
        val out = ArrayList<PlayLine>(urlGroups.size)
        for ((index, group) in urlGroups.withIndex()) {
            val name = fromNames.getOrElse(index) { "线路${index + 1}" }
            val episodes = ArrayList<Episode>()
            for (raw in group.split(EPISODE_SEPARATOR)) {
                if (raw.isBlank()) continue
                val parts = raw.split('$', limit = 2)
                val epName = if (parts.size == 2) parts[0].trim() else "正片"
                val epUrl = (if (parts.size == 2) parts[1] else parts[0]).trim()
                if (epUrl.isEmpty()) continue
                episodes += Episode(epName.ifEmpty { "第${episodes.size + 1}集" }, epUrl)
            }
            if (episodes.isNotEmpty()) out += PlayLine(name, episodes)
        }
        return out
    }

    private fun normalizePic(raw: String): String {
        val value = raw.trim()
        return when {
            value.isEmpty() -> ""
            value.startsWith("//") -> "https:$value"
            else -> value
        }
    }

    fun toJsonList(vods: List<Vod>): JSONObject {
        val arr = JSONArray()
        vods.forEach { v ->
            arr.put(
                JSONObject()
                    .put("vod_id", v.id)
                    .put("vod_name", v.name)
                    .put("vod_pic", v.pic)
                    .put("vod_remarks", v.remark)
                    .put("vod_year", v.year)
                    .put("vod_area", v.area)
                    .put("vod_class", v.typeName)
                    .put("vod_actor", v.actor)
                    .put("vod_director", v.director)
                    .put("vod_content", v.content)
                    .put("vod_tag", v.tag)
                    .put("action", v.action)
            )
        }
        return JSONObject().put("list", arr)
    }
}

/** 蜘蛛返回的播放结果。 */
data class PlayResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 0 = 直链，1 = 需要嗅探/解析（v0 只支持直链） */
    val parse: Int = 0
) {
    companion object {
        fun fromJson(root: JSONObject?): PlayResult? {
            if (root == null) return null
            val url = listOf("url", "playUrl", "play_url", "video")
                .firstNotNullOfOrNull { root.optString(it, "").trim().takeIf { s -> s.isNotEmpty() } }
                ?: return null
            val headers = LinkedHashMap<String, String>()
            when (val h = root.opt("header")) {
                // 两种写法都有：对象形式，或者把整段 header JSON 塞成字符串
                is JSONObject -> h.keys().forEach { k ->
                    h.optString(k, "").takeIf { it.isNotBlank() }?.let { v -> headers[k] = v }
                }
                is String -> runCatching { JSONObject(h) }.getOrNull()?.keys()?.forEach { k ->
                    runCatching { JSONObject(h) }.getOrNull()?.optString(k, "")
                        ?.takeIf { it.isNotBlank() }?.let { v -> headers[k] = v }
                }
            }
            // CatVod 生态里 UA 是单独一个字段，不写进 header；漏掉它很多 CDN 直接 403
            root.optString("ua", "").trim().takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
            val parse = when (val p = root.opt("parse")) {
                is Number -> p.toInt()
                is String -> p.trim().toIntOrNull() ?: 0
                else -> 0
            }
            return PlayResult(url, headers, parse)
        }
    }
}
