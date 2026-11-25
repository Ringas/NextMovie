@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nextmovie.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.ui.common.StageBackdrop
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.LocalTvDisplay
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Group(val title: String, val caption: String) {
    Overview("总览", "常用项 · 设备 · 状态"),
    Sources("数据源", "配置 · 导入 · 站点"),
    Network("网络与 DNS", "解析 · 线路 · 测速"),
    Posters("海报与元信息", "TMDB · 缓存 · 补全"),
    Display("外观与交互", "大屏 · 字号 · 键位"),
    Maintenance("维护", "缓存 · 历史"),
    About("关于", "版本 · 开源")
}

/**
 * 设置页：左侧分组导航 + 中间行式设置 + 右侧上下文卡。
 *
 * 设置项不做二次确认，改动即时生效；危险项用红色单独标出。
 * 右侧卡只放「当前是什么状态」，避免每行都堆一句解释。
 */
@Composable
fun SettingsScreen(repo: AppRepository, onBack: () -> Unit, onConfigLoaded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val display = LocalTvDisplay.current
    var config by remember { mutableStateOf(repo.configUrl()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(Group.Overview) }
    var token by remember { mutableStateOf(repo.tmdb.token) }
    var mirror by remember { mutableStateOf(repo.tmdbMirrorEnabled()) }
    var images by remember { mutableStateOf(repo.tmdbImageProxyEnabled()) }
    var enhance by remember { mutableStateOf(repo.posterEnhance) }
    var race by remember { mutableStateOf(repo.tmdbRaceEnabled()) }
    var dnsCustom by remember { mutableStateOf(repo.dnsCustom()) }
    var dnsMode by remember { mutableStateOf(repo.dnsMode()) }
    var routeMode by remember { mutableStateOf(repo.tmdbRouteMode()) }
    var probe by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(repo.tmdbCustomBase()) }
    var cacheBytes by remember { mutableStateOf(repo.posterCacheBytes()) }
    val accountLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch { repo.invalidateContent(); message = "账户界面已关闭，已刷新内容缓存" }
    }
    val navFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { navFocus.requestFocus() } }

    fun load() {
        if (busy) return
        scope.launch {
            busy = true
            message = "正在读取配置…"
            try {
                val count = repo.loadConfig(config.trim())
                message = "已导入 $count 个来源"
                onConfigLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "导入失败，请检查地址"
            } finally {
                busy = false
            }
        }
    }

    if (pairing) {
        PairingDialog(
            onDismiss = { pairing = false },
            onReceived = {
                config = it
                pairing = false
                message = "已从手机接收配置，按「加载配置」完成导入"
            }
        )
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val versionCode = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        }.getOrNull() ?: 0
    }

    StageBackdrop {
    Column(modifier = Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 6.dp, bottom = 4.dp)) {
        SettingsTopBar(onBack = onBack)
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = "设置",
                color = Tokens.Text,
                fontSize = Tokens.Type.display,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = group.title,
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    color = Tokens.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp).padding(bottom = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // —— 左：分组导航 ——
            LazyColumn(
                modifier = Modifier.width(176.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(Group.values().toList()) { index, item ->
                    GroupNavItem(
                        index = index + 1,
                        group = item,
                        active = group == item,
                        modifier = if (index == 0) Modifier.focusRequester(navFocus) else Modifier,
                        onClick = { group = item; message = "" }
                    )
                }
            }
            Spacer(Modifier.width(14.dp))

            // —— 中：行式设置 ——
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (group) {
                    Group.Overview -> OverviewGroup(
                        repo, display, dnsMode, message, versionName,
                        onGo = { group = it }
                    )
                    Group.Sources -> SourcesGroup(
                        repo, config, { config = it }, busy, message,
                        onLoad = ::load,
                        onPair = { pairing = true },
                        onAccount = { site -> accountLauncher.launch(com.nextmovie.PluginActivity.intent(context, site)) },
                        onSitePicked = { repo.selectSite(it.key); onConfigLoaded() },
                        onGo = { group = it }
                    )
                    Group.Network -> NetworkGroup(
                        repo, dnsMode, { dnsMode = it }, dnsCustom, { dnsCustom = it },
                        routeMode, { routeMode = it }, race, { race = it },
                        probe, { probe = it }, message = { message = it }
                    )
                    Group.Posters -> PostersGroup(
                        repo, token, { token = it }, custom, { custom = it },
                        mirror, { mirror = it }, images, { images = it },
                        enhance, { enhance = it }, cacheBytes, { cacheBytes = it },
                        message = { message = it }
                    )
                    Group.Display -> DisplayGroup(display, versionName)
                    Group.Maintenance -> MaintenanceGroup(repo, cacheBytes, { cacheBytes = it }, message = { message = it })
                    Group.About -> AboutGroup(versionName, versionCode)
                }
            }
            Spacer(Modifier.width(14.dp))

            // —— 右：上下文卡 ——
            ContextCard(
                repo = repo,
                display = display,
                message = message,
                versionName = versionName,
                cacheBytes = cacheBytes
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "App 不内置任何站点与 Key · 配置只从你给的地址或文件导入",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "GPL-3.0 · 零内置源",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "‹ 首页",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.Corner.pill))
                .background(Color(0x12FFFFFF))
                .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                .tvClickable(onClick = onBack, corner = Tokens.Corner.pill)
                .padding(horizontal = 18.dp, vertical = 9.dp)
        )

        Spacer(Modifier.weight(1f))
        Text(
            text = "更改即时生效 · 无需确认",
            color = Tokens.TextFaint,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun GroupNavItem(
    index: Int,
    group: Group,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(if (active) Tokens.AccentDim.copy(alpha = 0.18f) else Color.Transparent, label = "navBg")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Corner.card))
            .background(bg)
            .tvClickable(onClick = onClick, corner = Tokens.Corner.card)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(22.dp)
                .background(if (active) Tokens.Accent else Color.Transparent)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%02d".format(index),
                    color = if (active) Tokens.Accent else Tokens.TextFaint,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro, letterSpacing = 0.08.sp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = group.title,
                    color = if (active) Tokens.Text else Tokens.TextDim,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                text = group.caption,
                color = if (active) Tokens.TextDim else Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro),
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp, start = 1.dp)
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, caption: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 2.dp)) {
        Box(Modifier.size(width = 2.dp, height = 12.dp).background(Tokens.Accent))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.badge, letterSpacing = Tokens.Type.MICRO_TRACK.sp)
        )
        if (caption != null) {
            Spacer(Modifier.width(8.dp))
            Text(caption, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro))
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 一行设置：标题 + 一行说明，右侧放控件。 */
@Composable
private fun SettingRow(
    title: String,
    desc: String? = null,
    last: Boolean = false,
    control: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
                if (desc != null) {
                    Text(
                        text = desc,
                        color = Tokens.TextFaint,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            control()
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Stroke))
    }
}

