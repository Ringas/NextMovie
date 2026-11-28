package com.nextmovie.ui.theme

import android.content.Context
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
 * 客厅柔光主题（对齐 Marco TV 的 6 套预设）
 *
 * 底色不是纯黑，而是一层「深夜蓝」——斜向三段渐变打底，再叠四团极软的光。
 * 光的位置固定（左上 / 右上 / 顶部中 / 底部中），只有色相随主题换，
 * 于是每套主题都长得很像，却又有明确的气质差别：这是它的克制之处。
 */
data class AmbientPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val baseStart: Color,
    val baseMid: Color,
    val baseEnd: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val orbWarm: Color,
    val accent: Color
)

object Ambient {
    val presets = listOf(
        // V5「影院黑」：底色压到近黑，只留一团极弱的暖光。
        // 海报成为屏上唯一的彩色，这是影院 UI 与"柔光桌面"最大的区别。
        AmbientPreset(
            id = "cinema", name = "影院黑金", subtitle = "近黑底片与一缕片门暖光",
            baseStart = Color(0xFF05070B), baseMid = Color(0xFF07090F), baseEnd = Color(0xFF0A0B10),
            orbPrimary = Color(0xFF1B2735), orbSecondary = Color(0xFF131A24), orbWarm = Color(0xFF6B4A2A),
            accent = Color(0xFF4C9BFF)
        ),
        AmbientPreset(
            id = "aurora", name = "极光青翠", subtitle = "翡翠湖光与晴空柔雾",
            baseStart = Color(0xFF0F2B3D), baseMid = Color(0xFF121F38), baseEnd = Color(0xFF1A1A3B),
            orbPrimary = Color(0xFF14C7AD), orbSecondary = Color(0xFF3894FA), orbWarm = Color(0xFF6157E0),
            accent = Color(0xFF2E94F5)
        ),
        AmbientPreset(
            id = "twilight", name = "暮色流光", subtitle = "鸢尾紫霞与玫瑰晚风",
            baseStart = Color(0xFF291C47), baseMid = Color(0xFF1C1A38), baseEnd = Color(0xFF2E1A33),
            orbPrimary = Color(0xFF8561FA), orbSecondary = Color(0xFFEB529E), orbWarm = Color(0xFFF08057),
            accent = Color(0xFF7570FA)
        ),
        AmbientPreset(
            id = "ocean", name = "深海晨雾", subtitle = "冰川海湾与蔚蓝天际",
            baseStart = Color(0xFF0F2947), baseMid = Color(0xFF0F1C36), baseEnd = Color(0xFF0D2436),
            orbPrimary = Color(0xFF24A3FA), orbSecondary = Color(0xFF14C7E0), orbWarm = Color(0xFF4266D6),
            accent = Color(0xFF3394FA)
        ),
        AmbientPreset(
            id = "amber", name = "暖金影院", subtitle = "胡桃木暖灯与胶片琥珀",
            baseStart = Color(0xFF36211A), baseMid = Color(0xFF211A21), baseEnd = Color(0xFF2B1721),
            orbPrimary = Color(0xFFEB8F2E), orbSecondary = Color(0xFFD14761), orbWarm = Color(0xFF9E6B38),
            accent = Color(0xFFE69438)
        ),
        AmbientPreset(
            id = "nebula", name = "星云紫夜", subtitle = "深空星尘与极夜紫罗兰",
            baseStart = Color(0xFF291745), baseMid = Color(0xFF171430), baseEnd = Color(0xFF12243D),
            orbPrimary = Color(0xFFA34DF5), orbSecondary = Color(0xFF299EE6), orbWarm = Color(0xFFD642A8),
            accent = Color(0xFF8F66FA)
        ),
        AmbientPreset(
            id = "frost", name = "冰川银雾", subtitle = "通透银灰与克制微光",
            baseStart = Color(0xFF1C2636), baseMid = Color(0xFF141A26), baseEnd = Color(0xFF1A2130),
            orbPrimary = Color(0xFF7099C7), orbSecondary = Color(0xFF478FC7), orbWarm = Color(0xFF5C7094),
            accent = Color(0xFF4294F5)
        )
    )

    fun forId(id: String): AmbientPreset {
        val lower = id.trim().lowercase()
        return presets.firstOrNull { it.id == lower } ?: presets.first()
    }
}

/**
 * 柔光主题的当前取值。放在全局 compose state 里，
 * 设置页一点，整屏背景立刻跟着换，并与 SharedPreferences 保持一致。
 */
object AmbientState {
    private const val PREFS = "nextmovie_ambient"
    private const val KEY_THEME = "theme"
    private const val KEY_INTENSITY = "intensity"
    private const val KEY_POSTER = "poster_sync"

    var themeId by mutableStateOf("cinema")
        private set
    /** soft / standard / vivid */
    var intensity by mutableStateOf("soft")
        private set
    var posterSync by mutableStateOf(false)
        private set

    private var bound = false

