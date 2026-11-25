# NextMovie 新版 UI 落地测试记录

- 日期：2026-11-25
- 版本：0.6.12-dev / versionCode 28
- 代码基线：`afa20e1`（已合并远端 “Prioritize detail requests during cached startup”，UI 与文档已推送到 origin/main）
- 构建：`:app:assembleTv64Debug`（arm64-v8a，15 MB）+ `:app:assembleTvDebug`（armeabi-v7a，14 MB），
  产物在 `dist/`，脚本 `scripts/build-tv.sh`（不带参数即同时出两个 ABI）

## 一、设计语言 → Compose 映射

| 设计稿 | Compose 实现 |
| --- | --- |
| 冷炭底 `#0B0C10` | `Tokens.Bg`，全局 Scaffold/Box 底色 |
| 唯一强调色琥珀 `#E2B268` | `Tokens.Accent`：焦点环、进度条、主按钮、区块竖条、微标 |
| 6 / 8 / 12 / 999 圆角阶梯 | `Tokens.Corner.card/panel/pill` + 海报 6dp |
| 2dp 金描边 + 外发光焦点 | `Modifier.tvFocusRing()`：2dp 主环 + 4dp/7dp 两层低透明金内发光 |
| 焦点 1.035 推近 | `tvClickable(scaleOnFocus = 1.035f)` |
| 微型标签字距 | `Tokens.Type.MICRO_TRACK = 0.16f` + `letterSpacing` |
| 三栏设置页 | 176dp 分组导航 / 行式设置 / 226dp 运行状态卡 |

## 二、测试环境

| 项目 | 值 |
| --- | --- |
| 设备 A | Android 11 模拟器 `emulator-5556`（API 30，1920×1080，density 320，abilist 含 arm64-v8a） |
| 设备 B（备份） | ONEPLUS A3010 真机（API 28，arm64-v8a），`wm size 1920x1080`、`wm density 320`、横屏，用于模拟电视视口 |
| 配置 | `in.bmp`（50 个站点 / 50 个插件，其中 32 个可搜索） |
| 播放验证 | 64 位：本地 CMS 测试源 + 公开 HLS 测试流；32 位：真实 `in.bmp` 插件源（糯米\|秒播） |
| 32 位实测 | 同一台 ONEPLUS A3010 安装 `armeabi-v7a` 包，`dumpsys package` 显示 `primaryCpuAbi=armeabi-v7a` |

> 说明：本轮后半程模拟器 `qemu-system-x86_64-headless` 崩溃后 HVF 持续返回
> `HVF Unknown error 0xfae94007`，无法再启动 Android 11 模拟器，因此除 `09-emulator11-home.jpg`
> 外的截图改用 arm64 真机在 1920×1080 电视视口下补测；ABI、代码路径与 APK 完全一致。

## 三、用例与结果

| # | 用例 | 结果 | 截图 |
| --- | --- | --- | --- |
| 1 | 冷启动 → 载入 50 站点 → 首页真实片单 | 通过 | 01-home.jpg |
| 2 | 首启无配置空态（品牌 + 导入配置） | 通过 | 08-setup-hint.jpg |
| 3 | 设置页总览：三栏布局、分组导航、运行状态卡 | 通过 | 02-settings.jpg |
| 4 | 详情页：跨站回退（豆豆|片单无详情 → 糯米|秒播） | 通过 | 03-detail.jpg |
| 5 | 详情页选集网格 / 换源 chip / 播放按钮 | 通过 | 03-detail.jpg |
| 6 | 播放器：HLS 起播 | 通过 | 05-player-playing.jpg |
| 7 | 播放器 HUD：金色进度、`−10:00` 剩余时间、暂停浮层 | 通过 | 04-player-hud.jpg |
| 8 | 播放器控制条：继续 / ±10 秒 / 倍速 / 返回详情，焦点金环 | 通过（本轮修复焦点崩溃） | 04-player-hud.jpg |
| 9 | 搜索页：当前源不可搜索 → 就地挑选可搜索源 | 通过（本轮新增） | 06-search-source-picker.jpg |
| 10 | 搜索页：自绘九宫格键盘输入 | 通过（本轮修复 IME 遮挡） | 07-search-keyboard.jpg |
| 11 | 32 位包（armeabi-v7a）冷启动到 50 站首页 | 通过 | 10-arm32-home.jpg |
| 12 | 32 位包播放真实插件源 + HUD 控制条 | 通过 | 11-arm32-player-hud.jpg |

