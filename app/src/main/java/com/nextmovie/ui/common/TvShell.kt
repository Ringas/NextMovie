package com.nextmovie.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.ui.theme.Ambient
import com.nextmovie.ui.theme.AmbientPreset
import com.nextmovie.ui.theme.AmbientState
import com.nextmovie.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 客厅柔光背景（对齐 Marco TV 的 TvAmbientSoftGlowBackground）
 *
 * 斜向三段渐变打底 + 四团固定位置的软光 + 可选的海报色联动光。
 * 全部用径向渐变直接铺满矩形，不做实时模糊：1080p 电视盒子上零掉帧，
 * 这才是「高级但不卡」的取舍。
 */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    themeId: String = AmbientState.themeId,
    intensity: String = AmbientState.intensity,
    posterTint: Color? = null,
    posterSync: Boolean = AmbientState.posterSync
) {
    val preset = Ambient.forId(themeId)
    val gain = if (intensity == "soft") 0.72f else if (intensity == "vivid") 1.32f else 1.0f
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(preset.baseStart, preset.baseMid, preset.baseEnd),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
                orb(preset.orbPrimary, 0.46f * gain, 0.14f, 0.18f, 0.64f)
                orb(preset.orbSecondary, 0.42f * gain, 0.86f, 0.22f, 0.60f)
                orb(preset.orbSecondary, 0.24f * gain, 0.50f, 0.06f, 0.44f)
                orb(preset.orbWarm, 0.36f * gain, 0.52f, 0.94f, 0.66f)
                if (posterSync && posterTint != null && posterTint.alpha > 0.01f) {
                    orb(posterTint, 0.22f, 0.50f, 0.42f, 0.68f)
                }
            }
    )
}

private fun DrawScope.orb(color: Color, alpha: Float, cx: Float, cy: Float, radiusFactor: Float) {
    val a = alpha.coerceIn(0f, 0.85f)
    val center = Offset(size.width * cx, size.height * cy)
    val radius = (size.width * radiusFactor).coerceIn(160f, 2400f)
    drawRect(
        Brush.radialGradient(
            0f to color.copy(alpha = a),
            0.48f to color.copy(alpha = a * 0.42f),
            1f to Color.Transparent,
            center = center,
            radius = radius
        )
    )
}

/**
 * 通用舞台：柔光背景 + 内容。posterTint 传入当前焦点海报的主色即可整屏联动。
 * 既有页面（设置、详情、搜索）继续复用这个入口。
 */
@Composable
fun StageBackdrop(
    modifier: Modifier = Modifier,
    posterTint: Color? = null,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        AmbientBackdrop(posterTint = posterTint)
        content()
    }
}

/** 品牌字标：图标 + Next / Movie 两段字重，与 Macro 的 Marco Movie 同构。 */
@Composable
fun BrandLogo(
    iconSize: Dp = 42.dp,
    fontSize: Float = 23f,
    stacked: Boolean = false,
    showWordmark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val mark: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(iconSize * 0.24f))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF2A4E7E), Color(0xFF15243A))
                    )
                )
                .border(0.8.dp, Color(0x33FFFFFF), RoundedCornerShape(iconSize * 0.24f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "N",
                color = Color(0xFFE8F1FF),
                fontSize = (iconSize.value * 0.52f).sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = (iconSize.value * 0.52f).sp
            )
        }
    }
    val wordmark: @Composable () -> Unit = {
        Row {
            Text("Next", color = Color(0xFFF8FAFC), fontSize = fontSize.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
            Text(" Movie", color = Color(0xFFD8E0EE), fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        }
    }
    if (stacked) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
            mark()
            if (showWordmark) {
                Spacer(Modifier.height(10.dp))
                wordmark()
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            mark()
            if (showWordmark) {
                Spacer(Modifier.width(8.dp))
                wordmark()
            }
        }
    }
}

data class NavTab(
    val icon: ImageVector?,
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit
)

/**
 * 浮动玻璃胶囊导航（对齐 Marco TvLivingRoomShell 的顶栏）
 * 左：品牌；右：时钟；中：五枚胶囊，选中是浅白实心、聚焦是浅白 88%。
 */
