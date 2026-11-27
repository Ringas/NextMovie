package com.nextmovie.core.pan

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 阿里云盘官方扫码登录（宿主自助，插件完全没有阿里能力）。
 *
 * 链路（对齐阿里 passport 与 MarcoMovie 的实现）：
 *  1. GET  passport.aliyundrive.com/newlogin/qrcode/generate.do?... → codeContent(码内容) / ck / t
 *  2. 用户用「阿里云盘」App 扫 codeContent
 *  3. POST passport.aliyundrive.com/newlogin/qrcode/query.do (ck,t) →
 *       qrCodeStatus: NEW 等待 / SCANED 已扫 / CONFIRMED 确认 / EXPIRED 过期
 *  4. CONFIRMED 时 data.bizExt 是 base64 JSON，里面有 refresh_token
 *  5. refresh_token → auth.aliyundrive.com/v2/account/token 换 access_token + deviceId，
 *     存到宿主 shared_prefs/ali_session（阿里不走插件，凭据由宿主自己持有并自己解析直链）
 */
object AliQrLogin {

    private const val TAG = "NextMovieAli"
    private const val PREFS = "ali_session"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    data class Session(val ck: String, val t: String, val content: String)

    enum class State { WAITING, SCANNED, CONFIRMED, EXPIRED, ERROR }

    data class Poll(val state: State, val bizExt: String = "", val msg: String = "")

    data class Credential(
        val refreshToken: String,
        val accessToken: String,
        val deviceId: String,
        val driveId: String,
        val userId: String,
    )

