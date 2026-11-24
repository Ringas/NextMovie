# Next Movie

> 本轮电视与速度优化：当前开发版 `0.6.12-dev`，包含大屏缩放、遥控焦点、手机扫码导入、插件账户入口与首页/详情缓存。见 [电视交互与性能验证](docs/TV-REMOTE-PERFORMANCE-2026-11-24.md)；玩偶线路点播弹码与速度复测见 [专项记录](docs/WOGG-PLAYBACK-2026-11-24.md)。登录后本地视频服务转发修复见 [代理回归记录](docs/PROXY-PLAYBACK-2026-11-24.md)。

> 本地兼容修复（2026-11-24）：已在支持 ARM 转译的 Android 11 模拟器验证四份目标配置中的代表性来源，包含详情、选集、解析和实际起播。实时入口与快照测试有明确区别，见 [兼容验证记录](docs/COMPATIBILITY-2026-11-24.md)。下文版本段落为历史记录，当前能力以源码和本轮验证范围为准。

一款**独立实现**的 Android TV 播放器外壳：协议对齐 TVBox / CatVod 生态，界面走现代大屏设计，海报墙以「源站为准、TMDB 增强」的方式工作。

> 本项目只提供播放与内容管理工具：**不内置、不提供、不托管任何影视资源、订阅地址、直播源或账号权益**。内容与配置需由使用者自行合法获取。

- 许可证：GPL-3.0-only（见 `LICENSE`）
- 状态：v0.2 可用版（协议层 + JAR 宿主 + 首页/详情/播放 + 搜索 + 观看历史/续播 + 三级海报链路）
- 验证：`./gradlew :app:assembleDebug` 可产出可安装 APK；34 个单元测试全部通过；本地假 CMS 跑通「配置 → 首页 → 详情 → 线路/选集」

---

## 1. v0.1 做了什么

| 模块 | 状态 | 说明 |
|---|---|---|
| 配置解码 | ✅ | JSON / gzip / BOM / `xxxxxxxx**`Base64 / `2423` hex+AES-CBC |
| 多仓配置 | ✅ | `urls` / `storeHouse` / `depots` 递归（深度 ≤ 2，上限 64 仓） |
| 相对路径 | ✅ | `./`、`../` 按配置地址解析，`ext`/`jar` 可用相对写法 |
| CMS 站点 | ✅ | type 0（XML 兜底解析）、type 1/4（JSON），首页/分类/详情/搜索 |
| JAR 插件 | ✅ | `csp_` + DexClassLoader + 自有宿主 ABI；主进程内加载 |
| JS / PY / Node 插件 | ⛔ | 明确抛错，不静默失败（P1/P2 接入 QuickJS / Chaquopy） |
| 网页解析（parse=1） | ⛔ | 只支持直链，需解析的线路会提示 |
| 首页海报墙 | ✅ | 焦点驱动沉浸背景 + 横向海报 + 聚焦放大/描边；可见项后台异步补图 |
| 详情页 | ✅ | 海报/元信息/简介/线路切换/选集 |
| 播放 | ✅ | Media3 ExoPlayer 直链 + 自定义 Header |
| 海报 L0 升级 | ✅ | 源图 URL 尺寸改写（豆瓣 s→m/l、TMDB w185→w500/w780、B站去 `@` 后缀），零请求 |
| 海报 L1 兜底 | ✅ | B站 PGC `media_ft` 搜索（无 Key）+ 源图已够清晰时自动跳过 |
| TMDB 增强 | ✅ | 保守匹配（标题归一化 + 年份），只在确信时替换海报；需自备 Token |
| 搜索 | ✅ | 自绘九宫格键盘（纯遥控器可用）+ 输入框（接电视输入法）；6 列结果栅格 |
| 观看历史 / 续播 | ✅ | 每 5 秒 + 退出时落盘；首页「继续观看」行、详情页「继续观看」按钮、播放器自动 seek |
| 焦点恢复 | ✅ | 首页返回时回到上次停留的海报位置 |
| 背景交叉淡入 | ✅ | Hero 背景 300ms Crossfade |
| 收藏 / 分类页 / 直播 | ⛔ | P1–P3 |

