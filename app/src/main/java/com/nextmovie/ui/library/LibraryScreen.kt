package com.nextmovie.ui.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
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
 * 片库（对齐 Marco `_buildLibraryView`）
 *
 * 顶部两枚分段胶囊「观看历史 / 我的收藏」+ 右侧清空操作，下面是海报网格：
 * 历史卡带进度与「继续」胶囊，收藏卡直接进详情。
 */
@Composable
fun LibraryScreen(
    repo: AppRepository,
    onOpenDetail: (Site, Vod) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    var segment by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val history = remember(version, segment) { repo.history.list() }
    val favorites = remember(version, segment) { repo.favorites.list() }
    val columns = when {
        LocalConfiguration.current.screenWidthDp >= 1500 -> 6
        else -> 4
    }
    val gridState = rememberLazyGridState()

    Column(modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvPill(
                text = "观看历史",
                selected = segment == 0,
                horizontalPadding = 22.dp,
                verticalPadding = 11.dp,
                onClick = { segment = 0 }
            )
            Spacer(Modifier.width(12.dp))
            TvPill(
                text = "我的收藏",
                selected = segment == 1,
                horizontalPadding = 22.dp,
                verticalPadding = 11.dp,
                onClick = { segment = 1 }
            )
            Spacer(Modifier.weight(1f))
            if (segment == 0 && history.isNotEmpty()) {
                TvPill(
                    text = "清空观看历史",
                    destructive = true,
                    horizontalPadding = 18.dp,
                    verticalPadding = 9.dp,
                    onClick = {
                        repo.history.clear()
                        version++
                    }
                )
            }
            if (segment == 1 && favorites.isNotEmpty()) {
                TvPill(
                    text = "清空收藏",
                    destructive = true,
                    horizontalPadding = 18.dp,
                    verticalPadding = 9.dp,
                    onClick = {
                        repo.favorites.clear()
                        version++
                    }
                )
            }
        }
        Spacer(Modifier.height(22.dp))

        if (segment == 0 && history.isEmpty()) {
            EmptyLibrary("暂无观看记录")
        } else if (segment == 1 && favorites.isEmpty()) {
            EmptyLibrary("还没有收藏的影片")
        } else if (segment == 0) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(gridState)
            ) {
                items(history, key = { it.key }) { entry ->
                    val percent = (entry.progress * 100).toInt().coerceIn(0, 100)
                    val badge = if (entry.episodeName.isBlank()) "$percent%" else "${entry.episodeName} · $percent%"
                    Column {
                        PosterCard(
                            vod = Vod(
                                id = entry.vodId,
                                name = entry.name,
                                pic = entry.pic,
                                remark = badge
                            ),
                            onClick = { onResume(entry) },
                            onFocused = {},
                            fillWidth = true,
                            progress = entry.progress
                        )
                        Spacer(Modifier.height(8.dp))
                        TvPill(
                            text = "继续观看",
                            glass = true,
                            horizontalPadding = 14.dp,
                            verticalPadding = 6.dp,
                            onClick = { onResume(entry) }
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(gridState)
            ) {
                items(favorites, key = { it.key }) { entry ->
                    val site = repo.siteByKey(entry.siteKey)
                    PosterCard(
                        vod = Vod(id = entry.vodId, name = entry.name, pic = entry.pic, remark = entry.remark),
                        onClick = { if (site != null) onOpenDetail(site, vodOf(entry)) },
                        onFocused = {},
                        fillWidth = true
                    )
                }
            }
        }
    }
}

private fun vodOf(entry: com.nextmovie.core.history.FavoriteEntry): Vod =
    Vod(id = entry.vodId, name = entry.name, pic = entry.pic, remark = entry.remark, year = entry.year)

@Composable
private fun EmptyLibrary(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Tokens.TextDim, fontSize = 17.sp)
    }
}
