package com.nextmovie.ui.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * 按 URL / MIME 选择 HLS / DASH / Progressive。
 * 网盘直链、m3u8、mpd 在 TVBox 源里都常见。
 */
@OptIn(UnstableApi::class)
object MediaSourceBuilder {

    enum class Kind { HLS, DASH, PROGRESSIVE }

    fun detectKind(url: String, mimeHint: String? = null): Kind {
        val lower = url.lowercase()
        val mime = mimeHint?.lowercase().orEmpty()
        return when {
            mime.contains("dash") || mime.contains("mpd") -> Kind.DASH
            mime.contains("m3u8") || mime.contains("x-mpegurl") -> Kind.HLS
            lower.contains(".mpd") || lower.contains("format=mpd") || lower.contains("/dash/") -> Kind.DASH
            lower.contains(".m3u8") || lower.contains("format=m3u8") || lower.contains("/hls/") -> Kind.HLS
            else -> Kind.PROGRESSIVE
        }
    }

    fun build(
        url: String,
        dataSourceFactory: DataSource.Factory,
        headers: Map<String, String> = emptyMap(),
        mimeHint: String? = null,
        subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList(),
        drmConfig: MediaItem.DrmConfiguration? = null,
    ): MediaSource {
        val kind = detectKind(url, mimeHint)
        val itemBuilder = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
        when (kind) {
            Kind.HLS -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            Kind.DASH -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            Kind.PROGRESSIVE -> { /* sniff */ }
        }
        if (drmConfig != null) itemBuilder.setDrmConfiguration(drmConfig)
        val item = itemBuilder.build()

        return when (kind) {
            Kind.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(false)
                .createMediaSource(item)
            Kind.DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(item)
            Kind.PROGRESSIVE -> {
                if (subtitleConfigs.isNotEmpty() || drmConfig != null) {
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
                } else {
                    ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
                }
            }
        }
    }

    fun mimeForKind(kind: Kind): String? = when (kind) {
        Kind.HLS -> MimeTypes.APPLICATION_M3U8
        Kind.DASH -> MimeTypes.APPLICATION_MPD
        Kind.PROGRESSIVE -> null
    }
}
