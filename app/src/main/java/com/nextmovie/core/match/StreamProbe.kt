package com.nextmovie.core.match

import com.nextmovie.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 真实清晰度探测。不猜片名里的字眼，直接问 CDN：
 *  - HLS：取主清单（`#EXT-X-STREAM-INF`），读 `RESOLUTION` 与 `BANDWIDTH`，拿到真实的 1080P / 720P 与码率；
 *  - mp4/其它：读 `Content-Range` 的总大小（拿不到就退回 `Content-Length`），用体积档位给一个弱标签。
 *
 * 全程 Range: bytes=0-4095，绝不下载整片；5 秒超时，失败就当没探到（不阻塞聚合）。
 */
object StreamProbe {

    data class Quality(
        val height: Int = 0,
        val bandwidthKbps: Int = 0,
        val bytes: Long = 0L,
        /** 给人看的短标签：4K / 1080P / 720P / 高码率 / 标准 */
        val label: String = "",
        /** 给日志与调试看的原始信息 */
        val detail: String = ""
    )

    private const val MAX_ENTRIES = 64
    private val cache = object : LinkedHashMap<String, Quality>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Quality>?): Boolean = size > MAX_ENTRIES
    }
    private val lock = Any()

    fun cached(url: String): Quality? = synchronized(lock) { cache[url] }

    private fun remember(url: String, quality: Quality) {
        synchronized(lock) { cache[url] = quality }
    }

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Quality? {
        if (url.isBlank() || !url.startsWith("http", true)) return null
        cached(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = Http.defaultClient().newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(6, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Http.UA)
                    .header("Range", "bytes=0-4095")
                    .apply { headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) } }
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body ?: return@use null
                    val source = body.source()
                    source.request(4096L)
                    val head = source.buffer.clone().readUtf8(minOf(4096L, source.buffer.size))
                    val totalBytes = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: response.header("Content-Length")?.toLongOrNull()
                        ?: 0L
                    if (head.trimStart().startsWith("#EXTM3U")) parseHls(head)
                    else sizeOnly(totalBytes)
                }
            }.getOrNull()?.also { remember(url, it) }
        }
    }

    /** 主清单里挑码率最高的一档；纯媒体清单（没有 STREAM-INF）只能确认是 HLS。 */
    private fun parseHls(text: String): Quality {
        val lines = text.lineSequence().filter { it.startsWith("#EXT-X-STREAM-INF") }.toList()
        if (lines.isEmpty()) return Quality(label = "HLS", detail = "media-playlist")
        var bestHeight = 0
        var bestBandwidth = 0
        lines.forEach { line ->
            val attrs = line.substringAfter(":", "")
            Regex("RESOLUTION=([0-9]+)x([0-9]+)", RegexOption.IGNORE_CASE)
                .find(attrs)?.let { m ->
                    val h = m.groupValues[2].toIntOrNull() ?: 0
                    if (h > bestHeight) bestHeight = h
                }
            Regex("BANDWIDTH=([0-9]+)", RegexOption.IGNORE_CASE).find(attrs)?.let { m ->
                val bps = m.groupValues[1].toLongOrNull() ?: 0L
                val kbps = (bps / 1000L).toInt()
                if (kbps > bestBandwidth) bestBandwidth = kbps
            }
        }
        val label = heightLabel(bestHeight)
        return Quality(
            height = bestHeight,
            bandwidthKbps = bestBandwidth,
            label = label,
            detail = "hls ${bestHeight}p ${bestBandwidth}kbps"
        )
    }

    private fun sizeOnly(bytes: Long): Quality {
        if (bytes <= 0L) return Quality(label = "", detail = "unknown-size")
        val label = when {
            bytes >= 1_200_000_000L -> "高码率"
            bytes >= 400_000_000L -> "标准"
            else -> "小体积"
        }
        return Quality(bytes = bytes, label = label, detail = "%.2fGB".format(bytes / 1_000_000_000.0))
    }

    private fun heightLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height > 0 -> "${height}P"
        else -> ""
    }
}
