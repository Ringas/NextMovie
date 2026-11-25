@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nextmovie.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Episode
import com.nextmovie.core.model.PlayLine
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.mediaBadges
import com.nextmovie.core.model.ratingBadges
import com.nextmovie.core.model.withMetadataFrom
import com.nextmovie.ui.common.AmberChip
import com.nextmovie.ui.common.BrandLogo
import com.nextmovie.ui.common.GoldChip
import com.nextmovie.ui.common.MetaChip
import com.nextmovie.ui.common.PosterTint
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.common.mouseFocusable
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.common.posterModel
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.common.TvKeys
import com.nextmovie.ui.common.TvAction
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.nextmovie.ui.theme.Tokens
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 详情页（对齐 Marco TvDetailScreen）
 *
 * 整屏是一张剧照 + 多层压暗，文字压在「编辑式」左侧一栏（最宽 880dp）：
 * 元信息胶囊 → 44sp 片名 → 上次观看进度 → 主演 / 简介 → 行动胶囊行。
 * 下方是一整块深色玻璃面板：播放线路（每个源一条，带 HLS / 4K 云盘标识）、
 * 集数分组与 76dp 高的选集格子。
 *
 * 数据侧仍然走 NextMovie 的多源聚合：首个源回来就先渲染，全部回来按画质/成功率重排。
 */
