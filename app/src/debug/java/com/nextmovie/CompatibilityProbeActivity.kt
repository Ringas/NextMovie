package com.nextmovie

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.nextmovie.core.AppRepository
import com.nextmovie.core.net.PlayerDataSources
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Debug-only protocol/playback probe. No configuration or account is embedded in the APK. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CompatibilityProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repo: AppRepository
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val text = TextView(this).apply { text = "Compatibility probe running…" }
        val surface = android.view.SurfaceView(this)
        setContentView(android.widget.FrameLayout(this).apply {
            addView(surface, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(text, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        })
        repo = AppRepository(applicationContext)
        scope.launch {
            val result = JSONObject().put("probeVersion", 2).put("startedAt", System.currentTimeMillis())
            File(filesDir, "compat-probe.json").writeText(result.put("running", true).toString())
            try {
                intent.getStringExtra("inspectHttp")?.let { address ->
                    withContext(Dispatchers.IO) {
                        val request = okhttp3.Request.Builder().url(address)
                            .header("User-Agent", com.nextmovie.core.net.Http.BROWSER_UA).build()
                        repo.http.client.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            result.put("httpCode", response.code)
                                .put("responseHost", response.request.url.host)
                                .put("bodyLength", body.length)
                                .put("challengePage", listOf("Just a moment", "Attention Required", "cf-chl-").any { body.contains(it, true) })
                        }
                    }
                    return@launch
                }
                val config = intent.getStringExtra("config") ?: error("Missing config")
                withTimeout(90_000) { repo.loadConfig(config) }
                result.put("config", "ok").put("sites", repo.sites.size)
                val key = intent.getStringExtra("site")
                val site = repo.sites.firstOrNull { it.key == key }
                    ?: repo.sites.firstOrNull { it.isCms } ?: repo.currentSite ?: error("No site")
                repo.selectSite(site.key)
                if (intent.getBooleanExtra("preview", false)) {
                    startActivity(android.content.Intent(this@CompatibilityProbeActivity, MainActivity::class.java))
                    finish()
                    return@launch
                }
                if (intent.getBooleanExtra("account", false)) {
                    startActivity(PluginActivity.intent(this@CompatibilityProbeActivity, site))
                    finish(); return@launch
                }
                result.put("site", site.key).put("api", site.api)
                val homeStart = System.nanoTime()
                val home = withTimeout(100_000) { repo.home(site) }
                result.put("homeMs", (System.nanoTime() - homeStart) / 1_000_000)
                val warmStart = System.nanoTime()
                repo.home(site)
                result.put("warmHomeMs", (System.nanoTime() - warmStart) / 1_000_000)
                result.put("home", home.size)
                if (home.isEmpty()) error("Empty home")
                val detailStart = System.nanoTime()
                intent.getStringExtra("verifySearch")?.let { keyword ->
                    val categories = repo.categories(site)
                    check(categories.isNotEmpty()) { "No categories" }
                    val category = repo.category(site, categories.first().first, 1)
                    val found = repo.search(site, keyword)
                    check(category.isNotEmpty() && found.isNotEmpty()) { "Empty category or search" }
                    result.put("categories", categories.size).put("categoryItems", category.size)
                        .put("searchItems", found.size)
                }
                val selected = home.getOrElse(intent.getIntExtra("sampleIndex", 0)) { home.first() }
                result.put("title", selected.name)
                val strict = intent.getBooleanExtra("strict", true)
                val hit = withTimeout(100_000) {
                    if (strict) repo.detail(site, selected.id, selected.name)?.let { AppRepository.DetailHit(site, it) }
                    else repo.detailPreferring(site, selected) {
                        result.put("firstPlayableMs", (System.nanoTime() - detailStart) / 1_000_000)
                    }
                } ?: error("Empty detail")
                result.put("detailMs", (System.nanoTime() - detailStart) / 1_000_000)
                val detail = hit.detail
                result.put("detailSite", hit.site.key)
                result.put("detail", "ok").put("lines", detail.lines.size)
                val line = detail.lines.firstOrNull() ?: error("No episodes")
                val episode = line.episodes.firstOrNull() ?: error("No episode")
                val resolveStart = System.nanoTime()
                val play = withTimeout(100_000) { repo.resolvePlay(hit.site, line.name, episode.url) }
                    ?: error("No play result")
                result.put("resolveMs", (System.nanoTime() - resolveStart) / 1_000_000)
                result.put("resolved", true).put("parse", play.parse)
                if (play.parse != 0) error("Unresolved parse")
                val player = ExoPlayer.Builder(this@CompatibilityProbeActivity).build()
                try {
                    var failure: PlaybackException? = null
                    var firstFrame = false
                    val prepareStarted = System.nanoTime()
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { failure = error }
                        override fun onRenderedFirstFrame() {
                            if (!firstFrame) result.put("firstFrameMs", (System.nanoTime() - prepareStarted) / 1_000_000)
                            firstFrame = true
                        }
                    })
                    player.setVideoSurfaceView(surface)
                    player.setMediaSource(DefaultMediaSourceFactory(PlayerDataSources.factory(play.headers))
                        .createMediaSource(MediaItem.fromUri(play.url)))
                    player.prepare()
                    player.playWhenReady = true
                    suspend fun waitFor(timeout: Long, condition: () -> Boolean) {
                        withTimeout(timeout) {
                            while (!condition()) {
                                failure?.let { error(it.errorCodeName) }
                                delay(200)
                            }
                        }
                    }
                    waitFor(35_000) { firstFrame && player.currentPosition >= 3000 && player.isPlaying }
                    result.put("playback", "playing").put("firstFrame", true)
                    if (player.isCurrentMediaItemSeekable && player.duration > 6500) {
                        val target = minOf(10_000L, player.duration / 2)
                        player.seekTo(target)
                        waitFor(25_000) { player.currentPosition >= target + 700 && player.isPlaying }
                        result.put("seek", true)
                    } else result.put("seek", "not-seekable-or-too-short")
                } finally { player.release() }
            } catch (e: Exception) {
                result.put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                result.put("running", false)
                File(filesDir, "compat-probe.json").writeText(result.toString(2))
                android.util.Log.i("NextMovieProbe", result.toString())
                text.text = result.toString(2)
            }
        }
    }
    override fun onDestroy() { scope.cancel(); repo.release(); super.onDestroy() }
}
