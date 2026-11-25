package com.nextmovie.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import com.nextmovie.ui.common.tvClickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nextmovie.core.net.PlayerDataSources
import androidx.media3.exoplayer.ExoPlayer

import com.nextmovie.ui.player.engine.DecodeMode
import com.nextmovie.ui.player.engine.MediaSourceBuilder
import com.nextmovie.ui.player.engine.NmRenderersFactory
import com.nextmovie.ui.player.engine.PlaybackRecovery
import com.nextmovie.ui.player.engine.PlayerPrefs
import com.nextmovie.ui.player.drm.DrmSupport
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.PlayerView
import com.nextmovie.core.net.Http
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 播放页：Media3 直链播放 + 自绘 HUD。
 *
 * 不用 PlayerView 自带控制器：那套是手机版式（底部横条 + 图标），在 10-foot 屏上又小又难看清，
 * 而且它自己在做显隐，和键盘/鼠标事件抢焦点。这里关掉控制器，HUD 全部自己画，
 * 按任意键唤出、播放时 4 秒后自动隐藏。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    title: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    /** 插件返回的 parse 标记：0=直链，非 0=需要网页嗅探 */
    parse: Int = 0,
    subtitles: List<com.nextmovie.core.model.ExternalSubtitle> = emptyList(),
    requestedAtNanos: Long = 0L,
    seriesTitle: String = "",
    /** 续播起点；0 表示从头播放 */
    startPositionMs: Long = 0L,
    /** 每 5 秒以及退出时回报一次进度，交给历史记录落盘 */
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    /** 下一集的名称；为空表示这是最后一集（或单集影片） */
    nextEpisodeName: String? = null,
    /** 播完自动接上下一集；调用方负责把新一集重新解析成播放地址 */
    onPlayNext: () -> Unit = {},
    /** 聚合出来的可切换线路（同一集在不同源的对应地址），按优选顺序 */
    slots: List<com.nextmovie.core.model.PlaySlot> = emptyList(),
    currentSlot: Int = 0,
    onSwitchSlot: (Int) -> Unit = {},
    /** 跨页面会话：从播放器退回详情页时用它继续播，不销毁播放器 */
    session: PlaybackSession? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var decodeMode by remember { mutableStateOf(session?.currentDecodeMode() ?: PlayerPrefs.decodeMode(context)) }
    var triedSoft by remember { mutableStateOf(session?.triedSoftFallback ?: false) }
    // 软解降级成功后 +1，驱动下方 LaunchedEffect 用新 Renderers 重新 prepare
    var softRetryToken by remember { mutableIntStateOf(0) }
    val player = session?.player ?: remember(decodeMode) {
        ExoPlayer.Builder(context, NmRenderersFactory.create(context, decodeMode)).build()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var requestStarted by remember { mutableLongStateOf(0L) }
    val playerPrefs = remember(context) { context.getSharedPreferences("player_preferences", android.content.Context.MODE_PRIVATE) }
    val seriesKey = remember(seriesTitle) { com.nextmovie.core.match.TitleMatcher.normalize(seriesTitle) }
    var introMs by remember(seriesKey) { mutableLongStateOf(playerPrefs.getLong("intro:$seriesKey", 0L)) }
    var creditsMs by remember(seriesKey) { mutableLongStateOf(playerPrefs.getLong("credits:$seriesKey", 0L)) }
    var introHandled by remember(requestedAtNanos, url) { mutableStateOf(false) }
    var creditsHandled by remember(requestedAtNanos, url) { mutableStateOf(false) }
    var sleepDeadline by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(sleepDeadline) {
        if (sleepDeadline == 0L) return@LaunchedEffect
        delay((sleepDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        player.pause()
        sleepDeadline = 0L
    }
    var buffering by remember { mutableStateOf(true) }
    var trackBadges by remember { mutableStateOf(emptyList<String>()) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var resumedHint by remember { mutableStateOf(false) }
    var keyHint by remember { mutableStateOf<String?>(null) }
    var speed by remember {
        mutableFloatStateOf(playerPrefs.getFloat("speed", 1f).coerceIn(0.75f, 2f))
    }
    var ended by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var hudVisible by remember { mutableStateOf(true) }
    var hudSignal by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val toolbarFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
    var resizeMode by remember {
        mutableIntStateOf(playerPrefs.getInt("resize_mode", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT))
    }
    // 线路切换面板：不抢 Compose 焦点（播放器根节点独占按键），方向键在这里走自己的游标
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerIndex by remember { androidx.compose.runtime.mutableIntStateOf(currentSlot.coerceIn(0, slots.lastIndex.coerceAtLeast(0))) }
    // 控制条是按需组合的，慢设备上 50ms 内还没进组合树，直接 requestFocus 会抛
    // 「FocusRequester is not initialized」把播放器进程带崩；这里等组合完成再抢焦点。
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        repeat(8) {
            delay(100)
            if (runCatching { toolbarFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val scope = rememberCoroutineScope()

    fun flash(msg: String) {
        keyHint = msg
        hudVisible = true
        hudSignal++
    }

    val latestOnProgress by androidx.compose.runtime.rememberUpdatedState(onProgress)
    fun report() {
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        if (position <= 0L) return
        val duration = runCatching { player.duration }.getOrDefault(0L).let { if (it > 0L) it else 0L }
        latestOnProgress(position, duration)
    }

    val latestRequestedAt by androidx.compose.runtime.rememberUpdatedState(requestedAtNanos)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackBadges = buildList {
                    val formats = tracks.groups.flatMap { group ->
                        (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) }
                    }
                    formats.firstOrNull { it.height > 0 }?.height?.let { height ->
                        add(if (height >= 2160) "4K" else "${height}p")
                    }
                    formats.firstOrNull { it.channelCount > 0 }?.let { audio ->
                        val codec = when (audio.sampleMimeType) {
                            "audio/eac3-joc" -> "Dolby Atmos"
                            "audio/eac3" -> "Dolby Digital Plus"
                            "audio/ac3" -> "Dolby Digital"
                            "audio/true-hd" -> "Dolby TrueHD"
                            "audio/vnd.dts.hd" -> "DTS-HD"
                            "audio/vnd.dts" -> "DTS"
                            "audio/mp4a-latm" -> "AAC"
                            else -> null
                        }
                        codec?.let { add(it) }
                        when (audio.channelCount) { 6 -> add("5.1"); 8 -> add("7.1") }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                if (requestStarted > 0) {
                    val now = System.nanoTime()
                    android.util.Log.i("NextMovieTiming", "first-frame ${(now - requestStarted) / 1_000_000}ms")
                    if (latestRequestedAt > 0) {
                        android.util.Log.i("NextMovieTiming", "play-to-first-frame ${(now - latestRequestedAt) / 1_000_000}ms")
                    }
                    requestStarted = 0
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val decision = PlaybackRecovery.decide(
                    error = error,
                    currentMode = decodeMode,
                    alreadyTriedSoft = triedSoft || (session?.triedSoftFallback == true),
                    hasMoreLines = slots.size > 1,
                    autoSoft = PlayerPrefs.autoSoftOnDecodeError(context),
                    autoLine = PlayerPrefs.autoLineOnError(context),
                )
                when (decision.action) {
                    PlaybackRecovery.Action.RETRY_SOFT -> {
                        flash(decision.message)
                        triedSoft = true
                        session?.triedSoftFallback = true
                        val mode = decision.nextDecodeMode ?: DecodeMode.SOFT
                        decodeMode = mode
                        if (session != null) {
                            session.rebuildWithDecodeMode(mode)
                        }
                        // 无论有无会话：bump token 让 prepare LaunchedEffect 用当前 player 重跑
                        playbackError = null
                        softRetryToken += 1
                        hudVisible = true
                    }
                    PlaybackRecovery.Action.SWITCH_LINE -> {
                        flash(decision.message)
                        if (slots.size > 1) {
                            val next = (currentSlot + 1) % slots.size
                            pickerIndex = next
                        }
                        playbackError = decision.message
                        hudVisible = true
                    }
                    PlaybackRecovery.Action.SHOW_ERROR -> {
                        playbackError = decision.message
                        hudVisible = true
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    // 单集影片播完就停住，不要莫名其妙跳回详情
                    if (nextEpisodeName != null) countdown = 5
                }
            }
        }
        player.addListener(listener)
        onDispose {
            // 退出前补记一次，避免最后 5 秒的进度丢失
            report()
            player.removeListener(listener)
            // 有会话时（退回详情页）不销毁播放器，画面会接着在详情页底层播
            if (session == null) player.release()
        }
    }

    // 播放期间保持屏幕常亮，退出时恢复；退到后台立刻暂停，避免电视上「回了桌面还在放声音」
    DisposableEffect(view, lifecycleOwner, player) {
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) runCatching { player.pause() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(url, headers, startPositionMs, subtitles, requestedAtNanos, softRetryToken, decodeMode) {
        val activePlayer = session?.player ?: player
        requestStarted = System.nanoTime()
        trackBadges = emptyList()
        buffering = true
        playbackError = null
        ended = false
        countdown = 0
        // 插件只要能给你直链就能播；不能给的两种情况先讲清楚，别让用户对着黑屏猜
        if (parse != 0) {
            playbackError = "该站点给的是播放页而不是直链（parse=$parse），需要网页嗅探才能取到真实地址；可先换一条线路或换源"
            return@LaunchedEffect
        }
        // 注意：不少 CDN 对 Referer 做白名单校验，乱补一个同域 Referer 反而 403，
        // 所以只在源站/插件明确给了 Referer 时才带。
        val captionUrls = hashSetOf<String>()
        val firstBytes = java.util.concurrent.atomic.AtomicBoolean()
        val transferListener = object : androidx.media3.datasource.TransferListener {
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, spec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork && bytesTransferred > 0 && spec.uri.toString() !in captionUrls && firstBytes.compareAndSet(false, true)) {
                    android.util.Log.i("NextMovieTiming", "play-first-media-response ${(System.nanoTime() - requestedAtNanos) / 1_000_000}ms")
                }
            }
        }
        val dataSourceFactory = PlayerDataSources.factory(headers)
        // 关键：不能只靠 url 后缀判断。CMS 常把 m3u8 藏在没有扩展名的地址后面，
        // 一旦误判成渐进式就会报 PARSING_CONTAINER_UNSUPPORTED。这里先看后缀，
        // 再看响应体开头是不是 #EXTM3U。
        val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        val knownFile = listOf(".mp4", ".mkv", ".webm", ".flv", ".ts", ".mp3", ".m4v").any { path.endsWith(it) }
        val isHls = url.contains(".m3u8", ignoreCase = true) || (!knownFile && sniffHls(url, headers))
        android.util.Log.d("NextMoviePlay", "prepare hls=$isHls")
        val captionConfigs = subtitles.mapNotNull { subtitle ->
            val uri = runCatching { java.net.URI(url).resolve(subtitle.url) }.getOrNull()
                ?: return@mapNotNull null
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return@mapNotNull null
            val mimeType = subtitle.mimeType.takeIf {
                it in listOf("application/x-subrip", "text/vtt", "text/x-ssa", "application/ttml+xml")
            } ?: when (uri.path.orEmpty().substringAfterLast('.').lowercase()) {
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                "ass", "ssa" -> "text/x-ssa"
                "ttml", "dfxp" -> "application/ttml+xml"
                else -> return@mapNotNull null
            }
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri.toString()))
                .setMimeType(mimeType).setLabel(subtitle.label.ifBlank { null }).build()
        }
        val item = MediaItem.Builder().setUri(url)
            .setMimeType(if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
            .setSubtitleConfigurations(captionConfigs)
            .build()
        val captionUris = captionConfigs.mapTo(hashSetOf()) { it.uri }
        captionUris.mapTo(captionUrls) { it.toString() }
        val requestFactory = if (captionUris.isEmpty()) dataSourceFactory else {
            androidx.media3.datasource.ResolvingDataSource.Factory(PlayerDataSources.factory(emptyMap())) { spec ->
                if (spec.uri in captionUris) spec else spec.withAdditionalHeaders(headers)
            }
        }
        val measuredFactory = androidx.media3.datasource.DataSource.Factory {
            requestFactory.createDataSource().apply {
                if (requestedAtNanos > 0) addTransferListener(transferListener)
            }
        }
        val drmSpec = DrmSupport.parseFromPlayMeta(headers)
        val drmConfig = DrmSupport.mediaItemDrm(drmSpec)
        val mediaSource = MediaSourceBuilder.build(
            url = url,
            dataSourceFactory = measuredFactory,
            headers = headers,
            mimeHint = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            subtitleConfigs = captionConfigs,
            drmConfig = drmConfig,
        )
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT).build()
        activePlayer.setMediaSource(mediaSource)
        if (startPositionMs > 0L) {
            activePlayer.seekTo(startPositionMs)
            resumedHint = true
        }
        activePlayer.setPlaybackSpeed(speed)
        activePlayer.prepare()
        activePlayer.playWhenReady = true
    }

    // 播完自动连播：5 秒倒计时，期间按「确认」立即切，按 Esc 取消
    LaunchedEffect(ended, countdown) {
        if (!ended || nextEpisodeName == null) return@LaunchedEffect
        if (countdown <= 0) return@LaunchedEffect
        delay(1_000)
        countdown -= 1
        if (countdown == 0) onPlayNext()
    }

    // 进度采样：500ms 一次，HUD 的进度条才有实时感（5 秒太木）
    LaunchedEffect(player) {
        while (true) {
            delay(500)
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            durationMs = runCatching { player.duration }.getOrDefault(0L).let { if (it > 0L) it else 0L }
            bufferedMs = runCatching { player.bufferedPosition }.getOrDefault(0L)
        }
    }

    LaunchedEffect(player, requestedAtNanos, introMs, creditsMs, nextEpisodeName) {
        while (true) {
            delay(300)
            val duration = player.duration
            if (!player.isPlaying || duration <= 0L) continue
            val position = player.currentPosition
            if (!introHandled) {
                introHandled = true
                if (startPositionMs == 0L && position < introMs && introMs < duration / 3) {
                    player.seekTo(introMs)
                    flash("已跳过片头")
                }
            }
            if (!creditsHandled && creditsMs in 1 until (duration / 3) &&
                position >= duration - creditsMs && nextEpisodeName != null) {
                creditsHandled = true
                player.seekTo(duration)
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) report()
        }
    }

    // HUD 自动隐藏：播放中 4 秒无操作就收起来，暂停/出错时一直留着
    LaunchedEffect(hudVisible, hudSignal, player.isPlaying) {
        if (!hudVisible) return@LaunchedEffect
        delay(4_000)
        if (player.isPlaying && playbackError == null && !ended && !controlsVisible) hudVisible = false
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        flash(if (player.isPlaying) "已继续播放" else "已暂停")
    }

    // 连按快进/快退时把多次位移合并成一次 seek：HLS 每个目标位置都要新拉分片，
    // 20 次连跳会把源站当成盗链直接 403，合并后只发一次请求。
    var pendingSeek by remember { mutableIntStateOf(0) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    fun seekBy(deltaMs: Long) {
        pendingSeek += deltaMs.toInt()
        flash(if (pendingSeek >= 0) "快进 ${pendingSeek / 1000}s" else "快退 ${-pendingSeek / 1000}s")
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(320)
            val all = pendingSeek
            pendingSeek = 0
            if (all == 0) return@launch
            val duration = runCatching { player.duration }.getOrDefault(0L)
            val target = (player.currentPosition + all).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
            runCatching { player.seekTo(target) }
            flash("跳到 ${fmt(target)}")
        }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = controlsVisible) { controlsVisible = false; rootFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // 键盘调试：空格/回车暂停，←→ 快退快进；Esc 由 Activity 统一处理
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (pickerVisible) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            pickerIndex = (pickerIndex - 1).coerceAtLeast(0); true
                        }
                        Key.DirectionDown -> {
                            pickerIndex = (pickerIndex + 1).coerceAtMost(slots.lastIndex.coerceAtLeast(0)); true
                        }
                        Key.Enter, Key.DirectionCenter -> {
                            pickerVisible = false
                            if (pickerIndex != currentSlot) onSwitchSlot(pickerIndex)
                            true
                        }
                        Key.Back -> { pickerVisible = false; true }
                        else -> true
                    }
                }
                hudVisible = true
                hudSignal++
                when (event.key) {
                    Key.Spacebar, Key.Enter, Key.DirectionCenter, Key.P -> {
                        if (controlsVisible && event.key != Key.Spacebar && event.key != Key.P) false
                        else if (playbackError != null && slots.size > 1) {
                            pickerIndex = (currentSlot + 1) % slots.size
                            pickerVisible = true
                            true
                        } else { togglePlay(); true }
                    }
                    Key.DirectionLeft -> {
                        if (controlsVisible) false else { seekBy(if (event.nativeKeyEvent.repeatCount >= 5) -30_000L else -10_000L); true }
                    }
                    Key.DirectionRight -> {
                        if (controlsVisible) false else { seekBy(if (event.nativeKeyEvent.repeatCount >= 5) 30_000L else 10_000L); true }
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (controlsVisible) false else {
                            controlsVisible = true
                            true
                        }
                    }
                    Key.Menu, Key.L -> {
                        if (slots.size > 1) {
                            pickerIndex = currentSlot.coerceIn(0, slots.lastIndex)
                            pickerVisible = true
                            true
                        } else false
                    }
                    Key.S -> {
                        val steps = listOf(1f, 1.25f, 1.5f, 2f)
                        val idx = steps.indexOfFirst { it == speed }
                        speed = steps[(idx + 1) % steps.size]
                        runCatching { player.setPlaybackSpeed(speed) }
                        playerPrefs.edit().putFloat("speed", speed).apply()
                        flash("倍速 ${speed}×")
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                (if (session != null) session.view(ctx)
                else PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }).also { playerView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (pickerVisible) {
            LinePicker(
                slots = slots,
                current = currentSlot,
                cursor = pickerIndex,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 40.dp)
            )
        }

        if (controlsVisible) {
            PlayerSheet(
                player = player,
                speed = speed,
                onSpeed = { v ->
                    speed = v
                    playerPrefs.edit().putFloat("speed", v).apply()
                    runCatching { player.setPlaybackSpeed(v) }
                },
                resizeMode = resizeMode,
                onResizeMode = { mode ->
                    resizeMode = mode
                    playerPrefs.edit().putInt("resize_mode", mode).apply()
                    playerView?.resizeMode = mode
                },
                slots = slots,
                currentSlot = currentSlot,
                onSwitchSlot = onSwitchSlot,
                introMs = introMs,
                creditsMs = creditsMs,
                onMarkSkip = if (seriesKey.isBlank()) null else { intro ->
                    val mark = if (intro) player.currentPosition else player.duration - player.currentPosition
                    val key = if (intro) "intro:$seriesKey" else "credits:$seriesKey"
                    val previous = if (intro) introMs else creditsMs
                    if (previous != 0L || mark in 1 until (player.duration / 3)) {
                        val value = if (previous != 0L) 0L else mark
                        if (intro) introMs = value else creditsMs = value
                        playerPrefs.edit().putLong(key, value).apply()
                    } else flash(if (intro) "请在片头结束的位置设置" else "请在片尾开始的位置设置")
                },
                sleepEnabled = sleepDeadline != 0L,
                onSetSleep = { minutes ->
                    sleepDeadline = if (minutes == 0) 0 else android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                },
                playing = player.isPlaying,
                onToggle = { togglePlay() },
                onSeekBy = { seekBy(it) },
                onFullEpisodes = onBack,
                onClose = { controlsVisible = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // —— HUD ——
        val duration = durationMs
        val playedFraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val bufferedFraction = if (duration > 0) (bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

        androidx.compose.animation.AnimatedVisibility(
            visible = hudVisible && !controlsVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部渐变条：返回 + 片名 + 状态胶囊
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0xF00B0C10),
                                0.55f to Color(0x660B0C10),
                                1f to Color.Transparent
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 26.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "NEXT MOVIE",
                                color = Tokens.Text,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 3.sp
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "PLAY",
                                color = Tokens.Accent,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = Tokens.Type.micro,
                                    letterSpacing = Tokens.Type.MICRO_TRACK.sp
                                ),
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(22.dp))
                        Text(
                            text = "‹ 返回",
                            color = Tokens.TextDim,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Tokens.Corner.pill))
                                .background(Color(0x14F3F0E9))
                                .border(1.dp, Color(0x26F3F0E9), RoundedCornerShape(Tokens.Corner.pill))
                                .tvClickable(onClick = onBack, corner = Tokens.Corner.pill)
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                        Spacer(Modifier.width(22.dp))
                        Column {
                            Text(
                                text = title.split(" · ").let { parts ->
                                    if (parts.size > 1 && parts.distinct().size == 1) parts[0] else title
                                },
                                color = Tokens.Text,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = "确认键暂停 · 左右快进退 · 上下打开控制栏",
                                color = Color(0x8FFFFFFF),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (slots.size > 1) StatusPill("线路 ${currentSlot + 1}/${slots.size} · 菜单键切换", Tokens.Text)
                            trackBadges.forEach { StatusPill(it, Tokens.Text) }
                            if (speed > 1f) StatusPill("${speed}×", Tokens.Accent)
                            if (resumedHint) StatusPill("已续播", Tokens.Text)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 中部浮层：错误 / 连播倒计时 / 操作提示
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        playbackError != null -> FloatCard(accent = true) {
                            Text(
                                text = "播放失败",
                                color = Tokens.Text,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = playbackError!!,
                                color = Tokens.TextDim,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                text = if (slots.size > 1) "确认键选择其他线路 · 返回键回详情"
                                    else "返回详情后换一条线路或来源",
                                color = Tokens.Accent,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                        buffering -> FloatCard(accent = false) { Text("正在缓冲…", color = Tokens.Text) }
                        ended && nextEpisodeName != null && countdown > 0 -> FloatCard(accent = true) {
                            Text(
                                text = "即将播放 $nextEpisodeName",
                                color = Tokens.Text,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${countdown}s 后自动连播 · 按确认键立即播放 · Esc 取消",
                                color = Tokens.TextDim,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        keyHint != null && !player.isPlaying && !controlsVisible -> FloatCard(accent = false) {
                            Text(
                                text = keyHint!!,
                                color = Tokens.Text,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 底部进度区：进度条 + 缓冲条 + 时间戳
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xE6000000)
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 30.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0x24F3F0E9))
                        ) {
                            if (bufferedFraction > playedFraction) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(bufferedFraction)
                                        .fillMaxHeight()
                                        .background(Color(0x59F3F0E9))
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(playedFraction)
                                    .fillMaxHeight()
                                    .background(Tokens.Accent)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = fmt(positionMs),
                                color = Tokens.Text,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = " / ${if (duration > 0) fmt(duration) else "--:--"}",
                                color = Color(0x66F3F0E9),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (duration > 0) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "−" + fmt((duration - positionMs).coerceAtLeast(0L)),
                                    color = Tokens.TextFaint,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (nextEpisodeName != null) {
                                Text(
                                    text = "下一集：$nextEpisodeName",
                                    color = Color(0x8FFFFFFF),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                            Text(
                                text = if (player.isPlaying) "▶ 播放中" else "❚❚ 已暂停",
                                color = if (player.isPlaying) Tokens.Accent else Tokens.TextDim,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 线路面板：按聚合排序展示，标出当前线路与画质标签，方向键游标 + OK 切换。
 * 用纯文本行而不是可聚焦按钮，避免与播放器根节点的按键处理抢焦点。
 */
@Composable
private fun LinePicker(
    slots: List<com.nextmovie.core.model.PlaySlot>,
    current: Int,
    cursor: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(360.dp)
            .clip(RoundedCornerShape(Tokens.Corner.panel))
            .background(Color(0xF20B0C10))
            .border(1.dp, Tokens.Accent.copy(alpha = 0.45f), RoundedCornerShape(Tokens.Corner.panel))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "线路 · ${slots.size} 个来源聚合",
            color = Tokens.Accent,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "↑↓ 选择 · OK 切换 · 返回关闭",
            color = Color(0x8FFFFFFF),
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(12.dp))
        slots.forEachIndexed { index, slot ->
            val focused = index == cursor
            val active = index == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Tokens.Radius))
                    .background(if (focused) Tokens.AccentDim else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = if (active) "●" else "○",
                    color = if (active) Tokens.Accent else Color(0x66F3F0E9),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${slot.siteName} · ${slot.lineName}",
                        color = if (focused || active) Tokens.Text else Tokens.TextDim,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${slot.badge} · 分 ${slot.score}" + if (index == 0) " · 最优" else "",
                        color = Tokens.TextFaint,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x1AF3F0E9))
            .border(1.dp, Color(0x24F3F0E9), RoundedCornerShape(Tokens.Corner.pill))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun FloatCard(accent: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Corner.panel))
            .background(Color(0xF2111319))
            .border(
                1.dp,
                if (accent) Tokens.Accent.copy(alpha = 0.45f) else Tokens.Stroke,
                RoundedCornerShape(Tokens.Corner.panel)
            )
            .padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
        content()
    }
}

/** 毫秒 → mm:ss / h:mm:ss */
private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 嗅探前 2KB 判断是不是 HLS（`#EXTM3U` 开头）。
 * 很多源站的播放地址不带扩展名，光看后缀会把 m3u8 当成普通文件。
 */
private suspend fun sniffHls(url: String, headers: Map<String, String>): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", Http.UA).header("Range", "bytes=0-2047").apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
            Http.defaultClient().newBuilder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                val head = response.body?.source()?.let { source ->
                    source.request(512)
                    source.buffer.clone().readUtf8(minOf(512L, source.buffer.size))
                }.orEmpty()
                head.trimStart().startsWith("#EXTM3U")
            }

        }.getOrDefault(false)
    }

/**
 * 播控与音画设置（对齐 Marco tv_player_overlay.dart 的底部玻璃 sheet）
 *
 * 4 行：播放倍速 / 画面比例 / 播放线路 / 传输控制；抬头带线路与倍速角标，
 * 右侧「选集 / 关闭」。线路行按聚合排序原样展示，标注源站与 HLS，可手动切换。
 */
@Composable
private fun PlayerSheet(
    player: androidx.media3.exoplayer.ExoPlayer,
    speed: Float,
    onSpeed: (Float) -> Unit,
    resizeMode: Int,
    onResizeMode: (Int) -> Unit,
    slots: List<com.nextmovie.core.model.PlaySlot>,
    currentSlot: Int,
    onSwitchSlot: (Int) -> Unit,
    introMs: Long,
    creditsMs: Long,
    onMarkSkip: ((Boolean) -> Unit)?,
    sleepEnabled: Boolean,
    onSetSleep: (Int) -> Unit,
    playing: Boolean,
    onToggle: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onFullEpisodes: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val ratios = listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应屏幕",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁切填满",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸铺满"
    )
    val position = runCatching { player.currentPosition }.getOrDefault(0L)
    val duration = runCatching { player.duration }.getOrDefault(0L).takeIf { it > 0 } ?: 0L

    Column(
        modifier = modifier
            .padding(start = 32.dp, end = 32.dp, bottom = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Corner.sheet))
            .background(Color(0xF2141720))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(Tokens.Corner.sheet))
            .padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播控与音画设置", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            if (slots.size > 1) com.nextmovie.ui.common.MetaChip("线路 ${currentSlot + 1}/${slots.size}")
            Spacer(Modifier.width(8.dp))
            com.nextmovie.ui.common.MetaChip("${speed}×")
            Spacer(Modifier.weight(1f))
            com.nextmovie.ui.common.TvPill(
                text = "选集",
                glass = true,
                horizontalPadding = 16.dp,
                verticalPadding = 9.dp,
                onClick = onFullEpisodes
            )
            Spacer(Modifier.width(8.dp))
            com.nextmovie.ui.common.TvPill(
                text = "关闭",
                glass = true,
                horizontalPadding = 16.dp,
                verticalPadding = 9.dp,
                onClick = onClose
            )
        }
        Spacer(Modifier.height(10.dp))

        // 传输控制
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.nextmovie.ui.common.TvPill(
                text = if (playing) "⏸ 暂停" else "▶ 继续",
                prominent = true,
                onClick = onToggle
            )
            Spacer(Modifier.width(8.dp))
            com.nextmovie.ui.common.TvPill(text = "−10s", glass = true, onClick = { onSeekBy(-10_000L) })
            Spacer(Modifier.width(8.dp))
            com.nextmovie.ui.common.TvPill(text = "+10s", glass = true, onClick = { onSeekBy(10_000L) })
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(999.dp))
            ) {
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(5.dp)
                        .background(Tokens.Accent, RoundedCornerShape(999.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                fmt(position) + " / " + fmt(duration),
                color = Tokens.TextDim,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(10.dp))

        SheetRow("播放倍速") {
            speeds.forEach { v ->
                com.nextmovie.ui.common.TvPill(
                    text = if (v == 1f) "1.0×" else "${v}×",
                    selected = speed == v,
                    glass = speed != v,
                    horizontalPadding = 14.dp,
                    verticalPadding = 7.dp,
                    onClick = { onSpeed(v) }
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        SheetRow("画面比例") {
            ratios.forEach { (mode, label) ->
                com.nextmovie.ui.common.TvPill(
                    text = label,
                    selected = resizeMode == mode,
                    glass = resizeMode != mode,
                    horizontalPadding = 14.dp,
                    verticalPadding = 7.dp,
                    onClick = { onResizeMode(mode) }
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetRow("轨道 / 定时") {
            TrackPicker(player, androidx.media3.common.C.TRACK_TYPE_AUDIO, "音轨")
            Spacer(Modifier.width(8.dp))
            TrackPicker(player, androidx.media3.common.C.TRACK_TYPE_TEXT, "字幕")
            Spacer(Modifier.width(8.dp))
            com.nextmovie.ui.common.TvPill(
                text = if (sleepEnabled) "取消定时" else "30 分钟后暂停",
                glass = true,
                onClick = { onSetSleep(if (sleepEnabled) 0 else 30) }
            )
        }
        if (onMarkSkip != null) {
            Spacer(Modifier.height(12.dp))
            SheetRow("片头片尾") {
                com.nextmovie.ui.common.TvPill(
                    if (introMs > 0) "清除片头 ${fmt(introMs)}" else "标记片头结束",
                    glass = true, onClick = { onMarkSkip(true) }
                )
                Spacer(Modifier.width(8.dp))
                com.nextmovie.ui.common.TvPill(
                    if (creditsMs > 0) "清除片尾 ${fmt(creditsMs)}" else "标记片尾开始",
                    glass = true, onClick = { onMarkSkip(false) }
                )
            }
        }
        if (slots.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SheetRow("播放线路") {
                slots.take(4).forEachIndexed { index, slot ->
                    com.nextmovie.ui.common.TvPill(
                        text = slot.siteName + (if (slot.badge.isNotBlank()) " · ${slot.badge}" else ""),
                        selected = index == currentSlot,
                        glass = index != currentSlot,
                        horizontalPadding = 13.dp,
                        verticalPadding = 7.dp,
                        onClick = { onSwitchSlot(index) }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TrackPicker(player: ExoPlayer, type: Int, title: String) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: androidx.media3.common.Tracks) { tracks = value }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val options = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
    }
    if (options.isEmpty()) return
    val selected = options.indexOfFirst { (group, index) -> group.isTrackSelected(index) }
    val format = options.getOrNull(selected)?.let { (group, index) -> group.getTrackFormat(index) }
    val label = format?.label ?: format?.language ?: if (selected >= 0) "${selected + 1}" else "关闭"
    com.nextmovie.ui.common.TvPill(text = "$title · $label", glass = true, onClick = {
        val next = selected + 1
        val disable = type == androidx.media3.common.C.TRACK_TYPE_TEXT && next == options.size
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            .setTrackTypeDisabled(type, disable)
        if (!disable) {
            val (group, index) = options[next % options.size]
            builder.setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
        }
        player.trackSelectionParameters = builder.build()
    })
}

@Composable
private fun SheetRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tokens.TextDim, fontSize = 14.sp, modifier = Modifier.width(88.dp))
        content()
    }
}
