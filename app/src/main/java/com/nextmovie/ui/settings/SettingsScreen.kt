package com.nextmovie.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nextmovie.ui.common.TvAction
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.ui.player.engine.DecodeMode
import com.nextmovie.ui.player.engine.DecoderProbe
import com.nextmovie.ui.player.engine.NmRenderersFactory
import com.nextmovie.ui.player.engine.PlayerEngineType
import com.nextmovie.ui.player.engine.PlayerPrefs
import com.nextmovie.ui.player.PlaybackSession
import com.nextmovie.core.config.Site
import com.nextmovie.ui.common.BrandLogo
import com.nextmovie.ui.common.GlassCard
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.common.TvHintBar
import com.nextmovie.ui.common.TvKeys
import com.nextmovie.ui.theme.Ambient
import com.nextmovie.ui.theme.AmbientState
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 系统设置（对齐 Marco TV / tv_living_room_shell.dart 的设置页）
 *
 * 左栏 328dp：标题「系统设置」+ 7 段胶囊导航 + 底部品牌与版本；
 * 右栏：一张滚动玻璃卡，内容随左侧选中段落切换。
 */
private enum class Section(val title: String, val caption: String, val icon: ImageVector) {
    Sources("片源", "订阅、站点、海报解析", Icons.Filled.List),
    Playback("播放", "连播、解码、失败换线", Icons.Filled.Settings),
    Appearance("外观", "柔光、主题、取色", Icons.Filled.Star),
    Connect("连接", "网盘账号与 WebDAV", Icons.Filled.Home),
    Backup("数据", "收藏、历史、缓存", Icons.Filled.List),
    Version("关于", "版本与快捷键", Icons.Filled.Info)
}

@Composable
fun SettingsScreen(repo: AppRepository, onBack: () -> Unit, onConfigLoaded: () -> Unit) {
    var section by remember { mutableStateOf(Section.Sources) }
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val digit = TvKeys.sectionDigit(e)
                if (digit != null && digit in Section.entries.indices) {
                    section = Section.entries[digit]
                    true
                } else if (TvKeys.isBackUp(e)) {
                    onBack(); true
                } else false
            }
    ) {
            Column(
                Modifier
                    .width(328.dp)
                    .fillMaxSize()
                    .padding(start = 56.dp, top = 44.dp, bottom = 40.dp, end = 24.dp)
            ) {
                Text("系统设置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("方向键切换 · 确认进入 · 键盘 1–6 跳段 · Esc 返回", color = Tokens.TextFaint, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                val first = remember { FocusRequester() }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                Section.entries.forEachIndexed { index, item ->
                    TvPill(
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        selected = section == item,
                        autofocus = index == 0,
                        borderRadius = 14.dp,
                        horizontalPadding = 18.dp,
                        verticalPadding = 14.dp,
                        onClick = { section = item }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}",
                                color = if (section == item) Tokens.Accent else Tokens.TextGhost,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(18.dp)
                            )
                            Icon(
                                item.icon,
                                null,
                                tint = if (section == item) Color.White else Tokens.TextDim,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    item.title,
                                    color = if (section == item) Color.White else Tokens.TextDim,
                                    fontSize = 17.sp,
                                    fontWeight = if (section == item) FontWeight.SemiBold else FontWeight.Medium
                                )
                                Text(
                                    item.caption,
                                    color = Tokens.TextGhost,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandLogo(iconSize = 30.dp, fontSize = 17f)
                    Spacer(Modifier.width(12.dp))
                    Text(versionLabel(context), color = Tokens.TextFaint, fontSize = 12.5.sp)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(end = 56.dp, top = 44.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 44.dp)
                ) {
                    when (section) {
                        Section.Sources -> {
                            SourcesCard(repo, onConfigLoaded)
                            Spacer(Modifier.height(16.dp))
                            PostersCard(repo)
                            Spacer(Modifier.height(16.dp))
                            NetworkCard(repo)
                        }
                        Section.Playback -> PlaybackCard(repo)
                        Section.Appearance -> AppearanceCard()
                        Section.Connect -> {
                            NetdiskCard(repo)
                            Spacer(Modifier.height(16.dp))
                            WebdavCard(repo)
                        }
                        Section.Backup -> StorageCard(repo)
                        Section.Version -> VersionCard(context, repo)
                    }
                }
            }
    }
}

/* ---------------- 外观与柔光 ---------------- */

