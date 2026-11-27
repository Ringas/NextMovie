package com.nextmovie.core.pan

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 阿里云盘最小可用客户端（宿主自助，插件没有阿里能力）。
 *
 * 关键结论（真机 + 本机实测，别再退回 openapi）：
 *  - 二维码登录换到的是网页/App 共用 token，openapi.aliyundrive.com 一律 401
 *    TokenVerifyFailed；能用的基址是 api.aliyundrive.com。
 *  - 列目录：POST /adrive/v3/file/list（v1 的 openFile/list 在 web 基址上 404）。
 *  - 取直链：POST /v2/file/get_download_url，且必须带 Android 客户端的 x-canary 头；
 *    不带的话返回 200 但 url 是空串（服务端把网页端降级了）。
 *  - drive_id 来自 /v2/user/get 的 default_drive_id，同样要带 x-canary。
 *
 * 401（TokenVerifyFailed）时用 refresh_token 换一次再重试，并把新凭据写回。
 */
object AliApi {

    private const val TAG = "NextMovieAli"
    private const val API = "https://api.aliyundrive.com"

    /** 阿里靠这两个头判断是不是客户端，缺一个就降级成空直链。 */
    private const val UA = "AliApp(Android/2.10.0)"
    private const val CANARY = "client=Android,version=2.10.0,channel=wandoujia,device=Pixel"

    private const val FIELDS = "type,name,file_id,size,parent_file_id,mime_type,updated_at,thumbnail"

    /** 取 drive_id；cred.driveId 为空时现取一次。 */
    fun driveId(cred: AliQrLogin.Credential): String {
        if (cred.driveId.isNotBlank()) return cred.driveId
        val body = post("$API/v2/user/get", "{}", cred) ?: return ""
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        return json.optString("default_drive_id")
            .ifBlank { json.optString("resource_drive_id") }
    }

    /** 列目录；parent 传 "root" 即根目录。 */
    fun list(cred: AliQrLogin.Credential, parent: String = "root", limit: Int = 50): List<JSONObject> {
        val drive = driveId(cred)
        if (drive.isBlank()) {
            Log.w(TAG, "ali-list 没拿到 drive_id")
            return emptyList()
        }
        val body = JSONObject()
            .put("drive_id", drive)
            .put("parent_file_id", parent)
            .put("limit", limit)
            .put("order_by", "updated_at")
            .put("order", "desc")
            .put("fields", FIELDS)
            .toString()
        val res = post("$API/adrive/v3/file/list", body, cred) ?: return emptyList()
        val items = runCatching { JSONObject(res).optJSONArray("items") }.getOrNull() ?: return emptyList()
        val out = ArrayList<JSONObject>(items.length())
        for (i in 0 until items.length()) items.optJSONObject(i)?.let(out::add)
        Log.i(TAG, "ali-list n=" + out.size + " drive=" + drive.take(6))
        return out
    }

    /** 取直链；url 带时效签名（900s），播放前现取。 */
    fun downloadUrl(cred: AliQrLogin.Credential, fileId: String): String? {
        val drive = driveId(cred)
        if (drive.isBlank() || fileId.isBlank()) return null
        // 注意：绝对不能带 response_content_type / response_content_disposition。
        // 带上会让服务端签发「绑定 Host」的签名（x-oss-additional-headers=host），
        // 经 dl1-v6 这类 CDN 转发后 Host 被改写，必然 SignatureDoesNotMatch → 403。
        // 不带时签名只覆盖 path+query，实测 Range 206。
        val body = JSONObject()
            .put("drive_id", drive)
            .put("file_id", fileId)
            .put("expire_sec", 900)
            .toString()
        val res = post("$API/v2/file/get_download_url", body, cred) ?: return null
        val url = runCatching { JSONObject(res).optString("url") }.getOrDefault("")
        if (url.isBlank()) {
            Log.w(TAG, "ali-url 空 url，body=" + res.take(120))
            return null
        }
        Log.i(TAG, "ali-url ok len=" + url.length)
        return url
    }


