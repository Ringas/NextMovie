package com.nextmovie.core.config

import com.nextmovie.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 站点能力体检：把“配置里写了多少个站点”翻译成“到底能用几个”。
 *
 * TVBox 生态的配置动辄几十个站点，但真正能播的比例往往只有两三成：
 * CMS 接口能直连，`csp_*` 依赖加壳插件，`.js` 依赖 JS 运行时。
 * 面板的价值就是把这三种一眼分开，避免用户以为是软件坏了。
 *
 * 静态 [audit] 只看类型；[probe] 对 CMS 做短超时连通性探测，
 * 插件 / JS 只标记「需宿主」，不在这里假装已验证可播。
 */
enum class SiteKind(val label: String) {
    READY("可直接用"),
    PLUGIN("插件源"),
    JS("JS 源"),
    UNSUPPORTED("不支持")
}

/** 单站探活结果。 */
enum class ProbeStatus(val label: String) {
    UNKNOWN("未测"),
    OK("可达"),
    SLOW("偏慢"),
    FAIL("失败"),
    SKIPPED("跳过")
}

data class SiteProbeResult(
    val siteKey: String,
    val siteName: String,
    val kind: SiteKind,
    val status: ProbeStatus,
    val latencyMs: Long = -1,
    val detail: String = ""
)

data class HealthReport(
    val total: Int,
    val ready: Int,
    val plugin: Int,
    val js: Int,
    val unsupported: Int,
    val jarUrl: String = "",
    val jarHash: String = "",
    /** 探活后填充；未探活时为空。 */
    val probes: List<SiteProbeResult> = emptyList(),
    val probedAtMs: Long = 0L
) {
    /** 当前版本真正能打开的比例（插件 / JS 均未计入）。 */
    val usablePercent: Int get() = if (total == 0) 0 else ready * 100 / total

    val probeOk: Int get() = probes.count { it.status == ProbeStatus.OK || it.status == ProbeStatus.SLOW }
    val probeFail: Int get() = probes.count { it.status == ProbeStatus.FAIL }

    val summary: String
        get() = if (total == 0) {
            "尚未导入配置"
        } else {
            buildString {
                append("共 $total 个站点：可直接用 $ready · 插件源 $plugin · JS 源 $js · 不支持 $unsupported")
                if (probes.isNotEmpty()) {
                    append("｜探活 可达 $probeOk · 失败 $probeFail")
                }
            }
        }
}

object SiteHealth {

    /** CMS 探活超时（毫秒）。盒子网络差时宁可判慢/失败，也不拖死设置页。 */
    const val PROBE_TIMEOUT_MS = 4_000L
    /** 超过此时延仍成功则记为 SLOW。 */
    const val SLOW_THRESHOLD_MS = 2_000L
    /** 同时探活并发上限，避免把盒子网络打满。 */
    const val PROBE_CONCURRENCY = 3
    /** 单次最多探活的 CMS 站点数（优先列表前部）。 */
    const val PROBE_CMS_LIMIT = 12

    fun kindOf(site: Site): SiteKind {
        // 与 SourceKind 对齐：Python / CatPaw / 纯适配器单独归入「不支持」，便于设置页展示缺口
        return when (com.nextmovie.core.source.SourceKind.of(site)) {
            com.nextmovie.core.source.SourceKind.CMS -> SiteKind.READY
            com.nextmovie.core.source.SourceKind.JAR,
            com.nextmovie.core.source.SourceKind.NETDISK -> SiteKind.PLUGIN
            com.nextmovie.core.source.SourceKind.JS -> SiteKind.JS
            com.nextmovie.core.source.SourceKind.ADAPTER -> SiteKind.READY
            com.nextmovie.core.source.SourceKind.PYTHON,
            com.nextmovie.core.source.SourceKind.CATPAW,
            com.nextmovie.core.source.SourceKind.UNSUPPORTED -> SiteKind.UNSUPPORTED
        }
    }

