package com.nextmovie

import android.app.Application
import android.util.Log
import android.webkit.CookieManager

class NextMovieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.github.catvod.Runtime.set(this)
        // 插件扫码登录走 Runtime.dialog(options, callback)（FongMi 的 SpiderDialog）：
        // 宿主不装这个 handler，插件弹框直接失败，action 只回 "{}"。
        com.github.catvod.Runtime.setDialog { options, callback ->
            com.nextmovie.core.spider.SpiderDialogHost.show(options, callback)
        }
        listOf("play-raw.txt", "detail-raw.txt").forEach { java.io.File(filesDir, it).delete() }
        // CookieManager.getInstance() 会同步初始化整个 WebView 内核（冷启动 1.5–2s 的大头）。
        // Marco 冷启动 0.9s，我们之前 3.4s，差距几乎全在这里：把 WebView 初始化推到首帧之后。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { syncPanCookiesToWebView() }
        }, 1500L)
    }

    /**
     * Guard 插件解析网盘时，登录态往往不是从 spider.net 取，而是从系统 WebView 的
     * CookieManager 取（FongMi 的 JsHost -> WebViewCookies 就是包了它）。
     * 宿主扫码拿到的 Cookie 在这里同步进 CookieManager，插件查同一库即可拿到。
     */
    private fun syncPanCookiesToWebView() {
        val prefs = getSharedPreferences("nextmovie", MODE_PRIVATE)
        val map = mapOf(
            "quark" to "https://pan.quark.cn",
            "uc" to "https://drive.uc.cn",
            "ali" to "https://www.alipan.com",
            "baidu" to "https://pan.baidu.com"
        )
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            for ((id, url) in map) {
                val cookie = prefs.getString("pan_cookie_$id", "").orEmpty()
                // 匿名占位 __puus（AAAABBBBCCCC 这种）不是登录态，灌进 WebView 只会污染扫码页
                if (cookie.isBlank() || !com.nextmovie.core.pan.PanLogin.looksLoggedIn(id, cookie)) continue
                manager.setCookie(url, cookie)
                Log.i("NextMoviePan", "webview cookie sync id=$id len=${cookie.length} readback=" +
                    manager.getCookie(url).orEmpty().length)
            }
            manager.flush()
        }.onFailure { Log.w("NextMoviePan", "webview cookie sync failed: $it") }
    }
}
