package com.nextmovie.core.pan

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 移动云盘（139）自建客户端。
 *
 * 走的是官方「新个人云」接口 https://personal-kd-njs.yun.139.com/hcy ：
 *  - 凭据来自宿主 WebView 扫码登录后的 cookie（authorization 头 + ud_id）
 *  - 每个请求都要带 mcloud-sign 签名，算法取自官方前端 getNewSign：
 *      S = 字符排序( encodeURIComponent(JSON.stringify(body)) )
 *      r = md5( base64(S) )
 *      l = md5( "yyyy-MM-dd HH:mm:ss" + ":" + 16位随机 )
 *      sign = UPPER( md5(r + l) )
 *
 * 这条通道与插件无关：插件没有 139 写入能力，所以 139 由宿主自己接管。
 */
object Yun139Api {

    private const val TAG = "NextMovie139"
    private const val BASE = "https://personal-kd-njs.yun.139.com/hcy"
    private const val PREFS = "yun139"
    private const val DEVICE =
        "||9|7.18.3|chrome|120.0.0.0|72ce0db89d58dd74af22661bfb709cbe||windows 10||zh-CN|||"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 会话：authorization 是 "Basic base64(pc:账号:令牌)"，凭它就能调全量接口。 */
    data class Session(val authorization: String, val account: String, val udId: String)

    fun hasSession(context: Context): Boolean = session(context) != null

    fun session(context: Context): Session? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val auth = sp.getString("authorization", null) ?: return null
        if (auth.isBlank()) return null
        return Session(auth, sp.getString("account", "").orEmpty(), sp.getString("udId", "").orEmpty())
    }

    /** 扫码成功后调用：从 WebView 的 cookie 串里抠出 authorization / 账号 / ud_id 并落盘。 */
    fun storeSession(context: Context, cookie: String): Boolean {
        fun field(name: String): String =
            cookie.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=').orEmpty()

        val auth = field("authorization").ifBlank { field("Authorization") }
        if (auth.isBlank()) {
            Log.w(TAG, "store-session 失败：cookie 里没有 authorization")
            return false
        }
        val account = field("ORCHES-I-ACCOUNT-SIMPLIFY").ifBlank { field("userInfo") }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("authorization", auth)
            .putString("account", account)
            .putString("udId", field("ud_id"))
            .apply()
        Log.i(TAG, "store-session ok account=$account authLen=${auth.length}")
        return true
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---------- 签名 ----------

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 严格对齐 JS 的 encodeURIComponent：只放过 A-Za-z0-9 - _ . ! ~ * ' ( ) */
    private fun urlEncode(s: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val sb = StringBuilder()
        for (ch in s) {
            if (keep.indexOf(ch) >= 0) { sb.append(ch); continue }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            for (b in bytes) sb.append('%').append("%02X".format(b))
        }
        return sb.toString()
    }

    fun sign(body: String, ts: String, rnd: String): String {
        val sorted = urlEncode(body).toCharArray().sorted().joinToString("")
        val r = md5(android.util.Base64.encodeToString(sorted.toByteArray(), android.util.Base64.NO_WRAP))
        val l = md5("$ts:$rnd")
        return md5(r + l).uppercase(Locale.US)
    }

    private fun rand16(): String {
        val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (0 until 16).map { pool[Random.nextInt(pool.length)] }.joinToString("")
    }

    // ---------- 请求 ----------

    private fun post(context: Context, path: String, body: JSONObject): JSONObject {
        val s = session(context) ?: throw IllegalStateException("139 未登录")
        val bodyStr = body.toString()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val rnd = rand16()
        val signHdr = "$ts,$rnd," + sign(bodyStr, ts, rnd)

        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Authorization", s.authorization)
            setRequestProperty("mcloud-sign", signHdr)
            setRequestProperty("mcloud-client", "10701")
            setRequestProperty("mcloud-route", "001")
            setRequestProperty("mcloud-channel", "1000101")
            setRequestProperty("mcloud-version", "7.18.3")
            setRequestProperty("x-m4c-caller", "PC")
            setRequestProperty("x-m4c-src", "10002")
            setRequestProperty("x-inner-ntwk", "2")
            setRequestProperty("x-SvcType", "1")
            setRequestProperty("x-yun-svc-type", "1")
            setRequestProperty("x-huawei-channelSrc", "10000034")
            setRequestProperty("x-yun-channel-source", "10000034")
            setRequestProperty("x-yun-app-channel", "10000034")
            setRequestProperty("x-yun-module-type", "100")
            setRequestProperty("x-yun-api-version", "v1")
            setRequestProperty("INNER-HCY-ROUTER-HTTPS", "1")
            setRequestProperty("X-Deviceinfo", DEVICE)
            setRequestProperty("x-yun-client-info", DEVICE)
            setRequestProperty("CMS-DEVICE", "default")
            setRequestProperty("caller", "web")
            setRequestProperty("Origin", "https://yun.139.com")
            setRequestProperty("Referer", "https://yun.139.com/w/")
            setRequestProperty("User-Agent", UA)
        }
        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (text.isBlank()) throw IllegalStateException("139 接口返回空 body (HTTP $code)")
        return JSONObject(text)
    }

    // ---------- 业务 ----------

    data class Entry(
        val fileId: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val extension: String,
    )

    fun list(context: Context, parentFileId: String = "/"): List<Entry> {
        val body = JSONObject()
            .put("pageInfo", JSONObject().put("pageSize", 100).put("pageCursor", JSONObject.NULL))
            .put("orderBy", "updated_at")
            .put("orderDirection", "DESC")
            .put("parentFileId", parentFileId)
            .put("imageThumbnailStyleList", org.json.JSONArray().put("Small").put("Large"))
        val r = post(context, "/file/list", body)
        if (!r.optBoolean("success")) throw IllegalStateException("139 list 失败: " + r.optString("message"))
        val items = r.getJSONObject("data").optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                fileId = o.optString("fileId"),
                name = o.optString("name"),
                isFolder = o.optString("type") == "folder",
                size = o.optLong("size", 0),
                extension = o.optString("fileExtension"),
            )
        }
    }

    /** 取直链：返回的是 EOS 预签名 URL，支持 Range，可直接喂 ExoPlayer。 */
    fun downloadUrl(context: Context, fileId: String): String {
        val r = post(context, "/file/getDownloadUrl", JSONObject().put("fileId", fileId))
        if (!r.optBoolean("success")) throw IllegalStateException("139 直链失败: " + r.optString("message"))
        return r.getJSONObject("data").optString("url")
    }
}
