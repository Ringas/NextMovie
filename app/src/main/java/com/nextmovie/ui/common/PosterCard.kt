package com.nextmovie.ui.common

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 海报卡（对齐 Marco TvFocusablePosterCard）
 *
 * 2:3 竖版 + 16dp 圆角（描边）/14.5dp 内裁切；未聚焦 1.1dp 玻璃发丝线，
 * 聚焦 2.5dp 纯白描边 + 主色辉光 + 1.045 轻推。角标在右上角，片名与元信息在卡片下方。
 */
@Composable
fun PosterCard(
    vod: Vod,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Tokens.CardWidth,
    height: Dp = Tokens.CardHeight,
    progress: Float? = null,
    fillWidth: Boolean = false,
    onImageFailed: () -> Unit = {}
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
    val inner = RoundedCornerShape(Tokens.Corner.cardInner)
    val meta = remember(vod) {
        listOfNotNull(
            vod.displayYear.takeIf { it.isNotBlank() },
            vod.area.takeIf { it.isNotBlank() } ?: vod.typeName.substringBefore(',').takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }
    val badge = remember(vod) {
        val raw = vod.remark.trim()
        // 「0」「0.0」是源站没给评分的占位，不要画成评分角标
        if (raw == "0" || raw == "0.0" || raw == "0分") "" else raw.take(12)
    }

    Column(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width))
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 1f) }
            .mouseFocusable()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick = onClick, corner = Tokens.Corner.card, showRing = true, scaleOnFocus = 1.06f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillWidth) Modifier.aspectRatio(2f / 3f) else Modifier.height(height - 52.dp))
                .drawBehind {
                    if (focused) {
                        drawRoundRect(
                            color = Tokens.Accent.copy(alpha = 0.30f),
                            topLeft = Offset(-4f, -4f),
                            size = androidx.compose.ui.geometry.Size(size.width + 8f, size.height + 8f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                        )
                    }
                }
                .clip(shape)
                .background(Tokens.PosterPlaceholder)
                .border(
                    width = if (focused) 2.5.dp else 1.1.dp,
                    color = if (focused) Color.White else Tokens.GlassStroke,
                    shape = shape
                )
                .padding(if (focused) 2.5.dp else 1.1.dp)
        ) {
            if (url.isBlank() || imageFailed) {
                PosterPlaceholder(
                    name = vod.name,
                    modifier = Modifier.fillMaxSize().clip(inner)
                )
            } else {
                AsyncImage(
                    model = model,
                    contentDescription = vod.name,
                    contentScale = ContentScale.Crop,
                    onError = {
                        imageFailed = true
                        onImageFailed()
                    },
                    modifier = Modifier.fillMaxSize().clip(inner)
                )
            }

            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xB8000000))
                        .border(0.8.dp, Color(0x2EFFFFFF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }

            if (progress != null && progress > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.5.dp)
                        .background(Color(0x8A000000))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Tokens.Accent)
                    )
                }
            }
        }
        Box(Modifier.height(9.dp))
        Text(
            text = vod.name,
            color = if (focused) Color.White else Color(0xF0FFFFFF),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Box(Modifier.height(2.dp))
            Text(
                text = meta,
                color = Tokens.TextFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 缺图占位：深蓝底 + 片名首字，比纯灰更像设计过。 */
@Composable
fun PosterPlaceholder(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(0f to Color(0xFF232C3D), 1f to Color(0xFF141A26))
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1),
            color = Color(0x66FFFFFF),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 从海报里取一个「主色」用于整屏柔光联动（对齐 Marco 的 poster ambient sync）。
 *
 * 用 Coil 走内存/磁盘缓存拿一张 24×36 的缩略图，算平均色即可；
 * 只在小尺寸上做一次遍历，开销可以忽略，而且不会为了取色再下一遍原图。
 */
object PosterTint {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Color>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun cached(url: String): Color? = cache[url.takeIf { it.isNotBlank() }]

    suspend fun resolve(context: android.content.Context, url: String): Color? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        if (pending.putIfAbsent(url, true) != null) return null
        return try {
            val base = posterModel(context, url, detail = false) as? ImageRequest ?: return null
            val request = base.newBuilder().size(24, 36).allowHardware(false).build()
            val result = coil.ImageLoader(context).execute(request)
            val bitmap = (result as? SuccessResult)?.drawable?.let { drawable ->
                if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap else null
            }
            val color = bitmap?.let { averageColor(it) }
            if (color != null) cache[url] = color
            color
        } catch (_: Throwable) {
            null
        } finally {
            pending.remove(url)
        }
    }

    private suspend fun averageColor(bitmap: Bitmap): Color = withContext(Dispatchers.Default) {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val a = (px ushr 24) and 0xFF
                if (a > 40) {
                    r += (px shr 16) and 0xFF
                    g += (px shr 8) and 0xFF
                    b += px and 0xFF
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return@withContext Color(0xFF388FFA)
        val rr = (r / n).toInt()
        val gg = (g / n).toInt()
        val bb = (b / n).toInt()
        // 提亮一点，否则深色海报染不出气氛
        Color(
            red = (rr + 30).coerceAtMost(255),
            green = (gg + 30).coerceAtMost(255),
            blue = (bb + 30).coerceAtMost(255)
        )
    }
}
