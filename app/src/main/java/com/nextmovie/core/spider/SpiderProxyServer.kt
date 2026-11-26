package com.nextmovie.core.spider

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Loopback-only streaming bridge for CatVod proxy(Map) results. Streams stay out of Binder.
 *
 * 加固点（相对早期版本）：
 * - 请求头透传：Range / If-Range / Origin / Referer / User-Agent / Cookie 等进入 params，供插件代理使用
 * - 响应头透传：Content-Length / Content-Range / Accept-Ranges / Location / Cache-Control /
 *   Content-Disposition / ETag / Last-Modified
 * - rewrite 仍只改写 legacy 9978–9999 的 `/proxy` 查询接口，不碰插件自有 `/proxy/play` 端口
 */
class SpiderProxyServer : java.io.Closeable {
    // Guard 插件在内部把「宿主本地代理」写死为 9978（FongMi / Marco 的宿主端口），
    // 只有真正监听 9978 时它才会把分片请求发到宿主；否则插件自带的回环请求全部落空。
    private val server: ServerSocket = run {
        val addr = InetAddress.getByName("127.0.0.1")
        val fixed = runCatching { ServerSocket(9978, 16, addr) }.getOrNull()
        if (fixed != null) {
            android.util.Log.i("NextMovieTiming", "proxy-fixed-port 9978")
            fixed
        } else {
            android.util.Log.w("NextMovieTiming", "proxy-fixed-port 9978 busy, fallback random")
            ServerSocket(0, 16, addr)
        }
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var route: ((Map<String, String>) -> Array<*>?)? = null

    init {
        // 让 ported catvod 宿主层的 Spider.proxyUrl() / Proxy.getUrl() 指向本服务，
        // 否则插件拿到 http://127.0.0.1:-1/proxy 这类无效地址，网盘解析会整段超时。
        runCatching { com.github.catvod.Proxy.set(server.localPort) }
            .onFailure { android.util.Log.w("NextMovieTiming", "Proxy.set failed: $it") }
        android.util.Log.i("NextMovieTiming", "proxy-port ${server.localPort}")
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                try {
                    pool.execute {
                        try {
                            socket.use { runCatching { serve(it) } }
                        } finally {
                            sockets.remove(socket)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }, "spider-proxy-accept").apply {
            isDaemon = true
            start()
        }
    }

    val port: Int get() = server.localPort

    fun activate(handler: (Map<String, String>) -> Array<*>?) {
        route = handler
    }

    fun rewrite(url: String): String {
        // FongMi / Marco 形态：插件直接把 `proxy://?do=jar&siteKey=...` 交给播放器，
        // 宿主必须把它接成自己的回环接口，否则播放器会当成非法协议。
        val raw = url.trim()
        if (raw.startsWith("proxy://", ignoreCase = true)) {
            val rest = raw.substring(8)
            val query = when {
                rest.startsWith("/") -> rest.substringAfter('?', "")
                rest.startsWith("?") -> rest.substring(1)
                else -> rest
            }
            return if (query.isBlank()) url else "http://127.0.0.1:${server.localPort}/proxy?$query"
        }
        // 端口可能是 -1（插件没拿到宿主端口时写成 -1），java.net.URI 会直接判非法，
        // 所以这里按字符串拆，port <= 0 一律视作「在找宿主代理」。
        val m = Regex("^https?://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):(-?\\d+)(/[^?#]*)(\\?.*)?$")
            .find(url) ?: return url
        val declared = m.groupValues[1].toIntOrNull() ?: -1
        val path = m.groupValues[2]
        val query = m.groupValues[3]
        // Only the TVBox host callback endpoint belongs to us. GoProxy and other
        // plugin-owned servers expose /proxy/play/... on their own port; moving
        // those requests to this query-parameter dispatcher loses the route.
        if (path !in listOf("/proxy", "/proxy/") || query.isEmpty()) return url
        if (declared > 0 && declared !in 9978..9999 && declared != port) return url
        return "http://127.0.0.1:${server.localPort}$path$query"
    }

    private class Cached(val status: Int, val mime: String, val headers: Map<* , *>, val body: ByteArray)

    /** 播放器会重复请求同一个分片（seek / 拖回），命中即回，省一次插件往返。 */
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
            size > 24
    }
    private val cacheLock = Any()
    private val ALLOW_RESPONSE = setOf(
        "content-length", "content-range", "accept-ranges", "location",
        "cache-control", "content-disposition", "etag", "last-modified"
    )

    private fun cached(key: String?): Cached? {
        if (key == null) return null
        return synchronized(cacheLock) { cache[key] }
    }

    private fun store(key: String?, entry: Cached) {
        if (key == null || entry.body.size > 4 * 1024 * 1024) return
        synchronized(cacheLock) {
            cache[key] = entry
            while (cache.size > 24) {
                val it = cache.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 20_000
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 8192) {
                val c = input.read()
                if (c < 0 || c == 10) break
                if (c != 13) bytes.write(c)
            }
            return bytes.toString("ISO-8859-1")
        }
        val first = line().split(' ')
        if (first.size < 2) return
        val method = first[0]
        runCatching {
            android.util.Log.i("NextMovieProxyReq", "in $method path=" + first[1].substringBefore('?').take(80) +
                " qlen=" + first[1].length + " route=" + (route != null))
        }
        val params = LinkedHashMap<String, String>()
        val uri = runCatching { URI(first[1]) }.getOrNull() ?: return
        uri.rawQuery?.split('&')?.forEach { pair ->
            val kv = pair.split('=', limit = 2)
            params[URLDecoder.decode(kv[0], "UTF-8")] =
                URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        // 丢弃 hop-by-hop；其余请求头（含 Range / If-Range / Origin）原样进 params，
        // 键用小写，与 FongMi / Marco 宿主约定一致，便于插件 Map 取值。
        val dropRequest = setOf("host", "connection", "transfer-encoding", "content-length", "expect")
        var headerCount = 0
        while (headerCount++ < 100) {
            val h = line()
            if (h.isEmpty()) break
            val key = h.substringBefore(':').trim().lowercase()
            if (key.isNotEmpty() && key !in dropRequest) {
                params[key] = h.substringAfter(':').trim()
            }
        }
        // 部分插件用 method 字段区分 GET/HEAD
        params.putIfAbsent("method", method)

        val cacheKey = params["url"]?.takeIf { it.isNotBlank() }?.let { "${it}\u0000${params["range"].orEmpty()}" }
        val hit = cached(cacheKey)

        out@ run {
            val outStream = socket.getOutputStream().buffered()
            fun head(status: Int, mime: String, headers: Any?) {
                val reason = when (status) {
                    206 -> "Partial Content"
                    200 -> "OK"
                    else -> "Error"
                }
                outStream.write("HTTP/1.1 $status $reason\r\nContent-Type: $mime\r\nConnection: close\r\n".toByteArray())
                (headers as? Map<*, *>)?.forEach { (k, v) ->
                    val key = k.toString()
                    val value = v.toString()
                    if (key.lowercase() in ALLOW_RESPONSE && !value.contains('\r') && !value.contains('\n')) {
                        outStream.write("$key: $value\r\n".toByteArray())
                    }
                }
                outStream.write("\r\n".toByteArray())
            }

            if (hit != null) {
                head(hit.status, hit.mime, hit.headers)
                if (method != "HEAD") outStream.write(hit.body)
                outStream.flush()
                return@run
            }

            var reply: Array<*>? = null
            var status = 502
            var body: InputStream? = null
            if (method in listOf("GET", "HEAD")) {
                // 插件偶发一次网络抖动就回 null/5xx，重试比把错误甩给播放器划算。
                for (attempt in 1..3) {
                    val r = runCatching { route?.invoke(params) }.getOrNull()
                    val st = (r?.getOrNull(0) as? Number)?.toInt() ?: 502
                    val b = r?.getOrNull(2)
                    if (st in 200..299 && b != null) {
                        reply = r; status = st; break
                    }
                    if (attempt < 3) Thread.sleep(80L * attempt)
                }
            }
            body = when (val v = reply?.getOrNull(2)) {
                is InputStream -> v
                is ByteArray -> v.inputStream()
                is String -> v.byteInputStream()
                else -> null
            }
            if (status !in 200..299 || body == null) {
                status = 502
                head(502, "text/plain", null)
                if (method != "HEAD") outStream.write("Plugin proxy unavailable".toByteArray())
                outStream.flush()
                return@run
            }
            val mime = reply?.getOrNull(1)?.toString()?.takeUnless { it.contains('\r') || it.contains('\n') } ?: "application/octet-stream"
            val headers = reply?.getOrNull(3)
            body.use { stream ->
                head(status, mime, headers)
                if (method != "HEAD") {
                    // 小体量（分片）先收全再写：既能命中缓存，也不占着插件流。
                    val buf = java.io.ByteArrayOutputStream()
                    var total = 0L
                    var cachedAll = true
                    val chunkBuf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(chunkBuf)
                        if (n <= 0) break
                        total += n
                        if (total > 4L * 1024 * 1024) cachedAll = false
                        if (cachedAll) {
                            buf.write(chunkBuf, 0, n)
                        } else {
                            buf.writeTo(outStream)
                            outStream.write(chunkBuf, 0, n)
                            buf.reset()
                            break
                        }
                    }
                    if (cachedAll) {
                        outStream.write(buf.toByteArray())
                        store(cacheKey, Cached(status, mime, (headers as? Map<*, *>) ?: emptyMap<String, String>(), buf.toByteArray()))
                    } else {
                        stream.copyTo(outStream)
                    }
                }
                outStream.flush()
            }
        }
    }

    override fun close() {
        route = null
        server.close()
        pool.shutdownNow()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
