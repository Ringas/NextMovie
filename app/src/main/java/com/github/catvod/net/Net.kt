package com.github.catvod.net

import android.content.Context
import android.util.Log

/**
 * FongMi / Marco 生态里 `com.github.catvod.crawler.Spider` 的 `net` 字段类型。
 *
 * 第三方 Guard 插件解析网盘（夸克/UC/百度/阿里）时需要登录态，官方实现是把宿主
 * 的 Net（内部持有 Cookies 存储）赋给 spider.net，插件之后所有取 Cookie 的动作都
 * 走它。本类只实现插件实际会用到的那部分表面：Cookie 读写 + close，
 * 其余请求方法统一抛不支持，避免插件误用后静默失败。
 */
@Suppress("unused")
class Net : AutoCloseable {

    interface Cookies {
        fun getCookie(url: String): String?
        fun setCookie(url: String, cookie: String): Boolean
    }

    private val cookies: Cookies?
    private val siteKey: String

    constructor() : this(null, "")
    constructor(cookies: Cookies?) : this(cookies, "")
    constructor(cookies: Cookies?, siteKey: String?) {
        this.cookies = cookies
        this.siteKey = siteKey.orEmpty()
        Log.i(TAG, "Net created site=$siteKey cookies=${cookies != null}")
    }

    init {
        Log.i(TAG, "Net class initialized")
    }

    fun session(): Net = this

    fun getCookie(url: String?): String {
        val value = if (url == null) "" else cookies?.getCookie(url).orEmpty()
        Log.i(TAG, "getCookie site=$siteKey url=${url?.take(120)} len=${value.length}")
        return value
    }

    fun setCookie(url: String?, value: String?): Boolean {
        if (url == null || value == null) return false
        Log.i(TAG, "setCookie site=$siteKey url=${url.take(120)} len=${value.length}")
        return cookies?.setCookie(url, value) ?: false
    }

    fun sleep(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (_: Throwable) {
        }
    }

    override fun close() {}

    companion object {
        const val TAG = "NextMoviePanNet"

        fun logMissing(method: String) {
            Log.w(TAG, "unsupported Net.$method called by plugin; ignored")
        }
    }
}

/** 宿主侧的 Cookie 存储：读写 nextmovie prefs 里的 pan_cookie_<id>。 */
class HostCookies(private val context: Context) : Net.Cookies {

    private fun idFor(url: String): String = when {
        url.contains("quark.cn") -> "quark"
        url.contains("uc.cn") -> "uc"
        url.contains("alipan") || url.contains("aliyundrive") -> "ali"
        url.contains("baidu.com") || url.contains("baidupcs") -> "baidu"
        else -> ""
    }

    private fun prefs() = context.getSharedPreferences("nextmovie", Context.MODE_PRIVATE)

    override fun getCookie(url: String): String {
        val id = idFor(url)
        if (id.isEmpty()) return ""
        return prefs().getString("pan_cookie_$id", "").orEmpty()
    }

    override fun setCookie(url: String, cookie: String): Boolean {
        val id = idFor(url)
        if (id.isEmpty()) return false
        prefs().edit().putString("pan_cookie_$id", cookie).apply()
        Log.i(Net.TAG, "host stored cookie id=$id len=${cookie.length}")
        return true
    }
}
