package com.nextmovie.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.theme.DynamicAccent
import com.nextmovie.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 舞台背景：石墨夜色 + 一团取自当前作品的主色氛围光 + 极淡颗粒。
 * 这是 tvOS 让整块屏幕「有空气」的关键，颜色随焦点作品漂移。
 */
@Composable
fun StageBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val accent = Tokens.Accent
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Tokens.Bg)
                drawGradientGlow(accent.copy(alpha = 0.34f))
                drawNoise()
            }
    ) { content() }
}

private fun DrawScope.drawGradientGlow(color: Color) {
    val brush = Brush.radialGradient(
        0f to color,
        0.45f to color.copy(alpha = color.alpha * 0.42f),
        1f to Color.Transparent,
        center = Offset(size.width * 0.62f, size.height * 0.02f),
        radius = size.width * 0.85f
    )
    drawRect(brush)
}

/** 颗粒：几十个像素级小点，成本可忽略，但能去掉大面积渐变的「塑料感」。 */
private fun DrawScope.drawNoise() {
    var seed = 20261125
    repeat(900) {
        seed = seed * 1103515245 + 12345
        val x = ((seed ushr 8) % 1000) / 1000f * size.width
        seed = seed * 1103515245 + 12345
        val y = ((seed ushr 8) % 1000) / 1000f * size.height
        drawCircle(Color.White.copy(alpha = 0.05f), radius = 0.9f, center = Offset(x, y))
    }
}

/** 顶栏：居中玻璃胶囊 tab + 右侧时钟，滚动后可换浅一档背景（这里固定用最浅档）。 */
@Composable
fun GlassTopBar(
    tabs: List<TabSpec>,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusOk: Boolean = true,
    onStatusClick: (() -> Unit)? = null
) {
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = currentClock()
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        tabs.forEachIndexed { i, tab ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            GlassTab(tab)
        }
        Spacer(Modifier.weight(1f))
        if (status != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(Tokens.Surface)
                    .then(if (onStatusClick != null) Modifier.tvClickable(corner = Tokens.Corner.pill, onClick = onStatusClick) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (statusOk) Tokens.Accent else Tokens.Danger)
                )
                Spacer(Modifier.width(7.dp))
                Text(status, color = if (statusOk) Tokens.TextDim else Tokens.Danger, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Spacer(Modifier.width(18.dp))
        }
        Text(clock, color = Tokens.TextDim, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium))
    }
}

private fun currentClock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

data class TabSpec(val label: String, val active: Boolean, val onClick: () -> Unit)

/** 玻璃胶囊：默认白 6%，聚焦时整块被主色吃掉、文字转成对比色。 */
@Composable
fun GlassTab(spec: TabSpec) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            focused -> Tokens.Accent
            spec.active -> Color(0x24FFFFFF)
            else -> Color(0x10FFFFFF)
        },
        tween(180), label = "tabBg"
    )
    val fg by animateColorAsState(
        when {
            focused -> Tokens.OnAccent
            spec.active -> Tokens.Text
            else -> Tokens.TextDim
        },
        tween(180), label = "tabFg"
    )
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160, easing = FastOutSlowInEasing), label = "tabScale")
    Box(
        modifier = Modifier
            .height(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(Tokens.Corner.pill))
            .background(bg)
            .border(1.dp, if (focused) Tokens.Accent else Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(corner = Tokens.Corner.pill, showRing = false, onClick = spec.onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(spec.label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 巨幕：一张 20/28 圆角的暗色卡片，内部是剧照 + 左上主色氛围 + 左侧压暗 + 底收边。
 * 左下是 kicker / 片名 / 元信息 / 简介 / 操作，右下是 7 个小指示点（当前一枚拉成短棒）。
 */
@Composable
fun Billboard(
    vod: Vod,
    page: Int,
    pageCount: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onFocused: () -> Unit = {},
    onPageStep: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 186.dp
) {
    val context = LocalContext.current
    val bgUrl = remember(vod.displayBackdrop, vod.displayPic, vod.pic) { vod.displayBackdrop.ifBlank { vod.displayPic.ifBlank { vod.pic } } }
    val backdrop = remember(bgUrl) { posterModel(context, bgUrl, detail = true) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.Corner.hero)
    val accent = Tokens.Accent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Color(0xFF101116))
            .border(if (focused) 2.dp else 1.dp, if (focused) Tokens.Accent else Tokens.Stroke, shape)
            .tvClickable(corner = Tokens.Corner.hero, showRing = false, onClick = onOpen)
            .onPreviewKeyEvent { e ->
                if (!focused || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> { onPageStep(-1); true }
                    androidx.compose.ui.input.key.Key.DirectionRight -> { onPageStep(1); true }
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) DynamicAccent.fromSeed(vod.id)
            }
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 主色氛围光（左上一团）+ 左侧压暗 + 底部收边
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to accent.copy(alpha = 0.55f),
                        0.55f to accent.copy(alpha = 0.18f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.18f, size.height * 0.1f),
                        radius = size.width * 0.62f
                    )
                )
            }
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xF20B0C10), 0.46f to Color(0x990B0C10), 0.82f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.62f to Color(0x660B0C10), 1f to Color(0xEE0B0C10))
            )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 30.dp, end = 220.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(accent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append("当前焦点")
                        if (vod.remark.isNotBlank()) append(" · ").append(vod.remark.take(10))
                    },
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = vod.name,
                color = Tokens.Text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.6).sp,
                    lineHeight = 38.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(vod.displayYear, vod.area, vod.typeName.substringBefore(','))
                .filter { it.isNotBlank() }.distinct().joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = Tokens.Score,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            val overview = vod.displayOverview
            if (overview.isNotBlank()) {
                Text(
                    text = overview,
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.9f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BillboardButton("立即播放", primary = true, onClick = onPlay)
                Spacer(Modifier.width(12.dp))
                BillboardButton("详情", primary = false, onClick = onOpen)
            }
        }

        if (pageCount > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pageCount.coerceAtMost(8)) { i ->
                    val on = i == page
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (on) 20.dp else 7.dp, height = 7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (on) accent else Color(0x4DFFFFFF))
                    )
                }
            }
        }
    }
}

