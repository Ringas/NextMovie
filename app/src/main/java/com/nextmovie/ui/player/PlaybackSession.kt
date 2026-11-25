package com.nextmovie.ui.player

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nextmovie.ui.player.engine.DecodeMode
import com.nextmovie.ui.player.engine.ExoPlayerEngine
import com.nextmovie.ui.player.engine.NmRenderersFactory
import com.nextmovie.ui.player.engine.PlayerPrefs

/**
 * 跨页面存的播放会话（对齐 Marco 的「返回详情后影片继续播」）。
 *
 * 解码模式变化时通过 [rebuildWithDecodeMode] 重建底层 ExoPlayer。
 */
@OptIn(UnstableApi::class)
class PlaybackSession(context: Context) {
    private val appContext = context.applicationContext
    private var decodeMode: DecodeMode = PlayerPrefs.decodeMode(appContext)

    private val engine = ExoPlayerEngine(appContext, decodeMode)

    val player: ExoPlayer
        get() = engine.player

    private var playerView: PlayerView? = null

    /** 当前会话是否仍在播放（返回详情后置 true，进首页/换片时置 false） */
    var alive by mutableStateOf(false)

    var vodName: String? = null
    var episodeName: String? = null

    /** 本会话是否已尝试过软解降级（防止死循环） */
    var triedSoftFallback: Boolean = false

    fun currentDecodeMode(): DecodeMode = decodeMode

    fun view(context: Context): PlayerView {
        val v = playerView ?: PlayerView(context).apply {
            player = engine.player
            useController = false
            keepScreenOn = true
        }.also { playerView = it }
        (v.parent as? ViewGroup)?.removeView(v)
        v.player = engine.player
        return v
    }

    /** 硬解失败后切软解：重建 ExoPlayer，保留大致进度。 */
    fun rebuildWithDecodeMode(mode: DecodeMode): ExoPlayer {
        decodeMode = mode
        if (mode == DecodeMode.SOFT) triedSoftFallback = true
        val p = engine.rebuild(mode)
        playerView?.player = p
        return p
    }

    fun release() {
        alive = false
        triedSoftFallback = false
        playerView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        playerView = null
        engine.release()
    }

    companion object {
        fun ffmpegNote(): String =
            if (NmRenderersFactory.ffmpegAvailable()) "FFmpeg 软解已就绪"
            else "未检测到 FFmpeg AAR（仅 MediaCodec）；可将 media3-decoder-ffmpeg 放入 app/libs"
    }
}
