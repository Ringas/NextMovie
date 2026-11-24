# 玩偶哥哥点播弹码与速度复测

开发版：0.6.11-dev / versionCode 27。日期：2026-11-24。

## 样本与复现

实时配置 https://szyyds.cn/tv/x.json，站点 key 玩偶，api csp_WoGGGuard。
测试影片：创伤2026（135833），默认“夸父原”线路，对应夸克网盘。
环境：Android 11 x86_64 AVD 的 libndk_translation ARM 转译，安装 ARM64 APK；x86 dev 包不能用于这类 Guard 原生插件测试。

此前验证了设置账户入口，但没有覆盖详情点播放时的弹码，属于验证缺口。本轮直接从首页进入详情，再点播放。

## 根因与修复

- playerContent 在 :spider 后台服务执行，播放时该进程没有前台 Activity。新增同进程 PluginPlaybackHostActivity，确认 resumed 后才开始解析，直至解析完成或用户取消；同步 Spider.getActivity 和老插件 Init 的 Activity 引用。
- 插件加载晚于 Activity.resume 时，给新注册的生命周期回调补发当前前台状态。窗口和 SpiderService 继续共享同一个运行时。
- 玩偶插件取消扫码后会在同一运行时继续返回“未扫码登录无法观看”。保留源站错误信息，并在下次用户明确点播放前重建隔离插件进程；保留磁盘凭据，不清空应用数据。恢复有额外启动时间。
- 登录等待不再按普通请求的 85/90 秒杀进程或超时，播放请求可等待约五分钟。

## 实际遥控测试

scripts/wogg-login-smoke.py 只使用方向键、确认和返回；仅检查安全控件元数据，不保存二维码图像和输入内容。

连续两次验证通过，均经过：

1. 详情播放按钮真正获得焦点后按确认。
2. 自动出现“网盘源需登录：扫码或输入夸克cookie”。
3. 方向下、确认，选择“点我，使用夸克浏览器APP扫码登录”。
4. 原生二维码图片窗口出现，ImageView 具有非空可见 bounds。
5. 返回取消，回到详情，再点播放仍可重新弹码。

一次自动化观测：第一次发现登录提示约 5.473 秒；取消后重试含插件进程重建约 16.874 秒。这包含 UIAutomator 轮询耗时，不等于精确弹窗绘制时间。Debug 结果在 artifacts/wogg-login/debug-result.json。

未使用真实账号扫码授权；不能据此宣称已验证登录后的网盘视频起播。也未验证所有网盘提供商、所有 Guard 包。

## 速度处理和真实边界

- 首页已有列表时不再调用 homeVideoContent 重复请求。
- 近期首页快照（最长一天）先展示，正常请求在后台刷新；离线刷新失败仍保留已有海报墙。播放地址不走该缓存。
- 对已经初始化的当前来源，焦点停留 700ms 后最多预取一条详情，与实际点击请求合并；不预取播放地址、不主动触发登录。
- Http 客户端、播放代理服务器延后初始化；释放插件实例时保留可复用 dex 优化缓存。

实测观测：

| 场景 | 时间及含义 |
| --- | --- |
| 有近期首页快照 | 快照数据就绪 555ms，海报墙数据 ready 1084ms，不含所有图片下载 |
| 预取完成后点详情 | detail-ready 88ms；预取本身约 2.1 秒，发生在首页停留期间 |
| 无首页快照，已有插件下载文件 | 多次约 20 秒，仍慢，尚未解决到秒开 |
| 无快照一次分段 | 服务 ready 34ms；插件获得/初始化 6390ms；源站 homeContent 4163ms；另有进程启动/连接与界面开销 |

冷启动实测没有证明显著提速，不能用缓存命中结果替代。主要改善是重复打开和焦点停留后进入详情；首次进入仍受插件进程、ARM 转译与源站响应影响。没有真实 ARM 电视的数据，不能将模拟器分段归因全部推广到电视。

## 参考源码

仅参考机制，未整段复制上游实现：

- MarcoMovie 6805065790c6ec24205e92549defc00853a8a74a：android-tv/.../CspEngine.java 的前台 Activity 同步与生命周期补发；HomeScreen.java 的磁盘首页快照；SourceController.java 的单任务、已初始化来源详情预取；DetailCache.java 的限时缓存与并发请求合并。https://github.com/Pilipala9919/MarcoMovie
- FongMi/TV e7e7dd06250ca53da3c594ca4f6befc02e56b1d1：App.java 的 Runtime.setActivity(App::activity)，Spider.java 的 getActivity()，SiteApi.java 的 playerContent 调用。https://github.com/FongMi/TV/tree/fongmi

## 验证

ARM32、ARM64 Debug 构建；81 项单元测试（外部配置样本开启）全部通过，0 跳过。Git diff --check 通过。未提交 Git、未发布远端。

## 非调试构建对照

额外构建 ARM32/ARM64 Release 变体用于本地性能对照，临时 Gradle init 脚本指定已有 Debug 测试签名，未修改仓库发布签名配置。该对照构建离线缺少 lint-gradle:31.7.3，因此跳过了 Release lint；这不是正式发布包或发布检查通过声明。正常 ARM Debug 构建和 81 项回归已通过。

非调试包复测：无首页快照但保留插件下载缓存，home-ready 17351ms，插件进程 ready 3ms，插件获得/初始化 5816ms，homeContent 约3314ms，仍有约7秒的进程启动/连接等开销。预取后详情 ready 54ms。单次对照不能证明稳定的相对提速，首次仍非秒开。

非调试包连续两次点播扫码测试均通过；登录提示观测 5.505 秒、13.539 秒（后者含取消后重建运行时）。最新结果在 artifacts/wogg-login/result.json。全程没有扫码授权，也未保存二维码像素。
