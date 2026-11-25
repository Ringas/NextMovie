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

---

## 九、v0.6.14-dev（vc30）：真实码率探测 + 聚合二次筛选

两条主线同时落地：① 打分不再只靠线路名猜画质，改为**实测首集地址**（`StreamProbe`：Range 4KB 读 HLS 主清单
的 `#EXT-X-STREAM-INF` / `RESOLUTION` / `BANDWIDTH`，mp4 读 `Content-Range` 总大小；5s 超时、64 条 LRU 缓存、
不下载整片），命中 2160→+10 / 1440→+7 / 1080→+5 / 720→+2，再叠加 `BANDWIDTH/2500` 的码率加分；
② 详情页在聚合结果上加一排筛选：`全部 / 4K/2K / 1080P+ / 集数最全`，每项带命中计数，切筛选时若当前源被过滤掉会自动跳到首个可见源。

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 详情页聚合 badge 变成 `实测分辨率 · 集数`（如 `1080P · 全 42 集`） | 通过 | `23-detail-filter-all.jpg` |
| 筛选「1080P+ 2」：5 个源收敛到 2 个（电影天堂 / 非凡），当前源保持 | 通过 | `24-detail-filter-1080p.jpg` |
| 筛选「集数最全 3」：只留 42 集的 3 个源，量子实测仅 `608P` 被正确拉开差距 | 通过 | `25-detail-filter-most-episodes.jpg` |
| 播放器线路面板按分数显示实测分辨率与总分（`1080P 分26 最优` / `1080P 分25` / `608P 分22` / `全41集 分20`） | 通过 | `26-player-lines-probe.jpg` |
| 切到第 3 路（量子 `lzm3u8`）：`raw-url=https://v.lfthirtytwo.com/...index.m3u8`，从 00:08 续到 00:26，HUD `线路 3/5` | 通过 | `27-player-switched-probe.jpg` |
| 32 位包安装启动：`versionName=0.6.14-dev` / `primaryCpuAbi=armeabi-v7a` | 通过 | `28-v7a-home-vc30.jpg` |

环境：Android 11 模拟器 `emulator-5556`（API 30 / 1920×1080 / 320dpi / abilist 含 arm64-v8a 与 armeabi-v7a），
配置 `https://szyyds.cn/tv/x.json`（62 站）。日志：`detail-partial 1650ms sources=1` → `detail-ready 7783ms sources=5`。
构建：`./scripts/build-tv.sh` →

- `dist/NextMovie-0.6.14-dev-vc30-arm64-v8a.apk`（sha256 `54b2118d…bd30c13c`）
- `dist/NextMovie-0.6.14-dev-vc30-armeabi-v7a.apk`（sha256 `c5c59963…b73757ff`）

已知边界：CDN 拒绝 Range 或返回整片 `200` 时探测拿不到分辨率，按「拿不到不加分」退化为旧打分；
部分源只给媒体清单（无 `STREAM-INF`），面板上只显示集数不显示分辨率；探测对每源只打一次首集，切片中途换清晰度不会重测。

---

## 十、v0.6.15-dev（vc31）：首页冷启动对齐 Marco 的「1 秒」

对照 `Pilipala9919/MarcoMovie`（GPL-3.0，Flutter）后确认，它的首页快不是网络快，而是三件事：
① 首页只读**当前一个源**，`fetchHome` 结果落盘（`saveCachedSiteHome`），启动先渲染缓存、后台再刷新；
② 整个取数链包在 `runSourceOperation` 里，外层 `timeout(15s)`，超时不重试直接降级；
③ 配置里大量 `csp_*Guard` 在它这里走 `shared/catpaw/drpy-*-adapter.cjs` 的**内置适配器**，不下载远端 jar、不跑 DexClassLoader。

我们的三条慢路径按此修正：

| 改动 | 之前 | 现在 |
| --- | --- | --- |
| 首页快照 TTL | 5 分钟，过期就完全走网络 | 24 小时可渲染；10 分钟内直接命中，不再发请求 |
| 首页硬预算 | 无上限：30s callTimeout × `Retry.once` | 8s 预算；超时先用旧快照渲染，网络请求在共享 flight 里继续，下次进入即命中 |
| JS 脚本 | 每次冷启动重新下载 + eval | 按 URL 的 md5 落盘 `files/js-cache/*.js`，24h 复用 |
| 插件源失败提示 | 转圈到超时 | 明确提示「插件源要先下载并初始化，已转后台准备」 |

