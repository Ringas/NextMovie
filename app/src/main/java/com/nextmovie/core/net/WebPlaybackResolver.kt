package com.nextmovie.core.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nextmovie.core.model.PlayResult
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Resolve an ordinary playback page through its normal browser requests. No DRM handling. */
object WebPlaybackResolver {
    private val media = Regex("(?i)\\.(m3u8|mp4|mkv|webm|mpd)(?:[?#]|$)")
    fun isMedia(url: String): Boolean = media.containsMatchIn(url)

    suspend fun resolve(context: Context, page: String, headers: Map<String, String>): PlayResult? =
        withContext(Dispatchers.Main) {
            var web: WebView? = null
            try {
                withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine { continuation ->
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        val browser = WebView(context).also { web = it }
                        fun found(url: String, requestHeaders: Map<String, String>) {
                            if (!isMedia(url)) return
                            handler.post {
                                if (continuation.isActive) {
                                    val resultHeaders = LinkedHashMap(headers)
                                    requestHeaders.filterKeys { key ->
                                        key.equals("User-Agent", true) || key.equals("Referer", true) || key.equals("Origin", true)
                                    }.forEach { (k, v) -> resultHeaders[k] = v }
                                    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
                                        ?.let { resultHeaders["Cookie"] = it }
                                    continuation.resume(PlayResult(url, resultHeaders))
                                }
                            }
                        }
                        browser.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mediaPlaybackRequiresUserGesture = false
                            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { userAgentString = it.value }
                        }
                        browser.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                request?.let { found(it.url.toString(), it.requestHeaders) }
                                return null
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.play().catch(function(){});return v.currentSrc||v.src||'';}return '';})()") { value ->
                                    val decoded = runCatching { org.json.JSONArray("[$value]").getString(0) }.getOrDefault("")
                                    found(decoded, mapOf("Referer" to page))
                                }
                            }
                        }
                        browser.loadUrl(page, headers)
                    }
                }
            } finally {
                web?.apply { stopLoading(); loadUrl("about:blank"); removeAllViews(); destroy() }
            }
        }
}
