package com.nextmovie.core.spider

import android.content.Context
import com.nextmovie.core.config.PluginArtifact
import com.nextmovie.core.net.Http
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * 配置根部的 `spider` 是一个伪装成图片（`xxx.jpg;md5;<hash>`）的 jar。
 *
 * 95 个站点共用这一个 jar，但首次进入首页时才开始下载 + 校验 + DexClassLoader，
 * 盒子上这一段就是 20–30 秒的空转。这里在**配置导入成功后**立刻后台把它拉到
 * `files/spiders/`，等用户真正点开某个源时只剩 dex/Init。
 *
 * 状态通过 [state] 暴露给设置页：下载中 / 成功 / 失败可重试，避免用户只看到黑屏干等。
 */
object JarPrefetch {

    sealed class State {
        data object Idle : State()
        data class Progress(
            val url: String,
            val attempt: Int,
            val message: String
        ) : State()
        data class Ready(
            val url: String,
            val bytes: Long,
            val fromCache: Boolean
        ) : State()
        data class Failed(
            val url: String,
            val reason: String,
            val canRetry: Boolean = true
        ) : State()
    }

    private val stateRef = AtomicReference<State>(State.Idle)
    private val lastSpec = AtomicReference<String>("")

    fun state(): State = stateRef.get()

    fun lastRawSpec(): String = lastSpec.get()

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun setState(next: State) {
        stateRef.set(next)
    }

    /**
     * 已存在同名且 md5 匹配就跳过；返回是否可用。
     * 失败时 [state] 为 [State.Failed]，调用方可再 [prefetch] 一次重试。
     */
    suspend fun prefetch(context: Context, http: Http, raw: String): Boolean {
        val spec = PluginArtifact.split(raw)
        val url = spec.first
        val hash = spec.second
        lastSpec.set(raw)
        if (url.isBlank() || !url.startsWith("http")) {
            setState(State.Failed(url.ifBlank { raw }, "插件地址无效", canRetry = false))
            return false
        }

        val dir = File(context.filesDir, "spiders").apply { mkdirs() }
        val name = md5((url + "|" + hash).toByteArray()) + ".jar"
        val target = File(dir, name)
        val stamp = File(dir, "$name.md5")

        if (target.length() > 1024 &&
            (hash.isBlank() || runCatching { stamp.readText().trim() }.getOrDefault("").equals(hash, true))
        ) {
            setState(State.Ready(url, target.length(), fromCache = true))
            return true
        }

        setState(State.Progress(url, attempt = 1, message = "正在下载插件…"))
        val bytes = runCatching {
            try {
                http.bytes(url)
            } catch (first: Throwable) {
                if (!isTransientDownloadError(first)) throw first
                setState(State.Progress(url, attempt = 2, message = "下载失败，正在重试…"))
                kotlinx.coroutines.delay(1_500)
                http.bytes(url)
            }
        }.getOrElse { err ->
            val reason = err.message?.take(120) ?: err.javaClass.simpleName
            setState(State.Failed(url, "下载失败：$reason", canRetry = true))
            android.util.Log.w("NextMovieTiming", "jar-prefetch-fail url=$url reason=$reason")
            return false
        }

        setState(State.Progress(url, attempt = 1, message = "正在校验插件…"))

        val material = runCatching {
            com.nextmovie.core.config.JarBytes.materialize(bytes, hash)
        }.getOrElse { err ->
            val reason = err.message?.take(160) ?: err.javaClass.simpleName
            setState(State.Failed(url, reason, canRetry = true))
            android.util.Log.w("NextMovieTiming", "jar-prefetch-invalid url=$url reason=$reason")
            return false
        }

        val saved = runCatching {
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(material.jar)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, true)
                temp.delete()
            }
            stamp.writeText(hash)
            target.length() > 0
        }.getOrDefault(false)

        if (!saved) {
            setState(State.Failed(url, "写入本地缓存失败", canRetry = true))
            return false
        }

        android.util.Log.i("NextMovieTiming", "jar-prefetch ${bytes.size / 1024}KB ok name=$name")
        setState(State.Ready(url, bytes.size.toLong(), fromCache = false))
        return true
    }

    /** 设置页「重试下载」：用上次的 raw spec 再拉一次。 */
    suspend fun retryLast(context: Context, http: Http): Boolean {
        val raw = lastSpec.get()
        if (raw.isBlank()) {
            setState(State.Failed("", "没有可重试的插件地址", canRetry = false))
            return false
        }
        return prefetch(context, http, raw)
    }

    private fun isTransientDownloadError(e: Throwable): Boolean = when (e) {
        is Http.HttpError -> e.code >= 500 || e.code == 408 || e.code == 429
        is java.net.SocketTimeoutException,
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.io.IOException -> true
        else -> false
    }
}
