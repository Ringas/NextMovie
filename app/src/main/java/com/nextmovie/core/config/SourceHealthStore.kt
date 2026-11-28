package com.nextmovie.core.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 源健康度预检（P0）。
 *
 * 2026 年的 drpy 站点池大面积腐烂：30 站实测里 19 站是域名无解析 / 连接超时 / 连接被重置，
 * 用户在「换源」面板里逐个点，看到的都是空白页和 15s 等待，体感就是「软件坏了」。
 *
 * 这里只做一件轻量的事：把每个 JS 站点的**站点域名**（ext 里 `host:` 那个）做 DNS + TCP/TLS 探测，
 * 2s 超时、并发 2、盘上缓存 24h。结果给两处用：
 *  1. 换源面板打「失效」标，用户不必再点；
 *  2. JsSpider init 前命中缓存即快速失败，给出「源已失效：域名无解析」而不是干等超时。
 *
 * 不做的事：不拉首页内容、不判断「能不能出片」，只判断「域名还活着没」。
 */
object SourceHealthStore {

    enum class Status(val label: String) {
        OK("可达"),
        DNS("域名无解析"),
        TLS("证书/加密握手失败"),
        TIMEOUT("连接超时"),
        RESET("连接被重置"),
        API("接口异常"),
        PLAY("线路失效"),
        UNKNOWN("未测")
    }

    data class State(
        val host: String,
        val status: Status,
        val note: String = "",
        val latencyMs: Long = -1,
        val atMs: Long = 0
    ) {
        val dead: Boolean get() = status != Status.OK && status != Status.UNKNOWN
    }

    /** 缓存有效期：域名复活/挂掉都按天变化，一天一刷足够，避免每次启动都打一轮网络。 */
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private const val SITE_PREFIX = "site@"
    private const val PLAY_PREFIX = "play@"
    const val DEFAULT_TIMEOUT_MS = 2_000

