package com.nextmovie.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.TvGridNavigation
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.common.ScreenTopBar
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens

/**
 * 收藏页（V2）：与首页同一套海报网格。收藏项记录「源 + 影片 id」，
 * 打开详情时回到收藏时的那条源，播放线路因此不会错位。
 */
@Composable
fun FavoritesScreen(
    repo: AppRepository,
    onOpenDetail: (Site, Vod) -> Unit,
    onBack: () -> Unit
) {
    val density = LocalDensity.current.density
    val gridState = rememberLazyGridState()
    var columns by remember { mutableStateOf(6) }
    val entries by remember { mutableStateOf(repo.favorites.list()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .padding(Tokens.ScreenPadding)
    ) {
        ScreenTopBar(title = "", onBack = onBack, trailing = "${entries.size} 部已收藏")
        Spacer(Modifier.height(6.dp))

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "收藏",
                color = Tokens.Text,
                fontSize = Tokens.Type.display,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = "在详情页按「☆ 收藏」加入，按「★ 已收藏」移出",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Radius))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Radius))
                    .padding(28.dp)
            ) {
                Text("还没有收藏", color = Tokens.Text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "挑一部想留着看的片子，进详情页按「☆ 收藏」，这里就会攒起来。",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { columns = TvGridNavigation.columns(it.width / density) }
                    .mouseWheel(gridState)
            ) {
                items(entries, key = { it.key }) { fav ->
                    val site = repo.sites.firstOrNull { it.runtimeKey == fav.siteKey }
                    PosterCard(
                        vod = Vod(
                            id = fav.vodId,
                            name = fav.name,
                            pic = fav.pic,
                            remark = fav.remark,
                            year = fav.year
                        ),
                        onClick = {
                            val target = site ?: repo.currentSite
                            if (target != null) {
                                onOpenDetail(target, Vod(id = fav.vodId, name = fav.name, pic = fav.pic, remark = fav.remark, year = fav.year))
                            }
                        },
                        onFocused = {}
                    )
                }
            }
        }
    }
}