    /** 申请官方码；阻塞，放 IO 线程。 */
    fun start(): Session? {
        val ts = System.currentTimeMillis()
        val url = "https://passport.aliyundrive.com/newlogin/qrcode/generate.do" +
            "?appName=aliyun_drive&fromSite=52&appEntrance=web&_csrf_token=&isMobile=false" +
            "&lang=zh_CN&returnUrl=&hsbegin=$ts"
        val body = get(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = runCatching { json.getJSONObject("content").getJSONObject("data") }
            .getOrNull() ?: return null
        val content = data.optString("codeContent")
        val ck = data.optString("ck")
        val t = data.optString("t")
        if (content.isBlank() || ck.isBlank() || t.isBlank()) {
            Log.w(TAG, "ali-qr start 字段缺失")
            return null
        }
        Log.i(TAG, "ali-qr start contentLen=" + content.length + " t=" + t)
        return Session(ck, t, content)
    }

    /** 轮询一次；阻塞，放 IO 线程。 */
    fun poll(session: Session): Poll {
        val ts = System.currentTimeMillis()
        val form = listOf(
            "ck" to session.ck,
            "t" to session.t,
            "appName" to "aliyun_drive",
            "appEntrance" to "web",
            "fromSite" to "52",
            "isMobile" to "false",
            "lang" to "zh_CN",
            "returnUrl" to "",
            "navlanguage" to "zh-CN",
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val body = post(
            "https://passport.aliyundrive.com/newlogin/qrcode/query.do" +
                "?appName=aliyun_drive&fromSite=52&_bx-v=2.2.3",
            form,
        ) ?: return Poll(State.ERROR, msg = "网络不通")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Poll(State.ERROR, msg = "返回不是 JSON")
        val data = runCatching { json.getJSONObject("content").getJSONObject("data") }
            .getOrNull() ?: return Poll(State.ERROR, msg = "返回缺 data")
        return when (val status = data.optString("qrCodeStatus")) {
            "CONFIRMED" -> Poll(State.CONFIRMED, data.optString("bizExt"))
            "SCANED" -> Poll(State.SCANNED)
            "EXPIRED" -> Poll(State.EXPIRED, msg = "二维码已过期")
            "NEW" -> Poll(State.WAITING)
            else -> {
                Log.i(TAG, "ali-qr poll status=$status")
                Poll(State.WAITING)
            }
        }
    }

    /** bizExt(base64 JSON) → refresh_token。字段名在不同版本里有两种写法，递归兜底。 */
    fun refreshTokenFromBizExt(bizExt: String): String? {
        if (bizExt.isBlank()) return null
        val raw = runCatching {
            String(Base64.decode(bizExt, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return findString(json, "refreshToken", "refresh_token")
    }

    private fun findString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val v = json.optString(key)
            if (v.isNotBlank()) return v
        }
        val it = json.keys()
        while (it.hasNext()) {
            val k = it.next()
            val child = json.optJSONObject(k)
            if (child != null) findString(child, *keys)?.let { return it }
        }
        return null
    }

    /** exchange 正持有刷新权，AliApi 的 401 自动重试要避让，否则会无限递归。 */
    @Volatile
    var exchangInFlight = false
        private set

    /** refresh_token → access_token + deviceId；阻塞。 */
    fun exchange(refreshToken: String): Credential? {
        exchangInFlight = true
        try {
            return exchangeInner(refreshToken)
        } finally {
            exchangInFlight = false
        }
    }

    private fun exchangeInner(refreshToken: String): Credential? {
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
            .toString()
        val res = postJson("https://auth.aliyundrive.com/v2/account/token", body) ?: return null
        val json = runCatching { JSONObject(res) }.getOrNull() ?: return null
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { refreshToken }
        val device = json.optString("device_id")
        val user = runCatching { json.optJSONObject("user_id")?.toString() }.getOrNull().orEmpty()
        if (access.isBlank()) {
            Log.w(TAG, "ali-qr exchange 失败: " + res.take(120))
            return null
        }
        val partial = Credential(refresh, access, device, "", user)
        val driveId = runCatching { AliApi.driveId(partial) }.getOrDefault("")
        Log.i(TAG, "ali-qr exchange ok accessLen=" + access.length + " device=" + device.take(8) +
            " drive=" + driveId.take(6))
        return partial.copy(driveId = driveId)
    }

    // ---- 宿主会话存储（阿里不走插件，凭据由宿主持有）----

    fun store(context: Context, cred: Credential) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("refresh", cred.refreshToken)
            .putString("access", cred.accessToken)
            .putString("device", cred.deviceId)
            .putString("drive", cred.driveId)
            .apply()
        Log.i(TAG, "ali-session stored refreshLen=" + cred.refreshToken.length)
    }

    /** 阿里 API 层没有 Context，刷新后要落盘，这里挂一份 applicationContext。 */
    private var appCtx: Context? = null

    fun attach(context: Context) {
        appCtx = context.applicationContext
    }

    fun storeCached(cred: Credential) {
        appCtx?.let { store(it, cred) } ?: Log.w(TAG, "storeCached 没挂 context，跳过落盘")
    }

    fun has(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("refresh", "").orEmpty().isNotBlank()
        }.getOrDefault(false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun credential(context: Context): Credential? {
        attach(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val refresh = p.getString("refresh", "").orEmpty()
        if (refresh.isBlank()) return null
        return Credential(
            refreshToken = refresh,
            accessToken = p.getString("access", "").orEmpty(),
            deviceId = p.getString("device", "").orEmpty(),
            driveId = p.getString("drive", "").orEmpty(),
            userId = "",
        )
    }

    // ---- HTTP ----

    private fun get(url: String): String? = request(url, null, null)

    private fun post(url: String, form: String): String? =
        request(url, form, "application/x-www-form-urlencoded")

    private fun postJson(url: String, json: String): String? =
        request(url, json, "application/json")

    private fun request(url: String, body: String?, contentType: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = if (body == null) "GET" else "POST"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                contentType?.let { setRequestProperty("Content-Type", it) }
                if (body != null) doOutput = true
            }
            if (body != null) {
                conn.outputStream.use { os: OutputStream ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) Log.w(TAG, "ali-qr http=$code " + text.take(80))
            text
        } catch (t: Throwable) {
            Log.w(TAG, "ali-qr http 失败 " + t.javaClass.simpleName + " " + t.message)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
