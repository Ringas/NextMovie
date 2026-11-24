package com.nextmovie.ui.home

import com.nextmovie.ui.common.mouseWheel
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.nextmovie.ui.common.tvClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.nextmovie.ui.common.TvGridNavigation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.ContinueCard
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.posterModel
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 首页：全屏沉浸背景 + 焦点驱动的影片信息 + 继续观看 + 横向海报墙。
 * 背景图取当前焦点影片（TMDB backdrop / B站剧照 / 源站图）。
 */
@Composable
fun HomeScreen(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Vod) -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit = {},
    onResume: (HistoryEntry) -> Unit = {},
    initialFocusIndex: Int = 0,
    initialTab: Int = 0,
    reloadSignal: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    onFocusIndexChanged: (Int) -> Unit = {}
) {
    val screenStartedAt = remember { System.nanoTime() }
    var vods by remember { mutableStateOf<List<Vod>>(emptyList()) }
    /** 异步补图结果，按 id 归并；渲染时再和 vods 合并，避免并发写丢更新 */
    var enrichedById by remember { mutableStateOf<Map<String, Vod>>(emptyMap()) }
    var heroId by remember { mutableStateOf<String?>(null) }
    // Hero 需要年份/地区/简介才立得住：列表接口常常只有片名和评分，这里补一次详情元信息
    var heroMeta by remember { mutableStateOf<Vod?>(null) }
    var continueList by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    val requested = remember { mutableSetOf<String>() }
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    /** 分类导航：0 = 最近更新，其余对应 cats[i-1] */
    var cats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var tab by remember { mutableIntStateOf(initialTab) }
    val posterFocus = remember(vods.map { it.id }) { List(vods.size) { FocusRequester() } }
    var gridColumns by remember { mutableIntStateOf(5) }
    val density = LocalDensity.current.density
    val rowState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val initialReloadSignal = remember(site.runtimeKey) { reloadSignal }
    val initialSnapshot = remember(site.runtimeKey) {
        kotlinx.coroutines.CompletableDeferred<List<Vod>?>()
    }
    LaunchedEffect(site.runtimeKey) {
        initialSnapshot.complete(repo.cachedHome(site))
    }
    val backgroundReady = remember(site.runtimeKey) { kotlinx.coroutines.CompletableDeferred<Unit>() }
    LaunchedEffect(site.runtimeKey) {
        // Bind in parallel with reading the snapshot. Binding doesn't initialize a source.
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
            // 源头请求失败先自动重连一次（只针对超时/5xx 这类临时故障）
            com.nextmovie.core.net.Retry.once {
                if (tab == 0) repo.home(site, refresh = reloadTick > 0)
                else repo.category(site, cats.getOrNull(tab - 1)?.first.orEmpty(), 1)
            }
        }
            .onSuccess { list ->
                kotlin.coroutines.coroutineContext.ensureActive()
                android.util.Log.d("NextMovieHome", "home count=${list.size} " + list.take(5).joinToString(" | ") { "${it.name}=>${it.pic.take(60)}" })
                // L0：先把 URL 尺寸升级掉，首屏立刻能出图，不额外发请求
                vods = repo.prepareForList(list)
                enrichedById = emptyMap()
                requested.clear()
                heroId = null
            }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (vods.isEmpty()) error = com.nextmovie.core.net.FriendlyError.describe(e)
            }
        loading = false
    }

    // 回到首页时恢复上次停留的位置（索引由 AppRoot 持有，跨页面存活）
    LaunchedEffect(site.runtimeKey, tab, vods.map { it.id }) {
        // 只在「用户确实离开过第 0 张」时恢复焦点：首次进入不去抢焦点，
        // 否则会把刚固定住的 Hero 顶出去、滚动区自己滑到底。
        if (vods.isEmpty()) return@LaunchedEffect
        val target = initialFocusIndex.coerceIn(0, vods.lastIndex)
        if (target > 0) runCatching { rowState.scrollToItem(target + 1 + if (tab == 0 && continueList.isNotEmpty()) 1 else 0) }
        delay(100)
        runCatching { posterFocus.getOrNull(target)?.requestFocus() }
    }

    // L1：首屏前 4 张并发补图（TMDB 有 Token 时走 TMDB，否则走 B站）
    LaunchedEffect(site.key, vods.size) {
        if (vods.isEmpty()) return@LaunchedEffect
        val targets = vods.filter { it.id !in requested }.take(4)
        if (targets.isEmpty()) return@LaunchedEffect
        requested += targets.map { it.id }
        val sem = Semaphore(2)
        val resolved = coroutineScope {
            targets.map { vod -> async { sem.withPermit { repo.enrichPoster(vod, detail = false) } } }
                .awaitAll()
        }
        android.util.Log.d("NextMovieHome", "enriched " + resolved.joinToString(" | ") { "${it.name}=>${it.displayPic.take(60)}" })
        enrichedById = enrichedById + resolved.associateBy { it.id }
    }

    // 滚动到新区域时补上后面的卡片
    LaunchedEffect(rowState.firstVisibleItemIndex) {
        val start = rowState.firstVisibleItemIndex
        if (vods.isEmpty()) return@LaunchedEffect
        val targets = vods.drop(start).take(3).filter { it.id !in requested }
        if (targets.isEmpty()) return@LaunchedEffect
        requested += targets.map { it.id }
        val sem = Semaphore(2)
        val resolved = coroutineScope {
            targets.map { vod -> async { sem.withPermit { repo.enrichPoster(vod, detail = false) } } }
                .awaitAll()
        }
        enrichedById = enrichedById + resolved.associateBy { it.id }
    }

    val display = remember(vods, enrichedById) { vods.map { enrichedById[it.id] ?: it } }
    val heroBase = display.firstOrNull { it.id == heroId } ?: display.firstOrNull()
    val hero = heroMeta?.takeIf { it.id == heroBase?.id } ?: heroBase
    // Debounce navigation; never prefetch an entire wall or resolve playback/login speculatively.
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
    val backdrop = hero?.displayBackdrop.orEmpty()
    LaunchedEffect(display.isNotEmpty()) {
        if (display.isNotEmpty()) android.util.Log.i("NextMovieTiming", "home-ready " + (System.nanoTime() - screenStartedAt) / 1_000_000 + "ms")
    }

    Box(modifier = Modifier.fillMaxSize().background(Tokens.Bg).onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { onOpenSettings(); true } else false
    }) {
        // 焦点切换时背景 300ms 交叉淡入，避免硬切闪一下
        Crossfade(
            targetState = backdrop,
            animationSpec = tween(300),
            label = "heroBackdrop",
            modifier = Modifier.fillMaxSize()
        ) { url ->
            AsyncImage(
                model = remember(url) { posterModel(context, url, detail = true) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Tokens.ScrimTop,
                        0.42f to Color.Transparent,
                        0.68f to Color(0xCC0B0C10),
                        1f to Tokens.Bg
                    )
                )
        )

        // 左侧压暗：横向渐变与纵向渐变叠加，避免文字区出现生硬的矩形边界
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20B0C10),
                        0.42f to Color(0xB30B0C10),
                        0.76f to Color.Transparent
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 24.dp)
        ) {
            TopBar(site = site, status = repo.configStatus(), onSearch = onSearch, onOpenSettings = onOpenSettings)
            Spacer(Modifier.height(10.dp))
            if (hero != null) {
                HeroPanel(hero, onOpen = { onOpenDetail(hero) }, onSearch = onSearch)
                Spacer(Modifier.height(10.dp))
            }
            if (cats.isNotEmpty()) {
                CategoryTabs(cats, tab) { selected ->
                    tab = selected; onTabChanged(selected)
                }
                Spacer(Modifier.height(10.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns), state = rowState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).onSizeChanged { gridColumns = TvGridNavigation.columns(it.width / density) }.mouseWheel(rowState)
            ) {
                if (loading || error != null || site.unavailableReason != null || display.isEmpty()) {
                    item(key = "state", span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            loading -> HomeSkeleton()
                            site.unavailableReason != null -> ErrorBlock("站点不可用", site.unavailableReason!!, { reloadTick++ }, onOpenSettings)
                            error != null -> ErrorBlock("来源加载失败", error!!, { reloadTick++ }, onOpenSettings)
                            else -> ErrorBlock("此分类暂无内容", "可选择其他分类或更换来源", { reloadTick++ }, onOpenSettings)
                        }
                    }
                } else {
                    if (tab == 0 && continueList.isNotEmpty()) {
                        item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                SectionTitle("继续观看")
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(continueList, key = { it.key }) { entry -> ContinueCard(entry, { onResume(entry) }) }
                                }
                            }
                        }
                    }
                    item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(if (tab == 0) "最近更新" else cats.getOrNull(tab - 1)?.second.orEmpty())
                    }
                    itemsIndexed(display, key = { index, vod -> "poster|" + vod.id + "|" + index }) { index, vod ->
                        PosterCard(vod, { onOpenDetail(vod) }, {
                            heroId = vod.id; onFocusIndexChanged(index)
                        }, modifier = Modifier.focusRequester(posterFocus[index]).onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent.keyCode
                            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || key !in 19..22) false
                            else {
                                val target = TvGridNavigation.target(index, gridColumns, display.size, key)
                                if (target == null) false else {
                                    if (target != index) scope.launch {
                                        val gridIndex = target + 1 + if (tab == 0 && continueList.isNotEmpty()) 1 else 0
                                        if (rowState.layoutInfo.visibleItemsInfo.none { it.index == gridIndex }) {
                                            rowState.scrollToItem(gridIndex)
                                            delay(60)
                                        }
                                        runCatching { posterFocus[target].requestFocus() }
                                    }
                                    true
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    site: Site,
    status: AppRepository.ConfigStatus?,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "NEXT MOVIE",
                color = Tokens.Text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "HOST",
                color = Tokens.Accent,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = Tokens.Type.micro,
                    letterSpacing = Tokens.Type.MICRO_TRACK.sp
                ),
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.width(32.dp))
        NavPill("推荐", active = true, onClick = {})
        Spacer(Modifier.width(6.dp))
        NavPill("搜索", active = false, onClick = onSearch)
        Spacer(Modifier.width(6.dp))
        NavPill("设置", active = false, onClick = onOpenSettings)
        Spacer(Modifier.weight(1f))
        status?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (it.ok) Tokens.Accent else Tokens.Danger)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = it.text,
                    color = if (it.ok) Tokens.TextDim else Tokens.Danger,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun NavPill(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (active) Tokens.Text else Tokens.TextDim,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Corner.pill))
            .background(if (active) Tokens.SurfaceHigh else Color.Transparent)
            .tvClickable(onClick = onClick, corner = Tokens.Corner.pill)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

