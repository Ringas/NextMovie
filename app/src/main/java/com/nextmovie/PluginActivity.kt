package com.nextmovie

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.github.catvod.crawler.Spider
import com.nextmovie.core.config.Site
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodParser
import com.nextmovie.core.spider.PluginRuntime
import com.nextmovie.core.spider.SpiderProtocol
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.settings.SettingsButton
import com.nextmovie.ui.theme.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Runs with a real foreground Activity in :spider. Plugin-owned login dialogs stay in their process. */
class PluginActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engines: PluginRuntime.Engines
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = runCatching { SpiderProtocol.siteFromTransport(intent.getStringExtra("site").orEmpty()) }.getOrNull()
        if (site == null) { finish(); return }
        engines = PluginRuntime.acquire(this)
        com.nextmovie.core.spider.PluginDialogs.attach(this)
        // 本 Activity 在 :spider 进程，主进程 MainActivity 的 Supplier 传不过来；
        // 不在这里登记的话，插件进程里 Spider.getActivity() 永远 null，登录框弹不出来。
        com.github.catvod.Runtime.set(this)
        com.github.catvod.Runtime.setActivity { this }
        setResult(RESULT_OK)
        // 从详情/播放页「未登录点网盘线路」跳过来时，autoPan 直接把人送到对应网盘的官方登录页，
        // 不再需要先在账户页里手点一次「网盘登录 → 夸克」。
        autoPan = intent.getStringExtra("autoPan").orEmpty()
        setContent { TvDisplay { NextMovieTheme { AccountBrowser(site) } } }
    }

    private var autoPan: String = ""
    /** panconfig 模式：页面由配置中心插件自己渲染，cookie 也由插件/代理落盘，宿主不要抢着 finish。 */
    private var configMode = false

    @Composable private fun AccountBrowser(site: Site) {
        var spider by remember { mutableStateOf<Spider?>(null) }
        var entries by remember { mutableStateOf<List<Vod>>(emptyList()) }
        var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var status by remember { mutableStateOf("正在打开来源账户界面…") }
        var busy by remember { mutableStateOf(false) }
        var action by remember { mutableStateOf("") }
        var input by remember { mutableStateOf("") }
        // 网盘登录：来源（Wex Guard 类插件）用 action 暴露扫码入口，先把二维码拿到手
        var loginOpen by remember { mutableStateOf(false) }
        var qrUrl by remember { mutableStateOf<String?>(null) }
        var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var qrHint by remember { mutableStateOf("") }
        var activePan by remember { mutableStateOf("") }
        var loginWeb by remember { mutableStateOf<String?>(null) }
        var cookieMarker by remember { mutableStateOf("") }
        var cookieMarked by remember { mutableStateOf(false) }
        val first = remember { FocusRequester() }
        val uiScope = rememberCoroutineScope()
        fun String.len2blank(): Boolean = this.isBlank() || this.trim() == "{}"

        fun accept(raw: String) {
            android.util.Log.i("NextMoviePan", "action-raw len=${raw.length} head=" + raw.take(400))
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val list = VodParser.list(json)
            if (list.isNotEmpty()) entries = list
            val classes = json?.optJSONArray("class")
            if (classes != null) categories = (0 until classes.length()).mapNotNull { i ->
                val c = classes.optJSONObject(i) ?: return@mapNotNull null
                c.optString("type_id").takeIf { it.isNotBlank() }?.let { it to c.optString("type_name") }
            }
            status = json?.optString("msg")?.takeIf { it.isNotBlank() }
                ?: if (list.isEmpty()) "请完成来源弹窗中的操作。没有弹窗时，可选择分类或刷新；此来源可能没有账户界面。" else "请选择网盘或登录操作；登录二维码由来源提供。"
        }
        fun runCall(call: suspend (Spider) -> String) {
            if (busy) return
            uiScope.launch {
                busy = true
                try { spider?.let { instance -> accept(withContext(Dispatchers.IO) { call(instance) }) } }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { status = "来源操作失败：${e.cause?.message ?: e.message ?: "未知错误"}" }
                finally { busy = false }
            }
        }
        // 网盘登录：直接打开官方登录页（页面本身就是扫码），轮询 WebView Cookie，抓到后注入来源
        fun panLoginUrl(id: String): Pair<String, String> = when (id) {
            "quark" -> "https://pan.quark.cn/#/login" to "__puus"
            "uc" -> "https://drive.uc.cn/login" to "__puus"
            "ali" -> "https://www.alipan.com/" to "SESSIONID"
            "baidu" -> "https://pan.baidu.com/" to "BDUSS"
            else -> "" to ""
        }

        fun finishCasLogin(site: Site, id: String, cookie: String) {
            if (cookieMarked) return
            cookieMarked = true
            val label = if (id == "quark") "夸克" else "UC"
            getSharedPreferences("nextmovie", Context.MODE_PRIVATE).edit()
                .putString("pan_cookie_$id", cookie).apply()
            val injected = runCatching { engines.jar.injectPanCookie(site, id, cookie) }.getOrDefault(false)
            qrHint = if (injected) "「$label」已登录并同步给来源，回去点播放就行"
            else "「$label」Cookie 已拿到（${cookie.length} 字符），来源没有公开注入接口，重启来源后再试"
            status = qrHint
            qrBitmap = null
        }

        fun startCasLogin(id: String) {
            activePan = id
            loginWeb = null
            cookieMarked = false
            qrHint = "正在获取登录二维码…"
            status = qrHint
            com.nextmovie.core.pan.PanLogin.start(id) { snap ->
                uiScope.launch {
                    qrBitmap = snap.qr
                    qrHint = snap.message
                    status = snap.message
                    if (snap.state == com.nextmovie.core.pan.PanLogin.State.SUCCESS) {
                        finishCasLogin(site, id, snap.cookie)
                    }
                }
            }
        }

        fun startPanLogin(id: String) {
            val (url, marker) = panLoginUrl(id)
            if (url.isBlank()) return
            activePan = id
            loginWeb = url
            cookieMarker = marker
            qrBitmap = null
            qrHint = "登录页已打开：用手机 App 扫页面上的二维码，登录成功会自动同步"
            status = qrHint
        }

        suspend fun finishPanLogin(site: Site, id: String, cookie: String): Boolean {
            if (id == activePan && cookieMarked) return true
            val label = when (id) { "quark" -> "夸克"; "uc" -> "UC"; "ali" -> "阿里"; "baidu" -> "百度/嘟嘟"; else -> id }
            // WebView 里可能早就有匿名 __puus，必须先验真登录态，否则会在没扫码时就把扫码页关掉。
            val ok = withContext(Dispatchers.IO) { runCatching { com.nextmovie.core.pan.PanLogin.verify(id, cookie) }.getOrDefault(false) }
            if (!ok) {
                qrHint = "还没登录成功：请用「$label」App 扫画面里的二维码并确认"
                status = qrHint
                return false
            }
            cookieMarked = true
            val clean = com.nextmovie.core.pan.PanLogin.mergeCookies(cookie)
            getSharedPreferences("nextmovie", Context.MODE_PRIVATE).edit()
                .putString("pan_cookie_$id", clean).apply()
            runCatching { java.io.File(filesDir, "cookie_$id.txt").writeText(clean) }
            val injected = withContext(Dispatchers.IO) { runCatching { engines.jar.injectPanCookie(site, id, clean) }.getOrDefault(false) }
            qrHint = if (injected) "「$label」已登录并同步给来源，正在继续播放…"
            else "「$label」Cookie 已抓到（长度 ${clean.length}），来源没有公开注入接口，重启来源后再试"
            status = qrHint
            loginWeb = null
            android.util.Log.i("NextMoviePan", "login-ok id=$id len=${clean.length} injected=$injected")
            // 扫完直接回详情页继续刚才那一集，不再让人手动回去点播放。
            if (injected || clean.isNotBlank()) {
                setResult(RESULT_OK, Intent().putExtra("pan_login_ok", id))
                finish()
            }
            return true
        }

        // 从「未登录点网盘线路」进来：直接开对应网盘官方登录页（页面本身就是扫码框）。
        LaunchedEffect(autoPan) {
            if (autoPan.isNotBlank()) {
                loginOpen = true
                startPanLogin(autoPan)
            }
        }

        // 配置中心的网页版登录页：插件把 do=pan&action=config 交给宿主 9978 代理，
        // 由配置中心插件自己返回带二维码的 HTML，扫码/cookie 落盘都在插件侧完成。
        LaunchedEffect(Unit) {
            if (intent.getStringExtra("action") == "panconfig") {
                configMode = true
                loginOpen = true
                loginWeb = "http://127.0.0.1:9978/proxy?do=pan&action=config"
            }
        }

        fun home(instance: Spider): String {
            val h = runCatching { JSONObject(instance.homeContent(true).ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val videos = runCatching { JSONObject(instance.homeVideoContent().ifBlank { "{}" }) }.getOrDefault(JSONObject())
            if ((h.optJSONArray("list")?.length() ?: 0) == 0) videos.optJSONArray("list")?.let { h.put("list", it) }
            return h.toString()
        }
        LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
        LaunchedEffect(site.runtimeKey) {
            busy = true
            try {
                spider = withContext(Dispatchers.IO) {
                    if (site.isJavaScript) engines.js.obtain(site) else engines.jar.obtain(site)
                }
                val entryAction = intent.getStringExtra("action")
                val entryId = intent.getStringExtra("entry")
                // 触发 action 之前先把宿主 Activity 绑进插件字段（Marco 的 syncAllPluginActivities）
                withContext(Dispatchers.Main) { engines.jar.bindHostActivity(this@PluginActivity, site) }
                var raw = withContext(Dispatchers.IO) {
                    when {
                        !entryAction.isNullOrBlank() -> spider!!.action(entryAction)
                        !entryId.isNullOrBlank() -> spider!!.categoryContent(entryId, "1", false, hashMapOf())
                        else -> home(spider!!)
                    }
                }
                if (entryAction == "probe") {
                    val all = listOf("quark","quarkcookie","quark_cookie","panquark","quarklogin","LoginShow","pankaiguan","panpaixu","panorder","drive","login","panso","pandrv","panqr","qr","qrcode","__quark__","__open_config__","ucpancookie","uctvpancookie","aliyuntoken","baidupanlogin","115pancookie","tianyilogin","ydyuncookie","pan123login","panxunleiuser","bilicookie","pan139","pan189","guangya","thunder","bili")
                    for (f in all) {
                        val r = withContext(Dispatchers.IO) { runCatching { spider!!.action(f) }.getOrDefault("ERR") }
                        android.util.Log.i("NextMoviePan", "probe site=$site flag=$f len=${r.length} head=" + r.take(120))
                    }
                    raw = "{}"
                }
                // 有些 guard 的登录 flag 不是裸网盘名，试几个 FongMi 惯用写法，命中第一个非空即用
                if (!entryAction.isNullOrBlank() && raw.len2blank()) {
                    val cands = listOf(
                        entryAction, "LoginShow", "forward:LoginShow", "login", "account",
                        entryAction + "cookie", entryAction + "_cookie", "pan" + entryAction, entryAction + "login"
                    ).distinct()
                    for (f in cands) {
                        val r = withContext(Dispatchers.IO) { runCatching { spider!!.action(f) }.getOrDefault("") }
                        android.util.Log.i("NextMoviePan", "action-try flag=$f len=${r.length} head=" + r.take(160))
                        if (!r.len2blank()) { raw = r; break }
                    }
                }
                // 调试：在「有 Activity」的进程里跑一次 playerContent。
                // 如果插件是在 playerContent 里发现没登录就 Runtime.dialog 弹扫码，这里能看到弹框。
                if (entryAction == "dbgplay") {
                    val vodId = entryId ?: "26229"
                    val detail = withContext(Dispatchers.IO) {
                        runCatching { JSONObject(spider!!.detailContent(listOf(vodId))) }.getOrNull()
                    }
                    val vod = detail?.optJSONArray("list")?.optJSONObject(0)
                    val froms = vod?.optString("vod_play_from").orEmpty().split("$$$")
                    val groups = vod?.optString("vod_play_url").orEmpty().split("$$$")
                    var flag = ""; var url = ""; var epName = ""
                    for (i in froms.indices) {
                        val entry = groups.getOrElse(i) { "" }.split("#").firstOrNull().orEmpty()
                        val name = entry.substringBefore("$")
                        val u = entry.substringAfter("$", "")
                        if (u.isNotBlank()) { flag = froms[i]; url = u; epName = name; break }
                    }
                    android.util.Log.i("NextMoviePan", "dbgplay vod=$vodId flag=$flag ep=$epName urllen=${url.length}")
                    if (flag.isNotBlank() && url.isNotBlank()) {
                        val res = withContext(Dispatchers.IO) {
                            runCatching { spider!!.playerContent(flag, url, listOf()) }.getOrNull()
                        }
                        android.util.Log.i("NextMoviePan", "dbgplay-res len=${res?.length} head=" + (res ?: "").take(160))
                        raw = res ?: "{}"
                    } else raw = "{}"
                }
                accept(raw)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { status = "无法打开来源账户：${e.cause?.message ?: e.message}" }
            finally { busy = false }
        }
        LaunchedEffect(loginWeb, cookieMarker) {
            val url = loginWeb ?: return@LaunchedEffect
            val marker = cookieMarker
            val roots = listOf(
                url.substringBefore('#'), "https://pan.quark.cn", "https://quark.cn",
                "https://drive.uc.cn", "https://uc.cn", "https://www.alipan.com", "https://pan.baidu.com"
            )
            var ticks = 0
            while (true) {
                delay(1_500)
                ticks++
                val manager = android.webkit.CookieManager.getInstance()
                // 登录态常常写在父域（.quark.cn），只读 pan.quark.cn 会漏掉真 Cookie
                val cookie = com.nextmovie.core.pan.PanLogin.mergeCookies(*roots.map { manager.getCookie(it).orEmpty() }.toTypedArray())
                android.util.Log.i("NextMoviePan", "cookie poll marker=$marker len=${cookie.length} logged=${com.nextmovie.core.pan.PanLogin.looksLoggedIn(activePan, cookie)}")
                if (!configMode && cookie.isNotBlank() && com.nextmovie.core.pan.PanLogin.looksLoggedIn(activePan, cookie)) {
                    if (finishPanLogin(site, activePan, cookie)) break
                }
                // 官方二维码约 100 秒过期：没扫就定时刷新页面，免得人一慢就扫到过期码
                if (ticks % 70 == 0) {
                    android.util.Log.i("NextMoviePan", "qr-refresh ticks=$ticks")
                    uiScope.launch { runCatching { loginWeb?.let { web -> loginWeb = null; delay(150); loginWeb = web } } }
                }
            }
        }
        BackHandler { finish() }
        LazyColumn(Modifier.fillMaxSize().background(Tokens.Bg), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsButton("‹ 返回", Modifier.focusRequester(first)) { finish() }
                    SettingsButton("刷新账户状态", enabled = !busy) { runCall { home(it) } }
                }
            }
            item { Text(site.name + " · 账户与网盘", color = Tokens.Text, style = MaterialTheme.typography.headlineMedium) }
            item { Text(if (busy) "正在读取…" else status, color = Tokens.TextDim, style = MaterialTheme.typography.bodyLarge) }
            item {
                SettingsButton(if (loginOpen) "网盘登录（点来源名出二维码）" else "网盘登录 · 夸克 / UC / 阿里 / 百度", enabled = !busy) {
                    loginOpen = true
                }
            }
            if (loginOpen) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("选择你的网盘，点一下直接出扫码：", color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("quark" to "夸克", "uc" to "UC", "ali" to "阿里", "baidu" to "百度·嘟嘟").forEach { (id, label) ->
                                SettingsButton(if (activePan == id) "$label ✓" else label, enabled = !busy) {
                                    // 统一走官方网页登录：页面内的官方二维码才是真 payload，
                                    // 宿主自建 CAS token 的二维码扫出来只是文本（已实测）。
                                    startPanLogin(id)
                                }
                            }
                            if (activePan.isNotBlank()) {
                                SettingsButton("重新打开登录页", enabled = !busy) { startPanLogin(activePan) }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val web = loginWeb?.takeIf { qrBitmap == null }
                        if (web != null) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { ctx ->
                                    android.webkit.WebView(ctx).apply {
                                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        // TV 上整页塞进窗口，二维码才会一开始就在画面里
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.userAgentString = settings.userAgentString + " NextMovieTV"
                                        webViewClient = object : android.webkit.WebViewClient() {
                                            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                                                super.onPageFinished(view, url)
                                                // 桌面 UA 打开 pan.quark.cn 时首页是宣传页，扫码框要点顶栏「登录」才出来。
                                                // 先回到顶部，再自动点一次「登录」，随后才开始轮询 Cookie。
                                                view.postDelayed({ view.scrollTo(0, 0) }, 600)
                                                // 官方登录面板常被页面横幅挤到屏幕外：整体缩放到 72%，让二维码整块落在画面里
                                                view.postDelayed({
                                                    view.evaluateJavascript(
                                                        "(function(){try{document.body.style.zoom='0.72';window.scrollTo(0,0);return 'zoom-ok'}catch(e){return 'err'}})()"
                                                    ) { r -> android.util.Log.i("NextMoviePan", "login-zoom result=$r") }
                                                }, 900)
                                            }
                                        }
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                        setBackgroundColor(android.graphics.Color.WHITE)
                                        // 手机竖版 UA，页面才会直接给扫码框而不是官网大图
                                        // 桌面 UA：官方页才会给「扫码登录」面板（手机 UA 会跳去下载 App）
                                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                                        loadUrl(web)
                                    }
                                },
                                modifier = Modifier.width(1000.dp).height(820.dp)
                            )
                        }
                        qrBitmap?.let { bmp ->
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "登录二维码",
                                modifier = Modifier.width(220.dp).height(220.dp).background(androidx.compose.ui.graphics.Color.White)
                            )
                        }
                        if (qrHint.isNotBlank()) Text(qrHint, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            itemsIndexed(categories) { _, c ->
                SettingsButton(c.second, enabled = !busy) { runCall { it.categoryContent(c.first, "1", false, hashMapOf()) } }
            }
            itemsIndexed(entries) { _, vod ->
                Text(vod.name, color = Tokens.Text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().background(Tokens.SurfaceHigh).tvClickable(!busy) {
                        runCall { instance ->
                            when {
                                vod.action.isNotBlank() -> instance.action(vod.action)
                                vod.tag == "folder" -> instance.categoryContent(vod.id, "1", false, hashMapOf())
                                vod.id.startsWith("action://") -> instance.action(android.net.Uri.decode(vod.id.removePrefix("action://")))
                                else -> instance.detailContent(listOf(vod.id))
                            }
                        }
                    }.padding(20.dp))
            }
            item { Text("扫码授权请由本人在网盘官方应用完成；返回后可刷新账户状态。", color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    override fun onResume() { super.onResume(); com.nextmovie.core.spider.PluginDialogs.attach(this) }
    override fun onDestroy() {
        com.nextmovie.core.spider.PluginDialogs.detach(this)
        scope.cancel()
        if (::engines.isInitialized) PluginRuntime.release()
        super.onDestroy()
    }
    companion object {
        fun intent(context: Context, site: Site, entry: String? = null, action: String? = null, autoPan: String? = null): Intent =
            Intent(context, PluginActivity::class.java).putExtra("site", with(SpiderProtocol) { site.toTransport() }).putExtra("entry", entry).putExtra("action", action).putExtra("autoPan", autoPan)
        fun open(context: Context, site: Site, entry: String? = null, action: String? = null) { context.startActivity(intent(context, site, entry, action)) }
    }
}
