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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.theme.Tokens

/**
 * 海报卡：6dp 图片圆角 + 聚焦琥珀描边 + 外发光 + 1.035 轻推。
 * 只有卡片本身会发光，片名是静态文字，不参与焦点。
 */
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
    val shape = RoundedCornerShape(Tokens.Radius)

    Column(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .mouseFocusable()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick = onClick, corner = Tokens.Radius, showRing = false)
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(shape)
                .background(Tokens.SurfaceHigh)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Tokens.Accent else Tokens.Stroke,
                    shape = shape
                )
                .tvFocusRing(corner = Tokens.Radius, visible = focused)
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
            // 底部压暗，保证角标与片名在亮海报上也读得清
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE60B0C10)))
            )
            if (vod.remark.isNotBlank()) {
                Text(
                    text = vod.remark,
                    color = if (focused) Tokens.OnAccent else Tokens.Text,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(Tokens.Corner.badge))
                        .background(if (focused) Tokens.Accent else Color(0xCC0B0C10))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            // 片名压在图上：电视墙只给一行的高度，与其把名字挤到画面外，不如贴在封面底部
            Text(
                text = vod.name,
                color = Tokens.Text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 7.dp)
            )
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
