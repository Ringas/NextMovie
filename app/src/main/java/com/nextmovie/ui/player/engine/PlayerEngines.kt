package com.nextmovie.ui.player.engine

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 多引擎抽象：默认 Exo；IJK/MPV 在对应 so/AAR 可用时启用。
 */
interface PlayerEngine {
    val type: PlayerEngineType
    val player: Player?
    fun bindView(context: Context): View
    fun setMedia(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        mimeHint: String? = null,
    )
    fun setSpeed(speed: Float)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
    fun supportsSoftDecode(): Boolean = type == PlayerEngineType.EXO
}

@OptIn(UnstableApi::class)
class ExoPlayerEngine(
    context: Context,
    decodeMode: DecodeMode = DecodeMode.AUTO,
) : PlayerEngine {
    override val type: PlayerEngineType = PlayerEngineType.EXO

    private val appContext = context.applicationContext
    private var mode: DecodeMode = decodeMode
    override var player: ExoPlayer = buildPlayer(mode)
        private set

    private var playerView: PlayerView? = null

    private fun buildPlayer(decodeMode: DecodeMode): ExoPlayer =
        ExoPlayer.Builder(appContext, NmRenderersFactory.create(appContext, decodeMode)).build()

    /** 切换硬/软解需要重建 ExoPlayer（RenderersFactory 在 builder 时固化） */
    fun rebuild(decodeMode: DecodeMode): ExoPlayer {
        val pos = runCatching { player.currentPosition }.getOrDefault(0L)
        val playWhenReady = player.playWhenReady
        runCatching { player.release() }
        mode = decodeMode
        player = buildPlayer(decodeMode)
        playerView?.player = player
        player.playWhenReady = playWhenReady
        if (pos > 0) player.seekTo(pos)
        return player
    }

    override fun bindView(context: Context): View {
        val v = playerView ?: PlayerView(context).apply {
            player = this@ExoPlayerEngine.player
            useController = false
            keepScreenOn = true
        }.also { playerView = it }
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        return v
    }

    override fun setMedia(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        mimeHint: String?,
    ) {
        // 具体 MediaSource 仍由 PlayerScreen 组装（需 Measured DataSource / 字幕），此处仅占位
        val item = androidx.media3.common.MediaItem.fromUri(url)
        player.setMediaItem(item)
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.prepare()
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun play() {
        player.playWhenReady = true
        player.play()
    }

    override fun pause() {
        player.playWhenReady = false
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun release() {
        playerView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        playerView = null
        runCatching { player.release() }
    }

    override fun supportsSoftDecode(): Boolean = true
}

/**
 * IJK 占位：检测到 `tv.danmaku.ijk.media.player.IjkMediaPlayer` 时才可用。
 * 完整封装需单独 module；这里只暴露可用性探测，避免强依赖 native。
 */
object IjkBridge {
    fun available(): Boolean =
        runCatching {
            Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer")
            true
        }.getOrDefault(false)
}

object MpvBridge {
    fun available(): Boolean =
        runCatching {
            Class.forName("is.xyz.mpv.MPVLib")
            true
        }.getOrDefault(false)
}

object PlayerEngineFactory {
    fun create(context: Context, type: PlayerEngineType, decodeMode: DecodeMode): PlayerEngine {
        return when (type) {
            PlayerEngineType.IJK -> {
                if (IjkBridge.available()) {
                    android.util.Log.w("PlayerEngine", "IJK class found but full engine module not bundled; fallback Exo")
                }
                ExoPlayerEngine(context, decodeMode)
            }
            PlayerEngineType.MPV -> {
                if (MpvBridge.available()) {
                    android.util.Log.w("PlayerEngine", "MPV class found but full engine module not bundled; fallback Exo")
                }
                ExoPlayerEngine(context, decodeMode)
            }
            PlayerEngineType.EXO -> ExoPlayerEngine(context, decodeMode)
        }
    }

    fun availableTypes(): List<PlayerEngineType> = buildList {
        add(PlayerEngineType.EXO)
        if (IjkBridge.available()) add(PlayerEngineType.IJK)
        if (MpvBridge.available()) add(PlayerEngineType.MPV)
    }
}
