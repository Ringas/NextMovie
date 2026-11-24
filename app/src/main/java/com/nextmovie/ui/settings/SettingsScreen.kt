package com.nextmovie.ui.settings

import com.nextmovie.ui.common.mouseWheel
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.HealthReport
import com.nextmovie.core.config.SiteHealth
import com.nextmovie.core.config.SiteKind
import com.nextmovie.core.history.HistoryStore
import com.nextmovie.core.net.SmartDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * v0 设置页：导入配置地址 + TMDB Token + 站点选择。
 * 二维码/扫码导入、备份还原留到 P1。
 */
@Composable
fun SettingsScreen(
    repo: AppRepository,
    onBack: () -> Unit,
    onConfigLoaded: () -> Unit
) {
    var configUrl by remember { mutableStateOf(repo.configUrl()) }
    var tmdbToken by remember { mutableStateOf(repo.tmdb.token) }
    var status by remember { mutableStateOf("") }
    var configMsg by remember { mutableStateOf("") }
    var configOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sites by remember { mutableStateOf(repo.sites) }
    val toastContext = LocalContext.current
    var posterEnhance by remember { mutableStateOf(repo.posterEnhance) }
    var historyCount by remember { mutableStateOf(repo.history.list().size) }
    var tmdbRoute by remember { mutableStateOf(repo.tmdbRouteMode()) }
    var tmdbCustom by remember { mutableStateOf(repo.tmdbCustomBase()) }
    var tmdbRace by remember { mutableStateOf(repo.tmdbRaceEnabled()) }
    var tmdbImgProxy by remember { mutableStateOf(repo.tmdbImageProxyEnabled()) }
    var tmdbMirror by remember { mutableStateOf(repo.tmdbMirrorEnabled()) }
    var probeText by remember { mutableStateOf("") }
    var dnsMode by remember { mutableStateOf(repo.dnsMode()) }
    var dnsCustom by remember { mutableStateOf(repo.dnsCustom()) }
    var dnsProbe by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .verticalScroll(scrollState)
            .mouseWheel(scrollState)
            .padding(Tokens.ScreenPadding)
    ) {
        Text(
            text = "‹ 返回",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { onBack() }.padding(vertical = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("设置", color = Tokens.Text, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Field(
            label = "数据源配置地址（JSON / 多仓）",
            value = configUrl,
            onValueChange = { configUrl = it }
        )
        Spacer(Modifier.height(14.dp))
        // 抽成闭包：按钮、失败后的「重试」都复用它，保证两条入口的行为完全一致
        val performImport: () -> Unit = {
            if (configUrl.isBlank()) {
                configOk = false
                configMsg = "请先填入配置地址（可粘贴 JSON 地址或多仓地址）"
                Toast.makeText(toastContext, "请先填入配置地址", Toast.LENGTH_SHORT).show()
            } else {
                busy = true
                configOk = null
                configMsg = "正在拉取并解析配置…插件源首次加载通常要 10–40 秒"
                scope.launch {
                    runCatching { repo.loadConfig(configUrl.trim()) }
                        .onSuccess { count ->
                            sites = repo.sites
                            configOk = true
                            configMsg = "✓ 导入成功：已加载 $count 个站点｜当前站点：${repo.currentSite?.name ?: "未选择"}"
                            Toast.makeText(toastContext, "导入成功：$count 个站点", Toast.LENGTH_LONG).show()
                            onConfigLoaded()
                        }
                        .onFailure {
                            configOk = false
                            configMsg = "✗ 导入失败：" + (it.message ?: it.javaClass.simpleName) +
                                "\n可检查地址是否可访问、源是否换了域名，或换一个站点再试。"
                            Toast.makeText(
                                toastContext,
                                "导入失败：" + (it.message ?: it.javaClass.simpleName).take(60),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    busy = false
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(text = if (busy) "加载中…" else "加载配置", enabled = !busy) { performImport() }
            Spacer(Modifier.width(12.dp))
            ActionButton(text = "保存 TMDB Token", enabled = !busy) {
                repo.configureTmdb(tmdbToken)
                status = "TMDB Token 已保存"
            }
        }

        if (configMsg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            ConfigBanner(text = configMsg, ok = configOk)
            if (configOk == false && !busy) {
                Text(
                    text = "重试",
                    color = Tokens.Accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable { performImport() }
                        .padding(top = 10.dp, bottom = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Field(
            label = "TMDB Read Access Token（可留空）",
            value = tmdbToken,
            onValueChange = { tmdbToken = it }
        )

        Spacer(Modifier.height(22.dp))
        Text("TMDB 加速线路", color = Tokens.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "内置：官方备用域 api.tmdb.org（境内可直连）+ 官方主域 + 公共反代；启动自动测速排序，开启竞速后并发请求先到先用。真机在哪个网络最快就用哪条。",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))
        ChoiceRow("自动：测速排序 + 竞速", tmdbRoute == "auto") {
            tmdbRoute = "auto"; repo.setTmdbRoute("auto", tmdbCustom); status = "TMDB 线路：自动"
        }
        ChoiceRow("仅官方备用域 api.tmdb.org", tmdbRoute == "fast") {
            tmdbRoute = "fast"; repo.setTmdbRoute("fast", tmdbCustom); status = "TMDB 线路：备用域"
        }
        ChoiceRow("仅主域 api.themoviedb.org", tmdbRoute == "global") {
            tmdbRoute = "global"; repo.setTmdbRoute("global", tmdbCustom); status = "TMDB 线路：主域"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.Surface)
                .clickable {
                    tmdbMirror = !tmdbMirror
                    repo.setTmdbMirror(tmdbMirror)
                    status = if (tmdbMirror) "已启用公共反代线路" else "已关闭公共反代线路"
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("公共反代 tmdb.orzabc.com", color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "第三方服务，自带 Key：没有 Token 也能出数据；介意可关闭",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                if (tmdbMirror) "已启用" else "已关闭",
                color = if (tmdbMirror) Tokens.Accent else Tokens.TextDim,
                style = MaterialTheme.typography.labelLarge
            )
        }
        ChoiceRow("自定义镜像 / 反代（可多行）", tmdbRoute == "custom") {
            tmdbRoute = "custom"; repo.setTmdbRoute("custom", tmdbCustom); status = "TMDB 线路：自定义"
        }
        if (tmdbRoute == "custom") {
            Spacer(Modifier.height(10.dp))
            Field(
                label = "镜像地址，每行一个（需包含 /3，例如 https://tmdb.example.com/3）",
                value = tmdbCustom,
                onValueChange = { tmdbCustom = it; repo.setTmdbRoute("custom", it) }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(text = "测速线路", enabled = !busy) {
                busy = true
                scope.launch {
                    val results: List<Triple<String, Boolean, String>> =
                        runCatching { repo.probeTmdb() }.getOrElse { emptyList() }
                    probeText = if (results.isEmpty()) "没有可用线路" else results.joinToString("\n") {
                        (if (it.second) "✓ " else "✗ ") + it.first + " · " + it.third
                    }
                    busy = false
                }
            }
            Spacer(Modifier.width(12.dp))
            ActionButton(text = if (tmdbRace) "并发竞速：开" else "并发竞速：关") {
                tmdbRace = !tmdbRace
                repo.setTmdbRace(tmdbRace)
                status = if (tmdbRace) "已开启多线路竞速" else "已关闭竞速（按测速顺序串行）"
            }
        }
        if (probeText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(probeText, color = Tokens.TextDim, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(22.dp))
        Text("海报增强", color = Tokens.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.Surface)
                .clickable {
                    tmdbImgProxy = !tmdbImgProxy
                    repo.setTmdbImageProxy(tmdbImgProxy)
                    status = if (tmdbImgProxy) "TMDB 海报改走图片 CDN" else "TMDB 海报直连 image.tmdb.org"
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TMDB 图片走 CDN 加速", color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "通过 wsrv.nl 中转并压缩，image.tmdb.org 被墙时再打开",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                if (tmdbImgProxy) "已开启" else "已关闭",
                color = if (tmdbImgProxy) Tokens.Accent else Tokens.TextDim,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.Surface)
                .clickable {
                    val next = !posterEnhance
                    posterEnhance = next
                    repo.setPosterEnhance(next)
                    status = if (next) "已开启 B站补图" else "已关闭 B站补图"
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("B站 PGC 兜底补图", color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "无需 Key；标题+年份校验通过才替换，失败保持源图",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                if (posterEnhance) "已开启" else "已关闭",
                color = if (posterEnhance) Tokens.Accent else Tokens.TextDim,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.height(10.dp))
        ActionButton(text = "清空海报缓存", enabled = !busy) {
            repo.clearPosterCache()
            status = "海报缓存已清空"
        }

        Spacer(Modifier.height(22.dp))
        Text("DNS 解析", color = Tokens.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "盒子常被路由器 DNS 投毒：源站域名被解析成境外 IP，连接必然 12 秒超时。\n" +
                "「自动抗污染」只在系统解析可疑时才走 DoH，正常网络不多花时间。",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))
        val dnsOptions = listOf(
            SmartDns.MODE_AUTO to "自动抗污染",
            SmartDns.MODE_SYSTEM to "仅系统 DNS",
            SmartDns.MODE_CLOUDFLARE to "Cloudflare",
            SmartDns.MODE_GOOGLE to "Google",
            SmartDns.MODE_ALIYUN to "阿里",
            SmartDns.MODE_CUSTOM to "自定义"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dnsOptions.forEach { (mode, label) ->
                val active = dnsMode == mode
                Text(
                    text = label,
                    color = if (active) Tokens.Accent else Tokens.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (active) Tokens.AccentDim else Tokens.Surface)
                        .clickable {
                            dnsMode = mode
                            repo.setDns(mode)
                            dnsProbe = ""
                            status = "DNS 已切换：$label"
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        if (dnsMode == SmartDns.MODE_CUSTOM) {
            Spacer(Modifier.height(10.dp))
            Field(
                label = "自定义 DoH 模板（必须含 %s，如 https://dns.alidns.com/resolve?name=%s&type=A）",
                value = dnsCustom,
                onValueChange = { dnsCustom = it; repo.setDns(SmartDns.MODE_CUSTOM, it) }
            )
        }
        Spacer(Modifier.height(10.dp))
        ActionButton(text = "解析自检（当前站点域名）", enabled = !busy) {
            val host = runCatching { java.net.URI(repo.currentSite?.api.orEmpty()).host }.getOrNull()
                ?: runCatching { java.net.URI(repo.configUrl()).host }.getOrNull()
                ?: "caiji.dyttzyapi.com"
            scope.launch {
                dnsProbe = "正在解析 $host …"
                dnsProbe = withContext(Dispatchers.IO) { SmartDns.diagnose(host) }
            }
        }
        if (dnsProbe.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                dnsProbe,
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(22.dp))
        Text("观看历史", color = Tokens.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "本机已记录 $historyCount 条（上限 ${HistoryStore.MAX_ENTRIES}），看过 30 秒以上会出现在首页「继续观看」",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        ActionButton(text = "清空观看历史", enabled = historyCount > 0 && !busy) {
            repo.history.clear()
            historyCount = 0
            status = "观看历史已清空"
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(status, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
        }

        if (sites.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            HealthPanel(
                report = remember(sites) { SiteHealth.audit(sites) },
                abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            )
            Spacer(Modifier.height(26.dp))
            Text("站点", color = Tokens.Text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            sites.take(60).forEach { site ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (site == repo.currentSite) Tokens.SurfaceHigh else Tokens.Surface)
                        .clickable {
                            repo.selectSite(site.key)
                            status = "已切换到 ${site.name}"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = site.name,
                        color = Tokens.Text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = SiteHealth.shortTag(site),
                            color = kindColor(SiteHealth.kindOf(site)),
                            style = MaterialTheme.typography.labelSmall
                        )
                        val reason = site.unavailableReason
                        if (reason != null) {
                            Text(
                                text = reason,
                                color = Tokens.Accent,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "本应用不提供影视资源与配置地址，请使用你有权访问的数据源。",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun HealthPanel(report: HealthReport, abi: String = "") {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val diag = remember(ctx) { com.nextmovie.core.spider.PluginDiag.read(ctx) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Surface)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("源体检", color = Tokens.Text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                "共 ${report.total} 个站点" + if (abi.isNotBlank()) " · 本机 ABI $abi" else "",
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.weight(1f))
            Text(
                "可播 ${report.usablePercent}%",
                color = if (report.ready > 0) Tokens.Accent else Tokens.TextDim,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell("可直接用", report.ready, SiteKind.READY, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            StatCell("插件源", report.plugin, SiteKind.PLUGIN, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            StatCell("JS 源", report.js, SiteKind.JS, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            StatCell("不支持", report.unsupported, SiteKind.UNSUPPORTED, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        // 比例条：金色 = 当前能直接打开的部分
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Tokens.SurfaceHigh)
        ) {
            if (report.total > 0 && report.ready > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(report.ready.toFloat() / report.total)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Tokens.Accent)
                )
            }
        }

        val initInfo = remember(ctx) { com.nextmovie.core.spider.PluginDiag.readInit(ctx) }
        if (initInfo.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "插件初始化：" + initInfo,
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 6
            )
        }

        if (diag.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "上次插件加载：" + diag,
                color = if (diag.startsWith("✓")) Tokens.TextDim else Tokens.Accent,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3
            )
        }

        if (report.jarUrl.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            val fileName = report.jarUrl.substringAfterLast('/').take(36)
            Text(
                text = "插件包 $fileName" + if (report.jarHash.isNotBlank()) {
                    " · md5 ${report.jarHash.take(8)}…（校验已启用）"
                } else {
                    " · 无 md5（不做校验）"
                },
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "JS 源由内置脚本引擎直接解析，导入即可用；插件源需先加载防盗链插件包（见上方的 x.jpg）才能工作。",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun StatCell(label: String, count: Int, kind: SiteKind, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = count.toString(),
            color = if (count > 0) kindColor(kind) else Tokens.TextDim,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(label, color = Tokens.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}

private fun kindColor(kind: SiteKind) = when (kind) {
    SiteKind.READY -> Tokens.Text
    SiteKind.PLUGIN -> Tokens.Accent
    SiteKind.JS -> androidx.compose.ui.graphics.Color(0xFF7FB4E5)
    SiteKind.UNSUPPORTED -> androidx.compose.ui.graphics.Color(0xFFE0897B)
}

@Composable
private fun ActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Tokens.Accent else Tokens.SurfaceHigh)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            color = if (enabled) Tokens.OnAccent else Tokens.TextDim,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ConfigBanner(text: String, ok: Boolean?) {
    val accent = when (ok) {
        true -> Tokens.Accent
        false -> androidx.compose.ui.graphics.Color(0xFFE06C75)
        null -> Tokens.TextDim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ok == null) {
            Text("…", color = accent, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            color = accent,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Tokens.SurfaceHigh else Tokens.Surface)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) Tokens.Accent else Tokens.TextDim,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (selected) Tokens.Text else Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Tokens.Text, fontSize = 16.sp),
        cursorBrush = SolidColor(Tokens.Accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.Surface)
            .padding(16.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(label, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
                inner()
            }
        }
    )
}
