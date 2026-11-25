package com.nextmovie.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.ContinueCard
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.settings.PairingDialog
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 首页重构：
 * 顶栏（站点 + 分类）→ 继续观看横滑 → 海报网格。
 * 去掉多层浮动胶囊与复杂装饰，焦点路径：分类 → 继续观看 → 网格。
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
    onSiteChanged: () -> Unit = {},
    onTint: (Color?) -> Unit = {},
) {
    val screenStartedAt = remember { System.nanoTime() }
    var vods by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var enrichedById by remember { mutableStateOf<Map<String, Vod>>(emptyMap()) }
    var continueList by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    val requested = remember(site.runtimeKey) { mutableSetOf<String>() }
    var focusedVod by remember(site.runtimeKey) { mutableStateOf<Vod?>(null) }
    val posterRequests = remember { Semaphore(2) }
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
    // 关掉「切换片源」的同一次按键会继续漏到底层「换源」按钮，加一道时间闸
    var pickerClosedAt by remember { mutableLongStateOf(0L) }
    var pairing by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val initialSnapshot = remember(site.runtimeKey) { kotlinx.coroutines.CompletableDeferred<List<Vod>?>() }
    LaunchedEffect(site.runtimeKey) { initialSnapshot.complete(repo.cachedHome(site)) }
    val backgroundReady = remember(site.runtimeKey) { kotlinx.coroutines.CompletableDeferred<Unit>() }
    LaunchedEffect(site.runtimeKey) {
        repo.connectPluginHost(site)
        if (initialSnapshot.await() != null) delay(800)
        backgroundReady.complete(Unit)
    }
    LaunchedEffect(site.runtimeKey) {
        continueList = repo.history.list().take(12)
        if (initialSnapshot.await() != null) backgroundReady.await()
        cats = try {
            repo.categories(site)
        } catch (_: Throwable) {
            emptyList()
        }
    }
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        loadStartedAt = System.currentTimeMillis()
        while (loading) {
            loadSecs = ((System.currentTimeMillis() - loadStartedAt) / 1000L).toInt()
            delay(400)
        }
    }
    val selectedCategoryId = if (tab == 0) null else cats.getOrNull(tab)?.first
    LaunchedEffect(site.runtimeKey, tab, reloadTick, reloadSignal, selectedCategoryId) {
        loading = true
        error = null
        warming = false
        try {
            val cat = cats.getOrNull(tab)
            val list = if (cat == null || tab == 0) {
                val cached = initialSnapshot.await()
                if (cached != null && reloadTick == 0) {
                    vods = cached
                    loading = false
                    android.util.Log.i("NextMovieTiming", "home-instant " + (System.nanoTime() - screenStartedAt) / 1_000_000 + "ms items=${cached.size}")
                    scope.launch {
                        backgroundReady.await()
                        runCatching { repo.home(site) }.onSuccess { fresh ->
                            if (fresh.isNotEmpty()) vods = fresh
                        }
                    }
                    return@LaunchedEffect
                }
                repo.home(site)
            } else {
                // 切 tab 先出磁盘快照，再后台刷新，避免每次点分类都等插件
                val cachedCat = if (reloadTick == 0) repo.cachedCategory(site, cat.first) else null
                if (cachedCat != null && vods.none { it.id.startsWith("cat-reset") }) {
                    vods = cachedCat
                    loading = false
                    android.util.Log.i("NextMovieTiming", "cat-instant " + cat.first + " items=${cachedCat.size}")
                    scope.launch {
                        runCatching { repo.category(site, cat.first, page = 1) }
                            .onSuccess { if (it.isNotEmpty() && tab == cats.indexOf(cat)) vods = it }
                    }
                    return@LaunchedEffect
                }
                repo.category(site, cat.first, page = 1)
            }
            vods = list
            if (list.isEmpty()) {
                warming = true
                warmSince = System.currentTimeMillis()
                warmTries = 0
            }
        } catch (t: Throwable) {
            val msg = t.message ?: "未知错误"
            if (msg.contains("未配置", ignoreCase = true) || msg.contains("站点", ignoreCase = true)) {
                error = msg
            } else {
                error = msg
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
    LaunchedEffect(site.runtimeKey, focusedVod?.id) {
        val focused = focusedVod ?: return@LaunchedEffect
        delay(450)
        repo.prefetchDetail(site, focused)
    }
    LaunchedEffect(site.runtimeKey, vods) {
        val targets = vods.filter { it.displayPic.isBlank() && it.id !in requested }.take(4)
        if (targets.isEmpty()) return@LaunchedEffect
        requested += targets.map { it.id }
        val resolved = coroutineScope {
            targets.map { vod -> async { posterRequests.withPermit { repo.enrichPoster(vod, detail = false) } } }.awaitAll()
        }
        enrichedById = enrichedById + resolved.associateBy { it.id }
    }

    val display = remember(vods, enrichedById) { vods.map { enrichedById[it.id] ?: it } }
    LaunchedEffect(display.size) {
        if (display.isNotEmpty()) {
            android.util.Log.i("NextMovieTiming", "home-ready " + (System.nanoTime() - screenStartedAt) / 1_000_000 + "ms")
        }
    }

    if (pairing) {
        PairingDialog(
            onDismiss = { pairing = false },
            onReceived = {
                pairing = false
                onOpenSettings()
            },
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
            onDismiss = {
                sitePicker = false
                pickerClosedAt = android.os.SystemClock.elapsedRealtime()
            },
        )
    }

    val continueVisible = tab == 0 && continueList.isNotEmpty()
    val columns = when {
        LocalConfiguration.current.screenWidthDp >= 1500 -> 6
        LocalConfiguration.current.screenWidthDp >= 1100 -> 5
        else -> 4
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 32.dp, top = 20.dp, bottom = 16.dp),
    ) {
        // —— 顶栏：站点名 + 快捷操作 ——
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = site.name.ifBlank { "NextMovie" },
                    color = Tokens.Text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        loading -> "加载中… ${loadSecs}s"
                        error != null -> "加载失败"
                        display.isEmpty() -> "暂无内容"
                        else -> "${display.size} 部 · 遥控器确认打开"
                    },
                    color = Tokens.TextFaint,
                    fontSize = 13.sp,
                )
            }
            TvPill(
                text = "换源",
                glass = true,
                horizontalPadding = 16.dp,
                verticalPadding = 10.dp,
                onClick = {
                    if (android.os.SystemClock.elapsedRealtime() - pickerClosedAt > 250L) sitePicker = true
                }
            )
            TvPill(text = "搜索", glass = true, horizontalPadding = 16.dp, verticalPadding = 10.dp, onClick = onSearch)
            TvPill(
                text = "刷新",
                glass = true,
                horizontalPadding = 16.dp,
                verticalPadding = 10.dp,
                onClick = { reloadTick += 1 }
            )
        }

        Spacer(Modifier.height(14.dp))

        // —— 分类条 ——
        if (cats.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(cats.size) { index ->
                    val name = cats[index].second.ifBlank { cats[index].first }
                    val selected = index == tab
                    TvPill(
                        text = name,
                        selected = selected,
                        glass = !selected,
                        horizontalPadding = 18.dp,
                        verticalPadding = 10.dp,
                        onClick = {
                            tab = index
                            onTabChanged(index)
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when {
            loading && display.isEmpty() -> LoadingStage(loadSecs, warming, warmTries, warmSince)
            error != null && display.isEmpty() -> ErrorStage(
                message = error ?: "",
                onRetry = { reloadTick += 1 },
                onOpenSettings = onOpenSettings,
            )
            display.isEmpty() -> EmptyStage(siteReason = site.unavailableReason, onOpenSettings = onOpenSettings)
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .mouseWheel(gridState),
                ) {
                    if (continueVisible) {
                        item(span = { GridItemSpan(columns) }, key = "continue-title") {
                            Text(
                                "继续观看",
                                color = Tokens.Text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        item(span = { GridItemSpan(columns) }, key = "continue-row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 8.dp),
                            ) {
                                items(continueList, key = { it.key }) { entry ->
                                    ContinueCard(
                                        entry = entry,
                                        onClick = { onResume(entry) },
                                    )
                                }
                            }
                        }
                        item(span = { GridItemSpan(columns) }, key = "grid-title") {
                            Text(
                                if (tab == 0) "精选" else cats.getOrNull(tab)?.second ?: "列表",
                                color = Tokens.Text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                    itemsIndexed(display, key = { _, v -> v.id }) { index, vod ->
                        PosterCard(
                            vod = vod,
                            onClick = { onOpenDetail(vod) },
                            onFocused = {
                                focusedVod = vod
                                onFocusIndexChanged(index)
                            },
                            fillWidth = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingStage(secs: Int, warming: Boolean, tries: Int, since: Long) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Tokens.Accent)
            Spacer(Modifier.height(16.dp))
            Text(
                if (warming) "站点预热中… 第 ${tries + 1} 次" else "正在加载片单",
                color = Tokens.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text("${secs}s", color = Tokens.TextFaint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorStage(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", color = Tokens.AmberText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Tokens.TextDim, fontSize = 14.sp, modifier = Modifier.width(520.dp))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPill(text = "重试", prominent = true, horizontalPadding = 22.dp, verticalPadding = 12.dp, onClick = onRetry)
                TvPill(text = "设置", horizontalPadding = 22.dp, verticalPadding = 12.dp, onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun EmptyStage(siteReason: String?, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (siteReason != null) "站点不可用" else "这个分类还没有内容",
                color = Tokens.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            if (siteReason != null) {
                Spacer(Modifier.height(10.dp))
                Text(siteReason, color = Tokens.TextDim, fontSize = 14.sp, modifier = Modifier.width(520.dp))
            }
            Spacer(Modifier.height(20.dp))
            TvPill(text = "打开设置", prominent = true, horizontalPadding = 22.dp, verticalPadding = 12.dp, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun SitePickerDialog(
    sites: List<Site>,
    currentKey: String,
    onPick: (Site) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .height(520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Tokens.PanelSolid)
                .border(1.dp, Tokens.StrokeStrong, RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("切换片源", color = Tokens.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TvPill(
                    text = "关闭",
                    glass = true,
                    autofocus = true,
                    horizontalPadding = 16.dp,
                    verticalPadding = 8.dp,
                    onClick = onDismiss
                )
            }
            Spacer(Modifier.height(16.dp))
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(gridState),
            ) {
                itemsIndexed(sites, key = { _, s -> s.key }) { _, s ->
                    TvPill(
                        text = s.name,
                        glass = true,
                        selected = s.key == currentKey,
                        enabled = s.isPlayable,
                        horizontalPadding = 18.dp,
                        verticalPadding = 14.dp,
                        onClick = { onPick(s) },
                    )
                }
            }
        }
    }
}
