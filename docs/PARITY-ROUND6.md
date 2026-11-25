# 与 Marco 追平：第 1、2 步落地 + 多轮对比实测（Round 6）

同源 `https://9280.kstore.vip/newwex.json`，同一台 arm64 手机（1920×1080），冷启动、清 logcat 后逐轮采集。

## 已实现

1. **默认源能力探针**（`probeRealIds`）：取该源第一个分类页，判断返回的条目 id 是 `msearch:`（榜单重定向，不能出线路）还是真实 `vod_id`。
2. **启动预热线路源**（`prewarmLineSources`）：首页预热成功后，串行预热 1–2 个有线路能力的源并记 `prewarm-line-ok`。
3. **自动换源**：探针判定当前默认源 `realIds=false` 且存在 `realIds=true` 的源时，自动切默认源并持久化（`auto-site` 日志）。
4. **按实测耗时排序选源**（本轮新增）：每次详情成功记录 `cap_ms_<key>`，`lineScore` 用实测耗时扣分，越快的源越优先被聚合/预热。

## 实测数据

### A. 引导态（默认源复位成不产线路的 🐮 榜单站）
| 阶段 | 耗时 |
|---|---|
| `prewarm-ok 🐮` | 8802ms（仅 init） |
| `prewarm-line-ok 玩偶` | 1592ms |
| `cap-probe 玩偶 realIds=true` | 2469ms |
| `cap-probe 🐮 realIds=false` | 578ms |
| `auto-site 切到 玩偶` | 启动后约 14s |
| `home-ready` | 14261ms |
| 详情（玩偶源，5 条线路） | `detail-site-ready 5496ms` / `presented 6377ms` |

### B. 默认源为线路站（花卷），冷启动 3 轮
| 轮次 | home-ready | detail |
|---|---|---|
| R2 | — | `detail-presented 11022ms`（走了聚合兜底） |
| R3 | 21257ms | `detail-site-ready 18ms lines=1` / `presented 711ms` |
| 终轮（有首屏快照） | **1515ms**（Activity TotalTime 3261ms） | `detail-site-ready 141ms lines=5` / `presented 1704ms` |

### C. 对照 Marco（同机同源）
`MovieDetailTiming: cache=false queue_ms=2 engine_ms=2 source_ms=4138 parse_ms=7 total_ms=4166`，首页约 2–3s。

## 结论

- 详情：**1.7s（花卷） vs Marco 4.17s**，已追平并反超；玩偶源 5.5–6.4s，慢 3–4s，已通过 `cap_ms` 排序让它自动退位。
- 首页：有快照时 **1.5s**，无快照冷启 14–21s（Marco 2–3s）——**仍是最大差距**，瓶颈是插件 init（同 jar 首站 ~8–12s）+ 分类页请求，做的是「冷启首屏」，不是网络慢。
- 播放：**未追平**。花卷 4K 云盘线路点「立即播放」→ `PanDebug` GoProxy 在 37633 端口就绪 → 插件抛 `okio.RealBufferedSource.indexOf` 异常 → 约 40s 后回退详情页，**没有首帧**。云盘类线路的 resolve 链路仍然断的（需在 SpiderService 里对非 JSON/HTML 响应做容错 + 交给内置播放器解析直链）。
- 另外：首页「继续观看」卡片点击无响应（本轮多次点卡片都没进详情，只有精选海报可进）。

## 下一步（按收益排序）

1. 云盘线路 resolve 容错：插件返回非 JSON 时不整段抛异常，落日志并回退「直链解析/网盘扫码登录」路径，先把首帧打通。
2. 首页冷启首屏：把分类页首屏结果做磁盘快照 + 进程启动即读快照渲染（现在快照只在进程内存/较晚路径生效）。
3. 「继续观看」卡片补 CENTER/ENTER 点击。