    fun audit(sites: List<Site>): HealthReport {
        var ready = 0
        var plugin = 0
        var js = 0
        var unsupported = 0
        sites.forEach {
            when (kindOf(it)) {
                SiteKind.READY -> ready++
                SiteKind.PLUGIN -> plugin++
                SiteKind.JS -> js++
                SiteKind.UNSUPPORTED -> unsupported++
            }
        }
        val withJar = sites.firstOrNull { it.jar.isNotBlank() }
        return HealthReport(
            total = sites.size,
            ready = ready,
            plugin = plugin,
            js = js,
            unsupported = unsupported,
            jarUrl = withJar?.jar.orEmpty(),
            jarHash = withJar?.jarHash.orEmpty()
        )
    }

    /** 面板里每个站点的短标签，带不上原因时不刷屏。 */
    fun shortTag(site: Site): String = when (kindOf(site)) {
        SiteKind.READY -> "CMS"
        SiteKind.PLUGIN -> "JAR"
        SiteKind.JS -> "JS"
        SiteKind.UNSUPPORTED -> "N/A"
    }

    /**
     * 对 CMS 做短超时连通性探测；插件 / JS / 不支持 标记为 SKIPPED。
     * 不拉全站首页内容，只验证「接口地址是否在超时内有有效 HTTP 响应」。
     */
    suspend fun probe(
        sites: List<Site>,
        http: Http,
        cmsLimit: Int = PROBE_CMS_LIMIT,
        concurrency: Int = PROBE_CONCURRENCY
    ): HealthReport {
        val base = audit(sites)
        if (sites.isEmpty()) return base

        val cmsTargets = sites
            .filter { kindOf(it) == SiteKind.READY && it.api.startsWith("http") }
            .take(cmsLimit)
        // JAR：探测全局 spider 地址（同一 jar 多站共享，只测一次）
        val jarUrl = sites.firstOrNull { it.jar.startsWith("http") }?.jar.orEmpty()
        // JS：探测前若干脚本 URL
        val jsTargets = sites.filter { kindOf(it) == SiteKind.JS && it.api.startsWith("http") }.take(4)

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val cmsResults = coroutineScope {
            val jobs = cmsTargets.map { site ->
                async { semaphore.withPermit { probeOneCms(site, http) } }
            } + jsTargets.map { site ->
                async { semaphore.withPermit { probeHttpResource(site, http, site.api, "JS 脚本") } }
            } + if (jarUrl.isNotBlank()) {
                listOf(async {
                    semaphore.withPermit {
                        val fake = sites.first { it.jar == jarUrl }
                        probeHttpResource(fake, http, jarUrl.substringBefore(';'), "JAR 插件包")
                    }
                })
            } else emptyList()
            jobs.awaitAll()
        }
        val byKey = cmsResults.associateBy { it.siteKey }

        val probes = sites.map { site ->
            byKey[site.key] ?: when {
                kindOf(site) == SiteKind.PLUGIN && jarUrl.isNotBlank() &&
                    byKey.values.any { it.detail.startsWith("JAR") } -> {
                    val jarProbe = byKey.values.first { it.detail.startsWith("JAR") }
                    SiteProbeResult(
                        siteKey = site.key,
                        siteName = site.name,
                        kind = SiteKind.PLUGIN,
                        status = jarProbe.status,
                        latencyMs = jarProbe.latencyMs,
                        detail = "共用 spider · " + jarProbe.detail
                    )
                }
                else -> SiteProbeResult(
                    siteKey = site.key,
                    siteName = site.name,
                    kind = kindOf(site),
                    status = when (kindOf(site)) {
                        SiteKind.READY -> if (site.api.startsWith("http")) ProbeStatus.SKIPPED else ProbeStatus.FAIL
                        else -> ProbeStatus.SKIPPED
                    },
                    detail = when (kindOf(site)) {
                        SiteKind.PLUGIN -> if (jarUrl.isBlank()) "无 spider 地址" else "未单独探测"
                        SiteKind.JS -> "未列入本轮脚本探测"
                        SiteKind.UNSUPPORTED -> site.unavailableReason ?: "不支持"
                        SiteKind.READY -> if (!site.api.startsWith("http")) "非 HTTP 接口" else "未列入本轮探测"
                    }
                )
            }
        }

        return base.copy(probes = probes, probedAtMs = System.currentTimeMillis())
    }