实测（Android 11 模拟器，配置 `szyyds.cn/tv/x.json` 62 站，当前源 `糯米` csp_NmyswvGuard）：

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 冷启动（快照 age=503s） | `home-snapshot-ready 407ms` → `home-ready 1060ms`，`home-cache 199ms`，**0 次网络请求** | `29-home-cache-budget.jpg` |
| 快照 age=1400s（过期） | 先用快照渲染，随后 `home 1205ms` 刷新成功 | 日志 `home 1205ms items=20` |
| 旧版同机对照（量子 CMS） | `home 1106ms`；过期快照会直接等网络，插件源最坏 60s+ | 本节日志对照 |

构建：`./scripts/build-tv.sh` →

- `dist/NextMovie-0.6.15-dev-vc31-arm64-v8a.apk`（sha256 `e1d3c38b…49903ded`）
- `dist/NextMovie-0.6.15-dev-vc31-armeabi-v7a.apk`（sha256 `b0d8f6b4…32dce874`）

已知边界：插件源**首次**仍要下载并初始化 jar（盒子端 10–30s 区间），这段无法靠缓存消除；
要真正做到 Marco 的 1 秒，需要对高频 `csp_*` 做本地原生适配器（Macro 走的就是这条），属于后续独立工程。

## 十一、vc34 新源（newwex.json）性能对照：冷/暖/详情

测试源 `https://9280.kstore.vip/newwex.json`（95 站，全部 type=3 guard 插件，根部共享 1 个 1.0MB jar）。
设备 emulator-5556（API 30，abi arm64-v8a，Go 代理走 x86_64 二进制）。

### 本轮改动（vc33 → vc34）

| 项 | 改动 |
| --- | --- |
| 配置根部 jar | 新增 `JarPrefetch`，配置导入成功/冷启动即后台落盘到 `files/spiders`，不再等首次点击 |
| 首页冷启动预算 | 无快照时 8s → 30s（有快照仍 8s） |
| 详情冷插件预算 | 9s → 30s；聚合首源冷插件 30s、其余 10s |
| 详情加载提示 | ≥4s 起显示"插件源首次初始化中，通常 10–30 秒…（已 Ns）" |
| 预热 | `loadConfig` 成功后补调 `prewarmCurrentSite`，与 `prefetchSpiderJar` 一起跑 |

### 实测（清空 files/spiders + files/home-snapshots 后冷启动）

```
jar-prefetch     1.0MB 落盘（10:10，早于首次取数）
plugin-obtain    action=2  24721ms
plugin-home-content         2006ms
prewarm-ok                  34244ms  items=20
home-timeout                30333ms  budget=30000 → 自动重试后成功
home-ready                  35041ms
```

### 实测（保留 jar + 快照，force-stop 后重启）

```
home-snapshot-ready  399ms
home-ready          1063ms     ← 与 Marco 的"1 秒"同量级
home-cache            10ms     age=35263ms items=20
plugin-service-ready  11ms
```

### 实测（暖插件下点进详情「两京十五日」）

```
plugin-queue action=4     17ms（实例已在内存，无 Init）
detail-partial         14434ms sources=1   ← 首个网盘型 Wex 源返回如此之慢
detail-ready           14882ms sources=3
```

结论：**冷启动的 24s 全部花在「下载 jar → DexClassLoader → Init → Wex 远程配置 + Go 代理起服务」**，
网络与解析只占 2s。暖态已做到 1s 级；详情页不再"永远转圈"（预算 30s + 计秒提示），
但首个网盘源的 detail 请求本身就要 14s，这是插件内部的远程握手，不是我们的超时问题。

### 与 Marco 的机制差异（决定性）

Marco 不下载、不加载、不执行这个 jar。`shared/catpaw/drpy-wex-*-adapter.cjs` 用本地规则
+ 直连源站 API 完成同样的事（`csp_bridge.dart` 的 `guardAdapterFor` 把 `csp_WexAppV7Guard`
等映射到 `wexappv7-v1`）。所以它拿任何 `csp_wex*` 站点都是"直接 HTTP"，没有 24s 冷启动。
本项目走的是 CatVod 原生 `DexClassLoader + Init`，冷启动成本无法靠预算/缓存消除。

## 十二、vc35 冷启动根因定位（WexProgress / Go 代理）