@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    var theme by remember { mutableStateOf(AmbientState.themeId) }
    var intensity by remember { mutableStateOf(AmbientState.intensity) }
    var sync by remember { mutableStateOf(AmbientState.posterSync) }
    GlassCard(
        title = "外观与柔光",
        subtitle = "客厅背景的柔光配色；选中后立即生效，全局通用"
    ) {
        SettingLabel("柔光主题")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Ambient.presets.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { preset ->
                        TvPill(
                            selected = theme == preset.id,
                            glass = theme != preset.id,
                            horizontalPadding = 16.dp,
                            onClick = {
                                theme = preset.id
                                AmbientState.setTheme(context, preset.id)
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(preset.accent, RoundedCornerShape(999.dp))
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(preset.name, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        SettingLabel("柔光强度")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("soft" to "柔和", "standard" to "标准", "vivid" to "鲜亮").forEach { (id, label) ->
                TvPill(
                    text = label,
                    selected = intensity == id,
                    glass = intensity != id,
                    onClick = {
                        intensity = id
                        AmbientState.setIntensity(context, id)
                    }
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        ToggleRow("海报取色联动", "聚焦海报时用它的主色点亮整屏柔光", sync) {
            sync = it
            AmbientState.setPosterSync(context, it)
        }
    }
}

/* ---------------- 订阅与片源 ---------------- */

@Composable
private fun SourcesCard(repo: AppRepository, onConfigLoaded: () -> Unit) {
    var url by remember { mutableStateOf(repo.configUrl()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(repo.configStatus()) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var subscriptions by remember { mutableStateOf(repo.savedSubscriptions()) }
    var includeLibrary by remember { mutableStateOf(false) }
    val exportBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use {
                        it.write(repo.libraryBackup(includeLibrary))
                    }
                }.fold({ "备份已保存" }, { "无法保存备份" })
            }
        }
    }
    val importBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use {
                        val limit = 4 * 1024 * 1024
                        val data = CharArray(limit + 1)
                        var size = 0
                        while (size < data.size) {
                            val read = it.read(data, size, data.size - size)
                            if (read < 0) break
                            size += read
                        }
                        require(size <= limit)
                        repo.restoreBackup(String(data, 0, size))
                    }
                }.getOrNull()
            }
            subscriptions = repo.savedSubscriptions()
            message = count?.let { "备份已合并，新增 $it 条订阅" } ?: "无法读取订阅备份"
        }
    }

    fun load(address: String = url) {
        if (busy) return
        busy = true
        message = "加载中…"
        scope.launch {
            val count = runCatching { repo.loadConfig(address) }.getOrElse { -1 }
            subscriptions = repo.savedSubscriptions()
            status = repo.configStatus()
            message = if (count > 0) "已载入 $count 个片源" else "载入失败，检查地址或网络"
            busy = false
            if (count > 0) onConfigLoaded()
        }
    }

    GlassCard(
        title = "订阅与片源",
        subtitle = "粘贴 TVBox / CatVod 订阅地址，加载后首页自动按站点聚合"
    ) {
        SettingsField(
            label = "订阅地址",
            value = url,
            placeholder = "https://example.com/sub.json",
            onValueChange = { url = it },
            imeAction = ImeAction.Go,
            onDone = { load() }
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPill(
                text = if (busy) "载入中…" else "载入订阅",
                prominent = true,
                enabled = !busy && url.isNotBlank(),
                onClick = { load() }
            )
            TvPill("扫码快传", glass = true, onClick = { Pairing.show = true })
            TvPill("刷新状态", glass = true, onClick = { status = repo.configStatus() })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPill("导出备份", glass = true, enabled = subscriptions.isNotEmpty() || includeLibrary, onClick = {
                exportBackup.launch("nextmovie-backup.json")
            })
            TvPill("导入备份", glass = true, onClick = { importBackup.launch(arrayOf("application/json", "text/plain")) })
            TvPill("包含收藏和历史", selected = includeLibrary, glass = true, onClick = { includeLibrary = !includeLibrary })
        }
        if (subscriptions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("已保存订阅", color = Tokens.TextDim, fontSize = 14.sp)
            subscriptions.forEach { address ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val active = address == repo.configUrl()
                    val label = if (address.startsWith("{")) "内嵌 JSON" else address.take(64)
                    TvPill(label, selected = active, enabled = !busy && !active, onClick = {
                        url = address
                        load(address)
                    })
                    if (!active) TvPill("移除", glass = true, enabled = !busy, onClick = {
                        repo.forgetSubscription(address)
                        subscriptions = repo.savedSubscriptions()
                    })
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Tokens.GreenText, fontSize = 13.5.sp)
        }
        Spacer(Modifier.height(22.dp))
        HLine()
        Spacer(Modifier.height(18.dp))
        Text("当前站点", color = Tokens.TextDim, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            repo.currentSite?.let { it.name + (if (it.isSpider) " · 插件" else " · 接口") } ?: "未选择",
            color = Color.White,
            fontSize = 16.sp
        )
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.text, color = Tokens.TextFaint, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("片源列表（${repo.sites.size}）", color = Tokens.TextDim, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repo.sites.take(40).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { site: Site ->
                        TvPill(
                            text = site.name,
                            selected = repo.currentSite?.key == site.key,
                            glass = repo.currentSite?.key != site.key,
                            horizontalPadding = 14.dp,
                            onClick = {
                                repo.selectSite(site.key)
                                status = repo.configStatus()
                                onConfigLoaded()
                            }
                        )
                    }
                }
            }
        }
        if (repo.sites.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("还没有片源，先载入一个订阅。", color = Tokens.TextFaint, fontSize = 13.5.sp)
        }
    }
    PairingHost()
}

