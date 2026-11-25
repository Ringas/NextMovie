package com.nextmovie.core.spider

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nextmovie.core.net.Http
import com.nextmovie.core.spider.SpiderProtocol.ACTION_CATEGORY
import com.nextmovie.core.spider.SpiderProtocol.ACTION_DETAIL
import com.nextmovie.core.spider.SpiderProtocol.ACTION_HOME
import com.nextmovie.core.spider.SpiderProtocol.ACTION_PING
import com.nextmovie.core.spider.SpiderProtocol.ACTION_PLAY
import com.nextmovie.core.spider.SpiderProtocol.ACTION_SEARCH
import com.nextmovie.core.spider.SpiderProtocol.KEY_ARG1
import com.nextmovie.core.spider.SpiderProtocol.KEY_ARG2
import com.nextmovie.core.spider.SpiderProtocol.KEY_ARG3
import com.nextmovie.core.spider.SpiderProtocol.KEY_FILTER
import com.nextmovie.core.spider.SpiderProtocol.KEY_PAGE
import com.nextmovie.core.spider.SpiderProtocol.KEY_SITE
import com.nextmovie.core.spider.SpiderProtocol.errorBundle
import com.nextmovie.core.spider.SpiderProtocol.okBundle
import com.nextmovie.core.spider.SpiderProtocol.siteFromTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

/**
 * `:spider` 进程服务：唯一持有 JarSpiderLoader 的地方。
 *
 * 插件崩溃（native SIGSEGV、System.exit 等）只会杀掉本进程，主进程通过 Messenger 收到 binder 断开，
 * 显示「插件进程崩溃」而不是整个应用闪退。
 */
class SpiderService : Service() {

