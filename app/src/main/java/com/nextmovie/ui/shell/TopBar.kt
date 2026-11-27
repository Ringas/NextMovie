package com.nextmovie.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens

/**
 * Apple TV+ 式顶栏：54dp 极细黑条，左侧极小的字标，中部只有图标（首页 / 片库 / 网盘），
 * 右侧搜索与设置各自一枚幽灵图标。选中态不是实心胶囊，而是图标下方一条 2dp 白线。
 * 目的：把"导航"这件事的最小化，画面里只留内容与一条几乎看不见的轴。
 */
@Composable
fun TopBar(
    selected: ShellTab,
    status: String,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val leftTabs = listOf(ShellTab.Home, ShellTab.Library, ShellTab.Netdisk)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(Color(0xF0060810))
            .padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Tokens.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "NEXT",
            color = Color(0xCCFFFFFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Spacer(Modifier.width(40.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            leftTabs.forEach { tab ->
                BarIcon(tab = tab, selected = tab == selected) { onSelect(tab) }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            BarIcon(tab = ShellTab.Search, selected = selected == ShellTab.Search) {
                onSelect(ShellTab.Search)
            }
            BarIcon(tab = ShellTab.Settings, selected = selected == ShellTab.Settings) {
                onSelect(ShellTab.Settings)
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x0DFFFFFF))
    )
}

@Composable
private fun BarIcon(
    tab: ShellTab,
    selected: Boolean,
    icon: ImageVector = tab.icon,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0x1FFFFFFF) else Color.Transparent)
            .tvClickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .size(width = 46.dp, height = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tab.title,
            tint = when {
                focused -> Color.White
                selected -> Color(0xF2FFFFFF)
                else -> Color(0x66FFFFFF)
            },
            modifier = Modifier.size(19.dp)
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-4).dp)
                    .width(14.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}
