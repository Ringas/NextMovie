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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.theme.DynamicAccent
import com.nextmovie.ui.theme.Tokens

/**
 * 海报卡（tvOS 原型版）：14dp 大圆角 + 聚焦 1.075 轻推 + 3dp 主色描边与外发光。
 * 片名压在封面底部（电视墙只给一行的高度），角标贴在左上角。
 * 聚焦时把整屏主色切到这部作品的海报色相（见 [DynamicAccent]）。
 */
@Composable
fun PosterCard(
    vod: Vod,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Tokens.CardWidth,
    height: Dp = Tokens.CardHeight,
    progress: Float? = null
) {
    val context = LocalContext.current
    val url = remember(vod.displayPic, vod.pic) { vod.displayPic.ifBlank { vod.pic } }
    val model = remember(url) { posterModel(context, url, detail = false) }
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(vod.displayPic) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) Tokens.FocusScale else 1f,
        animationSpec = tween(Tokens.MotionMs, easing = FastOutSlowInEasing),
        label = "posterScale"
    )
    val shape = RoundedCornerShape(Tokens.Corner.card)

    Column(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .mouseFocusable()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    DynamicAccent.fromSeed(vod.id)
                    onFocused()
                }
            }
            .tvClickable(onClick = onClick, corner = Tokens.Corner.card, showRing = false)
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(shape)
                .background(Tokens.SurfaceHigh)
                .then(
                    if (focused) Modifier.border(3.dp, Tokens.Accent, shape)
                    else Modifier.border(1.dp, Tokens.Stroke, shape)
                )
                .then(if (focused) Modifier.tvFocusRing(corner = Tokens.Corner.card, visible = true) else Modifier)
        ) {
            if (url.isBlank() || imageFailed) {
                PosterPlaceholder(name = vod.name, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = model,
                    contentDescription = vod.name,
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (focused) {
                            Brush.verticalGradient(0.42f to Color.Transparent, 1f to Tokens.AccentDim.copy(alpha = 0.28f))
                        } else {
                            Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE60B0C10))
                        }
                    )
            )

            if (vod.remark.isNotBlank()) {
                Text(
                    text = vod.remark.take(8),
                    color = if (focused) Tokens.OnAccent else Tokens.Text,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(Tokens.Corner.badge))
                        .background(if (focused) Tokens.Accent else Color(0xCC0B0C10))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = if (progress != null) 18.dp else 12.dp)
            ) {
                Text(
                    text = vod.name,
                    color = Tokens.Text,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    vod.displayYear.takeIf { it.isNotBlank() },
                    vod.typeName.substringBefore(',').takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = Tokens.TextDim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x1FFFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Tokens.Accent)
                    )
                }
            }
        }
    }
}

/** 缺图占位：渐变底 + 片名首字，比一块纯灰更像"设计过"。 */
@Composable
private fun PosterPlaceholder(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                0f to Color(0xFF23262E),
                1f to Color(0xFF121419)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1),
            color = Tokens.AccentDim.copy(alpha = 0.9f),
            style = MaterialTheme.typography.displaySmall
        )
    }
}
