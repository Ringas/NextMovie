package com.nextmovie.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.LocalTvDisplay
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repo: AppRepository, onBack: () -> Unit, onConfigLoaded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val display = LocalTvDisplay.current
    var config by remember { mutableStateOf(repo.configUrl()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(repo.tmdb.token) }
    var mirror by remember { mutableStateOf(repo.tmdbMirrorEnabled()) }
    var images by remember { mutableStateOf(repo.tmdbImageProxyEnabled()) }
    var enhance by remember { mutableStateOf(repo.posterEnhance) }
    var race by remember { mutableStateOf(repo.tmdbRaceEnabled()) }
    var dnsCustom by remember { mutableStateOf(repo.dnsCustom()) }
    var probe by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(repo.tmdbCustomBase()) }
    val accountLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        scope.launch { repo.invalidateContent(); message = "账户界面已关闭，已刷新内容缓存" }
    }
    val loadFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); firstFocus.requestFocus() }
    fun load() {
        if (busy) return
        scope.launch {
            busy = true
            message = "正在读取配置…"
            try {
                val count = repo.loadConfig(config.trim())
                message = "已导入 $count 个来源"
                onConfigLoaded()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "导入失败，请检查地址" }
            finally { busy = false }
        }
    }
    if (pairing) PairingDialog(onDismiss = { pairing = false }, onReceived = {
        config = it; pairing = false; message = "已从手机接收配置，按确认键完成导入"
        scope.launch { kotlinx.coroutines.delay(100); loadFocus.requestFocus() }
    })
    LazyColumn(Modifier.fillMaxSize().background(Tokens.Bg), contentPadding = PaddingValues(Tokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SettingsButton("‹ 返回", onClick = onBack)
                Text("设置", color = Tokens.Text, style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { Text("资源导入", color = Tokens.Text, style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsButton("手机扫码导入", Modifier.focusRequester(firstFocus)) { pairing = true }
                SettingsButton(if (busy) "加载中…" else "加载配置", Modifier.focusRequester(loadFocus), enabled = !busy, onClick = ::load)
            }
        }
        item { SettingsField("配置网址或 JSON", config) { config = it } }
        if (message.isNotBlank()) item { Text(message, color = Tokens.Accent, style = MaterialTheme.typography.bodyMedium) }
        item { Text("大屏显示", color = Tokens.Text, style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(1f to "标准", 1.125f to "大号", 1.25f to "超大").forEach { (scale, name) ->
                    SettingsButton((if (display.scale == scale) "● " else "") + name) { display.select(scale) }
                }
            }
        }
        item { Text("来源与账户", color = Tokens.Text, style = MaterialTheme.typography.titleLarge) }
        item { Text("选择来源返回首页；网盘登录在来源自己的账户界面完成。", color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium) }
        items(repo.sites, key = { it.runtimeKey }) { site ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (repo.currentSite?.runtimeKey == site.runtimeKey) "● " else "") + site.name,
                    color = Tokens.Text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).background(Tokens.Surface, RoundedCornerShape(12.dp)).tvClickable {
                        repo.selectSite(site.key); onConfigLoaded()
                    }.padding(18.dp))
                if (site.isSpider && site.isPlayable) SettingsButton("账户 / 网盘") {
                    accountLauncher.launch(com.nextmovie.PluginActivity.intent(context, site))
                }
            }
        }
        item { SettingsButton(if (advanced) "收起高级设置" else "高级设置") { advanced = !advanced } }
        if (advanced) {
            item { SettingsField("TMDB Token（可留空）", token) { token = it } }
            item { SettingsButton("保存 Token") { repo.configureTmdb(token); message = "已保存" } }
            item { SettingsField("自定义 TMDB 镜像，每行一个", custom) { custom = it } }
            item { SettingsButton("使用自定义镜像") { repo.setTmdbRoute("custom", custom); message = "已保存镜像" } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsButton("自动线路") { repo.setTmdbRoute("auto") }
                    SettingsButton("官方线路") { repo.setTmdbRoute("global") }
                    SettingsButton("备用线路") { repo.setTmdbRoute("fast") }
                }
            }
            item { SettingsButton("多线路竞速：${if (race) "开" else "关"}") { race = !race; repo.setTmdbRace(race) } }
            item { SettingsButton("线路测速") {
                scope.launch { probe = "正在测速…"; probe = runCatching { repo.probeTmdb().joinToString("\n") { it.first + " · " + it.third } }.getOrDefault("测速失败") }
            } }
            if (probe.isNotBlank()) item { Text(probe, color = Tokens.TextDim) }
            item { SettingsButton("公共反代：${if (mirror) "开" else "关"}") { mirror = !mirror; repo.setTmdbMirror(mirror) } }
            item { SettingsButton("图片中转：${if (images) "开" else "关"}") { images = !images; repo.setTmdbImageProxy(images) } }
            item { SettingsButton("海报补全：${if (enhance) "开" else "关"}") { enhance = !enhance; repo.setPosterEnhance(enhance) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsButton("自动 DNS") { repo.setDns("auto"); message = "已切换自动 DNS" }
                    SettingsButton("系统 DNS") { repo.setDns("system"); message = "已切换系统 DNS" }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsButton("Cloudflare DNS") { repo.setDns("cloudflare"); message = "已切换 DNS" }
                    SettingsButton("Google DNS") { repo.setDns("google"); message = "已切换 DNS" }
                    SettingsButton("阿里 DNS") { repo.setDns("aliyun"); message = "已切换 DNS" }
                }
            }
            item { SettingsField("自定义 DoH 模板（含 %s）", dnsCustom) { dnsCustom = it } }
            item { SettingsButton("保存自定义 DNS") { repo.setDns("custom", dnsCustom); message = "已保存 DNS" } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsButton("清理海报缓存") { repo.clearPosterCache(); message = "已清理海报缓存" }
                    SettingsButton("清理观看历史") { repo.history.clear(); message = "已清理观看历史" }
                }
            }
        }
    }
}

@Composable
fun SettingsButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.background(if (enabled) Tokens.SurfaceHigh else Tokens.Surface, RoundedCornerShape(12.dp))
        .tvClickable(enabled, onClick).padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Tokens.Text else Tokens.TextDim, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(value, onValueChange, textStyle = TextStyle(color = Tokens.Text, fontSize = 18.sp),
            cursorBrush = SolidColor(Tokens.Accent), singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
                .background(Tokens.Surface, RoundedCornerShape(12.dp))
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Tokens.Stroke, RoundedCornerShape(12.dp)).padding(18.dp))
    }
}
