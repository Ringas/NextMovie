package com.nextmovie.ui.common

import androidx.compose.ui.composed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.nextmovie.ui.theme.Tokens
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * 键鼠适配的小工具。
 *
 * 电视应用默认只考虑遥控器（D-pad），但本地模拟器/MuMu 上调试几乎全靠鼠标和键盘，
 * 所以这里补三件事：鼠标悬停即聚焦、滚轮滚动列表、点击/回车都能触发。
 */

/** 鼠标悬停时自动把焦点挪到该控件，让放大/描边高亮跟着鼠标走。 */
@Composable
fun Modifier.mouseFocusable(): Modifier {
    // Hover must not steal focus from the remote. Pointer clicks still work.
    return this
}

/** 滚轮一格滚动的像素量。 */
private const val WHEEL_STEP = 140f

@Composable
fun Modifier.mouseWheel(state: LazyListState, horizontal: Boolean = false): Modifier {
    val scope = rememberCoroutineScope()
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                var delta = 0f
                event.changes.forEach {
                    delta += if (horizontal) it.scrollDelta.x else it.scrollDelta.y
                }
                if (delta != 0f) {
                    scope.launch { state.scrollBy(-delta * WHEEL_STEP) }
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

@Composable
fun Modifier.mouseWheel(state: LazyGridState): Modifier {
    val scope = rememberCoroutineScope()
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val delta = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                if (delta != 0f) {
                    scope.launch { state.scrollBy(-delta * WHEEL_STEP) }
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

@Composable
fun Modifier.mouseWheel(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val delta = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                if (delta != 0f) {
                    scope.launch { state.scrollBy(-delta * WHEEL_STEP) }
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

/**
 * 全局焦点语法：2dp 琥珀描边 + 两层内发光。
 *
 * 只有「按 OK 真的会发生什么」的控件才发光；静态文字一律不带焦点。
 * 描边画在控件内部，避免被父级 clip 裁掉一半。
 */
fun Modifier.tvFocusRing(corner: Dp = 8.dp, visible: Boolean = true): Modifier = drawWithContent {
    drawContent()
    if (!visible) return@drawWithContent
    val radius = CornerRadius(corner.toPx())
    val glow1 = 7.dp.toPx()
    val glow2 = 4.dp.toPx()
    val ring = 2.dp.toPx()
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawWithContent
    drawRoundRect(
        color = Color(0x1FE2B268),
        topLeft = Offset(0f, 0f),
        size = Size(w, h),
        cornerRadius = radius,
        style = Stroke(glow1)
    )
    drawRoundRect(
        color = Color(0x4DE2B268),
        topLeft = Offset(glow2 / 2, glow2 / 2),
        size = Size(w - glow2, h - glow2),
        cornerRadius = radius,
        style = Stroke(glow2)
    )
    drawRoundRect(
        color = Tokens.Accent,
        topLeft = Offset(ring / 2, ring / 2),
        size = Size((w - ring).coerceAtLeast(1f), (h - ring).coerceAtLeast(1f)),
        cornerRadius = radius,
        style = Stroke(ring)
    )
}

/**
 * One focus target per control, amber ring in both keyboard and D-pad modes.
 * 轻微放大只做在图层上，不参与布局，避免相邻控件被挤动。
 */
fun Modifier.tvClickable(
    enabled: Boolean = true,
    corner: Dp = 8.dp,
    scaleOnFocus: Float = 1.04f,
    showRing: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 同一次确认会被 onPreviewKeyEvent 和 clickable 各触发一次（遥控器上表现为「点两下」），
    // 这里做 150ms 去重，避免弹窗关掉又自己弹回来。
    var lastFireAt by remember { mutableLongStateOf(0L) }
    val fire: () -> Unit = {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFireAt > 150L) {
            lastFireAt = now
            onClick()
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) scaleOnFocus else 1f,
        animationSpec = tween(Tokens.MotionMs, easing = FastOutSlowInEasing),
        label = "tvFocusScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .tvFocusRing(corner = corner, visible = focused && showRing)
        // 遥控 OK / 键盘 Enter·空格 在部分机型上不会走 clickable，这里再兜一层
        .onPreviewKeyEvent { e ->
            if (enabled && TvKeys.isConfirmUp(e)) {
                fire()
                true
            } else false
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = fire)
}
