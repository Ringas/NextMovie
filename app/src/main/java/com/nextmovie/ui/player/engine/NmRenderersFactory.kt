package com.nextmovie.ui.player.engine

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * NextMovie 渲染工厂：硬/软解偏好 + 可选 FFmpeg 音频扩展。
 *
 * FFmpeg：官方 Media3 不发布预编译 AAR。把自编译的
 * `media3-decoder-ffmpeg`（或 FongMi 同源包）放进 `app/libs/` 后，
 * 本工厂会用反射挂上 `FfmpegAudioRenderer`；没有 AAR 时静默降级为纯 MediaCodec。
 */
@OptIn(UnstableApi::class)
class NmRenderersFactory(
    context: Context,
    private val decodeMode: DecodeMode = DecodeMode.AUTO,
) : DefaultRenderersFactory(context.applicationContext) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(
            when (decodeMode) {
                DecodeMode.SOFT -> EXTENSION_RENDERER_MODE_PREFER
                DecodeMode.HARD -> EXTENSION_RENDERER_MODE_OFF
                DecodeMode.AUTO -> EXTENSION_RENDERER_MODE_ON
            },
        )
        // 软解优先时仍允许 MediaCodec 兜底；硬解优先时不强制关掉扩展以外的路径
        if (decodeMode == DecodeMode.SOFT) {
            setMediaCodecSelector(MediaCodecSelector.DEFAULT)
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // 软解优先：先尝试挂 FFmpeg，再走默认链路
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER ||
            extensionRendererMode == EXTENSION_RENDERER_MODE_ON
        ) {
            maybeAddFfmpegAudioRenderer(out)
        }
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        // ON 模式：默认链路之后再补一次 FFmpeg 作为真正兜底（若前面反射失败）
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_ON &&
            out.none { it.javaClass.name.contains("Ffmpeg", ignoreCase = true) }
        ) {
            maybeAddFfmpegAudioRenderer(out)
        }
    }

    private fun maybeAddFfmpegAudioRenderer(out: ArrayList<Renderer>) {
        if (out.any { it.javaClass.name.contains("Ffmpeg", ignoreCase = true) }) return
        val renderer = runCatching {
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
            clazz.getDeclaredConstructor().newInstance() as Renderer
        }.getOrNull()
        if (renderer != null) {
            out.add(0, renderer)
            android.util.Log.i(TAG, "FfmpegAudioRenderer attached (mode=$decodeMode)")
        } else {
            android.util.Log.d(TAG, "FFmpeg AAR not present; MediaCodec only")
        }
    }

    companion object {
        private const val TAG = "NmRenderers"

        fun create(context: Context, mode: DecodeMode = PlayerPrefs.decodeMode(context)): NmRenderersFactory =
            NmRenderersFactory(context, mode)

        /** 运行时是否检测到 FFmpeg 扩展类 */
        fun ffmpegAvailable(): Boolean =
            runCatching {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
                true
            }.getOrDefault(false)
    }
}
