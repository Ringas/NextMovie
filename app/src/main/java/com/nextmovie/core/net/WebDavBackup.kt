package com.nextmovie.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class WebDavBackup(client: OkHttpClient) {
    private val directClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun sync(
        address: String,
        username: String,
        password: String,
        merge: (String) -> Unit,
        export: () -> String
    ) = withContext(Dispatchers.IO) {
        val url = address.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && url.pathSegments.lastOrNull()?.endsWith(".json", true) == true) {
            "请输入 HTTPS 下的 .json 文件完整地址"
        }
        fun request() = Request.Builder().url(url).apply {
            if (username.isNotBlank()) header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        }
        repeat(3) {
            val revision = directClient.newCall(request().get().build()).execute().use { response ->
                when (response.code) {
                    404 -> null
                    200 -> {
                        val etag = response.header("ETag")
                        check(etag != null && etag.startsWith("\"") && etag.endsWith("\"")) {
                            "服务器缺少强 ETag，无法安全合并"
                        }
                        val source = checkNotNull(response.body).source()
                        check(!source.request(MAX_BYTES + 1)) { "云端备份超过 4 MiB" }
                        merge(source.readUtf8())
                        etag
                    }
                    else -> error("读取云端备份失败（HTTP ${response.code}）")
                }
            }
            val bytes = export().toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES) { "备份超过 4 MiB" }
            val put = request().apply {
                if (revision == null) header("If-None-Match", "*") else header("If-Match", revision)
            }.put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            directClient.newCall(put).execute().use { response ->
                if (response.isSuccessful) return@withContext
                if (response.code != 412) error("写入云端备份失败（HTTP ${response.code}）")
            }
        }
        error("其他设备正在更新备份，请稍后重试")
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024L * 1024L
    }
}
