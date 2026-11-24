package com.nextmovie

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nextmovie.core.net.PlayerDataSources
import com.nextmovie.core.spider.SpiderProxyServer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Local transport regression only. The fixture is supplied privately by adb, never bundled. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProxyPlaybackProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val surface = SurfaceView(this)
        setContentView(surface)
        scope.launch {
            val result = JSONObject().put("running", true)
            val report = File(filesDir, "proxy-probe.json")
            report.writeText(result.toString())
            try {
                val media = File(filesDir, "proxy-sample.mp4")
                check(media.length() > 1024) { "Supply files/proxy-sample.mp4 first" }
                val requests = AtomicInteger()
                val ranges = AtomicInteger()
                SpiderProxyServer().use { backend ->
                    SpiderProxyServer().use { host ->
                        host.activate { error("Video was incorrectly routed into the host callback") }
                        backend.activate { params ->
                            requests.incrementAndGet()
                            val range = params["range"].orEmpty()
                            if (range.isNotBlank()) ranges.incrementAndGet()
                            val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                            val end = (range.substringAfter('-', "").toLongOrNull() ?: (media.length() - 1)).coerceAtMost(media.length() - 1)
                            val file = RandomAccessFile(media, "r").apply { seek(start) }
                            var remaining = end - start + 1
                            val stream = object : InputStream() {
                                override fun read(): Int = if (remaining <= 0) -1 else file.read().also { if (it >= 0) remaining-- }
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    if (remaining <= 0) return -1
                                    return file.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                                }
                                override fun close() { file.close() }
                            }
                            val headers = linkedMapOf("Content-Length" to remaining.toString(), "Accept-Ranges" to "bytes")
                            if (range.isNotBlank()) headers["Content-Range"] = "bytes $start-$end/${media.length()}"
                            arrayOf(if (range.isBlank()) 200 else 206, "video/mp4", stream, headers)
                        }
                        // The MP4 fixture intentionally uses the exact REST-shaped .mkv route from the bug.
                        // This checks HTTP routing, not a claim that the fixture is Matroska or a logged-in cloud stream.
                        val original = URI("http", null, "127.0.0.1", backend.port, "/proxy/play/夸父盘/两京十五日/01.mkv", null, null).toASCIIString()
                        val resolved = host.rewrite(original)
                        check(original == resolved) { "Plugin server address changed" }
                        result.put("pluginAddressPreserved", true)
                        withContext(Dispatchers.IO) {
                            val connection = URL(resolved).openConnection() as HttpURLConnection
                            connection.connectTimeout = 5000; connection.readTimeout = 5000
                            connection.setRequestProperty("Range", "bytes=1-3")
                            check(connection.responseCode == 206)
                            check(connection.getHeaderField("Content-Range") == "bytes 1-3/${media.length()}")
                            check(connection.inputStream.use { it.readBytes().size } == 3)
                            connection.disconnect()
                        }
                        result.put("range206", true)
                        val player = ExoPlayer.Builder(this@ProxyPlaybackProbeActivity).build()
                        try {
                            var failure: PlaybackException? = null
                            var firstFrame = false
                            player.addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) { failure = error }
                                override fun onRenderedFirstFrame() { firstFrame = true }
                            })
                            player.setVideoSurfaceView(surface)
                            player.setMediaSource(DefaultMediaSourceFactory(PlayerDataSources.factory(emptyMap())).createMediaSource(MediaItem.fromUri(resolved)))
                            player.prepare(); player.playWhenReady = true
                            withTimeout(25_000) {
                                while (!firstFrame || player.currentPosition < 2500 || !player.isPlaying) {
                                    failure?.let { throw it }; delay(100)
                                }
                            }
                            result.put("firstFrame", true).put("playing", true)
                            val target = minOf(8000L, (player.duration - 2000).coerceAtLeast(1000))
                            player.seekTo(target)
                            withTimeout(15_000) {
                                while (player.currentPosition < target + 500 || !player.isPlaying) {
                                    failure?.let { throw it }; delay(100)
                                }
                            }
                            result.put("seek", true).put("requests", requests.get()).put("rangeRequests", ranges.get())
                        } finally { player.release() }
                    }
                }
            } catch (e: Exception) {
                result.put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                result.put("running", false)
                report.writeText(result.toString(2))
                android.util.Log.i("NextMovieProxyProbe", result.toString())
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
