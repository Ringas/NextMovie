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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    suspend fun categories(site: Site): List<Pair<String, String>> =
        if (site.isCms) CmsSiteClient(http, site).categories() else spiderHost.categories(site)

    fun selectSite(key: String) {
        currentSite = sites.firstOrNull { it.key == key } ?: currentSite
        currentSite?.let {
            prefs.edit().putString(KEY_SITE_KEY, it.key).putBoolean(KEY_SITE_MANUAL, true).apply()
        }
    }

    suspend fun loadConfig(url: String): Int {
        val bundle: ConfigBundle = try {
            configRepository.load(url.trim())
        } catch (e: Throwable) {
            recordConfigResult(false, "✗ 加载失败：" + (e.message ?: e.javaClass.simpleName))
            throw e
        }
        sites = bundle.sites
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

    suspend fun home(site: Site = requireSite()): List<Vod> =
        if (site.isCms) CmsSiteClient(http, site).home() else spiderHost.home(site)

    suspend fun category(site: Site, tid: String, page: Int): List<Vod> =
        if (site.isCms) CmsSiteClient(http, site).category(tid, page) else spiderHost.category(site, tid, page, false, "")

    suspend fun detail(site: Site, id: String, altId: String? = null): VodDetail? =
        // 插件源额外带一个「片名」作为备用 id：部分插件 homeVod 不返回 vod_id
        if (site.isCms) CmsSiteClient(http, site).detail(id) else spiderHost.detail(site, id, altId)

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

    suspend fun detailPreferring(site: Site, vod: Vod, crossSiteLimit: Int = 6): DetailHit? {
        val primary = runCatching { detail(site, vod.id, altId = vod.name) }.getOrNull()
        if (primary != null && primary.lines.isNotEmpty()) return DetailHit(site, primary)

        val keyword = vod.name.trim()
        if (keyword.isBlank()) return primary?.let { DetailHit(site, it) }
        val targets = sites.filter { it.key != site.key && it.isPlayable && it.searchable }
        val cands = searchAcross(targets, keyword, vod.displayYear, crossSiteLimit)
        if (cands.isEmpty()) return primary?.let { DetailHit(site, it) }

        val working = ArrayList<Pair<Site, VodDetail>>()
        for ((s, v) in cands) {
            val d = runCatching { detail(s, v.id, altId = v.name) }.getOrNull() ?: continue
            if (d.lines.isEmpty()) continue
            working += s to d
            if (working.size >= 3) break
        }
        if (working.isEmpty()) return primary?.let { DetailHit(site, it) }
        val best = working.first()
        return DetailHit(
            site = best.first,
            detail = best.second,
            alternatives = working.drop(1).map { it.first },
            borrowed = true
        )
    }

    /** 并发在多个站点搜索同一个片名，只保留标题高分匹配的结果。 */
    private suspend fun searchAcross(
        targets: List<Site>,
        keyword: String,
        year: String,
        limit: Int
    ): List<Pair<Site, Vod>> = coroutineScope {
        targets.take(limit).map { s ->
            async {
                val list = withTimeoutOrNull(12_000L) {
                    runCatching { search(s, keyword) }.getOrNull()
                }.orEmpty()
                s to list.firstOrNull {
                    TitleMatcher.score(keyword, year, it.name, it.year) >= TitleMatcher.ACCEPT_THRESHOLD
                }
            }
        }.mapNotNull { deferred ->
            val hit = deferred.await()
            val v = hit.second ?: return@mapNotNull null
            hit.first to v
        }
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
    suspend fun resolvePlay(site: Site, flag: String, episodeUrl: String): PlayResult? {
        val raw = if (site.isCms) PlayResult(episodeUrl) else spiderHost.play(site, flag, episodeUrl)
        if (raw == null) return null
        if (!site.isCms) return raw
        if (!SharePageResolver.looksLikePage(raw.url)) return raw
        val real = SharePageResolver.resolve(http, raw.url, raw.headers)
        return if (real != null) PlayResult(real, raw.headers) else raw
    }

    /** 列表首屏：只做 L0 URL 尺寸升级，同步返回，不发请求。 */
    fun prepareForList(vods: List<Vod>): List<Vod> =
        if (vods.isEmpty()) vods else vods.map { poster.upgrade(it, detail = false) }

    /** 海报增强：L0 → TMDB（有 Token 时）→ B站（开关打开时），失败保持源图。 */
    suspend fun enrichPoster(vod: Vod, detail: Boolean): Vod =
        runCatching { poster.resolve(vod, detail, posterEnhance) }.getOrDefault(vod)

    fun release() {
        poster.flush()
        spiderHost.release()
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