## 2. 分层

```
UI (Compose for TV)
  └─ AppRepository            应用门面：配置、站点、请求、增强
       ├─ ConfigRepository    配置加载（单仓/多仓/相对路径）
       ├─ CmsSiteClient       CMS 通路（type 0/1/4）
       ├─ SpiderHost          插件调度（按 api 前缀）
       │    └─ JarSpiderLoader  DexClassLoader + 自有宿主 ABI
       ├─ TmdbClient          TMDB 搜索/详情/图片（可选，需自备 Token）
       └─ PosterResolver      海报链路：L0 URL 升级 → TMDB（有 Token）→ B站（无 Key）→ 源图兜底
            ├─ PosterUrlRules  L0 纯函数规则
            ├─ BilibiliPoster  L1 搜索 + 严格标题/年份校验（带频控与负缓存）
            └─ PosterCache     本地磁盘缓存（7 天 TTL，2000 条上限）
```

关键设计：**协议层独立实现，宿主 ABI 单独存在**。`com.github.catvod.crawler.Spider` 是给第三方 JAR 反射用的类，包名与方法签名必须与生态一致；除此之外不含任何第三方源码。

## 3. 目录

```
app/src/main/java/com/nextmovie/
├── core/
│   ├── config/     ConfigDecoder / ConfigModels / ConfigRepository
│   ├── cms/        CmsSiteClient
│   ├── spider/     SpiderHost / JarSpiderLoader
│   ├── tmdb/       TmdbClient / TmdbMatcher
│   ├── poster/     PosterResolver / PosterUrlRules / BilibiliPoster / PosterCache
│   ├── history/    HistoryStore（JSON 落盘、上限 120 条、续播判定）
│   ├── match/      TitleMatcher（统一归一化与打分）
│   ├── model/      Vod / VodDetail / PlayResult / VodParser
│   ├── net/        Http
│   └── AppRepository.kt
├── ui/
│   ├── theme/      设计令牌 + Material 主题
│   ├── common/     PosterCard / PosterImages（Coil 请求 + Referer）
│   ├── home/       HomeScreen
│   ├── search/     SearchScreen（键盘 + 结果栅格）
│   ├── detail/     DetailScreen
│   ├── player/     PlayerScreen
│   └── settings/   SettingsScreen
└── MainActivity.kt / NextMovieApp.kt
app/src/main/java/com/github/catvod/crawler/Spider.kt   # 宿主 ABI
```

## 4. 构建

要求：JDK 17、Android SDK 35（Platform 35 + Build-Tools）。仓库已带 Gradle Wrapper（Gradle 8.9），不依赖本机 Gradle 版本；用 Android Studio 打开时选择 JDK 17 即可。

```bash
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 单元测试
```

两点说明：

- **`targetSdk = 33` 是有意为之**：Android 14 起禁止加载「可写 dex」，JAR 插件会直接失效。等子进程方案（P1）验证后再评估是否升到 34+。
- **允许明文 HTTP**：数据源地址由用户运行时输入，编译期无法枚举域名，与 FongMi / Marco 的处理一致。后续可用「仅放行用户配置域名」的自定义 DNS/连接校验收窄。

> ⚠️ 本工程在无 Android SDK 的沙箱里生成，**还没有跑过一次 `assembleDebug`**。已经做过的验证：
>
> - 数据层（`core/config`、`core/model`、`core/net`、`core/cms`、`core/tmdb`）与插件层（`crawler/Spider`、`core/spider`、`core/AppRepository`）用 kotlinc 2.0.21 + Android 桩类编译通过；
> - `ConfigDecoderTest`（5 例）+ `TmdbMatcherTest`（6 例）全部通过；
> - Manifest、Compose UI、Gradle 依赖解析这三块没有本地验证，首次打开请按实际仓库最新版本校准 `gradle/libs.versions.toml`（AGP / Kotlin / Compose BOM / Media3 的组合最容易打架）。

## 5. 使用

