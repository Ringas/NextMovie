package com.nextmovie.ui.common

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import android.view.KeyEvent as AndroidKeyEvent

/**
 * 遥控器 + 键盘统一键位语义。
 *
 * 电视盒只有方向键 / 确认 / 返回 / 菜单；
 * 模拟器与电脑调试还有 Enter、Esc、空格、字母快捷键。
 * 业务层只认 [TvAction]，不要散落 `Key.Direction*` 判断。
 */
enum class TvAction {
    Confirm,
    Back,
    Menu,
    Up,
    Down,
    Left,
    Right,
    PlayPause,
    FastForward,
    Rewind,
    Info,
    PageUp,
    PageDown,
}

object TvKeys {

    fun actionOf(event: KeyEvent): TvAction? {
        if (event.type != KeyEventType.KeyUp && event.type != KeyEventType.KeyDown) return null
        // 只在 KeyUp 触发一次的动作由调用方用 type 过滤；这里两种都映射
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> TvAction.Confirm
            Key.Back, Key.Escape -> TvAction.Back
            Key.Menu, Key.Tab -> TvAction.Menu
            Key.DirectionUp -> TvAction.Up
            Key.DirectionDown -> TvAction.Down
            Key.DirectionLeft -> TvAction.Left
            Key.DirectionRight -> TvAction.Right
            Key.MediaPlay, Key.MediaPlayPause, Key.MediaPause -> TvAction.PlayPause
            Key.MediaFastForward, Key.MediaSkipForward -> TvAction.FastForward
            Key.MediaRewind, Key.MediaSkipBackward -> TvAction.Rewind
            Key.Info, Key.Help -> TvAction.Info
            Key.PageUp -> TvAction.PageUp
            Key.PageDown -> TvAction.PageDown
            else -> null
        }
    }

    fun actionOfAndroid(event: AndroidKeyEvent): TvAction? {
        if (event.action != AndroidKeyEvent.ACTION_UP &&
            event.action != AndroidKeyEvent.ACTION_DOWN
        ) return null
        return when (event.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            AndroidKeyEvent.KEYCODE_SPACE,
            AndroidKeyEvent.KEYCODE_BUTTON_A,
            -> TvAction.Confirm
            AndroidKeyEvent.KEYCODE_BACK,
            AndroidKeyEvent.KEYCODE_ESCAPE,
            AndroidKeyEvent.KEYCODE_BUTTON_B,
            -> TvAction.Back
            AndroidKeyEvent.KEYCODE_MENU,
            AndroidKeyEvent.KEYCODE_BUTTON_START,
            -> TvAction.Menu
            AndroidKeyEvent.KEYCODE_DPAD_UP -> TvAction.Up
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> TvAction.Down
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> TvAction.Left
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> TvAction.Right
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
            -> TvAction.PlayPause
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT,
            -> TvAction.FastForward
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> TvAction.Rewind
            AndroidKeyEvent.KEYCODE_INFO -> TvAction.Info
            AndroidKeyEvent.KEYCODE_PAGE_UP -> TvAction.PageUp
            AndroidKeyEvent.KEYCODE_PAGE_DOWN -> TvAction.PageDown
            else -> null
        }
    }

    fun isConfirmUp(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyUp && actionOf(event) == TvAction.Confirm

    fun isBackUp(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyUp && actionOf(event) == TvAction.Back

    fun isMenuUp(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyUp && actionOf(event) == TvAction.Menu

    /** 设置侧栏数字键 1–9 快速跳段（键盘） */
    fun sectionDigit(event: KeyEvent): Int? {
        if (event.type != KeyEventType.KeyUp) return null
        return when (event.key) {
            Key.One, Key.NumPad1 -> 0
            Key.Two, Key.NumPad2 -> 1
            Key.Three, Key.NumPad3 -> 2
            Key.Four, Key.NumPad4 -> 3
            Key.Five, Key.NumPad5 -> 4
            Key.Six, Key.NumPad6 -> 5
            Key.Seven, Key.NumPad7 -> 6
            Key.Eight, Key.NumPad8 -> 7
            Key.Nine, Key.NumPad9 -> 8
            else -> null
        }
    }
}
