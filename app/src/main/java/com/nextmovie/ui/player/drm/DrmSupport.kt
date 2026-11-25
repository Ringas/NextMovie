package com.nextmovie.ui.player.drm

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.datasource.HttpDataSource
import java.util.UUID

/**
 * DRM 脚手架：Widevine / ClearKey。
 * 当 play 结果带 license URL 或 #KODIPROP 风格字段时组装 [MediaItem.DrmConfiguration]。
 * 无 license 时返回 null，走普通清流。
 */
@OptIn(UnstableApi::class)
object DrmSupport {

    data class Spec(
        val scheme: Scheme = Scheme.WIDEVINE,
        val licenseUrl: String? = null,
        /** ClearKey：base64 keyId:key 对，或完整 JSON keys 体 */
        val clearKeyJson: String? = null,
        val multiSession: Boolean = false,
        val headers: Map<String, String> = emptyMap(),
    ) {
        enum class Scheme { WIDEVINE, PLAYREADY, CLEARKEY }
    }

    fun uuidOf(scheme: Spec.Scheme): UUID = when (scheme) {
        Spec.Scheme.WIDEVINE -> C.WIDEVINE_UUID
        Spec.Scheme.PLAYREADY -> C.PLAYREADY_UUID
        Spec.Scheme.CLEARKEY -> C.CLEARKEY_UUID
    }

    fun mediaItemDrm(spec: Spec?): MediaItem.DrmConfiguration? {
        if (spec == null) return null
        val builder = MediaItem.DrmConfiguration.Builder(uuidOf(spec.scheme))
            .setMultiSession(spec.multiSession)
        if (!spec.licenseUrl.isNullOrBlank()) {
            builder.setLicenseUri(spec.licenseUrl)
            if (spec.headers.isNotEmpty()) builder.setLicenseRequestHeaders(spec.headers)
        }
        return builder.build()
    }

    /**
     * 从 play headers / extra 里粗解析 license。
     * 常见键：license、license_url、drmLicense、#KODIPROP 拼接串。
     */
    fun parseFromPlayMeta(
        headers: Map<String, String>,
        extra: Map<String, String> = emptyMap(),
    ): Spec? {
        val merged = headers + extra
        val license = listOf("license", "license_url", "licenseUrl", "drmLicense", "wv_license")
            .firstNotNullOfOrNull { k -> merged[k]?.takeIf { it.startsWith("http") } }
        val clearKey = merged["clearkey"] ?: merged["clear_key"]
        val scheme = when {
            !clearKey.isNullOrBlank() -> Spec.Scheme.CLEARKEY
            merged["drm"]?.contains("playready", true) == true -> Spec.Scheme.PLAYREADY
            else -> Spec.Scheme.WIDEVINE
        }
        if (license == null && clearKey.isNullOrBlank()) return null
        return Spec(scheme = scheme, licenseUrl = license, clearKeyJson = clearKey)
    }

    fun sessionManagerProvider(
        httpFactory: HttpDataSource.Factory,
        spec: Spec?,
    ): DrmSessionManagerProvider? {
        if (spec == null) return null
        return DrmSessionManagerProvider {
            buildSessionManager(httpFactory, spec) ?: DrmSessionManager.DRM_UNSUPPORTED
        }
    }

    private fun buildSessionManager(
        httpFactory: HttpDataSource.Factory,
        spec: Spec,
    ): DrmSessionManager? = runCatching {
        val uuid = uuidOf(spec.scheme)
        if (!FrameworkMediaDrm.isCryptoSchemeSupported(uuid)) return@runCatching null
        val callback = when {
            !spec.clearKeyJson.isNullOrBlank() ->
                LocalMediaDrmCallback(spec.clearKeyJson!!.toByteArray(Charsets.UTF_8))
            !spec.licenseUrl.isNullOrBlank() ->
                HttpMediaDrmCallback(spec.licenseUrl, httpFactory).also { cb ->
                    spec.headers.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
                }
            else -> return@runCatching null
        }
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(spec.multiSession)
            .build(callback)
    }.getOrNull()
}