加了 `plugin-step` 分段计时 + 32MB logcat 缓冲后，23s 的去向终于看清：

```
plugin-artifact        1381ms    jar 本地已有（预取生效）
plugin-step jar-ready  1382ms
plugin-step native-extracted 1420ms
plugin-step dexloader  1430ms
plugin-step pre-init   1431ms
plugin-step init-entry 1507ms    ← Init.init 开始
WexProgress attach skip activity=null retry=0  msg=正在初始化本地代理
java.net.ConnectException: Failed to connect to /127.0.0.1:8964
WexProgress ... msg=正在读取扩展网盘配置
WexProgress ... msg=正在等待 Go 服务响应
plugin-step init-done  24958ms   ← Init.init 结束
```

结论：**24s 里 23s 是插件在 `Init.init` 内部启动自带 Go 代理（8964）+ 读取扩展网盘配置 + 等 Go 健康**。
下载只占 1.4s，dex 加载 0.03s。插件把进程内代理当作必需基础设施，所以每个 `:spider` 进程首启都要付一次。

插件在 `files/AiWex/` 落了三样东西（本次实测）：
`new_go_proxy_wex`（4.8MB 代理二进制）、`libdecjni.so`（2.1MB）、`siteconfig`（1.2KB 明文 JSON，含 40+ 站点主域名）。
所以第二次启动（AiWex 已存在）只需 10s（省掉二进制释放），仍然不是 1s。

`WexProgress attach skip activity=null` 只是插件想弹"初始化中"对话框：`:spider` 进程此刻没有前台 Activity，
弹不出来就每 500ms 记一条，不影响流程本身。

### vc35 的 UX 改动

- 首页加载 ≥6s 直接显示「正在准备这个源 · 约 10–30 秒 · 已 Ns」，不再裸骨架屏干等
- 超时不再甩「来源加载失败」卡片：进入 `warming` 态，每 5s 自动重试（最多 10 次），准备好后自动出内容
- 冷启动实测：`home-ready 38970ms`（无快照首启），暖启动 `home-ready 995ms`

截图：`34-vc35-warming.jpg`（8s 时）、`35-vc35-warming-done.jpg`（40s 后内容就绪）

## 十三、vc36 开机预热 + :spider 常驻（把 25s 挡在用户视线之外）

设计：插件实例（含 4.8MB Go 代理）只在 `:spider` 进程里存活一次，进程一死就要重付 10–25s。
所以把"付这笔账"的时机从「用户点开应用」挪到「开机」。

- 新增 `BootReceiver`（BOOT_COMPLETED / QUICKBOOT_POWERON / `com.nextmovie.WARM`）→ `BootWarm.run`
- `BootWarm`：`startService(:spider)`（避免预热完成即被回收）+ 恢复配置 + 预取 jar + `prewarmCurrentSite`
- `SpiderService.onStartCommand` 返回 `START_STICKY`：无人绑定时进程也留住

### 实测（模拟器，清空 jar/快照/AiWex 后只发预热广播，不开 UI）

```
boot-warm queued      161ms
jar-prefetch-done    1024ms
plugin-obtain action=2 8423ms      ← 代理+远程配置初始化（比首次冷启动的 25s 少 13s：AiWex 二进制已在）
prewarm-ok          12169ms  items=20
```

### 之后打开应用

| 场景 | 结果 |
| --- | --- |
| 预热后首次打开（无首页快照） | `plugin-obtain action=2 5ms`，`home-ready 2771ms` |
| 已看过一次，再 cold 重启 | `home-snapshot-ready 432ms`，**`home-ready 984ms`** |
| 详情（预热后点海报） | `plugin-obtain action=4 17ms`，`detail-ready 12480ms`（首个网盘源自身就要 ~10s） |

结论：首页在"开机预热 + 快照"下已经进入 1 秒级，与 Marco 同档；
详情慢在源自身的网盘直链接口（单源 9.8s），不是初始化，也不是我们的超时。

## 十四、vc37 · tvOS 原型整体改版（2026-11-25）

按用户上传的 `tvos-prototype-v1-standalone.html` 重做全局视觉与首页结构，功能链路未改。

