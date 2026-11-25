package com.nextmovie.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.poster.PosterUrlRules
import com.nextmovie.ui.theme.DynamicAccent
import com.nextmovie.ui.theme.Tokens

/**
 * 「继续观看」卡片（tvOS 原型版）：320×180 横版图铺满，片名与集名压在图上，
 * 底部 3dp 断点进度条。聚焦时 1.06 推近 + 3dp 主色描边。
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
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(Tokens.MotionMs, easing = FastOutSlowInEasing),
        label = "continueScale"
    )
    val shape = RoundedCornerShape(Tokens.Corner.card)

    Column(
        modifier = modifier
            .width(Tokens.WideWidth)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(shape)
            .background(Tokens.SurfaceHigh)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Tokens.Accent else Tokens.Stroke,
                shape = shape
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) DynamicAccent.fromSeed(entry.name)
            }
            .mouseFocusable()
            .tvClickable(onClick = onClick, corner = Tokens.Corner.card, showRing = false)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(Tokens.WideHeight)) {
            if (posterUrl.isBlank()) {
                Box(Modifier.fillMaxSize().background(Tokens.SurfaceHigh))
            } else {
                AsyncImage(
                    model = posterModel(context, posterUrl, detail = false),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE60B0C10))
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            ) {
                Text(
                    text = entry.name,
                    color = Tokens.Text,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(entry.episodeName.ifBlank { "上次观看" })
                        if (entry.progress > 0.01f) append("  ·  ${(entry.progress * 100).toInt()}%")
                    },
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.progress > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x1FFFFFFF))
                ) {
                    Box(
                        Modifier.fillMaxWidth(entry.progress.coerceIn(0f, 1f)).height(3.dp).background(Tokens.Accent)
                    )
                }
            }
        }
    }
}