/* ---------------- 海报与图片 ---------------- */

@Composable
private fun PostersCard(repo: AppRepository) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(repo.tmdb.token) }
    var enhance by remember { mutableStateOf(repo.posterEnhance) }
    var mirror by remember { mutableStateOf(repo.tmdbMirrorEnabled()) }
    var proxy by remember { mutableStateOf(repo.tmdbImageProxyEnabled()) }
    var cache by remember { mutableStateOf(repo.posterCacheBytes()) }
    var probe by remember { mutableStateOf<List<Triple<String, Boolean, String>>?>(null) }
    var probing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { cache = repo.posterCacheBytes() }

    GlassCard(
        title = "海报与图片",
        subtitle = "源站海报为主，TMDB 只做增强；无 Key 时用 B站兜底补图"
    ) {
        SettingsField(
            label = "TMDB Token（可选）",
            value = token,
            placeholder = "v3 / v4 只读 Token",
            onValueChange = { token = it },
            onDone = { repo.configureTmdb(token) }
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPill("保存 Token", prominent = true, onClick = { repo.configureTmdb(token) })
            TvPill(
                text = if (probing) "测速中…" else "线路测速",
                glass = true,
                enabled = !probing,
                onClick = {
                    probing = true
                    scope.launch {
                        probe = repo.probeTmdb()
                        probing = false
                    }
                }
            )
        }
        probe?.let { result ->
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.take(6).forEach { (host, ok, detail) ->
                    Text(
                        (if (ok) "✓ " else "✕ ") + host + "  " + detail.take(48),
                        color = if (ok) Tokens.GreenText else Tokens.TextFaint,
                        fontSize = 13.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        ToggleRow("海报增强（B站 / TMDB 兜底）", "源图缺失或过小时自动补一张高清海报", enhance) {
            enhance = it
            repo.setPosterEnhance(it)
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("TMDB 公共反代", "直连不通时走内置镜像线路", mirror) {
            mirror = it
            repo.setTmdbMirror(it)
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("图片走代理", "海报经 image proxy 拉取，国内更稳", proxy) {
            proxy = it
            repo.setTmdbImageProxy(it)
        }
        Spacer(Modifier.height(20.dp))
        HLine()
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("海报缓存", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(formatBytes(cache), color = Tokens.TextFaint, fontSize = 13.sp)
            }
            TvPill("清空缓存", destructive = true, onClick = {
                repo.clearPosterCache()
                cache = repo.posterCacheBytes()
            })
        }
    }
}

/* ---------------- 网络与解析 ---------------- */

@Composable
private fun NetworkCard(repo: AppRepository) {
    var mode by remember { mutableStateOf(repo.dnsMode()) }
    var custom by remember { mutableStateOf(repo.dnsCustom()) }
    GlassCard(
        title = "网络与解析",
        subtitle = "源站域名常被污染，自动模式会自动挑可用解析"
    ) {
        SettingLabel("DNS 策略")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "auto" to "自动（推荐）",
                "system" to "跟随系统",
                "cloudflare" to "Cloudflare",
                "aliyun" to "阿里 223.5.5.5",
                "google" to "Google 8.8.8.8",
                "custom" to "自定义"
            ).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        TvPill(
                            text = label,
                            selected = mode == id,
                            glass = mode != id,
                            onClick = {
                                mode = id
                                repo.setDns(id, custom)
                            }
                        )
                    }
                }
            }
        }
        if (mode == "custom") {
            Spacer(Modifier.height(14.dp))
            SettingsField(
                label = "自定义 DNS（可填 DoH / IP）",
                value = custom,
                placeholder = "https://doh.example/dns-query",
                onValueChange = { custom = it },
                onDone = { repo.setDns("custom", custom) }
            )
        }
        Spacer(Modifier.height(20.dp))
        HLine()
        Spacer(Modifier.height(16.dp))
        Text("当前线路：" + dnsLabel(mode), color = Tokens.TextDim, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "SmartDNS 只在插件/接口的域名解析阶段生效，不改动系统全局设置。",
            color = Tokens.TextFaint,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(14.dp))
        TvPill("应用并刷新", prominent = true, onClick = { repo.setDns(mode, custom) })
    }
}