1. 打开应用 → 「导入配置」→ 填入你有权访问的 JSON 配置地址（单仓或多仓都可）；
2. 选择站点（CMS / JAR 会标注类型）；
3. 海报是三级链路：先做 L0（源图 URL 换大图，不联网）；有 TMDB Token 走 TMDB；没有也能用设置里的「海报增强」（B站 PGC，无 Key）兜底；
4. 首页「搜索」进搜索页；看过 30 秒以上会进「继续观看」，点一下从上次进度继续；
5. 设置页可关闭 B站兜底、清空海报缓存、清空观看历史；
4. 首页点海报进详情，选集播放。

## 6. 历史限制（当前验证范围见文首记录）

- 只在本地验证了编译与数据链路，**还没有真机跑过**：TV 上的焦点顺序、输入法行为、播放器手势需要实机确认；
- JAR 插件跑在主进程，插件崩溃会带走应用（P1 迁 `:spider`）；
- 宿主 ABI 只覆盖主流程方法，依赖 `dialog` / `local` / WebView 等私有能力的插件会不兼容；
- `2423` 配置格式缺真实样本回归，失败时会抛异常（可回退到原文本）；
- 无收藏、分类页、直播、备份（P2–P3）；
- 海报 L1 只覆盖 B站有版权的片目（进口电影、番剧、纪录片较好，国产剧/国产电影常为 0 结果）；B站 有 IP 级频控，触发 412 后进入 10 分钟冷却；
- 无 Token 时拿不到「横版剧照」，首页沉浸背景会退回竖版海报裁切（有渐变遮罩）；
- 不解析 `parse=1` 线路，不做 DRM。

## 7. 合规与致谢

- 协议语义参考 TVBox / CatVod 生态的**公开文档与实测行为**，代码独立实现；
- 宿主 ABI 的包名与签名属于互操作所需，不代表与任何上游项目存在隶属关系；
- 本产品使用 TMDB API，但未经 TMDB 认可或认证（"This product uses the TMDB API but is not endorsed or certified by TMDB."）；
- 海报兜底使用 B站 公开搜索页接口，仅取展示用封面与简介，不下载、不转存、不提供播放地址；
- 请勿在仓库或 Issue 中提交影视资源、订阅地址、账号或任何侵权内容。

文档：[设计规范](docs/DESIGN.md) · [TMDB 接入](docs/TMDB.md) · [路线图](docs/ROADMAP.md)

---

## 8. 验证记录（v0.2）

沙箱里没有 Android SDK，因此用 kotlinc 单独编译「不依赖 Compose」的代码，并用桩类替代 Android 框架：

```bash
# 1) 数据层 + 插件层（java.util.Base64 / javax.crypto / org.json / OkHttp 都在 JDK 或 jar 里）
kotlinc -cp json.jar:okhttp.jar:okio.jar:coroutines.jar \
  core/config/*.kt core/model/*.kt core/net/*.kt core/cms/*.kt core/tmdb/*.kt \
  core/spider/*.kt core/AppRepository.kt crawler/Spider.kt -d out/

# 2) 单元测试
kotlinc -cp out:junit.jar test/**/*.kt -d out-test
java -cp out:out-test:junit.jar org.junit.runner.JUnitCore \
  com.nextmovie.core.config.ConfigDecoderTest com.nextmovie.core.tmdb.TmdbMatcherTest \
  com.nextmovie.core.poster.PosterUrlRulesTest com.nextmovie.core.poster.BilibiliPosterParseTest
# => OK (34 tests)

# 3) 完整构建（需要 Android SDK 35 + JDK 17）
./gradlew :app:assembleDebug
# => BUILD SUCCESSFUL, app/build/outputs/apk/debug/app-debug.apk (~13MB)
```

实测行为（可作为回归基线）：

