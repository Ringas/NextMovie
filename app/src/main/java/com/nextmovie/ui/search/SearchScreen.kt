package com.nextmovie.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_SOURCES = 12
private const val CONCURRENCY = 4
private const val PER_SOURCE_TIMEOUT_MS = 8_000L

private data class SearchGroup(val site: Site, val vods: List<Vod>)

/**
 * 搜索页（V2）：一次搜索同时打多个可搜索源，结果按源分组。
 * 交互仍保持「遥控器优先」：自绘键盘 + 外接键盘直敲，回车即开始聚合搜索。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Site, Vod) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var keyword by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<SearchGroup>>(emptyList()) }
    var doneCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val fieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val firstResultKey = remember { mutableStateOf<String?>(null) }

    // 第一批结果到达后，把焦点直接送到第一张海报，遥控器不用先摸位置
    androidx.compose.runtime.LaunchedEffect(groups.firstOrNull()?.site?.runtimeKey, submitted) {
        val first = groups.firstOrNull()?.let { g -> g.vods.firstOrNull()?.let { g.site.runtimeKey + "|" + it.id } }
        if (first != null && firstResultKey.value == null) {
            firstResultKey.value = first
            kotlinx.coroutines.delay(80)
            runCatching { firstResultFocus.requestFocus() }
        }
    }

    fun submit() {
        val query = keyword.trim()
        if (query.isEmpty() || loading) return
        val targets = repo.searchableSites(MAX_SOURCES)
        if (targets.isEmpty()) {
            message = "这份配置里没有可搜索的来源（searchable=0），去设置里换一份配置吧"
            return
        }
        editing = false
        submitted = query
        groups = emptyList()
        doneCount = 0
        totalCount = targets.size
        message = null
        firstResultKey.value = null
        scope.launch {
            loading = true
            val hits = java.util.concurrent.ConcurrentHashMap<Int, List<Vod>>()
            val sem = Semaphore(CONCURRENCY)
            coroutineScope {
                targets.forEachIndexed { index, target ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            val raw = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                                runCatching { repo.search(target, query) }.getOrNull() ?: emptyList()
                            } ?: emptyList()
                            val list = if (raw.isEmpty()) emptyList() else repo.prepareForList(raw)
                            if (list.isNotEmpty()) hits[index] = list
                            withContext(Dispatchers.Main) {
                                doneCount += 1
                                // 按配置顺序稳定排列，先回的结果先显示但不会乱跳位置
                                groups = targets.indices.mapNotNull { i ->
                                    hits[i]?.let { SearchGroup(targets[i], it) }
                                }
                            }
                        }
                    }
                }
            }
            loading = false
            if (groups.isEmpty()) message = "「$query」在 $totalCount 个来源里都没搜到，换个片名试试"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .padding(Tokens.ScreenPadding)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (fieldFocused || editing) { submit(); true } else false
                    }
                    android.view.KeyEvent.KEYCODE_DEL -> {
                        if (fieldFocused) false else { keyword = keyword.dropLast(1); true }
                    }
                    else -> {
                        if (fieldFocused) return@onPreviewKeyEvent false
                        val ch = native.unicodeChar.toChar()
                        if (ch.isLetterOrDigit() || ch == ' ') {
                            keyword = (keyword + ch).take(40)
                            if (!editing) editing = true
                            true
                        } else false
                    }
                }
            }
    ) {
        // ——— 顶栏 ———
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
                text = "全网聚合 · 最多 $MAX_SOURCES 个可搜索源",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(Modifier.height(12.dp))

        // ——— 标题行 ———
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "搜索",
                color = Tokens.Text,
                fontSize = Tokens.Type.display,
                fontWeight = FontWeight.SemiBold
            )
            if (!editing && submitted.isNotBlank()) {
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "“$submitted” · ${groups.sumOf { it.vods.size }} 条 · $doneCount/$totalCount 源",
                    color = Tokens.TextFaint,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.weight(1f))
            if (!editing) {
                Text(
                    text = "修改关键词",
                    color = Tokens.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Tokens.Corner.pill))
                        .background(Tokens.Surface)
                        .border(1.dp, Tokens.Stroke, RoundedCornerShape(Tokens.Corner.pill))
                        .tvClickable(corner = Tokens.Corner.pill) {
                            editing = true
                            scope.launch { runCatching { fieldFocus.requestFocus() } }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (editing) {
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
                        Text("输入片名，选中后按确认开始全网搜索", color = Tokens.TextFaint, style = MaterialTheme.typography.bodyMedium)
                    }
                    BasicTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Tokens.Text, fontSize = 15.sp),
                        cursorBrush = SolidColor(Tokens.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFocus)
                            .onFocusChanged {
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
                text = "方向键选字符 · OK 输入 · 也可用系统输入法直接打字 · 回车/「搜索」开始",
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = Tokens.Type.micro)
            )

            Spacer(Modifier.height(14.dp))

            Keypad(
                onChar = { keyword = (keyword + it).take(40) },
                onBackspace = { keyword = keyword.dropLast(1) },
                onClear = { keyword = "" },
                onSubmit = { submit() }
            )
        }

        // ——— 状态行 ———
        val msg = message
        if (loading || msg != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    Text(
                        text = if (doneCount == 0) "正在搜索 $totalCount 个来源…" else "搜索中 · 已完成 $doneCount/$totalCount 源",
                        color = Tokens.Accent,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Tokens.Stroke)
                    ) {
                        val p = if (totalCount == 0) 0f else doneCount.toFloat() / totalCount
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(p)
                                .height(3.dp)
                                .background(Tokens.Accent)
                        )
                    }
                }
                if (msg != null) {
                    Text(msg, color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ——— 结果：按源分组 ———
        if (groups.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().mouseWheel(listState)
            ) {
                groups.forEach { group ->
                    val groupKey = "h|" + group.site.runtimeKey + "|" + submitted
                    item(key = groupKey) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text(
                                text = group.site.name,
                                color = Tokens.TextDim,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${group.vods.size} 条",
                                color = Tokens.Accent,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(Modifier.width(14.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(Tokens.Stroke)
                            )
                        }
                    }
                    item(key = "r|" + group.site.runtimeKey + "|" + submitted) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(group.vods, key = { it.id + "|" + it.name }) { vod ->
                                val key = group.site.runtimeKey + "|" + vod.id
                                PosterCard(
                                    vod = vod,
                                    onClick = { onOpenDetail(group.site, vod) },
                                    onFocused = {},
                                    modifier = if (key == firstResultKey.value) Modifier.focusRequester(firstResultFocus) else Modifier
                                )
                            }
                        }
                    }
                }
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
            KeyCap(text = "全网搜索", width = 176.dp, accent = true, onClick = onSubmit)
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