@Composable
private fun ValueText(text: String, color: Color = Tokens.TextDim, wide: Boolean = false) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = if (wide) 210.dp else 160.dp)
    )
}

@Composable
private fun Pill(
    text: String,
    active: Boolean = false,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    val bg = when {
        primary -> Tokens.Accent
        danger -> Tokens.Danger.copy(alpha = 0.14f)
        active -> Tokens.AccentDim
        else -> Tokens.Surface
    }
    val fg = when {
        !enabled -> Tokens.TextFaint
        primary -> Tokens.OnAccent
        danger -> Tokens.Danger
        active -> Tokens.Accent
        else -> Tokens.TextDim
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(
                width = 1.dp,
                color = when {
                    primary -> Color.Transparent
                    danger -> Tokens.Danger.copy(alpha = 0.5f)
                    active -> Tokens.Accent.copy(alpha = 0.5f)
                    else -> Tokens.Stroke
                },
                shape = shape
            )
            .tvClickable(enabled = enabled, onClick = onClick, corner = Tokens.Corner.pill)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/** 分段选择：一眼能看出「现在选的是哪个」。 */
@Composable
private fun Segment(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            Pill(text = label, active = value == selected, onClick = { onSelect(value) })
        }
    }
}

@Composable
private fun TvToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(shape)
            .background(if (checked) Tokens.Accent.copy(alpha = 0.9f) else Tokens.SurfaceHigh)
            .border(1.dp, if (checked) Tokens.Accent else Tokens.Stroke, shape)
            .tvClickable(onClick = { onChange(!checked) }, corner = Tokens.Corner.pill)
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 3.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(Tokens.Corner.pill))
                .background(if (checked) Tokens.OnAccent else Tokens.TextDim)
        )
    }
}