| 输入 | 结果 |
|---|---|
| `{"sites":[{"api":"csp_A","type":3}]}` | 判定为插件源（`isSpider=true`） |
| `{"sites":[{"api":"https://x/api.php/provide/vod","type":1}]}` | 判定为 CMS 源 |
| `{"urls":[{"url":"./sub1.json"},"https://y/sub2.json"]}` | 识别多仓，得到 2 个 depot，相对地址保留待解析 |
| `vod_play_from="甲$$$乙"` + `vod_play_url` | 正确切出 2 条线路、每集 `名称$地址` |
| TMDB `流浪地球/2023` vs `流浪地球2/2023` | 0.5（续集冲突，拒绝） |
| TMDB `繁花/2023` vs `繁花/2024` | 0.55（年份冲突，拒绝） |
| TMDB `The Batman/空年份` vs `The Batman/2022` | 0.9（接受） |
| L0 `img4.doubanio.com/view/photo/s_ratio_poster/x.jpg`（列表） | `img1.doubanio.com/.../m_ratio_poster/x.jpg` |
| L0 `image.tmdb.org/t/p/w185/x.jpg`（详情） | `image.tmdb.org/t/p/w780/x.jpg` |
| L0 B站 `i0.hdslb.com/...@320w_200h.webp` | 去掉 `@` 后缀，取原图 |

本地假 CMS（`127.0.0.1:8765`，type=1 JSON 源）端到端：

| 步骤 | 结果 |
|---|---|
| 配置加载 | 1 个站点：`demo / 本地测试源 / type1` |
| 首页 | 2 部影片，`vod_pic` 正确带出 |
| 详情 | 2 条线路：`线路一:2`、`线路二:1`，首集 `正片$https://cdn.example.com/a.m3u8` 解析正确 |
| 搜索 `庆余年` | 命中 `庆余年 第二季 / 2024 / 更新至12集` |
| L0（列表 / 详情） | `s_ratio_poster → m_ratio_poster / l_ratio_poster` |
| 历史落盘 | 写入 → 重新读回 1 条，`continueWatching` 命中，进度 1.6% |

实网基线（B站 PGC，无 Key，单进程串行、间隔 300ms、随机 `buvid3`）：

| 输入 | 结果 |
|---|---|
| `流浪地球2 / 2023` | 命中，489ms，`cover` 竖版，无横版剧照 |
| `星际穿越 / 2014` | 命中，114ms |
| `肖申克的救赎 / 1994` | 命中，296ms |
| `庆余年 第二季 / 2024` | MISS（B站 无该剧版权） |
| `三体 / 2023` | MISS（同上） |
| 不存在片名 | MISS（负缓存 5 分钟内不再请求） |

---

## v0.3.0（配置兼容与 TMDB 加速）

### 配置包装兼容（按真实样本回归，`app/src/test/resources/config/`）

| 形态 | 样例 | 处理 |
|---|---|---|
| 裸 JSON / UTF-8 BOM | szyyds.cn | 直接解析 |
| JSONC（`//` 行注释、尾逗号） | 饭太硬 / 肥猫 | `ConfigDecoder.sanitizeJson` 字符串安全清洗 |
| gzip | 部分源站 | 先 gunzip |
| `xxxxxxxx**` + Base64 | 饭太硬 | 任意位置匹配前缀，不再锚定行首 |
| `2423` + AES-CBC | 肥猫 | `$#key#$` 头、13 字节 IV、key/IV 补 `0` 到 16；按字节定位，避免二进制串损坏 |
| JPEG 尾部藏配置 | 饭太硬 | 从 `FF D9` 截尾后按 Base64 解 |
| 网页落地页 | 4 个短地址中的 HTML | 报中文错误，并列出页面里的 `.json` 候选地址 |

同时：源站按 UA 分流，配置加载按 `okhttp/4.12.0 → okhttp/3.12.13 → 浏览器 UA` 依次尝试，避免把「网页」当成「配置」。

### 实网配置支持情况（2026-11-24 实测）

| 地址 | 结果 |
|---|---|
| `http://www.饭太硬.cc/tv` | ✅ 50 站点（3 个 JS 站点标记不可用），937ms |
| `http://tvbox.王二小放牛娃.top` | ✅ 95 站点，754ms |
| `http://肥猫.net` | ✅ 41 站点，1099ms |
| `http://fty.xxooo.cf/tv` | ❌ 502 / 域名不可达（备用线路已下线） |
| `https://9280.kstore.vip/wex.json` | ✅ 95 站点（王二小同源） |