## 四、本轮修复的问题

1. **首页 hero 被网格顶出屏幕**：hero 与分类 tabs 从 `LazyVerticalGrid` 内移到固定头部，滚动只作用于片单；hero 详情元信息补拉一次（片名/年份/地区/简介）。
2. **片名被挤出可视区**：电视墙只给一行高度，片名改为压在封面底部渐变上，角标移到右上。
3. **卡片只排 5 列**：`TvGridNavigation.columns()` 仍按旧的 148dp 计算，改为 128dp + 16dp 间距后 6 列。
4. **焦点环大于图片**：焦点环画在图片 Box 上，卡片本身 `showRing=false`。
5. **播放器控制条崩溃**：`SettingsButton` 丢弃了传入的 `Modifier`，且 50ms 内 `FocusRequester` 尚未入树 → 直接 `requestFocus()` 抛 `FocusRequester is not initialized`，进程被杀。现改为 `Modifier` 透传 + 最长 800ms 重试 + `runCatching`。
6. **插件本地代理端口为 -1**：`playerContent` 在拿不到宿主端口时返回 `http://127.0.0.1:-1/proxy?...`，ExoPlayer 报 `Malformed URL`。`SpiderProxyServer.rewrite()` 改用字符串解析，`port <= 0` 时重写到宿主回环代理；`resolvePlay()` 另加内层地址解包兜底。
7. **搜索页 IME 遮挡**：输入框聚焦时主动收起系统输入法，自绘键盘保持可见。
8. **搜索页不可搜索源死路**：就地列出 6 个可搜索来源，点选即切换，无需回设置换源。

## 五、已知未完成

- 部分 Guard 插件源的 `ProxyOrigin` 在本机抛异常，HLS 代理返回非 m3u8 内容（源侧问题，已保留 `NextMoviePlay` 日志便于定位）。
- `in.bmp` 中 3 个 drpy2 JS 源仍不可用（QuickJS 2024 内核语法 + async，未换引擎）。
- 64 位下部分插件源仍受 Guard 代理阻塞；32 位下同类源已验证可播，后续以 32 位为主要验收 ABI。

## 六、双 ABI 产物

| ABI | 文件 | 说明 |
| --- | --- | --- |
| arm64-v8a | `dist/NextMovie-0.6.12-dev-vc28-arm64-v8a.apk` | 64 位电视盒子 / 模拟器 |
| armeabi-v7a | `dist/NextMovie-0.6.12-dev-vc28-armeabi-v7a.apk` | 32 位盒子；Guard 插件 `libnmabi.so` 为 32 位 ELF，此 ABI 下插件链路更完整 |

32 位包实测：`home-snapshot-ready 1058ms` / `home-ready 2297ms`；进入详情 `detail-ready 4171ms`；
播放时插件给出**有效本地代理端口**（`127.0.0.1:37463/proxy?do=m3u8`，不再是 `-1`），ExoPlayer 正常起播，
HUD 显示 `00:29 / 1:53:55 / -1:53:26` 与「下一集：20261022-02」。截图见 `10-arm32-home.jpg`、`11-arm32-player-hud.jpg`。

## 七、最新上游（`05cf9d6`）复测：64 位端口问题是否仍在

上游提交 `05cf9d6 Overlap plugin startup with configuration loading` 让插件宿主在配置加载期间并行绑定
（`MainActivity` 里 `repo.startConfiguredPluginHost()` 先于 `setContent`），并移除了 `JarSpiderLoader`
的 `.guard` 载荷落盘。

复测环境：同一台 ONEPLUS A3010（`primaryCpuAbi=arm64-v8a`），安装最新 `arm64-v8a` 包，真实 `in.bmp` 配置。

