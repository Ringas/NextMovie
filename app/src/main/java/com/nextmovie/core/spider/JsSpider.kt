package com.nextmovie.core.spider

import android.util.Log
import com.github.catvod.crawler.Spider
import com.nextmovie.core.config.Site
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.QuickJS
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
 * 已知边界：drpy2 一类脚本把方法写成 `async`，返回 Promise。QuickJS 这边等不到 Promise 结果，
 * 所以只支持同步脚本；遇到 Promise 会明确报"异步脚本暂不支持"，不假装成功。
 */
class JsSpider(
    private val site: Site,
    private val code: String
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
            .build()
    }

    override fun init(context: android.content.Context?, extend: String?) {
        // 脚本初始化放在首次调用时做：那时才在 worker 线程上，避免主线程建 runtime
        runCatching { ensureContext() }
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
        return task.get(timeoutSec, TimeUnit.SECONDS)
    }

    private fun ensureContext(): JSContext {
        context?.let { return it }
        loadNative()
        val rt = QuickJS.createRuntime()
        val ctx = rt.createContext()
        registerBridge(ctx)
        // 脚本自身在顶层执行；它通常只是定义函数与常量，不产生网络请求。
        // 先按「只改写 export、保留 async/await」的方式加载：QuickJS 原生支持 async，
        // 保留后 async 函数会返回 Promise，我们就能给出「异步脚本暂不支持」的明确提示，
        // 而不是把 async 关键字硬删掉、再抛一个莫名的 SyntaxError。
        var loaded = false
        var lastError: Exception? = null
        for (variant in listOf(sanitizeKeepAsync(code), sanitize(code))) {
            val result = runCatching { ctx.executeScript(variant, site.api) }
            if (result.isSuccess) { loaded = true; break }
            lastError = result.exceptionOrNull() as? Exception
            Log.w("NextMovieJs", "site=${site.name} 脚本加载失败（${variant.length}B）：${lastError?.message}")
        }
        if (!loaded) {
            note = "「${site.name}」脚本无法在本机内核运行：${lastError?.message?.take(120) ?: "未知错误"}"
            throw lastError ?: IllegalStateException("脚本加载失败")
        }
        // 部分脚本把初始化放在 init() 里（例如拼 host / 取配置），先跑一次，失败不影响后续
        runCatching {
            ctx.executeStringScript(INIT_SCRIPT, "init")
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
                        Log.w("NextMovieJs", "request 失败 $url: ${it.message}")
                        ""
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
        val builder = Request.Builder().url(url)
        val headers = opt.optJSONObject("headers")
        headers?.keys()?.forEach { k -> runCatching { builder.header(k, headers.optString(k, "")) } }
        val method = opt.optString("method", "GET").uppercase()
        val bodyText = opt.optString("body", "")
        if (method == "POST") {
            val type = opt.optString("contentType", "application/x-www-form-urlencoded").toMediaTypeOrNull()
            builder.post(bodyText.toRequestBody(type))
        } else {
            builder.get()
        }
        http.newCall(builder.build()).execute().use { resp ->
            return resp.body?.string().orEmpty()
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
                            else -> put(arg ?: JSONObject.NULL)
                        }
                    }
                }.toString()
                ctx.set("__nmArgs", json)
                ctx.executeStringScript(
                    SCRIPT_CALL.replace("@NAME@", name).replace("@ALIAS@", aliasOf(name)),
                    "call:$name"
                )
            }.let { raw ->
                if (raw.isBlank()) "" else raw
            }
        }.getOrElse { e ->
            val msg = e.message.orEmpty()
            if (msg.contains(ASYNC_MARK)) {
                note = "「${site.name}」使用异步脚本（drpy2 async），本机 JS 内核只支持同步脚本，" +
                    "该页面内容无法加载；可换站点或换线路再试。"
                Log.w("NextMovieJs", "site=${site.name} $name 是异步脚本")
            } else {
                Log.w("NextMovieJs", "site=${site.name} $name 失败: $msg", e)
            }
            ""
        }
    }

    companion object {
        private const val ASYNC_MARK = "__NM_ASYNC__"

        private val SCRIPT_CALL = """
(function(){
  var a = JSON.parse(__nmArgs);
  var f = null;
  if (typeof @NAME@ === 'function') f = @NAME@;
  else if (typeof __spider !== 'undefined' && __spider) {
    if (typeof __spider['@NAME@'] === 'function') f = __spider['@NAME@'];
    else if (typeof __spider['@ALIAS@'] === 'function') f = __spider['@ALIAS@'];
  }
  if (!f) return '';
  // detail 的入参在生态里几乎全是数组，而脚本签名常写成 detail(id)。
  // 这里把 [[id]] / [id] 统一压成单值，避免把数组 toString() 拼进 URL（会变成 host[/xx]）。
  var cargs = a;
  if (a.length === 1) {
    var v = a[0];
    while (v && typeof v === 'object' && typeof v.length === 'number' && v.length > 0) { v = v[0]; }
    if (v !== a[0]) cargs = [v];
  }
  __nmLog('call @NAME@ args=' + JSON.stringify(cargs));
  var r = null, err = null;
  try { r = f.apply(null, cargs); } catch (e) { err = e; }
  if (err) { err = null; try { r = f.apply(null, a); } catch (e2) { err = e2; } }
  if (err) throw err;
  if (r && typeof r.then === 'function') throw new Error('$ASYNC_MARK');
  if (r === undefined || r === null) return '';
  if (typeof r === 'string') return r;
  try { return JSON.stringify(r); } catch (e) { return ''; }
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
  var f = null;
  if (typeof init === 'function') f = init;
  else if (typeof __spider !== 'undefined' && __spider && typeof __spider.init === 'function') f = __spider.init;
  if (f) { try { f(); } catch (e) { __nmLog('init failed: ' + String(e)); } }
  return '';
})()
        """.trimIndent()

        /**
         * 把 ES module 风格的脚本改写成全局脚本：
         * - `export default {...}` → `var __spider = {...}`
         * - 去掉 `export` / `async` / `await`（宿主侧的 req 是同步阻塞的，await 去掉等价）
         * 这样 QuickJS 的普通 executeScript 就能跑，不需要 module 环境。
         */
        /** 只改写模块导出：保留 async/await，让 async 脚本走「Promise 检测」的明确提示。 */
        private fun sanitizeKeepAsync(src: String): String {
            var out = src
            out = Regex("""export\s+default\s+""").replace(out, "var __spider = ")
            out = Regex("""export\s+(function|class|const|let|var)\s""").replace(out, "\$1 ")
            out = Regex("""export\s*\{[^}]*\}\s*;?""").replace(out, "")
            return out
        }

        private fun sanitize(src: String): String {
            var out = src
            out = Regex("""export\s+default\s+""").replace(out, "var __spider = ")
            out = Regex("""export\s+(function|class|const|let|var)\s""").replace(out, "\$1 ")
            out = Regex("""\basync\s+function\s""").replace(out, "function ")
            out = Regex("""\basync\s+""").replace(out, "")
            out = Regex("""\bawait\s+""").replace(out, "")
            return out
        }

        /** 给脚本补齐 TVBox 里习以为常的全局函数，缺了它们脚本第一行就报 undefined。 */
        private val BOOTSTRAP = """
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
  var body = __nmRequest(String(url), opt ? JSON.stringify(opt) : '');
  return { code: 200, content: body, headers: {} };
};
var fetch = request;
var req = request;
        """.trimIndent()
    }
}