/**
 * Hero 信息区：片名 + 元信息 + 两行简介 + 主次操作。
 * 背景是当前焦点影片的剧照，这里只负责把「这是哪部片、能不能看」讲清楚。
 */
@Composable
private fun HeroPanel(vod: Vod, onOpen: () -> Unit, onSearch: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(0.56f)) {
        Text(
            text = vod.name,
            color = Tokens.Text,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 40.sp
        )
        val meta = listOf(vod.displayYear, vod.area, vod.typeName, vod.remark)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("  ·  ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                color = Tokens.Accent,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        val overview = vod.displayOverview
        if (overview.isNotBlank()) {
            Text(
                text = overview,
                color = Tokens.TextDim,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(Tokens.Gap.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroButton("查看详情", primary = true, onClick = onOpen)
            Spacer(Modifier.width(Tokens.Gap.m))
            HeroButton("搜索", primary = false, onClick = onSearch)
        }
    }
}

/**
 * Hero 主按钮：主按钮用强调色实心（和遥控器「确定」键语义一致），次按钮描边。
 * 只做视觉/语义，焦点态交给 Compose 默认的 focus 指示，避免自定义指示器与 tab 焦点冲突。
 */
@Composable
private fun HeroButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) Tokens.Accent else Color.Transparent
    val fg = if (primary) Tokens.OnAccent else Tokens.Text
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, Tokens.StrokeStrong, shape))
            .tvClickable(onClick = onClick, corner = Tokens.Corner.pill, scaleOnFocus = 1.03f)
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null, onPage: ((Int) -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = 14.dp)
                .background(Tokens.Accent)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            color = Tokens.Text,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = Tokens.Type.section,
                letterSpacing = 0.4.sp
            )
        )
        if (trailing != null) {
            Spacer(Modifier.width(Tokens.Gap.s))
            Text(
                text = trailing,
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (onPage != null) {
            Spacer(Modifier.weight(1f))
            PageArrow("‹") { onPage(-1) }
            Spacer(Modifier.width(8.dp))
            PageArrow("›") { onPage(1) }
        }
    }
}