/* ---------------- 4K 网盘 ---------------- */

@Composable
private fun NetdiskCard(repo: AppRepository) {
    val site = repo.currentSite
    GlassCard(
        title = "4K 网盘",
        subtitle = "网盘账号与直链都由来源插件托管，本机不保存任何凭据"
    ) {
        InfoRow("当前来源", site?.name ?: "未选择")
        Spacer(Modifier.height(10.dp))
        InfoRow("网盘入口", if (site?.isSpider == true) "可用（插件账户页面）" else "当前来源不提供")
        Spacer(Modifier.height(16.dp))
        Text(
            "在顶部「网盘」标签页可以查看支持的网盘类型，并直接打开来源的账户页面完成扫码登录。",
            color = Tokens.TextFaint,
            fontSize = 13.sp
        )
    }
}

/* ---------------- WebDAV 云同步 ---------------- */

@Composable
private fun WebdavCard(repo: AppRepository) {
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var includeSubscriptions by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    GlassCard(
        title = "WebDAV 云同步",
        subtitle = "手动合并收藏与历史。请填写自己服务器上备份文件的完整地址。"
    ) {
        SettingsField("备份文件地址", address, "https://example.com/dav/nextmovie.json", { address = it })
        Spacer(Modifier.height(12.dp))
        SettingsField("用户名", username, "可留空", { username = it })
        Spacer(Modifier.height(12.dp))
        SettingsField("密码", password, "仅用于本次设置会话", { password = it }, secret = true)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPill("包含订阅", selected = includeSubscriptions, glass = true, enabled = !syncing,
                onClick = { includeSubscriptions = !includeSubscriptions })
            TvPill(if (syncing) "同步中…" else "合并并同步", prominent = true,
                enabled = !syncing && address.isNotBlank(), onClick = {
                    syncing = true
                    scope.launch {
                        try {
                            repo.syncLibraryWebDav(address, username, password, includeSubscriptions)
                            result = "收藏与历史已同步"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: IllegalArgumentException) {
                            result = e.message ?: "云端备份格式不正确"
                        } catch (e: IllegalStateException) {
                            result = e.message ?: "同步失败"
                        } catch (_: Exception) {
                            result = "无法同步，检查网络或云端文件格式"
                        } finally {
                            syncing = false
                        }
                    }
                })
        }
        if (result.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(result, color = Tokens.TextDim, fontSize = 14.sp)
        }
    }
}

/* ---------------- 解码自检 ---------------- */

