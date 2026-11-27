package com.nextmovie.ui.netdisk

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.pan.AliQrLogin
import com.nextmovie.core.pan.PanLogin
import com.nextmovie.core.pan.PanPrefs
import com.nextmovie.core.pan.PanProviders
import com.nextmovie.core.pan.Yun139Api
import com.nextmovie.ui.common.GlassCard
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.theme.Tokens

/**
 * 网盘页：这一页现在回答一个具体问题——「我这台盒子上到底哪几个网盘是登着的」。
 *
 * 凭据分两处：夸克 / UC / 百度 / 115 / 哔哩 / 光鸭 / 天翼 由扫码后写进宿主镜像（PanLogin），
 * 阿里是宿主自己持有的 refresh_token（AliQrLogin），移动 139 是宿主 WebView 抓到的
 * authorization（Yun139Api）。所以状态必须分别问三个来源，不能只看插件 status。
 */
@Composable
fun NetdiskScreen(
    repo: AppRepository,
    onOpenAccounts: () -> Unit,
    onPair: () -> Unit
) {
    val context = LocalContext.current
    val site = repo.currentSite
    var probeTick by remember { mutableIntStateOf(0) }
    val rows = remember(probeTick) { probe(context) }
    val logged = rows.filter { it.logged }
    val pending = rows.filter { !it.logged }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("网盘", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "扫码一次即可；播放时自动解析网盘直链",
                    color = Tokens.TextFaint,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TvPill(text = "刷新状态", glass = true, onClick = { probeTick++ })
            Spacer(Modifier.width(10.dp))
            TvPill(text = "扫码快传", glass = true, onClick = onPair)
        }
        Spacer(Modifier.height(18.dp))

        // —— 已登录 ——
        SectionHeader(
            title = "已登录",
            trailing = if (logged.isEmpty()) "还没有网盘登录" else "${logged.size} 个网盘在线"
        )
        if (logged.isEmpty()) {
            GlassCard(title = null) {
                Text(
                    "先去播一部网盘里的片子：播放页会在卡片里直接弹二维码，" +
                        "用对应网盘的 App 扫完就自动续播，不用跳配置中心。",
                    color = Tokens.TextDim,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        } else {
            GlassCard(title = null) { ProviderRows(logged) }
        }
        Spacer(Modifier.height(16.dp))

        // —— 未登录 ——
        SectionHeader(title = "未登录", trailing = "${pending.size} 个")
        GlassCard(title = null) { ProviderRows(pending) }
        Spacer(Modifier.height(16.dp))

        // —— 插件侧 ——
        GlassCard(
            title = if (site?.isSpider == true) "当前来源" else "当前来源不支持网盘",
            subtitle = if (site?.isSpider == true) {
                "「${site.name}」是插件来源，账号与网盘入口都在它的面板里"
            } else {
                "接口来源只给直链，没有网盘能力；换一个插件来源才能扫码"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvPill(
                    text = "打开账户与网盘",
                    glass = true,
                    enabled = site?.isSpider == true && site.isPlayable,
                    onClick = onOpenAccounts
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Tokens.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(trailing, color = Tokens.TextGhost, fontSize = 12.5.sp)
    }
}

@Composable
private fun ProviderRows(rows: List<PanRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (row.logged) Tokens.Green else Tokens.TextGhost)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.label,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(row.hint, color = Tokens.TextFaint, fontSize = 12.5.sp)
                }
                Text(
                    row.right,
                    color = if (row.logged) Tokens.GreenText else Tokens.TextGhost,
                    fontSize = 13.sp,
                    fontWeight = if (row.logged) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (index != rows.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x14FFFFFF))
                )
            }
        }
    }
}

/**
 * 插件 prefs 里各盘凭据的键名（实测自 95 站插件的 Wex 面板）。
 * 扫码成功时插件自己也会写一份，所以这一处必须查，否则页面会谎报「未登录」。
 */
private fun candidateKeys(id: String): List<String> = when (id) {
    "quark" -> listOf("Wex_quark_cookie", "quarkCookie", "cookie_quark")
    "ucCookie" -> listOf("Wex_ucpan_cookie", "Wex_uc_cookie", "ucCookie")
    "baidu" -> listOf("Wex_baidu_cookie", "pan.credential.baidu", "baiduCookie")
    "pan115" -> listOf("Wex_pan115_cookie", "Wex_115_cookie", "pan115Cookie")
    "bili" -> listOf("Wex_bili_cookie", "biliCookie")
    "guangya" -> listOf("Wex_guangya_token", "Wex_guangYa_token")
    "pan189" -> listOf("Wex_189_cookie", "Wex_pan189_cookie", "pan189Cookie")
    "thunder" -> listOf("thunderCfg")
    "pan123" -> listOf("Wex_123_Login")
    else -> emptyList()
}

private data class PanRow(
    val label: String,
    val logged: Boolean,
    val hint: String,
    val right: String
)

/**
 * 逐家问三个凭据来源。顺序按播放优先级：夸克 → UC → 阿里 → 百度 → 115 → 移动 → 123 → 天翼 → 光鸭 → 哔哩。
 * 只展示这 10 家主流盘；AList / WebDAV 属于地址型配置，放在设置页更合适。
 */
private fun probe(context: android.content.Context): List<PanRow> {
    val out = ArrayList<PanRow>(10)

    fun plugin(id: String, hint: String) {
        val provider = PanProviders.of(id) ?: return
        // 两处都可能持有凭据：宿主镜像（扫码后写回）+ 插件自己的 prefs 文件
        val mirror = runCatching { PanLogin.cookieFor(context, id) }.getOrDefault("")
        val inPlugin = candidateKeys(id).firstNotNullOfOrNull { key ->
            runCatching { PanPrefs.readString(context, key) }.getOrNull()?.takeIf { it.length > 24 }
        }.orEmpty()
        val cookie = mirror.takeIf { it.length > 24 } ?: inPlugin
        val ok = cookie.isNotBlank()
        out += PanRow(
            label = provider.label,
            logged = ok,
            hint = hint,
            right = if (ok) "已登录" else "未登录"
        )
    }

    plugin("quark", "夸克网盘扫码登录")
    plugin("ucCookie", "UC 网盘扫码登录")
    // 阿里：宿主自己存 refresh_token
    val ali = runCatching { AliQrLogin.credential(context) }.getOrNull()
    out += PanRow(
        label = "阿里",
        logged = ali != null,
        hint = "阿里云盘 App 扫码登录",
        right = if (ali != null) {
            "已登录" + ali.userId.takeIf { it.isNotBlank() }?.let { " · ${it.take(8)}" }.orEmpty()
        } else "未登录"
    )
    plugin("baidu", "百度网盘 App 扫码登录")
    plugin("pan115", "115 App 扫码")
    // 139：WebView 抓 authorization
    val y139 = runCatching { Yun139Api.session(context) }.getOrNull()
    out += PanRow(
        label = "移动 139",
        logged = y139 != null,
        hint = "官方网页二维码，扫完抓 authorization",
        right = if (y139 != null) "已登录 · ${y139.account.ifBlank { "账号已存" }}" else "未登录"
    )
    // 123 / 迅雷 只有账号密码，宿主看不到凭据
    out += PanRow(
        label = "123",
        logged = false,
        hint = "官方端没有扫码，只能账号密码（插件配置中心）",
        right = "需账号登录"
    )
    plugin("pan189", "天翼账号 Cookie，网页登录后写入")
    plugin("guangya", "光鸭扫码")
    plugin("bili", "哔哩扫码")

    return out
}
