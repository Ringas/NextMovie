package com.nextmovie.core.spider.native

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.Episode
import com.nextmovie.core.model.PlayLine
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AppTT / AppsX 家族原生适配。
 *
 * 协议（公开可观测）：
 * - POST multipart 到 `{api}/v3/...`
 * - 字段含 `sign = MD5(signKey + unixTimestamp)`、`timestamp`
 * - 路径：`/v3/type/top_type`、`/v3/type/tj_vod`、`/v3/home/type_search`、
 *   `/v3/home/vod_details`、`/v3/home/search`
 *
 * 配置来源：`site.ext` 为 JSON（host/api/signKey）或 HTTP 地址拉取 JSON；
 * 亦可从 jar 字符串启发式补全（[GuardStringScanner]）。
 */
class AppTtNativeSpider(private val http: Http) : NativeSpider {

    private data class Cfg(
        val api: String,
        val origin: String,
        val signKey: String,
        val userAgent: String
    )

    private val cfgCache = ConcurrentHashMap<String, Cfg>()

    override fun matches(site: Site): Boolean {
        val api = site.api
        return api.contains("AppTT", true) ||
            api.contains("AppsX", true) ||
            api.contains("AppSx", true) ||
            api.equals("csp_AppYS", ignoreCase = true)
    }

