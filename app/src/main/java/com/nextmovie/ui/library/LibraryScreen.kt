package com.nextmovie.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.theme.Tokens

/**
 * 片库（观看历史 / 我的收藏）
 *
 * 信息层级改成「标题 → 分段标签 → 海报网格」：标题给当前段落定性并带条数，
 * 分段标签只负责切换，清空动作放到标题行右侧并降权（danger 只留描边色）。
 */
@Composable
fun LibraryScreen(
    repo: AppRepository,
    onOpenDetail: (Site, Vod) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    var segment by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val history = remember(version, segment) { repo.history.list() }
    val favorites = remember(version, segment) { repo.favorites.list() }
    val columns = when {
        LocalConfiguration.current.screenWidthDp >= 1500 -> 6
        LocalConfiguration.current.screenWidthDp >= 1100 -> 5
        else -> 4
    }
    val gridState = rememberLazyGridState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var restoreKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (restoreKey != null) {
            kotlinx.coroutines.delay(80)
            runCatching { focus.requestFocus() }
        }
    }

    Column(modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (segment == 0) "观看历史" else "我的收藏",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (segment == 0) {
                        if (history.isEmpty()) "看过的片子会留在这里，随时接着播" else "共 ${history.size} 部 · 选中卡片即从上次位置继续"
                    } else {
                        if (favorites.isEmpty()) "收藏的片子会留在这里，点开进详情换源" else "共 ${favorites.size} 部 · 点开进详情页挑线路"
                    },
                    color = Tokens.TextFaint,
                    fontSize = 13.5.sp
                )
            }
            val hasData = if (segment == 0) history.isNotEmpty() else favorites.isNotEmpty()
            if (hasData) {
                TvPill(
                    text = if (segment == 0) "清空历史" else "清空收藏",
                    glass = true,
                    horizontalPadding = 16.dp,
                    verticalPadding = 8.dp,
                    onClick = {
                        if (segment == 0) repo.history.clear() else repo.favorites.clear()
                        version++
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentTab("观看历史", segment == 0) { segment = 0 }
            Spacer(Modifier.width(10.dp))
            SegmentTab("我的收藏", segment == 1) { segment = 1 }
        }
        Spacer(Modifier.height(20.dp))

        if (segment == 0 && history.isEmpty()) {
            EmptyLibrary("还没有看过片子", "去首页挑一部，播到一半回来这里就能接着看", onJumpHome = null)
        } else if (segment == 1 && favorites.isEmpty()) {
            EmptyLibrary("收藏夹还是空的", "详情页的「收藏」按一下，片子就会出现在这里", onJumpHome = null)
        } else if (segment == 0) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(gridState)
            ) {
                items(history, key = { it.key }) { entry ->
                    PosterCard(
                        vod = Vod(
                            id = entry.vodId,
                            name = entry.name,
                            pic = entry.pic,
                            remark = historyBadge(entry)
                        ),
                        onClick = { onResume(entry) },
                        onFocused = {},
                        fillWidth = true,
                        progress = entry.progress
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(gridState)
            ) {
                items(favorites, key = { it.key }) { entry ->
                    val site = repo.siteByKey(entry.siteKey)
                    PosterCard(
                        vod = Vod(id = entry.vodId, name = entry.name, pic = entry.pic, remark = entry.remark),
                        onClick = {
                            if (site != null) {
                                restoreKey = entry.key
                                onOpenDetail(site, vodOf(entry))
                            }
                        },
                        modifier = if (entry.key == restoreKey) Modifier.focusRequester(focus) else Modifier,
                        onFocused = {},
                        fillWidth = true
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentTab(text: String, selected: Boolean, onClick: () -> Unit) {
    TvPill(
        text = text,
        selected = selected,
        quietSelected = true,
        horizontalPadding = 20.dp,
        verticalPadding = 10.dp,
        onClick = onClick
    )
}

/**
 * 历史卡上的角标只回答「看到哪了」：集名 / 进度，不再把
 * `[5.8GB]蚊子杯：最后一役.mp4` 这种源站文件名糊在封面上。
 */
private fun historyBadge(entry: HistoryEntry): String {
    val percent = (entry.progress * 100).toInt().coerceIn(0, 100)
    val label = com.nextmovie.ui.common.HistoryText.episodeLabel(entry.episodeName, entry.name)
    return when {
        label.isNotBlank() && percent > 0 -> "$label · 已看 $percent%"
        label.isNotBlank() -> label
        percent > 0 -> "已看 $percent%"
        else -> "看到一半"
    }
}

private fun vodOf(entry: com.nextmovie.core.history.FavoriteEntry): Vod =
    Vod(id = entry.vodId, name = entry.name, pic = entry.pic, remark = entry.remark, year = entry.year)

@Composable
private fun EmptyLibrary(title: String, subtitle: String, onJumpHome: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x1FFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Tokens.Accent.copy(alpha = 0.55f))
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(title, color = Tokens.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                color = Tokens.TextGhost,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            if (onJumpHome != null) {
                Spacer(Modifier.height(16.dp))
                TvPill(text = "回首页挑片", glass = true, onClick = onJumpHome)
            }
        }
    }
}