@Composable
private fun DecodeProbeBlock() {
    val video = remember { DecoderProbe.videoCapabilities() }
    val audio = remember { DecoderProbe.audioCapabilities() }
    val ffmpeg = remember { DecoderProbe.ffmpegReady() }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("解码自检", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "本机能力 · ${DecoderProbe.summary()}",
                    color = Tokens.TextFaint,
                    fontSize = 12.sp
                )
            }
            Text(
                if (ffmpeg) "FFmpeg 已就绪" else "无 FFmpeg 扩展",
                color = if (ffmpeg) Color(0xFF63C98A) else Color(0xFFD8915B),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        ProbeRow("视频", video)
        Spacer(Modifier.height(8.dp))
        ProbeRow("音频", audio)
        Spacer(Modifier.height(10.dp))
        Text(
            if (video.firstOrNull { it.label == "HEVC 10bit" }?.ok == false)
                "提示：本机没有 HEVC 10bit 解码，4K 10bit 片源会黑屏或花屏，请选 1080p 或换线路"
            else "提示：源站片源的编码/封装千差万别，播放失败时先看这份清单再换线路",
            color = Tokens.TextFaint,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ProbeRow(title: String, items: List<DecoderProbe.Capability>) {
    Column {
        Text(title, color = Tokens.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        // 两列排布，窄屏也能一眼看全
        items.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { cap ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cap.label,
                            color = Tokens.TextDim,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            cap.status,
                            color = when {
                                cap.hardware -> Color(0xFF63C98A)
                                cap.software -> Color(0xFFD8B45B)
                                else -> Color(0xFFD8705E)
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/* ---------------- 播放与体验 ---------------- */

@Composable
private fun PlaybackCard(repo: AppRepository) {
    val context = LocalContext.current
    var autoNext by remember { mutableStateOf(repo.autoNext) }
    var warmed by remember { mutableStateOf(false) }
    var decodeMode by remember { mutableStateOf(PlayerPrefs.decodeMode(context)) }
    var autoSoft by remember { mutableStateOf(PlayerPrefs.autoSoftOnDecodeError(context)) }
    var autoLine by remember { mutableStateOf(PlayerPrefs.autoLineOnError(context)) }
    GlassCard(
        title = "播放与体验",
        subtitle = "对齐 Marco：从播放器退回详情页，影片仍在详情页底层继续播放"
    ) {
        ToggleRow("自动连播下一集", "播完弹出 5 秒倒计时并自动进入下一集", autoNext) {
            autoNext = it
            repo.setAutoNext(it)
        }
        Spacer(Modifier.height(18.dp))
        HLine()
        Spacer(Modifier.height(16.dp))
        Text("解码模式", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            PlaybackSession.ffmpegNote(),
            color = Tokens.TextFaint,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DecodeMode.entries.forEach { mode ->
                val selected = decodeMode == mode
                TvPill(
                    text = mode.label,
                    glass = !selected,
                    onClick = {
                        decodeMode = mode
                        PlayerPrefs.setDecodeMode(context, mode)
                    }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        ToggleRow("解码失败自动切软解", "硬解失败时重建播放器并优先软解重试", autoSoft) {
            autoSoft = it
            PlayerPrefs.setAutoSoftOnDecodeError(context, it)
        }
        Spacer(Modifier.height(12.dp))
        ToggleRow("失败自动换线路", "IO/解码仍失败时自动切到下一条聚合线路", autoLine) {
            autoLine = it
            PlayerPrefs.setAutoLineOnError(context, it)
        }
        Spacer(Modifier.height(18.dp))
        HLine()
        Spacer(Modifier.height(16.dp))
        DecodeProbeBlock()
        Spacer(Modifier.height(18.dp))
        HLine()
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("源预热", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (warmed) "已在后台预热当前站点，点开详情更快" else "提前下载插件 jar 并跑 Init，减少详情等待",
                    color = Tokens.TextFaint,
                    fontSize = 13.sp
                )
            }
            TvPill(
                text = if (warmed) "已预热" else "立即预热",
                glass = true,
                enabled = !warmed,
                onClick = {
                    repo.prewarmCurrentSite("manual")
                    warmed = true
                }
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "线路聚合：详情页按连通性与分辨率排序多线路；播放器内可换源。格式支持 HLS / DASH / 直链。",
            color = Tokens.TextFaint,
            fontSize = 13.sp
        )
    }
}

/* ---------------- 数据与存储 ---------------- */

@Composable
private fun StorageCard(repo: AppRepository) {
    val scope = rememberCoroutineScope()
    var favCount by remember { mutableStateOf(repo.favorites.list().size) }
    var hisCount by remember { mutableStateOf(repo.history.list().size) }
    var cache by remember { mutableStateOf(repo.posterCacheBytes()) }
    GlassCard(
        title = "数据与存储",
        subtitle = "收藏与播放记录按片名去重，换线路不会重复记账"
    ) {
        StatRow("收藏", "$favCount 部", "清空收藏") {
            repo.favorites.clear()
            favCount = 0
        }
        Spacer(Modifier.height(12.dp))
        StatRow("播放记录", "$hisCount 条", "清空记录") {
            repo.history.clear()
            hisCount = 0
        }
        Spacer(Modifier.height(12.dp))
        StatRow("海报缓存", formatBytes(cache), "清空缓存") {
            repo.clearPosterCache()
            cache = repo.posterCacheBytes()
        }
        Spacer(Modifier.height(20.dp))
        HLine()
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("缓存与索引", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("清掉首页快照、详情与插件缓存，下次重新拉取", color = Tokens.TextFaint, fontSize = 13.sp)
            }
            TvPill("重建缓存", glass = true, onClick = { scope.launch { repo.invalidateContent() } })
        }
    }
}

/* ---------------- 版本与更新 ---------------- */

@Composable
private fun VersionCard(context: Context, repo: AppRepository) {
    val scope = rememberCoroutineScope()
    var snapshotFresh by remember { mutableStateOf<Boolean?>(null) }
    GlassCard(
        title = "关于",
        subtitle = "版本、开源说明与快捷键"
    ) {
        InfoRow("版本", versionLabel(context))
        Spacer(Modifier.height(12.dp))
        InfoRow(
            "订阅快照",
            when (snapshotFresh) {
                true -> "6 小时内，冷启动直接可用"
                false -> "已过期，下次启动会重新拉"
                null -> "检测中…"
            }
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPill("项目主页", prominent = true, onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ringas/NextMovie"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
            TvPill("刷新快照状态", glass = true, onClick = {
                scope.launch { snapshotFresh = repo.configSnapshotIsFresh() }
            })
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "NextMovie 只做 Android TV 播放器，不内置任何源、Key 或解析规则；GPL-3.0 开源。",
            color = Tokens.TextFaint,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(22.dp))
        Text("快捷键", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("遥控：方向移动 · OK 确认 · 返回键退出 · 菜单键打开线路/菜单", color = Tokens.TextFaint, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text("键盘：↑↓←→ · Enter/空格确认 · Esc 返回 · Tab 菜单 · 设置内 1–6 跳段", color = Tokens.TextFaint, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text("播放页：空格/媒体键暂停 · ←→ 快退快进 · 菜单切换线路", color = Tokens.TextFaint, fontSize = 13.sp)
    }
    LaunchedEffect(Unit) { snapshotFresh = repo.configSnapshotIsFresh() }
}

/* ---------------- 通用小部件 ---------------- */

@Composable
private fun SettingLabel(text: String) {
    Text(text, color = Tokens.TextDim, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ToggleRow(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(caption, color = Tokens.TextFaint, fontSize = 13.sp)
        }
        TvPill(
            text = if (checked) "开" else "关",
            selected = checked,
            glass = !checked,
            onClick = { onChange(!checked) }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(value, color = Tokens.TextFaint, fontSize = 13.sp)
        }
        TvPill(action, glass = true, onClick = onAction)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tokens.TextDim, fontSize = 14.5.sp, modifier = Modifier.widthIn(min = 120.dp))
        Text(value, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Tokens.Stroke)
    )
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction = ImeAction.Done,
    secret: Boolean = false,
    onDone: () -> Unit = {}
) {
    Text(label, color = Tokens.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(7.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = Tokens.TextGhost, fontSize = 14.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction,
            keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password
            else androidx.compose.ui.text.input.KeyboardType.Text),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0x12FFFFFF),
            unfocusedContainerColor = Color(0x0DFFFFFF),
            focusedIndicatorColor = Tokens.Accent.copy(alpha = 0.75f),
            unfocusedIndicatorColor = Tokens.Stroke,
            cursorColor = Tokens.Accent
        )
    )
}

private fun versionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v" + info.versionName
}.getOrDefault("v0.0.0")

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toFloat())
    bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / (1L shl 10).toFloat())
    else -> bytes.toString() + " B"
}

private fun dnsLabel(mode: String): String = when (mode) {
    "auto" -> "自动（抗污染）"
    "system" -> "跟随系统"
    "cloudflare" -> "Cloudflare"
    "aliyun" -> "阿里 DNS"
    "google" -> "Google DNS"
    "custom" -> "自定义"
    else -> mode
}

/** 兼容旧调用点（插件页沿用同一枚按钮样式） */
@Composable
fun SettingsButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TvPill(
        text = text,
        modifier = modifier,
        glass = true,
        enabled = enabled,
        horizontalPadding = 20.dp,
        verticalPadding = 12.dp,
        onClick = onClick
    )
}

/** 扫码快传弹窗的全局开关（首页与设置页共用同一实现） */
object Pairing {
    var show by mutableStateOf(false)
}

@Composable
private fun PairingHost() {
    if (Pairing.show) {
        PairingDialog(onDismiss = { Pairing.show = false }, onReceived = { Pairing.show = false })
    }
}
