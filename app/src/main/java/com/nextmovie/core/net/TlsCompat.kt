package com.nextmovie.core.net

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS 兼容层（P1）。
 *
 * drpy 源站里有相当一批是自签 / 过期 / 缺中间证书的证书链，浏览器点「继续访问」能进，
 * 但 OkHttp 默认严格校验会直接抛 `SSLHandshakeException`，表现就是「站点明明活着却打不开」。
 * 30 站回归里 cokemv / voflix / 凌云三站就是这一类。
 *
 * 策略：**只对 JS 源站抓取**（列表、详情、播放地址）做一次不校验证书的兜底重试。
 * 配置订阅、更新、网盘这些链路继续走严格校验——那里的中间人风险会直接影响可信性。
 * 每次走兼容模式都会打一条日志，方便事后回查是哪一站、哪条域名。
 */
object TlsCompat {

    private val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val factory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), java.security.SecureRandom())
        ctx.socketFactory
    }

    fun socketFactory(): SSLSocketFactory = factory

    private val anyHost: HostnameVerifier = HostnameVerifier { _, _ -> true }

    /** 由现有 client 派生一个「不校验证书」的等价实例，保留 DNS / 超时 / 连接池策略。 */
    fun OkHttpClient.withLenientTls(): OkHttpClient =
        newBuilder()
            .sslSocketFactory(factory, trustAll)
            .hostnameVerifier(anyHost)
            .build()

    /** 判断异常是否属于「证书/握手」这一类可兼容问题（OkHttp 会把它包几层）。 */
    fun isTlsProblem(e: Throwable?): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is javax.net.ssl.SSLException || t is java.security.cert.CertificateException) return true
            t = t.cause
            depth++
        }
        return false
    }
}
