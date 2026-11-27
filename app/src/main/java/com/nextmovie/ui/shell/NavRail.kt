package com.nextmovie.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import com.nextmovie.ui.common.TvKeys
import com.nextmovie.ui.common.TvAction
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.nextmovie.ui.theme.Tokens

enum class ShellTab(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Search("搜索", Icons.Filled.Search),
    Library("片库", Icons.Filled.List),
    Netdisk("网盘", Icons.Filled.Star),
    Settings("设置", Icons.Filled.Settings),
}

/**
 * 左侧导航轨：电视上最稳的信息架构。
 * 焦点在轨道内上下移动；右键进入内容区（由内容自己抢焦）。
 */
@Composable
fun NavRail(
    selected: ShellTab,
    status: String,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(Color(0xE60A1018))
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 品牌点
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Tokens.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        ShellTab.entries.forEach { tab ->
            NavRailItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) },
            )
        }
        Spacer(Modifier.weight(1f))
        // 底部状态只留一行：5 个入口 + 状态在 600dp 高的电视上正好不溢出
        Text(
            text = status.take(6).ifBlank { "就绪" },
            color = Tokens.TextGhost,
            fontSize = 9.5f.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun NavRailItem(
    tab: ShellTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(Tokens.MotionMs),
        label = "navScale",
    )
    val bg = when {
        focused -> Tokens.Accent.copy(alpha = 0.95f)
        selected -> Tokens.Accent.copy(alpha = 0.28f)
        else -> Color.Transparent
    }
    val stroke = when {
        focused -> Color.White
        selected -> Tokens.Accent.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, stroke, RoundedCornerShape(14.dp))
            .background(bg)
            .tvClickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.title,
            tint = if (focused || selected) Color.White else Tokens.TextFaint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.title,
            color = if (focused || selected) Color.White else Tokens.TextFaint,
            fontSize = 12.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
