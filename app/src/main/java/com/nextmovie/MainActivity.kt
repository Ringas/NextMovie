package com.nextmovie

import android.os.Bundle
import com.nextmovie.core.model.isPanLine
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class MainActivity : ComponentActivity() {

    private lateinit var repo: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.i("NextMovieTiming", "activity-oncreate")
        com.github.catvod.Runtime.setActivity { this }
        super.onCreate(savedInstanceState)
        repo = AppRepository(applicationContext)
        // Android can start the isolated process while Compose and the configuration load.
        repo.startConfiguredPluginHost()
        setContent {
            // 首帧骨架：先画一帧纯色底，AppRoot 的重组合放到它后面。
            // 实测冷启动 3.20s → 2.62s（原生 View 换帧反而更慢，已回退）。
            var warm by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                warm = true
            }
            if (!warm) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0xFF070A12))
                )
            } else {
                com.nextmovie.ui.theme.TvDisplay {
                    NextMovieTheme { AppRoot(repo) }
                }
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
    /** 该网盘插件没有扫码接口（如移动/123/迅雷），只显示账号登录入口，不出假码 */
    var panQrManual by remember { mutableStateOf(false) }
    var panQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var panQrRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var panLoginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 扫码卡片的阶段：加载 / 等待 / 已扫 / 成功 / 账号登录
    var panPhase by remember { mutableStateOf(com.nextmovie.ui.pan.PanPhase.LOADING) }
    /** 当前这张码是不是「手机扫码填单」型 */
    var panQrPhone by remember { mutableStateOf(false) }
    var panSeconds by remember { mutableStateOf(60) }
    // 卡内输入态：短信登录全部在这张卡里完成，登录成功直接续播，不跳配置中心
    var panInput by remember { mutableStateOf<com.nextmovie.ui.pan.PanInputUi?>(null) }
    var panInputBusy by remember { mutableStateOf(false) }
    var panTick by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 插件没有扫码接口的网盘，用宿主自己的离屏 WebView 网关出官方码
    var panWeb by remember { mutableStateOf<com.nextmovie.core.pan.PanWebLogin?>(null) }
    // 「手机扫码填单」通道的局域网服务，卡片一关就关，别常驻
    var panPhoneServer by remember { mutableStateOf<java.net.ServerSocket?>(null) }
    // 跨页面播放会话：Marco 的「退回详情页影片继续播」靠它把 ExoPlayer 留在进程里
    val playbackSession = remember { com.nextmovie.ui.player.PlaybackSession(context) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { playbackSession.release() } }
    // 离开播放/详情（回首页、搜索、设置）就把声音收掉，避免电视上「回桌面还在放」
    androidx.compose.runtime.LaunchedEffect(route) {
        val r = route
        if (r is Route.Player) playbackSession.alive = true
        if (r !is Route.Player && r !is Route.Detail) {
            playbackSession.alive = false
            runCatching { playbackSession.pauseQuietly() }
        }
    }

    // 冷启动时把上次保存的配置自动载入，否则每次重启都会停在「还没有导入数据源」
    LaunchedEffect(Unit) {
        // 预热网盘登录态，但**不能等**：这是网络调用（要等 :spider 的 9978 起来），
        // 放在启动路径上会白白拖慢冷启动（实测多 1–2 秒）。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.nextmovie.core.pan.PanBridge.refresh() }
        }
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
    val accountLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        scope.launch { repo.invalidateContent(); accountGeneration++ }
        // 从「未登录点网盘线路」跳去扫码，扫完带着 pan_login_ok 回来：直接续播刚才那一集。
        val loginOk = res.data?.getStringExtra("pan_login_ok").orEmpty()
        val retry = panQrRetry
        if (loginOk.isNotBlank() && retry != null) {
            panQrRetry = null
            // 登录发生在 PluginActivity 进程，这里把磁盘上的新 Cookie 拉回本进程，否则 has() 仍是旧的
            val fresh = com.nextmovie.core.pan.PanLogin.cookieFor(context, loginOk)
            if (fresh.isNotBlank()) com.nextmovie.core.pan.PanLogin.persist(context, loginOk, fresh)
            android.util.Log.i("NextMoviePan", "login-ok pan=$loginOk -> auto resume len=${fresh.length}")
            retry()
        }
    }

    // 调试入口：adb shell am start -n com.nextmovie/.MainActivity --es dbg_site 花卷 --es dbg_action quark
    LaunchedEffect(Unit) {
        val dbgAct = (context as? android.app.Activity)?.intent?.getStringExtra("dbg_site") ?: return@LaunchedEffect
        val dbgAction = (context as? android.app.Activity)?.intent?.getStringExtra("dbg_action").orEmpty()
        val dbgEntry = (context as? android.app.Activity)?.intent?.getStringExtra("dbg_entry")
        android.util.Log.i("NextMoviePan", "dbg-launch site=$dbgAct action=$dbgAction")
        kotlinx.coroutines.delay(1500)
        val target = repo.sites.firstOrNull { it.name.equals(dbgAct, true) || it.key.equals(dbgAct, true) }
        if (target == null) { android.util.Log.i("NextMoviePan", "dbg-site-missing $dbgAct"); return@LaunchedEffect }
        accountLauncher.launch(PluginActivity.intent(context, target, entry = dbgEntry, action = dbgAction))
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
    fun panOf(text: String): String? = com.nextmovie.core.pan.PanProviders.idOf(text)

    /** 账号/短信类网盘的统一入口：打开插件配置中心（锚点由 PanProviders 决定）。 */
    fun openPluginPanConfig(panId: String, site: Site? = null) {
        val s = site ?: repo.currentSite ?: return
        runCatching {
            context.startActivity(PluginActivity.intent(context, s, "", "panconfig", autoPan = panId))
        }
    }

    fun closePanLogin() {
        runCatching { panWeb?.close() }
        panWeb = null
        runCatching { panPhoneServer?.close() }
        panPhoneServer = null
        panQrId = ""
        panQrBitmap = null
        panInput = null
        panInputBusy = false
        panTick?.cancel()
        panTick = null
        panQrPhone = false
        panPhase = com.nextmovie.ui.pan.PanPhase.LOADING
    }

    /** 验证码倒计时：单条协程，重复点发送先取消旧的，避免多条同时在跑。 */
    fun startPanCountdown(seconds: Int) {
        panTick?.cancel()
        panTick = scope.launch {
            var left = seconds
            while (isActive && left > 0) {
                delay(1000)
                left--
                panInput = panInput?.copy(countdown = left)
            }
        }
    }

    /** 登录成功统一收口：关卡片 → 让来源重算 → 回到原来的重试点继续播。 */
    fun finishPanLoginAndReplay() {
        panQrMsg = "登录成功，正在续播…"
        panPhase = com.nextmovie.ui.pan.PanPhase.OK
        panSeconds = 0
        val next = panQrRetry
        scope.launch {
            delay(900)
            closePanLogin()
            panQrRetry = null
            repo.invalidateContent()
            delay(150)
            next?.invoke()
        }
    }

    /** 走「卡内短信验证码」形态：保持同一张卡、同一个重试点，登录完直接续播。 */
    fun openPanSmsCard(panId: String) {
        panInput = com.nextmovie.ui.pan.PanInputUi(
            title = "短信验证码登录",
            hint = "验证码会发到你填的手机号；登录成功后自动续播当前线路",
            field = com.nextmovie.ui.pan.PanField.PHONE,
        )
        panInputBusy = false
        panPhase = com.nextmovie.ui.pan.PanPhase.INPUT
        panQrMsg = ""
    }

    fun askPanLogin(activeSite: Site, panId: String, retry: (() -> Unit)?) {
        val provider = com.nextmovie.core.pan.PanProviders.of(panId)
        val label = provider?.label ?: "网盘"
        android.util.Log.i("NextMoviePan", "auto-pop login pan=$panId label=$label site=${activeSite.key}")
        panQrRetry = retry
        panQrLabel = label
        panQrBitmap = null
        panSeconds = 60
        panQrManual = com.nextmovie.core.pan.PanProviders.needsConfigPage(panId)
        panQrId = panId
        panLoginJob?.cancel()
        runCatching { panWeb?.close() }
        panWeb = null

        // 统一按 route 分派：插件出码 / 宿主抓官方码 / 手机扫码填单 / 非登录型
        val route = provider?.route ?: com.nextmovie.core.pan.PanProviders.Route.PLUGIN
        panQrPhone = route == com.nextmovie.core.pan.PanProviders.Route.PHONE

        // ⓪ 非登录型（AList / WebDAV / VodDIY）：保留填地址入口，不是扫码
        if (route == com.nextmovie.core.pan.PanProviders.Route.URL) {
            panQrManual = true
            panPhase = com.nextmovie.ui.pan.PanPhase.MANUAL
            panQrMsg = "这一项是服务地址，不是账号登录，请在配置中心填写"
            return
        }

        // ① 阿里云盘：插件完全没有阿里能力，扫码、换 token、存会话、取直链全部宿主自助
        if (route == com.nextmovie.core.pan.PanProviders.Route.NATIVE && panId == "ali") {
            panPhase = com.nextmovie.ui.pan.PanPhase.LOADING
            panQrMsg = "正在向「阿里云盘」官方申请二维码…"
            panSeconds = 240
            panLoginJob = scope.launch(Dispatchers.IO) {
                val session = com.nextmovie.core.pan.AliQrLogin.start()
                if (session == null) {
                    panQrManual = true
                    panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                    panQrMsg = "阿里官方二维码申请失败，点「重新出码」再试"
                    return@launch
                }
                panQrBitmap = qrBitmap(session.content, 720)
                panPhase = com.nextmovie.ui.pan.PanPhase.WAIT
                panQrMsg = "打开「阿里云盘」App 扫一扫，扫完自动续播"
                var left = 240
                while (isActive && panQrId == panId && left > 0) {
                    delay(2000)
                    left -= 2
                    panSeconds = left
                    val poll = com.nextmovie.core.pan.AliQrLogin.poll(session)
                    when (poll.state) {
                        com.nextmovie.core.pan.AliQrLogin.State.CONFIRMED -> {
                            panPhase = com.nextmovie.ui.pan.PanPhase.SCANNED
                            panQrMsg = "已确认，正在换取阿里会话…"
                            val refresh = com.nextmovie.core.pan.AliQrLogin
                                .refreshTokenFromBizExt(poll.bizExt)
                            val cred = refresh?.let { com.nextmovie.core.pan.AliQrLogin.exchange(it) }
                            if (cred != null) {
                                com.nextmovie.core.pan.AliQrLogin.store(context, cred)
                                val files = com.nextmovie.core.pan.AliApi.list(cred)
                                android.util.Log.i(
                                    "NextMovieAli",
                                    "ali-login ok files=" + files.size + " refreshLen=" + cred.refreshToken.length
                                )
                                finishPanLoginAndReplay()
                                return@launch
                            }
                            panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                            panQrMsg = "阿里扫码成功但换取 token 失败，点「重新出码」再试"
                            return@launch
                        }
                        com.nextmovie.core.pan.AliQrLogin.State.SCANNED -> {
                            panPhase = com.nextmovie.ui.pan.PanPhase.SCANNED
                            panQrMsg = "已扫码，请在手机上确认登录"
                        }
                        com.nextmovie.core.pan.AliQrLogin.State.EXPIRED -> {
                            panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                            panQrMsg = "二维码已过期，点「重新出码」再试"
                            return@launch
                        }
                        else -> Unit
                    }
                }
                if (panQrId == panId) {
                    panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                    panQrMsg = "阿里扫码未完成，点「重新出码」再试"
                }
            }
            return
        }

        // ① UC：官方 CAS 扫码由宿主原生完成（UC浏览器 App 扫，扫完换 __pus 写回插件）
        if (route == com.nextmovie.core.pan.PanProviders.Route.NATIVE) {
            panPhase = com.nextmovie.ui.pan.PanPhase.LOADING
            panQrMsg = "正在向「$label」官方申请二维码…"
            panSeconds = 240
            panLoginJob = scope.launch(Dispatchers.IO) {
                val session = com.nextmovie.core.pan.UcQrLogin.start()
                if (session == null) {
                    panQrManual = true
                    panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                    panQrMsg = "UC 官方二维码申请失败，点「重新出码」再试"
                    return@launch
                }
                panQrBitmap = qrBitmap(session.content, 720)
                panPhase = com.nextmovie.ui.pan.PanPhase.WAIT
                panQrMsg = "打开「UC浏览器」App 扫一扫，扫完自动续播"
                android.util.Log.i("NextMovieUc", "uc-qr card token=" + session.token.take(8))
                var left = 240
                while (isActive && panQrId == panId && left > 0) {
                    delay(2000)
                    left -= 2
                    panSeconds = left
                    val poll = com.nextmovie.core.pan.UcQrLogin.poll(session.token)
                    when (poll.state) {
                        com.nextmovie.core.pan.UcQrLogin.State.OK -> {
                            panPhase = com.nextmovie.ui.pan.PanPhase.SCANNED
                            panQrMsg = "已确认，正在换取 UC 会话…"
                            val cookie = com.nextmovie.core.pan.UcQrLogin.exchange(poll.ticket)
                            if (!cookie.isNullOrBlank()) {
                                val ok = com.nextmovie.core.pan.PanBridge.setCookie(panId, cookie)
                                android.util.Log.i("NextMovieUc", "uc-qr setCookie ok=$ok")
                                if (ok) {
                                    finishPanLoginAndReplay()
                                    return@launch
                                }
                                panQrMsg = "已拿到 UC 会话，但插件没接收这个 cookie"
                            } else {
                                panQrMsg = "UC 会话交换失败，点「重新出码」再试"
                            }
                            panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                            return@launch
                        }
                        com.nextmovie.core.pan.UcQrLogin.State.SCANNED -> {
                            panPhase = com.nextmovie.ui.pan.PanPhase.SCANNED
                            panQrMsg = "已扫码，请在手机上确认登录"
                        }
                        com.nextmovie.core.pan.UcQrLogin.State.EXPIRED -> {
                            panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                            panQrMsg = "二维码已过期，点「重新出码」再试"
                            return@launch
                        }
                        else -> Unit
                    }
                }
                if (panQrId == panId) {
                    panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                    panQrMsg = "UC 扫码未完成，点「重新出码」再试"
                }
            }
            return
        }

        // ① 插件既没有官方码、宿主也抓不到码的网盘：局域网起一次性填单页，手机扫码后在手机上填
        if (route == com.nextmovie.core.pan.PanProviders.Route.PHONE) {
            panPhase = com.nextmovie.ui.pan.PanPhase.LOADING
            panQrMsg = "正在准备「$label」手机填写页…"
            val (url, srv) = com.nextmovie.core.pan.PanPhoneLogin.serve(context, panId, label)
            if (url.isBlank()) {
                panQrManual = true
                panPhase = com.nextmovie.ui.pan.PanPhase.MANUAL
                panQrMsg = "没拿到局域网地址（电视是否连了 Wi-Fi？），可改用配置中心完成一次"
                return
            }
            panPhoneServer = srv
            panQrBitmap = qrBitmap(url, 720)
            panPhase = com.nextmovie.ui.pan.PanPhase.WAIT
            panQrMsg = "手机扫码打开填写页，填完电视这边自动续播"
            panSeconds = 240
            android.util.Log.i("NextMoviePan", "phone-qr pan=$panId url=${url.take(40)}")
            panLoginJob = scope.launch {
                var left = 240
                while (isActive && panQrId == panId && left > 0) {
                    delay(1000)
                    left--
                    panSeconds = left
                    if (com.nextmovie.core.pan.PanPhoneLogin.lastOk) {
                        android.util.Log.i("NextMoviePan", "phone-login-ok pan=$panId")
                        finishPanLoginAndReplay()
                        return@launch
                    }
                }
                if (panQrId == panId) {
                    panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                    panQrMsg = "手机那边没填完，点「重新出码」再试"
                }
            }
            return
        }

        // ② 插件没有扫码接口但官方有码的网盘（移动 139）：宿主离屏 WebView 打开官方登录页，抓官方二维码
        val webSpec = if (route == com.nextmovie.core.pan.PanProviders.Route.HOST)
            com.nextmovie.core.pan.PanWebLogin.specOf(provider?.id ?: panId) else null
        if (webSpec != null) {
            val act = context as? android.app.Activity
            panPhase = com.nextmovie.ui.pan.PanPhase.LOADING
            panQrMsg = "正在向「$label」官方页面申请二维码…"
            if (act == null) {
                panQrManual = true
                panPhase = com.nextmovie.ui.pan.PanPhase.MANUAL
                panQrMsg = "登录页启动失败，可改用账号登录"
                return
            }
            panWeb = com.nextmovie.core.pan.PanWebLogin(
                activity = act,
                spec = webSpec,
                label = label,
                onQr = { bmp, msg -> panQrBitmap = bmp; if (msg.isNotBlank()) panQrMsg = msg },
                onPhase = { phase, msg ->
                    panPhase = when (phase) {
                        com.nextmovie.core.pan.PanWebLogin.Phase.LOADING -> com.nextmovie.ui.pan.PanPhase.LOADING
                        com.nextmovie.core.pan.PanWebLogin.Phase.WAIT -> com.nextmovie.ui.pan.PanPhase.WAIT
                        com.nextmovie.core.pan.PanWebLogin.Phase.SCANNED -> com.nextmovie.ui.pan.PanPhase.SCANNED
                        com.nextmovie.core.pan.PanWebLogin.Phase.OK -> com.nextmovie.ui.pan.PanPhase.OK
                        com.nextmovie.core.pan.PanWebLogin.Phase.FAIL -> com.nextmovie.ui.pan.PanPhase.FAIL
                    }
                    if (msg.isNotBlank()) panQrMsg = msg
                },
                onLogin = { cookie ->
                    // 139 插件没有写入通道，凭据由宿主自己收下、自己直连官方接口：
                    // 宿主会话存下来就算登录成功，不要再拿插件状态判断
                    if (panId == "pan139") {
                        runCatching { com.nextmovie.core.pan.Yun139Api.storeSession(context, cookie) }
                        if (com.nextmovie.core.pan.Yun139Api.hasSession(context)) {
                            android.util.Log.i("NextMovie139", "host-session ok len=${cookie.length}")
                            finishPanLoginAndReplay()
                        } else {
                            panQrManual = true
                            panPhase = com.nextmovie.ui.pan.PanPhase.MANUAL
                            panQrMsg = "官方页面登录成功但没抓到 139 凭据，可点「重新出码」再试"
                        }
                    }
                    if (panId != "pan139") {
                    val accepted = runCatching { com.nextmovie.core.pan.PanBridge.setCookie(panId, cookie) }.getOrDefault(false)
                    val visible = runCatching {
                        com.nextmovie.core.pan.PanBridge.refresh()?.let { com.nextmovie.core.pan.PanBridge.has(panId) }
                    }.getOrNull() == true
                    if (accepted || visible) {
                        finishPanLoginAndReplay()
                    } else {
                        // 插件对这家网盘没有 writeAction：别假装成功，直接给账号登录入口
                        panQrManual = true
                        panPhase = com.nextmovie.ui.pan.PanPhase.MANUAL
                        panQrMsg = "已在官方页面登录「$label」，但插件没有这个网盘的凭据写入接口，请在配置中心完成账号登录"
                        runCatching { panWeb?.close() }
                        panWeb = null
                    }
                    }
                },
                onFail = { msg ->
                    panQrManual = true
                    panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                    if (msg.isNotBlank()) panQrMsg = msg
                }
            )
            panWeb?.start()
            scope.launch {
                while (isActive && panQrId == panId && panPhase != com.nextmovie.ui.pan.PanPhase.OK && panSeconds > 0) {
                    delay(1000)
                    panSeconds--
                }
            }
            return
        }

        // ② 插件自带扫码接口：向来源要码 → 展示 → 轮询 → 扫完续播
        panPhase = com.nextmovie.ui.pan.PanPhase.LOADING
        panQrMsg = "正在向来源申请登录二维码…"
        panLoginJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && panQrId == panId && attempt < 8) {
                attempt++
                val start = com.nextmovie.core.pan.PanBridge.loginStart(panId)
                val qrUrl = start?.optString("qrUrl").orEmpty()
                val taskId = start?.optString("taskId").orEmpty()
                if (qrUrl.isBlank() || taskId.isBlank()) {
                    val why = start?.optString("msg").orEmpty()
                    if (why.contains("Unsupported", true) || attempt >= 2) {
                        panQrManual = true
                        panPhase = com.nextmovie.ui.pan.PanPhase.MANUAL
                        panQrMsg = if (why.contains("Unsupported", true))
                            "该网盘在插件里只支持账号/短信登录，没有扫码接口"
                        else why.ifBlank { "没拿到二维码，可重试或改用配置中心" }
                        return@launch
                    }
                    panQrMsg = why.ifBlank { "来源没有返回登录二维码" }
                    delay(2500)
                    continue
                }
                panQrBitmap = qrBitmap(qrUrl, 720)
                panPhase = com.nextmovie.ui.pan.PanPhase.WAIT
                panQrMsg = "打开「$label」App 扫一扫"
                android.util.Log.i("NextMoviePan", "qr-start pan=$panId task=$taskId urllen=${qrUrl.length}")
                var done = false
                val deadline = System.currentTimeMillis() + 95_000
                while (isActive && panQrId == panId && System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    val poll = com.nextmovie.core.pan.PanBridge.loginPoll(panId, taskId)
                    val st = poll?.optString("status").orEmpty()
                    if (st == "success" || poll?.optBoolean("success") == true) { done = true; break }
                    if (st == "error" || poll?.optBoolean("terminal") == true) {
                        panQrMsg = poll?.optString("msg").orEmpty().ifBlank { "二维码已过期，正在刷新…" }
                        android.util.Log.i("NextMoviePan", "qr-poll terminal pan=$panId $st")
                        break
                    }
                    if (st == "scanned" || st == "confirm") {
                        panPhase = com.nextmovie.ui.pan.PanPhase.SCANNED
                        panQrMsg = "已扫码，请在手机上确认"
                    }
                    if (com.nextmovie.core.pan.PanBridge.refresh()?.let { com.nextmovie.core.pan.PanBridge.has(panId) } == true) {
                        done = true; break
                    }
                }
                if (done || com.nextmovie.core.pan.PanBridge.has(panId)) {
                    android.util.Log.i("NextMoviePan", "qr-login-ok pan=$panId")
                    finishPanLoginAndReplay()
                    return@launch
                }
            }
            if (panQrId == panId) {
                panPhase = com.nextmovie.ui.pan.PanPhase.FAIL
                panQrMsg = "二维码没拿到或已过期，点「重新出码」再试"
            }
        }
    }

    // 调试入口：验证宿主 139 客户端（签名 → 文件列表 → 直链）
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent ?: return@LaunchedEffect
        if (intent.getStringExtra("dbg_139") == null) return@LaunchedEffect
        delay(800)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            if (!com.nextmovie.core.pan.Yun139Api.hasSession(context)) {
                val f = java.io.File(context.filesDir, "panweb-cookie.txt")
                if (f.exists()) com.nextmovie.core.pan.Yun139Api.storeSession(context, f.readText())
            }
            val list = com.nextmovie.core.pan.Yun139Api.list(context)
            android.util.Log.i("NextMovie139", "dbg-list n=${list.size} " + list.joinToString("|") { "${it.name}:${it.size}:${it.extension}" })
            val vid = list.firstOrNull { !it.isFolder && (it.extension == "mp4" || it.extension == "mkv") }
            if (vid != null) {
                val url = com.nextmovie.core.pan.Yun139Api.downloadUrl(context, vid.fileId)
                android.util.Log.i("NextMovie139", "dbg-url ${vid.name} len=${url.length} head=${url.take(90)}")
            } else {
                android.util.Log.i("NextMovie139", "dbg-url 没有可播放文件")
            }
        }.onFailure { android.util.Log.w("NextMovie139", "dbg-139 失败: ${it}") }
        }
    }

    // 调试入口：起一个保活的离屏 139 WebView，在页面上下文里跑任意 JS（探真实接口用）
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent ?: return@LaunchedEffect
        val rawJs = intent.getStringExtra("dbg_panjs") ?: return@LaunchedEffect
        val js = if (rawJs.startsWith("b64:")) {
            String(android.util.Base64.decode(rawJs.removePrefix("b64:"), android.util.Base64.DEFAULT))
        } else rawJs
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        val spec = com.nextmovie.core.pan.PanWebLogin.specOf("pan139") ?: return@LaunchedEffect
        val probe = com.nextmovie.core.pan.PanWebLogin(
            activity = act,
            spec = spec,
            label = "移动网盘",
            onQr = { _, _ -> },
            onPhase = { _, _ -> },
            onLogin = { _ -> },
            onFail = { _ -> },
            keepAfterLogin = true,
        )
        probe.start()
        delay(11_000)
        probe.evalJs(js, "dbg1")
        delay(5_000)
        probe.evalJs(js, "dbg2")
    }

    // 调试入口：直接走「未登录 → 弹码 → 轮询 → 续播」这条链，不用手动翻详情页
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent ?: return@LaunchedEffect
        val pan = intent.getStringExtra("dbg_panlogin") ?: return@LaunchedEffect
        delay(1_200)
        val target = repo.currentSite ?: repo.sites.firstOrNull() ?: return@LaunchedEffect
        android.util.Log.i("NextMoviePan", "dbg-panlogin pan=$pan site=${target.key}")
        askPanLogin(target, pan, null)
    }

    // 调试入口：阿里会话自检（列目录 + 取直链 + Range 探针），不需要重新扫码
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent ?: return@LaunchedEffect
        if (intent.getStringExtra("dbg_alicheck") == null) return@LaunchedEffect
        delay(1_200)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { com.nextmovie.core.pan.AliApi.selfCheck(context) }
                .getOrElse { "异常 " + it.javaClass.simpleName + " " + it.message }
            android.util.Log.i("NextMovieAli", "alicheck => " + result)
        }
    }

    // 调试入口：阿里直链双客户端探针（定位 403 是客户端还是链路）
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent ?: return@LaunchedEffect
        if (intent.getStringExtra("dbg_aliprobe") == null) return@LaunchedEffect
        delay(1_200)
        scope.launch(Dispatchers.IO) {
            val r = runCatching { com.nextmovie.core.pan.AliApi.probeVariants(context) }
                .getOrElse { "异常 " + it.javaClass.simpleName + " " + it.message }
            android.util.Log.i("NextMovieAli", "aliprobe => " + r)
        }
    }

    // 调试入口：验证「写 prefs → 重启 :spider → 插件重读」这条凭据写入通道
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent ?: return@LaunchedEffect
        val raw = intent.getStringExtra("dbg_panwrite") ?: return@LaunchedEffect
        val bar = raw.indexOf('|')
        if (bar <= 0) return@LaunchedEffect
        val keys = raw.substring(0, bar).split(',').filter { it.isNotBlank() }
        val value = raw.substring(bar + 1)
        val ok = com.nextmovie.core.pan.PanPrefs.writeStrings(context, keys.associateWith { value })
        android.util.Log.i("NextMoviePan", "dbg-panwrite keys=$keys ok=$ok read=" +
            keys.joinToString(",") { "${it}=${com.nextmovie.core.pan.PanPrefs.readString(context, it)?.length}" })
        com.nextmovie.core.pan.PanPrefs.restartPlugin(context)
        delay(600)
        repo.invalidateContent()
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
        // 云盘线路 + 本机还没有该网盘 Cookie：不再白等 15s 解析，直接弹官方扫码，
        // 扫完自动回到这一集续播（闭环）。
        val panId = panOf(line.name).takeIf { line.isPanLine }
        if (panId != null && !com.nextmovie.core.pan.PanBridge.has(panId)) {
            android.util.Log.i("NextMoviePan", "pan-line needs login line=${line.name} pan=$panId")
            askPanLogin(activeSite, panId) {
                playEpisode(vod, line, episode, startMs, source, queue, slots, slotIndex, attemptedSlots, onResolveFailure)
            }
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
                    android.util.Log.i("NextMovieDiag", "play-episode line=${line.name} url=${episode.url.take(300)}")
                    withTimeout(15_000) {
                        if (activeSite.isCms || activeSite.isJavaScript) repo.resolvePlay(activeSite, line.name, episode.url) { resolveStage = it }
                        else PluginPlaybackWindow.resolve(context) { repo.resolvePlay(activeSite, line.name, episode.url) { resolveStage = it } }
                    }
                }
                catch (e: TimeoutCancellationException) {
                    // 超时也走同一条失败路径：换线 / 给人话提示
                    repo.recordPlayOutcome(activeSite.key, false, line.name)
                    android.util.Log.i("NextMovieTiming", "line-play-timeout site=${activeSite.key} line=${line.name}")
                    if (onResolveFailure != null) { retry = true; return@launch }
                    Toast.makeText(context, "「${line.name}」15 秒没拿到地址，已换下一条线路", Toast.LENGTH_LONG).show()
                    return@launch
                }
                catch (e: kotlinx.coroutines.CancellationException) { repo.cancelPlayback(activeSite); throw e }
                catch (e: Exception) {
                    repo.recordPlayOutcome(activeSite.key, false, line.name)
                    val reason = "line-play-fail site=${activeSite.key} line=${line.name} " +
                        "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
                    android.util.Log.i("NextMovieTiming", reason)
                    android.util.Log.i("NextMoviePlayTrace", "throwable", e)
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
                    repo.recordPlayOutcome(activeSite.key, false, line.name)
                    if (onResolveFailure != null) retry = true
                    else Toast.makeText(context, "解析失败，换个线路试试", Toast.LENGTH_SHORT).show()
                }

                result.parse != 0 -> {
                    repo.recordPlayOutcome(activeSite.key, false, line.name)
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
                        title = vod.name + " · " + com.nextmovie.ui.common.HistoryText
                            .episodeLabel(episode.name, vod.name).ifBlank { "正片" },
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
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { closePanLogin() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.nextmovie.ui.pan.PanLoginDialog(
            label = panQrLabel,
            phase = panPhase,
            phoneQr = panQrPhone,
            qr = panQrBitmap,
            message = panQrMsg,
            secondsLeft = panSeconds,
            onRetry = {
                val s = repo.currentSite
                if (s != null) askPanLogin(s, panQrId, panQrRetry) else closePanLogin()
            },
            secondaryLabel = if (com.nextmovie.core.pan.PanProviders.of(panQrId)?.kind == com.nextmovie.core.pan.PanProviders.Kind.SMS)
                "短信登录" else "账号登录",
            onAccount = {
                val id = panQrId
                val pv = com.nextmovie.core.pan.PanProviders.of(id)
                if (pv != null && pv.kind == com.nextmovie.core.pan.PanProviders.Kind.SMS) {
                    openPanSmsCard(id)
                } else {
                    val s = repo.currentSite
                    closePanLogin()
                    if (s != null) accountLauncher.launch(PluginActivity.intent(context, s, "", "panconfig"))
                }
            },
            onClose = { closePanLogin() },
            input = panInput?.let { it.copy(busy = panInputBusy) },
            onKey = { c ->
                val cur = panInput
                if (cur != null) {
                    panInput = when (cur.field) {
                        com.nextmovie.ui.pan.PanField.PHONE ->
                            if (cur.phone.length < 11) cur.copy(phone = cur.phone + c, error = "") else cur
                        com.nextmovie.ui.pan.PanField.CODE ->
                            if (cur.code.length < 6) cur.copy(code = cur.code + c, error = "") else cur
                    }
                }
            },
            onBackspace = {
                val cur = panInput
                if (cur != null) {
                    panInput = when (cur.field) {
                        com.nextmovie.ui.pan.PanField.PHONE -> cur.copy(phone = cur.phone.dropLast(1), error = "")
                        com.nextmovie.ui.pan.PanField.CODE -> cur.copy(code = cur.code.dropLast(1), error = "")
                    }
                }
            },
            onClear = {
                val cur = panInput
                if (cur != null) {
                    panInput = when (cur.field) {
                        com.nextmovie.ui.pan.PanField.PHONE -> cur.copy(phone = "", error = "")
                        com.nextmovie.ui.pan.PanField.CODE -> cur.copy(code = "", error = "")
                    }
                }
            },
            onPickField = { f ->
                val cur = panInput
                if (cur != null) panInput = cur.copy(field = f, error = "")
            },
            onSend = {
                val cur = panInput
                if (cur == null || panInputBusy) {
                    // 忙的时候不重入
                } else if (cur.phone.length < 11) {
                    panInput = cur.copy(error = "先输入 11 位手机号")
                } else {
                    val panId = panQrId
                    val phone = cur.phone
                    panInputBusy = true
                    panInput = cur.copy(error = "", busy = true)
                    scope.launch(Dispatchers.IO) {
                        val r = com.nextmovie.core.pan.PanBridge.smsSend(panId, phone)
                        val ok = com.nextmovie.core.pan.PanBridge.isOk(r)
                        val msg = com.nextmovie.core.pan.PanBridge.msgOf(r)
                        android.util.Log.i("NextMoviePan", "sms-send pan=$panId ok=$ok msg=$msg")
                        withContext(Dispatchers.Main) {
                            panInputBusy = false
                            panInput = panInput?.copy(
                                sent = ok || panInput?.sent == true,
                                field = if (ok) com.nextmovie.ui.pan.PanField.CODE else com.nextmovie.ui.pan.PanField.PHONE,
                                countdown = if (ok) 60 else 0,
                                busy = false,
                                error = when {
                                    ok -> ""
                                    msg.isBlank() -> "验证码没发出去，检查手机号后再试"
                                    else -> msg
                                },
                            )
                            if (ok) startPanCountdown(240)
                        }
                    }
                }
            },
            onSubmit = {
                val cur = panInput
                if (cur != null && !panInputBusy) {
                    if (cur.phone.length < 11) {
                        panInput = cur.copy(error = "先输入 11 位手机号")
                    } else if (cur.code.length < 4) {
                        panInput = cur.copy(error = "验证码位数不够")
                    } else {
                        val panId = panQrId
                        val phone = cur.phone
                        val code = cur.code
                        panInputBusy = true
                        panInput = cur.copy(error = "", busy = true)
                        scope.launch(Dispatchers.IO) {
                            val r = com.nextmovie.core.pan.PanBridge.smsLogin(panId, phone, code)
                            val logged = com.nextmovie.core.pan.PanBridge.isOk(r)
                            val msg = com.nextmovie.core.pan.PanBridge.msgOf(r)
                            val visible = if (logged) {
                                runCatching {
                                    com.nextmovie.core.pan.PanBridge.refresh()?.let { com.nextmovie.core.pan.PanBridge.has(panId) }
                                }.getOrNull() == true
                            } else false
                            android.util.Log.i("NextMoviePan", "sms-login pan=$panId ok=$logged visible=$visible msg=$msg")
                            withContext(Dispatchers.Main) {
                                panInputBusy = false
                                if (logged || visible) {
                                    finishPanLoginAndReplay()
                                } else {
                                    panInput = panInput?.copy(
                                        busy = false,
                                        error = msg.ifBlank { "验证码不正确，或插件还没拿到登录态" },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
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
/** 把登录短链编成二维码位图（登录页是官方 su.quark.cn 短链，App 扫了才会认）。 */
private fun qrBitmap(content: String, size: Int): android.graphics.Bitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) black else white)
    }
    return bmp
}