    fun bind(context: Context) {
        if (bound) return
        bound = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // V5 迁移：默认从「柔光」切到「影院黑」，只强制一次，之后仍以用户选择为准
        val migrated = p.getBoolean("cinema_v5", false)
        if (!migrated) {
            p.edit()
                .putString(KEY_THEME, "cinema")
                .putString(KEY_INTENSITY, "soft")
                .putBoolean(KEY_POSTER, false)
                .putBoolean("cinema_v5", true)
                .apply()
            themeId = "cinema"
            intensity = "soft"
            posterSync = false
        } else {
            themeId = p.getString(KEY_THEME, "cinema").orEmpty().ifBlank { "cinema" }
            intensity = p.getString(KEY_INTENSITY, "soft").orEmpty().ifBlank { "soft" }
            posterSync = p.getBoolean(KEY_POSTER, false)
        }
    }

    fun setTheme(context: Context, id: String) {
        themeId = id
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, id).apply()
    }

    fun setIntensity(context: Context, value: String) {
        intensity = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_INTENSITY, value).apply()
    }

    fun setPosterSync(context: Context, value: Boolean) {
        posterSync = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_POSTER, value).apply()
    }

    fun gain(): Float = when (intensity) {
        "soft" -> 0.72f
        "vivid" -> 1.32f
        else -> 1.0f
    }
}

/**
 * 10-foot UI 设计令牌 · V4（对齐 Marco TV）
 *
 * 层次全靠白色半透明叠加，主色只有一支（#388FFA），金色只给评分。
 * 卡片圆角 16、聚焦描边 1.1dp → 2.5dp 变白，配一层主色辉光；缩放只做 1.045。
 */
object Tokens {
    // —— 底色 / 材质 ——（V5 影院黑：近黑打底，层次靠白色半透明，不靠色相）
    val Bg = Color(0xFF05070B)
    val Surface = Color(0x14FFFFFF)        // white 8%
    val SurfaceHigh = Color(0x21FFFFFF)    // white 13%
    val SurfaceSunk = Color(0x66000000)
    val GlassFill = Color(0x1AFFFFFF)
    val GlassStroke = Color(0x33FFFFFF)
    val Panel = Color(0xE60A0C12)
    val PanelSolid = Color(0xF50A0C12)
    val PosterPlaceholder = Color(0xFF11141B)

    val Accent = Color(0xFF4C9BFF)
    val OnAccent = Color.White
    val AccentDim = Color(0x384C9BFF)
    val AccentGlow = Color(0x524C9BFF)
    val Score = Color(0xFFF5C247)
    val ScoreInk = Color(0xFF0A0F18)
    val Amber = Color(0xFFF5A623)
    val AmberText = Color(0xFFFFD166)
    val AmberDim = Color(0x38F5A623)
    val Green = Color(0xFF34C759)
    val GreenText = Color(0xFF7CF0A4)
    val GreenDim = Color(0x3834C759)
    val Danger = Color(0xFFE5484D)
    /** 「接口正常、线路失效」的黄点：和站点整体不可达的红点区分开。 */
    val Warn = Color(0xFFE8A93B)
    val DangerDim = Color(0x38FF453A)

    // —— 文本 ——
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xCCFFFFFF)
    val TextFaint = Color(0x94FFFFFF)
    val TextGhost = Color(0x5CFFFFFF)
    val Stroke = Color(0x1AFFFFFF)
    val StrokeStrong = Color(0x3DFFFFFF)
    val DarkInk = Color(0xFF0A0F1A)

    val PosterRatio = 0.56f
    val FocusScale = 1.08f
    const val MotionMs = 160

    val ScreenPadding = 56.dp
    val RowGap = 24.dp
    val CardWidth = 160.dp
    val CardHeight = 240.dp
    val WideWidth = 208.dp
    val WideHeight = 117.dp
    val Radius = 14.dp

    // —— 间距栅格 ——
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
        val badge = 10.dp
        val card = 16.dp
        val cardInner = 14.5.dp
        val tile = 14.dp
        val panel = 20.dp
        val hero = 24.dp
        val sheet = 24.dp
        val pill = 999.dp
    }

    // —— 字号阶梯（10-foot：最小 11.5sp，正文 15–18sp）——
    object Type {
        val hero = 44.sp
        val display = 32.sp
        val section = 24.sp
        val rowTitle = 22.sp
        val tab = 18.sp
        val title = 17.sp
        val body = 16.sp
        val caption = 14.5.sp
        val small = 13.5.sp
        val badge = 13.sp
        val micro = 11.5.sp
        const val MICRO_TRACK = 0.35f
    }
}

private val TvColorScheme = darkColorScheme(
    primary = Tokens.Accent,
    onPrimary = Color.White,
    secondary = Tokens.Accent,
    background = Tokens.Bg,
    onBackground = Tokens.Text,
    surface = Color(0xFF0B0E14),
    onSurface = Tokens.Text,
    surfaceVariant = Color(0xFF11141B),
    onSurfaceVariant = Tokens.TextDim,
    error = Tokens.Danger
)

private val TvTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
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