    /** 双客户端探针：同一张直链分别用 HttpURLConnection 与 OkHttp 打 Range，定位是谁在坏签名。 */
    fun probeVariants(context: Context): String {
        val cred = AliQrLogin.credential(context) ?: return "无会话"
        val items = list(cred)
        var file = firstFile(items)
        if (file == null) {
            val queue = items.filter { it.optString("type") == "folder" }.take(6).toMutableList()
            repeat(2) {
                val next = ArrayList<JSONObject>()
                for (dir in queue) {
                    val sub = list(cred, dir.optString("file_id"), 20)
                    if (file == null) file = firstFile(sub)
                    next.addAll(sub.filter { it.optString("type") == "folder" })
                }
                queue.clear()
                queue.addAll(next.take(6))
                if (queue.isEmpty()) return@repeat
            }
        }
        if (file == null) return "无文件可探"
        val url = downloadUrl(cred, file.optString("file_id")) ?: return "取直链失败"
        Log.i(TAG, "ali-probe file=" + file.optString("name") + " url=" + url)
        val huc = probeRange(url)
        val ok = runCatching {
            val client = okhttp3.OkHttpClient.Builder().build()
            val req = okhttp3.Request.Builder().url(url)
                .header("Range", "bytes=0-1048575").build()
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()?.size ?: 0
                "okhttp http=" + resp.code + " type=" + resp.header("Content-Type") + " bytes=" + bytes
            }
        }.getOrElse { "okhttp 异常 " + it.javaClass.simpleName + ": " + it.message }
        Log.i(TAG, "ali-probe huc=" + huc)
        Log.i(TAG, "ali-probe " + ok)
        return huc + " | " + ok
    }

    /** Range 探针：只读 1MB 头，低并发，确认直链真能播。 */
    fun probeRange(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-1048575")
            }
            val t0 = System.currentTimeMillis()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val len = (stream ?: return "http=$code 无 body").use { input ->
                var n = 0
                val buf = ByteArray(64 * 1024)
                while (n < 1 shl 20) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    n += r
                }
                n
            }
            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
            "http=" + code + " type=" + conn.contentType + " bytes=" + len + " " + (len * 1000L / ms / 1024) + "KB/s"
        } catch (t: Throwable) {
            "探针失败 " + t.javaClass.simpleName + " " + t.message
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 登录后自检：根目录条目 + 下钻一层，第一个文件出直链并做 Range 探针。 */
    fun selfCheck(context: Context): String {
        val cred = AliQrLogin.credential(context) ?: return "无会话"
        val items = list(cred)
        Log.i(TAG, "ali-check root n=" + items.size + " names=" +
            items.joinToString("/") { it.optString("name") }.take(160))
        firstFile(items)?.let { return checkFile(cred, it) }
        for (dir in items.filter { it.optString("type") == "folder" }.take(4)) {
            firstFile(list(cred, dir.optString("file_id"), 20))?.let { return checkFile(cred, it) }
        }
        return "根目录 n=" + items.size + " 无文件"
    }

    /**
     * 自检目标：优先视频，其次非镜像类文件。
     * ISO 这类冷/归档文件落在内网桶（ccp-*-internal），公网直链签名不匹配会 403，
     * 与播放链路无关，别拿它当探针样本。
     */
    private fun firstFile(items: List<JSONObject>): JSONObject? =
        items.firstOrNull { it.optString("type") == "file" && it.optString("mime_type").startsWith("video") }
            ?: items.firstOrNull { it.optString("type") == "file" && !it.optString("name").endsWith(".iso", true) }

    private fun checkFile(cred: AliQrLogin.Credential, file: JSONObject): String {
        val url = downloadUrl(cred, file.optString("file_id"))
        if (url == null) return "文件 " + file.optString("name") + " 取直链失败"
        val probe = probeRange(url)
        Log.i(TAG, "ali-check file=" + file.optString("name") + " " + probe)
        return file.optString("name") + " -> " + probe
    }

    /** POST + 401 自动刷新一次。 */
    private fun post(url: String, body: String, cred: AliQrLogin.Credential): String? {
        val first = rawPost(url, body, cred.accessToken, cred.deviceId)
        if ((first.first == 401 || first.second.contains("TokenVerifyFailed")) &&
            !AliQrLogin.exchangInFlight
        ) {
            val fresh = AliQrLogin.exchange(cred.refreshToken) ?: return first.second
            AliQrLogin.storeCached(fresh)
            Log.i(TAG, "ali-api 401，已用 refresh 换新 token 重试")
            return rawPost(url, body, fresh.accessToken, fresh.deviceId).second
        }
        if (first.first !in 200..299) Log.w(TAG, "ali-api http=" + first.first + " " + first.second.take(100))
        return first.second
    }

    private fun rawPost(url: String, body: String, access: String, device: String): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 9000
                readTimeout = 15000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + access)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("x-canary", CANARY)
                if (device.isNotBlank()) setRequestProperty("x-device-id", device)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (t: Throwable) {
            Log.w(TAG, "ali-api 失败 " + t.javaClass.simpleName + " " + t.message)
            -1 to ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