以上配置均为 **type 3 插件源，无 CMS 站点**：海报墙/详情完全依赖 JAR 插件与 Guard 运行时。

### JAR 插件

- 加载前先反射调用插件自带的 `com.github.catvod.spider.Init.init(Context)`（每个 jar 一次），Guard 系插件的 secondary dex / 动态库依赖它；
- 仍是 `DexClassLoader` + 主进程内运行；插件崩溃隔离留到 P1 子进程方案。

### TMDB 加速

- 内置线路：`api.tmdb.org/3`（官方备用域，境内可直连）、`api.themoviedb.org/3`（主域，常被阻断）、`tmdb.orzabc.com/get`（公共反代，自带 Key，可关闭）；
- 启动后后台并发测速，按本地实测延迟排序；每次请求可并发竞速（先返回者胜出，其余取消），成功后记住冠军线路；
- 无 Token 时自动只走公共反代：实测 `流浪地球2/2023` 1.4s 命中，中文简介、`w500` 海报与 `w1280` 剧照齐全；
- 图片默认直连 `image.tmdb.org`，被干扰时可切 `wsrv.nl` 中转（含宽度压缩）；
- 自定义镜像可填多行，参与同一套测速/竞速。

### v0.3.2 插件进程隔离

- 插件（JAR/Guard）全部跑在 `:spider` 独立进程（`SpiderService` + Messenger），主进程只收发原始 JSON；
- Guard 系插件的 native 库崩溃（SIGSEGV）只会杀掉 `:spider`，主界面显示「插件进程已退出」并提供重试/换站点；
- 插件进程在首次调用时按需启动，30s 超时；断开后下次请求自动重连。

### v0.3.3 迁移到本地 Mac + 插件 ABI 预检

- 开发/调试已迁到本机（macOS 15.8.2，Intel x86_64）：JDK17 + cmdline-tools + `platform-tools`/`emulator`/`platforms;android-33`/`system-images;android-33;google_apis;x86_64` 均装在 `~/toolchain/`，`./gradlew :app:assembleDebug :app:testDebugUnitTest` 可跑通（46 单测）。
- 新增**插件 ABI 预检**：加载前读 jar 内 `.so` 的 ELF 头，若只有 ARM 库而设备是 x86/x86_64，直接给出可读结论并**不执行任何 native 代码**——这同时消除了 MuMu 这类「x86 + ARM 翻译」环境的原生崩溃。
- 插件进程连接等待从 6s 放宽到 20s（模拟器/低端盒子冷启动较慢），错误区分为「绑定失败 / 连接超时 / 插件返回失败」。
- 导入配置的结果会持久化：首页顶栏常驻 `✓ 已加载 N 个站点`（金色）或 `✗ 加载失败：原因`（红色），设置页额外弹 Toast，不再需要用户自己猜。

**实测结论（`https://cdn09022024.gitlink.org.cn/api/v1/repos/xxooo/in/raw/in.bmp`）**

| 环节 | 结果 |
|------|------|
| 配置下载/解码（JPEG 尾部藏配置） | ✅ 50 站点，当前站点「豆豆｜片单 <修复扫码Bug>」 |
| 插件下载（`ftyguard` jar，1.3MB） | ✅ 合法 zip：`classes.dex` + `ftyguard_v7.so(ARM)` + `ftyguard_v8.so(AArch64)` + `ftyshinidie.guard`（内含二级 dex） |
| x86_64 模拟器加载插件 | ❌ `dlopen failed: ftyguard_v8.so is for EM_AARCH64 (183) instead of EM_X86_64 (62)`；`DexNative.<clinit>` 同样要加载 ARM 的 `.so` |
| MuMu（x86 + ARM 翻译） | 同上，但翻译层直接 SIGSEGV → 0.3.2 的「插件进程已退出」 |
| 结论 | 这类 Guard 配置**只能在 ARM 设备**（arm64 电视盒子/手机）运行；x86 模拟器无解 |

本地模拟器命令见 `docs/DEV_MAC.md`。