    private lateinit var loader: JarSpiderLoader
    private lateinit var jsLoader: JsSpiderLoader
    private data class RequestKey(val client: IBinder, val id: Int)
    private val requests = mutableMapOf<RequestKey, Job>()
    private val proxies = ArrayDeque<SpiderProxyServer>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val siteLocks = mutableMapOf<String, Mutex>()
    private val executor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun newProxy(): SpiderProxyServer = synchronized(proxies) {
        if (proxies.size >= 8) proxies.removeFirst().close()
        SpiderProxyServer().also { proxies.addLast(it) }
    }
    private val messenger: Messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { onRequest(it); true }) }

    override fun onCreate() {
        super.onCreate()
        val created = System.nanoTime()
        android.util.Log.i("NextMovieTiming", "plugin-service-create")
        val engines = PluginRuntime.acquire(applicationContext)
        loader = engines.jar
        jsLoader = engines.js
        android.util.Log.i("NextMovieTiming", "plugin-service-ready ${(System.nanoTime() - created) / 1_000_000}ms")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /**
     * 常驻：插件实例（含 4.8MB Go 代理）只在进程里活着，进程一死就要重新付 10–25s。
     * START_STICKY 让系统在没有绑定时也把进程留着，开机预热完就一直待命。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        PluginRuntime.release()
        scope.cancel()
        executor.close()
        watchdog.shutdownNow()
        synchronized(proxies) { proxies.forEach { it.close() }; proxies.clear() }
        super.onDestroy()
    }

    private fun onRequest(msg: Message) {
        // Message 在 onRequest 返回后会被框架回收（what/arg1/data 清零），
        // 异步线程必须使用副本，否则会读到 what=0 而走到"未知动作"分支。
        if (msg.what == SpiderProtocol.ACTION_RESTART) {
            msg.replyTo?.send(Message.obtain(null, SpiderProtocol.ACTION_REPLY).apply { data = okBundle(msg.arg1, "restarting") })
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 100)
            return
        }
        if (msg.what == SpiderProtocol.ACTION_RELEASE_PROXY) {
            val port = msg.arg1
            val proxy = synchronized(proxies) {
                proxies.firstOrNull { it.port == port }?.also { proxies.remove(it) }
            }
            proxy?.close()
            return
        }
        val replyTo = msg.replyTo ?: return
        val id = msg.arg1
        val key = RequestKey(replyTo.binder, id)
        if (msg.what == SpiderProtocol.ACTION_CANCEL) {
            requests.remove(key)?.cancel()
            return
        }
        val action = msg.what
        val args = msg.data?.let { Bundle(it) } ?: Bundle()
        val gate = siteLocks.getOrPut(args.getString(KEY_SITE).orEmpty()) { Mutex() }
        val queuedAt = System.nanoTime()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val result = gate.withLock {
                    withContext(executor) {
                        val delayMs = (System.nanoTime() - queuedAt) / 1_000_000
                        android.util.Log.i("NextMovieTiming", "plugin-queue action=$action ${delayMs}ms")
                        val deadline = watchdog.schedule(
                            { android.os.Process.killProcess(android.os.Process.myPid()) },
                            if (action == ACTION_PLAY) 330 else 85,
                            java.util.concurrent.TimeUnit.SECONDS
                        )
                        try {
                            handle(action, args, id)
                        } catch (e: Exception) {
                            android.util.Log.w("NextMovieSpider", "action=$action failed ${e.javaClass.simpleName}")
                            errorBundle(id, "插件调用失败（${e.javaClass.simpleName}）")
                        } catch (e: LinkageError) {
                            errorBundle(id, "插件运行时不兼容（${e.javaClass.simpleName}）")
                        } finally {
                            deadline.cancel(false)
                        }
                    }
                }
                runCatching { replyTo.send(Message.obtain().apply { what = SpiderProtocol.ACTION_REPLY; data = result }) }
            } finally {
                requests.remove(key)
            }
        }
        requests[key] = job
        job.start()
    }

    private fun handle(action: Int, args: Bundle, id: Int): Bundle {
        if (action == ACTION_PING) return okBundle(id, "pong")
        val siteJson = args.getString(KEY_SITE) ?: return errorBundle(id, "缺少站点信息")
        val site = runCatching { siteFromTransport(siteJson) }
            .getOrElse { return errorBundle(id, "站点信息解析失败：" + rootCause(it)) }
        android.util.Log.d("NextMovieSpider", "site=${site.name} type=${site.type}")
        // .js 站点走 QuickJS；其余（csp_* / jar）走 DexClassLoader。两条路径共享同一个 :spider 进程隔离。
        val isJs = site.isJavaScript
        val loadStarted = System.nanoTime()
        val spider = runBlocking { if (isJs) jsLoader.obtain(site) else loader.obtain(site) }

        android.util.Log.i("NextMovieTiming", "plugin-obtain action=$action ${(System.nanoTime() - loadStarted) / 1_000_000}ms")

        if (action == SpiderProtocol.ACTION_INIT) return okBundle(id, "ready")

        val sourceStarted = System.nanoTime()
        val raw = when (action) {
            // TVBox 的两段式首页：homeContent 只给分类（class），homeVideoContent 才给视频列表。
            // 合并成一份返回，宿主一次请求就能同时渲染分类 Tab 与海报行。
            ACTION_HOME -> {
                val homeStarted = System.nanoTime()
                val home = runCatching { spider.homeContent(true) }
                    .onFailure { android.util.Log.w("NextMovieSpider", "homeContent(true) 失败: " + rootCause(it), it) }
                    .getOrNull()?.takeIf { it.isNotBlank() && it != "{}" }
                android.util.Log.i("NextMovieTiming", "plugin-home-content ${(System.nanoTime() - homeStarted) / 1_000_000}ms")
                val hasVideos = runCatching { org.json.JSONObject(home.orEmpty()).optJSONArray("list")?.length() ?: 0 }.getOrDefault(0) > 0
                val video = if (hasVideos) null else runCatching { spider.homeVideoContent() }
                    .onFailure { android.util.Log.w("NextMovieSpider", "homeVideoContent 失败: " + rootCause(it), it) }
                    .getOrNull()?.takeIf { it.isNotBlank() && it != "{}" }
                android.util.Log.i("NextMovieTiming", "plugin-home-video ${if (hasVideos) "skipped" else "called"} total=${(System.nanoTime() - homeStarted) / 1_000_000}ms")
                val merged = mergeHome(home, video)
                if (merged.isBlank()) {
                    (spider as? JsSpider)?.asyncNote()?.let { return errorBundle(id, it) }
                }
                merged
            }

            ACTION_CATEGORY -> {
                val tid = args.getString(KEY_ARG1).orEmpty()
                val page = args.getString(KEY_PAGE) ?: "1"
                val filter = args.getBoolean(KEY_FILTER, false)
                val extend = args.getString(KEY_ARG3).orEmpty()
                val map = HashMap<String, String>()
                if (extend.isNotBlank()) map["extend"] = extend
                val raw = runCatching { spider.categoryContent(tid, page, filter, map) }.getOrNull().orEmpty()
                android.util.Log.i("NextMovieDiag", "category tid=$tid raw0=" + raw.take(600))
                if (raw.isBlank()) {
                    (spider as? JsSpider)?.asyncNote()?.let { return errorBundle(id, it) }
                }
                raw
            }

            ACTION_DETAIL -> {
                val requestedId = args.getString(KEY_ARG1).orEmpty()
                // 「msearch:<keyword>」是插件 homeVod 给的检索式 id（Marco/CatVod 语义）：
                // 它不能直接喂给 detailContent（会立刻返回空），必须先 search 拿到真实 vod_id 再取详情。
                var vodId = requestedId
                var raw = ""
                if (requestedId.startsWith("msearch:", true)) {
                    val hint = args.getString(KEY_ARG2).orEmpty().trim()
                    val keyword = hint.ifBlank { requestedId.substringAfter(':').trim() }
                    val searchRaw = runCatching { spider.searchContent(keyword, false, "1") }
                        .onFailure { android.util.Log.w("NextMovieDiag", "msearch search 失败: " + rootCause(it), it) }
                        .getOrNull().orEmpty()
                    android.util.Log.i("NextMovieDiag", "msearch search kw=$keyword len=${searchRaw.length} head=" + searchRaw.take(200))
                    val realId = runCatching {
                        org.json.JSONObject(searchRaw).optJSONArray("list")
                            ?.optJSONObject(0)?.optString("vod_id").orEmpty()
                    }.getOrDefault("")
                    android.util.Log.i("NextMovieSpider", "msearch id=$requestedId kw=$keyword -> vod_id=$realId")
                    if (realId.isNotBlank() && realId != requestedId) {
                        vodId = realId
                        raw = runCatching { spider.detailContent(listOf(vodId)) }.getOrNull().orEmpty()
                        if (raw.isBlank() || raw == "{}") vodId = requestedId
                    }
                }
                if (raw.isBlank() || raw == "{}") {
                    android.util.Log.i("NextMovieSpider", "detailContent id=$vodId class=${spider.javaClass.name}")
                    raw = runCatching { spider.detailContent(listOf(vodId)) }
                        .onFailure { android.util.Log.w("NextMovieSpider", "detailContent 失败: " + rootCause(it), it) }
                        .getOrNull().orEmpty()
                }
                if (raw.isBlank()) {
                    val bare = requestedId.substringAfter(':')
                    if (bare != requestedId) {
                        val alt = runCatching { spider.detailContent(listOf(bare)) }.getOrNull().orEmpty()
                        android.util.Log.i("NextMovieDiag", "detail bare id=$bare len=${alt.length}")
                    }
                    android.util.Log.w("NextMovieSpider", "detailContent blank for id=$requestedId")
                    (spider as? JsSpider)?.asyncNote()?.let { return errorBundle(id, it) }
                }
                raw
            }

            ACTION_SEARCH -> {
                val keyword = args.getString(KEY_ARG1).orEmpty()
                val page = args.getString(KEY_ARG2) ?: "1"
                val quick = args.getBoolean(KEY_FILTER, false)
                val first = runCatching { spider.searchContent(keyword, quick, page) }
                    .onFailure { android.util.Log.w("NextMovieDiag", "searchContent(k,quick,page) 失败: " + rootCause(it), it) }
                    .getOrNull()
                android.util.Log.i("NextMovieDiag", "search kw=$keyword quick=$quick page=$page len=${first?.length ?: -1}")
                (first?.takeIf { it.isNotBlank() && it != "{}" }
                    ?: runCatching { spider.searchContent(keyword, quick) }
                        .onFailure { android.util.Log.w("NextMovieDiag", "searchContent(k,quick) 失败: " + rootCause(it), it) }
                        .getOrNull().orEmpty())
            }

            ACTION_PLAY -> {
                val flag = args.getString(KEY_ARG1).orEmpty()
                val episode = args.getString(KEY_ARG2).orEmpty()
                if (!isJs) loader.bindActivity(site)
                val proxy = newProxy()
                proxy.activate { params -> if (isJs) spider.proxy(HashMap(params)) else loader.proxy(site, params) }
                if (!isJs) loader.prepareProxy(site, proxy.port)
                val original = spider.playerContent(flag, episode, ArrayList()).orEmpty()
                val raw = runCatching {
                    org.json.JSONObject(original).apply {
                        val url = optString("url")
                        if (url.isNotBlank()) put("url", proxy.rewrite(url))
                    }.toString()
                }.getOrDefault(original)
                if (raw.isBlank()) {
                    (spider as? JsSpider)?.asyncNote()?.let { return errorBundle(id, it) }
                }
                raw
            }

            else -> return errorBundle(id, "未知动作：$action")
        }

        android.util.Log.i("NextMovieTiming", "plugin-source action=$action ${(System.nanoTime() - sourceStarted) / 1_000_000}ms")
        if (raw.isBlank() || raw == "{}") return errorBundle(id, "插件没有返回数据")
        return okBundle(id, raw)
    }

    /** 把 class（来自 home）与 list（来自 homeVod）合并；任一为空就退化成另一个。 */
    private fun mergeHome(home: String?, video: String?): String {
        if (home.isNullOrBlank()) return video.orEmpty()
        if (video.isNullOrBlank()) return home
        return runCatching {
            val h = org.json.JSONObject(home)
            val v = org.json.JSONObject(video)
            val list = h.optJSONArray("list")
            if (list == null || list.length() == 0) {
                v.optJSONArray("list")?.let { h.put("list", it) }
            }
            h.toString()
        }.getOrDefault(home)
    }

    private fun rootCause(e: Throwable): String {
        val parts = ArrayList<String>()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            val m = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.name
            if (parts.none { it == m }) parts += m
            t = t.cause
            depth++
        }
        return parts.joinToString(" ← ").take(400)
    }
}
