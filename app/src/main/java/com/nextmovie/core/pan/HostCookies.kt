package com.nextmovie.core.pan

import android.content.Context
import android.util.Log
import com.github.catvod.net.Net

/** 宿主侧 Cookie 存储：读写 nextmovie prefs 里的 pan_cookie_<id>。 */
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
        val value = prefs().getString("pan_cookie_$id", "").orEmpty()
        Log.i("NextMoviePanNet", "host-getCookie url=${url.take(120)} len=${value.length}")
        return value
    }

    override fun setCookie(url: String, cookie: String): Boolean {
        val id = idFor(url)
        if (id.isEmpty()) return false
        prefs().edit().putString("pan_cookie_$id", cookie).apply()
        Log.i("NextMoviePanNet", "host-setCookie id=$id len=${cookie.length}")
        return true
    }
}
