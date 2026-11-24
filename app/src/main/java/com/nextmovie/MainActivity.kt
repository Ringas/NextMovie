package com.nextmovie

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import com.nextmovie.ui.common.tvClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Episode
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.detail.DetailScreen
import com.nextmovie.ui.home.HomeScreen
import com.nextmovie.ui.player.PlayerScreen
import com.nextmovie.ui.search.SearchScreen
import com.nextmovie.ui.settings.SettingsScreen
import com.nextmovie.ui.theme.NextMovieTheme
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var repo: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(applicationContext)
        // Android can start the isolated process while Compose and the configuration load.
        repo.startConfiguredPluginHost()
        setContent {
            com.nextmovie.ui.theme.TvDisplay {
                NextMovieTheme { AppRoot(repo) }
            }
        }
    }

    /** Esc / 鼠标右键都按「返回」处理，方便模拟器与外接键鼠调试。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_BUTTON_PRESS &&
            (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        ) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        repo.release()
        super.onDestroy()
    }
}

sealed interface Route {
    data object Home : Route
    data class Detail(val vod: Vod, val siteKey: String? = null) : Route
    data class Player(val req: PlayRequest) : Route
    data object Search : Route
    data object Settings : Route
}

/**
 * 一次播放需要的全部上下文：除了地址与请求头，还要带上「哪部片、哪一集、哪条线路」，
 * 这样播放器回报进度时能直接落成一条历史记录。
 */
data class PlayRequest(
    val url: String,
    val headers: Map<String, String>,
    /** 插件给的 parse 标记：0=直链，非 0=需要网页嗅探 */
    val parse: Int = 0,
    val title: String,
    val siteKey: String,
    val vodId: String,
    val vodName: String,
    val pic: String,
    val remark: String,
    val playFrom: String,
    val episodeName: String,
    val episodeUrl: String,
    val startMs: Long,
    val backTo: Vod,
    /** 同一条线路里「后面还没播的集」，播完自动接上；单集影片为空 */
    val queue: List<Episode> = emptyList()
)

