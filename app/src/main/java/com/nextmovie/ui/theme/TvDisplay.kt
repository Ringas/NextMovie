package com.nextmovie.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Keep the same physical layout proportions at 720p/1080p/4K, instead of shrinking on 4K. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvDisplay(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val original = LocalDensity.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
    val prefs = remember { context.getSharedPreferences("tv-display", 0) }
    var scale by remember { mutableFloatStateOf(prefs.getFloat("scale", 1f).coerceIn(1f, 1.25f)) }
    val state = remember { TvDisplayState { value ->
        scale = value.coerceIn(1f, 1.25f)
        prefs.edit().putFloat("scale", scale).apply()
    } }
    state.scale = scale
    val widthPx = configuration.screenWidthDp * original.density
    val density = (widthPx / 960f * scale).coerceAtLeast(0.75f)
    CompositionLocalProvider(LocalDensity provides Density(density, original.fontScale), LocalTvDisplay provides state, content = content)
}

class TvDisplayState(private val update: (Float) -> Unit) {
    var scale by mutableFloatStateOf(1f)
    fun select(value: Float) = update(value)
}
val LocalTvDisplay = staticCompositionLocalOf { TvDisplayState {} }
