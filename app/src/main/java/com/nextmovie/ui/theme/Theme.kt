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
 * 10-foot UI 设计令牌。
 * 取色偏「影院暗场 + 香槟金」：背景压到接近纯黑，避免电视上发灰；
 * 主色只用于焦点与关键操作，保证远距离一眼识别。
 */
object Tokens {
    val Bg = Color(0xFF07080B)
    val Surface = Color(0xFF111318)
    val SurfaceHigh = Color(0xFF181B23)
    val ScrimTop = Color(0xCC000000)
    val ScrimBottom = Color(0xF2000000)

    val Accent = Color(0xFFE5C07B)
    val AccentDim = Color(0x66E5C07B)
    val OnAccent = Color(0xFF16130B)

    val Text = Color(0xFFF4F5F7)
    val TextDim = Color(0xFF9BA1AA)
    val Stroke = Color(0x1FFFFFFF)

    val PosterRatio = 2f / 3f
    val FocusScale = 1.06f
    const val MotionMs = 180

    val ScreenPadding = 32.dp
    val RowGap = 18.dp
    val CardWidth = 96.dp
    val CardHeight = 144.dp
    val Radius = 10.dp

    // —— 8pt 间距栅格 ——
    object Gap {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp
        val xxl = 48.dp
    }

    // —— 圆角阶梯 ——
    object Corner {
        val badge = 6.dp
        val card = 12.dp
        val panel = 18.dp
    }

    // —— 字号阶梯（10-foot UI：最小 13sp，正文 16sp）——
    object Type {
        val hero = 32.sp
        val title = 20.sp
        val section = 16.sp
        val body = 13.sp
        val caption = 11.sp
        val badge = 10.sp
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
    onSurfaceVariant = Tokens.TextDim
)

private val PosterTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    headlineMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun NextMovieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PosterColorScheme,
        typography = PosterTypography,
        content = content
    )
}