    override suspend fun home(site: Site): List<Vod> {
        val cfg = resolveCfg(site)
        val types = post(cfg, "/v3/type/top_type", emptyMap())
        val arr = types.optJSONArray("list") ?: types.optJSONArray("data")
        val out = ArrayList<Vod>()
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 8)) {
                val typeId = arr.optJSONObject(i)?.optString("type_id")
                    ?: arr.optJSONObject(i)?.optString("id") ?: continue
                val page = post(
                    cfg, "/v3/type/tj_vod",
                    mapOf("type_id" to typeId, "page" to "1", "limit" to "12")
                )
                parseList(page, cfg.origin).forEach { out += it }
            }
        }
        if (out.isEmpty()) {
            val page = post(cfg, "/v3/type/tj_vod", mapOf("page" to "1", "limit" to "24"))
            out += parseList(page, cfg.origin)
        }
        return out.distinctBy { it.id }
    }

    override suspend fun categories(site: Site): List<Pair<String, String>> {
        val cfg = resolveCfg(site)
        val root = post(cfg, "/v3/type/top_type", emptyMap())
        val arr = root.optJSONArray("list") ?: root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("type_id", o.optString("id"))
            val name = o.optString("type_name", o.optString("name"))
            if (id.isNotBlank() && name.isNotBlank()) out += id to name
        }
        return out
    }

    override suspend fun category(site: Site, tid: String, page: Int): List<Vod> {
        val cfg = resolveCfg(site)
        val root = post(
            cfg, "/v3/home/type_search",
            mapOf("type_id" to tid, "limit" to "12", "page" to page.toString())
        )
        return parseList(root, cfg.origin)
    }

    override suspend fun detail(site: Site, id: String): VodDetail? {
        val cfg = resolveCfg(site)
        val vodId = id.split(",").first().trim()
        val root = post(cfg, "/v3/home/vod_details", mapOf("vod_id" to vodId))
        val content = stripHtml(
            root.optString("vod_content").ifBlank { root.optString("vod_blurb") }
        )
        val vod = Vod(
            id = vodId,
            name = root.optString("vod_name", root.optString("name")),
            pic = absUrl(root.optString("vod_pic"), cfg.origin),
            year = root.optString("vod_year").take(4),
            area = root.optString("vod_area"),
            typeName = root.optString("type_name"),
            content = content,
            score = root.optString("vod_score"),
            remark = root.optString("vod_remarks")
        )
        val playList = root.optJSONArray("vod_play_list")
        val lines = ArrayList<PlayLine>()
        if (playList != null) {
            for (i in 0 until playList.length()) {
                val line = playList.optJSONObject(i) ?: continue
                val flag = line.optString("flag", line.optString("name", "线路${i + 1}"))
                val urls = line.optJSONArray("urls") ?: continue
                val eps = ArrayList<Episode>()
                for (j in 0 until urls.length()) {
                    val u = urls.optJSONObject(j) ?: continue
                    val name = u.optString("name", u.optString("title", "第${j + 1}集"))
                    val url = u.optString("url", u.optString("play_url"))
                    if (url.isNotBlank()) eps += Episode(name, url)
                }
                if (eps.isNotEmpty()) lines += PlayLine(flag, eps)
            }
        }
        // 兼容 vod_play_from / vod_play_url
        if (lines.isEmpty()) {
            lines += VodParser.playLines(root)
        }
        return VodDetail(vod, lines)
    }

    override suspend fun search(site: Site, key: String): List<Vod> {
        val cfg = resolveCfg(site)
        val kw = key.trim()
        if (kw.isEmpty()) return emptyList()
        val root = post(cfg, "/v3/home/search", mapOf("keyword" to kw))
        return parseList(root, cfg.origin).filter { it.name.contains(kw, true) }
    }

    override suspend fun play(site: Site, flag: String, id: String): PlayResult? {
        // AppTT 详情里的 url 多为直链或需二次解析的页；先当直链
        if (id.startsWith("http://") || id.startsWith("https://")) {
            return PlayResult(url = id, parse = 0)
        }
        return PlayResult(url = id, parse = 1)
    }

    private fun parseList(root: JSONObject, origin: String): List<Vod> {
        val arr = root.optJSONArray("list") ?: root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Vod>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("vod_id", o.optString("id"))
            val name = o.optString("vod_name", o.optString("name"))
            if (id.isBlank() || name.isBlank()) continue
            out += Vod(
                id = id,
                name = name,
                pic = absUrl(o.optString("vod_pic", o.optString("pic")), origin),
                year = o.optString("vod_year").take(4),
                remark = o.optString("vod_remarks", o.optString("remarks")),
                score = o.optString("vod_score")
            )
        }
        return out
    }

    private suspend fun resolveCfg(site: Site): Cfg {
        cfgCache[site.runtimeKey]?.let { return it }
        val fromExt = parseExt(site.ext)
        var api = fromExt["api"] ?: fromExt["host"] ?: fromExt["url"] ?: ""
        var signKey = fromExt["signKey"] ?: fromExt["sign"] ?: fromExt["key"] ?: ""
        var ua = fromExt["ua"] ?: fromExt["userAgent"] ?: Http.UA

        if (api.isBlank() && site.ext.startsWith("http")) {
            val body = runCatching { http.text(site.ext, site.headers) }.getOrNull().orEmpty()
            val remote = parseExt(body)
            api = remote["api"] ?: remote["host"] ?: remote["url"] ?: site.ext.trimEnd('/')
            signKey = signKey.ifBlank { remote["signKey"] ?: remote["sign"] ?: "" }
            ua = remote["ua"] ?: ua
        }

        // jar 扫描兜底
        if ((api.isBlank() || signKey.isBlank()) && site.jar.isNotBlank()) {
            // 仅当已有本地缓存 jar 时扫描；路径由调用方可选注入——此处只记日志
            android.util.Log.i("NativeAppTT", "cfg partial api=${api.isNotBlank()} key=${signKey.isNotBlank()}")
        }

        if (api.isBlank()) {
            throw IllegalStateException("AppTT 缺少 api/host（检查站点 ext）")
        }
        if (signKey.isBlank()) {
            // 部分源 signKey 在固定位置；无 key 时仍尝试空签名会失败，明确报错
            throw IllegalStateException("AppTT 缺少 signKey（检查站点 ext 或换用 JAR 路径）")
        }
        api = api.trimEnd('/')
        if (!api.startsWith("http")) api = "https://$api"
        val origin = api.substringBefore("://") + "://" + api.substringAfter("://").substringBefore('/')
        val cfg = Cfg(api = api, origin = origin, signKey = signKey, userAgent = ua)
        cfgCache[site.runtimeKey] = cfg
        return cfg
    }

    private fun parseExt(raw: String): Map<String, String> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyMap()
        if (t.startsWith("{")) {
            val o = runCatching { JSONObject(t) }.getOrNull() ?: return emptyMap()
            val map = HashMap<String, String>()
            o.keys().forEach { k ->
                val v = o.opt(k)
                if (v != null && v !is JSONObject && v !is org.json.JSONArray) {
                    map[k] = v.toString()
                }
            }
            // 嵌套 data
            o.optJSONObject("data")?.keys()?.forEach { k ->
                val v = o.getJSONObject("data").opt(k)
                if (v != null) map.putIfAbsent(k, v.toString())
            }
            return map
        }
        if (t.startsWith("http")) return mapOf("api" to t)
        return emptyMap()
    }

    private suspend fun post(cfg: Cfg, path: String, params: Map<String, String>): JSONObject {
        val stamp = (System.currentTimeMillis() / 1000L).toString()
        val sign = md5(cfg.signKey + stamp)
        val fields = LinkedHashMap<String, String>()
        fields["sign"] = sign
        fields["timestamp"] = stamp
        params.forEach { (k, v) -> fields[k] = v }
        val headers = mapOf(
            "User-Agent" to cfg.userAgent,
            "Accept" to "application/json"
        )
        // 使用 form-urlencoded（与 OkHttp FormBody 一致）；多数 AppTT 后端同时接受
        val text = http.text(
            url = cfg.api + path,
            headers = headers,
            ua = cfg.userAgent,
            form = fields
        )
        if (looksLikeChallenge(text)) {
            throw IllegalStateException("源站人机验证，原生路径暂不可用")
        }
        return runCatching { JSONObject(text) }.getOrElse {
            // 有的包一层 data
            throw IllegalStateException("AppTT 响应非 JSON")
        }.let { root ->
            root.optJSONObject("data") ?: root
        }
    }

    private fun looksLikeChallenge(body: String): Boolean =
        body.contains("SafeLineChallenge", true) ||
            body.contains("人机验证") ||
            body.contains("Checking your browser", true)

    private fun md5(s: String): String {
        val dig = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }
    }

    private fun absUrl(pic: String, origin: String): String {
        val p = pic.trim()
        if (p.isEmpty()) return ""
        if (p.startsWith("http")) return p
        if (p.startsWith("//")) return "https:$p"
        return origin.trimEnd('/') + "/" + p.trimStart('/')
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
