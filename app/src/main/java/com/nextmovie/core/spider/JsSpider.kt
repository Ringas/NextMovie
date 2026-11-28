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
    private val store: android.content.SharedPreferences? = null
) : Spider() {

    @Volatile private var note: String? = null
    fun asyncNote(): String? = note

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "js-${site.key}") }
    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private val localStore = LinkedHashMap<String, String>()
    private val localPrefix = "js." + site.key.hashCode().toString(16) + "."
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
        val isModule = JsScriptSupport.isEsModule(code, scriptUrl)
        val ctx = if (isModule) object : com.quickjs.ES6Module(rt) {
            override fun convertModuleName(base: String, name: String): String {
                val resolved = runCatching { base.toHttpUrl().resolve(name)?.toString() }.getOrNull()
                    ?: runCatching { scriptUrl.toHttpUrl().resolve(name)?.toString() }.getOrNull()
                return resolved ?: name
            }
            override fun getModuleScript(name: String): String {
                if (name == scriptUrl) return code
                val request = Request.Builder().url(name).header("User-Agent", defaultUa()).build()
                return http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "模块请求失败 HTTP ${response.code} @ $name" }
                    response.body?.string().orEmpty()
                }
            }
        } else rt.createContext()
        try {
            registerBridge(ctx)
            if (ctx is com.quickjs.ES6Module) {
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
            ctx.set("__nmInitExt", site.ext)
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

    private fun registerBridge(ctx: JSContext) {
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any =
                md5(runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
        }, "__nmMd5")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any {
                val url = runCatching { args?.getString(0).orEmpty() }.getOrDefault("")
                val optJson = runCatching { args?.getString(1).orEmpty() }.getOrDefault("")
                return runCatching { fetchSync(url, optJson) }.getOrElse {
                    Log.w("NextMovieJs", "request 失败 ${it.message}")
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
  if (owner && typeof owner.init === 'function') __nmSettle(owner.init(__nmInitExt));
  else __nmSettle(typeof init === 'function' ? init(__nmInitExt) : null);
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
var request = function(url, opt){
  opt = __nmMergeOpt(opt);
  var raw = __nmRequest(String(url), JSON.stringify(opt));
  var obj;
  try { obj = JSON.parse(raw); } catch (e) {
    obj = {code: 0, content: '', headers: {}};
  }
  if (opt && (opt.onlyString === true || opt.onlyChars === true || opt.toString === true)) {
    return obj.content || '';
  }
  return obj;
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
  var r = request(path, {});
  var code = (r && r.content) ? r.content : '';
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
        val spider = JsSpider(site, code, url, store)
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
