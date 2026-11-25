# 为什么同一个源 Marco 快、NextMovie 慢

测试条件：同一台 arm64 设备、同一份 `https://9280.kstore.vip/newwex.json`（95 个站点，全部复用同一个 `spider` jar）、同一部片子《后会友期》。
代码基线：`dev@78e99ab`（vc40）；Marco 对照 `android-tv` 的 `SourceController / CspEngine / DetailScreen / DetailCache`。

## 一、实测数据（vc40，arm64 真机）

冷启动到首页可点：

| 阶段 | 耗时 |
|---|---|
| `plugin-service-create` → ready | 7ms |
| 首个站点 `Spider.init()`（含 Guard 原生库 + Go 代理） | **7941ms** |
| `homeContent` | 989ms |
| `prewarm-ok` | 9228ms |
| `home-ready`（UI 可交互） | **10103ms** |

点一部片子进详情（一次详情页触发的插件调用）：

| 站点事件 | 排队等待 | 取实例 | 实际调用 |
|---|---|---|---|
| 玩偶 | 2ms | 715ms | 578ms |
| 花卷 | — | 5566ms | 302ms |
| 观影 | — | **16065ms** | — |
| 七味 | 8099ms | 6105ms | — |
| 盘库 | 6531ms | 11768ms | — |
| 木偶 | 8528ms | 10862ms | 403ms |
| 主站（🐮） | 8099ms | 16091ms | **30480ms（超时）** |

结果：`detail-ready` 从未出现，约 60s 后页面落到「所有站点都没有返回这部片的可用线路」。

## 二、根因（按影响排序）

### 1. 详情页默认「打全网」——把 1 次调用变成 20+ 次

`AppRepository.resolveAggregate()` 一进详情就并发打 **12 个站点**，每站两段：`search`（action=4）→ `detail`（action=5），再对每条线路首集做一次 `StreamProbe` 拉 HLS 主清单。

Marco 的 `DetailScreen` 只用 `video.lines`，也就是**当前选中站一次 `detailContent`**，前面还有 `DetailCache`（24 条 / 1MB / TTL）。同样一部片子，Marco 是 1 次调用，我们是 20+ 次。

### 2. 站点级插件实例是「懒加载 + 无缓存命中」

95 个站点共用同一个 jar，但每个站点是**独立的 `Spider` 实例**，`spider.init(app, ext)` 会各自拉一套 Guard 原生库与内置 Go 代理。实测单站初始化 **0.7s–16s**。

- 首页只碰默认站 → 一次 8s，还能忍；
- 详情一次要 12 个站 → 12 份初始化，全部要付。

Marco 的 `SourceController.engine()` 把引擎按 `site.key` 缓存在 `MainActivity.engines`（TV 上限 14 个），启动时预热选中站，之后切页面是 0 成本复用；凑满上限才淘汰。

### 3. 全局 `loadLock` 把并行变串行

`JarSpiderLoader.obtain()` 里是一把 **全局 `Mutex`**：

```kotlin
private val loadLock = Mutex()
suspend fun obtain(site: Site) = loadLock.withLock { obtainInner(site) }
```

所以外层 `Semaphore(4)` 的并发在装载阶段退化成 **1**。12 个站点的 5–16s 初始化被首尾相接串起来，日志里的 `plugin-queue 8099ms / 6531ms / 8528ms` 就是排队等这把锁。

### 4. 超时给得太宽 + 失败不记

- 主站详情超时 30s（未热身时），其它站 10s；
- 失败不缓存，`retry` 会原样再跑一遍这 20 次调用；
- 插件进程还有一把 85s 的 watchdog，超时直接 `killProcess` → 整个 `:spider` 冷启动，所有已初始化实例全丢（这也是上一轮反复看到 `jar-ready → native-extracted → dexloader → pre-init` 的原因）。

Marco 单站失败就直接报错，不会为了「聚合」把整机拖住。

### 5. 首页也走了两段式 + 兜底

`ACTION_HOME` 先 `homeContent(true)`，若 `list` 为空再补 `homeVideoContent()`，仍为空再按第一个分类兜底一次 `categoryContent`。默认站首页实测 `homeContent` 989ms 尚可，但这段逻辑叠加在 8s 的初始化之后，就成了 10s 首屏。

### 6. 顺带纠正上一轮的两个误判

- 「首页 20 部秒开」其实是 **stale cache**（日志 `home-instant 45ms age=249s`），不是真实速度；冷启动是 **10.1s**。
- 模拟器上详情失败还有一层原因：`emulator-5556` 是 **x86_64**，而 Guard 插件的 `.so` 只有 `EM_AARCH64`，`dlopen` 直接失败（`is for EM_AARCH64 (183) instead of EM_X86_64 (62)`）。所以**模拟器上永远测不出插件源的真实表现**，插件源必须在 arm 设备上验证。

## 三、Marco 与 NextMovie 的结构对照

| 维度 | Marco | NextMovie（现状） |
|---|---|---|
| 插件进程 | 同进程，伪造 `Application` 上下文 | 独立 `:spider` 进程 + Messenger |
| 实例生命周期 | 按 `site.key` 缓存，TV 上限 14，启动预热选中站 | 按 `runtimeKey` 缓存，但每站点首次仍要 5–16s 初始化 |
| 装载并发 | 引擎创建按站点去重，`QueuedSourceEngine` 只串行同一站点 | 全局一把 `Mutex`，跨站点也串行 |
| 详情 | 单站 `detailContent` + `DetailCache` | 12 站 ×（search+detail）+ 线路探速 |
| 代理 | 全局一个 native proxy，端口同步给插件 | 每个站点实例各起一套，端口易撞 |
| 失败处理 | 单站直接报错 | 聚合到底，最后给「所有站点都没有线路」 |

## 四、修复方案（建议按顺序落地）

1. **详情默认只查当前站**：进页先 `detail(当前站)` 直出线路与选集（目标 < 2s，热身时 < 1s）；「其它来源」改为用户点「换线路 / 更多来源」后再后台补，且最多 4 站。
2. **拆锁**：`loadLock` 改成 **per-jar 锁**（同一个 jar 串行创建实例，避免原生代理端口互撞），不同 jar 真并行；`obtain` 结果按 `runtimeKey` 强缓存，失败也缓存 60s，避免重试风暴。
3. **超时收紧**：详情单站 8s、搜索 5s、探速 2s；整体聚合预算 12s，超预算直接用已拿到的结果。
4. **预热**：启动后除默认站外，预建「最近使用 / 收藏的 3 个站」；详情首帧渲染完成后，空闲时再补聚合。
5. **去掉 85s 杀进程 watchdog**，改为单请求取消 + 实例重建，不让一个慢请求带走整个插件进程。
6. **首页**：优先 stale cache 立即渲染（已有 `home-instant`），真实刷新放后台并限制为 `homeContent` 一次，不再链式兜底。

预估：首页首屏 10.1s → 1–2s（缓存命中）／首次约 9s（唯一一次初始化）；详情 60s+失败 → 1.5–3s 出线路，聚合在后台补齐。
