package com.nextmovie.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.poster.PosterUrlRules
import com.nextmovie.ui.theme.Tokens

/**
 * 「继续观看」卡片：横版缩略图 + 琥珀进度条 + 集名。
 * 用横版而不是竖版，是为了和下方的海报行在视觉上拉开层次。
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
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = tween(Tokens.MotionMs, easing = FastOutSlowInEasing),
        label = "continueScale"
    )
    val shape = RoundedCornerShape(Tokens.Corner.card)

    Column(
        modifier = modifier
            .width(184.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) Tokens.SurfaceHigh else Tokens.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Tokens.Accent else Tokens.Stroke,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .mouseFocusable()
            .tvClickable(onClick = onClick, corner = Tokens.Corner.card, showRing = false)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(102.dp)) {
            AsyncImage(
                model = posterModel(context, posterUrl, detail = false),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xCC0B0C10))
                    )
            )
            if (entry.progress > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(entry.progress)
                        .height(3.dp)
                        .background(Tokens.Accent)
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = entry.name,
                color = Tokens.Text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(entry.episodeName.ifBlank { "上次观看" })
                    if (entry.progress > 0.01f) append("  ·  ${(entry.progress * 100).toInt()}%")
                },
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