| 观测点 | 修复前（`afa20e1`） | 复测（`05cf9d6`） |
| --- | --- | --- |
| 插件绑定 | 随站点切换到才绑定 | 启动即 `plugin-bind-request` → `plugin-bind-connected`（早于配置加载完成） |
| 播放代理端口 | `127.0.0.1:-1`（Malformed URL） | `127.0.0.1:38091`（有效端口） |
| 起播 | 失败 | ExoPlayer `prepare hls=true`，画面出帧 |
| HUD | 崩过（焦点未入树） | 正常：`00:32 / 1:53:55 / -1:53:22`、下一集、焦点金环 |
| 首页 | 分类 tabs 需等分类请求 | 首页 6 个分类 tab 与片单同屏，`home 2651ms items=20` |

结论：**64 位下 `port = -1` 的问题在当前最新代码上已不再复现**，真实插件源可正常起播。
截图：`12-latest64-home.jpg`、`13-latest64-player.jpg`、`14-latest64-hud.jpg`。

## 八、v0.6.13-dev（vc29）：收藏 / 独立历史 / 聚合优选 / 播放器换线

本轮把"聚合"从搜索页前移到**详情页**：进详情页即以片名并发检索多个可搜索源，按
「线路画质词（4K/蓝光/1080/高清/标清）+ 首集容器（mp4/m3u8）+ 剧集完整度 + 该源历史播放成功率
+ CMS 直连修正」打分排序（`core/match/SourceRanker.kt`），默认自动选中最高分源与最高分线路；
播放器侧把这一集的各源地址收成可切换清单（`PlaySlot`），菜单键呼出线路面板并按同分排序；
观看进度改为**按片名归一化去重**（`TitleMatcher.normalize`），换源/换线路后仍是同一条记录。

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 首页新增「收藏 / 历史」导航 | 通过 | `15-home-fav-history.jpg` |
| 详情页自动聚合 5 个源并默认选最优（`detail-partial 649ms sources=1` → `detail-ready 4642ms sources=5`） | 通过 | `16-detail-aggregate.jpg` |
| 详情页「☆ 收藏」写入 `files/favorites.json`（`site/vod/name/pic/remark/year/at`） | 通过 | 截图 + 落盘原文 |
| 收藏页网格、空态、点击回到对应源详情 | 通过 | `19-favorites.jpg` |
| 独立历史页：进度条、集名、源标签、相对时间、单条移除、清空全部 | 通过 | `20-history.jpg` |
| 多源聚合搜索：`love` → 106 条 / 12/12 源，按源分组 + 命中数 + 进度条 + 自动聚焦首张海报 | 通过 | `21-search-aggregate.jpg` |
| 播放器线路面板（`线路 · 5 个来源聚合`，↑↓ 选择 / OK 切换 / 返回关闭） | 通过 | `17-player-lines.jpg` |
| 线路切换：切到「电影天堂」后 `raw-url=https://play.dyttzyv.com/...m3u8`，从 01:06 续播，HUD 显示 `线路 2/5` | 通过 | `18-player-switched.jpg` |
| 32 位包（`primaryCpuAbi=armeabi-v7a`）启动 + 首页 | 通过 | `22-v7a-home.jpg` |

环境：Android 11 模拟器（API 30 / 1920×1080 / density 320 / abilist 含 arm64-v8a），
配置 `https://szyyds.cn/tv/x.json`（62 站，9 个可搜索影视源），当前源 `影视 | 量子[直连]`。
构建：`./scripts/build-tv.sh` →

- `dist/NextMovie-0.6.13-dev-vc29-arm64-v8a.apk`（sha256 `0026d683…c992ec9`）
- `dist/NextMovie-0.6.13-dev-vc29-armeabi-v7a.apk`（sha256 `b1a55337…723f03f7`）

已知边界：打分只用电台名/地址容器/集数/成功率等**可观测信号**，不读取视频码率；
同一部片各源集数不同时按集序号对齐，缺集的源不会出现在线路面板里；
`cms` 源搜索命中的别名（如「Love Live! 虹咲」）仍按标题相似度阈值 0.80 过滤，避免贴错片。
