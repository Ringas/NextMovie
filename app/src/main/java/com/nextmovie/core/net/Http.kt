package com.nextmovie.core.net

import com.nextmovie.core.config.ConfigDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.TimeUnit

/** 统一网络出口：超时、UA、明文 HTTP、gzip 兜底解码。 */
class Http(providedClient: OkHttpClient? = null) {

    val client: OkHttpClient by lazy { providedClient ?: defaultClient() }

    class HttpError(val code: Int, message: String) : IOException(message)

    suspend fun text(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        ua: String = UA,
        callTimeoutMs: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(normalize(url))
        builder.header("User-Agent", ua)
        headers.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        val call = clientFor(callTimeoutMs).newCall(builder.build())
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val decoded = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw HttpError(it.code, "HTTP ${it.code} @ $url")
                            val body = it.body ?: throw HttpError(-1, "空响应 @ $url")
                            ConfigDecoder.decode(body.bytes())
                        }
                    }
                    continuation.resumeWith(decoded)
                }
            })
        }
    }

    suspend fun bytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        ua: String = UA
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(normalize(url)).header("User-Agent", ua)
            headers.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpError(response.code, "HTTP ${response.code} @ $url")
                response.body?.bytes() ?: throw HttpError(-1, "空响应 @ $url")
            }
        }

    /** 同一 client 只按超时值缓存派生实例，避免每条请求都重建连接池。 */
    private val timedClients = HashMap<Int, OkHttpClient>()

    private fun clientFor(timeoutMs: Int?): OkHttpClient {
        if (timeoutMs == null || timeoutMs <= 0) return client
        return synchronized(timedClients) {
            timedClients.getOrPut(timeoutMs) {
                client.newBuilder()
                    .connectTimeout(minOf(timeoutMs, 12_000).toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            }
        }
    }

    private fun normalize(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.isBlank() -> throw HttpError(-1, "空地址")
        else -> url
    }

    companion object {
        /** 生态里多数源站按 UA 分流：给 okhttp 就吐配置，给浏览器 UA 就吐网页。 */
        const val UA = "okhttp/4.12.0"

        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        /**
         * 源站经常只给 IPv6 记录，而电视盒子/模拟器往往没有可用 IPv6 出口，
         * 直接连 AAAA 地址会卡到 12 秒超时。这里把 IPv4 排到前面，IPv6 仅作兜底。
         */
        private object Ipv4FirstDns : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrElse { emptyList() }
                if (all.isEmpty()) return all
                val v4 = all.filterIsInstance<Inet4Address>()
                val v6 = all.filterIsInstance<Inet6Address>()
                return v4 + v6
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(SmartDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
