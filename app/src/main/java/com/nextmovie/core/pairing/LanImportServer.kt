package com.nextmovie.core.pairing

import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/** A short-lived LAN form. The random pairing secret is per dialog and never logged or persisted. */
class LanImportServer(private val received: (String) -> Unit) : java.io.Closeable {
    val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val listener = ServerSocket(0)
    val port get() = listener.localPort
    private val workers = Executors.newFixedThreadPool(2)
    private val expires = System.currentTimeMillis() + 5 * 60_000
    fun url(address: String) = "http://$address:$port/?token=$token"
    init {
        Thread({
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                workers.execute { socket.use { runCatching { handle(it) } } }
            }
        }, "config-pairing").apply { isDaemon = true; start() }
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val b = java.io.ByteArrayOutputStream()
            while (b.size() < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) b.write(c) }
            return b.toString("ISO-8859-1")
        }
        fun respond(code: Int, body: String, html: Boolean = false) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $code Response\r\nContent-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                    "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes); flush()
            }
        }
        val first = line().split(' ')
        if (first.size != 3) return
        val uri = runCatching { URI(first[1]) }.getOrNull() ?: return
        val headers = HashMap<String, String>()
        var count = 0
        while (count++ < 64) {
            val h = line(); if (h.isEmpty()) break
            headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
        }
        // See loop below: headers are read until the first empty line, not from the request body.
        val params = uri.rawQuery.orEmpty().split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val validToken = MessageDigest.isEqual(params["token"].orEmpty().toByteArray(), token.toByteArray())
        val local = socket.inetAddress.isSiteLocalAddress || socket.inetAddress.isLoopbackAddress || socket.inetAddress.isLinkLocalAddress
        if (!local || !validToken || System.currentTimeMillis() > expires) { respond(403, "配对已失效，请在电视上重新打开扫码导入"); return }
        val origin = headers["origin"]
        if (origin != null && origin != "http://${headers["host"]}") { respond(403, "请求来源不匹配"); return }
        if (first[0] == "GET" && uri.path == "/") {
            respond(200, """<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NextMovie 配置导入</title><style>body{font:18px system-ui;background:#111318;color:#f4f5f7;max-width:600px;margin:40px auto;padding:24px}textarea{box-sizing:border-box;width:100%;height:180px;font:16px system-ui;padding:16px;border-radius:12px}button{background:#e5c07b;border:0;padding:16px 28px;font-size:18px;border-radius:12px;margin-top:16px}p{line-height:1.6;color:#bbb}</style><h1>发送配置到电视</h1><p>粘贴配置网址或 JSON，发送后在电视上确认。此配对五分钟内有效。</p><form method="post" action="/import?token=$token"><textarea name="config" required maxlength="60000" placeholder="配置网址或 JSON"></textarea><button>发送到电视</button></form></html>""", true)
            return
        }
        if (first[0] != "POST" || uri.path != "/import") { respond(404, "Not found"); return }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 1..65536) { respond(413, "内容过大或为空"); return }
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
        if (read != length) { respond(400, "内容不完整"); return }
        val data = String(body, Charsets.UTF_8).split('&').firstOrNull { it.startsWith("config=") }?.substringAfter('=')
        val config = data?.let { URLDecoder.decode(it, "UTF-8") }?.trim().orEmpty()
        if (!isConfigInput(config)) { respond(400, "请输入 http(s) 配置网址或 JSON 对象"); return }
        received(config)
        respond(200, "已发送，请回到电视确认导入。可以关闭此页面。")
    }
    override fun close() { listener.close(); workers.shutdownNow() }
    companion object {
        fun isConfigInput(value: String): Boolean = value.startsWith("{") || runCatching {
            val uri = URI(value); uri.scheme in listOf("http", "https") && !uri.rawAuthority.isNullOrBlank()
        }.getOrDefault(false)
        fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }
}
