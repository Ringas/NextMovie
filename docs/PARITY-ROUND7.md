# Round 7：①播放 ②首页冷启 ③继续观看

同机 arm64 手机（1920×1080），同源 `9280.kstore.vip/newwex.json`，`am force-stop` 冷启后采集 logcat。

## ① 播放链路（云盘线路）

先定位到底卡在哪一步，再改：

- 旧行为：点「立即播放」→ 插件返回异常 → 40s 后弹回详情页，没有首帧、没有原因。
- 实测捕获到的真实原因（两种源都复现）：
  - `花卷`：`line-play-fail ... SpiderException: java.lang.IllegalStateException: Expected BEGIN_OBJECT but was STRING at line 1 column 1`
  - `玩偶`：`line-play-fail ... SpiderException: [quark](auth) 还没有配置夸克 Cookie，请先去配置中心登录夸克`
- 结论：**这份配置里可播的线路全是夸克网盘盘源**，插件没有夸克 Cookie 时前台永远拿不到直链；不是解码器问题，也不是我们的 HTTP 层问题。

本轮改动：

1. 每条线路 15s 硬超时，超时/异常立刻走换线，不再让用户对着 loading 干等。
2. `explain()` 把插件异常翻成人话（ECONNREFUSED / 超时 / 被防爬返回网页 / 未登录网盘）。
3. 命中 `cookie/登录/auth` 类错误时，**自动打开「账户与网盘」页**（`PluginActivity`），引导扫码登录后重播。
4. 聚合线路排序改为**直链优先、云盘垫底**（`PlaySlot` 按线路名/地址特征判 pan），先让能播的先播。
5. 新增 `cap: line-play-fail / line-play-timeout` 日志，后续可按源统计。

**首帧仍未点亮**：需要在「账户与网盘」里完成夸克扫码登录（该页目前只列来源分类，下一步补一个明确的「夸克/阿里登录」入口）。

## ② 首页冷启（目标 <3s）

| 指标 | 改前 | 改后 |
|---|---|---|
| 首次出画 `home-instant` | 无此日志 | **502ms** |
| 首页就绪 `home-ready` | 14–21s | **1519ms** |
| Activity TotalTime | 3261ms | 3307ms（持平） |
| 切分类（第二次进入） | 每次重新请求 | **`cat-instant 2 items=70`（0ms）** |

改动：分类页结果按 `cat_<site>_<tid>` 落盘（6 小时），切 tab 先出快照再后台刷新；有快照时取消原先写死的 2.5s 空等（降到 800ms）。

## ③ 继续观看卡片

- 现象：卡片点了没反应。
- 原因：历史记录里的 `siteKey` 是旧源/已移除的源，`repo.siteByKey()` 返回 null，直接 toast 后 return（且之前连 toast 都很难被触发）。
- 改动：`owner` 为空时回退到当前源继续播，并提示「这条记录来自已移除的来源，改用「X」续播」，同时打 `continue-click` 日志。
- 实测：`continue-click vod=/vod-detail-id-96513.html site=花卷 ownerMissing=true` → 进入「正在解析播放…」→ 8s 内给出明确的失败原因，不再静默。

## 文件

- `MainActivity.kt`：单线路超时、失败人话化、登录引导、续播回退。
- `AppRepository.kt`：分类页快照读写。
- `HomeScreen.kt`：`home-instant` / `cat-instant`，去掉 2.5s 空等。
- `model/Vod.kt`：`PlayLine.isPanLine`。
- `DetailScreen.kt`：线路排序直链优先。