### 改了什么
| 层 | 改动 |
|---|---|
| `ui/theme/Theme.kt` | 整份重写：石墨底 `#0B0C10`、半透明白材质（6/12%）、发丝线、四级文字透明度、动态主色 `DynamicAccent`（按作品 id 取 HSL 色相 + 相对亮度决定黑/白字）、圆角 10/14/20/28、CardWidth 117 / CardHeight 175 / Wide 210×118、ScreenPadding 36 |
| `ui/common/TvShell.kt`（新） | `StageBackdrop`（夜色 + 主色氛围光 + 颗粒）、`GlassTopBar`（居中玻璃 tab + 时钟 + 源状态）、`Billboard`（圆角巨幕 + kicker + 评分金 + 页指示点 + ←→ 翻页）、`Rail`（标题 + 副标题 + 两端圆钮 + 轨道）、`ChipRow`、`ScreenTopBar`（二级页统一 chrome） |
| `ui/common/PosterCard.kt` | 117×175、圆角 14、聚焦 1.075 由底边推近、3dp 主色描边 + 光晕、角标左上、片名/年/类型压图上、可选 3dp 进度条；聚焦即切换全屏主色 |
| `ui/common/ContinueCard.kt` | 改 16:9 横版 210×118，片名 + 集名 + 进度条压在图上 |
| `ui/home/HomeScreen.kt` | 结构改为「玻璃 tab → 巨幕 → 分类胶囊 → 横排轨道」；保留快照/预算/预热全部逻辑；分类行固定占位避免慢接口把海报行顶出屏幕；恢复「菜单键 → 设置」快捷键 |
| `ui/settings/SettingsScreen.kt` | 套 StageBackdrop，去掉品牌字标，左导航选中态改主色淡填充，右栏上下文卡沿用 |
| `ui/detail/DetailScreen.kt` | 去品牌字标、返回胶囊统一、海报 208×312、加载态改「正在读取剧集 + 聚合提示」 |
| 收藏 / 历史 / 搜索 | 去掉品牌字标行（改版时误删返回按钮，已补 `ScreenTopBar`） |

### 实机验证（emulator-5556 · API 30 · 1920×1080 · arm64-v8a）
| 项 | 结果 |
|---|---|
| 冷启动首屏 | 约 12–15s 出内容（插件源 95 站点，Go 代理预热后再次打开 <3s） |
| 首页巨幕 / 分类 | 正常，切换「电视筛选」正确拉回 6 部并重排巨幕 |
| 海报聚焦 | 3dp 主色环 + 1.075 推近 + 全屏主色与环境光同步变粉（见 41） |
| 详情页 | 能渲染标题/元信息/简介；该片源返回「所有站点都没有返回这部片的可用线路」+ 插件域名不可用提示（源侧问题，非 UI 卡死） |
| 设置页 | 三栏正常，导航选中/聚焦态正确，版本 v0.6.21-dev |
| 搜索 / 收藏 / 历史 | 均正常进入与返回，键盘、收藏 1 部、历史 1 条 3% 进度正确 |
| 播放器 | 该标题源侧无可用直链，本轮未能进入播放器截图 |

截图：`40-vc37-home.jpg`、`41-vc37-card-focus.jpg`、`42-vc37-detail.jpg`、`43-vc37-settings.jpg`、`44-vc37-search.jpg`、`45-vc37-favorites.jpg`、`46-vc37-history.jpg`

## 十五、vc38 · 对齐 Marco（V4）重写首页/详情/设置/播放器

**基准**：Marco TV（Flutter `flutter/lib/ui/tv/*`）实际界面与交互，取代此前被否决的 tvOS 原型稿。

| 页面 | 主要改动 |
| --- | --- |
| 主题 | `Theme.kt` 全量重写：Marco 6 套柔光预设（极光青翠/暮色流光/深海晨雾/暖金影院/星云紫夜/冰川银雾）、斜向渐变 + 四团软光 + 海报取色 glaze、强度 soft/standard/vivid；`Tokens` 换为 `#0F1A29` 深蓝底 + `#388FFA` 主色 + `#F5C247` 金色 |
| 顶栏 | 左品牌 `N Next Movie`、居中玻璃胶囊 4 tab（发现/搜索/片库/设置）、右侧状态与时钟 |
| 首页 | 分类胶囊行 + 切换片源/扫码快传、继续观看 268×150 横卡、精选片库海报墙（自适应 4/5/6 列） |
| 详情 | 整屏剧照 + 左侧压暗、chips（站点高亮 / TMDB 金标 / 年·地区·集数）、44sp 片名、主演、简介、行动胶囊（播放/收藏）、玻璃线路面板与选集网格（每 30 集分组、正倒序、全屏选集 Sheet） |
| 设置 | 按 Marco 左 328dp 导航 + 右滚动玻璃卡重写，7 段：「外观与柔光 / 订阅与片源 / 海报与图片 / 网络与解析 / 播放与体验 / 数据与存储 / 版本与更新」，全部设置项换成 NextMovie 真实能力（柔光、订阅、TMDB、缓存、DNS、连播、预热、清空收藏/历史/缓存） |
| 播放器 | 底部玻璃 sheet「播控与音画设置」：传输控制 + 进度、播放倍速 0.75–2.0、画面比例（适应/裁切/拉伸）、播放线路（按聚合顺序 + HLS/角标，可手动切） |