/** 下载脚本并缓存成 [Spider]；`.js` 站点在 `:spider` 进程里走这条路径。 */
class JsSpiderLoader(private val http: com.nextmovie.core.net.Http) {

    private val cache = HashMap<String, Spider>()

    suspend fun obtain(site: Site): Spider {
        cache[site.key]?.let { return it }
        val url = if (site.api.startsWith("http")) site.api else com.nextmovie.core.net.Urls.resolve(site.origin, site.api) ?: site.api
        val code = runCatching { http.text(url, site.headers) }.getOrElse {
            throw JarSpiderLoader.SpiderLoadException(
                "「${site.name}」JS 脚本下载失败：${it.message ?: it.javaClass.simpleName}", it
            )
        }
        if (code.isBlank()) throw JarSpiderLoader.SpiderLoadException("「${site.name}」JS 脚本内容为空")
        // 简单识别：drpy2 的主入口是 async，直接给出结论，别让它跑到 Promise 上再报空数据
        val async = Regex("""function\s+(homeContent|categoryContent)\s*\([^)]*\)\s*\{""").containsMatchIn(code)
        val spider = JsSpider(site, code)
        cache[site.key] = spider
        Log.i("NextMovieJs", "site=${site.name} 脚本 ${code.length}B async候选=$async url=$url")
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
