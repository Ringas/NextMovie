package com.nextmovie.core.source.python

import android.content.Context
import android.util.Log
import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.net.Http
import com.nextmovie.core.source.SourceEngine
import com.nextmovie.core.source.SourceException
import com.nextmovie.core.source.SourceKind
import org.json.JSONObject
import java.io.File

/**
 * Python Spider 引擎。
 *
 * 默认不链接 Chaquopy（盒子内存）。若运行时 classpath 中存在
 * `com.chaquo.python.Python`，则通过反射尝试加载；否则给出明确中文错误。
 *
 * 额外兼容：部分「.py」实际是 JSON 接口地址或返回 JSON 的脚本 URL，
 * 会先尝试当纯 HTTP JSON 解析，避免整站不可用。
 */
object BuildFlags {
    /** 仅当集成 Chaquopy 依赖且验收通过后改为 true。 */
    const val PYTHON_ENABLED = false
}

class PythonEngine(
    private val http: Http = Http(),
    private val appContext: Context? = null
) : SourceEngine {

    override val kind: SourceKind = SourceKind.PYTHON
    override val isAvailable: Boolean
        get() = BuildFlags.PYTHON_ENABLED || ChaquopyBridge.isPresent()

    private fun deny(site: Site): Nothing = throw SourceException(
        "「${site.name}」是 Python 源（${site.api}）。当前构建未启用 Chaquopy；" +
            "可改用同配置下 CMS / JAR / JS，或换不带 .py 的订阅。"
    )

    override suspend fun home(site: Site, filter: Boolean): List<Vod> {
        tryJsonFallback(site)?.let { return it }
        if (!isAvailable) deny(site)
        return invokeSpider(site, "homeContent", filter.toString())?.let { VodParser.list(JSONObject(it)) }
            ?: emptyList()
    }

    override suspend fun categories(site: Site): List<Pair<String, String>> {
        if (!isAvailable) return emptyList()
        val raw = invokeSpider(site, "homeContent", "false") ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("class") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("type_id")
            val name = o.optString("type_name")
            if (id.isNotBlank() && name.isNotBlank()) out += id to name
        }
        return out
    }

    override suspend fun category(
        site: Site,
        tid: String,
        page: String,
        filter: Boolean,
        extend: Map<String, String>
    ): List<Vod> {
        if (!isAvailable) deny(site)
        val raw = invokeSpider(site, "categoryContent", tid, page, filter.toString()) ?: return emptyList()
        return runCatching { VodParser.list(JSONObject(raw)) }.getOrDefault(emptyList())
    }

    override suspend fun detail(site: Site, id: String, name: String): VodDetail? {
        if (!isAvailable) deny(site)
        val raw = invokeSpider(site, "detailContent", id) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val item = root.optJSONArray("list")?.optJSONObject(0) ?: return null
        val vod = VodParser.fromJson(item) ?: return null
        return VodDetail(vod, VodParser.playLines(item))
    }

    override suspend fun search(site: Site, key: String, quick: Boolean, page: String?): List<Vod> {
        tryJsonFallback(site, key)?.let { return it }
        if (!isAvailable) deny(site)
        val raw = invokeSpider(site, "searchContent", key, quick.toString()) ?: return emptyList()
        return runCatching { VodParser.list(JSONObject(raw)) }.getOrDefault(emptyList())
    }

    override suspend fun play(site: Site, flag: String, id: String): PlayResult? {
        if (!isAvailable) deny(site)
        val raw = invokeSpider(site, "playerContent", flag, id) ?: return null
        return runCatching { PlayResult.fromJson(JSONObject(raw)) }.getOrNull()
    }

    /** 若 .py 地址实际返回 JSON 列表，直接当数据源用。 */
    private suspend fun tryJsonFallback(site: Site, keyword: String? = null): List<Vod>? {
        val url = site.api
        if (!url.startsWith("http")) return null
        val body = runCatching { http.text(url, site.headers) }.getOrNull() ?: return null
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val list = runCatching {
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { VodParser.fromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
            } else VodParser.list(JSONObject(trimmed))
        }.getOrNull() ?: return null
        if (list.isEmpty()) return null
        Log.i("NextMoviePy", "「${site.name}」.py 地址返回 JSON，按纯数据解析")
        if (keyword.isNullOrBlank()) return list
        val q = keyword.lowercase()
        return list.filter { it.name.lowercase().contains(q) }
    }

    private fun invokeSpider(site: Site, method: String, vararg args: String): String? {
        val ctx = appContext ?: return null
        if (!ChaquopyBridge.isPresent()) return null
        // 脚本路径缓存文件名；正文由宿主异步下载后写入时再接线
        val cache = File(ctx.filesDir, "py-cache").apply { mkdirs() }
        val file = File(cache, site.api.hashCode().toString(16) + ".py")
        if (!file.exists() || file.length() == 0L) {
            Log.w("NextMoviePy", "Python 脚本尚未缓存，跳过 Chaquopy 调用 method=$method")
            return null
        }
        return ChaquopyBridge.call(ctx, file.absolutePath, site.ext, method, args.toList())
    }
}

/**
 * 可选 Chaquopy 反射桥。未集成依赖时 [isPresent] 为 false。
 * 约定 Python 侧模块暴露与 CatVod 相同的方法名，返回 JSON 字符串。
 */
object ChaquopyBridge {
    fun isPresent(): Boolean =
        runCatching { Class.forName("com.chaquo.python.Python") }.isSuccess

    fun call(
        context: Context,
        scriptPath: String,
        ext: String,
        method: String,
        args: List<String>
    ): String? = runCatching {
        val pythonClz = Class.forName("com.chaquo.python.Python")
        val getInstance = pythonClz.getMethod("getInstance")
        val start = pythonClz.getMethod("start", Class.forName("com.chaquo.python.android.AndroidPlatform"))
        val platformClz = Class.forName("com.chaquo.python.android.AndroidPlatform")
        val platform = platformClz.getConstructor(Context::class.java).newInstance(context.applicationContext)
        if (!(pythonClz.getMethod("isStarted").invoke(null) as Boolean)) {
            start.invoke(null, platform)
        }
        val py = getInstance.invoke(null)
        val getModule = pythonClz.getMethod("getModule", String::class.java)
        // 用户脚本路径无法直接 getModule；此处仅预留扩展点，正式启用需按 FongMi 方式注入
        Log.w("NextMoviePy", "Chaquopy 已检测到，但脚本注入尚未接线 method=$method path=$scriptPath")
        null
    }.getOrNull()
}
