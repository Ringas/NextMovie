package com.nextmovie.core.spider

import android.util.Log
import com.github.catvod.crawler.Spider
import com.nextmovie.core.config.Site
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.QuickJS
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * TVBox `.js` 站点宿主（QuickJS）。
 * 长名/短名、ESM、request 兼容、setResult/local/gzip；完整 drpy+cheerio 仍可能依赖外链失败。
 *
 * Tier 1（运行时补齐）：setTimeout / setInterval / sleep、local 落盘、HOST / MY_URL / input、
 * cache(getCache/setCache) 别名。
 * Tier 2（选择器补齐）：pdfh / pdfa / pd，走 [JsSelector]（Jsoup CSS + drpy `&&` 语义）。
 */
class JsSpider(
    private val site: Site,
    private val code: String,
    private val scriptUrl: String,
    private val store: android.content.SharedPreferences? = null,
    cacheDir: java.io.File? = null
) : Spider() {
    /** 模块与 ext 规则文本的磁盘缓存：冷启动不再重新下载，第二次进站 init 能压到 2s 内 */
    private val diskDir: java.io.File? = cacheDir?.let { java.io.File(it, "js-modules") }


    @Volatile private var note: String? = null
    fun asyncNote(): String? = note

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "js-${site.key}") }
    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private val localStore = LinkedHashMap<String, String>()
    private val localPrefix = "js." + site.key.hashCode().toString(16) + "."
    private val moduleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 专供 ESM 外链模块：短超时 + 结果缓存，避免 5 个死链把一次调用拖到 30s+ */
    private val moduleHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(1200, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun assetText(path: String): String? = runCatching {
        val ctx = appContext ?: return@runCatching null
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 入口脚本里声明的 import（url → 具名导出列表），用于给失败的外链模块造占位导出 */
    private val declaredImports: Map<String, List<String>> by lazy {
        val out = LinkedHashMap<String, MutableList<String>>()
        var from = 0
        while (true) {
            val at = code.indexOf("import", from)
            if (at < 0) break
            from = at + 6
            val semi = code.indexOf(';', at)
            val end = if (semi < 0) code.length else semi
            if (end - at > 400) continue          // 不是 import 语句，跳过
            val stmt = code.substring(at, end)
            val quoted = Regex("\"([^\"]+)\"|'([^']+)'").find(stmt)
            val url = quoted?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }.orEmpty()
            if (url.isEmpty() || !url.contains("/")) continue
            val names = out.getOrPut(url) { mutableListOf() }
            val bs = stmt.indexOf('{')
            val be = stmt.indexOf('}')
            if (bs in 0 until be) {
                stmt.substring(bs + 1, be).split(',').forEach { raw ->
                    val n = raw.substringBefore(" as ").trim()
                    if (n.isNotEmpty() && n != "default") names.add(n)
                }
            }
        }
        out.mapValues { it.value.distinct() }
    }

    /** 远端模块缺声明里的导出时补上占位，保证 ESM 链接阶段不报 "Could not find export" */
    private fun patchModuleExports(url: String, body: String): String {
        val lower = body.trimStart().lowercase()
        val looksHtml = lower.startsWith("<") || lower.startsWith("<!doctype")
        if (looksHtml) return stubModuleFor(url)
        val sb = StringBuilder(body)
        val needDefault = !Regex("""export\s+default""").containsMatchIn(body)
        if (needDefault) sb.append("\nexport default {};\n")
        declaredImports[url].orEmpty().forEach { n ->
            val escaped = Regex.escape(n)
            if (Regex("""export\s+(const|let|var|function|class)\s+$escaped\b""").containsMatchIn(body)) return@forEach
            if (Regex("""export\s*\{[^}]*\b$escaped\b""").containsMatchIn(body)) return@forEach
            val binding = Regex("""(?:^|[^\w$])(?:var|let|const|function|class)\s+$escaped\b""").containsMatchIn(body)
            if (binding) {
                // 模块里有定义但没导出：补一行 export { x }
                sb.append("\nexport { ").append(n).append(" };\n")
            } else {
                sb.append("\nexport const ").append(n).append(" = undefined;\n")
            }
        }
        Log.d("NextMovieJs", "模块已适配 $url ${body.length}B")
        return sb.toString()
    }

    private fun stubModuleFor(url: String): String {
        Log.d("NextMovieJs", "stub url=$url names=${declaredImports[url]}")
        val names = declaredImports[url].orEmpty()
        val ident = Regex("""^[A-Za-z_$\u4e00-\u9fa5][A-Za-z0-9_$\u4e00-\u9fa5]*$""")
        val valid = names.filter { ident.matches(it) }

        // 模板模块：drpy2 的 init 第一行就是 模板.getMubans()，给空对象即可放行
        if (url.contains("模板") || url.contains("XUKQ", true)) {
            val lib = assetSrc("js/lib/muban.js").orEmpty()
            return buildString {
                if (lib.contains("getMubans")) {
                    append(lib.replace(Regex("""export\s+default[^;]*;"""),
                        "var __tpl={muban:muban,getMubans:function(){return muban},getMuban:function(){return muban}};"))
                    append("\ntry{globalThis.__nmMuban=__tpl.muban;}catch(e){}\n")
                } else {
                    append("var __tpl={getMubans:function(){return {}},getMuban:function(){return {}}});\n")
                }
                append("export default __tpl;\n")
                valid.filter { it != "模板" }.forEach { append("export const ").append(it).append(" = __tpl;\n") }
                append("export const 模板 = __tpl;\n")
            }
        }

        // 通用桩：可调用 + 任意取属性都返回自身，避免下游只得到 "not a function"
        return buildString {
            append("var __nmP=new Proxy(function(){return __nmP;},{")
            append("get:function(t,k){if(typeof k==='symbol')return undefined;return __nmP;},")
            append("apply:function(){return __nmP;}});\n")
            append("export default __nmP;\n")
            valid.forEach { append("export const ").append(it).append(" = __nmP;\n") }
        }
    }
    private val http by lazy {
        val ms = site.timeoutMs.takeIf { it > 0 } ?: 20_000
        OkHttpClient.Builder()
            .connectTimeout(minOf(ms, 12_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(ms.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(ms.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun homeContent(filter: Boolean): String = call("homeContent", filter)
    override fun homeVideoContent(): String = call("homeVideoContent")
    override fun categoryContent(
        tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?
    ): String = call("categoryContent", tid, pg, filter, extend ?: HashMap<String, String>())
    override fun detailContent(ids: List<String>?): String =
        call("detailContent", ids ?: emptyList<String>())
    override fun searchContent(key: String?, quick: Boolean): String =
        call("searchContent", key, quick)
    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        call("searchContent", key, quick, pg)
    override fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String =
        call("playerContent", flag, id, vipFlags ?: emptyList<String>())

    override fun destroy() {
        runCatching {
            worker.submit {
                runCatching { context?.close() }
                runCatching { runtime?.close() }
            }.get(3, TimeUnit.SECONDS)
        }
        context = null
        runtime = null
        runCatching { worker.shutdownNow() }
    }

    private fun <T> onWorker(timeoutSec: Long = 30, block: (JSContext) -> T): T {
        val task = worker.submit(Callable { block(ensureContext()) })
        return try {
            task.get(timeoutSec, TimeUnit.SECONDS)
        } finally {
            if (!task.isDone) task.cancel(true)
        }
    }

    private fun ensureContext(): JSContext {
        context?.let { return it }
        runCatching { System.loadLibrary("quickjs-android") }
        val rt = QuickJS.createRuntime()
        // drpy2 这类引擎带 ESM import，但它在模块里做的 eval 会被 QuickJS 按字节截断（含中文时直接
        // "unexpected end of input"），且 eval 看不到函数局部变量。宿主把引擎降级成普通脚本执行：
        // 内联 cheerio/crypto、去掉 import/export，eval 回到普通作用域，规则里 js: 代码块才能正常工作。
        val engineScript = scriptEngineSource(code)
        val isModule = engineScript == null && JsScriptSupport.isEsModule(code, scriptUrl)
        val ctx = if (isModule) object : com.quickjs.ES6Module(rt) {
            override fun convertModuleName(base: String, name: String): String {
                // assets:// 是宿主内置资产协议（cheerio / crypto-js 等），不做 URL 解析，原样交给 getModuleScript
                if (name.startsWith("assets://")) return name
                val resolved = runCatching { base.toHttpUrl().resolve(name)?.toString() }.getOrNull()
                    ?: runCatching { scriptUrl.toHttpUrl().resolve(name)?.toString() }.getOrNull()
                return resolved ?: name
            }

            override fun getModuleScript(name: String): String {
                Log.d("NextMovieJs", "getModuleScript $name")
                if (name == scriptUrl) return code
                if (name.startsWith("assets://")) {
                    val path = name.removePrefix("assets://")
                    val src = assetText(path)
                    if (src == null) {
                        Log.w("NextMovieJs", "内置资产缺失 $path，按占位模块继续")
                        return stubModuleFor(name)
                    }
                    // crypto-js 是 UMD：在模块作用域里没有 this/exports，必须包一层把它挂到 globalThis
                    return if (path.contains("crypto")) wrapCryptoJs(src) else src
                }
                // 外链 ESM 模块走独立短超时：down.nigx.cn 这类 CDN 常年挂，不能吃掉整次调用的预算
                moduleCache[name]?.let { return it }
                diskGet("mod:" + name)?.let { moduleCache[name] = it; return it }
                val body = runCatching {
                    val request = Request.Builder().url(name).header("User-Agent", defaultUa()).build()
                    moduleHttp.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        response.body?.string().orEmpty().ifBlank { stubModuleFor(name) }
                    }
                }.getOrElse { failure ->
                    // 外链模块挂掉不能拖死整站：按入口脚本声明的具名导出生成占位模块
                    Log.w("NextMovieJs", "模块不可用 $name：${failure.message}，按占位模块继续")
                    stubModuleFor(name)
                }
                // 有些 CDN 会返回 HTML/空壳而不是模块：按入口声明的导出补齐，避免链接期 SyntaxError
                val fixed = patchModuleExports(name, body)
                moduleCache[name] = fixed
                if (!body.isNullOrBlank()) diskPut("mod:" + name, fixed)
                return fixed
            }
        } else rt.createContext()
        try {
            registerBridge(ctx)
            if (engineScript != null) {
                // 引擎体里有 `let VODS/VOD/TABS` 这类顶层声明，直接铺在全局会和 bootstrap 的 var
                // 重名（redeclaration of 'VODS'）；包一层 IIFE 后它们成为闭包变量，
                // 引擎函数里的 eval 仍能看见自己的局部变量，互不冲突。
                ctx.executeVoidScript("var global=globalThis;(function(){\n" + engineScript + "\n})();", scriptUrl)
                Log.d("NextMovieJs", "引擎已就绪 __spider=" +
                    (ctx.executeStringScript("typeof globalThis.__spider", "chk") ?: "?"))
            } else if (ctx is com.quickjs.ES6Module) {
                val entry = JSONObject.quote(scriptUrl)
                ctx.executeModuleScript(
                    """
                    import * as plugin from $entry;
                    globalThis.__spider = plugin.default || plugin;
                    if (plugin.default && typeof plugin.default === 'object') {
                      for (var k in plugin) if (k !== 'default' && plugin[k] != null) {
                        try { if (plugin.default[k] == null) plugin.default[k] = plugin[k]; } catch (e) {}
                      }
                    }
                    """.trimIndent(),
                    scriptUrl.toHttpUrl().resolve("./__nextmovie_entry__")!!.toString()
                )
            } else {
                ctx.executeVoidScript(JsScriptSupport.sanitizeExports(code), scriptUrl)
            }
            Log.d("NextMovieJs", "init ext=「${site.ext}」")
            setInitExt(ctx, site.ext)
            ctx.executeVoidScript(INIT_SCRIPT, "init")
            awaitResult(ctx)
        } catch (failure: Throwable) {
            runCatching { ctx.close() }
            runCatching { rt.close() }
            throw failure
        }
        Log.i("NextMovieJs", "site=${site.name} 脚本已载入（${code.length}B module=$isModule）")
        runtime = rt
        context = ctx
        return ctx
    }

    /** 资产路径读取（assets/js/ 下），供引擎脚本化内联使用 */
    private fun diskKey(key: String): String =
        MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun diskGet(key: String): String? = runCatching {
        val dir = diskDir ?: return null
        val f = java.io.File(dir, diskKey(key) + ".txt")
        if (!f.exists() || f.length() <= 0) return null
        if (System.currentTimeMillis() - f.lastModified() > 24L * 60 * 60 * 1000) return null
        f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun diskPut(key: String, text: String) {
        runCatching {
            val dir = diskDir ?: return
            dir.mkdirs()
            val f = java.io.File(dir, diskKey(key) + ".txt")
            f.writeText(text)
            f.setLastModified(System.currentTimeMillis())
        }
    }

    private fun assetSrc(path: String): String? = runCatching {
        appContext?.assets?.open(path)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    /**
     * 把 drpy2 这类 ESM 引擎转成普通脚本：内联 cheerio / crypto-js，去 import、去 export default。
     * 返回 null 表示这不是引擎（保持 ESM 路径不变）。
     */
    private fun scriptEngineSource(code: String): String? {
        if (!code.contains("export default") || !code.contains("runMain")) return null
        val cheerio = assetSrc("js/lib/cheerio.min.js") ?: return null
        val crypto = assetSrc("js/lib/crypto-js.js").orEmpty()
        val body = code
            .replace(Regex("""import[^;]*;"""), "")
            .replaceFirst("export default", "globalThis.__spider=")
            .let { patchEngineEvals(it) }
        val cheerioScript = cheerio
            .replace("export default", "var cheerio =")
            .replace(Regex("""\bexport\s+"""), "")
        // 模板库：drpy2 的 ext 大多以 muban.mxpro.二级.xxx=... 的形式补模板，
        // 空桩会让这类站直接 ReferenceError('muban')。这里内联真实 模板.js，
        // 并让 getMubans() 永远返回同一个实例，宿主改的 muban 引擎能立刻看见。
        val mubanLib = assetSrc("js/lib/muban.js").orEmpty()
        val mubanScript = if (mubanLib.contains("getMubans")) {
            mubanLib.replace(Regex("""export\s+default[^;]*;"""),
                "var __tplLib={muban:muban,getMubans:function(){return muban},getMuban:function(){return muban}};")
        } else {
            "var __tplLib={getMubans:function(){return {}},getMuban:function(){return {}}};"
        }
        val stub = "" +
            "var \u6a21\u677f=(function(){\n$mubanScript\n" +
            "try{globalThis.__nmMuban=__tplLib.muban||__tplLib.getMubans();}catch(e){}\n" +
            "return __tplLib;})();\n" +
            "var gbkTool=new Proxy(function(){},{get:function(t,k){if(typeof k==='symbol')return undefined;return gbkTool;}," +
            "apply:function(){return gbkTool;}});\n" +
            "globalThis.\u6a21\u677f=\u6a21\u677f;globalThis.gbkTool=gbkTool;\n"
        Log.d("NextMovieJs", "引擎脚本化 ${code.length}B cheerio=${cheerioScript.length}B crypto=${crypto.length}B")
        return cheerioScript + "\n;\n" + crypto + "\n;\n" + stub + body
    }

    /**
     * drpy2 的 init 里用 `eval("(function(){rule = {...}})()")` 装载 ext，
     * 但 QuickJS 里这次 eval 是间接求值，赋值落在全局而不是模块内的 rule → 规则永远是空壳。
     * 宿主先自己求值成对象再交给引擎，engine 走 `typeof ext == "object"` 分支直接赋值。
     */
    /** 把 drpy 里 eval(js代码) 的调用点换成 __nmApplyJs，并把结果同步回引擎闭包变量 */
    private fun patchEngineEvals(body: String): String {
        var out = body
        out = out.replace(
            "var input=homeObj.MY_URL;try{eval(homeObj.class_parse.replace(\"js:\",\"\"))",
            "var input=homeObj.MY_URL;try{var __r=__nmApplyJs(homeObj.class_parse.replace(\"js:\",\"\")," +
                "{input:input,rule:rule,MY_URL:MY_URL,HOST:HOST,VODS:VODS});input=__r.input"
        )
        out = out.replace(
            "var input=MY_URL;HOST=rule.host;eval(p.replace(\"js:\",\"\"));d=VODS",
            "var input=MY_URL;HOST=rule.host;var __r=__nmApplyJs(p.replace(\"js:\",\"\")," +
                "{input:input,HOST:HOST,MY_URL:MY_URL,rule:rule,VODS:VODS,VOD:VOD,TABS:TABS,LISTS:LISTS});" +
                "input=__r.input;HOST=__r.HOST;VODS=(globalThis.VODS&&globalThis.VODS.length)?globalThis.VODS:__r.VODS;VOD=__r.VOD;d=VODS"
        )
        out = out.replace(
            "const MY_PAGE=cateObj.pg;var desc=\"\";eval(p.trim().replace(\"js:\",\"\"));d=VODS",
            "const MY_PAGE=cateObj.pg;var desc=\"\";var __r=__nmApplyJs(p.trim().replace(\"js:\",\"\")," +
                "{input:input,MY_URL:MY_URL,rule:rule,VODS:VODS,VOD:VOD,TABS:TABS,LISTS:LISTS});" +
                "input=__r.input;VODS=(globalThis.VODS&&globalThis.VODS.length)?globalThis.VODS:__r.VODS;VOD=__r.VOD;d=VODS"
        )
        out = out.replace(
            "var detailUrl=rule.detailUrl||\"\";eval(p.trim().replace(\"js:\",\"\"));d=VODS",
            "var detailUrl=rule.detailUrl||\"\";var __r=__nmApplyJs(p.trim().replace(\"js:\",\"\")," +
                "{input:input,rule:rule,VODS:VODS,VOD:VOD,MY_URL:MY_URL});" +
                "input=__r.input;VODS=(globalThis.VODS&&globalThis.VODS.length)?globalThis.VODS:__r.VODS;VOD=__r.VOD;d=VODS"
        )
        out = out.replace(
            "var play_url=\"\";eval(p.trim().replace(\"js:\",\"\"));vod=VOD;",
            "var play_url=\"\";var __r=__nmApplyJs(p.trim().replace(\"js:\",\"\")," +
                "{input:input,rule:rule,VOD:VOD,VODS:VODS,MY_URL:MY_URL,play_url:play_url});" +
                "input=__r.input;VOD=__r.VOD;play_url=__r.play_url||play_url;vod=VOD;"
        )
        out = out.replace(
            "var input=MY_URL;eval(p.tabs.replace(\"js:\",\"\"));playFrom=TABS",
            "var input=MY_URL;var __r=__nmApplyJs(p.tabs.replace(\"js:\",\"\")," +
                "{input:input,rule:rule,TABS:TABS,MY_URL:MY_URL,HOST:HOST});" +
                "input=__r.input;playFrom=__r.TABS"
        )
        out = out.replace(
            "try{var input=MY_URL;var play_url=\"\";eval(p.lists.replace(\"js:\",\"\"));",
            "try{var input=MY_URL;var play_url=\"\";var __r=__nmApplyJs(p.lists.replace(\"js:\",\"\")," +
                "{input:input,rule:rule,LISTS:LISTS,MY_URL:MY_URL,play_url:play_url});" +
                "input=__r.input;play_url=__r.play_url||play_url;LISTS=__r.LISTS;"
        )
        out = out.replace(
            "var input=it.vod_pic;eval(rule.图片替换.trim().replace(\"js:\",\"\"));it.vod_pic=input",
            "var input=it.vod_pic;var __r=__nmApplyJs(rule.图片替换.trim().replace(\"js:\",\"\")," +
                "{input:input,rule:rule});it.vod_pic=__r.input"
        )
        Log.d("NextMovieJs", "js 块改写 " + (body.length - out.length))
        return out
    }

    private fun setInitExt(ctx: JSContext, ext: String) {
        if (!ext.startsWith("http", true)) {
            ctx.set("__nmInitExt", ext)
            return
        }
        val text = runCatching {
            moduleCache[ext]?.takeIf { it.contains("rule") }
                ?: diskGet("ext:" + ext)?.takeIf { it.contains("rule") }
                ?: run {
                val request = Request.Builder().url(ext).header("User-Agent", defaultUa()).build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body?.string().orEmpty()
                }.also { if (it.contains("var rule")) diskPut("ext:" + ext, it) }
            }
        }.getOrNull()
        val body = text?.takeIf { it.contains("var rule") || it.contains("muban") }?.replaceFirst("var rule", "rule")
        if (body.isNullOrBlank()) {
            Log.w("NextMovieJs", "ext 规则文本不可用，按 URL 交给引擎")
            ctx.set("__nmInitExt", ext)
            return
        }
        runCatching {
            ctx.executeVoidScript(
                "(function(){var rule={};var muban=globalThis.__nmMuban||{};\n$body\nglobalThis.__nmInitExt = rule;\n" +
                    "try{for(var k in rule){var v=rule[k];" +
                    "if(typeof v==='string'&&v.indexOf('js:')===0){" +
                    "rule[k]=v.replace(/[^\\x00-\\x7f]/g,function(c){return String.fromCharCode(92)+'u'+('0000'+c.charCodeAt(0).toString(16)).slice(-4);});" +
                    "}}}catch(e){}" +
                    "})()",
                "ext"
            )
            val title = ctx.executeStringScript("String(globalThis.__nmInitExt && globalThis.__nmInitExt.title || '')", "extTitle")
            Log.d("NextMovieJs", "ext 规则已求值 title=「$title」")
        }.onFailure { failure ->
            Log.w("NextMovieJs", "ext 规则求值失败，回退 URL：${failure.message}")
            ctx.set("__nmInitExt", ext)
        }
    }

    private fun registerBridge(ctx: JSContext) {
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any =
                md5(runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
        }, "__nmMd5")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val url = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                val optJson = runCatching { args?.getString(1).orEmpty() }.getOrDefault("")
                return runCatching { fetchSync(url, optJson) }.also { result ->
                    run {
                val env = runCatching { result.getOrThrow() }.getOrDefault("")
                val content = runCatching { JSONObject(env).optString("content") }.getOrDefault("")
                Log.d("NextMovieJs", "request ${url.take(100)} -> env=${env.length}B content=${content.length}B head=${content.take(60).replace("\n", " ")}")
            }
                }.getOrElse {
                    Log.w("NextMovieJs", "request 失败 ${url.take(120)}：${it.message}")
                    JSONObject().put("code", 0).put("content", "").put("headers", JSONObject()).toString()
                }
            }
        }, "__nmRequest")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                Log.d("NextMovieJs", "[js] " + runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
                return ""
            }
        }, "__nmLog")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any =
                JsSpiderLoader.encode(runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
        }, "__nmBase64Encode")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any =
                JsSpiderLoader.decode(runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
        }, "__nmBase64Decode")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val key = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                val value = runCatching {
                    if ((args?.length() ?: 0) > 1) args?.getString(1) else null
                }.getOrNull()
                if (value == null) return readLocal(key)
                if (value.isEmpty()) {
                    localStore.remove(key)
                    runCatching { store?.edit()?.remove(localPrefix + key)?.apply() }
                } else {
                    if (localStore.size > 64) localStore.remove(localStore.keys.first())
                    localStore[key] = value
                    // 落盘：drpy 的登录态/接口 token 常靠 local 活着，只在内存会一重启就废
                    runCatching { store?.edit()?.putString(localPrefix + key, value)?.apply() }
                }
                return value
            }
        }, "__nmLocal")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any = System.currentTimeMillis()
        }, "__nmNow")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val html = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                val rule = runCatching { args?.getString(1).orEmpty() }.getOrDefault("")
                return runCatching { JsSelector.pdfh(html, rule, scriptUrl) }.getOrElse {
                    Log.w("NextMovieJs", "pdfh 失败 rule=$rule ${it.message}")
                    ""
                }
            }
        }, "__nmPdfh")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val html = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                val rule = runCatching { args?.getString(1).orEmpty() }.getOrDefault("")
                return runCatching { JsSelector.pdfa(html, rule, scriptUrl) }.getOrElse {
                    Log.w("NextMovieJs", "pdfa 失败 rule=$rule ${it.message}")
                    "[]"
                }
            }
        }, "__nmPdfa")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any =
                gunzipOrSelf(runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
        }, "__nmGunzip")
        // 插件普遍把站点根域名存在 HOST / MY_URL 里拼接口地址；之前恒为空串导致 URL 全错
        ctx.set("__nmHost", runCatching {
            Regex("^(https?://[^/]+)/").find(scriptUrl)?.value
                ?: (scriptUrl.substringBefore('?').substringBeforeLast('/', "") + "/")
        }.getOrDefault(""))
        ctx.executeScript(BOOTSTRAP, "bootstrap.js")
        ctx.set("__nmSiteHeaders", JSONObject(site.headers).toString())
        ctx.set("__nmScriptUrl", scriptUrl)
        ctx.executeVoidScript(
            "try { __nmDefaultHeaders = JSON.parse(__nmSiteHeaders || '{}'); } catch (e) { __nmDefaultHeaders = {}; }",
            "site-headers"
        )
    }

    private fun readLocal(key: String): String =
        localStore[key] ?: runCatching { store?.getString(localPrefix + key, "").orEmpty() }.getOrDefault("")

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun defaultUa(): String =
        site.headers["User-Agent"] ?: site.headers["user-agent"]
            ?: "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    private fun fetchSync(url: String, optJson: String): String {
        if (url.isBlank()) {
            return JSONObject().put("code", 0).put("content", "").put("headers", JSONObject()).toString()
        }
        val opt = runCatching {
            if (optJson.isBlank()) JSONObject() else JSONObject(optJson)
        }.getOrDefault(JSONObject())
        val absolute = resolveUrl(url)
        val builder = Request.Builder().url(absolute)
        site.headers.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
        mergeHeaderObject(builder, opt.opt("headers"))
        mergeHeaderObject(builder, opt.opt("header"))
        if (builder.build().header("User-Agent") == null) builder.header("User-Agent", defaultUa())

        val method = opt.optString("method", "GET").uppercase(java.util.Locale.ROOT)
        val bodyText = when {
            opt.has("body") -> opt.optString("body", "")
            opt.has("data") -> {
                val d = opt.opt("data")
                if (d is JSONObject || d is JSONArray) d.toString() else opt.optString("data", "")
            }
            else -> ""
        }
        if (method in setOf("POST", "PUT", "PATCH") || (method == "DELETE" && bodyText.isNotEmpty())) {
            val type = opt.optString(
                "contentType",
                opt.optString("content-type", "application/x-www-form-urlencoded")
            ).toMediaTypeOrNull()
            builder.method(method, bodyText.toRequestBody(type))
        } else {
            builder.method(method, null)
        }

        http.newCall(builder.build()).execute().use { resp ->
            var bytes = resp.body?.bytes() ?: ByteArray(0)
            val encoding = resp.header("Content-Encoding").orEmpty()
            if (encoding.contains("gzip", true) ||
                (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            ) {
                bytes = runCatching { gunzipBytes(bytes) }.getOrDefault(bytes)
            }
            val content = String(bytes, Charsets.UTF_8)
            return JSONObject()
                .put("code", resp.code)
                .put("content", content)
                .put("body", content)
                .put("headers", JSONObject(resp.headers.toMultimap().mapValues { it.value.joinToString("; ") }))
                .toString()
        }
    }

    private fun resolveUrl(url: String): String {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        return runCatching { scriptUrl.toHttpUrl().resolve(url)?.toString() }.getOrNull() ?: url
    }

    private fun mergeHeaderObject(builder: Request.Builder, raw: Any?) {
        val o = when (raw) {
            null, JSONObject.NULL -> null
            is JSONObject -> raw
            is String -> raw.trim().takeIf { it.startsWith("{") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            else -> null
        } ?: return
        o.keys().forEach { k ->
            val v = o.optString(k, "")
            if (v.isNotBlank()) runCatching { builder.header(k, v) }
        }
    }

    private fun gunzipBytes(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun gunzipOrSelf(raw: String): String {
        if (raw.isEmpty()) return raw
        val decoded = runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: return raw
        return runCatching { String(gunzipBytes(decoded), Charsets.UTF_8) }.getOrDefault(raw)
    }

    private fun call(name: String, vararg args: Any?): String {
        note = null
        return runCatching {
            onWorker { ctx ->
                val json = JSONArray().apply {
                    args.forEach { arg ->
                        when (arg) {
                            is Collection<*> -> {
                                val inner = JSONArray()
                                arg.forEach { inner.put(it ?: "") }
                                put(inner)
                            }
                            is Map<*, *> -> put(JSONObject(arg as Map<*, *>))
                            else -> put(arg ?: JSONObject.NULL)
                        }
                    }
                }.toString()
                ctx.set("__nmArgs", json)
                ctx.executeVoidScript(
                    SCRIPT_CALL.replace("@NAME@", name).replace("@ALIAS@", aliasOf(name)),
                    "call:$name"
                )
                awaitResult(ctx)
            }.ifBlank { "" }
        }.getOrElse { e ->
            note = "「${site.name}」JS $name 调用失败：${(e.cause ?: e).message.orEmpty().take(140)}"
            Log.w("NextMovieJs", "JS $name 调用失败", e)
            ""
        }
    }

    private fun awaitResult(ctx: JSContext): String {
        fun readResult() = ctx.executeStringScript("__nmResult || ''", "result").orEmpty()
        val raw = readResult().ifEmpty { pump(ctx, ::readResult) }
        if (raw.isBlank()) return ""
        val result = JSONObject(raw)
        check(!result.has("error")) { result.optString("error") }
        return result.optString("value")
    }

    /**
     * 驱动 Promise 微任务 + 定时器，直到脚本给出结果。
     *
     * 早期版本直接调 [QuickJsJobs.complete]：它只要发现「没有待执行 job」就判失败，
     * 于是所有用 `await sleep(300)` / `setTimeout` 的插件都会立刻报「依赖宿主未提供的异步 API」。
     * 这里改成「有 job 就推 job，没 job 就推定时器，两者都没有才睡 2ms 再试」。
     */
    private fun pump(ctx: JSContext, readResult: () -> String): String {
        val deadline = System.nanoTime() + RESULT_TIMEOUT_MS * 1_000_000L
        var idle = 0
        repeat(60_000) {
            readResult().takeIf { it.isNotEmpty() }?.let { return it }
            check(!Thread.currentThread().isInterrupted) { "JS 线程被中断" }
            check(System.nanoTime() < deadline) {
                "JS 执行超时 ${RESULT_TIMEOUT_MS / 1000}s：脚本可能在等宿主没有提供的 API"
            }
            val status = QuickJsJobs.step(ctx.quickJS)
            check(status >= 0) { "JavaScript 任务执行失败" }
            var progressed = status != 0
            if (!progressed) {
                val fired = runCatching {
                    ctx.executeStringScript("(typeof __nmTick === 'function' ? __nmTick() : 0)", "tick")
                        .toIntOrNull()
                }.getOrNull() ?: 0
                progressed = fired > 0
            }
            if (progressed) {
                idle = 0
            } else {
                idle++
                check(idle < 6000) {
                    "Promise 未完成，脚本可能依赖宿主未提供的异步 API（已支持 setTimeout/setInterval/pdfh）"
                }
                Thread.sleep(2)
            }
        }
        error("JS 任务数超过限制")
    }

    companion object {
        /** Application 注入，用于读取 APK 内置的 assets://js/lib 模块（cheerio / crypto-js） */
        @Volatile var appContext: android.content.Context? = null

        private const val EMPTY_MODULE = "export default {};"

        /** crypto-js 是 UMD：ESM 作用域没有 this/exports，包一层并挂到 globalThis */
        private fun wrapCryptoJs(src: String): String =
            "var __nmMod={exports:{}};var module=__nmMod,exports=__nmMod.exports,define=void 0;\n" +
                "(function(){\n" + src + "\n}).call(globalThis);\n" +
                "var __nmCJ=(__nmMod.exports&&(__nmMod.exports.CryptoJS||__nmMod.exports))||globalThis.CryptoJS;\n" +
                "globalThis.CryptoJS=__nmCJ;\n" +
                "export default __nmCJ;"

        /** 单个 JS 调用（含定时器等待）的总时限；drpy 站点常见 1–2 次请求 + 重试，25s 够用。 */
        private const val RESULT_TIMEOUT_MS = 25_000L

        private fun aliasOf(name: String): String = when (name) {
            "homeContent" -> "home"
            "homeVideoContent" -> "homeVod"
            "categoryContent" -> "category"
            "detailContent" -> "detail"
            "searchContent" -> "search"
            "playerContent" -> "play"
            else -> name
        }

        private val INIT_SCRIPT = """
(function(){
  var owner = typeof __spider !== 'undefined' ? __spider : null;
  try {
    if (owner && typeof owner.init === 'function') __nmSettle(owner.init(__nmInitExt));
    else __nmSettle(typeof init === 'function' ? init(__nmInitExt) : null);
  } catch (e) {
    __nmLog('init异常: ' + (e && e.message ? e.message : e) + ' @ ' + (e && e.stack ? e.stack.split('\n')[1] : ''));
    __nmSettle(null);
  }
  try { __nmLog('rule=' + JSON.stringify(owner && owner.getRule ? owner.getRule('') : null).slice(0, 160)); } catch (e) {}

})()
        """.trimIndent()

        private val SCRIPT_CALL = """
(function(){
  var args = JSON.parse(__nmArgs);
  var __nmMethod = '@NAME@';
  var input = {};
  try {
    if (__nmMethod === 'categoryContent') { input.tid = args[0]; input.pg = args[1]; input.filter = args[2]; input.extend = args[3] || {}; }
    else if (__nmMethod === 'detailContent') { input.ids = args[0]; input.id = (args[0] && args[0][0]) || ''; }
    else if (__nmMethod === 'searchContent') { input.wd = args[0]; input.key = args[0]; input.quick = args[1]; input.pg = args[2] || '1'; }
    else if (__nmMethod === 'playerContent') { input.flag = args[0]; input.id = args[1]; input.vipFlags = args[2] || []; }
    else if (__nmMethod === 'homeContent') { input.filter = args[0]; }
  } catch (e) {}
  var owner = typeof __spider !== 'undefined' ? __spider : null;
  var method = owner && (owner['@NAME@'] || owner['@ALIAS@']);
  var shortDetail = owner && !owner['@NAME@'] && method === owner.detail;
  if (typeof method !== 'function' && typeof @NAME@ === 'function') {
    method = @NAME@;
    owner = null;
  }
  if (typeof method !== 'function' && typeof @ALIAS@ === 'function') {
    method = @ALIAS@;
    shortDetail = true;
  }
  if ('@NAME@' === 'detailContent' && shortDetail && args[0] && args[0].length) {
    args = [args[0][0]];
  }
  if ('@NAME@' === 'searchContent' && method && method.length >= 3 && args.length < 3) {
    args = [args[0], args[1], '1'];
  }
  __nmSettle(typeof method === 'function' ? method.apply(owner, args) : null);
})()
        """.trimIndent()

        private val BOOTSTRAP = """
var __nmResult;
var __nmDefaultHeaders = {};
var VODS = [];
// drpy 的 js 规则块原本用直接 eval：QuickJS 里长代码会被截断成 unexpected end of input。
// 改成 new Function + with 注入引擎局部（input / VOD / TABS / LISTS…），跑完调用点同步回闭包。
function __nmApplyJs(src, scope) {
  scope = scope || {};
  if (typeof src !== 'string') return scope;
  var keys = [];
  var vals = [];
  for (var k in scope) { if (Object.prototype.hasOwnProperty.call(scope, k)) { keys.push(k); vals.push(scope[k]); } }
  var assign = '';
  for (var i = 0; i < keys.length; i++) assign += '__o["' + keys[i] + '"]=' + keys[i] + ';';
  try {
    var text = 'var __o={};\n' + src + '\n;' + assign + '\nreturn __o;';
    var res = new Function(keys.join(','), text).apply(null, vals);
    if (res) for (var k2 in res) scope[k2] = res[k2];
  } catch (e) {
    __nmLog('[js块] ' + (e && e.message ? e.message : e) + ' len=' + src.length);
  }
  return scope;
}
// 部分 UMD 库（crypto-js / cheerio 生态）在模块里会用 window / self / global 做根对象
try {
  globalThis.window = globalThis.window || globalThis;
  globalThis.self = globalThis.self || globalThis;
  globalThis.global = globalThis.global || globalThis;
} catch (e) {}
var HOST = (typeof __nmHost === 'string' && __nmHost) ? __nmHost : '';
var MY_URL = HOST;
var __nmTimers = {};
var __nmTimerSeq = 1;
function setTimeout(fn, ms) {
  var id = __nmTimerSeq++;
  __nmTimers[id] = {fn: fn, at: __nmNow() + (ms || 0), every: 0,
    args: Array.prototype.slice.call(arguments, 2)};
  return id;
}
function setInterval(fn, ms) {
  var id = __nmTimerSeq++;
  var every = Math.max(1, ms || 0);
  __nmTimers[id] = {fn: fn, at: __nmNow() + every, every: every,
    args: Array.prototype.slice.call(arguments, 2)};
  return id;
}
function clearTimeout(id) { delete __nmTimers[id]; }
var clearInterval = clearTimeout;
function __nmTick() {
  var now = __nmNow();
  var due = [];
  var pending = 0;
  for (var id in __nmTimers) {
    var t = __nmTimers[id];
    if (!t) continue;
    if (t.at <= now) due.push(id); else pending++;
  }
  for (var i = 0; i < due.length; i++) {
    var key = due[i];
    var timer = __nmTimers[key];
    if (!timer) continue;
    if (timer.every > 0) timer.at = now + timer.every; else delete __nmTimers[key];
    try { timer.fn.apply(null, timer.args || []); } catch (e) { __nmLog('[timer] ' + e); }
  }
  return due.length > 0 ? 1 : (pending > 0 ? 2 : 0);
}
function sleep(ms) { return new Promise(function(resolve){ setTimeout(resolve, ms || 0); }); }
function pdfh(html, rule) {
  return __nmPdfh(String(html == null ? '' : html), String(rule == null ? '' : rule));
}
function pdfa(html, rule) {
  var raw = __nmPdfa(String(html == null ? '' : html), String(rule == null ? '' : rule));
  try { var arr = JSON.parse(raw); return arr && arr.length ? arr : []; } catch (e) { return []; }
}
var pd = pdfh;
var pq = pdfa;
var cache = {
  get: function(k, d){ return local.get(k, d); },
  set: function(k, v){ return local.set(k, v); },
  delete: function(k){ return local.delete(k); }
};
var getCache = cache.get;
var setCache = cache.set;
function __nmSettle(value) {
  __nmResult = null;
  function finish(result) {
    __nmResult = JSON.stringify({value: result == null ? '' :
      typeof result === 'string' ? result : JSON.stringify(result)});
  }
  if (value && typeof value.then === 'function') {
    value.then(finish).catch(function(error) {
      __nmResult = JSON.stringify({error: String(error && error.message ? error.message : error)});
    });
  } else finish(value);
}
var console = {
  log: function(){ __nmLog(Array.prototype.slice.call(arguments).join(' ')); },
  error: function(){ __nmLog('[err] ' + Array.prototype.slice.call(arguments).join(' ')); },
  warn: function(){ __nmLog('[warn] ' + Array.prototype.slice.call(arguments).join(' ')); },
  info: function(){ __nmLog(Array.prototype.slice.call(arguments).join(' ')); }
};
var print = function(){ console.log.apply(console, arguments); };
var log = print;
var md5 = function(s){ return __nmMd5(String(s)); };
var base64 = {
  encode: function(s){ return __nmBase64Encode(String(s)); },
  decode: function(s){ return __nmBase64Decode(String(s)); }
};
var atob = function(s){ return __nmBase64Decode(String(s)); };
var btoa = function(s){ return __nmBase64Encode(String(s)); };
var gzip = {
  unzip: function(s){ return __nmGunzip(String(s)); },
  decode: function(s){ return __nmGunzip(String(s)); }
};
var joinUrl = function(base, rel){
  if (!rel) return base || '';
  if (/^https?:\/\//i.test(rel)) return rel;
  base = base || (typeof __nmScriptUrl !== 'undefined' ? __nmScriptUrl : '');
  if (rel.charAt(0) === '/') {
    var m = base.match(/^(https?:\/\/[^\/]+)/i);
    return (m ? m[1] : base.replace(/\/+$/, '')) + rel;
  }
  return base.replace(/\/[^\/]*$/, '/') + rel.replace(/^\.\//, '');
};
function __nmMergeOpt(opt) {
  opt = opt || {};
  var headers = {};
  var k;
  for (k in __nmDefaultHeaders) headers[k] = __nmDefaultHeaders[k];
  var src = opt.headers || opt.header || {};
  if (typeof src === 'string') {
    try { src = JSON.parse(src); } catch (e) { src = {}; }
  }
  for (k in src) headers[k] = src[k];
  opt.headers = headers;
  return opt;
}
// CatVod/TVBox 系 JS 宿主里 request() 返回的是响应正文文本（引擎会直接 eval / 正则处理），
// 不是 JSON 信封；需要状态码/头信息时用 requestJson()。
var request = function(url, opt){
  opt = __nmMergeOpt(opt);
  var raw = __nmRequest(String(url), JSON.stringify(opt));
  if (opt && opt.asJson === true) {
    var obj;
    try { obj = JSON.parse(raw); } catch (e) { obj = {code: 0, content: '', headers: {}}; }
    return obj;
  }
  var env;
  try { env = JSON.parse(raw); } catch (e) { return String(raw); }
  if (!env || typeof env.content !== 'string') return '';
  return env.content;
};
var requestJson = function(url, opt){
  opt = __nmMergeOpt(opt || {});
  opt.asJson = true;
  return request(url, opt);
};
var fetch = request;
var req = request;
var get = function(url, opt){
  opt = __nmMergeOpt(opt || {});
  opt.method = 'GET';
  return request(url, opt);
};
var post = function(url, opt){
  opt = __nmMergeOpt(opt || {});
  opt.method = 'POST';
  return request(url, opt);
};
var setResult = function(data){
  VODS = data || [];
  return JSON.stringify({list: VODS});
};
var setHome = setResult;
var setHomeResult = setResult;
var getProxy = function(isPlay){ return ''; };
var getProxyUrl = getProxy;
var local = {
  get: function(key, def){
    var v = __nmLocal(String(key));
    return (v === undefined || v === null || v === '') ? (def == null ? '' : def) : v;
  },
  set: function(key, value){ return __nmLocal(String(key), value == null ? '' : String(value)); },
  delete: function(key){ return __nmLocal(String(key), ''); }
};
var require = function(path){
  path = String(path);
  // 内置模块名（crypto/fs/path…）不是 URL，返回 undefined 让调用方走降级分支
  if (!/^(https?:|\/)/i.test(path)) return undefined;
  var code = request(path, {});
  if (!code) throw new Error('require failed: ' + path);
  var module = {exports: {}};
  var exports = module.exports;
  eval(code);
  return module.exports;
};
        """.trimIndent()
    }
}

class JsSpiderLoader(
    private val http: com.nextmovie.core.net.Http,
    private val cacheDir: java.io.File? = null,
    private val store: android.content.SharedPreferences? = null
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Spider>()

    suspend fun obtain(site: Site): Spider {
        cache[site.runtimeKey]?.let { return it }
        val url = if (site.api.startsWith("http")) site.api
        else com.nextmovie.core.net.Urls.resolve(site.origin, site.api) ?: site.api
        val code = cachedScript(url) ?: runCatching { http.text(url, site.headers) }.getOrElse {
            throw JarSpiderLoader.SpiderLoadException(
                "「${site.name}」JS 脚本下载失败：${it.message ?: it.javaClass.simpleName}", it
            )
        }
        if (code.isBlank()) throw JarSpiderLoader.SpiderLoadException("「${site.name}」JS 脚本内容为空")
        storeScript(url, code)
        val spider = JsSpider(site, code, url, store, cacheDir)
        cache[site.runtimeKey] = spider
        Log.i("NextMovieJs", "site=${site.name} 脚本 ${code.length}B")
        return spider
    }

    private fun scriptFile(url: String): java.io.File? {
        val dir = cacheDir ?: return null
        return runCatching {
            dir.mkdirs()
            java.io.File(
                dir,
                MessageDigest.getInstance("MD5").digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) } + ".js"
            )
        }.getOrNull()
    }

    private fun cachedScript(url: String): String? = runCatching {
        val file = scriptFile(url) ?: return null
        if (!file.exists() || file.length() <= 0) return null
        if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) return null
        file.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun storeScript(url: String, code: String) {
        runCatching {
            val file = scriptFile(url) ?: return
            val temp = java.io.File(file.parentFile, file.name + ".tmp")
            temp.writeText(code)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, true)
                temp.delete()
            }
        }
    }

    fun release() {
        cache.values.forEach { runCatching { it.destroy() } }
        cache.clear()
    }

    companion object {
        fun encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())
        fun decode(s: String): String =
            runCatching { String(Base64.getDecoder().decode(s)) }.getOrDefault("")
    }
}
