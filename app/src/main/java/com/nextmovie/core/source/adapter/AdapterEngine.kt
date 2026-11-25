package com.nextmovie.core.source.adapter

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.net.Http
import com.nextmovie.core.source.SourceEngine
import com.nextmovie.core.source.SourceException
import com.nextmovie.core.source.SourceKind

/**
 * 纯数据适配器引擎：不加载第三方 DEX，按注册表或内置 JsonFeed 解析。
 */
abstract class BaseSiteAdapter {
    abstract suspend fun home(site: Site): List<Vod>
    open suspend fun categories(site: Site): List<Pair<String, String>> = emptyList()
    open suspend fun category(site: Site, tid: String, page: String): List<Vod> = emptyList()
    open suspend fun detail(site: Site, id: String): VodDetail? = null
    open suspend fun search(site: Site, key: String): List<Vod> = emptyList()
    open suspend fun play(site: Site, flag: String, id: String): PlayResult? = null
}

object AdapterRegistry {
    private val byPrefix = linkedMapOf<String, BaseSiteAdapter>()

    fun register(apiPrefix: String, adapter: BaseSiteAdapter) {
        byPrefix[apiPrefix] = adapter
    }

    fun find(site: Site): BaseSiteAdapter? {
        val api = site.api
        byPrefix.entries.firstOrNull { api.startsWith(it.key) }?.let { return it.value }
        return null
    }

    fun clear() = byPrefix.clear()

    fun registeredPrefixes(): List<String> = byPrefix.keys.toList()
}

class AdapterEngine(private val http: Http = Http()) : SourceEngine {
    override val kind: SourceKind = SourceKind.ADAPTER
    override val isAvailable: Boolean = true

    private val jsonFeed by lazy { JsonFeedAdapter(http) }

    private fun resolve(site: Site): BaseSiteAdapter {
        AdapterRegistry.find(site)?.let { return it }
        if (JsonFeedAdapter.matches(site)) return jsonFeed
        throw SourceException(
            "「${site.name}」需要纯数据适配器（${site.api}），但尚未注册。" +
                "可使用 api: adapter:json:https://... 或 adapter:cms:https://..."
        )
    }

    override suspend fun home(site: Site, filter: Boolean): List<Vod> = resolve(site).home(site)

    override suspend fun categories(site: Site): List<Pair<String, String>> =
        resolve(site).categories(site)

    override suspend fun category(
        site: Site,
        tid: String,
        page: String,
        filter: Boolean,
        extend: Map<String, String>
    ): List<Vod> = resolve(site).category(site, tid, page)

    override suspend fun detail(site: Site, id: String, name: String): VodDetail? =
        resolve(site).detail(site, id)

    override suspend fun search(site: Site, key: String, quick: Boolean, page: String?): List<Vod> =
        resolve(site).search(site, key)

    override suspend fun play(site: Site, flag: String, id: String): PlayResult? =
        resolve(site).play(site, flag, id)
}