@Composable
fun DetailScreen(
    repo: AppRepository,
    site: Site,
    initial: Vod,
    onPlay: (
        source: Site,
        vod: Vod,
        line: PlayLine,
        episode: Episode,
        startMs: Long,
        slots: List<com.nextmovie.core.model.PlaySlot>
    ) -> Unit,
    onBack: () -> Unit,
    /** 从播放器退回时仍在播的画面，铺在剧照位置（对齐 Marco） */
    videoBackdrop: (@Composable () -> Unit)? = null
) {
    androidx.activity.compose.BackHandler { onBack() }

    val screenStartedAt = remember { System.nanoTime() }
    val context = LocalContext.current
    var cachedMetadata by remember(site.runtimeKey, initial.id) { mutableStateOf<Vod?>(null) }
    LaunchedEffect(site.runtimeKey, initial.id) {
        cachedMetadata = repo.cachedDetailMetadata(site, initial.id)
        if (cachedMetadata != null) {
            android.util.Log.i("NextMovieTiming", "detail-metadata-ready ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms")
        }
        // 进页即补海报/评分/简介（不挡聚合）
        val seed = cachedMetadata?.withMetadataFrom(initial) ?: initial
        val enriched = runCatching { repo.enrichPoster(seed, detail = true) }.getOrDefault(seed)
        if (enriched != seed) {
            cachedMetadata = enriched
            android.util.Log.i("NextMovieTiming", "detail-meta-enrich ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms")
        }
    }
    val scope = rememberCoroutineScope()
    val prefetched = remember(site.runtimeKey, initial.id) { repo.readyDetail(site, initial.id) }
    var agg by remember(site.runtimeKey, initial.id) { mutableStateOf(prefetched) }
    LaunchedEffect(agg != null) {
        if (agg == null) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        android.util.Log.i("NextMovieTiming", "detail-presented ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms")
    }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var lineIndex by remember { mutableIntStateOf(prefetched?.best?.bestLineIndex ?: 0) }
    var loading by remember { mutableStateOf(prefetched == null) }
    var hint by remember { mutableStateOf("正在聚合全网来源…") }
    var manual by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var moreSources by remember { mutableIntStateOf(0) }
    var descending by remember { mutableStateOf(false) }
    var groupIndex by remember { mutableIntStateOf(0) }
    var synopsisExpanded by remember { mutableStateOf(false) }
    var episodeSheet by remember { mutableStateOf(false) }
    var posterTint by remember { mutableStateOf<Color?>(null) }
    val playFocus = remember { FocusRequester() }
    // Marco 的焦点是「返回 → 播放按钮 → 线路 → 选集」一条纵向链，不交给 2D 就近搜索乱跳
    val backFocus = remember { FocusRequester() }
    val lineFocus = remember { FocusRequester() }
    val epFocus = remember { FocusRequester() }

    // 进详情只查「当前来源」一次：这是拿到线路的最短路径（Marco 同模型）。
    // 全网比对改成显式动作（「更多来源」），不再让 12 个站点的首次初始化挡在选集前面。
    LaunchedEffect(initial.id, site.key, reload) {
        val ready = repo.readyDetail(site, initial.id)
        agg = ready
        error = null
        sourceIndex = 0
        lineIndex = ready?.best?.bestLineIndex ?: 0
        manual = false
        groupIndex = 0
        loading = ready == null
        hint = if (ready != null) "当前来源已就绪" else "正在读取当前来源…"
        if (ready == null) {
            val quick = runCatching { repo.detailForSite(site, initial) }.getOrNull()
            if (quick != null && quick.sources.isNotEmpty()) {
                android.util.Log.i("NextMovieTiming", "detail-quick ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms")
                agg = quick
                lineIndex = quick.best?.bestLineIndex ?: 0
                hint = "当前来源 ·「${quick.best?.site?.name ?: site.name}」"
                loading = false
            } else {
                loading = false
                error = "当前来源没有返回可用线路"
            }
        }
    }

    LaunchedEffect(moreSources) {
        if (moreSources == 0) return@LaunchedEffect
        loading = agg == null
        hint = "正在比对其它来源（最多 4 个）…"
        val result = runCatching {
            repo.aggregateDetail(site, initial, crossSiteLimit = 4) { partial ->
                if (partial.sources.size > (agg?.sources?.size ?: 0)) {
                    android.util.Log.i("NextMovieTiming", "detail-partial ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms sources=${partial.sources.size}")
                    agg = partial
                    if (!manual) {
                        sourceIndex = 0
                        lineIndex = partial.best?.bestLineIndex ?: 0
                    }
                    loading = false
                    hint = "已比到「${partial.best?.site?.name ?: site.name}」等 ${partial.sources.size} 个来源…"
                }
            }
        }
        result.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            if (agg == null) error = com.nextmovie.core.net.FriendlyError.describe(it)
            loading = false
        }
        val full = result.getOrNull()
        if (full == null || full.sources.isEmpty()) {
            loading = false
            if (agg == null) error = "其它来源也没有返回这部片的可用线路"
        } else {
            android.util.Log.i("NextMovieTiming", "detail-ready ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms sources=${full.sources.size}")
            val previousBest = agg?.best?.site?.key
            agg = full
            if (!manual) {
                sourceIndex = 0
                lineIndex = full.best?.bestLineIndex ?: 0
            }
            loading = false
            hint = buildString {
                append("已比对 ${full.sources.size} 个来源 · 采用「${full.best?.site?.name ?: site.name}」")
                full.best?.badge?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                if (previousBest != null && previousBest != full.best?.site?.key) append("（已切到更优源）")
            }
        }
    }

    LaunchedEffect(loading, agg) {
        if (!loading || agg != null) return@LaunchedEffect
        var elapsed = 0
        while (loading && agg == null) {
            delay(1_000)
            elapsed += 1
            if (elapsed >= 4) hint = "插件源首次初始化中，通常 10–30 秒…（已 ${elapsed}s）"
        }
    }

    LaunchedEffect(agg?.best?.site?.key, agg?.best?.detail?.vod?.id) {
        val currentAgg = agg ?: return@LaunchedEffect
        val best = currentAgg.best ?: return@LaunchedEffect
        val enriched = repo.enrichPoster(best.detail.vod, detail = true)
        if (enriched !== best.detail.vod) {
            agg = agg?.let { latest ->
                latest.copy(sources = latest.sources.map { source ->
                    if (source.site.runtimeKey == best.site.runtimeKey && source.detail.vod.id == best.detail.vod.id) {
                        source.copy(detail = source.detail.copy(vod = source.detail.vod.withMetadataFrom(enriched).copy(
                            tmdb = enriched.tmdb ?: source.detail.vod.tmdb
                        )))
                    } else source
                })
            }
        }
    }

    val sources = agg?.sources.orEmpty()
    val maxEpisodes = remember(sources) {
        sources.maxOfOrNull { src -> src.detail.lines.maxOfOrNull { it.episodes.size } ?: 0 } ?: 0
    }
    val visibleSources = remember(sources, filter) {
        val picked = when (filter) {
            1 -> sources.filter { it.height >= 1440 }
            2 -> sources.filter { it.height >= 1080 }
            3 -> sources.filter { (it.detail.lines.maxOfOrNull { line -> line.episodes.size } ?: 0) >= maxEpisodes }
            else -> sources
        }
        picked.ifEmpty { sources }
    }
    val safeSourceIndex = sourceIndex.coerceIn(0, sources.lastIndex.coerceAtLeast(0))
    val activeSource = sources.getOrNull(safeSourceIndex)
    val active: Site = activeSource?.site ?: site
    val seed = cachedMetadata?.withMetadataFrom(initial) ?: initial
    val vod = activeSource?.detail?.vod?.withMetadataFrom(seed) ?: seed
    val lines: List<PlayLine> = activeSource?.detail?.lines.orEmpty()
    val line: PlayLine? = lines.getOrNull(lineIndex)
        ?: activeSource?.let { src -> lines.getOrNull(src.bestLineIndex) }
        ?: lines.firstOrNull()

    LaunchedEffect(lines.isNotEmpty()) {
        if (lines.isNotEmpty()) { delay(120); runCatching { playFocus.requestFocus() } }
    }

    var isFavorite by remember { mutableStateOf(repo.favorites.contains(active.key, vod.id)) }
    LaunchedEffect(active.key, vod.id, agg) {
        isFavorite = repo.favorites.contains(active.key, vod.id)
    }

    val resumeEntry = remember(vod.name, agg) {
        (repo.history.findByName(vod.name) ?: repo.history.find(active.key, vod.id))
            ?.takeIf { it.resumable && it.episodeUrl.isNotBlank() }
    }

    LaunchedEffect(vod.displayBackdrop, vod.displayPic) {
        val url = vod.displayBackdrop.ifBlank { vod.displayPic }
        PosterTint.resolve(context, url)?.let { posterTint = it }
    }

    fun slotsOf(episodeIndex: Int, episode: Episode): List<com.nextmovie.core.model.PlaySlot> {
        val out = ArrayList<com.nextmovie.core.model.PlaySlot>(sources.size)
        sources.forEach { src ->
            val orderedLines = listOfNotNull(src.detail.lines.getOrNull(src.bestLineIndex)) + src.detail.lines
            orderedLines.distinct().forEach lineOption@{ option ->
                val sameLine = src.site.key == active.key && option == line
                val ep = if (sameLine) episode else option.episodes.firstOrNull { it.name == episode.name }
                    ?: option.episodes.getOrNull(episodeIndex).takeIf { option.episodes.size == line?.episodes?.size }
                    ?: return@lineOption
                val index = option.episodes.indexOf(ep)
                out += com.nextmovie.core.model.PlaySlot(
                    src.site.key, src.site.name, option.name, src.badge, src.score, ep.name, ep.url,
                    src.detail.vod, if (index >= 0) option.episodes.drop(index + 1) else emptyList()
                )
            }
        }
        return out
    }

    fun resume() {
        val entry = resumeEntry ?: return
        val src = sources.firstOrNull { it.site.key == entry.siteKey }
        val srcLines = src?.detail?.lines ?: lines
        val srcVod = src?.detail?.vod ?: vod
        val srcSite = src?.site ?: active
        val target = srcLines.firstOrNull { it.name == entry.playFrom } ?: srcLines.firstOrNull() ?: return
        val idx = target.episodes.indexOfFirst { it.url == entry.episodeUrl }
        val ep = target.episodes.getOrNull(idx) ?: Episode(entry.episodeName.ifBlank { "继续观看" }, entry.episodeUrl)
        onPlay(srcSite, srcVod, target, ep, entry.positionMs, slotsOf(idx.coerceAtLeast(0), ep))
    }

    val totalEpisodes = line?.episodes?.size ?: 0
    val episodesPerGroup = 30
    val groupCount = if (totalEpisodes > 0) (totalEpisodes + episodesPerGroup - 1) / episodesPerGroup else 0
    val safeGroup = groupIndex.coerceIn(0, (groupCount - 1).coerceAtLeast(0))
    val episodeIndices = remember(totalEpisodes, safeGroup, descending) {
        if (totalEpisodes == 0) emptyList()
        else {
            val start = safeGroup * episodesPerGroup
            val end = min(totalEpisodes, start + episodesPerGroup)
            val range = (start until end).toList()
            if (descending) range.reversed() else range
        }
    }
    val heroWidth = LocalConfiguration.current.screenWidthDp.dp
    val horizontalPad = (heroWidth * 0.036f).coerceIn(36.dp, 64.dp)
    val topBreathing = (LocalConfiguration.current.screenHeightDp.dp * 0.12f).coerceIn(56.dp, 128.dp)

    if (episodeSheet && line != null) {
        EpisodeSheet(
            title = vod.name,
            line = line,
            resumeUrl = resumeEntry?.episodeUrl.orEmpty(),
            onPlay = { ep ->
                episodeSheet = false
                val idx = line.episodes.indexOf(ep).coerceAtLeast(0)
                onPlay(active, vod, line, ep, 0L, slotsOf(idx, ep))
            },
            onDismiss = { episodeSheet = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Tokens.Bg)) {
        // 剧照铺满 + 多层压暗：左边最实（压文字），右侧保留画面呼吸
        if (videoBackdrop != null) {
            Box(Modifier.fillMaxSize()) { videoBackdrop() }
        } else AsyncImage(
            model = remember(vod.displayBackdrop, vod.displayPic) {
                posterModel(context, vod.displayBackdrop.ifBlank { vod.displayPic }, detail = true)
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.62f,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xF00B1420),
                    0.46f to Color(0xB80B1420),
                    1f to Color(0x660B1420)
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xB30B1420),
                    0.26f to Color(0x470B1420),
                    0.72f to Color(0x590B1420),
                    1f to Color(0xF00B1420)
                )
            )
        )
        if (posterTint != null) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        0f to posterTint!!.copy(alpha = 0.26f),
                        0.55f to posterTint!!.copy(alpha = 0.08f),
                        1f to Color.Transparent,
                        center = androidx.compose.ui.geometry.Offset(360f, 420f),
                        radius = 1100f
                    )
                )
            )
        }

        // 顶栏遮罩：滚动内容从返回钮下面淡出，避免文字压字
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xF2121D2C),
                        0.62f to Color(0x99121D2C),
                        1f to Color(0x00121D2C)
                    )
                )
        )
        // Marco 的详情页返回钮固定在顶部，不随内容滚动
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontalPad, end = horizontalPad, top = 24.dp)
        ) {
            TvPill(
                text = "← 返回",
                glass = true,
                horizontalPadding = 18.dp,
                verticalPadding = 10.dp,
                modifier = Modifier
                    .focusRequester(backFocus)
                    .focusProperties { down = playFocus },
                onClick = onBack
            )
            Spacer(Modifier.weight(1f))
            BrandLogo(iconSize = 34.dp, fontSize = 20f, showWordmark = false)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = horizontalPad, end = horizontalPad, top = 92.dp, bottom = 40.dp)
        ) {
            Spacer(Modifier.height(topBreathing))

            Column(modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(0.62f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetaChip(if (sources.isEmpty()) site.name else active.name, highlight = true)
                    vod.ratingBadges().forEach { GoldChip(it) }
                    vod.mediaBadges(line?.episodes?.firstOrNull()?.name.orEmpty(), line?.name.orEmpty())
                        .forEach { MetaChip(it) }
                    vod.displayYear.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                    vod.area.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                    vod.remark.takeIf { it.isNotBlank() }?.let { MetaChip(it.take(16)) }
                    if (line != null && totalEpisodes > 0) MetaChip("共 $totalEpisodes 集")
                    if (detectDrive(line?.name.orEmpty()) != null) AmberChip("4K 云盘")
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = vod.name,
                    color = Color.White,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.7).sp,
                    lineHeight = 50.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (resumeEntry != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Tokens.Accent))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "上次观看：${resumeEntry.episodeName.ifBlank { "上次位置" }} · ${fmtMs(resumeEntry.positionMs)}",
                            color = Color(0xE6FFFFFF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (resumeEntry.progress > 0.01f) {
                            Spacer(Modifier.width(12.dp))
                            Box(
                                Modifier.width(110.dp).height(4.dp).clip(RoundedCornerShape(999.dp))
                                    .background(Color(0x2EFFFFFF))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(resumeEntry.progress.coerceIn(0f, 1f))
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Tokens.Accent)
                                )
                            }
                        }
                    }
                }
                if (vod.actor.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "主演：${vod.actor}",
                        color = Color(0xC7FFFFFF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val overview = vod.displayOverview
                if (overview.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = overview,
                        color = Color(0xD6FFFFFF),
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        maxLines = if (synopsisExpanded) 8 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .mouseFocusable()
                            .tvClickable(showRing = false, onClick = { synopsisExpanded = !synopsisExpanded })
                    )
                }
                Spacer(Modifier.height(24.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val ctaLabel = resumeEntry?.let {
                        "▶ 续播 ${it.episodeName.ifBlank { "上次位置" }} (${fmtMs(it.positionMs)})"
                    } ?: "▶ 立即播放"
                    Box(modifier = Modifier.focusRequester(playFocus)) {
                        TvPill(
                            text = ctaLabel,
                            prominent = true,
                            modifier = Modifier.focusProperties {
                                up = backFocus
                                if (lines.isNotEmpty()) down = lineFocus
                            },
                            enabled = line != null && line.episodes.isNotEmpty(),
                            autofocus = true,
                            horizontalPadding = 28.dp,
                            verticalPadding = 14.dp,
                            onClick = {
                                if (resumeEntry != null) resume()
                                else line?.episodes?.firstOrNull()?.let { first ->
                                    onPlay(active, vod, line, first, 0L, slotsOf(0, first))
                                }
                            }
                        )
                    }
                    if (totalEpisodes > 1) {
                        TvPill(
                            text = "选集 · 共${totalEpisodes}集",
                            glass = true,
                            horizontalPadding = 22.dp,
                            verticalPadding = 14.dp,
                            onClick = { episodeSheet = true }
                        )
                    }
                    TvPill(
                        text = if (isFavorite) "★ 已收藏" else "☆ 收藏",
                        glass = true,
                        selected = isFavorite,
                        horizontalPadding = 22.dp,
                        verticalPadding = 14.dp,
                        onClick = {
                            isFavorite = repo.favorites.toggle(
                                com.nextmovie.core.history.FavoriteEntry(
                                    siteKey = active.key,
                                    vodId = vod.id,
                                    name = vod.name,
                                    pic = vod.displayPic,
                                    remark = vod.remark,
                                    year = vod.displayYear
                                )
                            )
                        }
                    )
                    if (sources.size > 1) {
                        TvPill(
                            text = "☁️ 多源聚合 ${sources.size}",
                            glass = true,
                            horizontalPadding = 20.dp,
                            verticalPadding = 14.dp,
                            onClick = { filter = 0; sourceIndex = 0; manual = true }
                        )
                    } else if (sources.isNotEmpty()) {
                        TvPill(
                            text = "查找更多来源",
                            glass = true,
                            horizontalPadding = 20.dp,
                            verticalPadding = 14.dp,
                            onClick = { moreSources++ }
                        )
                    }
                }
            }

            Spacer(Modifier.height(34.dp))

            // —— 播放线路 + 选集玻璃面板 ——
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Corner.hero))
                    .background(Tokens.Panel)
                    .border(1.1.dp, Color(0x26FFFFFF), RoundedCornerShape(Tokens.Corner.hero))
                    .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 26.dp)
            ) {
                when {
                    loading && lines.isEmpty() -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 42.dp).fillMaxWidth()
                    ) {
                        CircularProgressIndicator(color = Tokens.Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("正在解析选集与播放线路…", color = Color(0xB3FFFFFF), fontSize = 15.sp)
                            if (hint.isNotBlank()) Text(hint, color = Tokens.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    error != null && lines.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "加载详情失败：$error",
                            color = Color(0xFFFF9A9A),
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TvPill(text = "更多来源", glass = true, onClick = { moreSources++ })
                        Spacer(Modifier.width(10.dp))
                        TvPill(text = "重试加载", glass = true, onClick = { reload++ })
                    }
                    lines.isEmpty() -> Text(
                        "当前条目暂无可播放线路",
                        color = Tokens.TextFaint,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    else -> {
                        // 播放线路：一个源一条，带集数 + HLS / 4K 云盘标识
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("播放线路", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                            val lineState = rememberLazyListState()
                            LazyRow(
                                state = lineState,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f).mouseWheel(lineState, horizontal = true)
                            ) {
                                itemsIndexed(lines) { index, item ->
                                    LinePill(
                                        name = item.name,
                                        count = item.episodes.size,
                                        drive = detectDrive(item.name),
                                        selected = index == lineIndex,
                                        modifier = if (index == 0) Modifier
                                            .focusRequester(lineFocus)
                                            .focusProperties {
                                                up = playFocus
                                                if (item.episodes.isNotEmpty()) down = epFocus
                                            } else Modifier,
                                        onClick = {
                                            lineIndex = index
                                            groupIndex = 0
                                            manual = true
                                        }
                                    )
                                }
                            }
                        }

                        if (sources.size > 1) {
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("来源", color = Tokens.TextFaint, fontSize = 13.sp)
                                Spacer(Modifier.width(10.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        0 to "全部 ${sources.size}",
                                        1 to "4K/2K ${sources.count { it.height >= 1440 }}",
                                        2 to "1080P+ ${sources.count { it.height >= 1080 }}",
                                        3 to "集数最全 ${sources.count { (it.detail.lines.maxOfOrNull { l -> l.episodes.size } ?: 0) >= maxEpisodes }}"
                                    ).forEach { (value, label) ->
                                        TvPill(
                                            text = label,
                                            selected = value == filter,
                                            enabled = !((value == 1 && sources.none { it.height >= 1440 }) || (value == 2 && sources.none { it.height >= 1080 })),
                                            horizontalPadding = 14.dp,
                                            verticalPadding = 7.dp,
                                            onClick = {
                                                filter = value
                                                manual = true
                                                val next = when (value) {
                                                    1 -> sources.firstOrNull { it.height >= 1440 }
                                                    2 -> sources.firstOrNull { it.height >= 1080 }
                                                    3 -> sources.firstOrNull { (it.detail.lines.maxOfOrNull { l -> l.episodes.size } ?: 0) >= maxEpisodes }
                                                    else -> sources.firstOrNull()
                                                }
                                                if (next != null) {
                                                    sourceIndex = sources.indexOfFirst { it.site.key == next.site.key }
                                                    lineIndex = next.bestLineIndex
                                                    groupIndex = 0
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            val srcState = rememberLazyListState()
                            LazyRow(
                                state = srcState,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.mouseWheel(srcState, horizontal = true)
                            ) {
                                itemsIndexed(visibleSources) { _, src ->
                                    val isActive = src.site.key == active.key
                                    TvPill(
                                        text = "${src.site.name}  ${src.badge}",
                                        glass = true,
                                        selected = isActive,
                                        horizontalPadding = 14.dp,
                                        verticalPadding = 7.dp,
                                        onClick = {
                                            manual = true
                                            val idx = sources.indexOfFirst { it.site.key == src.site.key }
                                            if (idx >= 0) {
                                                sourceIndex = idx
                                                lineIndex = src.bestLineIndex
                                                groupIndex = 0
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Box(
                            Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF))
                        )

                        // 选集工具条
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("选集列表（共 $totalEpisodes 集）", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(14.dp))
                            TvPill(
                                text = if (descending) "↓ 倒序" else "↑ 正序",
                                glass = true,
                                horizontalPadding = 14.dp,
                                verticalPadding = 7.dp,
                                onClick = { descending = !descending }
                            )
                            Spacer(Modifier.width(10.dp))
                            if (groupCount > 1) {
                                val groupState = rememberLazyListState()
                                LazyRow(
                                    state = groupState,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f).mouseWheel(groupState, horizontal = true)
                                ) {
                                    itemsIndexed((0 until groupCount).toList()) { g, _ ->
                                        TvPill(
                                            text = "${g * episodesPerGroup + 1}-${min(totalEpisodes, (g + 1) * episodesPerGroup)}",
                                            glass = true,
                                            selected = g == safeGroup,
                                            horizontalPadding = 14.dp,
                                            verticalPadding = 7.dp,
                                            onClick = { groupIndex = g }
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (totalEpisodes > 12) {
                                TvPill(
                                    text = "全屏选集 ›",
                                    glass = true,
                                    horizontalPadding = 14.dp,
                                    verticalPadding = 7.dp,
                                    onClick = { episodeSheet = true }
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        val epState = rememberLazyGridState()
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 132.dp),
                            state = epState,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightInGrid(totalEpisodes)
                                .mouseWheel(epState)
                        ) {
                            itemsIndexed(episodeIndices) { slot, epIndex ->
                                val ep = line!!.episodes[epIndex]
                                val watched = resumeEntry?.episodeUrl == ep.url
                                EpisodeTile(
                                    number = epIndex + 1,
                                    modifier = if (slot == 0) Modifier
                                        .focusRequester(epFocus)
                                        .focusProperties { up = lineFocus } else Modifier,
                                    title = ep.name,
                                    watched = watched,
                                    isNetdisk = detectDrive(line.name) != null,
                                    onClick = {
                                        onPlay(active, vod, line, ep, 0L, slotsOf(epIndex, ep))
                                    }
                                )
                            }
                        }
                        if (hint.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(hint, color = Tokens.Accent, fontSize = 13.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** 一屏最多显示 6 行选集，超出后网格内部滚动；避免详情页被 40 集顶到几米长。 */
private fun Modifier.heightInGrid(total: Int) = this.height(
    when {
        total <= 12 -> 88.dp
        total <= 24 -> 176.dp
        total <= 36 -> 264.dp
        else -> 352.dp
    }
)

@Composable
private fun LinePill(
    name: String,
    count: Int,
    drive: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvPill(
            text = "$name ($count)",
            glass = true,
            selected = selected,
            modifier = modifier,
            horizontalPadding = 18.dp,
            verticalPadding = 9.dp,
            onClick = onClick
        )
        Spacer(Modifier.width(8.dp))
        if (drive != null) AmberChip("4K 云盘") else HlsBadge()
    }
}

@Composable
private fun HlsBadge() {
    Text(
        text = "HLS",
        color = Tokens.GreenText,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tokens.GreenDim)
            .border(0.9.dp, Color(0x8834C759), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun EpisodeTile(
    number: Int,
    title: String,
    watched: Boolean,
    isNetdisk: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(140), label = "epScale")
    val bg = when {
        focused -> Color.White
        watched -> Tokens.Accent.copy(alpha = 0.28f)
        else -> Color(0x14FFFFFF)
    }
    val borderColor = when {
        focused -> Color.White
        watched -> Tokens.Accent.copy(alpha = 0.85f)
        else -> Color(0x29FFFFFF)
    }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(Tokens.Corner.tile))
            .background(bg)
            .border(
                if (focused) 2.4.dp else if (watched) 1.4.dp else 1.dp,
                borderColor,
                RoundedCornerShape(Tokens.Corner.tile)
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(showRing = false, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (watched) {
                Text("▶", color = if (focused) Tokens.Accent else Color(0xFF7EC0FF), fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "EP ${number.toString().padStart(2, '0')}",
                color = if (focused) Color(0xFF3E4A5C) else Color(0x9EFFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            Spacer(Modifier.weight(1f))
            if (watched) {
                Text(
                    "上次观看",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Tokens.Accent)
                        .padding(horizontal = 7.dp, vertical = 1.5.dp)
                )
            } else if (isNetdisk) {
                Text("4K", color = Tokens.AmberText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            text = title,
            color = if (focused) Color(0xFF0A0F18) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 全屏选集：40 集以上的剧不再靠详情页里挤 6 行。 */
@Composable
private fun EpisodeSheet(
    title: String,
    line: PlayLine,
    resumeUrl: String,
    onPlay: (Episode) -> Unit,
    onDismiss: () -> Unit
) {
    val perGroup = 30
    val count = line.episodes.size
    val resumeIndex = line.episodes.indexOfFirst { it.url == resumeUrl }
    var group by remember(line, resumeUrl) { mutableIntStateOf(resumeIndex.coerceAtLeast(0) / perGroup) }
    var reversed by remember(line) { mutableStateOf(false) }
    val start = group * perGroup
    val indices = (start until min(count, start + perGroup)).let { if (reversed) it.reversed().toList() else it.toList() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(24.dp))
                .background(Tokens.PanelSolid)
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
                .padding(28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${line.name} · 共 ${line.episodes.size} 集", color = Tokens.TextDim, fontSize = 14.sp)
                }
                TvPill(text = "关闭", glass = true, horizontalPadding = 16.dp, verticalPadding = 8.dp, onClick = onDismiss)
            }
            Spacer(Modifier.height(18.dp))
            val groupCount = (count + perGroup - 1) / perGroup
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    TvPill(if (reversed) "倒序" else "正序", glass = true, onClick = { reversed = !reversed })
                }
                if (groupCount > 1) items(groupCount) { index ->
                    TvPill("${index * perGroup + 1}–${min(count, (index + 1) * perGroup)}",
                        selected = group == index, glass = true, onClick = { group = index })
                }
            }
            Spacer(Modifier.height(12.dp))
            val state = rememberLazyGridState()
            LaunchedEffect(group, reversed) {
                val index = indices.indexOf(resumeIndex).coerceAtLeast(0)
                if (indices.isNotEmpty()) state.scrollToItem(index)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                state = state,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(state)
            ) {
                itemsIndexed(indices, key = { _, index -> index }) { _, index ->
                    val ep = line.episodes[index]
                    EpisodeTile(
                        number = index + 1,
                        title = ep.name,
                        watched = resumeUrl.isNotBlank() && ep.url == resumeUrl,
                        isNetdisk = detectDrive(line.name) != null,
                        onClick = { onPlay(ep) }
                    )
                }
            }
        }
    }
}

/** 线路名里出现这些词，基本就是网盘直链（夸克 / UC / 阿里 / 百度 / 115 / AList）。 */
private fun detectDrive(name: String): String? {
    val lower = name.lowercase()
    val keys = listOf("夸克", "quark", "uc", "阿里", "ali", "百度", "baidu", "115", "alist", "云盘", "网盘", "4k")
    return keys.firstOrNull { lower.contains(it) }
}

private fun fmtMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