@Composable
private fun TvField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.Corner.card)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            text = label,
            color = Tokens.TextFaint,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro)
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Tokens.Surface)
                .border(if (focused) 2.dp else 1.dp, if (focused) Tokens.Accent else Tokens.Stroke, shape)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (value.isEmpty()) {
                Text("留空表示不使用", color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = Tokens.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(Tokens.Accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }
            )
        }
    }
}

@Composable
private fun OverviewGroup(
    repo: AppRepository,
    display: com.nextmovie.ui.theme.TvDisplayState,
    dnsMode: String,
    message: String,
    versionName: String,
    onGo: (Group) -> Unit
) {
    val site = repo.currentSite
    val status = repo.configStatus()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("常用项", "按遥控器方向键逐行调整") }
        item {
            SettingRow("当前数据源", site?.name ?: "未导入", control = {
                Pill("切换", onClick = { onGo(Group.Sources) })
            })
        }
        item {
            SettingRow(
                "配置地址",
                repo.configUrl().ifBlank { "还没有导入配置" },
                control = { Pill("管理", onClick = { onGo(Group.Sources) }) }
            )
        }
        item {
            SettingRow(
                "站点 / 插件",
                "${repo.sites.size} 个站点 · ${repo.sites.count { it.isSpider }} 个插件",
                control = { Pill("查看", onClick = { onGo(Group.Sources) }) }
            )
        }
        item { GroupHeader("大屏与解析") }
        item {
            SettingRow(
                "大屏显示",
                "整套排版等比放大，不改布局",
                control = {
                    Segment(
                        listOf("1.0" to "标准", "1.125" to "大号", "1.25" to "超大"),
                        display.scale.toString(),
                        { display.select(it.toFloat()) }
                    )
                }
            )
        }
        item {
            SettingRow(
                "DNS 解析",
                dnsLabel(dnsMode),
                control = { Pill("调整", onClick = { onGo(Group.Network) }) }
            )
        }
        item {
            SettingRow(
                "配置状态",
                status?.text ?: "尚未记录",
                last = true,
                control = {
                    Text(
                        text = if (status?.ok == true) "正常" else "待检查",
                        color = if (status?.ok == true) Tokens.Accent else Tokens.TextFaint,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            InfoCard(
                title = "零内置源",
                body = "本应用不附带任何站点、插件或 API Key。所有内容来自你导入的配置，卸载后本地缓存一并清除。",
                accent = "v$versionName"
            )
        }
        if (message.isNotBlank()) item {
            Spacer(Modifier.height(10.dp))
            Text(message, color = Tokens.Accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SourcesGroup(
    repo: AppRepository,
    config: String,
    onConfig: (String) -> Unit,
    busy: Boolean,
    message: String,
    onLoad: () -> Unit,
    onPair: () -> Unit,
    onAccount: (com.nextmovie.core.config.Site) -> Unit,
    onSitePicked: (com.nextmovie.core.config.Site) -> Unit,
    onGo: (Group) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("配置", "支持 JSON / JSONC · 会校验 md5") }
        item { TvField("配置网址或 JSON", config, onValueChange = onConfig) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Pill(if (busy) "加载中…" else "加载配置", primary = true, enabled = !busy, onClick = onLoad)
                Pill("手机扫码导入", onClick = onPair)
                Pill("网络设置", onClick = { onGo(Group.Network) })
            }
        }
        item { GroupHeader("来源", "选中即切换，返回首页生效") }
        items(repo.sites, key = { it.runtimeKey }) { site ->
            val current = repo.currentSite?.runtimeKey == site.runtimeKey
            SettingRow(
                title = site.name,
                desc = buildString {
                    append(if (site.isSpider) "插件源" else "CMS")
                    append(if (site.isPlayable) " · 可播" else " · 仅列表")
                    if (site.searchable) append(" · 可搜索")
                },
                control = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (current) ValueText("当前", Tokens.Accent)
                        if (site.isSpider && site.isPlayable) Pill("账户 / 网盘", onClick = { onAccount(site) })
                        Pill(if (current) "已选" else "切换", active = current, onClick = { onSitePicked(site) })
                    }
                }
            )
        }
        item {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "网盘登录在来源自己的账户界面完成；本应用不保存任何账号。",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun NetworkGroup(
    repo: AppRepository,
    dnsMode: String,
    onDnsMode: (String) -> Unit,
    dnsCustom: String,
    onDnsCustom: (String) -> Unit,
    routeMode: String,
    onRouteMode: (String) -> Unit,
    race: Boolean,
    onRace: (Boolean) -> Unit,
    probe: String,
    onProbe: (String) -> Unit,
    message: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("DNS", "解析不通时优先换这里") }
        item {
            SettingRow("解析模式", dnsLabel(dnsMode), control = {
                Segment(
                    listOf("auto" to "自动", "system" to "系统"),
                    dnsMode,
                    { onDnsMode(it); repo.setDns(it); message("已切换 ${dnsLabel(it)}") }
                )
            })
        }
        item {
            SettingRow("公共 DoH", "自动模式下的候选上游", control = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("cloudflare" to "CF", "google" to "Google", "aliyun" to "阿里").forEach { (v, label) ->
                        Pill(label, active = dnsMode == v, onClick = {
                            onDnsMode(v); repo.setDns(v); message("已切换 $label DNS")
                        })
                    }
                }
            })
        }
        item { TvField("自定义 DoH 模板（含 %s）", dnsCustom, onValueChange = onDnsCustom) }
        item {
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                Pill("保存自定义 DNS", primary = true, onClick = {
                    repo.setDns("custom", dnsCustom)
                    onDnsMode("custom")
                    message("已保存自定义 DNS")
                })
            }
        }
        item { GroupHeader("TMDB 线路", "海报与评分的取数通道") }
        item {
            SettingRow("线路选择", "无 Token 时自动跳过", control = {
                Segment(
                    listOf("auto" to "自动", "global" to "官方", "fast" to "备用"),
                    routeMode,
                    { onRouteMode(it); repo.setTmdbRoute(it); message("已切换线路：$it") }
                )
            })
        }
        item {
            SettingRow("多线路竞速", "启动时并发测速，取最快", control = {
                TvToggle(race, { onRace(it); repo.setTmdbRace(it); message("竞速已${if (it) "开启" else "关闭"}") })
            })
        }
        item {
            SettingRow("线路测速", probe.ifBlank { "还没测过" }, last = true, control = {
                Pill("开始测速", onClick = {
                    scope.launch {
                        onProbe("正在测速…")
                        onProbe(runCatching { repo.probeTmdb().joinToString("  ·  ") { it.first + " " + it.third } }.getOrDefault("测速失败"))
                    }
                })
            })
        }
        if (probe.isNotBlank()) item {
            Spacer(Modifier.height(10.dp))
            Text(probe, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall, maxLines = 3)
        }
    }
}

