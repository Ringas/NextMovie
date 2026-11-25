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
import com.nextmovie.core.config.Site
import com.nextmovie.ui.common.AmbientBackdrop
import com.nextmovie.ui.common.BrandLogo
import com.nextmovie.ui.common.GlassCard
import com.nextmovie.ui.common.TvPill
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
    Appearance("外观与柔光", "主题、强度、海报取色", Icons.Filled.Star),
    Sources("订阅与片源", "订阅地址、站点、加载状态", Icons.Filled.List),
    Posters("海报与图片", "TMDB、增强、缓存", Icons.Filled.Search),
    Network("网络与解析", "DNS 策略与线路", Icons.Filled.Home),
    Playback("播放与体验", "连播、预热、返回行为", Icons.Filled.Settings),
    Storage("数据与存储", "收藏、历史、清理", Icons.Filled.List),
    Version("版本与更新", "版本号、项目主页", Icons.Filled.Info)
}

@Composable
fun SettingsScreen(repo: AppRepository, onBack: () -> Unit, onConfigLoaded: () -> Unit) {
    var section by remember { mutableStateOf(Section.Appearance) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop()
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(328.dp)
                    .fillMaxSize()
                    .padding(start = 56.dp, top = 44.dp, bottom = 40.dp, end = 24.dp)
            ) {
                Text("系统设置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("全部设置项都在这一屏", color = Tokens.TextFaint, fontSize = 13.5.sp)
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
                            Icon(
                                item.icon,
                                null,
                                tint = if (section == item) Color.White else Tokens.TextDim,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.title,
                                color = if (section == item) Color.White else Tokens.TextDim,
                                fontSize = 17.sp,
                                fontWeight = if (section == item) FontWeight.SemiBold else FontWeight.Medium
                            )
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
                        Section.Appearance -> AppearanceCard()
                        Section.Sources -> SourcesCard(repo, onConfigLoaded)
                        Section.Posters -> PostersCard(repo)
                        Section.Network -> NetworkCard(repo)
                        Section.Playback -> PlaybackCard(repo)
                        Section.Storage -> StorageCard(repo)
                        Section.Version -> VersionCard(context, repo)
                    }
                    Spacer(Modifier.height(18.dp))
                    TvPill("‹ 返回首页", glass = true, onClick = onBack)
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

    fun load() {
        if (busy) return
        busy = true
        message = "加载中…"
        scope.launch {
            val count = runCatching { repo.loadConfig(url) }.getOrElse { -1 }
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

/* ---------------- 播放与体验 ---------------- */

@Composable
private fun PlaybackCard(repo: AppRepository) {
    var autoNext by remember { mutableStateOf(repo.autoNext) }
    var warmed by remember { mutableStateOf(false) }
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
            "线路聚合：详情页会按连通性、分辨率把同一影片的多条线路排序，播放器内可随时换源。",
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
        title = "版本与更新",
        subtitle = "开源、零内置源：订阅地址与 Key 都由你自己填"
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
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
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
