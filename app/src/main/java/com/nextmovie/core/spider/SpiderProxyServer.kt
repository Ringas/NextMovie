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
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(4)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var route: ((Map<String, String>) -> Array<*>?)? = null

    init {
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

        val reply = if (method in listOf("GET", "HEAD")) route?.invoke(params) else null
        val status = (reply?.getOrNull(0) as? Number)?.toInt() ?: 502
        val mime = reply?.getOrNull(1)?.toString()
            ?.takeUnless { it.contains('\r') || it.contains('\n') }
            ?: "text/plain"
        val body: InputStream = when (val value = reply?.getOrNull(2)) {
            is InputStream -> value
            is ByteArray -> value.inputStream()
            is String -> value.byteInputStream()
            else -> "Plugin proxy unavailable".byteInputStream()
        }
        body.use { stream ->
            val out = socket.getOutputStream().buffered()
            val reason = when {
                status in 200..299 -> "OK"
                status == 206 -> "Partial Content"
                else -> "Error"
            }
            out.write("HTTP/1.1 $status $reason\r\nContent-Type: $mime\r\nConnection: close\r\n".toByteArray())
            val allowResponse = setOf(
                "content-length", "content-range", "accept-ranges", "location",
                "cache-control", "content-disposition", "etag", "last-modified"
            )
            (reply?.getOrNull(3) as? Map<*, *>)?.forEach { (k, v) ->
                val key = k.toString()
                val value = v.toString()
                if (key.lowercase() in allowResponse &&
                    !value.contains('\r') && !value.contains('\n')
                ) {
                    out.write("$key: $value\r\n".toByteArray())
                }
            }
            out.write("\r\n".toByteArray())
            if (method != "HEAD") stream.copyTo(out)
            out.flush()
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
