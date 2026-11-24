# TMDB 接入说明

## 1. 定位

TMDB 在本项目里是**增强层**，不是骨架：

- 海报墙与列表**默认用源站 `vod_pic`**，保证零成本、零错配；
- TMDB 只在**置信匹配**时替换海报、补简介/评分/背景图；
- 未配置 Token 时，全流程照常工作。

## 2. 认证

- 使用 v4 **Read Access Token**（`Authorization: Bearer <token>`），由用户在设置页填写；
- **不内置任何 Key**，仓库与安装包都不包含；
- `apiBase` / `imageBase` 可配置（`TmdbClient` 字段），便于指向自建镜像。

## 3. 匹配策略（`TmdbMatcher`）

| 条件 | 分数 | 结果 |
|---|---|---|
| 归一化标题全等 + 年份一致 | 1.00 | 采用 |
| 标题全等、源站缺年份 | 0.90 | 采用 |
| 一方包含另一方且长度差 ≤ 40% + 年份一致 | 0.80 | 采用（临界） |
| 标题全等但年份冲突 | 0.55 | 拒绝（很可能是翻拍） |
| 编辑距离相似度 ≥ 0.82 且年份一致 | 相似度 | 采用 |
| 其余 | 0 | 拒绝，回退源站图 |

归一化：去空白、标点、书名号、`第N季` / `Season N` / `S01` 后缀，转小写。

> 宁可没有海报，也不要贴错海报。错配在电视上极其刺眼，而且会误导用户点进错误的剧。

## 4. 请求与缓存

| 项 | 取值 |
|---|---|
| 搜索 | `/3/search/multi?query=&language=zh-CN&page=1`，只看前 8 条候选 |
| 详情 | `/3/{movie|tv}/{id}?language=zh-CN` |
| 海报尺寸 | `w500` |
| 背景尺寸 | `w1280` |
| 触发时机 | 首页首屏 4 张 + 滚动补 3 张（并发 2）；详情页 1 次 |
| 失败策略 | 全程 `runCatching`，失败静默回退，不阻塞页面 |

已实现：磁盘缓存 `PosterCache`（单 JSON、2000 条、7 天 TTL、按写入时间淘汰）；首页只对可见区做补图，避免滚动风暴。

## 5. 没有 Token 怎么办：L0 + L1

不填 Token 时链路自动走 **L0 源图升级 → L1 B站 PGC → 源图兜底**，详情页仍能有清晰海报与简介。

### L0（零请求）

只改写 URL 尺寸档位，不换图源，因此不会错配：豆瓣 `s/m → m/l`、TMDB `w185 → w500/w780`、B站去 `@` 缩放后缀、百度 BCE 去 `?x-bce-process=`。规则见 `core/poster/PosterUrlRules.kt`，可单测、幂等。

### L1（B站 PGC，无 Key）

- `api.bilibili.com/x/web-interface/search/type?search_type=media_ft&keyword=<片名>`，无需登录；
- 返回 `cover` 竖版海报、`desc` 简介、`pubtime` 年份、`media_score.score`；番剧/纪录片另有 `eps[].cover` 作为 16:9 背景；
- 与 TMDB 共用 `TitleMatcher`（阈值 0.80），年份冲突直接拒；
- **频控**：串行 + 300ms 间隔 + 随机 `buvid3` cookie（无 cookie 会 412）；412/429 后全局冷却 10 分钟；MISS 负缓存 5 分钟；
- **覆盖边界**：进口电影、番剧、纪录片命中率尚可；国产剧/国产电影常 0 结果（版权不在 B站），此时保留 L0 结果。

### 为什么不用别的免 Key 源

| 方案 | 结论 |
|---|---|
| 豆瓣 `subject_suggest` | 已 302 到 `sec.douban.com` 风控页，非公开接口，默认不启用 |
| 第三方「免 Key TMDB 镜像」 | 违反 TMDB ToS，且随时失效，不做 |
| AniList GraphQL | 无 Key 可用，但只覆盖番剧（P2 作为 L2） |
| Bangumi `api.bgm.tv` | 实测 12s 超时不可达 |
| 图片代理 | 只解决防盗链与体积，不解决「没有好图」 |

## 6. 合规清单（上线前必须核对）

1. 界面或关于页展示：`This product uses the TMDB API but is not endorsed or certified by TMDB.`（已在 `strings.xml` 的 `tmdb_attribution`）；
2. 不内置 Key，不代用户注册账号；
3. 图片与元数据缓存设置容量上限与过期策略，符合 TMDB 当前条款；
4. 不把 TMDB 数据用于训练、转售或二次分发；
5. 商用/上架前重新阅读最新 Terms of Use。

## 7. 可达性

`api.themoviedb.org` 与 `image.tmdb.org` 在部分网络下不稳定。建议：

- 设置页暴露 `apiBase` / `imageBase` 高级项（P1）；
- 自建镜像只做**转发与缓存**，不改内容；
- 连续失败 3 次后本次会话自动降级为「纯源站海报」，避免每张卡都等超时；
- B站 偶发 412 属于正常频控，冷却期内静默用 L0 结果，不弹错。