@Composable
private fun PostersGroup(
    repo: AppRepository,
    token: String,
    onToken: (String) -> Unit,
    custom: String,
    onCustom: (String) -> Unit,
    mirror: Boolean,
    onMirror: (Boolean) -> Unit,
    images: Boolean,
    onImages: (Boolean) -> Unit,
    enhance: Boolean,
    onEnhance: (Boolean) -> Unit,
    cacheBytes: Long,
    onCache: (Long) -> Unit,
    message: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("TMDB 凭据", "留空则只用源站海报") }
        item { TvField("TMDB Token / API Key", token, onValueChange = onToken) }
        item {
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                Pill("保存 Token", primary = true, onClick = {
                    repo.configureTmdb(token)
                    message("Token 已保存")
                })
            }
        }
        item { TvField("自定义镜像，每行一个", custom, singleLine = false, onValueChange = onCustom) }
        item {
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                Pill("使用自定义镜像", onClick = {
                    repo.setTmdbRoute("custom", custom)
                    message("已保存镜像")
                })
            }
        }
        item { GroupHeader("取图策略", "源站为主，TMDB 只做增强") }
        item {
            SettingRow("公共反代", "官方域名不稳时走公共镜像", control = {
                TvToggle(mirror, { onMirror(it); repo.setTmdbMirror(it) })
            })
        }
        item {
            SettingRow("图片中转", "海报经本机代理拉取，绕开防盗链", control = {
                TvToggle(images, { onImages(it); repo.setTmdbImageProxy(it) })
            })
        }
        item {
            SettingRow("海报补全", "缺图时用 TMDB / 剧照补齐", last = true, control = {
                TvToggle(enhance, { onEnhance(it); repo.setPosterEnhance(it) })
            })
        }
        item { GroupHeader("缓存") }
        item {
            SettingRow("海报缓存", formatBytes(cacheBytes), last = true, control = {
                Pill("清理", onClick = {
                    repo.clearPosterCache()
                    onCache(repo.posterCacheBytes())
                    message("已清理海报缓存")
                })
            })
        }
    }
}

