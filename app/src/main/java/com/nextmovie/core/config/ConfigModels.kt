package com.nextmovie.core.config

import org.json.JSONArray
import org.json.JSONObject

/** 数据源站点定义，字段语义对齐 TVBox / CatVod 的 JSON 配置。 */
data class Site(
    val key: String,
    val name: String,
    val api: String,
    val type: Int = 0,
    val ext: String = "",
    val jar: String = "",
    /** 配置里 `jar;md5;<hash>` 的校验值；空表示作者没给，跳过校验 */
    val jarHash: String = "",
    val searchable: Boolean = true,
    val quickSearch: Boolean = true,
    val filterable: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** 站点所属配置的真实地址，用于解析 ext / jar 的相对路径 */
    val origin: String = ""
) {
    /** Identity includes configuration and plugin inputs; site keys alone are not globally unique. */
    val runtimeKey: String get() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(listOf(origin, key, api, jar, jarHash, ext, headers.toSortedMap().toString()).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

    val isJavaScript: Boolean get() = isJavaScriptApi(api)
    val isSpider: Boolean get() = type == 3 || api.startsWith("csp_") || isJavaScript || api.endsWith(".py") || api.startsWith("node:")
    val isHttpT4: Boolean get() = type == 4 && !isSpider &&
        (api.startsWith("http://", true) || api.startsWith("https://", true))
    val isCms: Boolean get() = !isSpider && (type == 0 || type == 1 || type == 4)

    /**
     * 站点当前能否加载；null 表示可以。原因用中文写清楚，直接展示给用户，
     * 避免把 OkHttp 的英文异常抛到电视上。
     */
    val unavailableReason: String?
        get() = when {
            isCms -> null
            isJavaScript -> null
            api.endsWith(".py") -> "PY 插件需要 Python 运行时，当前版本暂不支持"
            api.startsWith("node:") -> "Node 插件暂不支持"
            api.startsWith("csp_") || api.endsWith(".jar") ->
                if (jar.isBlank()) "配置没有提供 spider.jar，无法加载该插件站点" else null
            else -> "无法识别的源类型：$api"
        }

    val isPlayable: Boolean get() = unavailableReason == null

    companion object {
        private fun isJavaScriptApi(api: String): Boolean =
            api.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in setOf("js", "mjs")
        /** 配置根级 `spider` 字段：`"https://x.jar;md5;hash"` 或 `{"jar": "..."}`。 */
        /** 原样返回根级 `spider`（可能带 `;md5;hash`），拆分交给 [splitJarSpec]。 */
        fun spiderJar(root: JSONObject): String {
            val raw = when (val value = root.opt("spider")) {
                is String -> value
                is JSONObject -> value.optString("jar", value.optString("url", ""))
                else -> ""
            }
            return raw.trim()
        }

        /** `https://x/tv/x.jpg;md5;abc` → (url, hash)。省略写法 `url;abc` 也认。 */
        fun splitJarSpec(raw: String): Pair<String, String> {
            return PluginArtifact.split(raw)
        }

        fun fromJson(o: JSONObject, origin: String, fallbackJar: String = ""): Site {
            val api = o.optString("api", "").trim()
            val type = when {
                o.has("type") -> o.optInt("type", 0)
                api.startsWith("csp_") || isJavaScriptApi(api) || api.endsWith(".py") -> 3
                else -> 0
            }
            val rawJar = o.optString("jar", "").trim().ifBlank { fallbackJar }
            val (jarUrl, jarHash) = splitJarSpec(rawJar)
            return Site(
                key = o.optString("key", api).ifEmpty { api },
                name = o.optString("name", o.optString("key", "未命名")),
                api = api,
                type = type,
                ext = o.optString("ext", ""),
                jar = jarUrl,
                jarHash = jarHash,
                searchable = flag(o, "searchable", true),
                quickSearch = flag(o, "quickSearch", true),
                filterable = flag(o, "filter", false),
                headers = jsonToHeaders(o.optJSONObject("headers")),
                origin = origin
            )
        }

        private fun flag(o: JSONObject, key: String, default: Boolean): Boolean = when (val v = o.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.lowercase()) { "0", "false" -> false; "1", "true" -> true; else -> default }
            else -> default
        }

        private fun jsonToHeaders(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            o.keys().forEach { k -> out[k] = o.optString(k, "") }
            return out
        }
    }
}

/** 解析器（web parse）配置，v0 只保存不强依赖。 */
data class Parse(val name: String, val type: Int, val url: String)

data class ConfigBundle(
    val sites: List<Site>,
    val parses: List<Parse> = emptyList()
) {
    companion object {
        fun fromJson(root: JSONObject, origin: String, spiderJar: String = ""): ConfigBundle {
            val sites = ArrayList<Site>()
            root.optJSONArray("sites")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("api").isBlank()) continue
                    if (o.optInt("hide", 0) == 1) continue
                    sites += Site.fromJson(o, origin, spiderJar)
                }
            }
            val parses = ArrayList<Parse>()
            root.optJSONArray("parses")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parses += Parse(
                        name = o.optString("name", "解析"),
                        type = o.optInt("type", 0),
                        url = o.optString("url", "")
                    )
                }
            }
            return ConfigBundle(sites, parses)
        }
    }
}

/** 多仓 / 单仓目录识别。 */
object Warehouse {
    const val MAX_DEPOTS = 64
    const val MAX_DEPTH = 2

    fun isCatalog(root: JSONObject): Boolean {
        val hasSites = (root.optJSONArray("sites")?.length() ?: 0) > 0
        if (hasSites) return false
        return root.has("urls") || root.has("storeHouse") || root.has("depots")
    }

    /** 返回 (名称, 地址) 列表，字段名与生态保持一致。 */
    fun depots(root: JSONObject): List<Pair<String, String>> {
        val fields = listOf("urls", "storeHouse", "depots")
        val out = ArrayList<Pair<String, String>>()
        for (field in fields) {
            val arr: JSONArray = root.optJSONArray(field) ?: continue
            for (i in 0 until minOf(arr.length(), MAX_DEPOTS)) {
                when (val row = arr.opt(i)) {
                    is JSONObject -> {
                        val url = row.optString("url", row.optString("api", "")).trim()
                        if (url.isNotEmpty()) out += row.optString("name", "仓库 ${out.size + 1}") to url
                    }
                    is String -> if (row.isNotBlank()) out += "仓库 ${out.size + 1}" to row.trim()
                }
            }
        }
        return out
    }
}
