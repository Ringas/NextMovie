package com.nextmovie.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Vod) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 电视/盒子上系统输入法会整屏盖住自绘键盘，聚焦时主动收起；外接键盘不受影响
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 当前源不支持搜索时，允许在这一页直接挑一个可搜索的源，不用先回设置换源
    var activeSite by remember(site.runtimeKey) { mutableStateOf(site) }
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
            runCatching { repo.search(activeSite, query) }
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "NEXT MOVIE",
                color = Tokens.Text,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "SEARCH",
                color = Tokens.Accent,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro, letterSpacing = Tokens.Type.MICRO_TRACK.sp),
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Spacer(Modifier.width(22.dp))
            Text(
                text = "‹ 返回",
                color = Tokens.TextDim,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Corner.pill))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                    .tvClickable(onClick = onBack, corner = Tokens.Corner.pill)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "当前源 · ${activeSite.name}",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "搜索",
            color = Tokens.Text,
            fontSize = Tokens.Type.display,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(14.dp))

        if (!activeSite.searchable) {
            Text(
                text = "「${activeSite.name}」没有开启搜索，先挑一个可搜索的来源：",
                color = Tokens.TextDim,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            val candidates = repo.sites.filter { it.searchable }.take(6)
            if (candidates.isEmpty()) {
                Text(
                    text = "这份配置里没有可搜索的来源（searchable=0），去设置里换一份配置吧",
                    color = Tokens.TextFaint,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    candidates.forEach { candidate ->
                        Text(
                            text = candidate.name,
                            color = Tokens.Text,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Tokens.Corner.pill))
                                .background(Tokens.Surface)
                                .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                                .tvClickable(corner = Tokens.Corner.pill) { activeSite = candidate }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            return@Column
        }

        // 输入框：给了输入法一条路，也给外接键盘一条路
        val fieldShape = RoundedCornerShape(Tokens.Corner.card)
        Row(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(48.dp)
                .clip(fieldShape)
                .background(Tokens.Surface)
                .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) Tokens.Accent else Tokens.Stroke, fieldShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⌕", color = Tokens.Accent, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (keyword.isEmpty()) {
                    Text("输入片名，选中后按确认开始搜索", color = Tokens.TextFaint, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Tokens.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Tokens.Accent),
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        fieldFocused = it.isFocused
                        if (it.isFocused) keyboard?.hide()
                    }
                )
            }
            if (keyword.isNotEmpty()) {
                Text(
                    text = "清空",
                    color = Tokens.TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.tvClickable(onClick = { keyword = "" }, corner = Tokens.Corner.pill).padding(8.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "方向键选字符 · OK 输入 · 也可用系统输入法直接打字",
            color = Tokens.TextFaint,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro)
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
            Text("正在搜索…", color = Tokens.Accent, style = MaterialTheme.typography.bodyMedium)
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEach { key ->
                    KeyCap(text = key, onClick = { onChar(key) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap(text = "空格", width = 190.dp, onClick = { onChar(" ") })
            KeyCap(text = "删除", width = 120.dp, onClick = onBackspace)
            KeyCap(text = "清空", width = 120.dp, onClick = onClear)
            KeyCap(text = "搜索", width = 160.dp, accent = true, onClick = onSubmit)
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
    val shape = RoundedCornerShape(Tokens.Radius)
    Box(
        modifier = Modifier
            .width(width)
            .height(44.dp)
            .clip(shape)
            .background(if (accent) Tokens.AccentDim else Tokens.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Tokens.Accent else if (accent) Tokens.Accent.copy(alpha = 0.45f) else Tokens.Stroke,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick, corner = Tokens.Radius, scaleOnFocus = 1.03f),
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
