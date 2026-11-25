package com.nextmovie.core.spider

import android.content.Context
import android.os.Bundle
import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import com.nextmovie.core.spider.SpiderProtocol.ACTION_CATEGORY
import com.nextmovie.core.spider.SpiderProtocol.ACTION_DETAIL
import com.nextmovie.core.spider.SpiderProtocol.ACTION_HOME
import com.nextmovie.core.spider.SpiderProtocol.ACTION_PLAY
import com.nextmovie.core.spider.SpiderProtocol.ACTION_SEARCH
import com.nextmovie.core.spider.SpiderProtocol.KEY_ARG1
import com.nextmovie.core.spider.SpiderProtocol.KEY_ARG2
import com.nextmovie.core.spider.SpiderProtocol.KEY_ARG3
import com.nextmovie.core.spider.SpiderProtocol.KEY_FILTER
import com.nextmovie.core.spider.SpiderProtocol.KEY_PAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.nextmovie.core.spider.native.NativeSpiderRegistry

/**
 * 插件调度层：按 `api` 前缀把请求分发给 JAR / JS / PY / CMS。
 *
 * JAR 插件的实际执行在 `:spider` 独立进程（见 [SpiderService]），这里只负责传参与解析结果，
 * 插件崩溃不会带走主界面。
 */
