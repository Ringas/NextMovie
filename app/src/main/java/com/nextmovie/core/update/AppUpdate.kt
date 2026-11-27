package com.nextmovie.core.update

import com.nextmovie.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 更新检查。
 *
 * 我们不发应用商店，更新只能靠用户自己装新包，所以检查更新只做一件事：
 * 拉一份公开的 JSON 清单，比 versionCode 大小，然后把下载地址给用户。
 *
 * 清单格式（放在任意 CDN / 静态目录即可）：
 * {
 *   "versionCode": 42,
 *   "versionName": "0.6.26-dev",
 *   "notes": "修复设置页焦点；新增运行日志",
 *   "apk": "https://xxx/nextmovie-0.6.26.apk",
 *   "page": "https://github.com/Ringas/NextMovie/releases"
 * }
 *
 * 拉不到不是错误——很多用户在墙内、或者清单还没发布。失败时给一句人话，
 * 而不是把 IOException 直接甩到电视上。
 */
object AppUpdate {

    /** 默认清单地址：与订阅同域，方便一起托管。 */
    const val DEFAULT_MANIFEST = "https://9280.kstore.vip/nextmovie-version.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val pageUrl: String,
    )

    sealed interface Result {
        data class Newer(val info: Info) : Result
        data class UpToDate(val remoteName: String) : Result
        data class Failed(val reason: String) : Result
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(currentVersionCode: Int, manifestUrl: String = DEFAULT_MANIFEST): Result =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(manifestUrl)
                    .header("User-Agent", "NextMovie-AndroidTV")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppLog.record("update", "清单 HTTP ${resp.code} $manifestUrl")
                        return@withContext Result.Failed("检查失败：清单返回 HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val code = json.optInt("versionCode", 0)
                    if (code <= 0) {
                        AppLog.record("update", "清单缺少 versionCode")
                        return@withContext Result.Failed("检查失败：清单缺少 versionCode")
                    }
                    val info = Info(
                        versionCode = code,
                        versionName = json.optString("versionName").ifBlank { "v$code" },
                        notes = json.optString("notes"),
                        apkUrl = json.optString("apk").ifBlank { json.optString("url") },
                        pageUrl = json.optString("page"),
                    )
                    AppLog.record(
                        "update",
                        "清单 versionCode=$code name=${info.versionName} 本地=$currentVersionCode"
                    )
                    if (code > currentVersionCode) Result.Newer(info) else Result.UpToDate(info.versionName)
                }
            } catch (t: Throwable) {
                AppLog.recordThrowable("update", "检查失败", t)
                Result.Failed("检查失败：" + friendly(t))
            }
        }

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "连接超时，稍后再试"
        is java.net.UnknownHostException -> "DNS 解析失败，检查网络或 DNS 设置"
        is javax.net.ssl.SSLException -> "证书校验失败，可能是代理或中间人"
        else -> t.message?.take(60) ?: t.javaClass.simpleName
    }
}