    /** 探测任意 HTTP 资源（JS 脚本 / JAR 包）：有响应即 OK，不解析正文。 */
    private suspend fun probeHttpResource(
        site: Site,
        http: Http,
        url: String,
        label: String
    ): SiteProbeResult {
        val started = System.nanoTime()
        val outcome = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    http.bytes(url)
                }
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return when {
            outcome == null -> SiteProbeResult(
                siteKey = site.key, siteName = site.name, kind = kindOf(site),
                status = ProbeStatus.FAIL, latencyMs = elapsed,
                detail = "$label 超时"
            )
            outcome.isFailure -> SiteProbeResult(
                siteKey = site.key, siteName = site.name, kind = kindOf(site),
                status = ProbeStatus.FAIL, latencyMs = elapsed,
                detail = "$label 失败: " + (outcome.exceptionOrNull()?.message?.take(60) ?: "")
            )
            (outcome.getOrNull()?.size ?: 0) < 32 -> SiteProbeResult(
                siteKey = site.key, siteName = site.name, kind = kindOf(site),
                status = ProbeStatus.FAIL, latencyMs = elapsed,
                detail = "$label 体积异常"
            )
            elapsed >= SLOW_THRESHOLD_MS -> SiteProbeResult(
                siteKey = site.key, siteName = site.name, kind = kindOf(site),
                status = ProbeStatus.SLOW, latencyMs = elapsed,
                detail = "$label 慢 ${elapsed}ms · ${(outcome.getOrNull()?.size ?: 0) / 1024}KB"
            )
            else -> SiteProbeResult(
                siteKey = site.key, siteName = site.name, kind = kindOf(site),
                status = ProbeStatus.OK, latencyMs = elapsed,
                detail = "$label 可达 · ${(outcome.getOrNull()?.size ?: 0) / 1024}KB"
            )
        }
    }

    private suspend fun probeOneCms(site: Site, http: Http): SiteProbeResult {
        val started = System.nanoTime()
        val outcome = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // 轻量 GET：多数 CMS provide/vod 对无参也能回 JSON/XML；失败则仍记 FAIL。
                    http.text(
                        url = site.api,
                        headers = site.headers,
                        callTimeoutMs = PROBE_TIMEOUT_MS.toInt()
                    )
                }
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        return when {
            outcome == null -> SiteProbeResult(
                siteKey = site.key,
                siteName = site.name,
                kind = SiteKind.READY,
                status = ProbeStatus.FAIL,
                latencyMs = elapsed,
                detail = "超时 (>${PROBE_TIMEOUT_MS / 1000}s)"
            )
            outcome.isFailure -> SiteProbeResult(
                siteKey = site.key,
                siteName = site.name,
                kind = SiteKind.READY,
                status = ProbeStatus.FAIL,
                latencyMs = elapsed,
                detail = outcome.exceptionOrNull()?.message?.take(80) ?: "请求失败"
            )
            elapsed >= SLOW_THRESHOLD_MS -> SiteProbeResult(
                siteKey = site.key,
                siteName = site.name,
                kind = SiteKind.READY,
                status = ProbeStatus.SLOW,
                latencyMs = elapsed,
                detail = "${elapsed}ms"
            )
            else -> SiteProbeResult(
                siteKey = site.key,
                siteName = site.name,
                kind = SiteKind.READY,
                status = ProbeStatus.OK,
                latencyMs = elapsed,
                detail = "${elapsed}ms"
            )
        }
    }
}
