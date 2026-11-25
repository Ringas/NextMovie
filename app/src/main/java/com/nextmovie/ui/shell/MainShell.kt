package com.nextmovie.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nextmovie.core.AppRepository
import com.nextmovie.core.config.Site
import com.nextmovie.core.history.HistoryEntry
import com.nextmovie.core.model.Vod
import com.nextmovie.ui.common.AmbientBackdrop
import com.nextmovie.ui.common.FloatingTopBar
import com.nextmovie.ui.common.NavTab
import com.nextmovie.ui.home.HomeScreen
import com.nextmovie.ui.library.LibraryScreen
import com.nextmovie.ui.netdisk.NetdiskScreen
import com.nextmovie.ui.search.SearchScreen
import com.nextmovie.ui.settings.Pairing
import com.nextmovie.ui.settings.PairingDialog
import com.nextmovie.ui.settings.SettingsScreen

/**
 * 主外壳（对齐 Marco `TvLivingRoomShell`）
 *
 * 全局只有一份柔光背景 + 一枚居中玻璃胶囊顶栏，五个标签页（发现 / 搜索 / 片库 / 网盘 / 设置）
 * 都是外壳里的内容区，切页不重建背景、不重建顶栏，和 Marco 完全同构。
 * 详情页与播放器是压在外壳之上的独立层级，由 MainActivity 负责路由。
 */
@Composable
fun MainShell(
    repo: AppRepository,
    site: Site,
    onOpenDetail: (Site, Vod) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onOpenAccounts: () -> Unit,
    onTab: (Int) -> Unit = {},
    initialTab: Int = 0,
    homeInitialFocusIndex: Int = 0,
    homeInitialCategory: Int = 0,
    homeReloadSignal: Int = 0,
    onHomeTabChanged: (Int) -> Unit = {},
    onHomeFocusIndexChanged: (Int) -> Unit = {}
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    var tint by remember { mutableStateOf<Color?>(null) }
    var pairing by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(posterTint = tint)
        Column(Modifier.fillMaxSize()) {
            FloatingTopBar(
                tabs = listOf(
                    NavTab(Icons.Filled.Home, "发现", tab == 0) { tab = 0; onTab(0) },
                    NavTab(Icons.Filled.Search, "搜索", tab == 1) { tab = 1; onTab(1) },
                    NavTab(Icons.Filled.List, "片库", tab == 2) { tab = 2; onTab(2) },
                    NavTab(Icons.Filled.Star, "网盘", tab == 3) { tab = 3; onTab(3) },
                    NavTab(Icons.Filled.Settings, "设置", tab == 4) { tab = 4; onTab(4) }
                ),
                status = repo.configStatus()?.text ?: site.name,
                modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 28.dp, bottom = 18.dp)
            )
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
            ) {
                when (tab) {
                    1 -> SearchScreen(
                        repo = repo,
                        site = site,
                        onOpenDetail = { foundSite, vod -> onOpenDetail(foundSite, vod) },
                        onBack = {}
                    )
                    2 -> LibraryScreen(
                        repo = repo,
                        onOpenDetail = { foundSite, vod -> onOpenDetail(foundSite, vod) },
                        onResume = onResume
                    )
                    3 -> NetdiskScreen(
                        repo = repo,
                        onOpenAccounts = onOpenAccounts,
                        onPair = { pairing = true }
                    )
                    4 -> SettingsScreen(
                        repo = repo,
                        onBack = { tab = 0; onTab(0) },
                        onConfigLoaded = { tab = 0; onTab(0) }
                    )
                    else -> HomeScreen(
                        repo = repo,
                        site = site,
                        onOpenDetail = { vod -> onOpenDetail(site, vod) },
                        onOpenSettings = { tab = 4; onTab(4) },
                        onSearch = { tab = 1; onTab(1) },
                        onFavorites = { tab = 2; onTab(2) },
                        onHistory = { tab = 2; onTab(2) },
                        onResume = onResume,
                        initialFocusIndex = homeInitialFocusIndex,
                        initialTab = homeInitialCategory,
                        reloadSignal = homeReloadSignal,
                        onTabChanged = onHomeTabChanged,
                        onFocusIndexChanged = onHomeFocusIndexChanged,
                        onTint = { tint = it }
                    )
                }
            }
        }
    }

    if (pairing) PairingDialog(onDismiss = { pairing = false }, onReceived = { pairing = false })
}
