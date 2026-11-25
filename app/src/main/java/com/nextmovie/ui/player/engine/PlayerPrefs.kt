package com.nextmovie.ui.player.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放器全局偏好（解码模式、引擎、失败策略）。
 * 与 PlayerScreen 里的 intro/credits/speed 共用 `player_preferences`。
 */
object PlayerPrefs {
    private const val NAME = "player_preferences"
    private const val KEY_DECODE = "decode_mode"
    private const val KEY_ENGINE = "player_engine"
    private const val KEY_AUTO_SOFT = "auto_soft_on_decode_error"
    private const val KEY_AUTO_LINE = "auto_line_on_error"
    private const val KEY_DANMAKU = "danmaku_enabled"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun decodeMode(context: Context): DecodeMode =
        DecodeMode.fromWire(prefs(context).getString(KEY_DECODE, DecodeMode.AUTO.wire))

    fun setDecodeMode(context: Context, mode: DecodeMode) {
        prefs(context).edit().putString(KEY_DECODE, mode.wire).apply()
    }

    fun engineType(context: Context): PlayerEngineType =
        PlayerEngineType.fromWire(prefs(context).getString(KEY_ENGINE, PlayerEngineType.EXO.wire))

    fun setEngineType(context: Context, type: PlayerEngineType) {
        prefs(context).edit().putString(KEY_ENGINE, type.wire).apply()
    }

    fun autoSoftOnDecodeError(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SOFT, true)

    fun setAutoSoftOnDecodeError(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SOFT, enabled).apply()
    }

    fun autoLineOnError(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_LINE, true)

    fun setAutoLineOnError(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_LINE, enabled).apply()
    }

    fun danmakuEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DANMAKU, false)

    fun setDanmakuEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DANMAKU, enabled).apply()
    }
}
