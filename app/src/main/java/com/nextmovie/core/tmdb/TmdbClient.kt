package com.nextmovie.core.tmdb

import com.nextmovie.core.model.TmdbMeta
import com.nextmovie.core.model.Vod
import com.nextmovie.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * TMDB 增强层。
 *
 * 设计原则：
 *  - 只在**确信匹配**时替换海报，否则一律回退源站 vod_pic；
 *  - 语言固定 zh-CN，先精确（标题归一化 + 年份），再模糊（相似度阈值）；
 *  - Key 由用户自填，不内置；线路可多选，本地测速 + 并发竞速，谁快用谁。
 *
 * 线路形态：
 *  - 官方 `https://api.tmdb.org/3`（境内实测可直连）与 `https://api.themoviedb.org/3`（常被阻断）
 *  - 公共反代 `https://tmdb.orzabc.com/get`（自带 Key，可免 Token；第三方服务，可关闭）
 *  - 自定义镜像：填 API 前缀（需包含 `/3` 或 `/get`），图片默认跟随同域 `/img` 规则需自行保证
 */
class TmdbClient(private val http: Http) {

    data class Route(
        val label: String,
        val apiBase: String,
        val imageBase: String,
        val needsToken: Boolean
    )

    var token: String = ""
    var language: String = "zh-CN"

    /** 是否允许使用公共反代（无 Token 时也能出数据）。关闭后只走需要 Token 的官方线路。 */
    var publicMirrorEnabled: Boolean = true

    /** 多线路并发竞速：谁先返回有效 JSON 就用谁，其余请求取消。 */
    var raceEnabled: Boolean = true

    /** 图片走免费 CDN 代理（image.tmdb.org 被干扰时可用），默认关。 */
    var imageProxy: Boolean = false

    /** 自定义镜像列表（API 前缀，可多个），与内置线路一起参与测速/竞速。 */
    var customBases: List<String> = emptyList()

    /** auto=全部；fast=只用官方备用域；global=只用官方主域；custom=自定义优先 + 内置兜底。 */
    var mode: String = "auto"

    var routes: List<Route> = emptyList()

    var activeRoute: Route? = null
        private set

    private val latency = LinkedHashMap<String, Long>()

    init {
        routes = buildRoutes()
    }

    fun isConfigured(): Boolean = token.isNotBlank() || (publicMirrorEnabled && mirrorRoute() != null)

    /** 当前可用线路（按 Token 权限过滤）。 */
    fun availableRoutes(): List<Route> = buildRoutes().filter { it.needsToken.not() || token.isNotBlank() }

    private fun buildRoutes(): List<Route> {
        val list = ArrayList<Route>()
        val customs = customBases.map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }
            .distinct()
            .map { base ->
                val image = if (base.contains("/get")) MIRROR_IMAGE_BASE else "https://image.tmdb.org/t/p"
                Route("自定义", base, image, needsToken = token.isNotBlank())
            }

