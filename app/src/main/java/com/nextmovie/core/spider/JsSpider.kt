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
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TVBox 的 `.js` 站点（`type: 3`）：脚本里直接导出 `homeContent / categoryContent / detailContent / playerContent`。
 *
 * 为什么要独立线程：QuickJS 的 runtime 有线程亲和性，跨线程访问会直接 crash。
 * 这个 spider 的所有 JS 调用都投递到自己的单线程 executor 上执行，宿主那边无论从哪个线程调都安全。
 *
 */
class JsSpider(
    private val site: Site,
    private val code: String,
    private val scriptUrl: String
) : Spider() {

    /** 最近一次调用遇到的问题（目前只有「异步脚本」这一种），供服务层转成用户可见的提示。 */
    @Volatile private var note: String? = null

    fun asyncNote(): String? = note

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "js-${site.key}") }
    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun homeContent(filter: Boolean): String = call("homeContent", filter)

    override fun homeVideoContent(): String = call("homeVideoContent")

    override fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?
    ): String = call("categoryContent", tid, pg, filter, extend ?: HashMap<String, String>())

    override fun detailContent(ids: List<String>?): String = call("detailContent", ids ?: emptyList<String>())

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

    // ---- 内部实现 ----

    private fun <T> onWorker(timeoutSec: Long = 25, block: (JSContext) -> T): T {
        val task = worker.submit(Callable { block(ensureContext()) })
        return try { task.get(timeoutSec, TimeUnit.SECONDS) } finally { if (!task.isDone) task.cancel(true) }
    }

    private fun ensureContext(): JSContext {
        context?.let { return it }
        loadNative()
        val rt = QuickJS.createRuntime()
        val isModule = scriptUrl.toHttpUrl().encodedPath.endsWith(".mjs", true) ||
            Regex("""(?m)^\s*import\s+(?!\()""").containsMatchIn(code)
        val ctx = if (isModule) object : com.quickjs.ES6Module(rt) {
            override fun convertModuleName(base: String, name: String): String =
                (base.toHttpUrl().resolve(name) ?: error("不支持的模块地址")).toString()

            override fun getModuleScript(name: String): String {
                if (name == scriptUrl) return code
                val request = Request.Builder().url(name).build()
                return http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "模块请求失败 HTTP ${response.code}" }
                    response.body?.string().orEmpty()
                }
            }
        } else rt.createContext()
        try {
            registerBridge(ctx)
            if (ctx is com.quickjs.ES6Module) {
                val entry = JSONObject.quote(scriptUrl)
                ctx.executeModuleScript(
                    "import * as plugin from $entry; globalThis.__spider = plugin.default || plugin;",
                    scriptUrl.toHttpUrl().resolve("./__nextmovie_entry__")!!.toString()
                )
            } else ctx.executeVoidScript(sanitizeExports(code), scriptUrl)
            ctx.set("__nmInitExt", site.ext)
            ctx.executeVoidScript(INIT_SCRIPT, "init")
            awaitResult(ctx)
        } catch (failure: Throwable) {
            runCatching { ctx.close() }
            runCatching { rt.close() }
            throw failure
        }
        Log.i("NextMovieJs", "site=${site.name} 脚本已载入（${code.length}B）")
        runtime = rt
        context = ctx
        return ctx
    }

    private fun loadNative() {
        // aar 内自带 libquickjs-android.so；Gradle 会按 ABI 打包，
        // 显式 load 一次可让后续 JNI 调用拿到确定的库版本
        runCatching { System.loadLibrary("quickjs-android") }
    }

    private fun registerBridge(ctx: JSContext) {
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val text = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                return md5(text)
            }
        }, "__nmMd5")

        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val url = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                val optJson = runCatching { args?.getString(1).orEmpty() }.getOrDefault("")
                return runCatching { fetchSync(url, optJson) }
                    .getOrElse {
                        Log.w("NextMovieJs", "request 失败 ${it.javaClass.simpleName}")
                        "{\"code\":0,\"content\":\"\",\"headers\":{}}"
                    }
            }
        }, "__nmRequest")

        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val text = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                Log.d("NextMovieJs", "[js] $text")
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

        ctx.executeScript(BOOTSTRAP, "bootstrap.js")
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fetchSync(url: String, optJson: String): String {
        if (url.isBlank()) return ""
        val opt = runCatching { if (optJson.isBlank()) JSONObject() else JSONObject(optJson) }
            .getOrDefault(JSONObject())
        val builder = Request.Builder().url(scriptUrl.toHttpUrl().resolve(url) ?: error("无效的请求地址"))
        val headers = opt.optJSONObject("headers")
        headers?.keys()?.forEach { k -> runCatching { builder.header(k, headers.optString(k, "")) } }
        val method = opt.optString("method", "GET").uppercase(java.util.Locale.ROOT)
        val bodyText = opt.optString("body", "")
        if (method in setOf("POST", "PUT", "PATCH") || (method == "DELETE" && bodyText.isNotEmpty())) {
            val type = opt.optString("contentType", "application/x-www-form-urlencoded").toMediaTypeOrNull()
            builder.method(method, bodyText.toRequestBody(type))
        } else {
            builder.method(method, null)
        }
        http.newCall(builder.build()).execute().use { resp ->
            return JSONObject().put("code", resp.code)
                .put("content", resp.body?.string().orEmpty())
                .put("headers", JSONObject(resp.headers.toMultimap().mapValues { it.value.joinToString("; ") }))
                .toString()
        }
    }

    /**
     * 统一调用入口：把 Kotlin 参数序列化成 JSON，在 JS 侧用 apply 展开，
     * 从而避免为每个方法单独写一套参数传递逻辑。
     */
    private fun call(name: String, vararg args: Any?): String {
        note = null
        return runCatching {
            onWorker { ctx ->
                // Android 的 JSONArray.put(List) 会把集合 toString 成 "[/xx]"，导致脚本拿到带方括号的假 id。
                // 这里显式构造嵌套数组，保证 ["/xx"] 而不是 "[/xx]"。
                val json = JSONArray().apply {
                    args.forEach { arg ->
                        when (arg) {
                            is Collection<*> -> {
                                val inner = JSONArray()
                                arg.forEach { inner.put(it ?: "") }
                                put(inner)
                            }
                            is Map<*, *> -> put(JSONObject(arg))
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
            }.let { raw ->
                if (raw.isBlank()) "" else raw
            }
        }.getOrElse { e ->
            note = "「${site.name}」JS $name 调用失败：${(e.cause ?: e).message.orEmpty().take(140)}"
            Log.w("NextMovieJs", "JS $name 调用失败", e)
            ""
        }
    }

    private fun awaitResult(ctx: JSContext): String {
        fun readResult() = ctx.executeStringScript("__nmResult || ''", "result").orEmpty()
        val raw = readResult().ifEmpty { QuickJsJobs.complete(ctx.quickJS, ::readResult) }
        val result = JSONObject(raw)
        check(!result.has("error")) { result.optString("error") }
        return result.optString("value")
    }

    companion object {
        private val SCRIPT_CALL = """
(function(){
  var args = JSON.parse(__nmArgs);
  var owner = typeof __spider !== 'undefined' ? __spider : null;
  var method = owner && (owner['@NAME@'] || owner['@ALIAS@']);
  var shortDetail = owner && !owner['@NAME@'] && method === owner.detail;
  if (typeof method !== 'function' && typeof @NAME@ === 'function') {
    method = @NAME@;
    owner = null;
  }
  if (typeof method !== 'function' && typeof @ALIAS@ === 'function') { method = @ALIAS@; shortDetail = true; }
  if ('@NAME@' === 'detailContent' && shortDetail) args = [args[0][0]];
  __nmSettle(typeof method === 'function' ? method.apply(owner, args) : null);
})()
        """.trimIndent()

        /** TVBox 单文件 JS spider 的短名约定：homeContent ↔ home、playerContent ↔ play。 */
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
  if (owner && typeof owner.init === 'function') __nmSettle(owner.init(__nmInitExt));
  else __nmSettle(typeof init === 'function' ? init(__nmInitExt) : null);
})()
        """.trimIndent()

        private fun sanitizeExports(src: String): String = src
            .replace(Regex("""(?m)^([ \t]*)export[ \t]+default[ \t]+"""), "\$1var __spider = ")
            .replace(Regex("""(?m)^([ \t]*)export[ \t]+(?=(?:async\s+)?function\b|(?:class|const|let|var)\b)"""), "\$1")
            .replace(Regex("""(?m)^\s*export\s*\{([^}]*)\}\s*;?""")) { match ->
                val members = match.groupValues[1].split(',').mapNotNull { member ->
                    val names = member.trim().split(Regex("""\s+as\s+"""))
                    if (names.first().isBlank()) null else "${JSONObject.quote(names.last())}:${names.first()}"
                }
                "var __spider = {${members.joinToString(",")}};\n"
            }

        /** 给脚本补齐 TVBox 里习以为常的全局函数，缺了它们脚本第一行就报 undefined。 */
        private val BOOTSTRAP = """
var __nmResult;
function __nmSettle(value) {
  __nmResult = null;
  function finish(result) {
    __nmResult = JSON.stringify({value: result == null ? '' :
      typeof result === 'string' ? result : JSON.stringify(result)});
  }
  if (value && typeof value.then === 'function') {
    value.then(finish).catch(function(error) {
      __nmResult = JSON.stringify({error: String(error)});
    });
  } else finish(value);
}
var console = {
  log: function(s){ __nmLog(String(s)); },
  error: function(s){ __nmLog('[err] ' + String(s)); },
  warn: function(s){ __nmLog('[warn] ' + String(s)); },
  info: function(s){ __nmLog(String(s)); }
};
var print = function(s){ __nmLog(String(s)); };
var md5 = function(s){ return __nmMd5(String(s)); };
var base64 = {
  encode: function(s){ return __nmBase64Encode(String(s)); },
  decode: function(s){ return __nmBase64Decode(String(s)); }
};
var atob = function(s){ return __nmBase64Decode(String(s)); };
var btoa = function(s){ return __nmBase64Encode(String(s)); };
var joinUrl = function(base, rel){
  if (!rel) return base;
  if (/^http/i.test(rel)) return rel;
  if (rel.charAt(0) === '/') return base.replace(/\/+$/, '') + rel;
  return base.replace(/\/+$/, '') + '/' + rel.replace(/^\/+/, '');
};
var request = function(url, opt){
  return JSON.parse(__nmRequest(String(url), opt ? JSON.stringify(opt) : ''));
};
var fetch = request;
var req = request;
        """.trimIndent()
    }
}

/** 下载脚本并缓存成 [Spider]；`.js` 站点在 `:spider` 进程里走这条路径。 */
class JsSpiderLoader(private val http: com.nextmovie.core.net.Http) {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Spider>()

    suspend fun obtain(site: Site): Spider {
        cache[site.runtimeKey]?.let { return it }
        val url = if (site.api.startsWith("http")) site.api else com.nextmovie.core.net.Urls.resolve(site.origin, site.api) ?: site.api
        val code = runCatching { http.text(url, site.headers) }.getOrElse {
            throw JarSpiderLoader.SpiderLoadException(
                "「${site.name}」JS 脚本下载失败：${it.message ?: it.javaClass.simpleName}", it
            )
        }
        if (code.isBlank()) throw JarSpiderLoader.SpiderLoadException("「${site.name}」JS 脚本内容为空")
        val spider = JsSpider(site, code, url)
        cache[site.runtimeKey] = spider
        Log.i("NextMovieJs", "site=${site.name} 脚本 ${code.length}B")
        return spider
    }

    fun release() {
        cache.values.forEach { runCatching { it.destroy() } }
        cache.clear()
    }

    companion object {
        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

        fun decode(s: String): String =
            runCatching { String(Base64.getDecoder().decode(s)) }.getOrDefault("")
    }
}
