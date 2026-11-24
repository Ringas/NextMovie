package com.nextmovie.core.net

import java.net.IDN
import java.net.URI

/** 中文域名（如 `www.饭太硬.cc`）转 punycode，OkHttp 只接受 ASCII host。 */
object Urls {

    fun ascii(raw: String): String {
        if (raw.isBlank() || raw.all { it.code < 128 }) return raw
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
