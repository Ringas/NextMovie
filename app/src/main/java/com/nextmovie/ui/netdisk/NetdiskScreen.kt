package com.nextmovie.ui.netdisk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.ui.common.GlassCard
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.theme.Tokens

/**
 * 网盘（对齐 Marco 的「4K 网盘」能力页）
 *
 * Marco 自己托管网盘 OAuth；NextMovie 不内置账号体系，网盘授权与直链解析
 * 都由来源插件提供，所以这一页只做三件事：说清楚能力归属、给出入口、
 * 放扫码快传把订阅/账号推到盒子上。
 */
@Composable
fun NetdiskScreen(
    repo: AppRepository,
    onOpenAccounts: () -> Unit,
    onPair: () -> Unit
) {
    val site = repo.currentSite
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 56.dp, end = 56.dp, top = 8.dp, bottom = 28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("4K 网盘", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "网盘账号、授权与 4K 直链都由来源插件托管，本机不保存任何网盘凭据",
            color = Tokens.TextFaint,
            fontSize = 13.5.sp
        )
        Spacer(Modifier.height(20.dp))

        GlassCard(
            title = site?.name ?: "未选择来源",
            subtitle = if (site?.isSpider == true) "插件来源 · 支持账户与网盘入口" else "接口来源 · 不提供网盘能力"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvPill(
                    text = "打开账户与网盘",
                    prominent = true,
                    enabled = site?.isSpider == true && site.isPlayable,
                    onClick = onOpenAccounts
                )
                TvPill(text = "扫码快传", glass = true, onClick = onPair)
            }
        }
        Spacer(Modifier.height(16.dp))

        GlassCard(
            title = "支持的网盘类型",
            subtitle = "能否使用取决于当前来源是否接入对应网盘"
        ) {
            listOf(
                "夸克网盘" to "扫码登录后可直接播放 4K 原片",
                "UC 网盘" to "与夸克同源，跟随来源插件授权",
                "阿里云盘" to "需要来源插件提供 token",
                "百度网盘" to "受限速影响，建议走直链",
                "115 网盘" to "多为来源自带账号池",
                "AList / WebDAV" to "来源插件代理转发"
            ).forEach { (name, desc) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(desc, color = Tokens.TextFaint, fontSize = 13.sp)
                    }
                    Text("来源插件", color = Tokens.AmberText, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        GlassCard(title = "使用方式") {
            Text(
                "1. 在「设置 · 片源与订阅」载入订阅；2. 在详情页点「账户 / 网盘」进入插件页面完成扫码登录；" +
                    "3. 回到详情页选带「云盘」标记的线路，即可直接播放 4K 原片。",
                color = Tokens.TextDim,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        }
    }
}
