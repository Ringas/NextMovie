package com.nextmovie.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 动态主色：由「当前焦点的作品」决定。
 *
 * tvOS 的招牌做法是整屏被当前内容染色——不是固定一支品牌色，而是按作品 id 取色相，
 * 在深色底上取中高明度（保证它自己能从夜色里跳出来），再按对比度决定填充上的文字是黑是白。
 * 青 / 绿 / 黄这类色相在白字下永远不够看，交给 [ink] 换成黑字即可。
 */
object DynamicAccent {
    var color by mutableStateOf(Color(0xFF3D8BFF))
        private set
    var ink by mutableStateOf(Color.White)
        private set
    var soft by mutableStateOf(Color(0x4D3D8BFF))
        private set
    private var currentSeed: String? = null

    /** 同一个作品永远得到同一个色相：按 id 做稳定哈希。 */
    fun fromSeed(seed: String) {
        if (seed == currentSeed) return
        currentSeed = seed
        val h = ((seed.hashCode().toLong() and 0x7FFFFFFFL) % 3600L) / 10f
        fromHue(h)
    }

    fun fromHue(hue: Float) {
        var l = 58f
        while (l < 78f && relLum(hsl(hue, 0.74f, l / 100f)) < 0.22f) l += 2f
        val c = hsl(hue, 0.74f, l / 100f)
        color = c
        ink = if (relLum(c) > 0.179f) Color(0xFF0B0C10) else Color.White
        soft = hsl(hue, 0.78f, 0.58f).copy(alpha = 0.30f)
    }

    private fun relLum(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    private fun hsl(h: Float, s: Float, l: Float): Color {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f))
        val m = l - c / 2f
        val r: Float
        val g: Float
        val b: Float
        when {
            h < 60f -> { r = c; g = x; b = 0f }
            h < 120f -> { r = x; g = c; b = 0f }
            h < 180f -> { r = 0f; g = c; b = x }
            h < 240f -> { r = 0f; g = x; b = c }
            h < 300f -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }
}

/**
 * 10-foot UI 设计令牌（V3 · tvOS 原型）
 *
 * 底色是石墨夜色（#0B0C10），层次靠半透明白叠加而不是换底色，
 * 于是海报、面板、浮层都在同一个「夜色」里自然融合。
 * 唯一的主色是动态的（见 [DynamicAccent]），文字四级透明度建立层级。
 */
object Tokens {
    // —— 底色 / 材质 ——
    val Bg = Color(0xFF0B0C10)
    val Surface = Color(0x0FFFFFFF)      // white 6%
    val SurfaceHigh = Color(0x1FFFFFFF)  // white 12%
    val SurfaceSunk = Color(0x05000000)
    val ScrimTop = Color(0xE608090C)
    val ScrimBottom = Color(0xF20B0C10)

    // —— 主色（动态，读取时取当前值）——
    val Accent: Color get() = DynamicAccent.color
    val OnAccent: Color get() = DynamicAccent.ink
    val AccentDim: Color get() = DynamicAccent.soft
    val AccentGlow: Color get() = DynamicAccent.soft
    val Score = Color(0xFFF5C451)

    // —— 文本 / 发丝线 ——
    val Text = Color(0xFFF4F6FA)
    val TextDim = Color(0xB8F4F6FA)
    val TextFaint = Color(0x7AF4F6FA)
    val TextGhost = Color(0x47F4F6FA)
    val Stroke = Color(0x1AFFFFFF)
    val StrokeStrong = Color(0x2EFFFFFF)
    val Danger = Color(0xFFD9635C)

    val PosterRatio = 2f / 3f
    val FocusScale = 1.075f
    const val MotionMs = 220

    val ScreenPadding = 36.dp
    val RowGap = 14.dp
    val CardWidth = 117.dp
    val CardHeight = 175.dp
    val WideWidth = 210.dp
    val WideHeight = 118.dp
    val Radius = 14.dp

    // —— 8pt 间距栅格 ——
    object Gap {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp
        val xxl = 48.dp
    }

    // —— 圆角阶梯：图 14 / 面板 20 / 巨幕 28 / 胶囊整圆 ——
    object Corner {
        val badge = 10.dp
        val card = 14.dp
        val panel = 20.dp
        val hero = 28.dp
        val pill = 999.dp
    }

    // —— 字号阶梯（10-foot UI：最小 12sp，正文 15–16sp）——
    object Type {
        val billboard = 44.sp
        val display = 32.sp
        val hero = 28.sp
        val rowTitle = 21.sp
        val title = 19.sp
        val section = 16.sp
        val body = 15.sp
        val caption = 13.5.sp
        val badge = 12.sp
        val micro = 11.sp
        const val ROW_TRACK = -0.6f
        const val MICRO_TRACK = 0.16f
    }
}

private val TvColorScheme = darkColorScheme(
    primary = Color(0xFF3D8BFF),
    onPrimary = Color.White,
    secondary = Color(0xFF3D8BFF),
    background = Tokens.Bg,
    onBackground = Tokens.Text,
    surface = Color(0xFF13151B),
    onSurface = Tokens.Text,
    surfaceVariant = Color(0xFF1A1C23),
    onSurfaceVariant = Tokens.TextDim,
    error = Tokens.Danger
)

private val TvTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    labelSmall = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun NextMovieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvColorScheme,
        typography = TvTypography,
        content = content
    )
}
