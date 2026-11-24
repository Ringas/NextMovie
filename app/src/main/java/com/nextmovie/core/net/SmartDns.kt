package com.nextmovie.core.net

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 抗 DNS 污染的解析器。
 *
 * 电视盒子常见事故：路由器/运营商 DNS 被投毒，把源站域名解析到境外无关 IP
 * （实测 `caiji.dyttzyapi.com` 被解析成 Twitter 的 `104.244.43.208`），TCP 连接必然
 * 12 秒超时，用户看到的就是「数据源加载失败 failed to connect to ... after 12000ms」。
 *
 * 策略：先问系统 DNS；只有结果「不可用」（空 / 回环 / 内网 / 已知投毒段）时才走 DoH
 * 重新解析（Cloudflare 1.1.1.1 → Google 8.8.8.8 → 阿里 223.5.5.5，逐个降级）。
 * 正常网络下不会多一次请求；被污染时 DoH 给出的 CNAME 真身（23.225.x）能连上。
 */
object SmartDns : Dns {

    private const val TAG = "NextMovieDns"
    private const val TTL_MS = 10 * 60 * 1000L

    private class Entry(val ips: List<InetAddress>, val expireAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** DoH 引导客户端：必须用系统 DNS，不能挂回自己，否则递归。 */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val CF = "https://1.1.1.1/dns-query?name=%s&type=A"
    private val GOOGLE = "https://8.8.8.8/resolve?name=%s&type=A"
    private val ALI = "https://223.5.5.5/resolve?name=%s&type=A"

    /** 用户可选的解析策略，见设置页「DNS 解析」。 */
    const val MODE_AUTO = "auto"        // 系统优先，污染时才 DoH
    const val MODE_SYSTEM = "system"    // 只信系统 DNS
    const val MODE_CLOUDFLARE = "cloudflare"
    const val MODE_GOOGLE = "google"
    const val MODE_ALIYUN = "aliyun"
    const val MODE_CUSTOM = "custom"    // 自定义 DoH 模板，必须含 %s

    @Volatile private var mode: String = MODE_AUTO
    @Volatile private var customTemplate: String = ""

    fun applyConfig(mode: String, custom: String = "") {
        this.mode = mode.ifBlank { MODE_AUTO }
        this.customTemplate = custom.trim()
        cache.clear()
        Log.d(TAG, "DNS 模式=$mode endpoints=${endpoints().size}")
    }

    fun currentMode(): String = mode

    private fun endpoints(): List<String> = when (mode) {
        MODE_SYSTEM -> emptyList()
        MODE_CUSTOM -> if (customTemplate.contains("%s")) listOf(customTemplate) else emptyList()
        MODE_CLOUDFLARE -> listOf(CF)
        MODE_GOOGLE -> listOf(GOOGLE)
        MODE_ALIYUN -> listOf(ALI)
        else -> listOf(CF, GOOGLE, ALI)
    }

    /** 指定了具体 DoH 供应商时，先 DoH 再系统，用户意图明确；auto 则相反。 */
    private fun dohFirst(): Boolean = mode != MODE_AUTO && mode != MODE_SYSTEM && endpoints().isNotEmpty()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expireAt > now) return it.ips }

        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val usable = DnsPolicy.usable(system)
        // 有可用的 A 记录就直接用；只拿到 AAAA 时不能信——盒子多半没有 IPv6 出口，
        // 而且实测被投毒的正是 AAAA（2a03:2880...:face:b00c），连上去必卡 12 秒。
        val v4 = DnsPolicy.v4Of(usable)
        if (!dohFirst() && v4.isNotEmpty()) {
            cache[hostname] = Entry(v4, now + TTL_MS)
            Log.d(TAG, "host=$hostname via=系统 ${v4.map { it.hostAddress }} thread=${Thread.currentThread().name}")
            return v4
        }

        val doh = resolveByDoh(hostname)
        if (doh.isNotEmpty()) {
            Log.d(
                TAG,
                "host=$hostname via=DoH system=${system.map { it.hostAddress }} 判定为污染，得到 ${doh.map { it.hostAddress }} " +
                    "thread=${Thread.currentThread().name}"
            )
            cache[hostname] = Entry(doh, now + TTL_MS)
            return doh
        }
        if (usable.isNotEmpty()) {
            // 走到这里说明只有 v6，且 DoH 也没拿到 A 记录：只能赌设备有 IPv6 出口
            cache[hostname] = Entry(usable, now + TTL_MS)
            Log.d(TAG, "host=$hostname via=仅IPv6 兜底 ${usable.map { it.hostAddress }} thread=${Thread.currentThread().name}")
            return usable
        }
        throw UnknownHostException("无法解析 $hostname（系统 DNS 与 DoH 均失败）")
    }

    /** 设置页自检：把系统解析与 DoH 解析结果并排给出，方便判断是不是被投毒。 */
    fun diagnose(hostname: String): String {
        val sys = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
            .map { it.hostAddress ?: "?" }
        val eps = endpoints()
        val doh = if (eps.isEmpty()) emptyList() else {
            val tmp = ArrayList<InetAddress>()
            for (t in eps) {
                val got = runCatching {
                    dohClient.newCall(
                        Request.Builder().url(String.format(t, hostname))
                            .header("accept", "application/dns-json").get().build()
                    ).execute().use { r ->
                        if (!r.isSuccessful) emptyList() else DnsPolicy.parseAnswers(r.body?.string().orEmpty())
                    }
                }.getOrDefault(emptyList())
                tmp += got
                if (got.isNotEmpty()) break
            }
            tmp.map { it.hostAddress ?: "?" }
        }
        val verdict = when {
            mode == MODE_SYSTEM -> "仅系统 DNS 模式，未启用 DoH（加载失败可切回自动抗污染）"
            sys.isEmpty() && doh.isEmpty() -> "两边都没解析到，检查网络"
            sys.any { DnsPolicy.isPoisoned(it) } && doh.isNotEmpty() -> "系统结果疑似被投毒，DoH 可用"
            sys.isNotEmpty() && doh.isEmpty() -> "DoH 不可达（可能被网络拦截），将回退系统 DNS"
            mode == MODE_CUSTOM && endpoints().isEmpty() -> "自定义地址无效：模板里必须含 %s"
            else -> "解析正常"
        }
        return buildString {
            append("系统 DNS：").append(if (sys.isEmpty()) "失败" else sys.joinToString(", "))
            append("\nDoH：").append(if (eps.isEmpty()) "未启用" else if (doh.isEmpty()) "失败" else doh.joinToString(", "))
            append("\n判定：").append(verdict)
        }
    }

    private fun resolveByDoh(hostname: String): List<InetAddress> {
        for (template in endpoints()) {
            val url = String.format(template, hostname)
            val got = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("accept", "application/dns-json")
                    .get()
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    DnsPolicy.parseAnswers(resp.body?.string().orEmpty())
                }
            }.getOrElse {
                Log.w(TAG, "DoH $url 失败：${it.javaClass.simpleName} ${it.message}")
                emptyList()
            }
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

}
