package com.nextmovie.core.spider.native

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.net.Http

/**
 * 原生适配器：不走 DexClassLoader / Init / Go 本地代理，直接 HTTP。
 * 对齐 Marco「读常量 → 直连」策略，仅覆盖已摸清协议的家族。
 */
interface NativeSpider {
    /** 是否认领该站点（只看 api / ext，不做 IO）。 */
    fun matches(site: Site): Boolean

    suspend fun home(site: Site): List<Vod>
    suspend fun categories(site: Site): List<Pair<String, String>>
    suspend fun category(site: Site, tid: String, page: Int): List<Vod>
    suspend fun detail(site: Site, id: String): VodDetail?
    suspend fun search(site: Site, key: String): List<Vod>
    suspend fun play(site: Site, flag: String, id: String): PlayResult?
}

object NativeSpiderRegistry {
    private val spiders = mutableListOf<NativeSpider>()

    fun install(http: Http) {
        if (spiders.isNotEmpty()) return
        spiders += AppTtNativeSpider(http)
        spiders += WexHttpNativeSpider(http)
        spiders += ExtHostNativeSpider(http)
    }

    fun resolve(site: Site): NativeSpider? = spiders.firstOrNull { it.matches(site) }

    fun supportedPrefixes(): List<String> = listOf(
        "csp_AppTT", "csp_AppsX", "csp_AppSx",
        "csp_Wex", "csp_wex",
        "native:"
    )
}
