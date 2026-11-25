package com.nextmovie.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.ui.theme.Tokens

/**
 * 统一菜单行：设置侧栏、播放菜单、弹层列表共用。
 * 遥控器上下移动焦点，确认/Enter/空格触发；可选右键进入、左键返回。
 */
@Composable
fun TvMenuItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: String? = null,
    focusRequester: FocusRequester? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.Corner.card)
    val bg = when {
        focused -> Color(0x33E2B268)
        selected -> Color(0x22E2B268)
        else -> Color.Transparent
    }
    val borderColor = when {
        focused -> Tokens.Accent
        selected -> Tokens.Accent.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .border(1.5.dp, borderColor, shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                when {
                    TvKeys.isConfirmUp(e) && enabled -> {
                        onClick(); true
                    }
                    e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp &&
                        TvKeys.actionOf(e) == TvAction.Left && onLeft != null -> {
                        onLeft(); true
                    }
                    e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp &&
                        TvKeys.actionOf(e) == TvAction.Right && onRight != null -> {
                        onRight(); true
                    }
                    else -> false
                }
            }
            .tvClickable(enabled = enabled, corner = Tokens.Corner.card, showRing = false, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (focused || selected) Tokens.Accent else Tokens.Text,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) Color.White else Tokens.TextFaint,
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Tokens.TextFaint,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, color = Tokens.TextFaint, fontSize = 12.sp)
        }
    }
}

/**
 * 分段标题 + 说明，用于设置卡、播放菜单分组。
 */
@Composable
fun TvSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Tokens.TextFaint, fontSize = 13.sp)
        }
    }
}

/**
 * 胶囊操作按钮：遥控器可聚焦，键盘 Enter 确认。
 */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.Corner.pill)
    val bg = when {
        !enabled -> Color(0x22FFFFFF)
        primary && focused -> Tokens.Accent
        primary -> Tokens.Accent.copy(alpha = 0.85f)
        focused -> Color(0x44E2B268)
        else -> Color(0x22FFFFFF)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Tokens.Accent else Color(0x33FFFFFF),
                shape = shape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(enabled = enabled, corner = Tokens.Corner.pill, showRing = false, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary && focused) Color(0xFF1A1208) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 快捷键提示条：底部常驻一行，同时写清遥控与键盘。
 */
@Composable
fun TvHintBar(
    remote: String,
    keyboard: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC0A1018))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("遥控 · $remote", color = Tokens.TextFaint, fontSize = 12.sp)
        Text("键盘 · $keyboard", color = Tokens.TextFaint, fontSize = 12.sp)
    }
}
