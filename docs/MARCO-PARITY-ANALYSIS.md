# NextMovie vs Marco：同源、同片、同机的耗时差异（2026-11-25）

测试条件（两端完全一致）：

- 源：`https://9280.kstore.vip/newwex.json`（95 个站点，**全部 type=3 / csp 插件，且共用同一个 spider jar**）
- 设备：`29d395e2`（arm64，Android，同一台手机，冷启动 `am force-stop` 后 `am start`）
- 片：`小城良方`（Marco 侧）/ `饥饿游戏：收获日出`（NextMovie 侧，来自同一个 `rank_list_movie` 榜单，id 均为 `msearch:<豆瓣 subject id>`）
- 对照程序：Marco Movie `0.6.2 android-tv`（GitHub Release 官方包，装在同一台机器，导入同一源）

## 一、实测数字

### Marco

| 环节 | 数值 | 来源 |
|---|---|---|
| 冷启动到首页有内容 | ≈2–3s（`Displayed +983ms` 后内容即出） | 截图 + ActivityManager |
| 详情（含简介 + 线路 + 29 集选集） | **4166ms** | `MovieDetailTiming: cache=false queue_ms=2 engine_ms=2 source_ms=4138 parse_ms=7 present_ms=17 total_ms=4166` |

关键是 `engine_ms=2`：插件引擎**早就初始化好了**，4.1s 全部花在一次插件调用上（`source_ms=4138`），线路来自 `💓花卷┃4K💓`，不是默认的 🐮 站。

### NextMovie（同一台机器、同一源）

| 环节 | 数值 | 来源 |
|---|---|---|
| 首页（有磁盘快照） | **38ms**（`home-instant age=50s items=20`），后台刷新 1.1s | `NextMovieTiming` |
| 插件首次初始化 | 7.6–7.9s（`plugin-step init-done 7704ms`） | 同上 |
| 详情：当前源 `detailContent` | **0ms 返回空** | `detailContent blank for id=msearch:36915988` |
| 详情：跨源找线路（6 源并发） | 全部 fail（玩偶 1804ms、观影/七味/虎斑/盘库 ≈4500ms 超时、花卷 15007ms 超时） | `agg-site … fail` |
| 结果 | 页面上写「全部来源都没有返回这部片的可用线路」 | 截图 `det3.png` |

## 二、差异定位（三条，按影响排序）

### 1. `msearch:<id>` 是「搜索重定向」，不能直接喂 `detailContent`（决定性）

配置里所有榜单/首页条目的 `vod_id` 都是 `msearch:36915988` 这种形态。它的语义是「到这一站按片名搜」，**不是**这一站详情数据的 id。

- 旧代码：`detail(site, vod.id) → detailContent(listOf("msearch:36915988"))` → 插件同步返回 `""`（0ms，无异常），宿主转成「插件没有返回数据」。
- Marco 的做法：`Cms.searchTitle(video)` 把 `msearch:` 后面的内容拆开，跳过纯数字（豆瓣 id 属于元数据，不是片名），拿到片名后走**全站搜索**；搜索命中才有真正的 `vod_id`，`detailContent` 才可用。它的详情页上那行「正在从「❤️花卷｜4K❤️」加载播放线路…」就是全站搜索的过程态。

**已改**：`SpiderService.ACTION_DETAIL` 里识别 `msearch:`，先用片名（新增 `KEY_ARG2` 传片名，`SpiderHost` 已带过去）做 `searchContent`，拿到真实 id 再 `detailContent`；`AppRepository.resolveAggregate` 对 `msearch` 条目跳过「当前源按 id 查详情」，直接进全站搜索。

### 2. 每个站点一个 `Spider` 实例 ⇒ 第 N 个站点要再付一次初始化，且同 jar 串行

我们的 `JarSpiderLoader.instances[site.runtimeKey]` 是**按站点**缓存实例，`spider.init(app, ext)` 也按站点重跑；`loadLocks` 按 jar 分锁，同一份 jar 的 95 个站点本质上是**排队**初始化。

所以 6 个候选源并发出去，实际是 6 次 ≈8s 的初始化串行排队：第一个 4.5s 超时、第二个 4.5s 超时、花卷排到 15s 才轮上，早已超时。

Marco 侧 `CspEngine` 同样是「一站点一实例」，但它的 `engine_ms=2` 说明目标源的引擎在进详情之前就已经建好了（它在首页阶段就把正在用的源预热了），所以详情只花一次调用时间。

### 3. 默认源选错：🐮 站的 detail 恒空，花卷才出线路

同一份配置里，`🐮【免费分享】🐮 (csp_NewDouBanGuard)` 的 `homeContent/categoryContent` 正常（299ms），但 `detailContent` 对任何 id 都返回空、`searchContent` 返回 null。Marco 用的 `💓花卷┃4K💓 (csp_AiNewHuaJuanGuard)` 是能出线路的站点（29 集、夸克原画）。

我们默认选的是配置里的第一个站点（🐮），于是「首页好看、详情死掉」。

## 三、结论：慢的不是插件，是路由和预热

- 同一个 jar、同一份配置、同一台机器，插件单次调用在两边都是 **0.3–4s 量级**（category 299ms、home 1.1s、Marco detail 4.1s）。
- 我们「首页慢」的表象早已消失（磁盘快照 38ms）；真正剩下的慢全部来自**详情阶段**：把 `msearch` 当普通 id、每站重复初始化、默认源不可用。

## 四、下一轮要做的（按收益排序）

1. **默认源改为「可出线路」的源**：配置加载后，用一次 `categoryContent` + 一次 `searchContent` 的能力探针（各 2s 预算）排除 `detail` 恒空的站点；`🐮` 这类只做榜单的站点降级为「发现源」，详情/线路一律走能用的源。
2. **启动预热 1–2 个「线路源」**，而不是只预热默认源（Marco 的 `engine_ms=2` 就是这么来的）。同一 jar 的 `Init` 已按 jar 复用，第二站点只需要 `spider.init(ext)`，实测应在 1–3s。
3. **全站搜索并发只留 3 个同 jar 候选**，其余错峰；同 jar 串行是物理事实，6 并发只是把超时叠在一起。
4. 详情页 UI 改成 Marco 的进程态（「正在从「花卷｜4K」加载播放线路…」），避免把「等待」显示成「失败」。

## 五、本轮已落地的改动

- `SpiderService.ACTION_DETAIL`：`msearch:` → 按片名 `searchContent` → 真实 id → `detailContent`；失败原因、id、class 全部进日志（`NextMovieSpider` / `NextMovieDiag`）。
- `SpiderHost.detail()`：把片名作为 `KEY_ARG2` 一并下发。
- `AppRepository.resolveAggregate()`：`msearch` 条目跳过当前源的 id 详情；候选改为全并行（6 permits）、去掉 1.5s「让当前源先跑」的排队、跨源预算 4.5s、逐站打 `agg-site … ok/fail …ms`。
- `DetailScreen`：当前源拿不到线路时不再报错，自动转入全站搜索并在页面上显示进程态文案。
