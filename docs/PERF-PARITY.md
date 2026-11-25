# 与 Marco 的性能对齐方案（NextMovie）

## 现状对照

| 环节 | Marco | NextMovie（vc34） | 差距 |
| --- | --- | --- | --- |
| 首屏（暖/有缓存） | 直出磁盘缓存后后台刷新 | 快照 399ms 直出，1.06s 完成 | 持平 |
| 首屏（真冷） | 15s 硬超时 + 原生适配器通常 1s 内 | jar 预取已做，仍 24s（Init + Wex 远程配置 + Go 代理） | **差 20s+** |
| 详情 | 原生适配器直连 API，秒级 | 插件 detail 握手 14s（网盘型 Wex） | **差 10s+** |
| 换线/聚合 | 有 | 有（详情聚合 + 播放器换线） | 持平 |

## 差距根因（唯一根因）

NextMovie 忠实执行 CatVod 协议：`DexClassLoader` 加载 jar → `Init` → 调 `Spider.detailContent`。
Marco 放弃执行，改成"读常量 → 直连 HTTP"：
`shared/catpaw/drpy-guard-extractor.cjs` 解 zip/DEX 取 `api`、`signKey`、`userAgent`，
再由 `drpy-<family>-adapter.cjs` 拼请求、解析 JSON。二者许可证都是 GPL-3.0，可移植。

## 建议实施顺序（每步都可独立验收、可回滚）

1. **wex 家族（覆盖本例 95 站中的 12 站真源 + 全部 `csp_Wex*`）**
   - 移植 `drpy-guard-extractor.cjs` 的"只读常量"部分：zip 解包 → `config` 文本 / DEX 字符串表 → `api`、`signKey`。
   - 移植 `drpy-wex-extractor.cjs` + `drpy-wex-vod-adapter.cjs`：首页/分类/详情/播放四类请求。
   - 落点：`core/spider/native/WexNativeSpider.kt`，实现现有 `SpiderHost` 接口；
     `PluginRuntime` 里按 `site.api` 前缀路由，命中就走原生、未命中回退插件（保证"每个功能真实可用"）。
2. **apptt / appsx 家族**（旧测试源 x.json 的主力），复用同一套 extractor。
3. **缓存层**：原生结果直接进 `HomeSnapshotStore` + `RequestCache`，冷启动彻底无 Init。
4. 全部 `csp_*` 未覆盖的家族继续走插件路径（不追求 100% 覆盖，只保证常用源秒开）。

## 预期

- wex 家族站点：首页/详情 1s 级（与 Marco 持平），无需 Go 代理（网盘直链改由播放器直接拿 `url`）。
- 未覆盖家族：维持 vc34 现状（30s 预算 + 计秒提示 + 快照兜底），不会退步。
