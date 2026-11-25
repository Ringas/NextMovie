# dev → main 合并评估（2026-11-25）

## 仓库状态

- `origin/main`：`3424f7f docs: clear README content, keep title only`
- `origin/dev`：`13e05ef perf(tv): align detail and plugin startup latency`
- 关系：main **是 dev 的祖先**，`main...dev` = `0 8` → **可 fast-forward，零冲突**
- 差异规模：8 个提交，92 文件，+5806/−770

| 提交 | 内容 |
|---|---|
| 1a8490b | 原生适配器、配置对齐、首页/详情即时化首版 |
| 0e81245 | 解码栈 P0-P2：DASH、软/硬解、恢复、DRM/弹幕骨架 |
| fdf744b | 软解重试重 prepare、默认策略、可选 ffmpeg AAR |
| ed4c551 | 左导航、首页简化、焦点修正 |
| 53367cf | IA 锁定：5 tab 外壳、6 段设置、遥控+键盘键位 |
| 0fb9413 | 全局 IA 应用（播放器/搜索/详情/设置/海报） |
| 78e99ab | dev 可编译、自动换线接线、解码自检面板（vc40） |
| 13e05ef | 详情与插件启动延迟对齐（性能 Pass 1） |

## 验证结果（本次实测）

- `:app:compileTv64DebugKotlin`：**成功**
- `:app:testTv64DebugUnitTest`：**通过**
- arm64 真机（`29d395e2`，源 `https://9280.kstore.vip/newwex.json`）：
  - 冷启动：`prewarm initOnly` 8.4s → 首页 `home-bg-refresh` 约 10s 内出 20 部
  - 详情：**元数据/简介 2.1s 就出**（TMDB 9.0 + 片源评分 9.0），比 vc40 明显改善
  - **线路仍然失败**：约 50s 后仍报「所有站点都没有返回这部片的可用线路」；日志 `plugin-source action=5 30664ms / 30506ms`（两个站点都顶到 30s 超时）

## 结论

`13e05ef` 解决的是「详情页先出画面」和「探速不再挡选集」，**没有解决「拿不到线路」**：

1. 详情仍默认跨 12 站 fan-out（`resolveAggregate` 未改候选数量），只是给主站 1.5s 先跑；
2. 主站 `detailContent` 本身 30s 超时，说明插件调用链（Guard 原生代理 / 站点 HTTP）没有打通；
3. 全局 `loadLock` 依然是全局锁，跨站点装载仍串行；
4. 失败仍不缓存，重试会重跑整轮。

## 建议

**结构上可以合**（零冲突、可编译、单测绿），但**不建议在「拿不到线路」修好之前把 main 当作可发布分支**。

推荐顺序：先在 dev 补 3 项再 ff 合入 main 并出 vc41：

1. 详情默认只查当前站（聚合折叠到「更多来源」手动触发，最多 4 站）；
2. 超时收紧：详情 8s / 搜索 5s，失败结果缓存 60s；
3. `loadLock` 拆 per-jar，避免 12 站装载串行。

---

## 2026-11-25 执行结果：已合并

用户选择「先补 3 项修复再合」。已完成并合入：

1. **详情只查当前来源**：`AppRepository.detailForSite()` + `DetailScreen` 进页单站解析；跨源比对改由「更多来源 / 查找更多来源」按钮显式触发，上限 4 站。
2. **超时与失败冷却**：单站详情预算 9s；聚合 15s（冷启动）/8s（已热身）/6s（其它）；`detailFails` 60s 冷却，避免重试风暴。
3. **per-jar 装载锁**：`JarSpiderLoader` 按 jar 分锁，同一 jar 串行（原生代理端口不撞），不同 jar 并行。

提交：`c029271 perf(detail): single-source first, short timeouts, per-jar load locks (vc41)`；产物 `0.6.25-dev / vc41`，双 ABI；38 个单测全绿。
合并方式：`main` ff 到 `c029271`（零冲突），`origin/main` 与 `origin/dev` 同步。

### 实测（arm64 真机，同一源）

| 场景 | vc40 | vc41 |
|---|---|---|
| 详情失败反馈 | 约 60s 后报「所有站点都没有返回」 | **约 2s** 报「当前来源没有返回可用线路」 |
| 手动「更多来源」 | 不适用 | 约 **20s** 给出「其它来源也没有返回」 |
| 失败重试 | 每次重跑 20+ 次插件调用 | 60s 内直接命中冷却，不再打插件 |

### 仍未解决（下一个独立问题）

`detailContent` 在插件侧直接返回空 → 宿主 `errorBundle("插件没有返回数据")`，所以「拿不到线路」**不是**调度/超时问题，而是插件调用参数或站点接口层的问题。下一步应：

1. 把 `reply.error` 原文打进日志（当前只记了异常类名 `SpiderException`）；
2. 用该站 `category` 返回的 `vod_id` 再调一次 `detailContent`，验证是否只有 `msearch:` 形态的 id 不被接受；
3. 若所有 id 都为空，转去核对插件 `detailContent` 的内部请求（是否需要 cookie / ext 参数）。
