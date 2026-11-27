package com.nextmovie.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.nextmovie.ui.common.TvKeys
import androidx.compose.ui.graphics.Color
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.AmbientBackdrop
import com.nextmovie.ui.home.HomeScreen
import com.nextmovie.ui.library.LibraryScreen
import com.nextmovie.ui.netdisk.NetdiskScreen
import com.nextmovie.ui.search.SearchScreen
import com.nextmovie.ui.settings.PairingDialog
import com.nextmovie.ui.settings.SettingsScreen

/**
 * 主壳：左侧固定导航轨 + 右侧内容。
 * 交互原则：
 * - 顶层只有 5 个入口，不再用顶部胶囊叠多层导航
 * - 内容区自己管理焦点；返回键由各页 / Activity 处理
 * - 背景柔光统一由壳层绘制，页面不再各铺一层
 */
@Composable
fun MainShell(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Site, Vod) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onOpenAccounts: () -> Unit = {},
    homeInitialFocusIndex: Int = 0,
    homeInitialCategory: Int = 0,
    homeReloadSignal: Int = 0,
    onHomeTabChanged: (Int) -> Unit = {},
    onHomeFocusIndexChanged: (Int) -> Unit = {},
    initialTab: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    searchState: com.nextmovie.ui.search.TvSearchState = remember { com.nextmovie.ui.search.TvSearchState() },
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, ShellTab.entries.lastIndex)) }
    androidx.compose.runtime.LaunchedEffect(tab) { onTabChanged(tab) }
    var tint by remember { mutableStateOf<Color?>(null) }
    var pairing by remember { mutableStateOf(false) }
    val selected = ShellTab.entries.getOrElse(tab) { ShellTab.Home }

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(posterTint = tint)
        // 内容区先组合、顶栏后组合：Android 把窗口首个焦点给「组合顺序里的第一个可聚焦节点」，
        // 以前 TopBar 在上面，所以每次进 App / 切页焦点都先落在顶栏图标上（蓝框在导航里闪一下），
        // 要按一次方向键才进内容。这里把内容放进 54dp 偏移的 Column、顶栏用 align 叠在上层，
        // 视觉不变（内容本来就在顶栏下方），但首焦点自然落到内容区第一张卡。
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxSize()
                .padding(top = 54.dp)
                .onPreviewKeyEvent { e ->
                    val d = TvKeys.sectionDigit(e)
                    if (d != null && d in ShellTab.entries.indices) {
                        tab = d
                        true
                    } else false
                }
        ) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (selected) {
                    ShellTab.Home -> HomeScreen(
                        repo = repo,
                        site = site,
                        onOpenDetail = { vod -> onOpenDetail(site, vod) },
                        onOpenSettings = { tab = ShellTab.Settings.ordinal },
                        onSearch = { tab = ShellTab.Search.ordinal },
                        onFavorites = { tab = ShellTab.Library.ordinal },
                        onHistory = { tab = ShellTab.Library.ordinal },
                        onResume = onResume,
                        initialFocusIndex = homeInitialFocusIndex,
                        initialTab = homeInitialCategory,
                        reloadSignal = homeReloadSignal,
                        onTabChanged = onHomeTabChanged,
                        onFocusIndexChanged = onHomeFocusIndexChanged,
                        onTint = { tint = it },
                    )
                    ShellTab.Search -> SearchScreen(
                        repo = repo,
                        site = site,
                        onOpenDetail = { foundSite, vod -> onOpenDetail(foundSite, vod) },
                        onBack = { tab = ShellTab.Home.ordinal },
                        state = searchState,
                    )
                    ShellTab.Library -> LibraryScreen(
                        repo = repo,
                        onOpenDetail = { foundSite, vod -> onOpenDetail(foundSite, vod) },
                        onResume = onResume,
                    )
                    ShellTab.Netdisk -> NetdiskScreen(
                        repo = repo,
                        onOpenAccounts = onOpenAccounts,
                        onPair = { pairing = true },
                    )
                    ShellTab.Settings -> SettingsScreen(
                        repo = repo,
                        onBack = { tab = ShellTab.Home.ordinal },
                        onConfigLoaded = { tab = ShellTab.Home.ordinal },
                    )
                }
            }
        }
        TopBar(
            selected = selected,
            status = repo.configStatus()?.text ?: site.name,
            onSelect = { t ->
                tab = t.ordinal
                if (t == ShellTab.Home) onHomeTabChanged(0)
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    if (pairing) {
        PairingDialog(onDismiss = { pairing = false }, onReceived = { pairing = false })
    }
}
