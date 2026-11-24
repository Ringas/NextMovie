package com.nextmovie.core.net

import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

/**
 * DNS 结果的政治判断：哪些 IP 一看就是投毒/劫持产物，不能拿来连接。
 * 抽成纯 Kotlin 对象（不依赖 android.util.Log），方便单测覆盖。
 */
object DnsPolicy {

    /** 常见投毒返回值：境外社媒/搜索 IP 段，国内源站不可能解析到这些。 */
    private val POISON_PREFIXES = listOf(
        "104.244.",  // Twitter / X
        "199.59.",   // Twitter 旧段
        "31.13.",    // Facebook
        "157.240.",  // Facebook（实测本域名被投成 157.240.17.41）
        "66.220.", "69.63.", "69.171.", "173.252.", // Facebook 其它段
        "108.160.",  // Dropbox（实测本域名被投成 108.160.169.178）
        "8.7.198.",
        "46.82.",
        "59.24.",
        "0.0.0.0",
        "127."
    )

    /** IPv6 投毒：2a03:2880 是 Facebook 段，实测本域名被投成 2a03:2880:f10d:183:face:b00c:... */
    private val POISON_V6_PREFIXES = listOf("2a03:2880", "2607:fb90", "2a03:2880:f10d")

    fun isPoisoned(ip: String): Boolean {
        val v4 = POISON_PREFIXES.any { ip.startsWith(it) }
        val v6 = POISON_V6_PREFIXES.any { ip.startsWith(it, ignoreCase = true) }
        return v4 || v6
    }

    /** 是否「可能连通」：排除回环/内网/任意地址/投毒段。 */
    fun isUsable(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress) return false
        if (addr.isSiteLocalAddress) return false
        val ip = addr.hostAddress ?: return false
        return !isPoisoned(ip)
    }

    /** A 记录排前、AAAA 仅兜底（盒子常无 IPv6 出口）。 */
    fun v4first(list: List<InetAddress>): List<InetAddress> =
        list.filterIsInstance<Inet4Address>() + list.filterNot { it is Inet4Address }

    fun usable(list: List<InetAddress>): List<InetAddress> =
        v4first(list.filter { isUsable(it) })

    fun v4Of(list: List<InetAddress>): List<InetAddress> =
        list.filterIsInstance<Inet4Address>()

    /**
     * 兼容阿里 / Cloudflare / Google 的 JSON 格式：Answer[] 里取 A 记录。
     * 顺手把投毒段剔掉；若过滤后为空，说明应答整份都不可信，返回空让上层换下一个 DoH。
     */
    fun parseAnswers(json: String): List<InetAddress> {
        if (json.isBlank()) return emptyList()
        val answer = runCatching { JSONObject(json).optJSONArray("Answer") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<InetAddress>(4)
        for (i in 0 until answer.length()) {
            val item = answer.optJSONObject(i) ?: continue
            if (item.optInt("type", 0) != 1) continue
            val ip = item.optString("data", "").trim()
            if (ip.isEmpty() || isPoisoned(ip)) continue
            runCatching { InetAddress.getByName(ip) }.getOrNull()?.let { out += it }
        }
        return v4first(out)
    }
}
