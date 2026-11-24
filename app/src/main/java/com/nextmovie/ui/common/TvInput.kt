package com.nextmovie.ui.common

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    val requester = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) runCatching { requester.requestFocus() }
    }
    return this.focusRequester(requester).hoverable(source)
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