@Composable
fun FloatingTopBar(
    tabs: List<NavTab>,
    modifier: Modifier = Modifier,
    status: String? = null
) {
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = currentClock()
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
    ) {
        // 窄屏（手机的 730dp）放不下「品牌 + 5 个 tab + 状态」，按 Marco 的优先级让位给导航
        val narrow = maxWidth < 780.dp
        val compact = maxWidth < 1040.dp
        if (!narrow) BrandLogo(
            iconSize = if (compact) 36.dp else 42.dp,
            fontSize = if (compact) 20f else 23f,
            showWordmark = !compact,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x880B1320))
                .border(1.1.dp, Color(0x29FFFFFF), RoundedCornerShape(999.dp))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { NavCapsule(it) }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status != null && !compact) {
                Text(
                    text = status,
                    color = Tokens.TextFaint,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(190.dp),
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                text = clock,
                color = Tokens.TextDim,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun NavCapsule(tab: NavTab) {
    var focused by remember { mutableStateOf(false) }
    val active = tab.active || focused
    val fg by animateColorAsState(
        if (active) Tokens.DarkInk else Color(0xDBFFFFFF),
        tween(Tokens.MotionMs), label = "navFg"
    )
    val bg by animateColorAsState(
        when {
            tab.active -> Color(0xECEAECEF)
            focused -> Color(0xE0FFFFFF)
            else -> Color.Transparent
        },
        tween(Tokens.MotionMs), label = "navBg"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(corner = Tokens.Corner.pill, showRing = false, onClick = tab.onClick)
            .padding(horizontal = 26.dp, vertical = 11.dp)
    ) {
        tab.icon?.let {
            androidx.compose.material3.Icon(
                imageVector = it,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = tab.label,
            color = fg,
            fontSize = 18.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

/**
 * 胶囊控件（对齐 Marco TvFocusPill）：四种语气
 * - 普通：白 11% 底，聚焦时主色实心
 * - prominent：浅白实心 + 深墨字，聚焦时白色辉光 + 主色描边（主行动）
 * - glass：白 12%，聚焦变实白深墨字，选中时主色 90%
 * - destructive：红色危险项
 *
 * 描边 1.1dp → 聚焦 2.4dp，缩放只做 1.045，符合客厅 3 米观看距离。
 */
@Composable
fun TvPill(
    text: String? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    prominent: Boolean = false,
    glass: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    autofocus: Boolean = false,
    preserveColor: Boolean = false,
    borderRadius: Dp = Tokens.Corner.pill,
    horizontalPadding: Dp = 18.dp,
    verticalPadding: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val actual = focused
    val click = if (enabled) onClick else null
    val bg: Color = when {
        prominent -> if (actual) Color.White else Color(0xF5F7F9FC)
        destructive -> if (actual) Color(0xFFE5484D) else Tokens.DangerDim
        glass -> when {
            actual && !preserveColor -> Color.White
            selected -> Tokens.Accent.copy(alpha = 0.90f)
            else -> if (actual) Color(0x33FFFFFF) else Color(0x1FFFFFFF)
        }
        selected -> Tokens.Accent
        actual && !preserveColor -> Tokens.Accent
        else -> Color(0x1CFFFFFF)
    }
    val fg: Color = when {
        prominent -> if (!enabled) Color(0x73FFFFFF) else Tokens.DarkInk
        destructive -> if (actual) Color.White else Color(0xFFFFB7B3)
        glass -> if (actual && !preserveColor) Tokens.DarkInk else Color.White
        else -> Color.White
    }
    val border: Color = when {
        prominent -> if (actual) Tokens.Accent else Color(0xD9FFFFFF)
        destructive -> if (actual) Color.White else Color(0x70FF6961)
        actual -> Color.White
        selected -> Color(0x6BFFFFFF)
        else -> Tokens.GlassStroke
    }
    val borderWidth by animateDpAsState(if (actual) 2.4.dp else 1.1.dp, tween(Tokens.MotionMs), label = "pillBorder")
    val scale by animateFloatAsState(if (actual) 1.045f else 1f, tween(140, easing = FastOutSlowInEasing), label = "pillScale")
    val shape = RoundedCornerShape(borderRadius)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg)
            .border(borderWidth, border, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(corner = borderRadius, showRing = false, enabled = click != null, onClick = { click?.invoke() })
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        if (content != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides fg
            ) { content() }
        }
        if (text != null) {
            Text(
                text = text,
                color = fg,
                fontSize = 16.sp,
                fontWeight = if (prominent || actual || selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 玻璃卡片（对齐 Marco _settingsGlassCard）：白 8.5% 底 + 20dp 圆角 + 26dp 内边距。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Corner.panel))
            .background(Color(0x16FFFFFF))
            .border(1.1.dp, Tokens.GlassStroke, RoundedCornerShape(Tokens.Corner.panel))
            .padding(26.dp)
    ) {
        if (title != null) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(5.dp))
                Text(subtitle, color = Tokens.TextDim, fontSize = 14.5.sp)
            }
            Spacer(Modifier.height(18.dp))
        }
        content()
    }
}

/** 元信息胶囊（白 13% 底 / 主色 28% 高亮）。 */
@Composable
fun MetaChip(text: String, highlight: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = if (highlight) Color(0xFFD6EAFF) else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (highlight) Tokens.Accent.copy(alpha = 0.28f) else Color(0x21FFFFFF))
            .border(
                0.9.dp,
                if (highlight) Tokens.Accent.copy(alpha = 0.65f) else Color(0x33FFFFFF),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

/** 金色评分胶囊：唯一允许用「亮底深字」的地方，因为它就是全场最亮的一点。 */
@Composable
fun GoldChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Tokens.ScoreInk,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Tokens.Score)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

/** 琥珀「4K 云盘」角标。 */
@Composable
fun AmberChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Tokens.AmberText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Tokens.AmberDim)
            .border(0.9.dp, Tokens.Amber, RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

/** 内页顶栏：返回胶囊 + 标题 + 右侧品牌，替代旧的居中玻璃 tab（详情/搜索/片库共用）。 */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        TvPill(text = "← 返回", glass = true, horizontalPadding = 18.dp, verticalPadding = 10.dp, onClick = onBack)
        Spacer(Modifier.width(18.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, color = Tokens.TextFaint, fontSize = 13.5.sp, maxLines = 1)
            Spacer(Modifier.width(18.dp))
        }
        BrandLogo(iconSize = 30.dp, fontSize = 17f)
    }
}

/** 设置/详情里的小节标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

private fun currentClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

/** 极简搜索框样式（Macro 的遥控键盘太依赖输入法，这里保留一个可视化入口）。 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            detail,
            color = Tokens.TextDim,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(640.dp)
        )
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}