@Composable
private fun AppRoot(repo: AppRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<Route>(Route.Home) }
    // 首页海报行的焦点索引放在这里，跨页面存活，回来时能复原位置
    var homeFocusIndex by remember { mutableStateOf(0) }
    var homeTab by remember { mutableStateOf(0) }
    var booting by remember { mutableStateOf(false) }
    var resolveStage by remember { mutableStateOf("正在读取来源播放地址…") }
    var resolving by remember { mutableStateOf(false) }
    var playJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 冷启动时把上次保存的配置自动载入，否则每次重启都会停在「还没有导入数据源」
    LaunchedEffect(Unit) {
        if (repo.currentSite == null && repo.configUrl().isNotBlank()) {
            val savedUrl = repo.configUrl()
            val configStarted = System.nanoTime()
            val restored = repo.restoreCachedConfig()
            android.util.Log.i("NextMovieTiming", "startup-config restored=$restored ${(System.nanoTime() - configStarted) / 1_000_000}ms")
            booting = !restored
            if (restored) {
                if (repo.configSnapshotIsFresh()) return@LaunchedEffect
                kotlinx.coroutines.delay(3_000)
                if (route != Route.Home || repo.configUrl() != savedUrl) return@LaunchedEffect
            }
            val remoteConfigStarted = System.nanoTime()
            val r = runCatching { repo.loadConfig(savedUrl) }
            android.util.Log.i("NextMovieTiming", "startup-config-network ok=${r.isSuccess} ${(System.nanoTime() - remoteConfigStarted) / 1_000_000}ms")
            booting = false
            if (restored) return@LaunchedEffect
            // 冷启动自动载入同样要给结果反馈：成功报站点数，失败报原因，避免"黑屏几秒像卡死"
            r.onSuccess { n ->
                Toast.makeText(context, "已加载 $n 个站点", Toast.LENGTH_SHORT).show()
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                Toast.makeText(
                    context,
                    "数据源载入失败：" + (it.message ?: it.javaClass.simpleName).take(60),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 启动后后台给 TMDB 线路测速，把本地最快的那条提到最前（无 Token 时直接跳过）
    LaunchedEffect(Unit) {
        runCatching { repo.tuneTmdbSilently() }
    }

    BackHandler(enabled = resolving || route != Route.Home) {
        if (resolving) { playJob?.cancel(); resolving = false; return@BackHandler }
        route = when (val current = route) {
            is Route.Player -> Route.Detail(current.req.backTo, current.req.siteKey)
            else -> Route.Home
        }
    }

    var accountGeneration by remember { mutableStateOf(0) }
    val accountLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        scope.launch { repo.invalidateContent(); accountGeneration++ }
    }

    val site = repo.currentSite

    fun recordProgress(req: PlayRequest, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L) return
        val entry = HistoryEntry(
            siteKey = req.siteKey,
            vodId = req.vodId,
            name = req.vodName,
            pic = req.pic,
            remark = req.remark,
            playFrom = req.playFrom,
            episodeName = req.episodeName,
            episodeUrl = req.episodeUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis()
        )
        scope.launch { withContext(Dispatchers.IO) { repo.history.upsert(entry) } }
    }

    /** 从详情页点某一集：走一次播放地址解析，然后进入播放器。 */
    fun playEpisode(
        vod: Vod,
        line: com.nextmovie.core.model.PlayLine,
        episode: Episode,
        startMs: Long = 0L,
        source: Site? = null,
        queue: List<Episode> = emptyList()
    ) {
        val activeSite = source ?: site
        if (activeSite == null) {
            Toast.makeText(context, "还没有选择数据源", Toast.LENGTH_SHORT).show()
            return
        }
        playJob?.cancel()
        playJob = scope.launch {
            resolveStage = "正在读取来源播放地址…"
            resolving = true
            try {
                val result = try {
                    repo.preparePlayback(activeSite)
                    if (activeSite.isCms) repo.resolvePlay(activeSite, line.name, episode.url) { resolveStage = it }
                    else PluginPlaybackWindow.resolve(context) { repo.resolvePlay(activeSite, line.name, episode.url) { resolveStage = it } }
                }
                catch (e: kotlinx.coroutines.CancellationException) { repo.cancelPlayback(activeSite); throw e }
                catch (e: Exception) {
                    Toast.makeText(context, if (e is IllegalStateException && e.message == "网页解析未取得媒体地址，请更换线路") e.message else "来源解析失败，请重试或换一条线路", Toast.LENGTH_LONG).show()
                    return@launch
                }
            when {
                result == null ->
                    Toast.makeText(context, "解析失败，换个线路试试", Toast.LENGTH_SHORT).show()

                result.parse != 0 ->
                    Toast.makeText(context, "该线路未能取得直链，请换一条线路", Toast.LENGTH_LONG).show()

                else -> route = Route.Player(
                    PlayRequest(
                        url = result.url,
                        headers = result.headers,
                        parse = result.parse,
                        title = vod.name + " · " + episode.name,
                        siteKey = activeSite.key,
                        vodId = vod.id,
                        vodName = vod.name,
                        pic = vod.displayPic,
                        remark = vod.remark,
                        playFrom = line.name,
                        episodeName = episode.name,
                        episodeUrl = episode.url,
                        startMs = startMs,
                        backTo = vod,
                        queue = queue
                    )
                )
            }
            } finally { resolving = false }
        }
    }

    /** 首页「继续观看」：用记录里的地址直接续播，不再走搜索。 */
    fun resume(entry: HistoryEntry) {
        val activeSite = repo.siteByKey(entry.siteKey)
        if (activeSite == null) {
            Toast.makeText(context, "还没有选择数据源", Toast.LENGTH_SHORT).show()
            return
        }
        val vod = Vod(id = entry.vodId, name = entry.name, pic = entry.pic, remark = entry.remark)
        playEpisode(
            vod = vod,
            line = com.nextmovie.core.model.PlayLine(
                entry.playFrom,
                listOf(Episode(entry.episodeName.ifBlank { "继续观看" }, entry.episodeUrl))
            ),
            episode = Episode(entry.episodeName.ifBlank { "继续观看" }, entry.episodeUrl),
            startMs = entry.positionMs,
            source = activeSite
        )
    }

    when (val current = route) {
        Route.Home -> {
            if (booting) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.Bg), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "NEXT MOVIE",
                            color = Tokens.Text,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                letterSpacing = 4.sp
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "正在载入数据源…",
                            color = Tokens.Accent,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "首次加载插件源需要下载并初始化插件，通常 10–40 秒",
                            color = Tokens.TextFaint,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else if (site == null) {
                SetupHint(onOpenSettings = { route = Route.Settings })
            } else {
                HomeScreen(
                    repo = repo,
                    site = site,
                    onOpenDetail = { vod ->
                        if (vod.action.isNotBlank() || vod.tag == "folder") accountLauncher.launch(PluginActivity.intent(context, site, vod.id, vod.action.takeIf { it.isNotBlank() }))
                        else route = Route.Detail(vod)
                    },
                    onOpenSettings = { route = Route.Settings },
                    onSearch = { route = Route.Search },
                    onResume = { resume(it) },
                    initialFocusIndex = homeFocusIndex,
                    initialTab = homeTab,
                    reloadSignal = accountGeneration,
                    onTabChanged = { homeTab = it; homeFocusIndex = 0 },
                    onFocusIndexChanged = { homeFocusIndex = it }
                )
            }
        }

        is Route.Detail -> DetailScreen(
            repo = repo,
            site = repo.siteByKey(current.siteKey.orEmpty()) ?: site ?: return,
            initial = current.vod,
            onPlay = { source, resolvedVod, line, episode, startMs ->
                val idx = line.episodes.indexOf(episode)
                val rest = if (idx >= 0) line.episodes.drop(idx + 1) else emptyList()
                playEpisode(resolvedVod, line, episode, startMs, source, rest)
            },
            onBack = { route = Route.Home }
        )

        is Route.Player -> PlayerScreen(
            title = current.req.title,
            url = current.req.url,
            headers = current.req.headers,
            parse = current.req.parse,
            startPositionMs = current.req.startMs,
            onProgress = { position, duration -> recordProgress(current.req, position, duration) },
            nextEpisodeName = current.req.queue.firstOrNull()?.name,
            onPlayNext = {
                val next = current.req.queue.firstOrNull() ?: return@PlayerScreen
                playEpisode(
                    vod = current.req.backTo,
                    line = com.nextmovie.core.model.PlayLine(current.req.playFrom, listOf(next)),
                    episode = next,
                    startMs = 0L,
                    source = repo.siteByKey(current.req.siteKey),
                    queue = current.req.queue.drop(1)
                )
            },
            onBack = { route = Route.Detail(current.req.backTo, current.req.siteKey) }
        )

        Route.Search -> SearchScreen(
            repo = repo,
            site = site ?: return,
            onOpenDetail = { route = Route.Detail(it) },
            onBack = { route = Route.Home }
        )

        Route.Settings -> SettingsScreen(
            repo = repo,
            onBack = { route = Route.Home },
            onConfigLoaded = { route = Route.Home }
        )
    }
    val proxyUrl = (route as? Route.Player)?.req?.url
    if (proxyUrl != null) {
        androidx.compose.runtime.DisposableEffect(proxyUrl) {
            onDispose { repo.releasePlaybackProxy(proxyUrl) }
        }
    }
    if (resolving) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { playJob?.cancel(); resolving = false }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.panel))
                    .background(Tokens.Surface)
                    .padding(28.dp)
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = Tokens.Accent)
                Spacer(Modifier.height(16.dp))
                Text(resolveStage, color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("按返回取消", color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); first.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "NEXT MOVIE",
                color = Tokens.Text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = 4.sp
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "HOST · 零内置源",
                color = Tokens.Accent,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "还没有导入数据源",
                color = Tokens.TextDim,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(Tokens.Accent)
                    .focusRequester(first)
                    .tvClickable(onClick = onOpenSettings, corner = Tokens.Corner.pill)
                    .padding(horizontal = 32.dp, vertical = 14.dp)
            ) {
                Text("导入配置", color = Tokens.OnAccent, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "本应用不提供影视资源，请使用你有权访问的配置。",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
