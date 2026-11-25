package com.nextmovie.ui.player.engine

import androidx.media3.common.PlaybackException

/**
 * 解码 / 起播失败时的自动恢复策略：
 * 1. 解码错误且当前非软解 → 切 SOFT 重试同一条 URL
 * 2. 仍失败或 IO 错误 → 建议换下一条线路
 */
object PlaybackRecovery {

    enum class Action {
        /** 用软解重建 ExoPlayer 再播同一 URL */
        RETRY_SOFT,
        /** 切换到 slots 下一条 */
        SWITCH_LINE,
        /** 无可自动动作，只展示错误文案 */
        SHOW_ERROR,
    }

    data class Decision(
        val action: Action,
        val message: String,
        val nextDecodeMode: DecodeMode? = null,
    )

    fun decide(
        error: PlaybackException,
        currentMode: DecodeMode,
        alreadyTriedSoft: Boolean,
        hasMoreLines: Boolean,
        autoSoft: Boolean,
        autoLine: Boolean,
        /** 本机是否真的存在扩展软解（FFmpeg AAR）；没有时切 SOFT 只是空转 */
        softAvailable: Boolean = true,
    ): Decision {
        val decodeFail = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED

        if (decodeFail && autoSoft && softAvailable && !alreadyTriedSoft && currentMode != DecodeMode.SOFT) {
            return Decision(
                action = Action.RETRY_SOFT,
                message = "硬解失败，正在切换软解重试…",
                nextDecodeMode = DecodeMode.SOFT,
            )
        }

        if (autoLine && hasMoreLines) {
            val reason = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "源站拒绝请求"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连不上源站"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "容器无法解析"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码仍失败"
                else -> "播放失败"
            }
            return Decision(
                action = Action.SWITCH_LINE,
                message = "$reason，正在切换下一条线路…",
            )
        }

        val msg = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "源站拒绝了这次请求（403/404，多为区域限制或防盗链），换条线路试试"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "网络连不上源站，检查网络或换个线路"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "这个地址不是可播放的视频流（可能需要网页解析），换条线路"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "解码失败。可在设置中改为「软解优先」，或换线路"
            else -> error.errorCodeName
        }
        return Decision(Action.SHOW_ERROR, msg)
    }
}
