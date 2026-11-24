package com.nextmovie

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
        setResult(RESULT_OK)
        setContent { TvDisplay { NextMovieTheme { AccountBrowser(site) } } }
    }
    @Composable private fun AccountBrowser(site: Site) {
        var spider by remember { mutableStateOf<Spider?>(null) }
        var entries by remember { mutableStateOf<List<Vod>>(emptyList()) }
        var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var status by remember { mutableStateOf("正在打开来源账户界面…") }
        var busy by remember { mutableStateOf(false) }
        var action by remember { mutableStateOf("") }
        var input by remember { mutableStateOf("") }
        val first = remember { FocusRequester() }
        val uiScope = rememberCoroutineScope()
        fun accept(raw: String) {
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
                val raw = withContext(Dispatchers.IO) {
                    when {
                        !entryAction.isNullOrBlank() -> spider!!.action(entryAction)
                        !entryId.isNullOrBlank() -> spider!!.categoryContent(entryId, "1", false, hashMapOf())
                        else -> home(spider!!)
                    }
                }
                accept(raw)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { status = "无法打开来源账户：${e.cause?.message ?: e.message}" }
            finally { busy = false }
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
        fun intent(context: Context, site: Site, entry: String? = null, action: String? = null): Intent =
            Intent(context, PluginActivity::class.java).putExtra("site", with(SpiderProtocol) { site.toTransport() }).putExtra("entry", entry).putExtra("action", action)
        fun open(context: Context, site: Site, entry: String? = null, action: String? = null) { context.startActivity(intent(context, site, entry, action)) }
    }
}