@Composable
private fun DisplayGroup(display: com.nextmovie.ui.theme.TvDisplayState, versionName: String) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("大屏", "整套排版等比放大，布局不变") }
        item {
            SettingRow("显示比例", "标准 100% / 大号 112.5% / 超大 125%", last = true, control = {
                Segment(
                    listOf("1.0" to "标准", "1.125" to "大号", "1.25" to "超大"),
                    display.scale.toString(),
                    { display.select(it.toFloat()) }
                )
            })
        }
        item { GroupHeader("设备") }
        item {
            SettingRow("分辨率", "${configuration.screenWidthDp} × ${configuration.screenHeightDp} dp", last = true, control = {
                ValueText("density %.2f".format(density.density))
            })
        }
        item { GroupHeader("键位") }
        item {
            SettingRow("返回 / 取消", "Esc 或鼠标右键", last = true, control = { ValueText("全局", Tokens.TextFaint) })
        }
        item {
            SettingRow("菜单键 / 长按 OK", "直接打开设置", last = true, control = { ValueText("首页", Tokens.TextFaint) })
        }
        item {
            SettingRow("播放器", "确认暂停 · 左右快进退 · 上下开控制栏", last = true, control = { ValueText("v$versionName", Tokens.TextFaint) })
        }
    }
}

@Composable
private fun MaintenanceGroup(
    repo: AppRepository,
    cacheBytes: Long,
    onCache: (Long) -> Unit,
    message: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("缓存与记录", "只影响本机，不动远端配置") }
        item {
            SettingRow("海报缓存", formatBytes(cacheBytes), control = {
                Pill("清理", onClick = {
                    repo.clearPosterCache(); onCache(repo.posterCacheBytes()); message("已清理海报缓存")
                })
            })
        }
        item {
            SettingRow("观看历史", "首页「继续观看」与进度都来自这里", last = true, control = {
                Pill("清空", danger = true, onClick = {
                    repo.history.clear(); message("已清空观看历史")
                })
            })
        }
        item { GroupHeader("诊断") }
        item {
            SettingRow("配置状态", repo.configStatus()?.text ?: "尚未记录", last = true, control = {
                ValueText(if (repo.configStatus()?.ok == true) "正常" else "待检查", Tokens.TextFaint)
            })
        }
    }
}

