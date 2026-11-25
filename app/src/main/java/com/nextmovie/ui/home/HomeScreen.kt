@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.AmbientBackdrop
import com.nextmovie.ui.common.BrandLogo
import com.nextmovie.ui.common.ContinueCard
import com.nextmovie.ui.common.FloatingTopBar
import com.nextmovie.ui.common.NavTab
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.PosterTint
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.settings.PairingDialog
import com.nextmovie.ui.theme.Tokens
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 首页（对齐 Marco TvLivingRoomShell）
 *
 * 结构：浮动玻璃胶囊导航 → 分类胶囊行 + 切换片源/扫码快传 → 继续观看横排 → 精选片库海报墙。
 * 海报墙是 4/5/6 列的网格（宽屏更多列），和 Marco 的 SliverGrid 一致，
 * 不再是一排排横向轨道 —— 后者在 40 部片时要横向翻十几屏，电视上很难用。
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
    onFocusIndexChanged: (Int) -> Unit = {},
    onSiteChanged: () -> Unit = {}
) {
    val screenStartedAt = remember { System.nanoTime() }
    var vods by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var enrichedById by remember { mutableStateOf<Map<String, Vod>>(emptyMap()) }
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
    var sitePicker by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var posterTint by remember { mutableStateOf<Color?>(null) }
    val gridState = rememberLazyGridState()
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
                warming = false
                warmTries = 0
                warmSince = 0L
                vods = repo.prepareForList(list)
                enrichedById = emptyMap()
                requested.clear()
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
    LaunchedEffect(vods.map { it.id }) {
        if (vods.isEmpty()) return@LaunchedEffect
        val target = initialFocusIndex.coerceIn(0, vods.lastIndex)
        if (target > 0) {
            delay(80)
            runCatching { gridState.scrollToItem(target) }
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
    LaunchedEffect(display.size) {
        if (display.isNotEmpty()) android.util.Log.i("NextMovieTiming", "home-ready " + (System.nanoTime() - screenStartedAt) / 1_000_000 + "ms")
    }

    if (pairing) {
        PairingDialog(
            onDismiss = { pairing = false },
            onReceived = {
                pairing = false
                onOpenSettings()
            }
        )
    }
    if (sitePicker) {
        SitePickerDialog(
            sites = repo.sites,
            currentKey = site.key,
            onPick = { picked ->
                sitePicker = false
                repo.selectSite(picked.key)
                onTabChanged(0)
                onSiteChanged()
            },
            onDismiss = { sitePicker = false }
        )
    }

    val continueVisible = tab == 0 && continueList.isNotEmpty()
    val columns = when {
        LocalConfiguration.current.screenWidthDp >= 1500 -> 6
        LocalConfiguration.current.screenWidthDp >= 1100 -> 5
        else -> 4
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(posterTint = posterTint)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    // 遥控器「菜单」键直达设置，老快捷键不能丢
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Menu || event.key == Key.F1)
                    ) { onOpenSettings(); true } else false
                }
        ) {
            FloatingTopBar(
                tabs = listOf(
                    NavTab(Icons.Filled.Home, "发现", true) {},
                    NavTab(Icons.Filled.Search, "搜索", false) { onSearch() },
                    NavTab(Icons.Filled.List, "片库", false) { onFavorites() },
                    NavTab(Icons.Filled.Settings, "设置", false) { onOpenSettings() }
                ),
                status = repo.configStatus()?.text ?: site.name,
                modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 28.dp, bottom = 18.dp)
            )

            // 子工具条：分类胶囊（左）+ 切换片源 / 扫码快传（右）
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val catState = rememberLazyListState()
                LazyRow(
                    state = catState,
                    modifier = Modifier.weight(1f).mouseWheel(catState, horizontal = true),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        TvPill(
                            text = "精选推荐",
                            selected = tab == 0,
                            horizontalPadding = 22.dp,
                            verticalPadding = 11.dp,
                            onClick = { tab = 0; onTabChanged(0) }
                        )
                    }
                    itemsIndexed(cats) { index, cat ->
                        TvPill(
                            text = cat.second,
                            selected = tab == index + 1,
                            horizontalPadding = 22.dp,
                            verticalPadding = 11.dp,
                            onClick = { tab = index + 1; onTabChanged(index + 1) }
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                TvPill(
                    glass = true,
                    horizontalPadding = 20.dp,
                    verticalPadding = 11.dp,
                    onClick = { sitePicker = true }
                ) {
                    Text("切换片源", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                TvPill(
                    glass = true,
                    horizontalPadding = 20.dp,
                    verticalPadding = 11.dp,
                    onClick = { pairing = true }
                ) {
                    Text("扫码快传", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (continueVisible) {
                Column(modifier = Modifier.padding(start = 56.dp, end = 56.dp)) {
                    Text("继续观看", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    val rowState = rememberLazyListState()
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.height(202.dp).mouseWheel(rowState, horizontal = true)
                    ) {
                        items(continueList, key = { it.key }) { entry ->
                            ContinueCard(entry = entry, onClick = { onResume(entry) })
                        }
                    }
                }
                Spacer(Modifier.height(26.dp))
                Text(
                    "精选片库",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 56.dp, end = 56.dp, bottom = 16.dp)
                )
            }

            when {
                loading && display.isEmpty() -> LoadingStage(
                    secs = loadSecs,
                    warming = warming,
                    tries = warmTries,
                    since = if (warmSince > 0) warmSince else loadStartedAt
                )
                error != null && display.isEmpty() -> ErrorStage(
                    message = error!!,
                    onRetry = { reloadTick++ },
                    onOpenSettings = onOpenSettings
                )
                display.isEmpty() -> EmptyStage(siteReason = site.unavailableReason, onOpenSettings = onOpenSettings)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 56.dp, end = 56.dp, top = 4.dp, bottom = 44.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.fillMaxSize().mouseWheel(gridState)
                ) {
                    itemsIndexed(display, key = { _, vod -> vod.id + vod.name }) { index, vod ->
                        PosterCard(
                            vod = vod,
                            onClick = { onOpenDetail(vod) },
                            onFocused = {
                                onFocusIndexChanged(index)
                                val tint = PosterTint.cached(vod.displayPic)
                                if (tint != null) posterTint = tint
                                scope.launch {
                                    PosterTint.resolve(context, vod.displayPic)?.let { posterTint = it }
                                }
                            },
                            modifier = Modifier.focusRequester(remember(vod.id) { FocusRequester() }),
                            fillWidth = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingStage(secs: Int, warming: Boolean, tries: Int, since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = if (since > 0) ((now - since) / 1000).coerceAtLeast(0) else secs.toLong()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 0.75f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulseAlpha"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (warming || elapsed >= 6) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Tokens.Accent.copy(alpha = pulse)))
                    Spacer(Modifier.width(10.dp))
                    Text("正在准备这个源", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "插件源首次读取要起本地代理并拉取配置，约 10–30 秒；准备好后会自动刷新" +
                        if (tries > 0) "（已重试 $tries 次）" else "",
                    color = Tokens.TextDim,
                    fontSize = 15.sp,
                    modifier = Modifier.width(620.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text("已等待 ${elapsed}s", color = Tokens.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            } else {
                CircularProgressIndicator(color = Tokens.Accent, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(16.dp))
                Text("正在读取片库…", color = Tokens.TextDim, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ErrorStage(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载内容失败", color = Color(0xFFFFB86B), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Tokens.TextDim, fontSize = 15.sp, modifier = Modifier.width(620.dp))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvPill(text = "重试加载", prominent = true, horizontalPadding = 22.dp, verticalPadding = 12.dp, onClick = onRetry)
                TvPill(text = "打开设置", horizontalPadding = 22.dp, verticalPadding = 12.dp, onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun EmptyStage(siteReason: String?, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (siteReason != null) "站点不可用" else "这个分类下还没有内容",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            if (siteReason != null) {
                Spacer(Modifier.height(10.dp))
                Text(siteReason, color = Tokens.TextDim, fontSize = 15.sp, modifier = Modifier.width(620.dp))
            }
            Spacer(Modifier.height(20.dp))
            TvPill(text = "打开设置", prominent = true, horizontalPadding = 22.dp, verticalPadding = 12.dp, onClick = onOpenSettings)
        }
    }
}

/** 切换片源：沿用 Marco 的站点选择弹层语汇（玻璃卡 + 胶囊列表 + 当前源标记）。 */
@Composable
private fun SitePickerDialog(
    sites: List<Site>,
    currentKey: String,
    onPick: (Site) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Tokens.PanelSolid)
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(24.dp))
                .padding(26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("切换片源", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text("共 ${sites.size} 个站点 · 当前：${sites.firstOrNull { it.key == currentKey }?.name ?: "未选择"}", color = Tokens.TextDim, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                TvPill(text = "关闭", glass = true, horizontalPadding = 16.dp, verticalPadding = 8.dp, onClick = onDismiss)
            }
            Spacer(Modifier.height(18.dp))
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(gridState)
            ) {
                itemsIndexed(sites, key = { _, s -> s.key }) { _, s ->
                    TvPill(
                        text = s.name,
                        glass = true,
                        selected = s.key == currentKey,
                        enabled = s.isPlayable,
                        horizontalPadding = 18.dp,
                        verticalPadding = 14.dp,
                        onClick = { onPick(s) }
                    )
                }
            }
        }
    }
}
