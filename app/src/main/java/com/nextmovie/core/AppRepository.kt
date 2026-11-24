package com.nextmovie.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nextmovie.core.cms.CmsSiteClient
import com.nextmovie.core.config.ConfigBundle
import com.nextmovie.core.config.ConfigRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.config.SiteSelector
import com.nextmovie.core.history.HistoryStore
import com.nextmovie.core.match.TitleMatcher
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.net.Http
import com.nextmovie.core.net.SmartDns
import com.nextmovie.core.net.SharePageResolver
import com.nextmovie.core.poster.BilibiliPoster
import com.nextmovie.core.poster.PosterCache
import com.nextmovie.core.poster.PosterResolver
import com.nextmovie.core.spider.SpiderHost
import com.nextmovie.core.tmdb.TmdbClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 应用门面：配置、站点、数据请求、TMDB 增强的唯一入口。
 * UI 层只依赖本类，不直接触碰协议/插件细节。
 */
class AppRepository(private val context: Context) {

    val http = Http()
    private val configRepository = ConfigRepository(http)
    private val spiderHost = SpiderHost(context, http)
    val tmdb = TmdbClient(http)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var parses: List<com.nextmovie.core.config.Parse> = emptyList()

    private val lists = com.nextmovie.core.cache.RequestCache<String, List<Vod>>(120_000, 48, cacheIf = { it.isNotEmpty() })
    private val details = com.nextmovie.core.cache.RequestCache<String, VodDetail?>(120_000, 128, cacheIf = { it != null && it.lines.isNotEmpty() })
    private val homeSnapshots = com.nextmovie.core.cache.HomeSnapshotStore(File(context.filesDir, "home-snapshots"))
    private val warmSites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val prefetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val prefetchSlot = java.util.concurrent.Semaphore(1)
    private val configGeneration = java.util.concurrent.atomic.AtomicLong()
    private val snapshot = File(context.filesDir, "config-snapshot.json")

    val history = HistoryStore(File(context.filesDir, "history.json"))

    private val posterCache = PosterCache(File(context.filesDir, "poster-cache"))
    val poster = PosterResolver(tmdb, BilibiliPoster(http), posterCache)

    /** 是否允许用 B站 PGC 兜底补图（无需 Key，默认开） */
    var posterEnhance: Boolean = prefs.getBoolean(KEY_POSTER_ENHANCE, true)
        private set

    /** 站点列表与当前站点用 Compose 状态承载：配置加载完成后首页会自动切换，不需要额外的回调。 */
    var sites: List<Site> by mutableStateOf(emptyList())
        private set

    var currentSite: Site? by mutableStateOf(null)
        private set

    init {
        tmdb.token = prefs.getString(KEY_TMDB_TOKEN, "").orEmpty()
        applyTmdbRoute()
        applyDnsRoute()
    }

    /** DNS 解析策略：auto（默认，抗污染）/ system / cloudflare / google / aliyun / custom */
    fun dnsMode(): String = prefs.getString(KEY_DNS_MODE, SmartDns.MODE_AUTO).orEmpty()
        .ifBlank { SmartDns.MODE_AUTO }

    fun dnsCustom(): String = prefs.getString(KEY_DNS_CUSTOM, "").orEmpty()

    fun setDns(mode: String, custom: String? = null) {
        prefs.edit().putString(KEY_DNS_MODE, mode).apply()
        if (custom != null) prefs.edit().putString(KEY_DNS_CUSTOM, custom.trim()).apply()
        applyDnsRoute()
    }

    fun applyDnsRoute() {
        SmartDns.applyConfig(dnsMode(), dnsCustom())
    }

