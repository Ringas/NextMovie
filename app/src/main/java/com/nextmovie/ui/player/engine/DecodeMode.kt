package com.nextmovie.ui.player.engine

/**
 * 解码偏好，对齐 FongMi 的硬解 / 软解切换。
 *
 * - [AUTO]：优先系统 MediaCodec 硬解，失败后走扩展软解（若有 FFmpeg AAR）再失败则换线
 * - [HARD]：强制硬解优先（EXTENSION_RENDERER_MODE_OFF + decoder fallback）
 * - [SOFT]：扩展软解优先（EXTENSION_RENDERER_MODE_PREFER），盒子硬解花屏时用
 */
enum class DecodeMode(val wire: String, val label: String) {
    AUTO("auto", "自动"),
    HARD("hard", "硬解优先"),
    SOFT("soft", "软解优先");

    companion object {
        fun fromWire(value: String?): DecodeMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: AUTO
    }
}
