package com.nextmovie.core.spider

import android.content.Context
import com.nextmovie.core.config.PluginArtifact
import com.nextmovie.core.net.Http
import java.io.File
import java.security.MessageDigest

/**
 * 配置根部的 `spider` 是一个伪装成图片（`xxx.jpg;md5;<hash>`）的 jar。
 *
 * 95 个站点共用这一个 jar，但首次进入首页时才开始下载 + 校验 + DexClassLoader，
 * 盒子上这一段就是 20–30 秒的空转。这里在**配置导入成功后**立刻后台把它拉到
 * `files/spiders/`，等用户真正点开某个源时只剩 dex/Init。
 */
object JarPrefetch {

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 已存在同名且 md5 匹配就跳过；返回是否可用。 */
    suspend fun prefetch(context: Context, http: Http, raw: String): Boolean {
        val spec = PluginArtifact.split(raw)
        val url = spec.first
        val hash = spec.second
        if (url.isBlank() || !url.startsWith("http")) return false
        val dir = File(context.filesDir, "spiders").apply { mkdirs() }
        val name = md5((url + "|" + hash).toByteArray()) + ".jar"
        val target = File(dir, name)
        val stamp = File(dir, "$name.md5")
        if (target.length() > 1024 && (hash.isBlank() || runCatching { stamp.readText().trim() }.getOrDefault("").equals(hash, true))) {
            return true
        }
        val bytes = runCatching { http.bytes(url) }.getOrNull() ?: return false
        if (bytes.size < 1024) return false
        if (hash.isNotBlank() && !md5(bytes).equals(hash, true)) return false
        if (!(bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) return false
        runCatching {
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) { temp.copyTo(target, true); temp.delete() }
            stamp.writeText(hash)
        }
        android.util.Log.i("NextMovieTiming", "jar-prefetch ${bytes.size / 1024}KB ok=${target.length() > 0} name=$name")
        return target.length() > 0
    }
}