    /** TMDB 加速线路：默认「自动」= 内置多线路 + 公共反代，本地并发测速/竞速。 */
    fun applyTmdbRoute() {
        val mode = prefs.getString(KEY_TMDB_ROUTE, "auto").orEmpty().ifBlank { "auto" }
        val customs = prefs.getString(KEY_TMDB_CUSTOM, "").orEmpty()
            .split(Regex("[,\\n]"))
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }
        tmdb.mode = mode
        tmdb.customBases = customs
        tmdb.publicMirrorEnabled = prefs.getBoolean(KEY_TMDB_MIRROR, true)
        tmdb.imageProxy = prefs.getBoolean(KEY_TMDB_IMG_PROXY, false)
        tmdb.raceEnabled = prefs.getBoolean(KEY_TMDB_RACE, true)
        tmdb.refreshRoutes()
    }

    fun tmdbRouteMode(): String = prefs.getString(KEY_TMDB_ROUTE, "auto").orEmpty().ifBlank { "auto" }

    fun tmdbCustomBase(): String = prefs.getString(KEY_TMDB_CUSTOM, "").orEmpty()

    fun setTmdbRoute(mode: String, customBase: String = tmdbCustomBase()) {
        prefs.edit()
            .putString(KEY_TMDB_ROUTE, mode)
            .putString(KEY_TMDB_CUSTOM, customBase.trim())
            .apply()
        applyTmdbRoute()
    }

    fun setTmdbImageProxy(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TMDB_IMG_PROXY, enabled).apply()
        tmdb.imageProxy = enabled
    }

    fun tmdbImageProxyEnabled(): Boolean = prefs.getBoolean(KEY_TMDB_IMG_PROXY, false)

    fun tmdbMirrorEnabled(): Boolean = prefs.getBoolean(KEY_TMDB_MIRROR, true)

    fun setTmdbMirror(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TMDB_MIRROR, enabled).apply()
        tmdb.publicMirrorEnabled = enabled
        tmdb.refreshRoutes()
    }

    fun tmdbRaceEnabled(): Boolean = prefs.getBoolean(KEY_TMDB_RACE, true)

    fun setTmdbRace(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TMDB_RACE, enabled).apply()
        tmdb.raceEnabled = enabled
    }

    /** 启动时后台测速一次，把最快的线路排到前面（失败静默，不影响使用）。 */
    suspend fun tuneTmdbSilently() {
        if (tmdb.token.isBlank()) return
        runCatching { tmdb.probe() }
    }

    suspend fun probeTmdb(): List<Triple<String, Boolean, String>> = tmdb.probe()

    fun configUrl(): String = prefs.getString(KEY_CONFIG_URL, "").orEmpty()

    fun setPosterEnhance(enabled: Boolean) {
        posterEnhance = enabled
        prefs.edit().putBoolean(KEY_POSTER_ENHANCE, enabled).apply()
    }

    fun clearPosterCache() = posterCache.clear()

    fun posterCacheBytes(): Long = posterCache.sizeBytes()

    fun configureTmdb(token: String) {
        tmdb.token = token.trim()
        prefs.edit().putString(KEY_TMDB_TOKEN, tmdb.token).apply()
    }

    /** 按 key 找回站点对象（播放器自动连播时用来还原当前站点） */
    fun siteByKey(key: String): Site? = sites.firstOrNull { it.key == key }

    /** 站点分类导航（CMS 走 ac=list 的 class 数组；插件站点暂不提供） */
    suspend fun connectPluginHost(site: Site) {
        if (site.isSpider && site.isPlayable) spiderHost.preconnect()
    }

    suspend fun categories(site: Site): List<Pair<String, String>> =
        (if (site.isCms) CmsSiteClient(http, site).categories() else spiderHost.categories(site)).also { warmSites.add(site.runtimeKey) }

    fun selectSite(key: String) {
        currentSite = sites.firstOrNull { it.key == key } ?: currentSite
        currentSite?.let {
            prefs.edit().putString(KEY_SITE_KEY, it.key).putBoolean(KEY_SITE_MANUAL, true).apply()
        }
    }

    suspend fun loadConfig(url: String): Int {
        val generation = configGeneration.incrementAndGet()
        val bundle: ConfigBundle = try {
            if (url.trimStart().startsWith("{")) configRepository.loadText(url.trim())
            else configRepository.load(url.trim().replace("\\:", ":").trimEnd('\\'))
        } catch (e: Throwable) {
            recordConfigResult(false, "✗ 加载失败：" + (e.message ?: e.javaClass.simpleName))
            throw e
        }
        if (generation != configGeneration.get()) throw kotlinx.coroutines.CancellationException("新的配置已替换此请求")
        sites = bundle.sites
        parses = bundle.parses
        withContext(kotlinx.coroutines.Dispatchers.IO) { saveConfigSnapshot(url.trim()) }
        runCatching {
            val byType = sites.groupingBy { it.type }.eachCount()
            android.util.Log.d("NextMovieSites", "types=$byType")
            sites.forEach { android.util.Log.d("NextMovieSites", "${it.type}|${it.key}|${it.name}|${it.api}") }
        }
        prefs.edit().putString(KEY_CONFIG_URL, url.trim()).apply()
        val savedKey = prefs.getString(KEY_SITE_KEY, "").orEmpty()
        val manual = prefs.getBoolean(KEY_SITE_MANUAL, false)
        currentSite = SiteSelector.pick(sites, savedKey, manual)
        currentSite?.let { prefs.edit().putString(KEY_SITE_KEY, it.key).apply() }
        recordConfigResult(true, "✓ 已加载 ${sites.size} 个站点")
        return sites.size
    }

    /** 最近一次导入配置的结果，用于首页/设置页持续显示，避免用户分不清成功还是失败。 */
    data class ConfigStatus(val text: String, val ok: Boolean)

    fun configStatus(): ConfigStatus? {
        val text = prefs.getString(KEY_CONFIG_STATUS, "").orEmpty()
        if (text.isBlank()) return null
        return ConfigStatus(text, prefs.getBoolean(KEY_CONFIG_STATUS_OK, false))
    }

    private fun recordConfigResult(ok: Boolean, text: String) {
        prefs.edit()
            .putString(KEY_CONFIG_STATUS, text.take(160))
            .putBoolean(KEY_CONFIG_STATUS_OK, ok)
            .apply()
    }

    /** Display recent content immediately while the regular request refreshes it. Never used for play URLs. */
    suspend fun cachedHome(site: Site): List<Vod>? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        homeSnapshots.read(site.runtimeKey, maxAgeMs = 86_400_000)
    }

    suspend fun home(site: Site = requireSite(), refresh: Boolean = false): List<Vod> {
        val key = "home|" + site.runtimeKey
        if (refresh) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.invalidate(site.runtimeKey) }
            lists.invalidate(key)
            spiderHost.clearCache()
        }
        val started = System.nanoTime()
        val result = lists.get(key) {
            val snapshotGeneration = homeSnapshots.generation
            val saved = if (refresh) null else withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.read(site.runtimeKey) }
            saved ?: (if (site.isCms) CmsSiteClient(http, site).home() else spiderHost.home(site)).also {
                withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.write(site.runtimeKey, it, snapshotGeneration) }
            }
        }
        android.util.Log.i("NextMovieTiming", "home ${((System.nanoTime() - started) / 1_000_000)}ms items=${result.size}")
        return result
    }

    suspend fun category(site: Site, tid: String, page: Int): List<Vod> =
        lists.get("category|${site.runtimeKey}|$tid|$page") {
            if (site.isCms) CmsSiteClient(http, site).category(tid, page) else spiderHost.category(site, tid, page, false, "")
        }

    /** Speculation stays on the focused item of an already initialized source, at most one request. */
    fun prefetchDetail(site: Site, vod: Vod) {
        if (site.runtimeKey !in warmSites || vod.id.isBlank() || vod.action.isNotBlank() || vod.tag == "folder") return
        if (vod.id.startsWith("action://", true)) return
        val sourceLabel = "${site.api} ${site.name}"
        if (listOf("wogg", "网盘", "玩偶", "夸克", "alist", "netdisk").any { sourceLabel.contains(it, true) }) return
        if (!prefetchSlot.tryAcquire()) return
        prefetchScope.launch {
            try { detail(site, vod.id, vod.name) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* A speculative failure must never affect navigation. */ }
            finally { prefetchSlot.release() }
        }
    }

    suspend fun detail(site: Site, id: String, altId: String? = null): VodDetail? =
        // 插件源额外带一个「片名」作为备用 id：部分插件 homeVod 不返回 vod_id
        details.get("detail|${site.runtimeKey}|$id") {
            if (site.isCms) CmsSiteClient(http, site).detail(id) else spiderHost.detail(site, id, altId)
        }

    /**
     * 详情订阅的容错链路：主站点拿不到线路时，按片名去其它站点找同名条目，
     * 找到能返回线路的站点就用它（带换源列表）。
     *
     * 背景：部分 Guard 插件（如 @csp_DouDouGuard）的 detailContent 在本宿主下恒返回空，
     * 只有 home/category 可用；而同一份配置里还有几十个正常站点，能给出同一部片。
     */
    data class DetailHit(
        val site: Site,
        val detail: VodDetail,
        /** 其它也能提供这部片的站点，供详情页「换源」 */
        val alternatives: List<Site> = emptyList(),
        /** 主站点是否失败、靠跨站查找救回来的 */
        val borrowed: Boolean = false
    )

    suspend fun detailPreferring(
        site: Site,
        vod: Vod,
        crossSiteLimit: Int = 12,
        preferSearch: Boolean = false,
        onFirstPlayable: (DetailHit) -> Unit = {}
    ): DetailHit? {
        val keyword = vod.name.trim()
        val primaryBudgetMs = if (site.isSpider && site.runtimeKey !in warmSites) 9_000L else 5_000L
        val primary = withTimeoutOrNull(primaryBudgetMs) {
            detailAttempt {
                val id = if (preferSearch) matchingVideo(site, keyword, vod.displayYear)?.id else vod.id
                id?.let { detail(site, it, altId = vod.name) }
            }
        }
        if (primary != null && primary.lines.isNotEmpty()) {
            warmSites.add(site.runtimeKey)
            return DetailHit(site, primary)
        }
        if (keyword.isBlank()) return primary?.let { DetailHit(site, it) }

        val targets = sites.filter { it.isPlayable && it.searchable }
            .sortedBy { if (it.runtimeKey == site.runtimeKey) 0 else if (it.isCms) 1 else 2 }
            .filterNot { preferSearch && it.runtimeKey == site.runtimeKey }
            .take(crossSiteLimit)
        return coroutineScope {
            val replies = Channel<DetailHit?>(targets.size.coerceAtLeast(1))
            val workers = targets.map { candidate ->
                launch {
                    val hit = withTimeoutOrNull(8_000) {
                        detailAttempt {
                            val match = matchingVideo(candidate, keyword, vod.displayYear)
                            val resolved = match?.let { detail(candidate, it.id, it.name) }
                            resolved?.takeIf { it.lines.isNotEmpty() }
                                ?.let { DetailHit(candidate, it, borrowed = true) }
                        }
                    }
                    replies.send(hit)
                }
            }
            val hits = ArrayList<DetailHit>()
            try {
                for (i in targets.indices) {
                    val hit = replies.receive() ?: continue
                    hits += hit
                    if (hits.size == 1) onFirstPlayable(hit)
                    if (hits.size == 3) break
                }
            } finally {
                workers.forEach { it.cancel() }
                replies.cancel()
            }
            hits.firstOrNull()?.copy(alternatives = hits.drop(1).map { it.site })
                ?: primary?.let { DetailHit(site, it) }
        }
    }

    private suspend fun matchingVideo(site: Site, keyword: String, year: String): Vod? =
        search(site, keyword).firstOrNull {
            TitleMatcher.score(keyword, year, it.name, it.year) >= TitleMatcher.ACCEPT_THRESHOLD
        }

    private suspend fun <T> detailAttempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    suspend fun search(site: Site, keyword: String): List<Vod> {
        if (!site.searchable) return emptyList()
        return if (site.isCms) CmsSiteClient(http, site).search(keyword) else spiderHost.search(site, keyword)
    }

    /**
     * 插件源的播放地址需要二次解析；CMS 源一般给直链，
     * 但也有不少线路给的是一个分享播放页（`/share/<id>`），
     * 这里顺手把页面里的真实地址扒出来，免得用户看到「需要网页解析」。
     */
    fun cancelPlayback(site: Site) { if (!site.isCms) spiderHost.cancelPlayback() }

    fun releasePlaybackProxy(url: String) = spiderHost.releasePlaybackProxy(url)

    suspend fun preparePlayback(site: Site) { if (!site.isCms) spiderHost.preparePlayback() }

    suspend fun resolvePlay(
        site: Site,
        flag: String,
        episodeUrl: String,
        onStage: (String) -> Unit = {}
    ): PlayResult? {
        onStage("正在读取来源播放地址…")
        // Native plugins may wait for the user to complete a login dialog.
        val raw = (if (site.isCms) PlayResult(episodeUrl) else spiderHost.play(site, flag, episodeUrl))
            ?: return null
        if (raw.parse == 0 && (!SharePageResolver.looksLikePage(raw.url) ||
            runCatching { java.net.URI(raw.url).host in listOf("127.0.0.1", "localhost") }.getOrDefault(false))) return raw
        onStage("正在解析网页播放地址…")
        return withTimeoutOrNull(18_000) {
            if (site.isCms || raw.parse != 0) {
                withTimeoutOrNull(4_000) { SharePageResolver.resolve(http, raw.url, raw.headers) }
                    ?.let { return@withTimeoutOrNull PlayResult(it, raw.headers) }
            }
            if (raw.parse == 0) return@withTimeoutOrNull raw
            withTimeoutOrNull(6_000) {
                com.nextmovie.core.net.WebPlaybackResolver.resolve(context, raw.url, raw.headers)
            }?.let { return@withTimeoutOrNull it }
            for (parser in parses.filter { it.type == 0 || it.type == 1 }.take(3)) {
                if (!parser.url.startsWith("http")) continue
                val page = parser.url + raw.url
                val result = withTimeoutOrNull(4_000) {
                    detailAttempt {
                        if (parser.type == 1) PlayResult.fromJson(org.json.JSONObject(http.text(page)))
                        else com.nextmovie.core.net.WebPlaybackResolver.resolve(context, page, emptyMap())
                    }
                }
                if (result != null && result.parse == 0) return@withTimeoutOrNull result
            }
            null
        } ?: throw IllegalStateException("网页解析未取得媒体地址，请更换线路")
    }

    /** 列表首屏：只做 L0 URL 尺寸升级，同步返回，不发请求。 */
    fun prepareForList(vods: List<Vod>): List<Vod> =
        if (vods.isEmpty()) vods else vods.map { poster.upgrade(it, detail = false) }

    /** 海报增强：L0 → TMDB（有 Token 时）→ B站（开关打开时），失败保持源图。 */
    suspend fun enrichPoster(vod: Vod, detail: Boolean): Vod =
        runCatching { poster.resolve(vod, detail, posterEnhance) }.getOrDefault(vod)

    suspend fun invalidateContent() {
        withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.clear() }
        lists.clear()
        details.clear()
        spiderHost.clearCache()
    }

    fun release() {
        prefetchScope.cancel()
        lists.close()
        details.close()
        poster.flush()
        spiderHost.release()
    }

    private fun saveConfigSnapshot(url: String) {
        val array = org.json.JSONArray()
        sites.forEach { site -> array.put(org.json.JSONObject(with(com.nextmovie.core.spider.SpiderProtocol) { site.toTransport() })) }
        val p = org.json.JSONArray()
        parses.forEach { p.put(org.json.JSONObject().put("name", it.name).put("type", it.type).put("url", it.url)) }
        val json = org.json.JSONObject().put("url", url).put("sites", array).put("parses", p)
        val temp = File(snapshot.parentFile, snapshot.name + ".tmp")
        runCatching { temp.writeText(json.toString()); if (!temp.renameTo(snapshot)) { temp.copyTo(snapshot, overwrite = true); temp.delete() } }
    }

    suspend fun configSnapshotIsFresh(): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        System.currentTimeMillis() - snapshot.lastModified() in 0 until 6 * 60 * 60 * 1000L
    }

    suspend fun restoreCachedConfig(): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val json = runCatching { org.json.JSONObject(snapshot.readText()) }.getOrNull() ?: return@withContext false
        if (json.optString("url") != configUrl()) return@withContext false
        val array = json.optJSONArray("sites") ?: return@withContext false
        val restored = (0 until array.length()).mapNotNull { i ->
            runCatching { com.nextmovie.core.spider.SpiderProtocol.siteFromTransport(array.getJSONObject(i).toString()) }.getOrNull()
        }
        if (restored.isEmpty()) return@withContext false
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            sites = restored
            parses = com.nextmovie.core.config.ConfigBundle.fromJson(json, "").parses
            currentSite = SiteSelector.pick(sites, prefs.getString(KEY_SITE_KEY, "").orEmpty(), prefs.getBoolean(KEY_SITE_MANUAL, false))
        }
        true
    }

    private fun requireSite(): Site =
        currentSite ?: throw IllegalStateException("还没有选择数据源")

    companion object {
        private const val PREFS = "nextmovie"
        private const val KEY_CONFIG_URL = "config_url"
        private const val KEY_TMDB_TOKEN = "tmdb_token"
        private const val KEY_SITE_KEY = "site_key"
        private const val KEY_POSTER_ENHANCE = "poster_enhance"
        private const val KEY_SITE_MANUAL = "site_manual"
        private const val KEY_TMDB_ROUTE = "tmdb_route"
        private const val KEY_TMDB_CUSTOM = "tmdb_custom_base"
        private const val KEY_TMDB_IMG_PROXY = "tmdb_img_proxy"
        private const val KEY_TMDB_RACE = "tmdb_race"
        private const val KEY_TMDB_MIRROR = "tmdb_mirror"
        private const val KEY_CONFIG_STATUS = "config_status"
        private const val KEY_CONFIG_STATUS_OK = "config_status_ok"
        private const val KEY_DNS_MODE = "dns_mode"
        private const val KEY_DNS_CUSTOM = "dns_custom"
    }
}
