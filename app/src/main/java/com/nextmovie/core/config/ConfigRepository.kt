package com.nextmovie.core.config

import com.nextmovie.core.net.Http
import com.nextmovie.core.net.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

/**
 * 配置加载：单仓 / 多仓（urls、storeHouse、depots）统一入口。
 *
 * 相对地址处理与生态一致：`./`、`../` 按配置文件地址解析，
 * 这样 `ext` / `jar` 写成相对路径的订阅也能用。
 */
class ConfigRepository(private val http: Http) {

    class ConfigError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val relativeToken = Regex("\"(\\.{1,2}/[^\"]*)\"")

    var lastResolvedUrl: String = ""

    suspend fun loadText(text: String): ConfigBundle = withContext(Dispatchers.IO) {
        val root = JSONObject(ConfigDecoder.sanitizeJson(text))
        val sites = ArrayList<Site>()
        val parses = ArrayList<Parse>()
        collect(root, "", 0, sites, Site.spiderJar(root), parses)
        if (sites.isEmpty()) throw ConfigError("JSON 中没有来源")
        ConfigBundle(sites.distinctBy { it.runtimeKey }, parses.distinctBy { it.url })
    }

    suspend fun load(url: String): ConfigBundle = withContext(Dispatchers.IO) {
        val root = fetchRoot(url, depth = 0)
        lastResolvedUrl = root.second
        val sites = ArrayList<Site>()
        val parses = ArrayList<Parse>()
        collect(root.first, root.second, 0, sites, Site.spiderJar(root.first), parses)
        if (sites.isEmpty()) throw ConfigError("配置里没有可用站点：$url")
        ConfigBundle(sites.distinctBy { it.runtimeKey }, parses.distinctBy { it.url })
    }

    /** 不少源站按 UA 分流：okhttp 系给配置，浏览器 UA 给网页；逐个试。 */
    private val userAgents = listOf(Http.UA, "okhttp/3.12.13", Http.BROWSER_UA)
    private val jsonLink = Regex("https?://[\\w\\-.:%/@!$&'()*+,;=?~#\\u4e00-\\u9fa5]+?\\.json")

    private suspend fun fetchRoot(url: String, depth: Int): Pair<JSONObject, String> {
        if (depth > Warehouse.MAX_DEPTH) throw ConfigError("仓库嵌套过深")
        val asciiUrl = Urls.ascii(url)

        var httpError: Throwable? = null
        var html: String? = null
        var decodeError: Throwable? = null

        for (ua in userAgents) {
            val text = try {
                http.text(asciiUrl, ua = ua)
            } catch (e: Exception) {
                httpError = e
                continue
            }
            if (text.trimStart().startsWith("<")) {
                html = text
                continue
            }
            val root = runCatching { JSONObject(absolutize(ConfigDecoder.sanitizeJson(text), asciiUrl)) }
                .getOrNull() ?: run { decodeError = ConfigError("配置不是合法 JSON：$asciiUrl"); null }
            if (root != null) return root to asciiUrl
        }

        // 不少「短地址」其实是导航页：自动在页面里找 .json/.txt/.bmp 配置并试一次
        if (html != null) {
            for (candidate in configLinks(html, asciiUrl)) {
                val page = runCatching { http.text(candidate, ua = Http.BROWSER_UA) }
                    .recoverCatching { http.text(candidate, ua = Http.UA) }
                    .getOrNull() ?: continue
                val root = runCatching {
                    JSONObject(absolutize(ConfigDecoder.sanitizeJson(page), candidate))
                }.getOrNull()
                if (root != null) return root to candidate
            }
        }

        val code = (httpError as? Http.HttpError)?.code
        throw when {
            html != null -> ConfigError(htmlHint(asciiUrl, html))
            code != null -> ConfigError(
                when (code) {
                    403, 401 -> "配置地址拒绝访问（HTTP $code），可能需要换网络或该地址已限制外部引用：$url"
                    502, 503, 504 -> "配置服务器暂时不可用（HTTP $code），请稍后重试或换备用地址：$url"
                    404 -> "配置地址不存在（HTTP 404），请检查是否写错：$url"
                    else -> "配置下载失败（HTTP $code）：$url"
                }
            )
            decodeError != null -> decodeError!!
            else -> ConfigError(
                "配置下载失败：" + (httpError?.message ?: "网络不可达") + "\n地址：$url"
            )
        }
    }

    /** 导航页里出现的配置链接（绝对或相对），最多取 4 个去试。 */
    private val configLink = Regex("""(?:https?:)?//[^\s"'<>]+?\.(?:json|txt|bmp)|/[^\s"'<>]+?\.(?:json|txt|bmp)""")

    private fun configLinks(html: String, base: String): List<String> =
        configLink.findAll(html)
            .map { Urls.resolve(base, it.value) }
            .filter { it.startsWith("http") }
            .distinct()
            .take(4)
            .toList()

    /** 地址其实是导航网页时，把页面里给出的 .json 配置挑出来提示用户。 */
    private fun htmlHint(url: String, html: String): String {
        val candidates = jsonLink.findAll(html).map { it.value }.distinct().take(3).toList()
        return if (candidates.isEmpty()) {
            "这个地址返回的是网页，不是配置 JSON。请填写 .json 结尾的接口地址：$url"
        } else {
            "这个地址返回的是网页，不是配置 JSON。可改用页面里的接口地址：\n" +
                candidates.joinToString("\n")
        }
    }

    private suspend fun collect(
        root: JSONObject,
        origin: String,
        depth: Int,
        out: MutableList<Site>,
        parentJar: String,
        parses: MutableList<Parse>
    ) {
        val jar = Site.spiderJar(root).ifBlank { parentJar }
        if (Warehouse.isCatalog(root)) {
            if (depth >= Warehouse.MAX_DEPTH) return
            val depots = Warehouse.depots(root).take(Warehouse.MAX_DEPOTS)
            for ((name, rawUrl) in depots) {
                val resolved = resolve(origin, rawUrl) ?: continue
                val child = runCatching { fetchRoot(resolved, depth + 1) }.getOrNull() ?: continue
                if (child.first.optJSONArray("sites")?.length() == 0 &&
                    !Warehouse.isCatalog(child.first)
                ) {
                    continue
                }
                runCatching { collect(child.first, child.second, depth + 1, out, jar, parses) }
                    .onFailure { /* 单个子仓失败不影响其它仓 */ }
                android.util.Log.d("NextMovie", "depot loaded: $name -> $resolved")
            }
            return
        }
        val bundle = ConfigBundle.fromJson(root, origin, jar)
        out += bundle.sites
        parses += bundle.parses
    }

    private fun resolve(origin: String, target: String): String? {
        if (target.isBlank()) return null
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        return runCatching { URI(origin).resolve(target).toString() }.getOrNull()
    }

    /** 把 JSON 文本里 `"./x.js"` 这类相对引用替换成绝对地址。 */
    private fun absolutize(text: String, origin: String): String =
        text.replace(relativeToken) { match ->
            val rel = match.groupValues[1]
            val abs = resolve(origin, rel)
            if (abs == null) match.value else "\"$abs\""
        }
}
