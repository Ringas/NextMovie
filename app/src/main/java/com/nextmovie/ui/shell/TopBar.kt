package com.nextmovie.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.ui.common.TvPill
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens

/** 顶栏占位高度：内容区按这个值下移，改高度只改这一处。 */
val TopBarHeight = 56.dp

/**
 * D + B 融合顶栏：
 * - 左（D）：NEXT 字标，不画底、不加徽标，只留字。
 * - 中（D）：纯文字导航，选中态是一条蓝色短线，没有胶囊底；聚焦时给一层 8% 白底。
 * - 右（B）：站点名 · 花卷 / 换源胶囊 / 设置圆钮，换源与设置在右侧固定可达。
 * 背景是自上而下的渐变（含内容时压住海报，空背景时几乎看不见），没有分割线。
 */
@Composable
fun TopBar(
    selected: ShellTab,
    siteName: String,
    onSelect: (ShellTab) -> Unit,
    onSwitchSite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navItems = listOf(ShellTab.Home, ShellTab.Library, ShellTab.Netdisk, ShellTab.Search)
    // 设置页里顶栏不再抢焦点：否则从齿轮按向下会先落回「首页」，要多按一次才进设置列表
    val barFocusable = selected != ShellTab.Settings
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0xE605070A),
                    0.72f to Color(0xB305070A),
                    1.0f to Color(0x0005070A),
                )
            )
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 左：只留字标，不画底
        Text(
            "NEXT",
            color = Color(0xE6FFFFFF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
        )

        // 中：文字导航居中，位置不被两侧宽度影响
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            navItems.forEach { tab ->
                NavText(tab = tab, selected = tab == selected, focusable = barFocusable) { onSelect(tab) }
            }
        }

        // 右：站点 + 换源 + 设置
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Text(
                text = "站点 · ${cleanSiteName(siteName)}",
                color = Tokens.TextFaint,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
            Spacer(Modifier.width(12.dp))
            TvPill(
                glass = true,
                horizontalPadding = 14.dp,
                verticalPadding = 8.dp,
                onClick = onSwitchSite,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("换源", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(10.dp))
            SettingsButton(
                active = selected == ShellTab.Settings,
                focusable = barFocusable,
                onClick = { onSelect(ShellTab.Settings) },
            )
        }
    }
}

@Composable
private fun NavText(
    tab: ShellTab,
    selected: Boolean,
    focusable: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(showRing = false, corner = 8.dp, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = tab.title,
            color = when {
                focused -> Color.White
                selected -> Color(0xF2FFFFFF)
                else -> Color(0x80FFFFFF)
            },
            fontSize = 16.sp,
            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .width(if (selected || focused) 30.dp else 0.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    when {
                        selected -> Tokens.Accent
                        focused -> Color(0x8CFFFFFF)
                        else -> Color.Transparent
                    }
                )
        )
    }
}

@Composable
private fun SettingsButton(active: Boolean, focusable: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    active -> Color(0x24FFFFFF)
                    else -> Color(0x1AFFFFFF)
                }
            )
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(showRing = false, corner = 20.dp, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "设置",
            tint = when {
                focused -> Tokens.DarkInk
                active -> Color.White
                else -> Color(0xB3FFFFFF)
            },
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * 站点名清洗：很多源把站点写成「❤️花卷 | 4K❤️」，直接上顶栏会带一串符号。
 * 只取第一个含文字的片段、去掉非字母数字（emoji/心形/竖线），清洗后为空就退回原名，
 * 不做 substringBefore("|") 那种一旦没有分隔符就把名字截空的写法。
 */
private fun cleanSiteName(raw: String): String {
    if (raw.isBlank()) return "未配置"
    val segment = raw.split('|', '｜', '/')
        .firstOrNull { part -> part.any { it.isLetterOrDigit() } }
        ?: raw
    val cleaned = segment
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+(4K|8K|HD|SD|1080P|2160P|蓝光|高清|超清)\\b", RegexOption.IGNORE_CASE), "")
        .trim()
    return cleaned.ifBlank { raw.trim() }.take(14)
}
