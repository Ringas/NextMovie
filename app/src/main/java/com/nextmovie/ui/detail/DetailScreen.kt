@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nextmovie.ui.detail

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.nextmovie.ui.common.tvClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Episode
import com.nextmovie.core.model.PlayLine
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.ui.common.mouseFocusable
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.common.posterModel
import com.nextmovie.ui.theme.Tokens

/**
 * 详情页（10-foot 版式）
 *
 * 左栏：海报 + 主操作 + 换源；右栏：片名 / 元信息胶囊 / 简介 / 线路 / 多行选集网格。
 * 相比首版的单行选集，网格让 40+ 集的剧不必横向拉几屏，一屏能看全并可方向键直达。
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
    onBack: () -> Unit
) {
    val screenStartedAt = remember { System.nanoTime() }
    val context = LocalContext.current
    var agg by remember { mutableStateOf<AppRepository.AggregatedDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var lineIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("正在聚合全网来源…") }
    var manual by remember { mutableStateOf(false) }
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var reload by remember { mutableIntStateOf(0) }

    // 进详情页就自动聚合：第一个回来的源先渲染，全部回来后按画质/成功率重排，默认选最优
    LaunchedEffect(initial.id, site.key, reload) {
        loading = true
        hint = "正在聚合全网来源…"
        agg = null
        error = null
        sourceIndex = 0
        lineIndex = 0
        manual = false
        val result = runCatching {
            repo.aggregateDetail(site, initial) { partial ->
                if (agg == null && partial.sources.isNotEmpty()) {
                    android.util.Log.i(
                        "NextMovieTiming",
                        "detail-partial ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms sources=${partial.sources.size}"
                    )
                    agg = partial
                    lineIndex = partial.best?.bestLineIndex ?: 0
                    loading = false
                    hint = "已找到「${partial.best?.site?.name ?: site.name}」，继续比对其它来源…"
                }
            }
        }
        result.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            error = com.nextmovie.core.net.FriendlyError.describe(it)
            loading = false
        }
        val full = result.getOrNull()
        if (full == null || full.sources.isEmpty()) {
            loading = false
            if (agg == null) error = "所有站点都没有返回这部片的可用线路"
        } else {
            android.util.Log.i(
                "NextMovieTiming",
                "detail-ready ${(System.nanoTime() - screenStartedAt) / 1_000_000}ms sources=${full.sources.size}"
            )
            val previousBest = agg?.best?.site?.key
            agg = full
            val best = full.best
            if (!manual) {
                sourceIndex = 0
                lineIndex = best?.bestLineIndex ?: 0
            }
            loading = false
            hint = buildString {
                append("已聚合 ${full.sources.size} 个来源 · 自动选中「${best?.site?.name ?: site.name}」")
                best?.badge?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                if (previousBest != null && previousBest != best?.site?.key) append("（已自动切到更优源）")
            }
        }
    }

    // 海报增强只作用在最优源，避免为了一部片打十几次 TMDB/B站
    LaunchedEffect(agg?.best?.site?.key, agg?.best?.detail?.vod?.id) {
        val currentAgg = agg ?: return@LaunchedEffect
        val best = currentAgg.best ?: return@LaunchedEffect
        val enriched = repo.enrichPoster(best.detail.vod, detail = true)
        if (enriched !== best.detail.vod) {
            agg = currentAgg.copy(
                sources = currentAgg.sources.map {
                    if (it.site.key == best.site.key) it.copy(detail = it.detail.copy(vod = enriched)) else it
                }
            )
        }
    }

    val sources = agg?.sources.orEmpty()
    val safeSourceIndex = sourceIndex.coerceIn(0, sources.lastIndex.coerceAtLeast(0))
    val activeSource = sources.getOrNull(safeSourceIndex)
    val active: Site = activeSource?.site ?: site
    val vod = activeSource?.detail?.vod ?: initial
    val lines: List<PlayLine> = activeSource?.detail?.lines.orEmpty()
    val line: PlayLine? = lines.getOrNull(lineIndex)
        ?: activeSource?.let { src -> lines.getOrNull(src.bestLineIndex) }
        ?: lines.firstOrNull()


    // 本机看过这部片且没看完时，给一个一键续播入口
    LaunchedEffect(lines.isNotEmpty()) {
        if (lines.isNotEmpty()) { kotlinx.coroutines.delay(100); runCatching { playFocus.requestFocus() } }
    }

    // 收藏状态跟着「当前生效源 + 影片 id」走：换源后按钮会自动反映该源是否已收藏
    var isFavorite by remember { mutableStateOf(repo.favorites.contains(active.key, vod.id)) }
    LaunchedEffect(active.key, vod.id, agg) {
        isFavorite = repo.favorites.contains(active.key, vod.id)
    }

    // 进度按「片名」取，所以换了源/线路仍然是同一部片的进度
    val resumeEntry = remember(vod.name, agg) {
        (repo.history.findByName(vod.name) ?: repo.history.find(active.key, vod.id))
            ?.takeIf { it.resumable && it.episodeUrl.isNotBlank() }
    }

    /** 把「当前这一集」在所有已聚合源里的对应集收成可切换清单，顺序与自动优选一致。 */
    fun slotsOf(episodeIndex: Int, episode: Episode): List<com.nextmovie.core.model.PlaySlot> {
        val out = ArrayList<com.nextmovie.core.model.PlaySlot>(sources.size)
        sources.forEach { src ->
            val best = src.detail.lines.getOrNull(src.bestLineIndex) ?: src.detail.lines.firstOrNull() ?: return@forEach
            val ep = best.episodes.getOrNull(episodeIndex)
                ?: best.episodes.firstOrNull { it.name == episode.name }
                ?: return@forEach
            out += com.nextmovie.core.model.PlaySlot(
                siteKey = src.site.key,
                siteName = src.site.name,
                lineName = best.name,
                badge = src.badge,
                score = src.score,
                episodeName = ep.name,
                episodeUrl = ep.url
            )
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
        val ep = target.episodes.getOrNull(idx)
            ?: Episode(entry.episodeName.ifBlank { "继续观看" }, entry.episodeUrl)
        onPlay(srcSite, srcVod, target, ep, entry.positionMs, slotsOf(idx.coerceAtLeast(0), ep))
    }

    Box(modifier = Modifier.fillMaxSize().background(Tokens.Bg)) {
        AsyncImage(
            model = remember(vod.displayBackdrop) { posterModel(context, vod.displayBackdrop, detail = true) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.30f,
            modifier = Modifier.fillMaxSize()
        )
        // 左重右轻的横向压暗 + 上下渐隐，让文字区永远有足够对比度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20B0C10),
                        0.45f to Color(0xC00B0C10),
                        1f to Color(0x990B0C10)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xE60B0C10),
                        0.22f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color(0xF20B0C10)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "NEXT MOVIE",
                        color = Tokens.Text,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 3.sp
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "DETAIL",
                        color = Tokens.Accent,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = Tokens.Type.micro,
                            letterSpacing = Tokens.Type.MICRO_TRACK.sp
                        ),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Spacer(Modifier.width(24.dp))
                Text(
                    text = "‹ 返回",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Corner.pill))
                        .background(Tokens.Surface)
                        .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                        .mouseFocusable()
                        .tvClickable(onClick = onBack, corner = Tokens.Corner.pill)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(10.dp))
                FavoritePill(
                    active = isFavorite,
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
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (sources.isEmpty()) site.name else "${active.name} · ${sources.size} 源 / ${lines.size} 线路",
                    color = Tokens.TextFaint,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(22.dp))

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // —— 左栏 ——
                Column(modifier = Modifier.width(192.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Box(
                        modifier = Modifier
                            .width(192.dp)
                            .height(264.dp)
                            .clip(RoundedCornerShape(Tokens.Radius))
                            .background(Tokens.SurfaceHigh)
                            .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Radius))
                    ) {
                        AsyncImage(
                            model = remember(vod.displayPic) { posterModel(context, vod.displayPic, detail = true) },
                            contentDescription = vod.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = "▶ 播放",
                        modifier = Modifier.focusRequester(playFocus),
                        enabled = line != null && line.episodes.isNotEmpty(),
                        onClick = {
                            line?.episodes?.firstOrNull()?.let { first ->
                                onPlay(active, vod, line, first, 0L, slotsOf(0, first))
                            }
                        }
                    )
                    if (resumeEntry != null) {
                        Spacer(Modifier.height(10.dp))
                        val pct = (resumeEntry.progress * 100).toInt()
                        GhostButton(
                            label = "继续观看 · ${resumeEntry.episodeName.ifBlank { "上次位置" }}${if (pct > 0) "  $pct%" else ""}",
                            onClick = { resume() }
                        )
                    }                }

                Spacer(Modifier.width(36.dp))

                // —— 右栏 ——
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Text(
                        text = vod.name,
                        color = Tokens.Text,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 44.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    val chips = buildList {
                        vod.displayYear.takeIf { it.isNotBlank() }?.let { add(it) }
                        vod.area.takeIf { it.isNotBlank() }?.let { add(it) }
                        vod.typeName.takeIf { it.isNotBlank() }?.let { add(it) }
                        vod.tmdb?.voteAverage?.takeIf { it > 0.0 }?.let { add("TMDB %.1f".format(it)) }
                        if (line != null && line.episodes.isNotEmpty()) add("${line.episodes.size} 集")
                        vod.remark.takeIf { it.isNotBlank() }?.let { add(it.take(14)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { MetaChip(it) }
                    }

                    val overview = vod.displayOverview
                    if (overview.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = overview,
                            color = Tokens.TextDim,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 28.sp
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    when {
                        loading -> Text(
                            "正在读取详情…",
                            color = Tokens.TextDim,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        error != null -> ErrorCard(message = error!!, onRetry = { reload++ })
                        lines.isEmpty() -> Text(
                            "暂无可用播放线路",
                            color = Tokens.TextDim,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> {
                            if (hint.isNotBlank()) {
                                Text(
                                    text = hint,
                                    color = Tokens.Accent,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                            }
                            // 已聚合的源：按「画质 + 历史成功率」排序，第一个就是自动优选的
                            if (sources.size > 1) {
                                SourceChips(
                                    sources = sources,
                                    selected = safeSourceIndex,
                                    onPick = { picked ->
                                        manual = true
                                        sourceIndex = picked
                                        lineIndex = sources[picked].bestLineIndex
                                    }
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            if (lines.size > 1) {
                                LineTabs(lines = lines, selected = lineIndex, onSelect = { lineIndex = it })
                                Spacer(Modifier.height(14.dp))
                            }
                            EpisodeGrid(
                                line = line!!,
                                onPlay = { ep ->
                                    val idx = line.episodes.indexOf(ep).coerceAtLeast(0)
                                    onPlay(active, vod, line, ep, 0L, slotsOf(idx, ep))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text = text,
        color = Tokens.TextDim,
        fontSize = Tokens.Type.badge,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Corner.pill))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) Tokens.Accent else Tokens.SurfaceHigh)
            .mouseFocusable()
            .tvClickable(enabled = enabled, onClick = onClick, corner = Tokens.Corner.pill, scaleOnFocus = 1.02f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) Tokens.OnAccent else Tokens.TextDim,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FavoritePill(active: Boolean, onClick: () -> Unit) {
    Text(
        text = if (active) "★ 已收藏" else "☆ 收藏",
        color = if (active) Tokens.Accent else Tokens.TextDim,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Corner.pill))
            .background(if (active) Tokens.AccentDim else Tokens.Surface)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) Tokens.Accent else Tokens.Stroke,
                shape = RoundedCornerShape(Tokens.Corner.pill)
            )
            .mouseFocusable()
            .tvClickable(onClick = onClick, corner = Tokens.Corner.pill)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Tokens.TextDim,
        fontSize = Tokens.Type.caption,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Corner.pill))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
            .mouseFocusable()
            .tvClickable(onClick = onClick, corner = Tokens.Corner.pill)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Corner.panel))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.panel))
            .padding(18.dp)
    ) {
        Text(message, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "重试",
            color = Tokens.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.Corner.pill))
                .background(Tokens.AccentDim)
                .mouseFocusable()
                .tvClickable(onClick = onRetry, corner = Tokens.Corner.pill)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SourceChips(
    sources: List<AppRepository.SourceDetail>,
    selected: Int,
    onPick: (Int) -> Unit
) {
    val state = rememberLazyListState()
    Column {
        Text(
            text = "来源 · 已按画质与历史成功率排序",
            color = Tokens.Accent,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.06.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.mouseWheel(state, horizontal = true)
        ) {
            itemsIndexed(sources) { index, src ->
                val isActive = index == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Corner.pill))
                        .background(if (isActive) Tokens.AccentDim else Tokens.Surface)
                        .border(
                            width = if (isActive) 2.dp else 1.dp,
                            color = if (isActive) Tokens.Accent else Tokens.Stroke,
                            shape = RoundedCornerShape(Tokens.Corner.pill)
                        )
                        .mouseFocusable()
                        .tvClickable(onClick = { onPick(index) }, corner = Tokens.Corner.pill)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    if (index == 0) {
                        Text(
                            text = "最优",
                            color = Tokens.OnAccent,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Tokens.Accent)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = "${if (isActive) "● " else ""}${src.site.name}",
                        color = if (isActive) Tokens.Accent else Tokens.TextDim,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = src.badge,
                        color = Tokens.TextFaint,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro)
                    )
                }
            }
        }
    }
}

