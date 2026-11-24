# 详情与播放兼容验证（2026-11-24）

基于 `01a1882` 的本地修改，未提交、未发布。应用版本仍为 0.6.9。

## 设备与范围

Intel Mac 上新增独立 Android 11 Google APIs x86_64 AVD：`nextmovie-arm-compat`。系统实际返回 `ro.dalvik.vm.native.bridge=libndk_translation.so`，支持列表包含 ARM64/ARM32。安装 ARM64 构建执行测试；不是仅在 x86 应用中检查配置。

每次验证依次运行配置加载、站点首页（必要时进入首个分类）、详情、选集、播放解析、Media3 起播。只有 `isPlaying=true` 且进度超过 3 秒才记录 `playing`。这不是长播稳定性或全部站点的验收。

| 用户配置 | 配置内站点数 | 代表性来源 | 详情 / 解析 / 实际播放 | 输入方式 |
|---|---:|---|---|---|
| szyyds | 62 | Aid / csp_FirstAidGuard | 通过 | 实时入口 |
| 饭太硬 | 50 | Aid / csp_FirstAidGuard | 通过 | 当日下载的原始配置、原始插件快照 |
| 王二小 | 95 | ChildrenAiTuTu / csp_ChildrenAiTuTuGuard | 通过 | 当日下载的原始配置、原始插件快照 |
| 肥猫 | 41 | 豆瓣预告 / csp_YGP | 通过 | 实时入口 |

快照测试只把配置和声明的原始 JAR 放在本机临时服务器，继续校验原始 MD5，不替换内容来源接口。内容请求和媒体播放仍由插件访问实际网络。快照不随应用、仓库或 APK 分发。

王二小实时入口测试中出现 HTTP 504；HTTPS 复查还出现证书过期，没有关闭证书校验。快照通过不代表实时入口始终可用。

另外，szyyds 的 CMS 样例取得详情和两条线路，但测试媒体返回 HTTP 错误。YGPGuard 的部分预告首页为空。这些结果不计为播放通过，不承诺任意电影、网盘、直播或第三方插件可用。

## 本轮修复

- 插件日志 ABI：新增 `SpiderDebug.log(Throwable): void`，修复 Guard 后台线程的 NoSuchMethodError。
- 摘要格式：只移除 MD5 周围空白及 Unicode 格式字符，继续执行真实摘要校验；非法摘要明确报错。
- 插件身份：IPC 保留配置 origin、JAR 摘要；实例按完整输入缓存，ClassLoader 按 JAR 共享，避免同包站点初始化状态分裂；摘要变化使用不同缓存文件。
- 初始化：初始化一次；尊重插件的一参/二参 init 重载；不再遍历调用任意 load/prepare 方法。
- 配置状态：提供插件实际读取的 `tv.Config(url,type,time)` 当前配置约定，不内置配置地址。
- 首页：只返回分类时加载首个分类；即使推荐为空也保留分类入口；修复分类切换没有触发加载。
- CMS：解析 XML 的 dl/dd 线路与选集；请求覆盖原有 ac 参数，避免重复查询键。
- IPC：正确解绑，取消时清理等待与跳过队列任务；按来源串行、不同来源有界并发；超长执行重启插件进程。
- 详情/续播：图片增强不阻塞选集；跨站详情使用目标影片 ID；换源先匹配目标站点；续播与播放返回保留来源上下文。
- 播放：接入 loopback 插件流代理，保留 Range/响应状态；补普通播放页 WebView 解析及部分配置解析器入口；HLS 探测与媒体请求统一网络栈。
- JS：保留真实 HTTP 状态和响应头，并向 init 传入 ext；异步 JS、Python、Node 仍不作为已支持能力。

## 界面

参考 MarcoMovie 的电视交互方向，改为多行竖版海报墙、紧凑分类导航与继续观看；详情保留左侧大海报，右侧信息/线路/选集独立滚动，播放解析提供可取消提示。不是对 MarcoMovie 的完整复制，也未加入其全部 Dock、收藏、主题和动画。

## 自动检查与构建

使用 JDK 17 与 Android SDK 35，离线构建现有锁定依赖。运行：

```sh
./gradlew :app:testDevDebugUnitTest :app:assembleTvDebug :app:assembleTv64Debug :app:assembleDevDebug --offline
```

本轮总计 69 项测试通过，0 失败、0 跳过（已显式提供四份本机配置样本）。

本轮增加 XML 选集、外部实体拒绝、MD5 格式、RPC 身份往返、数字开关、插件 init 重载、日志 JVM 签名、代理流和 Range 回归。实网配置测试需要显式指定 `NEXTMOVIE_COMPAT_SAMPLES` 临时目录，默认跳过；不捆绑新外部样本。

Debug-only `CompatibilityProbeActivity` 可用 intent extras `config`、`site` 运行链路，结果写应用私有目录 `files/compat-probe.json`。调试入口不进入 release 构建。

## 仍需继续验收

- 四个实时入口的重复冷启动、多个站点覆盖与长期播放。
- 网页解析、配置解析器和插件本机代理的更多真实来源。目前代理有流式协议回归，但不能据此宣称全部插件代理协议兼容。
- 异步 JS、专有宿主交互、Python/Node、网盘登录、DRM、直播均未完成全面验收。
- ARM 转译测试不能代替真实电视的硬解码、音频透传、HDR 和性能验证。

本机脱敏结果见 `artifacts/compat-20261124/playback-results.json`；临时服务与下载文件位于系统临时目录，不是应用默认来源。

## 界面实测

最终 ARM64 包已实际从海报墙点击进入详情，再点击播放按钮开始播放；已检查首页、详情和播放截图，未发现该流程崩溃。截图保存在本机 `artifacts/compat-20261124/home.png`、`detail.png`、`player.png`，不随源码发布。临时快照服务器验证后已关闭。
