package com.nextmovie.core.poster

import com.nextmovie.core.match.TitleMatcher
import com.nextmovie.core.net.Http
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

/**
 * L1：B站 PGC 搜索（无需任何 Key）。
 *
 * 公开接口 `x/web-interface/search/type?search_type=media_ft`，返回剧目封面、
 * 剧情简介、地区/类型，以及每集 16:9 剧照。中国网络直达，实测约 0.3s。
 *
 * 关键约束：**不能取第一条**。同名剧很多，必须用 [TitleMatcher] 校验标题 + 年份，
 * 低于阈值一律放弃，避免把《流浪地球》的封面贴到《流浪地球2》。
 */
class BilibiliPoster(private val http: Http) {

    // B站 对高频请求会返回 412（风控）。串行化 + 最小间隔，命中风控后整体冷却。
    private val gate = Mutex()
    private var lastCallMs = 0L

    @Volatile
    private var cooldownUntilMs = 0L

    data class Hit(
        val title: String,
        val poster: String,
        val backdrop: String,
        val overview: String,
        val year: String,
        val score: String = ""
    )

    suspend fun find(title: String, year: String): Hit? {
        if (title.isBlank()) return null
        val key = title + "|" + year
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MISS_TTL_MS) return null
        // 先用去掉「第X季」的主标题搜：B站片名经常不带季数，用年份筛季度
        val base = searchTitle(title)
        var hit = fetch(base)?.let { pick(parse(it), title, year) }
        if (hit == null && base != title) {
            hit = fetch(title)?.let { pick(parse(it), title, year) }
        }
        if (hit == null) {
            misses[key] = System.currentTimeMillis()
        } else {
            misses.remove(key)
        }
        return hit
    }

    private val misses = HashMap<String, Long>()

    private suspend fun fetch(keyword: String): String? {
        if (System.currentTimeMillis() < cooldownUntilMs) return null
        return gate.withLock {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastCallMs)
            if (wait > 0) delay(wait)
            lastCallMs = System.currentTimeMillis()
            try {
                http.text(buildUrl(keyword), HEADERS)
            } catch (e: Http.HttpError) {
                if (e.code == 412 || e.code == 429) {
                    cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun searchTitle(title: String): String =
        SEASON_SUFFIX.replace(title, "").trim().ifEmpty { title }

    companion object {

        private const val ENDPOINT = "https://api.bilibili.com/x/web-interface/search/type"

        /**
         * B站 的 web 搜索对“无 cookie 的新指纹”会直接回 412，带上一个随机 buvid3 就能稳定拿到 200。
         * 这里每个进程只生成一次，不涉及任何账号信息。
         */
        private val COOKIE_HEADER = run {
            val prefix = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
            "Cookie" to "buvid3=${prefix}infoc"
        }

        private val HEADERS = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "Accept" to "application/json"
        ) + COOKIE_HEADER

        private val TAG = Regex("<[^>]+>")
        private val YEAR = Regex("((?:19|20)\\d{2})")
        private val SEASON_SUFFIX = Regex("(第[一二三四五六七八九十\\d]+季|Season\\s*\\d+|S\\d{1,2})", RegexOption.IGNORE_CASE)

        private const val MIN_INTERVAL_MS = 300L
        private const val COOLDOWN_MS = 10L * 60 * 1000
        private const val MISS_TTL_MS = 5L * 60 * 1000

        fun buildUrl(title: String): String =
            "$ENDPOINT?search_type=media_ft&page=1&keyword=" + URLEncoder.encode(title, "UTF-8")

        /**
         * 解析搜索结果。做成纯函数（不碰网络），便于单元测试与回归。
         */
        fun parse(body: String): List<Hit> {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            if (root.optInt("code", -1) != 0) return emptyList()
            val arr = root.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
            val out = ArrayList<Hit>(arr.length())
            for (i in 0 until minOf(arr.length(), 20)) {
                val o = arr.optJSONObject(i) ?: continue
                val poster = normalizeUrl(o.optString("cover", ""))
                if (poster.isEmpty()) continue
                val title = cleanText(o.optString("title", o.optString("org_title", "")))
                if (title.isEmpty()) continue
                val backdrop = firstEpisodeCover(o)
                val overview = cleanText(o.optString("desc", ""))
                    .trimEnd('。', '.', '…')
                val scoreObj = o.optJSONObject("media_score")
                val score = scoreObj?.optDouble("score", 0.0)?.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }.orEmpty()
                out += Hit(
                    title = title,
                    poster = poster,
                    backdrop = backdrop,
                    overview = overview,
                    year = yearOf(o, overview),
                    score = score
                )
            }
            return out
        }

        fun pick(hits: List<Hit>, title: String, year: String): Hit? {
            if (hits.isEmpty()) return null
            var best: Hit? = null
            var bestScore = 0.0
            for (hit in hits) {
                val s = TitleMatcher.score(title, year, hit.title, hit.year)
                if (s > bestScore) {
                    bestScore = s
                    best = hit
                }
            }
            return if (best != null && bestScore >= TitleMatcher.ACCEPT_THRESHOLD) best else null
        }

        private fun firstEpisodeCover(o: JSONObject): String {
            val eps = o.optJSONArray("eps") ?: return ""
            for (i in 0 until minOf(eps.length(), 3)) {
                val ep = eps.optJSONObject(i) ?: continue
                val cover = normalizeUrl(ep.optString("cover", ""))
                if (cover.isNotEmpty()) return cover
            }
            return ""
        }

        private fun yearOf(o: JSONObject, overview: String): String {
            val ts = o.optLong("pubtime", 0L)
            if (ts > 0L) {
                val fromTimestamp = runCatching {
                    Instant.ofEpochSecond(ts).atZone(ZoneOffset.ofHours(8)).year.toString()
                }.getOrNull()
                if (fromTimestamp != null && fromTimestamp.length == 4) return fromTimestamp
            }
            return YEAR.find(overview)?.value.orEmpty()
        }

        private fun normalizeUrl(raw: String): String {
            val url = raw.trim()
            return when {
                url.isEmpty() -> ""
                url.startsWith("//") -> "https:$url"
                else -> url
            }
        }

        private fun cleanText(raw: String): String {
            var s = TAG.replace(raw, "")
            s = s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
            return s.trim()
        }
    }
}
