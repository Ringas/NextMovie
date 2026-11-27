package com.nextmovie.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.poster.PosterUrlRules
import com.nextmovie.ui.theme.Tokens

/**
 * 继续观看横版卡（对齐 Marco TvWideContinueCard）
 *
 * 268×150 的 16:9 剧照 + 底部 5dp 进度条，标题 17sp，副标题带集名与百分比。
 * 焦点只做 1.04 —— 比海报卡更收敛，因为它在首屏是「次要信息」。
 */
@Composable
fun ContinueCard(
    entry: HistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val posterUrl = remember(entry.pic) { PosterUrlRules.upgrade(entry.pic, detail = false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "continueScale"
    )
    val pct = (entry.progress * 100).toInt().coerceIn(0, 100)
    val label = HistoryText.episodeLabel(entry.episodeName, entry.name)
    val sub = "${label.ifBlank { "继续播放" }} · 已看 $pct%"
    val outer = RoundedCornerShape(Tokens.Corner.card)
    val inner = RoundedCornerShape(Tokens.Corner.cardInner)

    Column(
        modifier = modifier
            .width(Tokens.WideWidth)
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 1f) }
            .onFocusChanged { focused = it.isFocused }
            .mouseFocusable()
            .tvClickable(onClick = onClick, corner = Tokens.Corner.card, showRing = true, scaleOnFocus = 1.05f)
    ) {
        Box(
            modifier = Modifier
                .width(Tokens.WideWidth)
                .height(Tokens.WideHeight)
                .clip(outer)
                .background(Tokens.PosterPlaceholder)
                .border(
                    width = if (focused) 2.5.dp else 1.1.dp,
                    color = if (focused) Color.White else Tokens.GlassStroke,
                    shape = outer
                )
                .padding(if (focused) 2.5.dp else 1.1.dp)
        ) {
            if (posterUrl.isBlank()) {
                Box(Modifier.fillMaxSize().clip(inner).background(Tokens.PosterPlaceholder))
            } else {
                AsyncImage(
                    model = posterModel(context, posterUrl, detail = false),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(inner)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(Color(0x9E000000))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entry.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Tokens.Accent)
                )
            }
        }
        Box(Modifier.height(9.dp))
        Text(
            text = entry.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.height(2.dp))
        Text(
            text = sub,
            color = Tokens.TextDim,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
