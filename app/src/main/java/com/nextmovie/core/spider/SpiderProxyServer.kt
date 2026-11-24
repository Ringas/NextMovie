package com.nextmovie.core.spider

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Loopback-only streaming bridge for CatVod proxy(Map) results. Streams stay out of Binder. */
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
                        try { socket.use { runCatching { serve(it) } } }
                        finally { sockets.remove(socket) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }, "spider-proxy-accept").apply { isDaemon = true; start() }
    }
    val port: Int get() = server.localPort
    fun activate(handler: (Map<String, String>) -> Array<*>?) { route = handler }
    fun rewrite(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host !in listOf("127.0.0.1", "localhost", "::1")) return url
        if (uri.scheme !in listOf("http", "https")) return url
        // Only the TVBox host callback endpoint belongs to us. GoProxy and other
        // plugin-owned servers expose /proxy/play/... on their own port; moving
        // those requests to this query-parameter dispatcher loses the route.
        if (uri.path !in listOf("/proxy", "/proxy/") || uri.rawQuery.isNullOrBlank()) return url
        if (uri.port !in 9978..9999 && uri.port != port) return url
        return "http://127.0.0.1:${server.localPort}" + (uri.rawPath ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
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
        val uri = URI(first[1])
        uri.rawQuery?.split('&')?.forEach { pair ->
            val kv = pair.split('=', limit = 2)
            params[URLDecoder.decode(kv[0], "UTF-8")] = URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        var headerCount = 0
        while (headerCount++ < 100) {
            val h = line()
            if (h.isEmpty()) break
            val key = h.substringBefore(':').trim().lowercase()
            if (key !in listOf("host", "connection", "transfer-encoding", "content-length")) params[key] = h.substringAfter(':').trim()
        }
        val reply = if (method in listOf("GET", "HEAD")) route?.invoke(params) else null
        val status = (reply?.getOrNull(0) as? Number)?.toInt() ?: 502
        val mime = reply?.getOrNull(1)?.toString()?.takeUnless { it.contains('\r') || it.contains('\n') } ?: "text/plain"
        val body: InputStream = when (val value = reply?.getOrNull(2)) {
            is InputStream -> value
            is ByteArray -> value.inputStream()
            is String -> value.byteInputStream()
            else -> "Plugin proxy unavailable".byteInputStream()
        }
        body.use { stream ->
            val out = socket.getOutputStream().buffered()
            val reason = if (status in 200..299) "OK" else "Error"
            out.write("HTTP/1.1 $status $reason\r\nContent-Type: $mime\r\nConnection: close\r\n".toByteArray())
            (reply?.getOrNull(3) as? Map<*, *>)?.forEach { (k, v) ->
                val key = k.toString(); val value = v.toString()
                if (key.lowercase() in listOf("content-length", "content-range", "accept-ranges", "location", "cache-control") &&
                    !value.contains('\r') && !value.contains('\n')) out.write("$key: $value\r\n".toByteArray())
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