@Composable
private fun LineTabs(lines: List<PlayLine>, selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.mouseWheel(state, horizontal = true)
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == selected
            Text(
                text = line.name,
                color = if (active) Tokens.Accent else Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(if (active) Tokens.AccentDim else Tokens.Surface)
                    .border(
                        width = 1.dp,
                        color = if (active) Tokens.Accent.copy(alpha = 0.55f) else Tokens.Stroke,
                        shape = RoundedCornerShape(Tokens.Corner.pill)
                    )
                    .mouseFocusable()
                    .tvClickable(onClick = { onSelect(index) }, corner = Tokens.Corner.pill)
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * 选集网格：自适应列宽 + 固定高度滚动区。
 * 40 集以上的剧不再需要横向拉好几屏，方向键上下就能跳行。
 */
@Composable
private fun EpisodeGrid(line: PlayLine, onPlay: (Episode) -> Unit) {
    val state = rememberLazyGridState()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                text = "选集",
                color = Tokens.Accent,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.06.sp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "共 ${line.episodes.size} 集",
                color = Color(0x66FFFFFF),
                style = MaterialTheme.typography.labelSmall
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .mouseWheel(state)
        ) {
            itemsIndexed(line.episodes) { _, ep ->
                Box(
                    modifier = Modifier
                        .height(56.dp)
                        .clip(RoundedCornerShape(Tokens.Radius))
                        .background(Tokens.Surface)
                        .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Radius))
                        .mouseFocusable()
                        .tvClickable(onClick = { onPlay(ep) }, corner = Tokens.Radius, scaleOnFocus = 1.02f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ep.name,
                        color = Tokens.Text,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
