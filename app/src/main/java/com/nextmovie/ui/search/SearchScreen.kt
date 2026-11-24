package com.nextmovie.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.nextmovie.ui.common.tvClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.PosterCard
import com.nextmovie.ui.common.mouseWheel
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * 搜索页。电视上没有好用的中文输入法，所以做了两层输入：
 * 1) 一个可聚焦输入框（外接键盘 / 电视输入法可用）；
 * 2) 一套自绘九宫格键盘，纯遥控器也能把英文片名敲出来。
 */
@Composable
fun SearchScreen(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Vod) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    fun submit() {
        val query = keyword.trim()
        if (query.isEmpty() || loading) return
        scope.launch {
            loading = true
            message = null
            runCatching { repo.search(site, query) }
                .onSuccess { list ->
                    results = repo.prepareForList(list)
                    if (list.isEmpty()) message = "没有搜到「$query」，换个片名试试"
                }
                .onFailure { message = it.message ?: "搜索失败" }
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .padding(Tokens.ScreenPadding)
            // 外接键盘/模拟器：不聚焦输入框时也能直接敲片名，回车即搜索
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_ENTER -> { if (fieldFocused) { submit(); true } else false }
                    android.view.KeyEvent.KEYCODE_DEL -> {
                        if (fieldFocused) false else { keyword = keyword.dropLast(1); true }
                    }
                    else -> {
                        if (fieldFocused) return@onPreviewKeyEvent false
                        val ch = native.unicodeChar.toChar()
                        if (ch.isLetterOrDigit() || ch == ' ') {
                            keyword = (keyword + ch).take(40)
                            true
                        } else false
                    }
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "返回",
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.tvClickable { onBack() }.padding(8.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "搜索 · ${site.name}",
                color = Tokens.Text,
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(Modifier.height(20.dp))

        if (!site.searchable) {
            Text(
                text = "这个源没有开启搜索（配置里 searchable=0）",
                color = Tokens.TextDim,
                style = MaterialTheme.typography.bodyLarge
            )
            return@Column
        }

        // 输入框：给了输入法一条路，也给外接键盘一条路
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(52.dp)
                .background(Tokens.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, Tokens.Stroke, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (keyword.isEmpty()) {
                Text("输入片名", color = Tokens.TextDim, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = keyword,
                onValueChange = { keyword = it },
                singleLine = true,
                textStyle = TextStyle(color = Tokens.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(Tokens.Accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { fieldFocused = it.isFocused }
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "方向键选择字符，确认键输入；也可使用系统输入法",
            color = Tokens.TextDim,
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(Modifier.height(16.dp))

        Keypad(
            onChar = { keyword = (keyword + it).take(40) },
            onBackspace = { keyword = keyword.dropLast(1) },
            onClear = { keyword = "" },
            onSubmit = { submit() }
        )

        Spacer(Modifier.height(16.dp))

        val msg = message
        if (loading) {
            Text("正在搜索…", color = Tokens.TextDim, style = MaterialTheme.typography.bodyLarge)
        } else if (msg != null) {
            Text(msg, color = Tokens.TextDim, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(148.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().mouseWheel(gridState)
        ) {
            items(results, key = { it.id + "|" + it.name }) { vod ->
                PosterCard(
                    vod = vod,
                    onClick = { onOpenDetail(vod) },
                    onFocused = {}
                )
            }
        }
    }
}

@Composable
private fun Keypad(
    onChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    val rows = listOf(
        "1234567890".map { it.toString() },
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { key ->
                    KeyCap(text = key, onClick = { onChar(key) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyCap(text = "空格", width = 150.dp, onClick = { onChar(" ") })
            KeyCap(text = "删除", width = 110.dp, onClick = onBackspace)
            KeyCap(text = "清空", width = 110.dp, onClick = onClear)
            KeyCap(text = "搜索", width = 140.dp, accent = true, onClick = onSubmit)
        }
    }
}

@Composable
private fun KeyCap(
    text: String,
    width: androidx.compose.ui.unit.Dp = 60.dp,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Tokens.Accent else Tokens.Stroke,
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                if (focused) Tokens.SurfaceHigh else if (accent) Tokens.Accent.copy(alpha = 0.18f) else Tokens.Surface,
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                focused -> Tokens.Text
                accent -> Tokens.Accent
                else -> Tokens.TextDim
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
