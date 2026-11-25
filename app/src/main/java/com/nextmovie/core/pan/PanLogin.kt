package com.nextmovie.core.pan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 网盘扫码登录（宿主侧实现，不依赖插件 UI）。
 *
 * 现状：夸克/UC 共用阿里 CAS 的扫码流程，可以拿到 serviceTicket 再换 Cookie；
 * 阿里云盘要 openapi clientId（来源没给），百度/嘟嘟走 passport，暂不内置。
 *
 * Cookie 落地：写进宿主 prefs（`pan_cookie_<id>`），并尝试反射注入来源里的
 * `com.github.catvod.api.*.setCookie` / `Pref.putString("cookie_<id>")`。
 */
object PanLogin {

    private const val TAG = "NextMoviePan"

    enum class State { IDLE, WAITING, SCANNED, SUCCESS, FAILED }

    data class Snapshot(
        val id: String,
        val state: State,
        val message: String,
        val qr: Bitmap? = null,
        val cookie: String = "",
    )

    private data class Provider(
        val id: String,
        val label: String,
        val tokenUrl: String,
        val qrImageUrl: (String) -> String,
        val pollUrl: (String) -> String,
        val cookieUrl: (String) -> String,
    )

    private val providers = listOf(
        Provider(
            id = "quark", label = "夸克",
            tokenUrl = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin",
            qrImageUrl = { "https://uop.quark.cn/cas/ajax/getQRCodeImage?token=$it" },
            pollUrl = { "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?token=$it" },
            cookieUrl = { "https://pan.quark.cn/account/info?st=$it&lname=quark&fr=android" },
        ),
        Provider(
            id = "uc", label = "UC",
            tokenUrl = "https://open.uc.cn/cas/ajax/getTokenForQrcodeLogin",
            qrImageUrl = { "https://open.uc.cn/cas/ajax/getQRCodeImage?token=$it" },
            pollUrl = { "https://open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?token=$it" },
            cookieUrl = { "https://drive.uc.cn/account/info?st=$it&lname=uc&fr=android" },
        ),
    )

    fun providers(): List<Pair<String, String>> = providers.map { it.id to it.label }

    private val jarCookies = ConcurrentHashMap<String, MutableMap<String, String>>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val bag = jarCookies.getOrPut(url.host) { ConcurrentHashMap() }
                    cookies.forEach { bag[it.name] = it.value }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val bag = jarCookies[url.host] ?: return emptyList()
                    return bag.mapNotNull { (k, v) -> Cookie.parse(url, "$k=$v") }
                }
            })
            .build()
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            Log.i(TAG, "GET ${url.take(80)} -> ${r.code} len=${body.length} ${body.take(120)}")
            return body
        }
    }

    private fun getBytes(url: String): ByteArray? = runCatching {
        Request.Builder().url(url).build().let { req ->
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }
    }.getOrNull()

    private fun qrBitmap(text: String, size: Int = 640): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        bmp
    }.getOrNull()

    /** 拿 token + 二维码。二维码优先用官方图片，拿不到就本地把 token 渲染成二维码。 */
    fun start(id: String, onUpdate: (Snapshot) -> Unit): Thread {
        val p = providers.firstOrNull { it.id == id }
        if (p == null) {
            onUpdate(Snapshot(id, State.FAILED, "这个来源没有内置扫码通道（仅支持夸克 / UC）"))
            return Thread {}
        }
        val worker = Thread {
            try {
                onUpdate(Snapshot(id, State.WAITING, "正在获取「${p.label}」登录二维码…"))
                val tokenJson = get(p.tokenUrl)
                val data = JSONObject(tokenJson).optJSONObject("data")
                // 夸克把 token 包在 data.members 里，UC 有的版本直接放 data.token
                val token = data?.optJSONObject("members")?.optString("token").orEmpty()
                    .ifBlank { data?.optString("token").orEmpty() }
                if (token.isBlank()) {
                    onUpdate(Snapshot(id, State.FAILED, "来源没有返回 token：${tokenJson.take(80)}"))
                    return@Thread
                }
                val imageBytes = getBytes(p.qrImageUrl(token))
                val bmp = imageBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                    ?: qrBitmap(token)
                onUpdate(Snapshot(id, State.WAITING, "用「${p.label}」App 扫码登录", bmp))

                var scannedNotified = false
                repeat(150) {   // 最多约 5 分钟
                    Thread.sleep(2_000)
                    val poll = runCatching { get(p.pollUrl(token)) }.getOrDefault("")
                    val data = runCatching { JSONObject(poll).optJSONObject("data") }.getOrNull()
                    val stateCode = runCatching { JSONObject(poll).optInt("status") }.getOrDefault(0)
                    val ticket = data?.optString("serviceTicket").orEmpty()
                    if (ticket.isNotBlank()) {
                        onUpdate(Snapshot(id, State.SCANNED, "已扫码，正在换取登录凭证…"))
                        val cookieBody = runCatching { get(p.cookieUrl(ticket)) }.getOrDefault("")
                        val bag = jarCookies.entries.firstOrNull { it.key.contains(p.id, true) || it.key.contains("quark") || it.key.contains("uc.cn") }
                        val cookie = bag?.value?.entries?.joinToString("; ") { "${it.key}=${it.value}" }.orEmpty()
                        if (cookie.isNotBlank()) {
                            Log.i(TAG, "login success ${p.label} cookieLen=${cookie.length}")
                            onUpdate(Snapshot(id, State.SUCCESS, "「${p.label}」登录成功", null, cookie))
                            return@Thread
                        }
                        onUpdate(Snapshot(id, State.FAILED, "换 Cookie 失败：${cookieBody.take(80)}"))
                        return@Thread
                    }
                    // 50004001 = "Query result is empty"，仍是未扫码；只有拿到 ticket 才算已扫
                    if (!scannedNotified && stateCode != 0 && stateCode != 2000000 && stateCode != 50004001) {
                        scannedNotified = true
                        onUpdate(Snapshot(id, State.SCANNED, "已扫码，请在手机上确认登录"))
                    }
                }
                onUpdate(Snapshot(id, State.FAILED, "二维码已过期，重新点一次「${p.label}」"))
            } catch (t: Throwable) {
                Log.i(TAG, "login error ${t.javaClass.simpleName}: ${t.message}")
                onUpdate(Snapshot(id, State.FAILED, "扫码失败：${t.message ?: t.javaClass.simpleName}"))
            }
        }
        worker.isDaemon = true
        worker.start()
        return worker
    }
}
