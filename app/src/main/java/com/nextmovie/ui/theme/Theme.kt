package com.nextmovie.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 10-foot UI 设计令牌（V2 · 统一全局样式）
 *
 * 底色是冷炭而不是纯黑：电视 OLED 上纯黑会让面板边界消失，整屏看起来「没画完」。
 * 只用一支强调色（琥珀金）承担焦点、主操作和播放进度，其余全部退到灰度，
 * 保证三米外一眼就能找到「现在能按哪里」。
 */
object Tokens {
    // —— 底色 / 面板 ——
    val Bg = Color(0xFF0B0C10)
    val Surface = Color(0xFF111319)
    val SurfaceHigh = Color(0xFF15171D)
    val SurfaceSunk = Color(0xFF07080B)
    val ScrimTop = Color(0xCC0B0C10)
    val ScrimBottom = Color(0xF20B0C10)

    // —— 强调色（唯一）——
    val Accent = Color(0xFFE2B268)
    val AccentDim = Color(0x33E2B268)
    val AccentGlow = Color(0x59E2B268)
    val OnAccent = Color(0xFF221A0C)

    // —— 文本 / 发丝线 ——
    val Text = Color(0xFFF3F0E9)
    val TextDim = Color(0xFF8B8A85)
    val TextFaint = Color(0xFF5E5D59)
    val Stroke = Color(0x1AF3F0E9)
    val StrokeStrong = Color(0x33F3F0E9)
    val Danger = Color(0xFFD9635C)

    val PosterRatio = 2f / 3f
    val FocusScale = 1.035f
    const val MotionMs = 180

    val ScreenPadding = 48.dp
    val RowGap = 20.dp
    val CardWidth = 128.dp
    val CardHeight = 172.dp
    val Radius = 6.dp

    // —— 8pt 间距栅格 ——
    object Gap {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp
        val xxl = 48.dp
    }

    // —— 圆角阶梯：图片 6 / 卡片 8 / 面板 12 / 胶囊整圆 ——
    object Corner {
        val badge = 6.dp
        val card = 8.dp
        val panel = 12.dp
        val pill = 999.dp
    }

    // —— 字号阶梯（10-foot UI：最小 12sp，正文 16sp）——
    object Type {
        val display = 34.sp
        val hero = 30.sp
        val title = 20.sp
        val section = 16.sp
        val body = 16.sp
        val caption = 14.sp
        val badge = 13.sp
        val micro = 11.sp
        const val MICRO_TRACK = 0.16f
    }
}

private val PosterColorScheme = darkColorScheme(
    primary = Tokens.Accent,
    onPrimary = Tokens.OnAccent,
    secondary = Tokens.Accent,
    background = Tokens.Bg,
    onBackground = Tokens.Text,
    surface = Tokens.Surface,
    onSurface = Tokens.Text,
    surfaceVariant = Tokens.SurfaceHigh,
    onSurfaceVariant = Tokens.TextDim,
    error = Tokens.Danger
)

private val PosterTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun NextMovieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PosterColorScheme,
        typography = PosterTypography,
        content = content
    )
}
