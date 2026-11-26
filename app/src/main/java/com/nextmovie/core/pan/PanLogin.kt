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

    /** 登录态是否已有 Cookie（夸克/UC 走宿主 prefs，插件会通过 HostCookies 读到）。
     *  注意：WebView 里的匿名态会给出 `__puus=AAAABBBBCCCC` 这类占位值，
     *  它长得像 Cookie 但不是登录态，必须过滤，否则会误判"已登录"。 */
    fun has(context: android.content.Context, id: String): Boolean =
        looksLoggedIn(id, cookieFor(context, id))

    /**
     * PluginActivity 跑在独立进程里，它写的 SharedPreferences 主进程读不到（进程内缓存）。
     * 所以登录态以 files/cookie_<id>.txt 为准，prefs 只是给本进程用的副本。
     */
    fun cookieFor(context: android.content.Context, id: String): String {
        val fromPrefs = context.getSharedPreferences("nextmovie", android.content.Context.MODE_PRIVATE)
            .getString("pan_cookie_$id", "").orEmpty()
        val fromFile = runCatching {
            java.io.File(context.filesDir, "cookie_$id.txt").takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")
        return when {
            looksLoggedIn(id, fromFile) -> fromFile
            looksLoggedIn(id, fromPrefs) -> fromPrefs
            fromFile.isNotBlank() -> fromFile
            else -> fromPrefs
        }
    }

    /** 真登录态的 __puus/__pus 都很长；匿名占位值只有十来个字符。 */
    fun looksLoggedIn(id: String, cookie: String): Boolean {
        if (cookie.isBlank()) return false
        if (id == "quark" || id == "uc") {
            val puus = cookie.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("__puus=") }?.substringAfter('=').orEmpty()
            return puus.length >= 40
        }
        return true
    }

    /** 把多份 Cookie 合并且丢掉占位 __puus：同名键保留更长的那份。 */
    fun mergeCookies(vararg raws: String): String {
        val map = LinkedHashMap<String, String>()
        raws.filter { it.isNotBlank() }.forEach { raw ->
            raw.split(';').forEach { part ->
                val kv = part.trim()
                if (kv.isBlank() || !kv.contains('=')) return@forEach
                val key = kv.substringBefore('=')
                val value = kv.substringAfter('=')
                if (key == "__puus" && value.length < 40) return@forEach
                val old = map[key]
                if (old == null || value.length > old.length) map[key] = value
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }


    /** 把扫码拿到的 Cookie 落到宿主存储：prefs（插件 HostCookies 实时读）+ files（兼容旧通道）。 */
    fun persist(context: android.content.Context, id: String, cookie: String) {
        if (cookie.isBlank()) return
        context.getSharedPreferences("nextmovie", android.content.Context.MODE_PRIVATE)
            .edit().putString("pan_cookie_$id", cookie).apply()
        runCatching { java.io.File(context.filesDir, "cookie_$id.txt").writeText(cookie) }
        Log.i(TAG, "persist cookie id=$id len=${cookie.length}")
    }

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

    private val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    /**
     * Cookie 是否真的「登录态」。WebView 里常常残留 `__puus` 之类的匿名 cookie（长度也能到 100），
     * 只按 marker 判断会在没扫码时就误报登录成功、把扫码页直接关掉。
     * 真登录时 account/info 的 data 里才会出现 nickname / member_type。
     */
    fun verify(id: String, cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val url = when (id) {
            "uc" -> "https://drive.uc.cn/account/info?fr=pc"
            else -> "https://pan.quark.cn/account/info?fr=pc&platform=pc"
        }
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Cookie", cookie)
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
                val ok = data != null && (data.has("nickname") || data.has("member_type") ||
                    data.has("user_name") || data.has("puus"))
                Log.i(TAG, "verify $id code=${r.code} ok=$ok keys=${data?.keys()?.asSequence()?.joinToString()}")
                ok
            }
        }.getOrDefault(false)
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
