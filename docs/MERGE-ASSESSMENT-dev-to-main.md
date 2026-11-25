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