    private val DNS_POOL = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "nm-health-dns").apply { isDaemon = true }
    }

    private val lock = Any()
    private var storeFile: File? = null
    private val cache = HashMap<String, State>()

    fun bind(context: Context) {
        synchronized(lock) {
            if (storeFile != null) return
            val f = File(context.filesDir, "source-health.json")
            storeFile = f
            runCatching {
                if (!f.exists()) return@runCatching
                val root = JSONObject(f.readText())
                root.keys().forEach { host ->
                    val o = root.optJSONObject(host) ?: return@forEach
                    val status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.UNKNOWN)
                    cache[host] = State(
                        host = host,
                        status = status,
                        note = o.optString("note"),
                        latencyMs = o.optLong("latency", -1),
                        atMs = o.optLong("at", 0)
                    )
                }
            }.onFailure { Log.w("NextMovieHealth", "健康缓存读取失败 ${it.message}") }
        }
    }

    fun cached(host: String): State? {
        val h = normalizeHost(host) ?: return null
        synchronized(lock) {
            val s = cache[h] ?: return null
            if (System.currentTimeMillis() - s.atMs > TTL_MS) return null
            return s
        }
    }

    fun snapshot(): Map<String, State> = synchronized(lock) { cache.toMap() }

    /**
     * 站点 key → 结论。宿主缓存按域名存（一个域名可能被多站复用），但 UI 换源面板是按站点 key 取值的，
     * 所以另存一份带 `site@` 前缀的别名条目，重启后不必重扫就能把红点和原因立刻摆出来。
     */
    fun siteSnapshot(): Map<String, State> = synchronized(lock) {
        cache.entries.filter { it.key.startsWith(SITE_PREFIX) }
            .associate { it.key.removePrefix(SITE_PREFIX) to it.value }
    }

    fun rememberSite(siteKey: String, state: State) {
        if (siteKey.isBlank()) return
        synchronized(lock) {
            cache[SITE_PREFIX + siteKey] = state
            persistLocked()
        }
    }

    /** 站点被移除时一并清掉它的结论，避免换源面板留下已不存在的红点。 */
    fun forgetSite(siteKey: String) {
        if (siteKey.isBlank()) return
        synchronized(lock) {
            cache.remove(SITE_PREFIX + siteKey)
            cache.remove(PLAY_PREFIX + siteKey)
            persistLocked()
        }
    }

    /**
     * 「片库活着、首集线路死了」是采集站最常见的腐烂姿势：接口秒回 118 万条，
     * 点播放却发现 m3u8 域名已经不通。这里单独记一笔，别和「站点不可达」混成一个红点。
     */
    fun markPlayDead(siteKey: String, note: String = "播放线路不可达") {
        if (siteKey.isBlank()) return
        synchronized(lock) {
            cache[PLAY_PREFIX + siteKey] = State(
                host = PLAY_PREFIX + siteKey,
                status = Status.PLAY,
                note = note,
                latencyMs = -1,
                atMs = System.currentTimeMillis()
            )
            persistLocked()
        }
    }

    fun playDead(siteKey: String): State? {
        if (siteKey.isBlank()) return null
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cache[PLAY_PREFIX + siteKey]
                ?.takeIf { it.status == Status.PLAY && now - it.atMs < TTL_MS }
        }
    }

    fun playDeadSnapshot(): Map<String, State> = synchronized(lock) {
        val now = System.currentTimeMillis()
        cache.entries
            .filter { it.key.startsWith(PLAY_PREFIX) }
            .filter { it.value.status == Status.PLAY && now - it.value.atMs < TTL_MS }
            .associate { it.key.removePrefix(PLAY_PREFIX) to it.value }
    }

    /** 这条源真播成功过一次，之前的「线路失效」结论就该作废。 */
    fun clearPlayDead(siteKey: String) {
        if (siteKey.isBlank()) return
        synchronized(lock) {
            if (cache.remove(PLAY_PREFIX + siteKey) != null) persistLocked()
        }
    }

    fun remember(state: State) {
        synchronized(lock) {
            cache[state.host] = state
            persistLocked()
        }
    }

    private fun persistLocked() {
        val f = storeFile ?: return
        runCatching {
            val root = JSONObject()
            cache.values.forEach { s ->
                root.put(s.host, JSONObject().apply {
                    put("status", s.status.name)
                    put("note", s.note)
                    put("latency", s.latencyMs)
                    put("at", s.atMs)
                })
            }
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
        }.onFailure { Log.w("NextMovieHealth", "健康缓存写入失败 ${it.message}") }
    }

    /** 从 ext 规则文本里取站点域名：drpy 规则几乎都用 `host:'https://xxx'` 直写。 */
    fun hostFromExt(extText: String): String? {
        if (extText.isBlank()) return null
        val m = Regex("""host\s*:\s*['"](https?://[^'"\s]+)['"]""").find(extText) ?: return null
        return normalizeHost(m.groupValues[1])
    }

    fun normalizeHost(url: String): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.startsWith("http", true)) raw else "https://$raw"
        return runCatching { URI(withScheme).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** DNS → TCP/TLS 握手。只判「域名是否还活着」，不下载内容，避免吃流量、拖慢播放。 */
    suspend fun check(host: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): State =
        withContext(Dispatchers.IO) {
            val h = normalizeHost(host) ?: return@withContext State(host, Status.UNKNOWN, "域名非法")
            val started = System.nanoTime()
            fun ms() = (System.nanoTime() - started) / 1_000_000

            // DNS 必须单独设上限：InetAddress.getByName 不受 socket 超时约束，
            // 遇到半死解析器能卡 20s+（实测 www.lzizy9.com 卡了 21.9s），会拖垮整轮扫描。
            val address = runCatching {
                val job = DNS_POOL.submit(java.util.concurrent.Callable { InetAddress.getByName(h) })
                job.get(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrElse { e ->
                // submit + get 会把真实异常包成 ExecutionException，判定前先拆包
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                val state = if (cause is java.util.concurrent.TimeoutException) {
                    State(h, Status.TIMEOUT, "DNS 解析超时", ms(), System.currentTimeMillis())
                } else {
                    State(h, Status.DNS, (cause.message ?: "无解析").take(48), ms(), System.currentTimeMillis())
                }
                remember(state)
                return@withContext state
            }

            val https = true // drpy 站点 99% 走 https，TLS 层能顺带暴露证书问题
            val port = 443
            val state = runCatching {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(address, port), timeoutMs)
                    if (!https) return@use State(h, Status.OK, "tcp ok", ms(), System.currentTimeMillis())
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, h, port, true) as SSLSocket
                    ssl.soTimeout = timeoutMs
                    ssl.startHandshake()
                    val peer = runCatching { ssl.session?.peerPrincipal?.name.orEmpty() }.getOrDefault("")
                    ssl.close()
                    State(h, Status.OK, if (peer.isBlank()) "tls ok" else "tls ok", ms(), System.currentTimeMillis())
                }
            }.getOrElse { raw ->
                val e = (raw as? java.util.concurrent.ExecutionException)?.cause ?: raw
                // 证书链有问题但端口活着：按「可达（兼容模式）」处理，别把这类站判死。
                // 用户点进去能看，才有意义；真正的死站是 DNS/超时/重置。
                if (com.nextmovie.core.net.TlsCompat.isTlsProblem(e)) {
                    val lenient = runCatching {
                        Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(address, port), timeoutMs)
                            val ssl = com.nextmovie.core.net.TlsCompat.socketFactory()
                                .createSocket(socket, h, port, true) as SSLSocket
                            ssl.soTimeout = timeoutMs
                            ssl.startHandshake()
                            ssl.close()
                            true
                        }
                    }.getOrDefault(false)
                    if (lenient) {
                        val ok = State(h, Status.OK, "证书无效·兼容模式", ms(), System.currentTimeMillis())
                        remember(ok)
                        return@withContext ok
                    }
                }
                val status = when (e) {
                    is SSLException -> Status.TLS
                    is java.net.SocketTimeoutException -> Status.TIMEOUT
                    is UnknownHostException -> Status.DNS
                    is java.net.SocketException -> Status.RESET
                    else -> Status.TIMEOUT
                }
                State(h, status, (e.message ?: e.javaClass.simpleName).take(48), ms(), System.currentTimeMillis())
            }
            remember(state)
            state
        }

    /** 面板/错误提示用的一句话。 */
    fun describe(state: State): String = "源已失效：${state.status.label}"
}
