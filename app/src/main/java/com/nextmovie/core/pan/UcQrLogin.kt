package com.nextmovie.core.pan

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * UC 网盘的官方扫码登录（宿主原生，不依赖插件）。
 *
 * 插件 `loginStart` 对 uc 返回 Unsupported，但 UC 的 CAS 扫码接口本身是公开可用的：
 * MarcoMovie 就是这么做的（`flutter/lib/core/netdisk_service.dart` / `netdisk_session.dart`）。
 *
 * 链路：
 *  1. GET api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?client_id=381&v=1.2&request_id=<ts>
 *     → data.members.token
 *  2. 码内容 = https://su.uc.cn/1_n0ZCv?...&token=<token>&client_id=381...
 *     用户用「UC浏览器」App 扫码（这张码只认 UC 自家的 App）
 *  3. 轮询 api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=381&token=<token>
 *     2000000 = 已确认，50004002 = 已过期，其余 = 等待/已扫未确认
 *  4. service_ticket → GET drive.uc.cn/account/mobileinfo?pr=UCBrowser&fr=h5&st=<ticket>
 *     → data.__pus
 *  5. 写回插件：setUcCookie("__pus=<pus>")，然后续播
 *
 * 全程只有 2 个短请求（申请码 / 轮询），2s 一次、串行、不并发，不影响播放。
 */
object UcQrLogin {

    private const val TAG = "NextMovieUc"
    private const val CLIENT_ID = "381"

    /** UC 的移动端 UA：cas 与 mobileinfo 都按这个 UA 发会话。 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36 UCBrowser/15.5.0.1258"

    data class Session(val token: String, val content: String)

    enum class State { WAITING, SCANNED, OK, EXPIRED, ERROR }

    data class Poll(val state: State, val ticket: String = "", val msg: String = "")

    /** 申请一张官方码；阻塞调用，放在 IO 线程。 */
    fun start(): Session? {
        val ts = System.currentTimeMillis()
        val url = "https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin" +
            "?client_id=$CLIENT_ID&v=1.2&request_id=$ts"
        val body = get(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optInt("status", -1) != 2000000) {
            Log.w(TAG, "uc-qr start status=" + json.optInt("status", -1))
            return null
        }
        val token = runCatching {
            json.getJSONObject("data").getJSONObject("members").optString("token")
        }.getOrNull().orEmpty()
        if (token.isBlank()) return null
        // 官方扫码短链：UC 浏览器扫这个才算数
        val content = "https://su.uc.cn/1_n0ZCv" +
            "?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt" +
            "&token=" + URLEncoder.encode(token, "UTF-8") +
            "&client_id=$CLIENT_ID" +
            "&uc_biz_str=" + URLEncoder.encode("S:custom|C:titlebar_fix", "UTF-8")
        Log.i(TAG, "uc-qr start token=" + token.take(10) + " contentLen=" + content.length)
        return Session(token, content)
    }

    /** 轮询一次扫码状态；阻塞调用，放在 IO 线程。 */
    fun poll(token: String): Poll {
        val ts = System.currentTimeMillis()
        val url = "https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken" +
            "?client_id=$CLIENT_ID&v=1.2&token=" + URLEncoder.encode(token, "UTF-8") +
            "&request_id=$ts"
        val body = get(url) ?: return Poll(State.ERROR, msg = "网络不通")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Poll(State.ERROR, msg = "返回不是 JSON")
        return when (val status = json.optInt("status", -1)) {
            2000000 -> {
                val ticket = runCatching {
                    json.getJSONObject("data").getJSONObject("members")
                        .optString("service_ticket")
                }.getOrNull().orEmpty()
                if (ticket.isBlank()) Poll(State.OK) else Poll(State.OK, ticket)
            }
            50004002 -> Poll(State.EXPIRED, msg = "二维码已过期")
            50004001 -> Poll(State.WAITING)
            // 50004003 等：已扫、等手机确认
            else -> {
                Log.i(TAG, "uc-qr poll status=$status")
                Poll(State.SCANNED)
            }
        }
    }

    /** 用 service_ticket 换 UC 会话 cookie；成功返回 "__pus=xxx"。 */
    fun exchange(ticket: String): String? {
        val url = "https://drive.uc.cn/account/mobileinfo?pr=UCBrowser&fr=h5&st=" +
            URLEncoder.encode(ticket, "UTF-8")
        val body = get(url, referer = "https://drive.uc.cn/") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val pus = runCatching { json.getJSONObject("data").optString("__pus") }.getOrNull()
            .orEmpty()
        if (pus.isBlank()) {
            Log.w(TAG, "uc-qr exchange 无 __pus")
            return null
        }
        Log.i(TAG, "uc-qr exchange ok pusLen=" + pus.length)
        return "__pus=$pus"
    }

    private fun get(url: String, referer: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                referer?.let { setRequestProperty("Referer", it) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) Log.w(TAG, "uc-qr http=$code body=" + text.take(80))
            text
        } catch (t: Throwable) {
            Log.w(TAG, "uc-qr http 失败 " + t.javaClass.simpleName + " " + t.message)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
