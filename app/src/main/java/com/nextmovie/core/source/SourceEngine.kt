package com.nextmovie.core.source

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail

/**
 * 统一源引擎接口。CMS / JAR / JS / Python / CatPaw / 纯适配器都实现此契约，
 * UI 与 [com.nextmovie.core.AppRepository] 只依赖本接口，不感知底层运行时。
 *
 * 返回值约定与现有 Spider / CMS 一致：列表为空表示「无数据」而非错误；
 * 错误通过抛出 [SourceException] 表达，消息必须是可直接展示的中文。
 */
interface SourceEngine {
    val kind: SourceKind

    /** 该引擎是否已在本构建中启用（未接运行时的返回 false）。 */
    val isAvailable: Boolean

    suspend fun home(site: Site, filter: Boolean = false): List<Vod>

    suspend fun categories(site: Site): List<Pair<String, String>>

    suspend fun category(
        site: Site,
        tid: String,
        page: String = "1",
        filter: Boolean = false,
        extend: Map<String, String> = emptyMap()
    ): List<Vod>

    suspend fun detail(site: Site, id: String, name: String = ""): VodDetail?

    suspend fun search(site: Site, key: String, quick: Boolean = false, page: String? = null): List<Vod>

    suspend fun play(site: Site, flag: String, id: String): PlayResult?
}

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 未实现运行时的占位引擎：保证路由表完整，用户看到明确中文原因而不是崩溃。
 */
class UnavailableEngine(
    override val kind: SourceKind,
    private val reason: String
) : SourceEngine {
    override val isAvailable: Boolean = false

    private fun fail(): Nothing = throw SourceException(reason)

    override suspend fun home(site: Site, filter: Boolean) = fail()
    override suspend fun categories(site: Site) = fail()
    override suspend fun category(
        site: Site,
        tid: String,
        page: String,
        filter: Boolean,
        extend: Map<String, String>
    ) = fail()
    override suspend fun detail(site: Site, id: String, name: String) = fail()
    override suspend fun search(site: Site, key: String, quick: Boolean, page: String?) = fail()
    override suspend fun play(site: Site, flag: String, id: String) = fail()
}
