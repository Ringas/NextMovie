package com.nextmovie.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.Billboard
import com.nextmovie.ui.common.BillboardButton
import com.nextmovie.ui.common.ChipRow
import com.nextmovie.ui.common.ContinueCard
import com.nextmovie.ui.common.GlassTopBar
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.Rail
import com.nextmovie.ui.common.StageBackdrop
import com.nextmovie.ui.common.TabSpec
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.DynamicAccent
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 首页（tvOS 原型版）
 *
 * 自上而下：居中玻璃 tab + 时钟 → 圆角巨幕（带翻页指示点）→ 分类胶囊 → 横排轨道。
 * 整屏背景是石墨夜色 + 一团随焦点作品漂移的主色氛围光，不再铺满剧照，
 * 于是首屏不再依赖一张大图下载完成才能"看起来对"。
 */
@Composable
fun HomeScreen(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Vod) -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit = {},
    onFavorites: () -> Unit = {},
    onHistory: () -> Unit = {},
    onResume: (HistoryEntry) -> Unit = {},
    initialFocusIndex: Int = 0,
    initialTab: Int = 0,
    reloadSignal: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    onFocusIndexChanged: (Int) -> Unit = {}
) {
    val screenStartedAt = remember { System.nanoTime() }
    var vods by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var enrichedById by remember { mutableStateOf<Map<String, Vod>>(emptyMap()) }
    var heroMeta by remember { mutableStateOf<Vod?>(null) }
    var continueList by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    val requested = remember { mutableSetOf<String>() }
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var warming by remember { mutableStateOf(false) }
    var warmTries by remember { mutableIntStateOf(0) }
    var warmSince by remember { mutableLongStateOf(0L) }
    var loadSecs by remember { mutableIntStateOf(0) }
    var loadStartedAt by remember { mutableLongStateOf(0L) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var cats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var tab by remember { mutableIntStateOf(initialTab) }
    var page by remember { mutableIntStateOf(0) }
    val posterFocus = remember(vods.map { it.id }) { List(vods.size) { FocusRequester() } }
    val railState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val initialReloadSignal = remember(site.runtimeKey) { reloadSignal }
    val initialSnapshot = remember(site.runtimeKey) { kotlinx.coroutines.CompletableDeferred<List<Vod>?>() }
    LaunchedEffect(site.runtimeKey) { initialSnapshot.complete(repo.cachedHome(site)) }
    val backgroundReady = remember(site.runtimeKey) { kotlinx.coroutines.CompletableDeferred<Unit>() }
    LaunchedEffect(site.runtimeKey) {
        repo.connectPluginHost(site)
        if (initialSnapshot.await() != null) delay(2_500)
        backgroundReady.complete(Unit)
    }
    LaunchedEffect(site.runtimeKey) {
        backgroundReady.await()
        cats = try { repo.categories(site) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
        if (tab > cats.size) tab = 0
    }
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        loadStartedAt = System.currentTimeMillis()
        loadSecs = 0
        while (loading) {
            delay(1_000)
            loadSecs += 1
        }
    }
    LaunchedEffect(site.runtimeKey, tab, reloadTick, reloadSignal) {
        loading = true
        error = null
        if (tab == 0 && reloadTick == 0 && reloadSignal == initialReloadSignal) {
            initialSnapshot.await()?.let { saved ->
                vods = repo.prepareForList(saved)
                loading = false
                android.util.Log.i("NextMovieTiming", "home-snapshot-ready " + (System.nanoTime() - screenStartedAt) / 1_000_000 + "ms")
                backgroundReady.await()
            }
        }
        continueList = repo.history.continueWatching(10).filter { it.siteKey == site.key }
        runCatching {
            com.nextmovie.core.net.Retry.once {
                if (tab == 0) repo.home(site, refresh = reloadTick > 0)
                else repo.category(site, cats.getOrNull(tab - 1)?.first.orEmpty(), 1)
            }
        }
            .onSuccess { list ->
                kotlin.coroutines.coroutineContext.ensureActive()
                warming = false
                warmTries = 0
                warmSince = 0L
                vods = repo.prepareForList(list)
                enrichedById = emptyMap()
                requested.clear()
                heroMeta = null
                page = 0
            }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = com.nextmovie.core.net.FriendlyError.describe(e)
                if (vods.isEmpty()) {
                    if (msg.contains("首次读取超时") || msg.contains("首次初始化")) {
                        if (!warming) warmSince = System.currentTimeMillis()
                        warming = true
                        error = null
                    } else {
                        error = msg
                    }
                }
            }
        loading = false
    }
    LaunchedEffect(warming, warmTries) {
        if (!warming || warmTries >= 10) return@LaunchedEffect
        delay(5_000)
        if (vods.isEmpty()) {
            warmTries += 1
            reloadTick += 1
        }
    }
    LaunchedEffect(site.runtimeKey, tab, vods.map { it.id }) {
        if (vods.isEmpty()) return@LaunchedEffect
        val target = initialFocusIndex.coerceIn(0, vods.lastIndex)
        if (target > 0) {
            runCatching { railState.scrollToItem(target) }
            delay(80)
            runCatching { posterFocus.getOrNull(target)?.requestFocus() }
        }
    }
    LaunchedEffect(site.key, vods.size) {
        if (vods.isEmpty()) return@LaunchedEffect
        val targets = vods.filter { it.id !in requested }.take(4)
        if (targets.isEmpty()) return@LaunchedEffect
        requested += targets.map { it.id }
        val sem = Semaphore(2)
        val resolved = coroutineScope {
            targets.map { vod -> async { sem.withPermit { repo.enrichPoster(vod, detail = false) } } }.awaitAll()
        }
        enrichedById = enrichedById + resolved.associateBy { it.id }
    }

    val display = remember(vods, enrichedById) { vods.map { enrichedById[it.id] ?: it } }
    val billboardItems = remember(display) { display.take(5) }
    val heroBase = billboardItems.getOrNull(page.coerceIn(0, (billboardItems.size - 1).coerceAtLeast(0)))
    val hero = heroMeta?.takeIf { it.id == heroBase?.id } ?: heroBase
    LaunchedEffect(site.runtimeKey, heroBase?.id, cats) {
        val focused = heroBase ?: return@LaunchedEffect
        delay(700)
        repo.prefetchDetail(site, focused)
        if (focused.displayOverview.isBlank() || focused.displayYear.isBlank()) {
            val detail = runCatching { repo.detail(site, focused.id, focused.name) }.getOrNull()
            val vod = detail?.vod
            if (vod != null && vod.id == focused.id) heroMeta = vod
        }
    }
    LaunchedEffect(display.isNotEmpty()) {
        if (display.isNotEmpty()) android.util.Log.i("NextMovieTiming", "home-ready " + (System.nanoTime() - screenStartedAt) / 1_000_000 + "ms")
        billboardItems.firstOrNull()?.let { DynamicAccent.fromSeed(it.id) }
    }

    StageBackdrop(modifier = Modifier.onPreviewKeyEvent { event ->
        // 遥控器「菜单」键直达设置：老版本一直有这个快捷键，改版时不能丢
        if (event.type == KeyEventType.KeyDown &&
            (event.key == androidx.compose.ui.input.key.Key.Menu || event.key == androidx.compose.ui.input.key.Key.F1)
        ) { onOpenSettings(); true } else false
    }) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
            GlassTopBar(
                tabs = listOf(
                    TabSpec("首页", true) {},
                    TabSpec("收藏", false) { onFavorites() },
                    TabSpec("历史", false) { onHistory() },
                    TabSpec("搜索", false) { onSearch() },
                    TabSpec("设置", false) { onOpenSettings() }
                ),
                status = repo.configStatus()?.text,
                statusOk = repo.configStatus()?.ok ?: true,
                onStatusClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(6.dp))

            when {
                hero != null && !loading -> {
                    Billboard(
                        vod = hero,
                        page = page.coerceIn(0, (billboardItems.size - 1).coerceAtLeast(0)),
                        pageCount = billboardItems.size,
                        onOpen = { onOpenDetail(hero) },
                        onPlay = { onOpenDetail(hero) },
                        onFocused = { heroMeta = heroMeta },
                        onPageStep = { step ->
                            page = (page + step).coerceIn(0, billboardItems.lastIndex.coerceAtLeast(0))
                        },
                        modifier = Modifier.padding(horizontal = Tokens.ScreenPadding)
                    )
                }
                else -> BillboxPlaceholder(
                    loading = loading,
                    error = error,
                    warming = warming,
                    since = if (warmSince > 0) warmSince else loadStartedAt,
                    tries = warmTries,
                    siteReason = site.unavailableReason,
                    onRetry = { reloadTick++ },
                    onOpenSettings = onOpenSettings
                )
            }

            Spacer(Modifier.height(14.dp))

            // 分类行固定占位：插件源的分类接口比首页慢，晚到时不至于把下面的海报行顶出屏幕
            Box(modifier = Modifier.fillMaxWidth().height(if (cats.isEmpty()) 0.dp else 34.dp)) {
                if (cats.isNotEmpty()) {
                    ChipRow(
                        labels = listOf("最近更新") + cats.map { it.second },
                        selected = tab,
                        onSelect = { tab = it; onTabChanged(it) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (hero != null && display.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (tab == 0 && continueList.isNotEmpty()) {
                        item(key = "continue") {
                            Rail(
                                title = "继续观看",
                                subtitle = "接着上次的位置继续 · ${continueList.size} 部",
                                items = continueList,
                                key = { it.key },
                                card = { entry -> ContinueCard(entry, { onResume(entry) }) }
                            )
                        }
                    }
                    item(key = "rail") {
                        Rail(
                            title = if (tab == 0) "最近更新" else cats.getOrNull(tab - 1)?.second.orEmpty(),
                            subtitle = "共 ${display.size} 部 · ${site.name}",
                            items = display,
                            key = { it.id + it.name },
                            state = railState,
                            card = { vod ->
                                val index = display.indexOf(vod)
                                PosterCard(
                                    vod = vod,
                                    onClick = { onOpenDetail(vod) },
                                    onFocused = { onFocusIndexChanged(index) },
                                    modifier = Modifier
                                        .focusRequester(posterFocus.getOrElse(index) { FocusRequester() })
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 巨幕位（加载/错误/预热共用）：保持同一块圆角玻璃面板，避免首屏结构在加载前后跳变。 */
@Composable
private fun BillboxPlaceholder(
    loading: Boolean,
    error: String?,
    warming: Boolean,
    since: Long,
    tries: Int,
    siteReason: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val shape = RoundedCornerShape(Tokens.Corner.hero)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secs = if (since > 0) ((now - since) / 1000).coerceAtLeast(0) else 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(186.dp)
            .padding(horizontal = Tokens.ScreenPadding)
            .clip(shape)
            .background(Color(0xFF101116))
            .border(1.dp, Tokens.Stroke, shape)
    ) {
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            0.35f, 0.75f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulseAlpha"
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to Tokens.AccentDim.copy(alpha = 0.5f),
                    1f to Color.Transparent,
                    center = androidx.compose.ui.geometry.Offset(240f, 60f),
                    radius = 900f
                )
            )
        )
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp, end = 300.dp),
            horizontalAlignment = Alignment.Start
        ) {
            when {
                siteReason != null -> {
                    Text("站点不可用", color = Tokens.Text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(8.dp))
                    Text(siteReason, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    Spacer(Modifier.height(20.dp))
                    Row {
                        BillboardButton("打开设置", primary = true, onClick = onOpenSettings)
                        Spacer(Modifier.width(12.dp))
                        BillboardButton("重试", primary = false, onClick = onRetry)
                    }
                }
                error != null -> {
                    Text("来源加载失败", color = Tokens.Text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    Row {
                        BillboardButton("重试", primary = true, onClick = onRetry)
                        Spacer(Modifier.width(12.dp))
                        BillboardButton("打开设置", primary = false, onClick = onOpenSettings)
                    }
                }
                warming || (loading && secs >= 6) -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Tokens.Accent.copy(alpha = pulse))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "正在准备这个源",
                            color = Tokens.Text,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "插件源首次读取要起本地代理并拉取配置，约 10–30 秒；准备好后会自动刷新" +
                            if (tries > 0) "（已重试 $tries 次）" else "",
                        color = Tokens.TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已等待 ${secs}s",
                            color = Tokens.Accent,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.width(16.dp))
                        if (secs >= 20) BillboardButton("立即重试", primary = false, onClick = onRetry)
                    }
                }
                else -> {
                    SkeletonTitle(pulse)
                }
            }
        }
    }
}

@Composable
private fun SkeletonTitle(alpha: Float) {
    Column {
        Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF).copy(alpha = alpha * 0.5f)))
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(420.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF).copy(alpha = alpha * 0.6f)))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(260.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x26FFFFFF).copy(alpha = alpha * 0.5f)))
    }
}
