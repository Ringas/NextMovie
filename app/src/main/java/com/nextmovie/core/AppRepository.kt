package com.nextmovie.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nextmovie.core.cms.CmsSiteClient
import com.nextmovie.core.config.ConfigBundle
import com.nextmovie.core.config.ConfigRepository
import com.nextmovie.core.config.HealthReport
import com.nextmovie.core.config.Site
import com.nextmovie.core.config.SiteHealth
import com.nextmovie.core.config.SiteSelector
import com.nextmovie.core.spider.JarPrefetch
import com.nextmovie.core.history.HistoryStore
import com.nextmovie.core.match.TitleMatcher
import com.nextmovie.core.model.PlayLine
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    init {
        // 线路优选：把「是否已登录网盘」和「这条线路真实播成功过几次」喂给排序器
        com.nextmovie.core.match.SourceRanker.panLoggedIn = { pan ->
            // 登录态以插件 /proxy?do=pan&action=status 为准（30s 缓存），不再读宿主旧 pref
            runCatching { com.nextmovie.core.pan.PanBridge.has(pan) }.getOrDefault(false)
        }
        com.nextmovie.core.match.SourceRanker.lineStats = { siteKey, line ->
            val ok = prefs.getInt("line_ok_${siteKey}_$line", 0)
            val fail = prefs.getInt("line_fail_${siteKey}_$line", 0)
            ok to fail
        }
    }

    private var parses: List<com.nextmovie.core.config.Parse> = emptyList()

    private val t4Extensions = com.nextmovie.core.cache.RequestCache<String, String>(300_000, 32)
    private fun cms(site: Site): CmsSiteClient = CmsSiteClient(http, site) {
        t4Extensions.get(site.runtimeKey) { CmsSiteClient(http, site).fetchExtension() }
    }

    private val lists = com.nextmovie.core.cache.RequestCache<String, List<Vod>>(120_000, 48, cacheIf = { it.isNotEmpty() })
    private val details = com.nextmovie.core.cache.RequestCache<String, VodDetail?>(300_000, 128, cacheIf = { it != null && it.lines.isNotEmpty() })
    private val aggregations = com.nextmovie.core.cache.RequestCache<String, AggregatedDetail?>(180_000, 32, cacheIf = { it != null && it.sources.isNotEmpty() })
    private val homeSnapshots = com.nextmovie.core.cache.HomeSnapshotStore(File(context.filesDir, "home-snapshots"))
    private val warmSites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val detailSnapshots = com.nextmovie.core.cache.HomeSnapshotStore(File(context.filesDir, "detail-metadata"))
    private val playableDetails = com.nextmovie.core.cache.DetailSnapshotStore(File(context.cacheDir, "playable-details"))
    /** 站点级失败冷却：同一个 id 失败后 60s 内不再重复打插件，避免重试风暴。 */
    private val detailFails = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val detailFailTtlMs = 60_000L
    private val prefetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val prefetchSlot = java.util.concurrent.Semaphore(1)
    private val configGeneration = java.util.concurrent.atomic.AtomicLong()
    private val snapshot = File(context.filesDir, "config-snapshot.json")

    val history = HistoryStore(File(context.filesDir, "history.json"))
    val favorites = com.nextmovie.core.history.FavoriteStore(File(context.filesDir, "favorites.json"))

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

    /**
     * 源健康度快照（key=站点 key，value=域名探测结果）。
     * 换源面板据此给死源打「失效」标，JS 站 init 命中缓存即快速失败。
     */
    var siteHealth: Map<String, com.nextmovie.core.config.SourceHealthStore.State> by mutableStateOf(emptyMap())
        private set

    private var healthScanning = false

    init {
        com.nextmovie.core.config.SourceHealthStore.bind(context)
        // 盘上已有站点结论就先铺上去，避免「重启后换源面板红点消失、点进去又白等一轮」
        siteHealth = com.nextmovie.core.config.SourceHealthStore.siteSnapshot()
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

    fun savedSubscriptions(): List<String> {
        val stored = runCatching { org.json.JSONArray(prefs.getString("saved_subscriptions", "[]")) }.getOrNull()
        return (listOf(configUrl()) + List(stored?.length() ?: 0) { stored?.optString(it).orEmpty() })
            .filter { it.isNotBlank() }.distinct().take(20)
    }

    fun forgetSubscription(url: String) {
        if (url == configUrl()) return
        prefs.edit().putString("saved_subscriptions", org.json.JSONArray(savedSubscriptions() - url).toString()).apply()
    }

    suspend fun syncLibraryWebDav(address: String, username: String, password: String, includeSubscriptions: Boolean) {
        var remoteSubscriptions: org.json.JSONArray? = null
        com.nextmovie.core.net.WebDavBackup(http.client).sync(
            address, username, password,
            merge = {
                restoreBackup(it, includeSubscriptions)
                if (!includeSubscriptions) remoteSubscriptions = org.json.JSONObject(it).getJSONArray("subscriptions")
            },
            export = {
                val local = libraryBackup(includeLibrary = true, includeSubscriptions = includeSubscriptions)
                val retained = remoteSubscriptions
                if (retained == null) local else org.json.JSONObject(local).put("subscriptions", retained).toString()
            }
        )
    }

    fun libraryBackup(includeLibrary: Boolean, includeSubscriptions: Boolean = true): String = org.json.JSONObject()
        .put("format", "nextmovie-backup").put("version", 1)
        .put("subscriptions", org.json.JSONArray(if (includeSubscriptions) savedSubscriptions() else emptyList()))
        .apply {
            if (includeLibrary) {
                put("favorites", com.nextmovie.core.history.FavoriteStore.toJson(favorites.list()))
                put("history", HistoryStore.toJson(history.list()))
            }
        }.toString()

    fun restoreBackup(backup: String, includeSubscriptions: Boolean = true): Int {
        val root = org.json.JSONObject(backup)
        require(root.optString("format") == "nextmovie-backup" && root.optInt("version") == 1)
        val entries = root.getJSONArray("subscriptions")
        val restored = List(minOf(entries.length(), 20)) { entries.optString(it).trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("{") }
        val existing = savedSubscriptions()
        val merged = if (includeSubscriptions) (existing + restored).distinct().take(20) else existing
        if (merged != existing) {
            prefs.edit().putString("saved_subscriptions", org.json.JSONArray(merged).toString()).apply()
        }
        root.optJSONObject("favorites")?.let { favorites.merge(com.nextmovie.core.history.FavoriteStore.parse(it.toString())) }
        root.optJSONObject("history")?.let { history.merge(HistoryStore.parse(it.toString())) }
        return (merged - existing.toSet()).size
    }

    /** 播放器选项（对齐 Marco 播放偏好）：自动连播下一集 */
    var autoNext: Boolean = prefs.getBoolean(KEY_AUTO_NEXT, true)
        private set

    fun setAutoNext(enabled: Boolean) {
        autoNext = enabled
        prefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
    }

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
    fun startConfiguredPluginHost() {
        if (configUrl().isNotBlank() && prefs.getBoolean(KEY_SITE_PLUGIN, true)) spiderHost.startBinding()
    }

    /**
     * 启动预热仅准备插件，不请求 home；首页刷新不能挡住用户刚启动时点开的详情。
     *
     * 对照 Marco：它靠内置适配器不走 jar，我们仍走生态的 jar 路径，那就把这段耗时挪到
     * 用户还在看首页的时候（有快照时首页 200ms 就出画，用户不会察觉）。等真正点开海报时，
     * 插件实例、dex、native lib 都已在 :spider 进程里就绪。
     */
    // ── 源能力记忆 ─────────────────────────────────────────────────────────────
    // 有的站点只做「榜单/发现」：homeContent、categoryContent 都正常，但 detailContent
    // 对任何 id 都同步返回空（例如 csp_NewDouBanGuard）。它们不能当「线路源」用，
    // 但仍然是首页内容的好来源。这里按实测结果记住这类站点，避免每次详情都白跑一轮。
    fun isDiscoveryOnly(site: Site): Boolean =
        prefs.getBoolean("cap_discovery_${site.key}", false)

    fun lineScore(site: Site): Int {
        if (isDiscoveryOnly(site)) return -1
        val ok = prefs.getInt("cap_lines_${site.key}", 0)
        val rate = (sourceSuccessRate(site.key) * 10).toInt()
        val warm = if (site.runtimeKey in warmSites) 100 else 0
        // 实测详情耗时越短越优先（花卷 1.6s / 玩偶 5.4s，差 4s 就体现在这里）
        val ms = prefs.getInt("cap_ms_${site.key}", 0)
        return ok * 3 + rate + warm - ms / 50
    }

    private fun noteDetailLines(site: Site) {
        prefs.edit()
            .putInt("cap_lines_${site.key}", prefs.getInt("cap_lines_${site.key}", 0) + 1)
            .putBoolean("cap_discovery_${site.key}", false)
            .apply()
    }

    private fun noteDetailBlank(site: Site) {
        val blanks = prefs.getInt("cap_blank_${site.key}", 0) + 1
        val lines = prefs.getInt("cap_lines_${site.key}", 0)
        prefs.edit().putInt("cap_blank_${site.key}", blanks).apply()
        if (lines == 0 && blanks >= 2) {
            prefs.edit().putBoolean("cap_discovery_${site.key}", true).apply()
            android.util.Log.i("NextMovieTiming", "cap-mark discovery-only site=${site.name} blanks=$blanks")
        }
    }

    /**
     * 能力探针：分类页里出现「非 msearch:」的 id，说明这个源的条目带真实详情 id，
     * detailContent 才可能返回线路；全是 msearch: 的源只能做榜单。
     */
    private suspend fun probeRealIds(site: Site?): Boolean {
        if (site == null) return false
        if (prefs.contains("cap_real_${site.key}")) return prefs.getBoolean("cap_real_${site.key}", false)
        val started = System.nanoTime()
        val result = runCatching {
            val tid = categories(site).firstOrNull()?.first
            if (tid.isNullOrBlank()) false
            else category(site, tid, 1).any { it.id.isNotBlank() && !it.id.startsWith("msearch:", true) }
        }.getOrDefault(false)
        prefs.edit().putBoolean("cap_real_${site.key}", result).apply()
        android.util.Log.i(
            "NextMovieTiming",
            "cap-probe site=${site.name} realIds=$result ${(System.nanoTime() - started) / 1_000_000}ms"
        )
        return result
    }

    /** 启动时把 1–2 个「真的能出线路」的源一起预热，详情才有 Marco 那种 4s 量级。 */
    fun prewarmLineSources(count: Int = 2, reason: String = "startup") {
        prefetchScope.launch {
            val current = currentSite
            val picked = sites
                .filter { it.isPlayable && it.searchable && it.isSpider }
                .filterNot { isDiscoveryOnly(it) }
                .filterNot { it.runtimeKey == current?.runtimeKey }
                .sortedByDescending { lineScore(it) }
                .take(count)
            for (candidate in picked) {
                if (candidate.runtimeKey in warmSites) continue
                val started = System.nanoTime()
                runCatching { spiderHost.warm(candidate) }
                    .onSuccess {
                        warmSites.add(candidate.runtimeKey)
                        android.util.Log.i(
                            "NextMovieTiming",
                            "prewarm-line-ok ${(System.nanoTime() - started) / 1_000_000}ms site=${candidate.name} reason=$reason"
                        )
                        // 预热完立刻做一次「这个源会不会给真 id」的能力探针：
                        // 只做榜单的源（如 csp_NewDouBanGuard）分类里全是 msearch: 重定向 id，
                        // 详情必然拿不到线路；给真 id 的源才是线路源。
                        if (probeRealIds(candidate) && !probeRealIds(currentSite)) {
                            android.util.Log.i(
                                "NextMovieTiming",
                                "auto-site 切到 ${candidate.name}（能给出真实线路 id）"
                            )
                            prefs.edit()
                                .putString(KEY_SITE_KEY, candidate.key)
                                .putBoolean(KEY_SITE_MANUAL, false)
                                .putString("line_site_key", candidate.key)
                                .apply()
                            currentSite = candidate
                        }
                    }
                    .onFailure {
                        android.util.Log.w(
                            "NextMovieTiming",
                            "prewarm-line-fail ${(System.nanoTime() - started) / 1_000_000}ms site=${candidate.name} ${it.message}"
                        )
                    }
            }
        }
    }

    /**
     * JS 源域名健康扫描（P0）。
     *
     * 只对 `.js` 站做：拉一次 ext 规则文本（1.5s 短超时，命中磁盘缓存时不再走网），
     * 取规则里的 `host:`，然后做 DNS + TLS 探测（2s）。并发固定 2，启动后延迟 1.5s 才开始，
     * 确保不跟首屏渲染、也不跟播放抢带宽。
     */
    fun scanJsSourceHealth(reason: String = "startup", limit: Int = 20, concurrency: Int = 2) {
        if (healthScanning) return
        val targets = sites
            .filter { it.isJavaScript && it.ext.startsWith("http", true) }
            .take(limit)
        if (targets.isEmpty()) return
        healthScanning = true
        val started = System.nanoTime()
        prefetchScope.launch {
            try {
                kotlinx.coroutines.delay(1500)
                val sem = Semaphore(concurrency.coerceAtLeast(1))
                val results = coroutineScope {
                    targets.map { site ->
                        async<com.nextmovie.core.config.SourceHealthStore.State?> { sem.withPermit { probeJsSiteHealth(site) } }
                    }.awaitAll()
                }
                // 结果按「站点 key」建索引给 UI 用（探活缓存本身是按 host 存的，两者不能混）
                siteHealth = targets.zip(results)
                    .mapNotNull { (site, health) -> health?.let { site.key to it } }
                    .toMap()
                siteHealth.forEach { (key, state) ->
                    com.nextmovie.core.config.SourceHealthStore.rememberSite(key, state)
                }
                val dead = results.filter { it?.dead == true }.size
                val ok = results.filter { it?.status == com.nextmovie.core.config.SourceHealthStore.Status.OK }.size
                android.util.Log.i(
                    "NextMovieHealth",
                    "health-scan $reason 共 ${targets.size} 站 可达 $ok 失效 $dead " +
                        "${(System.nanoTime() - started) / 1_000_000}ms"
                )
            } catch (e: Throwable) {
                android.util.Log.w("NextMovieHealth", "health-scan 失败 ${e.message}")
            } finally {
                healthScanning = false
            }
        }
    }

    /** 单站：ext 文本（缓存优先）→ host → DNS/TLS 探测。任一步失败按「未测」处理，不误伤站点。 */
    private suspend fun probeJsSiteHealth(site: Site): com.nextmovie.core.config.SourceHealthStore.State? {
        val ext = runCatching {
            withTimeoutOrNull(1_500) {
                withContext(kotlinx.coroutines.Dispatchers.IO) { http.text(site.ext) }
            }
        }.getOrNull() ?: return null
        val host = com.nextmovie.core.config.SourceHealthStore.hostFromExt(ext) ?: return null
        val cached = com.nextmovie.core.config.SourceHealthStore.cached(host)
        // 已有 24h 内的结论就不再打网络，扫描一轮只补新站
        if (cached != null) return cached
        val state = com.nextmovie.core.config.SourceHealthStore.check(host)
        android.util.Log.d(
            "NextMovieHealth",
            "probe site=${site.name} host=$host ${state.status.label} ${state.latencyMs}ms ${state.note}"
        )
        return state
    }

    /** 当前站点是否是「已判死」的源：给 UI 提示用，不改站点选择。 */
    /** 进首页时兜底触发一次扫描：配置命中快照时不会重走 loadConfig，健康度不能因此永远空着。 */
    fun ensureHealthScan(reason: String) {
        val jsSites = sites.count { it.isJavaScript && it.ext.startsWith("http", true) }
        if (siteHealth.size >= jsSites.coerceAtMost(20)) return
        scanJsSourceHealth(reason)
    }

    fun isSiteDead(site: Site?): Boolean = deadNote(site) != null

    /** 死源时返回一句人能看懂的原因，供首页直接顶掉「这个分类还没有内容」这种误导文案。 */
    fun deadNote(site: Site?): String? {
        val s = site ?: return null
        val st = siteHealth[s.key] ?: return null
        if (!st.dead) return null
        val host = st.host.substringAfter("://").substringBefore('/')
        return "${st.status.label}（$host）"
    }

    fun prewarmCurrentSite(reason: String = "startup") {
        val site = currentSite ?: return
        if (!site.isSpider || !site.isPlayable || site.runtimeKey in warmSites) return
        prefetchScope.launch {
            val started = System.nanoTime()
            runCatching { spiderHost.warm(site) }
                .onSuccess {
                    warmSites.add(site.runtimeKey)
                    android.util.Log.i("NextMovieTiming", "prewarm-ok ${(System.nanoTime() - started) / 1_000_000}ms site=${site.name} reason=$reason initOnly=true")
                    // 当前源预热完，再把线路源排进队列（同 jar 的 Init 已复用，只剩 spider.init(ext)）
                    prewarmLineSources(reason = "$reason/line")
                }
                .onFailure { android.util.Log.w("NextMovieTiming", "prewarm-fail ${(System.nanoTime() - started) / 1_000_000}ms site=${site.name} reason=$reason ${it.message}") }
        }
    }

    /** 配置根部的 spider jar 先拉到本地，别等第一次点开插件源才开始下载。 */
    fun prefetchSpiderJar(reason: String = "startup") {
        val siteWithJar = sites.firstOrNull { it.jar.isNotBlank() } ?: return
        // 还原 `url;md5;hash` 形态，供 JarPrefetch 校验
        val raw = if (siteWithJar.jarHash.isNotBlank()) {
            "${siteWithJar.jar};md5;${siteWithJar.jarHash}"
        } else {
            siteWithJar.jar
        }
        prefetchScope.launch {
            val started = System.nanoTime()
            val ok = runCatching { JarPrefetch.prefetch(context, http, raw) }.getOrDefault(false)
            android.util.Log.i("NextMovieTiming", "jar-prefetch-done ${(System.nanoTime() - started) / 1_000_000}ms ok=$ok reason=$reason")
        }
    }

    /** 静态站点分类报告（不探活）。 */
    fun siteHealthReport(): HealthReport = SiteHealth.audit(sites)

    /** 对 CMS 做短超时探活，返回带 probes 的报告。 */
    suspend fun probeSiteHealth(): HealthReport = SiteHealth.probe(sites, http)

    fun jarDownloadState(): JarPrefetch.State = JarPrefetch.state()

    suspend fun retryJarDownload(): Boolean = JarPrefetch.retryLast(context, http)

    suspend fun connectPluginHost(site: Site) {
        if (site.isSpider && site.isPlayable) spiderHost.preconnect()
    }

    suspend fun categories(site: Site): List<Pair<String, String>> =
        (if (site.isCms) cms(site).categories() else spiderHost.categories(site)).also { warmSites.add(site.runtimeKey) }

    fun selectSite(key: String) {
        currentSite = sites.firstOrNull { it.key == key } ?: currentSite
        currentSite?.let {
            prefs.edit().putString(KEY_SITE_KEY, it.key).putBoolean(KEY_SITE_MANUAL, true).putBoolean(KEY_SITE_PLUGIN, it.isSpider && it.isPlayable).apply()
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
        val subscriptions = (listOf(url.trim()) + savedSubscriptions()).distinct().take(20)
        prefs.edit().putString(KEY_CONFIG_URL, url.trim())
            .putString("saved_subscriptions", org.json.JSONArray(subscriptions).toString()).apply()
        val savedKey = prefs.getString(KEY_SITE_KEY, "").orEmpty()
        val manual = prefs.getBoolean(KEY_SITE_MANUAL, false)
        currentSite = SiteSelector.pick(sites, savedKey, manual)
        currentSite?.let { prefs.edit().putString(KEY_SITE_KEY, it.key).putBoolean(KEY_SITE_PLUGIN, it.isSpider && it.isPlayable).apply() }
        recordConfigResult(true, "✓ 已加载 ${sites.size} 个站点")
        prefetchSpiderJar("config-loaded")
        scanJsSourceHealth("config-loaded")
        prewarmCurrentSite("config-loaded")
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
        com.nextmovie.core.log.AppLog.record("config", text)
        prefs.edit()
            .putString(KEY_CONFIG_STATUS, text.take(160))
            .putBoolean(KEY_CONFIG_STATUS_OK, ok)
            .apply()
    }

    /** Display recent content immediately while the regular request refreshes it. Never used for play URLs. */
    suspend fun cachedHome(site: Site): List<Vod>? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        homeSnapshots.read(site.runtimeKey, maxAgeMs = 7 * 86_400_000L)
    }

    /**
     * 首页：Stale-While-Revalidate。
     * - 有磁盘/内存快照 → **立即返回**（秒开），网络在后台刷新；
     * - 无快照 → 等网络（插件冷启最多 30s）。
     */
    suspend fun home(
        site: Site = requireSite(),
        refresh: Boolean = false,
        budgetMs: Long = 8_000
    ): List<Vod> {
        val key = "home|" + site.runtimeKey
        val started = System.nanoTime()
        if (refresh) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.invalidate(site.runtimeKey) }
            lists.invalidate(key)
            spiderHost.clearCache()
        }

        val age = withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.ageMs(site.runtimeKey) }
        val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.read(site.runtimeKey) }
        val mem = lists.peek(key)

        fun kickBackgroundRefresh() {
            prefetchScope.launch {
                runCatching { lists.get(key) { loadHomeNetwork(site) } }
                    .onSuccess {
                        android.util.Log.i(
                            "NextMovieTiming",
                            "home-bg-refresh ok items=${it.size} site=${site.name}"
                        )
                    }
                    .onFailure {
                        android.util.Log.w("NextMovieTiming", "home-bg-refresh fail ${it.message}")
                    }
            }
        }

        if (!refresh && snapshot != null) {
            // 新鲜：内存优先，否则磁盘；超过 2 分钟软刷新
            if (age < FRESH_HOME_MS) {
                if (age > SOFT_HOME_REFRESH_MS) kickBackgroundRefresh()
                val ready = mem ?: prepareForList(snapshot)
                android.util.Log.i(
                    "NextMovieTiming",
                    "home-instant ${(System.nanoTime() - started) / 1_000_000}ms age=${age}ms items=${ready.size}"
                )
                return ready
            }
            // 过期但仍有快照：立刻出屏，后台拉新
            kickBackgroundRefresh()
            android.util.Log.i(
                "NextMovieTiming",
                "home-stale-instant ${(System.nanoTime() - started) / 1_000_000}ms items=${snapshot.size}"
            )
            return prepareForList(snapshot)
        }

        val effectiveBudget =
            if (snapshot == null && site.isSpider && site.runtimeKey !in warmSites) 30_000L else budgetMs
        val network = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(effectiveBudget) {
                lists.get(key) { loadHomeNetwork(site) }
            }
        }
        val fresh = network.getOrNull()
        if (fresh != null) {
            android.util.Log.i("NextMovieTiming", "home ${(System.nanoTime() - started) / 1_000_000}ms items=${fresh.size}")
            return fresh
        }
        if (snapshot != null) {
            kickBackgroundRefresh()
            android.util.Log.i(
                "NextMovieTiming",
                "home-stale ${(System.nanoTime() - started) / 1_000_000}ms items=${snapshot.size}"
            )
            return prepareForList(snapshot)
        }
        // 无快照且超时：仍后台拉，下次可秒开
        kickBackgroundRefresh()
        android.util.Log.w(
            "NextMovieTiming",
            "home-timeout ${(System.nanoTime() - started) / 1_000_000}ms budget=$effectiveBudget site=${site.name}"
        )
        throw java.io.IOException(
            "「${site.name}」首次读取超时（>${effectiveBudget / 1000}s）：插件源要先下载并初始化，已转到后台继续准备，稍后重试即可"
        )
    }

    private suspend fun loadHomeNetwork(site: Site): List<Vod> {
        val snapshotGeneration = homeSnapshots.generation
        return (if (site.isCms) cms(site).home() else spiderHost.home(site)).also {
            withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.write(site.runtimeKey, it, snapshotGeneration) }
        }
    }

    suspend fun category(site: Site, tid: String, page: Int): List<Vod> {
        val key = "category|${site.runtimeKey}|$tid|$page"
        val list = lists.get(key) {
            if (site.isCms) cms(site).category(tid, page) else spiderHost.category(site, tid, page, false, "")
        }
        // 分类页也落盘：切 tab / 冷启动可以直接出画，不用等插件
        if (page == 1 && list.isNotEmpty()) withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { homeSnapshots.write(snapshotKey(tid, site), list, homeSnapshots.generation) }
        }
        return list
    }

    /** 分类页磁盘快照（6 小时内有效），供切 tab 与冷启动秒开。 */
    suspend fun cachedCategory(site: Site, tid: String): List<Vod>? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        homeSnapshots.read(snapshotKey(tid, site), maxAgeMs = 6 * 3_600_000L)
    }

    private fun snapshotKey(tid: String, site: Site) = "cat_${site.runtimeKey}_$tid"

    /** Speculation stays on the focused item of an already initialized source, at most one request. */
    suspend fun prefetchDetail(site: Site, vod: Vod): Vod? {
        if (site.runtimeKey !in warmSites || vod.id.isBlank() || vod.action.isNotBlank() || vod.tag == "folder") return null
        if (vod.id.startsWith("action://", true)) return null
        val sourceLabel = "${site.api} ${site.name}"
        if (listOf("wogg", "网盘", "玩偶", "夸克", "alist", "netdisk").any { sourceLabel.contains(it, true) }) return null
        details.peek("detail|${site.runtimeKey}|${vod.id}")?.let { return it.vod }
        if (!prefetchSlot.tryAcquire()) return null
        val task = prefetchScope.async { detailAttempt { detail(site, vod.id, vod.name)?.vod } }
        task.invokeOnCompletion { prefetchSlot.release() }
        return task.await()
    }

    private fun detailSnapshotKey(site: Site, id: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("${site.runtimeKey}|$id".toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun cachedDetailMetadata(site: Site, id: String): Vod? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        detailSnapshots.read(detailSnapshotKey(site, id), maxAgeMs = 7 * 86_400_000L)?.firstOrNull()
    }

    fun readyDetail(site: Site, id: String): AggregatedDetail? {
        val ready = details.peekNow("detail|${site.runtimeKey}|$id")?.takeIf { it.lines.isNotEmpty() } ?: return null
        val rank = com.nextmovie.core.match.SourceRanker.sourceScore(site, ready, sourceSuccessRate(site.key), true)
        return AggregatedDetail(listOf(SourceDetail(site, ready, rank.score, rank.label,
            com.nextmovie.core.match.SourceRanker.bestLineIndex(ready, site.key))))
    }

    /**
     * 详情页的默认路径：只解析当前这一个来源，超时短、失败立刻返回。
     * 这是 Marco 的模型（DetailScreen 只用当前源的 lines），也是拿到选集的最短链路。
     */
    suspend fun detailForSite(site: Site, vod: Vod, budgetMs: Long = 9_000L): AggregatedDetail? {
        val started = System.nanoTime()
        val resolved = try {
            withTimeoutOrNull(budgetMs) { detail(site, vod.id, altId = vod.name.takeIf { it.isNotBlank() }) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.takeIf { it.lines.any { line -> line.episodes.isNotEmpty() } }
            // 首页给的 id 有时候插件自己 detail 不出来（只返回空 JSON，几十毫秒就回来），
            // 这时用片名在本站 search 一次拿真实 vod_id 再 detail —— 与 Marco 的「搜到再取详情」一致。
            ?: run {
                val keyword = vod.name.trim()
                if (keyword.isBlank()) return@run null
                val match = runCatching { matchingVideo(site, keyword, vod.displayYear) }.getOrNull()
                if (match == null || match.id == vod.id) return@run null
                val remaining = budgetMs - (System.nanoTime() - started) / 1_000_000
                if (remaining < 2_000) return@run null
                android.util.Log.i("NextMovieTiming", "detail-retry-by-search site=${site.name} id=${match.id}")
                runCatching {
                    withTimeoutOrNull(remaining) { detail(site, match.id, altId = match.name) }
                }.getOrNull()?.takeIf { it.lines.any { line -> line.episodes.isNotEmpty() } }
            }
            ?: return null
        android.util.Log.i("NextMovieTiming", "detail-site-ready ${(System.nanoTime() - started) / 1_000_000}ms site=${site.name} lines=${resolved.lines.size}")
        val rank = com.nextmovie.core.match.SourceRanker.sourceScore(site, resolved, sourceSuccessRate(site.key), true)
        return AggregatedDetail(
            listOf(
                SourceDetail(
                    site,
                    resolved,
                    rank.score,
                    rank.label,
                    com.nextmovie.core.match.SourceRanker.bestLineIndex(resolved, site.key)
                )
            )
        )
    }

    suspend fun detail(site: Site, id: String, altId: String? = null): VodDetail? =
        details.get("detail|${site.runtimeKey}|$id") {
            val key = "detail|${site.runtimeKey}|$id"
            val started = System.nanoTime()
            val diskKey = detailSnapshotKey(site, id)
            detailFails[key]?.let { at ->
                if (System.currentTimeMillis() - at < detailFailTtlMs) {
                    android.util.Log.i("NextMovieTiming", "detail-source cache=fail-cooldown")
                    return@get null
                }
            }
            // Interactive/netdisk IDs can be login or folder actions. They must stay live.
            val canPersist = !listOf("wogg", "网盘", "玩偶", "夸克", "alist", "netdisk", "config")
                .any { "${site.api} ${site.name}".contains(it, true) } &&
                !listOf("action", "login", "cookie", "token", "clear").any { id.contains(it, true) }
            if (canPersist) {
                val cached = withContext(kotlinx.coroutines.Dispatchers.IO) { playableDetails.read(diskKey) }
                if (cached != null) {
                    android.util.Log.i("NextMovieTiming", "detail-source cache=disk ${(System.nanoTime() - started) / 1_000_000}ms")
                    return@get cached
                }
            }
            val generation = detailSnapshots.generation
            val playableGeneration = playableDetails.generation
            val result = try {
                if (site.isCms) cms(site).detail(id) else spiderHost.detail(site, id, altId)
            } catch (t: Throwable) {
                detailFails[key] = System.currentTimeMillis()
                if (t is kotlinx.coroutines.CancellationException) throw t
                android.util.Log.w("NextMovieTiming", "detail-source fail ${site.name} id=$id ${t.javaClass.simpleName}: ${t.message}")
                throw t
            }
            if (result == null) {
                detailFails[key] = System.currentTimeMillis()
                if (id.startsWith("msearch:", true) && System.nanoTime() - started < 1_500_000_000L) noteDetailBlank(site)
            } else {
                detailFails.remove(key)
                if (result.lines.any { it.episodes.isNotEmpty() }) {
                    noteDetailLines(site)
                    prefs.edit()
                        .putInt("cap_ms_${site.key}", ((System.nanoTime() - started) / 1_000_000).toInt())
                        .putString("line_site_key", site.key)
                        .apply()
                }
            }
            android.util.Log.i("NextMovieTiming", "detail-source cache=miss ${(System.nanoTime() - started) / 1_000_000}ms")
            if (result != null) withContext(kotlinx.coroutines.Dispatchers.IO) {
                detailSnapshots.write(diskKey, listOf(result.vod.copy(action = "")), generation)
                if (canPersist && result.vod.action.isBlank() && result.vod.tag != "folder") {
                    playableDetails.write(diskKey, result, playableGeneration)
                }
            }
            result
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
        // 插件源第一次要下载 jar、解压 native、跑 Init（实测 19s），此前 9s 的预算会直接判失败
        val warm = site.runtimeKey in warmSites
        val primaryBudgetMs = if (site.isSpider && !warm) 30_000L else 5_000L
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
            .take(crossSiteLimit.coerceAtLeast(6))
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

    /**
     * 详情页聚合：以片名为线索，把所有可搜索源并发打一遍，拿到各自完整线路后按质量排序。
     * 第一个回来的结果立刻回调（页面不用等全网），全部结束后给出去重排序后的终值。
     */
    suspend fun aggregateDetail(
        site: Site,
        vod: Vod,
        crossSiteLimit: Int = 4,
        skipCurrent: Boolean = false,
        onPartial: (AggregatedDetail) -> Unit = {}
    ): AggregatedDetail? {
        val keyword = vod.name.trim()
        val cacheKey = "agg|${site.runtimeKey}|${vod.id}|$keyword|skip=$skipCurrent"
        // A second screen can join an aggregation whose first callback belonged to
        // a previous screen. Replay the already usable current-source detail first.
        details.peek("detail|${site.runtimeKey}|${vod.id}")?.takeIf { it.lines.isNotEmpty() }?.let { ready ->
            val rank = com.nextmovie.core.match.SourceRanker.sourceScore(site, ready, sourceSuccessRate(site.key), true)
            onPartial(AggregatedDetail(listOf(SourceDetail(site, ready, rank.score, rank.label,
                com.nextmovie.core.match.SourceRanker.bestLineIndex(ready, site.key)))))
        }
        return aggregations.get(cacheKey) { resolveAggregate(site, vod, keyword, crossSiteLimit, skipCurrent, onPartial) }
    }

    private suspend fun resolveAggregate(
        site: Site,
        vod: Vod,
        keyword: String,
        crossSiteLimit: Int,
        skipCurrent: Boolean,
        onPartial: (AggregatedDetail) -> Unit
    ): AggregatedDetail? = coroutineScope {
        // 「msearch:<id>」卡片本身没有可用的 detailContent（插件只会返回空），
        // 它的线路必须来自同一配置里的其它源 —— 这正是 Marco 的行为：
        // 详情页一边出简介，一边在后台拿片名做全站搜索，谁先返回可播线路谁上屏。
        val searchRedirect = vod.id.startsWith("msearch:", true)
        val candidates = buildList {
            // 当前源刚才已经由 detailForSite 试过一次（拿不到线路才走到这里），
            // 再把它塞回候选会让聚合白白多等一个 8–15s 超时。
            if (!searchRedirect && !skipCurrent) add(site)
            addAll(
                sites.filter { it.isPlayable && it.searchable && it.runtimeKey != site.runtimeKey }
                    .filterNot { isDiscoveryOnly(it) }
                    .sortedWith(
                        compareByDescending<Site> { lineScore(it) }
                            .thenByDescending { sourceSuccessRate(it.key) }
                            .thenBy { if (it.isCms) 0 else 1 }
                    )
                    .take(crossSiteLimit.coerceAtLeast(6))
            )
        }
        val replies = Channel<SourceDetail?>(candidates.size.coerceAtLeast(1))
        val gate = Semaphore(6)
        candidates.mapIndexed { index, candidate ->
            launch(kotlinx.coroutines.Dispatchers.IO) {
                // Give the selected source a head start. Background cross-source searches
                // must not occupy the plugin/network queue before its detail request.
                // 不再让当前源抢占：全站搜索的每一个候选都同时起跑。
                gate.withPermit {
                    val startedAt = System.nanoTime()
                    val hit = withTimeoutOrNull(
                        when {
                            index == 0 && candidate.isSpider && candidate.runtimeKey !in warmSites -> 15_000L
                            index == 0 -> 8_000L
                            else -> 4_500L
                        }
                    ) {

                        detailAttempt {
                            val resolved: VodDetail? =
                                if (index == 0 && vod.id.isNotBlank() && !vod.id.startsWith("msearch:", true)) detail(candidate, vod.id, altId = vod.name)
                                else keyword.takeIf { it.isNotBlank() }
                                    ?.let { matchingVideo(candidate, it, vod.displayYear) }
                                    ?.let { detail(candidate, it.id, it.name) }
                            resolved?.takeIf { it.lines.isNotEmpty() }?.let { resolvedDetail ->
                                val quality = com.nextmovie.core.match.SourceRanker.sourceScore(
                                    candidate,
                                    resolvedDetail,
                                    sourceSuccessRate(candidate.key),
                                    candidate.runtimeKey == site.runtimeKey
                                )
                                val bestIndex = com.nextmovie.core.match.SourceRanker.bestLineIndex(resolvedDetail, site.key)
                                // Read known quality without putting a CDN request on the detail path.
                                val probeUrl = resolvedDetail.lines.getOrNull(bestIndex)
                                    ?.episodes?.firstOrNull()?.url.orEmpty()
                                // Detail is usable as soon as the source returns it. A media/CDN
                                // request here used to hold synopsis and episodes for up to 6s.
                                // Reuse known quality only; the player reports actual tracks later.
                                val probe = com.nextmovie.core.match.StreamProbe.cached(probeUrl)
                                val probeBonus = when {
                                    probe == null -> 0
                                    probe.height >= 2160 -> 10
                                    probe.height >= 1440 -> 7
                                    probe.height >= 1080 -> 5
                                    probe.height >= 720 -> 2
                                    else -> 0
                                } + ((probe?.bandwidthKbps ?: 0) / 2500)
                                val badge = listOfNotNull(
                                    probe?.label?.takeIf { it.isNotBlank() },
                                    quality.label.takeIf { it.isNotBlank() }
                                ).joinToString(" · ")
                                val resLabel = when {
                                    (probe?.height ?: 0) >= 2160 -> "4K"
                                    (probe?.height ?: 0) >= 1440 -> "1440p"
                                    (probe?.height ?: 0) >= 1080 -> "1080p"
                                    (probe?.height ?: 0) >= 720 -> "720p"
                                    else -> ""
                                }
                                val detailWithRes = if (resLabel.isNotBlank() && resolvedDetail.vod.resolution.isBlank()) {
                                    resolvedDetail.copy(vod = resolvedDetail.vod.copy(resolution = resLabel))
                                } else resolvedDetail
                                SourceDetail(
                                    site = candidate,
                                    detail = detailWithRes,
                                    score = quality.score + probeBonus,
                                    badge = badge,
                                    bestLineIndex = bestIndex,
                                    height = probe?.height ?: 0,
                                    bandwidthKbps = probe?.bandwidthKbps ?: 0,
                                    sizeBytes = probe?.bytes ?: 0L
                                )
                            }
                        }
                    }
                    android.util.Log.i(
                        "NextMovieTiming",
                        "agg-site ${candidate.name} ${if (hit == null) "fail" else "ok lines=${hit.detail.lines.size}"} " +
                            "${(System.nanoTime() - startedAt) / 1_000_000}ms"
                    )
                    replies.send(hit)
                }
            }
        }
        val collected = ArrayList<SourceDetail>()
        var partialSent = false
        repeat(candidates.size) {
            val hit = replies.receive()
            if (hit == null) return@repeat
            if (collected.any { it.site.runtimeKey == hit.site.runtimeKey }) return@repeat
            collected += hit
            if (!partialSent) {
                partialSent = true
                runCatching { onPartial(AggregatedDetail(collected.sortedByDescending { it.score })) }
            }
        }
        replies.close()
        val sorted = collected.sortedWith(
            compareByDescending<SourceDetail> { it.score }.thenByDescending { it.detail.lines.size }
        )
        AggregatedDetail(sorted).takeIf { it.sources.isNotEmpty() }
    }

    /** 「饥饿游戏：收获日出」这类带副标题的片名，站点往往只按主标题收录。 */
    private fun keywordVariants(keyword: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(keyword)
        for (sep in listOf("：", ":", "·", " - ", "（", "(")) {
            out.add(keyword.substringBefore(sep).trim())
        }
        return out.filter { it.isNotBlank() && it.length >= 2 }
    }

    private suspend fun matchingVideo(site: Site, keyword: String, year: String): Vod? {
        var list = search(site, keyword)
        if (list.isEmpty()) {
            for (variant in keywordVariants(keyword).drop(1)) {
                if (variant == keyword) continue
                list = search(site, variant)
                if (list.isNotEmpty()) break
            }
        }
        // 先严格阈值，再放宽到 0.72，减少跨站「同名找不到」
        val ranked = list.map { it to TitleMatcher.score(keyword, year, it.name, it.year) }
            .filter { it.second >= 0.72 }
            .sortedByDescending { it.second }
        return ranked.firstOrNull { it.second >= TitleMatcher.ACCEPT_THRESHOLD }?.first
            ?: ranked.firstOrNull()?.first
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
        return if (site.isCms) cms(site).search(keyword) else spiderHost.search(site, keyword)
    }

    /**
     * 聚合搜索的候选源：当前配置里「可播放 + 声明支持搜索」的站点。
     * 顺序优先当前站点，让用户最可能想看的结果先到。
     */
    /**
     * 一次聚合的产物：某个源给出的完整详情 + 排序分。
     * `badge` 是给人看的画质标签（4K 蓝光 / 1080P / 全 20 集…）。
     */
    data class SourceDetail(
        val site: Site,
        val detail: VodDetail,
        val score: Int,
        val badge: String,
        val bestLineIndex: Int,
        /** 首集实测分辨率高度（0 = 没探到） */
        val height: Int = 0,
        val bandwidthKbps: Int = 0,
        val sizeBytes: Long = 0L
    )

    /** 聚合结果按分倒序，第一个就是「自动优选」的源。 */
    data class AggregatedDetail(val sources: List<SourceDetail>) {
        val best: SourceDetail? get() = sources.firstOrNull()
        val vod: Vod? get() = best?.detail?.vod
        val bestLine: PlayLine? get() = best?.let {
            it.detail.lines.getOrNull(it.bestLineIndex) ?: it.detail.lines.firstOrNull()
        }
    }

    /** 本站历史播放成功率（Laplace 平滑，没播过时是 0.5）。 */
    fun sourceSuccessRate(siteKey: String): Double {
        if (siteKey.isBlank()) return 0.5
        val ok = prefs.getInt("stat_ok_$siteKey", 0)
        val fail = prefs.getInt("stat_fail_$siteKey", 0)
        return (ok + 1.0) / (ok + fail + 2.0)
    }

    /** 起播成功/失败都要回写：聚合排序靠这个逐步收敛到"哪个源真的能播"。 */
    fun recordPlayOutcome(siteKey: String, ok: Boolean, lineName: String = "") {
        if (siteKey.isBlank()) return
        val k = (if (ok) "stat_ok_" else "stat_fail_") + siteKey
        val edit = prefs.edit().putInt(k, prefs.getInt(k, 0) + 1)
        if (lineName.isNotBlank()) {
            val lk = (if (ok) "line_ok_" else "line_fail_") + siteKey + "_" + lineName
            edit.putInt(lk, prefs.getInt(lk, 0) + 1)
        }
        edit.apply()
    }

    fun searchableSites(limit: Int = 12): List<Site> {
        val all = sites.filter { it.searchable && it.isPlayable }
        val current = currentSite
        return (all.filter { it.runtimeKey == current?.runtimeKey } + all.filter { it.runtimeKey != current?.runtimeKey })
            .distinctBy { it.runtimeKey }
            .take(limit)
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
        val raw = when {
            site.isHttpT4 -> detailAttempt { cms(site).play(flag, episodeUrl) }
                ?: episodeUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { PlayResult(it) }
            site.isCms -> PlayResult(episodeUrl)
            else -> spiderHost.play(site, flag, episodeUrl)
        } ?: return null
        // 部分插件把地址包进自己的本地代理（127.0.0.1:端口），但宿主环境里那个端口可能根本没起来
        // （此时端口是 -1 / 0），ExoPlayer 只会报 Malformed URL。这里把内层真实地址取出来直接用。
        fun unwrapLocalProxy(url: String): String {
            // 端口可能是 -1，「java.net.URI」会直接判定非法，所以这里按字符串解析
            val m = Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1\\]):(-?\\d+)/").find(url) ?: return url
            if ((m.groupValues[2].toIntOrNull() ?: -1) > 0) return url
            val query = url.substringAfter('?', "")
            if (query.isBlank()) return url
            val target = query.split('&')
                .firstOrNull { it.substringBefore('=') == "url" }?.substringAfter('=', "")
                ?.takeIf { it.isNotBlank() } ?: return url
            val decoded = runCatching { java.net.URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
            return if (decoded.startsWith("http")) decoded else url
        }
        val usable = raw.copy(url = unwrapLocalProxy(raw.url))
        android.util.Log.d("NextMoviePlay", "parse=${raw.parse} proxyRewritten=${raw.url != usable.url}")
        val rawFixed = usable
        if (rawFixed.parse == 0 && (!SharePageResolver.looksLikePage(rawFixed.url) ||
            runCatching { java.net.URI(rawFixed.url).host in listOf("127.0.0.1", "localhost") }.getOrDefault(false))) return rawFixed
        onStage("正在解析网页播放地址…")
        return withTimeoutOrNull(18_000) {
            if (site.isCms || rawFixed.parse != 0) {
                withTimeoutOrNull(4_000) { SharePageResolver.resolve(http, rawFixed.url, rawFixed.headers) }
                    ?.let { return@withTimeoutOrNull rawFixed.copy(url = it, parse = 0) }
            }
            if (rawFixed.parse == 0) return@withTimeoutOrNull rawFixed
            withTimeoutOrNull(6_000) {
                com.nextmovie.core.net.WebPlaybackResolver.resolve(context, raw.url, raw.headers)
            }?.let { return@withTimeoutOrNull it.withAuxiliaryMedia(rawFixed) }
            for (parser in parses.filter { it.type == 0 || it.type == 1 }.take(3)) {
                if (!parser.url.startsWith("http")) continue
                val page = parser.url + raw.url
                val result = withTimeoutOrNull(4_000) {
                    detailAttempt {
                        if (parser.type == 1) PlayResult.fromJson(org.json.JSONObject(http.text(page)))
                        else com.nextmovie.core.net.WebPlaybackResolver.resolve(context, page, emptyMap())
                    }
                }
                if (result != null && result.parse == 0) return@withTimeoutOrNull result.withAuxiliaryMedia(rawFixed)
            }
            null
        } ?: throw IllegalStateException("网页解析未取得媒体地址，请更换线路")
    }

    /**
     * 跨站聚合搜索：多源并发，按 TitleMatcher 去重合并，当前站优先。
     * 结果带上 [SearchHit.site] 便于一键换源进详情。
     */
    data class SearchHit(val site: Site, val vod: Vod, val score: Double)

    suspend fun searchAll(
        keyword: String,
        limitSites: Int = 10,
        onPartial: (List<SearchHit>) -> Unit = {}
    ): List<SearchHit> = coroutineScope {
        val q = keyword.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val targets = searchableSites(limitSites)
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val collected = java.util.concurrent.ConcurrentLinkedQueue<SearchHit>()
        targets.map { site ->
            launch(kotlinx.coroutines.Dispatchers.IO) {
                gate.withPermit {
                    val list = withTimeoutOrNull(8_000) {
                        runCatching { search(site, q) }.getOrDefault(emptyList())
                    }.orEmpty()
                    list.forEach { vod ->
                        val score = TitleMatcher.score(q, "", vod.name, vod.year)
                        if (score >= 0.55 || vod.name.contains(q, ignoreCase = true)) {
                            collected += SearchHit(site, vod, score.coerceAtLeast(0.55))
                        }
                    }
                    if (collected.isNotEmpty()) {
                        onPartial(collected.sortedByDescending { it.score }.distinctBy { it.vod.name to it.site.key })
                    }
                }
            }
        }.forEach { it.join() }
        collected.sortedWith(
            compareByDescending<SearchHit> { it.score }
                .thenBy { if (it.site.runtimeKey == currentSite?.runtimeKey) 0 else 1 }
        ).distinctBy { TitleMatcher.normalize(it.vod.name) to it.site.key }
    }

    /**
     * 播放失败自动换线：按聚合源顺序尝试 [alternatives] 中同一集（按集名/序号对齐）。
     * 成功则 [recordPlayOutcome]；全部失败抛最后错误。
     */
    suspend fun resolvePlayWithFailover(
        primary: Site,
        flag: String,
        episodeUrl: String,
        episodeName: String = "",
        alternatives: List<SourceDetail> = emptyList(),
        onStage: (String) -> Unit = {}
    ): Pair<Site, PlayResult> {
        val queue = ArrayList<Pair<Site, String>>()
        queue += primary to episodeUrl
        alternatives.forEach { alt ->
            if (alt.site.runtimeKey == primary.runtimeKey) return@forEach
            val line = alt.detail.lines.getOrNull(alt.bestLineIndex) ?: alt.detail.lines.firstOrNull()
            val ep = line?.episodes?.firstOrNull { episodeName.isNotBlank() && it.name == episodeName }
                ?: line?.episodes?.firstOrNull()
            if (ep != null) queue += alt.site to ep.url
        }
        var lastError: Throwable? = null
        for ((site, url) in queue) {
            onStage("尝试「${site.name}」…")
            val result = runCatching { resolvePlay(site, flag, url, onStage) }.getOrElse {
                lastError = it
                recordPlayOutcome(site.key, false)
                null
            }
            if (result != null && result.url.isNotBlank()) {
                // 网盘兜底头
                val headers = com.nextmovie.core.source.netdisk.NetdiskProxy.mergePlayHeaders(
                    result.url, result.headers
                )
                recordPlayOutcome(site.key, true)
                return site to result.copy(headers = headers)
            }
            recordPlayOutcome(site.key, false)
        }
        throw lastError ?: IllegalStateException("所有线路均无法播放，请稍后再试或换源")
    }

    private fun PlayResult.withAuxiliaryMedia(original: PlayResult): PlayResult = copy(
        subtitles = (subtitles + original.subtitles).distinctBy { it.url },
        danmaku = danmaku.ifBlank { original.danmaku }
    )

    /** 列表首屏：只做 L0 URL 尺寸升级，同步返回，不发请求。 */
    fun prepareForList(vods: List<Vod>): List<Vod> =
        if (vods.isEmpty()) vods else vods.map { poster.upgrade(it, detail = false) }

    /** 海报增强：L0 → TMDB（有 Token 时）→ B站（开关打开时），失败保持源图。 */
    suspend fun enrichPoster(vod: Vod, detail: Boolean): Vod =
        runCatching { poster.resolve(vod, detail, posterEnhance) }.getOrDefault(vod)

    suspend fun invalidateContent() {
        withContext(kotlinx.coroutines.Dispatchers.IO) { homeSnapshots.clear(); detailSnapshots.clear(); playableDetails.clear() }
        t4Extensions.clear()
        lists.clear()
        details.clear()
        aggregations.clear()
        spiderHost.clearCache()
    }

    fun release() {
        prefetchScope.cancel()
        t4Extensions.close()
        lists.close()
        details.close()
        aggregations.close()
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
        private const val FRESH_HOME_MS = 10 * 60 * 1000L
        /** 有快照时超过此时长后台软刷新，不挡首屏。 */
        private const val SOFT_HOME_REFRESH_MS = 2 * 60 * 1000L
        private const val PREFS = "nextmovie"
        private const val KEY_CONFIG_URL = "config_url"
        private const val KEY_TMDB_TOKEN = "tmdb_token"
        private const val KEY_SITE_KEY = "site_key"
        private const val KEY_SITE_PLUGIN = "site_plugin"
        private const val KEY_POSTER_ENHANCE = "poster_enhance"
        private const val KEY_AUTO_NEXT = "auto_next"
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