@Composable
private fun AboutGroup(versionName: String, versionCode: Int) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
        item { GroupHeader("版本") }
        item {
            SettingRow("当前版本", "versionCode $versionCode", control = { ValueText("v$versionName") })
        }
        item {
            SettingRow("更新通道", "跟随仓库 Release 手动更新", last = true, control = { ValueText("手动", Tokens.TextFaint) })
        }
        item { GroupHeader("开源") }
        item {
            SettingRow("许可证", "允许自建分发，需保留同样义务", control = { ValueText("GPL-3.0") })
        }
        item {
            SettingRow("项目地址", "github.com/Ringas/NextMovie", last = true, control = { ValueText("外部", Tokens.TextFaint) })
        }
        item { GroupHeader("原则") }
        item {
            SettingRow("内置源与 Key", "不附带、不代下载、不代理账号", last = true, control = { ValueText("0", Tokens.Accent) })
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, accent: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Corner.panel))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.panel))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Tokens.Accent, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            if (accent != null) Text(accent, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, color = Tokens.TextDim, style = MaterialTheme.typography.labelSmall, lineHeight = 20.sp)
    }
}

@Composable
private fun ContextCard(
    repo: AppRepository,
    display: com.nextmovie.ui.theme.TvDisplayState,
    message: String,
    versionName: String,
    cacheBytes: Long
) {
    val site = repo.currentSite
    Column(
        modifier = Modifier
            .width(226.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Corner.panel))
                .background(Tokens.Surface)
                .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.panel))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = "运行状态",
                color = Tokens.Accent,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.sp)
            )
            Spacer(Modifier.height(8.dp))
            CtxLine("当前来源", site?.name ?: "未选择")
            CtxLine("站点", "${repo.sites.size} 个")
            CtxLine("插件源", "${repo.sites.count { it.isSpider }} 个")
            CtxLine("可搜索", "${repo.sites.count { it.searchable }} 个")
            CtxLine("显示比例", "%.2f×".format(display.scale))
            CtxLine("版本", "v$versionName")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Stroke))
            Spacer(Modifier.height(8.dp))
            Text("配置", color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro))
            Spacer(Modifier.height(4.dp))
            Text(
                text = repo.configStatus()?.text ?: "尚未记录",
                color = if (repo.configStatus()?.ok == true) Tokens.TextDim else Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Corner.panel))
                .background(Tokens.Surface)
                .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.panel))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("最近操作", color = Tokens.Accent, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = message.ifBlank { "暂无操作。改动会即时写入本机。" },
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Stroke))
            Spacer(Modifier.height(10.dp))
            Text("海报缓存", color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro))
            Spacer(Modifier.height(3.dp))
            Text(formatBytes(cacheBytes), color = Tokens.TextDim, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "设置不弹二次确认 · 危险操作单独标红",
            color = Tokens.TextFaint,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro),
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun CtxLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro))
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp)
        )
    }
}

private fun dnsLabel(mode: String): String = when (mode) {
    "system" -> "系统 DNS"
    "cloudflare" -> "Cloudflare"
    "google" -> "Google"
    "aliyun" -> "阿里 DNS"
    "custom" -> "自定义 DoH"
    else -> "自动（SmartDns）"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "空"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 兼容旧调用（播放器控制栏 / 其它页面仍在使用）。 */
@Composable
fun SettingsButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Pill(text = text, enabled = enabled, modifier = modifier, onClick = onClick)
}

@Composable
fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit) {
    TvField(label, value, onValueChange = onValueChange)
}
