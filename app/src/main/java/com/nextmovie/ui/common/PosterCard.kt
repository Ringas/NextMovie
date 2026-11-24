package com.nextmovie.ui.common

import com.nextmovie.ui.common.mouseFocusable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.theme.Tokens

/**
 * 海报卡：聚焦放大 + 金色描边 + 标题浮现。
 * 图片优先 TMDB 海报，缺失时回退源站 vod_pic（由 Vod.displayPic 统一处理）。
 */
/** 缺图占位：渐变底 + 片名首字，比一块纯灰更像"设计过"。 */
@Composable
private fun PosterPlaceholder(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                0f to Color(0xFF2A2E3A),
                1f to Color(0xFF14161C)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = name.take(1),
            color = Tokens.AccentDim,
            style = androidx.compose.material3.MaterialTheme.typography.displaySmall
        )
    }
}

@Composable
fun PosterCard(
    vod: Vod,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Tokens.CardWidth,
    height: Dp = Tokens.CardHeight
) {
    val context = LocalContext.current
    val model = remember(vod.displayPic) { posterModel(context, vod.displayPic, detail = false) }
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(vod.displayPic) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) Tokens.FocusScale else 1f,
        animationSpec = tween(Tokens.MotionMs, easing = FastOutSlowInEasing),
        label = "posterScale"
    )

    Column(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .mouseFocusable()
            .clickable { onClick() }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(Tokens.Radius))
                .background(Tokens.SurfaceHigh)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Tokens.Accent else Tokens.Stroke,
                    shape = RoundedCornerShape(Tokens.Radius)
                )
        ) {
            if (vod.displayPic.isBlank() || imageFailed) {
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
            if (vod.remark.isNotBlank()) {
                Text(
                    text = vod.remark,
                    color = Tokens.Text,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = vod.name,
            color = if (focused) Tokens.Text else Tokens.TextDim,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