class SpiderHost(
    private val context: Context,
    private val http: Http
) {

    class UnsupportedSource(message: String) : Exception(message)

    private val client = SpiderClient(context.applicationContext)

    init {
        NativeSpiderRegistry.install(http)
    }

    @Volatile private var cancelledLogin = false
    fun cancelPlayback() { cancelledLogin = true }
    suspend fun preparePlayback() {
        if (cancelledLogin) {
            client.restartForLogin()
            cancelledLogin = false
            homeRequests.clear()
        }
    }

    fun release() { homeRequests.close(); client.close() }

    /** home 的原始 JSON 按站点缓存：分类 Tab 与首页列表来自同一次请求，避免重复打源站。 */
    private val homeRequests = com.nextmovie.core.cache.RequestCache<String, JSONObject>(120_000, 32)

    
    /**
     * 原生适配优先：成功则完全跳过 JAR Init / Go 代理。
     * 失败（协议不匹配、缺 signKey、需人机验证）返回 null，由调用方走插件路径。
     */
    private suspend fun <T> tryNative(site: Site, label: String, block: suspend (com.nextmovie.core.spider.native.NativeSpider) -> T): T? {
        val spider = NativeSpiderRegistry.resolve(site) ?: return null
        return runCatching { block(spider) }
            .onSuccess {
                android.util.Log.i("NativeSpider", "$label ok api=${site.api} site=${site.name}")
            }
            .onFailure {
                android.util.Log.w("NativeSpider", "$label fallback api=${site.api} ${it.message}")
            }
            .getOrNull()
    }

    fun startBinding() = client.startBinding()

    suspend fun preconnect() = client.preconnect()

    suspend fun clearCache() { homeRequests.clear() }

    suspend fun home(site: Site): List<Vod> = withContext(Dispatchers.IO) {
        tryNative(site, "home") { it.home(site) }?.let { return@withContext it }
        ensureSpider(site, "home")
        val obj = requestHome(site)
        val listed = VodParser.list(obj)
        val firstCategory = obj.optJSONArray("class")?.optJSONObject(0)?.optString("type_id").orEmpty()
        val content = if (listed.isEmpty() && firstCategory.isNotBlank()) {
            category(site, firstCategory, 1, false, "")
        } else listed
        content.also {
            android.util.Log.d("NextMovieHome", "ids=" + it.take(3).joinToString(" | ") { v -> v.id })
        }
    }

    /** 插件源的分类：TVBox 的 `class` 数组（type_id / type_name）。 */
    suspend fun categories(site: Site): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        tryNative(site, "categories") { it.categories(site) }?.let { return@withContext it }
        ensureSpider(site, "home")
        val obj = requestHome(site)
        val arr = obj.optJSONArray("class") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type_id", o.optString("type", ""))
            val name = o.optString("type_name", o.optString("name", ""))
            if (id.isBlank() || name.isBlank()) null else id to name
        }
    }

    private suspend fun requestHome(site: Site): JSONObject = homeRequests.get(site.runtimeKey) {
        // 不再吞掉异常：插件加载失败（如「只有 ARM 原生库」）必须让用户看到原因，
        // 否则首页只会显示「这个源暂时没有返回内容」，等于把可诊断的问题变成玄学。
        val raw = client.request(ACTION_HOME, site)
        runCatching { JSONObject(raw) }.getOrElse {
            android.util.Log.w("NextMovieHome", "插件源 home 返回无法解析：${raw.take(120)}")
            JSONObject()
        }
    }

    /**
     * 这个 Guard 插件（豆豆/饭太硬这类豆瓣源）的 home 列表只给 `vod_name` + `vod_pic`，
     * 不给 `vod_id`；它的真实 id 是 `msearch:###片名###海报URL` 这种复合串（category 返回里可见）。
     * home 缺 id 时用同样的格式本地拼一个，详情页才能被插件认出来。
     */
    private fun withSyntheticId(vod: Vod): Vod {
        val needsId = vod.id.isBlank() || vod.id == vod.name
        if (!needsId) return vod
        if (vod.name.isBlank()) return vod
        return vod.copy(id = "msearch:###" + vod.name + "###" + vod.pic)
    }

    suspend fun category(site: Site, tid: String, page: Int, filter: Boolean, extend: String): List<Vod> =
        withContext(Dispatchers.IO) {
            tryNative(site, "category") { it.category(site, tid, page) }?.let { return@withContext it }
            ensureSpider(site, "category")
            val raw = client.request(ACTION_CATEGORY, site) {
                putString(KEY_ARG1, tid)
                putString(KEY_PAGE, page.toString())
                putBoolean(KEY_FILTER, filter)
                if (extend.isNotBlank()) putString(KEY_ARG3, extend)
            }
            VodParser.list(JSONObject(raw))
        }

    suspend fun detail(site: Site, id: String, altId: String? = null): VodDetail? = withContext(Dispatchers.IO) {
        tryNative(site, "detail") { it.detail(site, id) }?.let { return@withContext it }
        ensureSpider(site, "detail")
        var raw = client.request(ACTION_DETAIL, site) { putString(KEY_ARG1, id) }
        // 有些插件 homeVod 根本不带 vod_id，只给片名；拿名字当 id 再试一次，
        // 比重猜一个 id 格式靠谱。
        if ((raw.isBlank() || raw == "{}") && !altId.isNullOrBlank() && altId != id) {
            raw = client.request(ACTION_DETAIL, site) { putString(KEY_ARG1, altId) }
        }
        val root = JSONObject(raw)
        val vod = VodParser.list(root).firstOrNull() ?: return@withContext null
        val item = root.optJSONArray("list")?.optJSONObject(0)
        VodDetail(vod, item?.let { VodParser.playLines(it) } ?: emptyList())
    }

    suspend fun search(site: Site, keyword: String): List<Vod> = withContext(Dispatchers.IO) {
        tryNative(site, "search") { it.search(site, keyword) }?.let { return@withContext it }
        ensureSpider(site, "search")
        val raw = client.request(ACTION_SEARCH, site) {
            putString(KEY_ARG1, keyword)
            putString(KEY_ARG2, "1")
            putBoolean(KEY_FILTER, site.quickSearch)
        }
        VodParser.list(JSONObject(raw))
    }

    suspend fun play(site: Site, flag: String, id: String, vip: List<String> = emptyList()): PlayResult? =
        withContext(Dispatchers.IO) {
            tryNative(site, "play") { it.play(site, flag, id) }?.let { return@withContext it }
            ensureSpider(site, "play")
            val raw = client.request(ACTION_PLAY, site, timeoutMs = 325_000) {
                putString(KEY_ARG1, flag)
                putString(KEY_ARG2, id)
            }
            val result = JSONObject(raw)
            PlayResult.fromJson(result) ?: run {
                val note = result.optString("errMsg").ifBlank { result.optString("msg") }.ifBlank { "来源未返回播放地址" }
                // Guard cloud clients can remember a dismissed login for the lifetime of their class loader.
                // Reset only the isolated runtime on the next explicit play; preserve all persisted credentials.
                if (note.contains("登录") || note.contains("login", true)) cancelledLogin = true
                throw SpiderClient.SpiderException(note)
            }
        }

    fun releasePlaybackProxy(url: String) = client.releasePlaybackProxy(url)

    private fun ensureSpider(site: Site, action: String) {
        when {
            // 插件源（加壳 jar）与 JS 源都由 :spider 进程加载，这里只放行，具体能力由服务端判断
            site.api.startsWith("csp_") || site.api.endsWith(".jar") -> Unit
            site.isJavaScript -> Unit
            site.api.endsWith(".py") ->
                throw UnsupportedSource("Python 插件计划在 P2 支持：${site.name}")
            site.api.startsWith("node:") ->
                throw UnsupportedSource("Node 运行时不在 v0 范围内：${site.name}")
            else -> throw UnsupportedSource("站点 ${site.name} 不是插件源，请用 CMS 通路（$action）")
        }
    }
}