/** 海报行的左右翻页按钮：电视上比鼠标滚轮更快，也方便纯遥控器用户。 */
@Composable
private fun PageArrow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tokens.SurfaceHigh)
            .tvClickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Text(text, color = Tokens.TextDim, style = MaterialTheme.typography.titleSmall)
    }
}

/** 分类导航条：横向可滚，选中态用强调色底。 */
@Composable
private fun CategoryTabs(
    cats: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.mouseWheel(state, horizontal = true)
    ) {
        item {
            TabChip("最近更新", selected == 0) { onSelect(0) }
        }
        itemsIndexed(cats) { index, item ->
            TabChip(item.second, selected == index + 1) { onSelect(index + 1) }
        }
    }
}

@Composable
private fun TabChip(text: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) Tokens.Accent else Tokens.Surface)
            .then(if (active) Modifier else Modifier.border(1.dp, Tokens.Stroke, shape))
            .tvClickable(onClick = onClick, corner = Tokens.Corner.pill)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (active) Tokens.OnAccent else Tokens.TextDim,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}


/**
 * 首屏骨架：真实数据回来前先给出布局轮廓，避免"黑屏 + 一行字"的廉价感。
 */
@Composable
private fun HomeSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        SkeletonBar(width = 420.dp, height = 44.dp, alpha = alpha)
        Spacer(Modifier.height(Tokens.Gap.m))
        SkeletonBar(width = 280.dp, height = 18.dp, alpha = alpha)
        Spacer(Modifier.height(Tokens.Gap.m))
        SkeletonBar(width = 620.dp, height = 16.dp, alpha = alpha)
        SkeletonBar(width = 560.dp, height = 16.dp, alpha = alpha, top = 8.dp)
        Spacer(Modifier.height(Tokens.Gap.xxl))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) {
                Box(
                    modifier = Modifier
                        .width(Tokens.CardWidth)
                        .height(Tokens.CardHeight)
                        .clip(RoundedCornerShape(Tokens.Corner.card))
                        .background(Tokens.SurfaceHigh.copy(alpha = alpha))
                )
            }
        }
    }
}

@Composable
private fun SkeletonBar(width: Dp, height: Dp, alpha: Float, top: Dp = 0.dp) {
    Box(
        modifier = Modifier
            .padding(top = top)
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(Tokens.Corner.badge))
            .background(Tokens.SurfaceHigh.copy(alpha = alpha))
    )
}

@Composable
private fun LoadingBlock() {
    Column {
        Text("正在加载…", color = Tokens.TextDim, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "首次使用插件源需要下载并初始化插件，可能要等几秒",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ErrorBlock(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 860.dp)
            .clip(RoundedCornerShape(Tokens.Corner.panel))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.panel))
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(title, color = Tokens.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = Tokens.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(Tokens.Accent)
                    .tvClickable(onClick = onRetry, corner = Tokens.Corner.pill)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text("重试", color = Tokens.OnAccent, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .border(1.dp, Tokens.StrokeStrong, RoundedCornerShape(Tokens.Corner.pill))
                    .tvClickable(onClick = onOpenSettings, corner = Tokens.Corner.pill)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text("换站点 / 改配置", color = Tokens.Text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
