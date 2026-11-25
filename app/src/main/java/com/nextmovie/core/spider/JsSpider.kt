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
 */
class JsSpider(
    private val site: Site,
    private val code: String,
    private val scriptUrl: String
) : Spider() {

    @Volatile private var note: String? = null
    fun asyncNote(): String? = note

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "js-${site.key}") }
    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private val localStore = LinkedHashMap<String, String>()
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
                if (value == null) return localStore[key].orEmpty()
                if (value.isEmpty()) localStore.remove(key)
                else {
                    if (localStore.size > 64) localStore.remove(localStore.keys.first())
                    localStore[key] = value
                }
                return value
            }
        }, "__nmLocal")
        ctx.registerJavaMethod(object : JavaCallback {
            override fun invoke(thisObj: JSObject?, args: JSArray?): Any =
                gunzipOrSelf(runCatching { args?.getString(0).orEmpty() }.getOrDefault(""))
        }, "__nmGunzip")
        ctx.executeScript(BOOTSTRAP, "bootstrap.js")
        ctx.set("__nmSiteHeaders", JSONObject(site.headers).toString())
        ctx.set("__nmScriptUrl", scriptUrl)
        ctx.executeVoidScript(
            "try { __nmDefaultHeaders = JSON.parse(__nmSiteHeaders || '{}'); } catch (e) { __nmDefaultHeaders = {}; }",
            "site-headers"
        )
    }

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
        val o: JSONObject = when (raw) {
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
        val raw = readResult().ifEmpty { QuickJsJobs.complete(ctx.quickJS, ::readResult) }
        if (raw.isBlank()) return ""
        val result = JSONObject(raw)
        check(!result.has("error")) { result.optString("error") }
        return result.optString("value")
    }

    companion object {
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
var HOST = '';
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
    private val cacheDir: java.io.File? = null
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
        val spider = JsSpider(site, code, url)
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