@Composable
fun BillboardButton(text: String, primary: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    val bg by animateColorAsState(if (focused) Tokens.Accent else if (primary) Color(0xE6FFFFFF) else Color(0x1AFFFFFF), tween(160), label = "bbBg")
    val fg by animateColorAsState(if (focused) Tokens.OnAccent else if (primary) Color(0xFF0B0C10) else Tokens.Text, tween(160), label = "bbFg")
    Box(
        modifier = Modifier
            
            .height(40.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (primary || focused) Color.Transparent else Tokens.StrokeStrong, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(corner = Tokens.Corner.pill, showRing = false, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 横排：标题（可带灰色副标题）+ 两端翻页圆钮 + 卡片轨道。
 * 轨道左右留 56dp（与 [Tokens.ScreenPadding] 对齐），卡片间距 20dp。
 */
@Composable
fun <T> Rail(
    title: String,
    subtitle: String?,
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 36.dp, end = 36.dp),
    state: LazyListState = rememberLazyListState(),
    card: @Composable (T) -> Unit
) {
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.firstVisibleItemIndex) { index = state.firstVisibleItemIndex }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 36.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Tokens.Text,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, letterSpacing = (-0.6).sp),
                    maxLines = 1
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            RailArrow("‹") { scope.launch { state.animateScrollToItem((index - 5).coerceAtLeast(0)) } }
            Spacer(Modifier.width(8.dp))
            RailArrow("›") { scope.launch { state.animateScrollToItem((index + 5).coerceAtMost(items.size - 1)) } }
        }
        LazyRow(
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(items, key = { _, item -> key(item) }) { _, item -> card(item) }
        }
    }
}

@Composable
fun RailArrow(glyph: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            
            .size(30.dp)
            .clip(CircleShape)
            .background(if (focused) Tokens.Accent else Color(0x1AFFFFFF))
            .border(1.dp, Tokens.Stroke, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(corner = Tokens.Corner.pill, showRing = false, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = if (focused) Tokens.OnAccent else Tokens.TextDim, style = MaterialTheme.typography.titleLarge)
    }
}

/** 筛选/分类胶囊行（原型的 .chip）。 */
@Composable
fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(labels) { i, label ->
            var focused by remember { mutableStateOf(false) }
            val bg by animateColorAsState(
                when {
                    focused -> Tokens.Accent
                    i == selected -> Color(0x2EFFFFFF)
                    else -> Color(0x0FFFFFFF)
                }, tween(160), label = "chipBg"
            )
            val fg by animateColorAsState(
                when {
                    focused -> Tokens.OnAccent
                    i == selected -> Tokens.Text
                    else -> Tokens.TextDim
                }, tween(160), label = "chipFg"
            )
            Box(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(bg)
                    .border(1.dp, if (focused) Tokens.Accent else Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                    .onFocusChanged { focused = it.isFocused }
                    .tvClickable(corner = Tokens.Corner.pill, showRing = false, onClick = { onSelect(i) })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 二级页统一顶栏：返回胶囊 + 页名 + 右侧统计。所有内页都从这里拿同一套 chrome。 */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "‹ 首页",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.Corner.pill))
                .background(Color(0x12FFFFFF))
                .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                .tvClickable(corner = Tokens.Corner.pill, onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 9.dp)
        )
        Spacer(Modifier.weight(1f))
        if (title.isNotBlank()) {
            Text(title, color = Tokens.Text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
            Spacer(Modifier.width(16.dp))
        }
        if (trailing != null) {
            Text(trailing, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall)
        }
    }
}
