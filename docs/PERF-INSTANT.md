# 秒开 / 详情快 / 海报评分分辨率（本轮）

## 首页秒开
- `home()` 改为 **Stale-While-Revalidate**：有磁盘快照立刻返回，网络后台刷新。
- `RequestCache.peek`：内存命中零等待。
- `SOFT_HOME_REFRESH_MS = 2min`：新鲜快照超时后后台软刷。
- UI 原有 `cachedHome` 首帧路径保留。

## 详情快
- 详情内存缓存 TTL **120s → 300s**（贴近 Marco 5 分钟量级）。
- 进详情即 `enrichPoster` 补海报/简介/评分，不挡 `aggregateDetail`。
- 列表 `initial` + `cachedDetailMetadata` 作 seed，简介/徽章先上屏。
- 聚合仍 partial 回调；StreamProbe 高度写入 `vod.resolution`。

## 海报 / 评分 / 分辨率
- TMDB `enrich`：补 poster、backdrop、overview、score、year。
- B站 Hit：解析 `media_score.score`，经 PosterCache 写入 `vod.score`。
- 聚合探针：4K/1080p 等写入 resolution，详情 `mediaBadges` / 源列表 badge 展示。
- 播放器：既有 `onTracksChanged` 真实分辨率与音轨徽章。

## 验证
- 冷启有快照：日志 `home-instant` / `home-snapshot-ready` 应 < 500ms。
- 详情：`detail-metadata-ready` / `detail-meta-enrich` / `detail-partial`。
- 评分芯片：TMDB Token 或 B站命中后出现「TMDB x.x / 片源评分」。
- 起播后顶栏出现 `1080p` / `Atmos` 等轨信息。
