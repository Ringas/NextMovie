package com.nextmovie.core.net

import java.net.IDN
import java.net.URI

/** 中文域名（如 `www.饭太硬.cc`）转 punycode，OkHttp 只接受 ASCII host。 */
object Urls {

    private val httpUrl = Regex(
        "^(https?://)([^/?#]+)([/?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    fun ascii(raw: String): String {
        if (raw.isBlank() || raw.all { it.code < 128 }) return raw
        // 优先正则拆 host：Java URI 对非 ASCII host 常解析失败，导致无法 punycode
        httpUrl.matchEntire(raw.trim())?.let { m ->
            val scheme = m.groupValues[1]
            val hostPort = m.groupValues[2]
            val rest = m.groupValues.getOrNull(3).orEmpty()
            val colon = hostPort.lastIndexOf(':')
            val (host, port) = if (colon > 0 && hostPort.substring(colon + 1).all { it.isDigit() }) {
                hostPort.substring(0, colon) to hostPort.substring(colon)
            } else {
                hostPort to ""
            }
            val asciiHost = runCatching {
                host.split('.').joinToString(".") { label ->
                    if (label.all { it.code < 128 }) label else IDN.toASCII(label)
                }
            }.getOrNull() ?: return raw
            return scheme + asciiHost + port + rest
        }
        return runCatching {
            val u = URI(raw)
            val host = u.host ?: return raw
            val p = IDN.toASCII(host)
            URI(u.scheme, u.userInfo, p, u.port, u.path, u.query, u.fragment).toString()
        }.getOrDefault(raw)
    }

    fun resolve(base: String, link: String): String = runCatching {
        URI(base).resolve(link).toString()
    }.getOrDefault(link)
}
