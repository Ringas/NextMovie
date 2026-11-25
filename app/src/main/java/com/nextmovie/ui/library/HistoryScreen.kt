package com.nextmovie.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nextmovie.core.AppRepository
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.common.posterModel
import com.nextmovie.ui.common.ScreenTopBar
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens

/**
 * 独立观看记录页（V2）：首页那条「继续观看」横滑只放 10 条，
 * 完整记录在这里按时间倒序，可续播、可单条移除、可整体清空。
 */
@Composable
fun HistoryScreen(
    repo: AppRepository,
    onResume: (HistoryEntry) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    var entries by remember { mutableStateOf(repo.history.list()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .padding(Tokens.ScreenPadding)
    ) {
        ScreenTopBar(title = "", onBack = onBack, trailing = "${entries.size} 条记录")
        Spacer(Modifier.height(6.dp))

        Spacer(Modifier.height(14.dp))
        Text(
            text = "观看记录",
            color = Tokens.Text,
            fontSize = Tokens.Type.display,
            fontWeight = FontWeight.SemiBold
        )
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
                Text("还没有观看记录", color = Tokens.Text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "播放任意一集，进度会自动记在这里，下次直接从上次的位置接着看。",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(listState)
            ) {
                items(entries, key = { it.key }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onPlay = { onResume(entry) },
                        onRemove = {
                            repo.history.remove(entry.siteKey, entry.vodId)
                            entries = repo.history.list()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    val shape = RoundedCornerShape(Tokens.Radius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, shape)
            .tvClickable(onClick = onPlay)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(54.dp)
                .fillMaxHeight()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.SurfaceHigh)
        ) {
            AsyncImage(
                model = posterModel(androidx.compose.ui.platform.LocalContext.current, entry.pic, detail = false),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = Tokens.Text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            val subtitle = buildList {
                if (entry.remark.isNotBlank()) add(entry.remark.take(18))
                if (entry.episodeName.isNotBlank()) add(entry.episodeName)
                if (entry.playFrom.isNotBlank()) add("源 · ${entry.playFrom}")
            }.joinToString(" · ")
            Text(
                text = subtitle.ifBlank { entry.siteKey },
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val pct = (entry.progress * 100).toInt()
            if (pct > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Tokens.Stroke)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(entry.progress)
                                .height(3.dp)
                                .background(Tokens.Accent)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("$pct%", color = Tokens.Accent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeAgo(entry.updatedAt),
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "移除",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                    .tvClickable(onClick = onRemove, corner = Tokens.Corner.pill)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

private fun timeAgo(ms: Long): String {
    if (ms <= 0L) return ""
    val delta = System.currentTimeMillis() - ms
    if (delta < 0) return "刚刚"
    val min = delta / 60_000
    val hour = min / 60
    val day = hour / 24
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        hour < 24 -> "${hour} 小时前"
        day < 7 -> "${day} 天前"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            "%d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}
