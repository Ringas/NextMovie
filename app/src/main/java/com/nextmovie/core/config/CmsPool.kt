package com.nextmovie.core.config

import android.content.Context
import com.nextmovie.core.spider.SpiderProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 内置 CMS 直连源池。
 *
 * 定位：给「零内置源」的壳补一批**协议级**可直连的苹果 CMS V10 采集接口。
 * 与订阅的关系是叠加而不是替换——订阅里几乎全是 `csp_*` / `.js`，一个 CMS 站都没有，
 * 而这类接口解析成本最低（裸 JSON、无引擎、无字节码），是唯一能低成本把片库撑起来的源。
 *
 * 边界：池子里只存接口地址，不存任何影视内容；接口随公开来源变化，导入后由健康探针标红。
 */
object CmsPool {

    private const val ASSET = "sources/cms-pool.json"
    const val ORIGIN = "cms-pool"

    data class Entry(
        val key: String,
        val name: String,
        val api: String,
        val total: Int = 0,
        val verified: String = ""
    )

    fun entries(context: Context): List<Entry> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("stations") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val api = o.optString("api").trim()
            if (api.isBlank()) return@mapNotNull null
            Entry(
                key = o.optString("key").ifBlank { "cms_$i" },
                name = o.optString("name").ifBlank { api },
                api = api,
                total = o.optInt("total", 0),
                verified = o.optString("verified")
            )
        }
    }.getOrDefault(emptyList())

    fun sites(context: Context): List<Site> = entries(context).map { entry ->
        Site(
            key = entry.key,
            name = entry.name,
            api = entry.api,
            type = 0,
            searchable = true,
            quickSearch = true,
            origin = ORIGIN
        )
    }

    fun import(context: Context): List<Site> {
        val list = sites(context)
        ExtraSites.save(context, list)
        return list
    }

    fun isImported(context: Context): Boolean = ExtraSites.load(context).isNotEmpty()

    fun importedCount(context: Context): Int = ExtraSites.load(context).size

    fun clear(context: Context) = ExtraSites.clear(context)
}

/**
 * 内置源池落到本地：`filesDir/extra-sites.json`。
 *
 * 单独存一份而不是塞进配置快照，原因是订阅刷新、快照重建都不该把用户导入的源冲掉；
 * 加载订阅与命中快照两条路径都会走 [CmsPool] 的合并入口，保证行为一致。
 */
object ExtraSites {

    private const val FILE_NAME = "extra-sites.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<Site> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("sites") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    SpiderProtocol.siteFromTransport(arr.getJSONObject(i).toString())
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach { site ->
            arr.put(JSONObject(with(SpiderProtocol) { site.toTransport() }))
        }
        val json = JSONObject().put("updated", System.currentTimeMillis()).put("sites", arr)
        runCatching { file(context).writeText(json.toString()) }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 合并：外挂源追加在订阅站点之后，按 api 去重（订阅里已有同一接口就不重复加）。 */
    fun merge(base: List<Site>, extras: List<Site>): List<Site> {
        if (extras.isEmpty()) return base
        val existing = base.map { it.api.trim().lowercase() }.toMutableSet()
        val added = ArrayList<Site>(extras.size)
        extras.forEach { site ->
            if (existing.add(site.api.trim().lowercase())) added += site
        }
        return if (added.isEmpty()) base else base + added
    }
}