**新增行为（对齐 Marco）**：`PlaybackSession` 把 ExoPlayer 与 PlayerView 跨页面复用——从播放页按返回键退回详情页时不销毁播放器，详情页把同一块 PlayerView 铺在剧照层继续播放（回到首页/搜索/设置才暂停），播放进度仍按片名记账，与线路无关。

**实机验证（29d395e2 / 1080p 电视输出）**
- 冷启动 → 首页出画：`home-cache age≈248s` 命中快照，首页 2.3s 内可见（含源预热 `prewarm-ok 2322ms`）。
- 首页、设置页、详情页三屏截图见 `50-vc38-home-marco.png`、`51-vc38-settings-marco.png`、`52-vc38-detail-marco.png`。
- 设置页在 420dpi 手机上高度不足以放下 7 段，已改为左栏可滚动。
- 详情页聚合正常出 chips/剧照/选集；源侧线路显示「待补链」，属源站地址失效（旧问题，与 UI 无关）。
- **未验证**：从播放器退回详情页继续播放（测试机锁屏需要人工解锁，本轮无法自动化点播），待解锁后补测。

## 十六、vc39 · 完全按 Marco 外壳结构重排

**原则**：不再保留此前自创的页面骨架。所有主页面都收进一个 Marco 式外壳 `TvLivingRoomShell` 的同构实现里。

| 结构项 | Marco | NextMovie vc39 |
| --- | --- | --- |
| 外壳 | `Scaffold` + 柔光背景 + `_buildFloatingTopBar()` + `Expanded(switch(tab))` | `MainShell`：`AmbientBackdrop` + `FloatingTopBar` + 内容区，切页只换内容 |
| 顶栏 | 品牌左 / 居中玻璃胶囊 tab / 右状态与时钟 | 同构；窄屏（<1000dp）自动隐藏品牌与状态，把宽度让给 5 个 tab |
| 主标签 | 发现 / 直播 / 搜索 / 片库 / 设置 | 发现 / 搜索 / 片库 / 网盘 / 设置（点播应用用「网盘」替掉「直播」） |
| 片库 | 分段胶囊「观看历史 / 我的收藏」+ 清空 + 海报网格（历史卡带进度与「继续」） | `LibraryScreen` 同构实现，历史/收藏合并到一页，不再分成两个独立页面 |
| 网盘 | 设置里「4K 网盘」段 + 详情页「云盘扫码管理」 | 新增「网盘」标签页：能力归属说明 + 支持的网盘清单 + 打开来源账户页 + 扫码快传 |
| 设置 | 左 328dp 七段导航 + 右滚动玻璃卡：外观 / 片源 / 4K 网盘 / WebDAV / 播放 / 备份 / 更新 | 同序同名七段；片源段内合并订阅、海报（TMDB）、DNS |
| 详情 | 独立路由压在 shell 上 | 同（`Route.Detail`） |
| 播放器 | 独立层 + 底部播控 sheet | 同（`Route.Player` + `PlayerSheet`） |

**结构收益**：顶栏、柔光背景、焦点取色不再随页面重建；详情/播放器作为覆盖层存在，为「退回详情页仍在播」留出天然层级。

**实机截图**：`60-vc39-shell-home.png`、`61-vc39-shell-library.png`、`62-vc39-shell-netdisk.png`、`63-vc39-shell-settings.png`（29d395e2，1920×1080 输出）。

**遗留**：设置左栏在 731dp 宽的手机上超出可视高度（电视 1080dp 高度下正常），左栏已可滚动；详情页线路仍显示源侧「待补链」。
