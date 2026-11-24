# 登录后视频代理路径修复

版本：0.6.12-dev / versionCode 28。日期：2026-11-24。

## 用户反馈与定位

用户在真实电视使用玩偶哥哥，已经扫码登录夸克。播放“两京十五日”的 MKV 时出现 403/404 提示。照片中的原始返回包含被宿主改写后的随机端口 /proxy/play/.../01.mkv，而另一字段的嵌套视频地址仍指向原插件服务 6677 端口。

代码确认了确定的错误：SpiderProxyServer.rewrite 对任意 loopback HTTP 地址无条件替换端口。GoProxy 的 REST 视频路径和 TVBox 的宿主回调 /proxy?do=... 是两个接口。转到只按查询参数分发的宿主之后，原视频路径失效。

## 修改

- 只改写 legacy 宿主端口 9978–9999 上的 /proxy 或 /proxy/ 查询接口。插件自己的视频服务、/proxy/play 路径及其它端口不改写。
- 嵌套查询地址原样保留，不二次解码，不替换内层服务端口。
- playerContent 之前即激活宿主回调，并通过 Proxy.set(int) 告知实际监听端口；Proxy.init 每个类只调用一次，允许插件解析过程中同步回调。
- 静态 Proxy 分发兼容 Init.classLoader 返回的二级类加载器，不再以真实实例类与外层包装加载器身份完全相等作为唯一条件。
- 补传 Origin、If-Range 等请求头，继续透传 Content-Range/Content-Length/Accept-Ranges。

参考核对了 MarcoMovie CspEngine 的同步代理端口与代理初始化流程，以及 FongMi server/process/Proxy 的请求头转发。未整段复制上游源码。

## 测试

85 项 JVM 单元测试全部通过，0 失败、0 跳过（开启真实外部配置样本）；ARM32/ARM64 Debug 均构建通过。

新增 4 项回归覆盖：原服务端口和中文 REST 路径保持、嵌套 URL 编码保持、真实 socket HTTP Range 请求到达正确服务、Origin/If-Range 转发。旧版无条件改写逻辑会使视频服务地址保持和实际到达服务的测试失败。

ARM64 转译模拟器上的 ProxyPlaybackProbeActivity 实测：

- pluginAddressPreserved: true
- range206: true
- firstFrame: true
- playing: true
- seek: true
- requests: 3；rangeRequests: 2

原始结果：artifacts/proxy-fix/proxy-probe.json。

播放探针使用 SDK 自带 default360.mp4（从本机私有文件导入，不打包进应用），通过与报错相同形态的中文 /proxy/play/.../01.mkv 路由传输。实际容器为 MP4，该测试验证本地 HTTP 媒体转发与解码，不声称验证了 Matroska 编码兼容或用户网盘中的同一文件。

未读取或清除用户的电视账号凭据；本地没有用户登录后的网盘会话，不能宣称真实“两京十五日”已起播。需覆盖安装后重新从详情点播放，重新取得正确的来源地址。

调试探针仅存在于 Debug source set，不进入 Release 包。Git diff --check 通过；未提交或发布远端。

## 真实电视安装（随后执行）

用户授权连接 TCL 电视并要求静音。核验 ABI 为 armeabi-v7a；通过 ADB 将媒体音量设为 0，并从 dumpsys audio 确认 STREAM_MUSIC Muted=true、speaker=0。

ADB 直接覆盖安装返回 INSTALL_FAILED_VERIFICATION_FAILURE，因此未关闭系统校验，改将 ARM32 APK 复制到电视 U 盘根目录 NextMovie-0.6.12-arm32.apk，通过 TCL 媒体中心正常安装。已核验 versionCode=28、versionName=0.6.12-dev，保留应用数据。

安装后真实首页显示正常，当前配置 50 站点、首页为豆豆片单，包含“两京十五日”条目；首页快照 ready 约2061ms。准备选择该条目时，电视网络和调试端口突然不可达。尚不能宣称电视上的夸克播放已通过，等待恢复连接后继续。

## 用户确认

随后用户明确反馈：已测试可正常播放。此项为用户在已登录夸克的真实电视上的确认，补齐上述自动测试因网络断线未完成的真实网盘播放验证；不扩展为所有网盘提供商或所有文件都已验证。后续非网盘测试按用户要求只在模拟器执行。
