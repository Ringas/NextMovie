package com.nextmovie.ui.player.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * 本机解码能力自检。
 *
 * 设置页「解码自检」用这份数据回答一个很实际的问题：
 * 这台盒子到底能不能硬解 HEVC 10bit / AV1 / 杜比视界，以及 AC3/EAC3/DTS 音频谁来解。
 * 只有「不支持」且没有 FFmpeg 扩展时，才会出现黑屏无声——先看这里，再怀疑片源。
 */
object DecoderProbe {

    data class Capability(
        val label: String,
        val mime: String,
        val hardware: Boolean,
        val software: Boolean,
    ) {
        val status: String
            get() = when {
                hardware -> "硬解"
                software -> "仅软解"
                else -> "不支持"
            }

        val ok: Boolean get() = hardware || software
    }

    private val videoTargets = listOf(
        "H.264" to "video/avc",
        "HEVC" to "video/hevc",
        "HEVC 10bit" to "video/hevc:main10",
        "AV1" to "video/av01",
        "VP9" to "video/x-vnd.on2.vp9",
        "Dolby Vision" to "video/dolby-vision",
    )

    private val audioTargets = listOf(
        "AAC" to "audio/mp4a-latm",
        "AC3" to "audio/ac3",
        "EAC3" to "audio/eac3",
        "DTS" to "audio/vnd.dts",
        "DTS-HD" to "audio/vnd.dts.hd",
        "TrueHD" to "audio/true-hd",
    )

    fun videoCapabilities(): List<Capability> = videoTargets.map { (label, key) ->
        if (key.endsWith(":main10")) {
            val (hw, sw) = hevcMain10()
            Capability(label, "video/hevc", hw, sw)
        } else {
            val (hw, sw) = support(key)
            Capability(label, key, hw, sw)
        }
    }

    fun audioCapabilities(): List<Capability> = audioTargets.map { (label, mime) ->
        val (hw, sw) = support(mime)
        Capability(label, mime, hw, sw)
    }

    /** FFmpeg 音频扩展是否已随包打入 */
    fun ffmpegReady(): Boolean = NmRenderersFactory.ffmpegAvailable()

    /** 一行摘要，给设置页做副标题 */
    fun summary(): String {
        val video = videoCapabilities()
        val audio = audioCapabilities()
        val hdr = video.firstOrNull { it.label == "HEVC 10bit" }?.ok == true
        val dv = video.firstOrNull { it.label == "Dolby Vision" }?.ok == true
        val eac3 = audio.firstOrNull { it.label == "EAC3" }?.ok == true
        return buildString {
            append(if (hdr) "10bit OK" else "10bit 缺失")
            append(" · ")
            append(if (dv) "DV OK" else "DV 缺失")
            append(" · ")
            append(if (eac3) "EAC3 OK" else "EAC3 缺失")
        }
    }

    private fun support(mime: String): Pair<Boolean, Boolean> {
        var hw = false
        var sw = false
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder) return@forEach
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) return@forEach
                if (isHardware(info)) hw = true else sw = true
            }
        }
        return hw to sw
    }

    private fun hevcMain10(): Pair<Boolean, Boolean> {
        var hw = false
        var sw = false
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder) return@forEach
                if (!info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }) return@forEach
                val profiles = runCatching {
                    info.getCapabilitiesForType("video/hevc").profileLevels
                }.getOrNull() ?: return@forEach
                val main10 = profiles.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                if (!main10) return@forEach
                if (isHardware(info)) hw = true else sw = true
            }
        }
        return hw to sw
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { info.isHardwareAccelerated }.getOrDefault(false)
        }
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") &&
            !name.contains(".sw.") &&
            !name.contains("software")
    }
}
