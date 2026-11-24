package com.nextmovie.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
    onPlay: (source: Site, line: PlayLine, episode: Episode, startMs: Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hit by remember { mutableStateOf<AppRepository.DetailHit?>(null) }
    var detail by remember { mutableStateOf<VodDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lineIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("正在读取详情…") }
    var forced by remember { mutableStateOf<Site?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(initial.id, site.key, forced?.key, reload) {
        loading = true
        hint = "正在读取详情…"
        hit = null
        detail = null
        error = null
        lineIndex = 0
        runCatching {
            com.nextmovie.core.net.Retry.once { repo.detailPreferring(forced ?: site, initial) }
        }
            .onSuccess { found ->
                hit = found
                detail = found?.detail?.let {
                    it.copy(vod = repo.enrichPoster(it.vod, detail = true))
                }
                when {
                    found == null -> error = "所有站点都没有返回这部片的详情"
                    found.detail.lines.isEmpty() -> error = "「${found.site.name}」没有给出播放线路"
                    found.borrowed -> hint = "「${site.name}」没有详情，已改用「${found.site.name}」的线路"
                    else -> hint = ""
                }
            }
            .onFailure { error = com.nextmovie.core.net.FriendlyError.describe(it) }
        loading = false
    }

    val active: Site = hit?.site ?: site
    val vod = detail?.vod ?: initial
    val lines: List<PlayLine> = detail?.lines.orEmpty()
    val line = lines.getOrElse(lineIndex) { lines.firstOrNull() }

    // 本机看过这部片且没看完时，给一个一键续播入口
    val resumeEntry = remember(vod.id, detail) {
        repo.history.find(site.key, vod.id)?.takeIf { it.resumable && it.episodeUrl.isNotBlank() }
    }

    fun resume() {
        val entry = resumeEntry ?: return
        val target = lines.firstOrNull { it.name == entry.playFrom } ?: line ?: return
        val ep = target.episodes.firstOrNull { it.url == entry.episodeUrl }
            ?: Episode(entry.episodeName.ifBlank { "继续观看" }, entry.episodeUrl)
        onPlay(active, target, ep, entry.positionMs)
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
                        0f to Color(0xF207080B),
                        0.42f to Color(0xC007080B),
                        1f to Color(0x8A07080B)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xE607080B),
                        0.22f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color(0xF207080B)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "‹ 返回",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1AFFFFFF))
                        .mouseFocusable()
                        .clickable { onBack() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (hit == null) site.name else "${active.name}  ·  ${lines.size} 条线路",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.height(22.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                // —— 左栏 ——
                Column(modifier = Modifier.width(216.dp)) {
                    Box(
                        modifier = Modifier
                            .width(216.dp)
                            .height(290.dp)
                            .clip(RoundedCornerShape(Tokens.Corner.card))
                            .background(Tokens.SurfaceHigh)
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
                        enabled = line != null && line.episodes.isNotEmpty(),
                        onClick = {
                            line?.episodes?.firstOrNull()?.let { onPlay(active, line, it, 0L) }
                        }
                    )
                    if (resumeEntry != null) {
                        Spacer(Modifier.height(10.dp))
                        val pct = (resumeEntry.progress * 100).toInt()
                        GhostButton(
                            label = "继续观看 · ${resumeEntry.episodeName.ifBlank { "上次位置" }}${if (pct > 0) "  $pct%" else ""}",
                            onClick = { resume() }
                        )
                    }
                }

                Spacer(Modifier.width(36.dp))

                // —— 右栏 ——
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vod.name,
                        color = Tokens.Text,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 46.sp
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            lineHeight = 21.sp
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
                            // 换源：其它站点也有这部片，点一下直接换线路（比返回重搜快得多）
                            val alts = buildList {
                                if (hit?.site != null) add(hit!!.site to true)
                                hit?.alternatives?.forEach { add(it to false) }
                            }
                            if (alts.size > 1) {
                                SourceRow(
                                    sources = alts,
                                    onPick = { s -> if (s.key != active.key) forced = s }
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            if (lines.size > 1) {
                                LineTabs(lines = lines, selected = lineIndex, onSelect = { lineIndex = it })
                                Spacer(Modifier.height(14.dp))
                            }
                            EpisodeGrid(
                                line = line!!,
                                onPlay = { ep -> onPlay(active, line, ep, 0L) }
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
        color = Tokens.Text,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Tokens.Surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Tokens.Accent else Tokens.SurfaceHigh)
            .mouseFocusable()
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) Tokens.OnAccent else Tokens.TextDim,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Tokens.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x26FFFFFF))
            .mouseFocusable()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    )
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Corner.card))
            .background(Tokens.Surface)
            .padding(18.dp)
    ) {
        Text(message, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
        // 详情为什么是空的：把传进去的 id 和插件返回原文摊出来，一眼能分辨是 id 传错还是 Init 没解开二级 dex
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val raw = remember(message) { com.nextmovie.core.spider.DetailRaw.read(ctx) }
        if (raw.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "插件返回：$raw",
                color = Tokens.TextDim.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "重试",
            color = Tokens.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.AccentDim)
                .mouseFocusable()
                .clickable { onRetry() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SourceRow(sources: List<Pair<Site, Boolean>>, onPick: (Site) -> Unit) {
    val state = rememberLazyListState()
    Column {
        Text(
            text = "换源",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.mouseWheel(state, horizontal = true)
        ) {
            itemsIndexed(sources) { _, item ->
                val (s, current) = item
                Text(
                    text = if (current) "● ${s.name}" else s.name,
                    color = if (current) Tokens.Accent else Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (current) Tokens.AccentDim else Color(0x14FFFFFF))
                        .mouseFocusable()
                        .clickable { onPick(s) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
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
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) Tokens.AccentDim else Color(0x14FFFFFF))
                    .mouseFocusable()
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
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
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "共 ${line.episodes.size} 集",
                color = Color(0x66FFFFFF),
                style = MaterialTheme.typography.labelSmall
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(154.dp)
                .mouseWheel(state)
        ) {
            itemsIndexed(line.episodes) { _, ep ->
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Tokens.Surface)
                        .mouseFocusable()
                        .clickable { onPlay(ep) },
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
