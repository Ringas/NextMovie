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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

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
        // 键盘 Esc / 部分键盘 Back 统一当返回（与遥控返回键一致）
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_DEL)
        ) {
            // DEL 仅在无输入框抢焦点时当返回；有输入时交给系统
            if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                val focused = currentFocus
                if (focused is android.widget.EditText || focused?.onCheckIsTextEditor() == true) {
                    return super.dispatchKeyEvent(event)
                }
            }
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
    data object Favorites : Route
    data object History : Route
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
    val queue: List<Episode> = emptyList(),
    /** 聚合出来的可切换线路清单（同一集在不同源的对应地址） */
    val slots: List<com.nextmovie.core.model.PlaySlot> = emptyList(),
    /** 当前正在播的槽位下标 */
    val slotIndex: Int = 0,
    val subtitles: List<com.nextmovie.core.model.ExternalSubtitle> = emptyList(),
    val requestedAtNanos: Long = 0L,
    val attemptedSlots: Set<Int> = emptySet()
)

@Composable
private fun AppRoot(repo: AppRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<Route>(Route.Home) }
    // 首页海报行的焦点索引放在这里，跨页面存活，回来时能复原位置
    var homeFocusIndex by remember { mutableStateOf(0) }
    var homeTab by remember { mutableStateOf(0) }
    var shellTab by remember { mutableStateOf(0) }
    val searchState = remember { com.nextmovie.ui.search.TvSearchState() }
    val shellState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var booting by remember { mutableStateOf(false) }
    var resolveStage by remember { mutableStateOf("正在读取来源播放地址…") }
    var resolving by remember { mutableStateOf(false) }
    var playJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 播放器回报的当前位置：换线路时从这里接着播，不丢进度
    var lastPositionMs by remember { mutableStateOf(0L) }
    // 网盘线路未登录：直接在播放界面弹二维码，扫完自动重试播放
    var panQrId by remember { mutableStateOf("") }
    var panQrLabel by remember { mutableStateOf("") }
    var panQrMsg by remember { mutableStateOf("") }
    var panQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var panQrRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 跨页面播放会话：Marco 的「退回详情页影片继续播」靠它把 ExoPlayer 留在进程里
    val playbackSession = remember { com.nextmovie.ui.player.PlaybackSession(context) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { playbackSession.release() } }
    // 离开播放/详情（回首页、搜索、设置）就把声音收掉，避免电视上「回桌面还在放」
    androidx.compose.runtime.LaunchedEffect(route) {
        val r = route
        if (r is Route.Player) playbackSession.alive = true
        if (r !is Route.Player && r !is Route.Detail) {
            playbackSession.alive = false
            runCatching { playbackSession.player.pause() }
        }
    }

    // 冷启动时把上次保存的配置自动载入，否则每次重启都会停在「还没有导入数据源」
    LaunchedEffect(Unit) {
        if (repo.currentSite == null && repo.configUrl().isNotBlank()) {
            val savedUrl = repo.configUrl()
            val configStarted = System.nanoTime()
            val restored = repo.restoreCachedConfig()
            android.util.Log.i("NextMovieTiming", "startup-config restored=$restored ${(System.nanoTime() - configStarted) / 1_000_000}ms")
            booting = !restored
            // 首页有快照可秒开，插件源的下载/初始化放到后台先跑，别等用户点海报时才开始
            repo.prefetchSpiderJar(if (restored) "restored" else "fresh")
            repo.prewarmCurrentSite(if (restored) "restored" else "fresh")
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

    /** 把异常/线路名里的网盘线索认出来：夸克 / UC / 阿里 / 百度。 */
    fun panOf(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("quark") || text.contains("夸克") -> "quark"
            t.contains("uc") || text.contains("UC") -> "uc"
            t.contains("ali") || text.contains("阿里") -> "ali"
            t.contains("baidu") || text.contains("百度") || text.contains("嘟嘟") -> "baidu"
            else -> null
        }
    }

    /** 未登录网盘时一律走账户页的官方网页登录：二维码由官方页面渲染，Cookie 回来再回灌插件。 */
    fun askPanLogin(activeSite: Site, panId: String, retry: (() -> Unit)?) {
        val label = when (panId) { "quark" -> "夸克"; "uc" -> "UC"; "ali" -> "阿里"; else -> "百度·嘟嘟" }
        android.util.Log.i("NextMoviePan", "auto-pop web-login pan=$panId site=${activeSite.key}")
        Toast.makeText(context, "「$label」未登录，已打开账户页，登录后回来点播放", Toast.LENGTH_LONG).show()
        accountLauncher.launch(PluginActivity.intent(context, activeSite, "", null))
        panQrRetry = retry
    }

    /** 把插件异常翻成人话，别再让用户对着「解析失败」猜。 */
    fun explain(e: Throwable, lineName: String): String {
        val raw = (e.message.orEmpty() + " " + (e.cause?.message.orEmpty())).lowercase()
        return when {
            raw.contains("econnrefused") || raw.contains("failed to connect") ->
                "「$lineName」需要来源的本地网盘代理，但它没起来（连接被拒）。换一条非云盘的线路，或稍后重试"
            e is java.util.concurrent.TimeoutException || raw.contains("timeout") ->
                "「$lineName」15 秒内没拿到地址，已放弃。换一条线路"
            raw.contains("cookie") || raw.contains("登录") || raw.contains("auth") ->
                "「$lineName」是网盘线路，来源还没登录（${e.message.orEmpty().take(60)}）。已弹出扫码登录，扫完自动接着播"
            raw.contains("doctype") || raw.contains("jsonobject") ->
                "来源返回了网页而不是数据（被防爬拦截），换一条线路"
            e is IllegalStateException && e.message == "网页解析未取得媒体地址，请更换线路" -> e.message.orEmpty()
            else -> "来源解析失败（${e.javaClass.simpleName}），换一条线路"
        }
    }

    /** 从详情页点某一集：走一次播放地址解析，然后进入播放器。 */
    fun playEpisode(
        vod: Vod,
        line: com.nextmovie.core.model.PlayLine,
        episode: Episode,
        startMs: Long = 0L,
        source: Site? = null,
        queue: List<Episode> = emptyList(),
        slots: List<com.nextmovie.core.model.PlaySlot> = emptyList(),
        slotIndex: Int = -1,
        attemptedSlots: Set<Int> = emptySet(),
        onResolveFailure: (() -> Unit)? = null
    ) {
        val activeSite = source ?: site
        if (activeSite == null) {
            Toast.makeText(context, "还没有选择数据源", Toast.LENGTH_SHORT).show()
            return
        }
        playJob?.cancel()
        val resolveStarted = System.nanoTime()
        playJob = scope.launch {
            resolveStage = "正在读取来源播放地址…"
            resolving = true
            var retry = false
            try {
                val result = try {
                    repo.preparePlayback(activeSite)
                    // 单条线路最多给 15s：插件连不上自己的网盘代理（ECONNREFUSED）时不要拖到用户以为死机
                    withTimeout(15_000) {
                        if (activeSite.isCms || activeSite.isJavaScript) repo.resolvePlay(activeSite, line.name, episode.url) { resolveStage = it }
                        else PluginPlaybackWindow.resolve(context) { repo.resolvePlay(activeSite, line.name, episode.url) { resolveStage = it } }
                    }
                }
                catch (e: TimeoutCancellationException) {
                    // 超时也走同一条失败路径：换线 / 给人话提示
                    repo.recordPlayOutcome(activeSite.key, false)
                    android.util.Log.i("NextMovieTiming", "line-play-timeout site=${activeSite.key} line=${line.name}")
                    if (onResolveFailure != null) { retry = true; return@launch }
                    Toast.makeText(context, "「${line.name}」15 秒没拿到地址，已换下一条线路", Toast.LENGTH_LONG).show()
                    return@launch
                }
                catch (e: kotlinx.coroutines.CancellationException) { repo.cancelPlayback(activeSite); throw e }
                catch (e: Exception) {
                    repo.recordPlayOutcome(activeSite.key, false)
                    val reason = "line-play-fail site=${activeSite.key} line=${line.name} " +
                        "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
                    android.util.Log.i("NextMovieTiming", reason)
                    val needsLogin = listOf("cookie", "登录", "auth", "网盘").any {
                        (e.message.orEmpty()).contains(it, ignoreCase = true)
                    }
                    if (needsLogin) {
                        // 网盘线路没登录：不把人踢去设置页，直接在播放界面弹扫码，扫完自动重试
                        val panId = panOf(e.message.orEmpty() + " " + line.name)
                        Toast.makeText(context, explain(e, line.name), Toast.LENGTH_LONG).show()
                        if (panId != null) askPanLogin(activeSite, panId, {
                            playEpisode(vod, line, episode, startMs, source, queue, slots, slotIndex, attemptedSlots, onResolveFailure)
                        })
                        else accountLauncher.launch(PluginActivity.intent(context, activeSite, "", null))
                        if (onResolveFailure != null) retry = true
                        return@launch
                    }
                    if (onResolveFailure != null) {
                        retry = true
                        return@launch
                    }
                    Toast.makeText(context, explain(e, line.name), Toast.LENGTH_LONG).show()
                    return@launch
                }
            android.util.Log.i("NextMovieTiming", "play-resolve ${(System.nanoTime() - resolveStarted) / 1_000_000}ms")
            when {
                result == null -> {
                    repo.recordPlayOutcome(activeSite.key, false)
                    if (onResolveFailure != null) retry = true
                    else Toast.makeText(context, "解析失败，换个线路试试", Toast.LENGTH_SHORT).show()
                }

                result.parse != 0 -> {
                    repo.recordPlayOutcome(activeSite.key, false)
                    if (onResolveFailure != null) retry = true
                    else Toast.makeText(context, "该线路未能取得直链，请换一条线路", Toast.LENGTH_LONG).show()
                }

                else -> {
                    val effectiveSlot = if (slotIndex >= 0) slotIndex else slots.indexOfFirst {
                        it.siteKey == activeSite.key && it.lineName == line.name && it.episodeUrl == episode.url
                    }
                    lastPositionMs = startMs
                    route = Route.Player(
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
                        queue = queue,
                        slots = slots,
                        slotIndex = effectiveSlot,
                        attemptedSlots = if (effectiveSlot >= 0) attemptedSlots + effectiveSlot else attemptedSlots,
                        subtitles = result.subtitles,
                        requestedAtNanos = resolveStarted
                    )
                )
                }
            }
            } finally {
                resolving = false
                if (retry) onResolveFailure?.invoke()
            }
        }
    }

    /** 播放器里换线路：换源重新解析同一集，并从当前时间点接着播。 */
    fun switchSlot(req: PlayRequest, index: Int, automatic: Boolean = false) {
        val candidates = if (automatic) (listOf(index) + req.slots.indices)
            .distinct().filter { it in req.slots.indices && it !in req.attemptedSlots }
        else listOf(index)
        val chosen = candidates.firstOrNull { repo.siteByKey(req.slots.getOrNull(it)?.siteKey.orEmpty()) != null }
        if (chosen == null) {
            Toast.makeText(context, "没有其他可用线路", Toast.LENGTH_SHORT).show()
            return
        }
        val slot = req.slots[chosen]
        val target = repo.siteByKey(slot.siteKey) ?: return
        val attempted = if (automatic) req.attemptedSlots + chosen else emptySet()
        playEpisode(
            vod = slot.vod ?: req.backTo,
            line = com.nextmovie.core.model.PlayLine(slot.lineName, listOf(Episode(slot.episodeName, slot.episodeUrl))),
            episode = Episode(slot.episodeName, slot.episodeUrl),
            startMs = playbackSession.player.currentPosition.takeIf { it > 0L } ?: lastPositionMs,
            source = target,
            queue = slot.remaining,
            slots = req.slots,
            slotIndex = chosen,
            attemptedSlots = attempted,
            onResolveFailure = if (automatic) {
                { switchSlot(req.copy(attemptedSlots = attempted), chosen, true) }
            } else null
        )
    }

    /** 首页「继续观看」：用记录里的地址直接续播，不再走搜索。 */
    fun resume(entry: HistoryEntry) {
        // 历史记录可能来自已经删掉/换掉的源：优先用记录自己的源，找不到就退到当前源继续
        val owner = repo.siteByKey(entry.siteKey)
        val activeSite = owner ?: repo.currentSite ?: site
        if (activeSite == null) {
            Toast.makeText(context, "还没有选择数据源", Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.i("NextMovieTiming", "continue-click vod=${entry.vodId} site=${activeSite.key} ownerMissing=${owner == null}")
        if (owner == null) {
            Toast.makeText(context, "这条记录来自已移除的来源，改用「${activeSite.name}」续播", Toast.LENGTH_SHORT).show()
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

    if (panQrId.isNotBlank()) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { }) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF11151C))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("登录「$panQrLabel」网盘", color = Tokens.Text, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                val bmp = panQrBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.width(115.dp).height(115.dp).background(androidx.compose.ui.graphics.Color.White)
                    )
                } else {
                    Box(
                        modifier = Modifier.width(115.dp).height(115.dp).background(Tokens.Surface),
                        contentAlignment = Alignment.Center
                    ) { Text("生成二维码…", color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium) }
                }
                Spacer(Modifier.height(10.dp))
                Text(panQrMsg, color = Tokens.TextDim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                Text("扫码成功后自动继续播放", color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    val first = remember { FocusRequester() }
                    LaunchedEffect(panQrId) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Corner.pill))
                            .background(Tokens.Accent)
                            .focusRequester(first)
                            .tvClickable(onClick = { val s = site; if (s != null) askPanLogin(s, panQrId, panQrRetry) }, corner = Tokens.Corner.pill)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("重新出码", color = Tokens.OnAccent, style = MaterialTheme.typography.labelMedium) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Corner.pill))
                            .background(Tokens.Surface)
                            .tvClickable(onClick = {
                                panQrId = ""
                                site?.let { accountLauncher.launch(PluginActivity.intent(context, it, "", null)) }
                            }, corner = Tokens.Corner.pill)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("网页登录", color = Tokens.Text, style = MaterialTheme.typography.labelMedium) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Corner.pill))
                            .background(Tokens.Surface)
                            .tvClickable(onClick = { panQrId = "" }, corner = Tokens.Corner.pill)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("关闭", color = Tokens.TextDim, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
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
                shellState.SaveableStateProvider("main-shell") {
                com.nextmovie.ui.shell.MainShell(
                    repo = repo,
                    site = site,
                    onOpenDetail = { source, vod ->
                        if (vod.action.isNotBlank() || vod.tag == "folder") accountLauncher.launch(PluginActivity.intent(context, source, vod.id, vod.action.takeIf { it.isNotBlank() }))
                        else route = Route.Detail(vod, source.key)
                    },
                    onResume = { resume(it) },
                    onOpenAccounts = { accountLauncher.launch(PluginActivity.intent(context, site, "", null)) },
                    initialTab = shellTab,
                    onTabChanged = { shellTab = it },
                    searchState = searchState,
                    homeInitialFocusIndex = homeFocusIndex,
                    homeInitialCategory = homeTab,
                    homeReloadSignal = accountGeneration,
                    onHomeTabChanged = { homeTab = it; homeFocusIndex = 0 },
                    onHomeFocusIndexChanged = { homeFocusIndex = it }
                )
                }
            }
        }

        is Route.Detail -> DetailScreen(
            repo = repo,
            site = repo.siteByKey(current.siteKey.orEmpty()) ?: site ?: return,
            initial = current.vod,
            onPlay = { source, resolvedVod, line, episode, startMs, slots ->
                val idx = line.episodes.indexOf(episode)
                val rest = if (idx >= 0) line.episodes.drop(idx + 1) else emptyList()
                playEpisode(resolvedVod, line, episode, startMs, source, rest, slots)
            },
            onBack = { route = Route.Home },
            videoBackdrop = if (playbackSession.alive) {
                {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx -> playbackSession.view(ctx) },
                        modifier = androidx.compose.ui.Modifier.fillMaxSize()
                    )
                }
            } else null
        )

        is Route.Player -> PlayerScreen(
            title = current.req.title,
            url = current.req.url,
            headers = current.req.headers,
            parse = current.req.parse,
            subtitles = current.req.subtitles,
            requestedAtNanos = current.req.requestedAtNanos,
            seriesTitle = current.req.vodName,
            startPositionMs = current.req.startMs,
            onProgress = { position, duration ->
                lastPositionMs = position
                recordProgress(current.req, position, duration)
            },
            slots = current.req.slots,
            currentSlot = current.req.slotIndex,
            availableRecoverySlot = current.req.slots.indices.firstOrNull { it !in current.req.attemptedSlots },
            onAutomaticSwitch = { index -> switchSlot(current.req, index, true) },
            onPlaybackOutcome = { ok -> repo.recordPlayOutcome(current.req.siteKey, ok) },
            onSwitchSlot = { index -> switchSlot(current.req, index) },
            session = playbackSession,
            nextEpisodeName = current.req.queue.firstOrNull()?.name,
            onPlayNext = {
                val next = current.req.queue.firstOrNull() ?: return@PlayerScreen
                playEpisode(
                    vod = current.req.backTo,
                    line = com.nextmovie.core.model.PlayLine(current.req.playFrom, listOf(next)),
                    episode = next,
                    startMs = 0L,
                    source = repo.siteByKey(current.req.siteKey),
                    queue = current.req.queue.drop(1),
                    slots = current.req.slots.mapNotNull { slot ->
                        val selected = slot.siteKey == current.req.siteKey && slot.lineName == current.req.playFrom
                        val index = if (selected) 0 else slot.remaining.indexOfFirst { it.name == next.name }
                            .takeIf { it >= 0 } ?: 0.takeIf { slot.remaining.size == current.req.queue.size }
                            ?: return@mapNotNull null
                        val episode = slot.remaining.getOrNull(index) ?: return@mapNotNull null
                        slot.copy(episodeName = episode.name, episodeUrl = episode.url, remaining = slot.remaining.drop(index + 1))
                    }
                )
            },
            onBack = { route = Route.Detail(current.req.backTo, current.req.siteKey) }
        )

        Route.Search -> SearchScreen(
            repo = repo,
            site = site ?: return,
            onOpenDetail = { foundSite, vod -> route = Route.Detail(vod, foundSite.key) },
            onBack = { route = Route.Home }
        )

        Route.Favorites -> com.nextmovie.ui.library.FavoritesScreen(
            repo = repo,
            onOpenDetail = { foundSite, vod -> route = Route.Detail(vod, foundSite.key) },
            onBack = { route = Route.Home }
        )

        Route.History -> com.nextmovie.ui.library.HistoryScreen(
            repo = repo,
            onResume = { resume(it) },
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