        when (mode) {
            "fast" -> Unit
            "global" -> Unit
            else -> customs.forEach { list += it }
        }
        if (mode != "global") list += Route("官方备用域", FAST_BASE, "https://image.tmdb.org/t/p", needsToken = true)
        if (mode != "fast") list += Route("官方主域", GLOBAL_BASE, "https://image.tmdb.org/t/p", needsToken = true)
        if (mode != "fast" && mode != "global" && publicMirrorEnabled) {
            list += Route("公共反代", MIRROR_BASE, MIRROR_IMAGE_BASE, needsToken = false)
        }
        return list.distinctBy { it.apiBase }
    }

    private fun mirrorRoute(): Route? =
        if (publicMirrorEnabled) Route("公共反代（免 Token）", MIRROR_BASE, MIRROR_IMAGE_BASE, false) else null

    fun refreshRoutes() {
        routes = buildRoutes()
        if (activeRoute?.let { r -> routes.any { it.apiBase == r.apiBase } } != true) activeRoute = null
    }

    suspend fun enrich(vod: Vod): Vod {
        if (!isConfigured()) return vod
        val meta = lookup(vod.name, vod.displayYear) ?: return vod
        return vod.copy(
            tmdb = meta,
            pic = meta.posterUrl.ifBlank { vod.pic },
            backdrop = meta.backdropUrl.ifBlank { vod.backdrop },
            content = vod.content.ifBlank { meta.overview },
            year = vod.year.ifBlank { meta.year },
            score = vod.score.ifBlank {
                if (meta.voteAverage > 0) String.format(java.util.Locale.ROOT, "%.1f", meta.voteAverage) else ""
            }
        )
    }

    private suspend fun lookup(title: String, year: String): TmdbMeta? {
        if (title.isBlank()) return null
        val candidates = search(title, year)
        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { it.score }
        if (best == null || best.score < TmdbMatcher.ACCEPT_THRESHOLD) return null

        return detail(best.id, best.mediaType, best.score)
    }

    private data class Candidate(
        val id: Int,
        val mediaType: String,
        val title: String,
        val year: String,
        val score: Double
    )

    private suspend fun search(title: String, year: String): List<Candidate> {
        val root = request(
            "/search/multi",
            mapOf(
                "query" to title,
                "language" to language,
                "include_adult" to "false",
                "page" to "1"
            )
        ) ?: return emptyList()

        val arr = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.optJSONObject(i) ?: continue
            val mediaType = o.optString("media_type", "")
            if (mediaType != "movie" && mediaType != "tv") continue
            val name = o.optString("title", o.optString("name", ""))
            val release = o.optString("release_date", o.optString("first_air_date", ""))
            val candidateYear = release.take(4)
            out += Candidate(
                id = o.optInt("id"),
                mediaType = mediaType,
                title = name,
                year = candidateYear,
                score = TmdbMatcher.score(title, year, name, candidateYear)
            )
        }
        return out
    }

    private suspend fun detail(id: Int, mediaType: String, score: Double): TmdbMeta? {
        val root = request("/$mediaType/$id", mapOf("language" to language)) ?: return null
        val title = root.optString("title", root.optString("name", ""))
        val year = root.optString("release_date", root.optString("first_air_date", "")).take(4)
        return TmdbMeta(
            tmdbId = id,
            mediaType = mediaType,
            title = title,
            year = year,
            overview = root.optString("overview", "").trim(),
            voteAverage = root.optDouble("vote_average", 0.0),
            posterUrl = imageUrl(root.optString("poster_path", ""), "w500"),
            backdropUrl = imageUrl(root.optString("backdrop_path", ""), "w1280")
        )
    }

    private suspend fun request(path: String, params: Map<String, String>): JSONObject? {
        val available = availableRoutes()
        if (available.isEmpty()) return null
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        val ordered = orderRoutes(available)
        if (ordered.isEmpty()) return null

        val json = if (raceEnabled && ordered.size > 1) race(path, query, ordered)
        else sequential(path, query, ordered)
        return json
    }

    /** 当前主用线路排第一，其余按最近测速升序。 */
    private fun orderRoutes(list: List<Route>): List<Route> {
        val head = activeRoute?.let { a -> list.firstOrNull { it.apiBase == a.apiBase } }
        val rest = list.filter { it != head }.sortedBy { latency[it.apiBase] ?: Long.MAX_VALUE }
        return listOfNotNull(head) + rest
    }

    private fun urlFor(route: Route, path: String, query: String) =
        route.apiBase.trimEnd('/') + path + "?" + query

    private fun headersFor(route: Route): Map<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        // 公共反代自带 Key，带 Authorization 反而可能被拒
        if (route.needsToken && token.isNotBlank()) h["Authorization"] = "Bearer $token"
        return h
    }

    private suspend fun sequential(path: String, query: String, routes: List<Route>): JSONObject? {
        for (route in routes) {
            val t0 = System.currentTimeMillis()
            val json = runCatching {
                JSONObject(http.text(urlFor(route, path, query), headersFor(route), callTimeoutMs = 6000))
            }.getOrNull() ?: continue
            latency[route.apiBase] = System.currentTimeMillis() - t0
            activeRoute = route
            return json
        }
        return null
    }

    /** 并发竞速：第一个成功返回的线路胜出，其余协程取消。 */
    private suspend fun race(path: String, query: String, routes: List<Route>): JSONObject? = coroutineScope {
        val winner = Channel<Pair<Route, JSONObject>>(Channel.CONFLATED)
        val started = System.currentTimeMillis()
        val jobs = routes.map { route ->
            launch(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val json = runCatching {
                    JSONObject(http.text(urlFor(route, path, query), headersFor(route), callTimeoutMs = 6000))
                }.getOrNull()
                if (json != null) {
                    latency[route.apiBase] = System.currentTimeMillis() - t0
                    winner.trySend(route to json)
                }
            }
        }
        val first = withTimeoutOrNull(6500) { winner.receive() }
        jobs.forEach { it.cancel() }
        if (first != null) {
            activeRoute = first.first
            android.util.Log.d(
                "NextMovie",
                "tmdb race winner=${first.first.label} ${first.first.apiBase} in ${System.currentTimeMillis() - started}ms"
            )
        }
        first?.second
    }

    /**
     * 本地测速：并发探测每条线路（无 Token 时官方域返回 401，同样说明网络可达）。
     * 结果按耗时升序，并把最快的线路设为当前主用。
     */
    suspend fun probe(): List<Triple<String, Boolean, String>> = coroutineScope {
        val list = availableRoutes()
        val results = list.map { route ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val outcome = runCatching {
                    http.text(route.apiBase + "/configuration", headersFor(route), callTimeoutMs = 6000)
                    true to "可通"
                }.getOrElse { e ->
                    val code = (e as? Http.HttpError)?.code
                    when {
                        code == 401 -> true to "可通（无 Token）"
                        code == 403 -> false to "被拒绝 403"
                        code != null -> false to "HTTP $code"
                        else -> false to (e.message ?: "不可达")
                    }
                }
                val ms = System.currentTimeMillis() - t0
                if (outcome.first) latency[route.apiBase] = ms
                Triple("${route.label} ${route.apiBase}", outcome.first, outcome.second + " · ${ms}ms")
            }
        }.awaitAll().sortedBy { latency[it.first.substringAfterLast(' ')] ?: Long.MAX_VALUE }
        results.firstOrNull { it.second }?.let { hit ->
            activeRoute = list.firstOrNull { "${it.label} ${it.apiBase}" == hit.first } ?: activeRoute
        }
        results
    }

    fun imageUrl(path: String?, size: String): String {
        if (path.isNullOrBlank()) return ""
        val base = activeRoute?.imageBase ?: "https://image.tmdb.org/t/p"
        val direct = base.trimEnd('/') + "/" + size + "/" + path.trimStart('/')
        if (!imageProxy) return direct
        val width = when (size) {
            "w92" -> 92; "w154" -> 154; "w185" -> 185; "w300" -> 300
            "w342" -> 342; "w500" -> 500; "w780" -> 780; "w1280" -> 1280
            else -> 780
        }
        return "https://wsrv.nl/?url=" + URLEncoder.encode(direct, "UTF-8") + "&w=$width&output=jpg"
    }

    companion object {
        /** 官方备用域名，境内实测可直连。 */
        const val FAST_BASE = "https://api.tmdb.org/3"

        /** 官方主域名，境内常被阻断。 */
        const val GLOBAL_BASE = "https://api.themoviedb.org/3"

        /** 公共反代：`/get/` 对应官方 `/3/`，`/img/` 对应 `image.tmdb.org`。 */
        const val MIRROR_BASE = "https://tmdb.orzabc.com/get"
        const val MIRROR_IMAGE_BASE = "https://tmdb.orzabc.com/img/t/p"
    }
}
