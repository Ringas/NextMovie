# Marco / FongMi 网盘登录与 4K 播放机制研究

> 结论先行：**网盘登录不是宿主实现的，是插件实现的；宿主只做两件事——把插件给的二维码字符串画出来，把播放请求转成 `127.0.0.1:<port>/proxy?…` 中继。**
> 我们目前把登录做在宿主侧（自建 CAS token + 反射塞 cookie），模型就错了，所以会看到 `{"msg":"quark: resolve timeout"}`。

## 1. 两套实现的共同骨架

Marco（`com.pilipala.marcomovie`）与 FongMi/TV 同源，APK 里可直接读到：

| 证据（Marco APK strings） | 含义 |
|---|---|
| `http://127.0.0.1:9978/proxy` | 宿主本地 HTTP 服务，端口 9978 起，路径 `/proxy` |
| `quark_cookie.txt`、`quark_cookie`、`quarkcookie`、`115pancookie` | 网盘 Cookie 落地成文件，按网盘分别存 |
| `account-drive:quark` | 网盘账号/盘符标识，用于按盘取 Cookie |
| `com/google/zxing/...` | 宿主只负责“画二维码”，不负责“生成登录态” |
| `setCookie` / `parseSetCookies` / `handleSetCookieRequests` | 宿主侧有 Cookie 注入/回灌通道 |

FongMi 源码（github.com/FongMi/TV，本轮已 clone 核对）：

- `app/.../server/Server.java`：`http://127.0.0.1:<port>`，端口从 9978 起自增。
- `catvod/.../Proxy.java`：`getUrl(true)` → `http://127.0.0.1:<port>/proxy`。
- `catvod/.../crawler/Spider.java:71-80`：`proxyUrl(params)` 拼成
  `http://127.0.0.1:<port>/proxy?do=jar&siteKey=<站点key>&<其它参数>`。
  **也就是说播放地址在交给播放器之前就被改写成宿主代理地址。**
- `app/.../api/loader/BaseLoader.java:160-168`：`proxy(params)` →
  `getSpider(siteKey).proxy(params)`，即**由插件产出字节流**。
- `app/.../player/mpv/MpvUtil.java:159`：MPV 直接 `proxy-url=http://127.0.0.1:<port>/proxy?`，
  连 mpv 自己的网络请求都走宿主。
- `app/.../ui/dialog/SpiderDialog.java:137-234`：插件请求弹窗时若带 `qrcode` 字段，
  宿主就用 zxing 把**插件给的字符串**画成二维码——`QRCode.getThemedBitmap(value, …)`。

## 2. 4K 原画到底依赖什么

1. **插件授权（Cookie）**：插件自己持有网盘 API（Quark/UC/Ali/Baidu），
   没有 Cookie 时 `detailContent` 会返回 `{"list":[],"msg":"quark: resolve timeout"}`，
   有 Cookie 才会把分享链接解析成真实直链。
2. **本机分片代理**：解析出的直链再被宿主改写成 `127.0.0.1/proxy?do=jar&siteKey=…`，
   播放器的 `Range` 请求原样透传给插件，插件按片返回（206 + `Content-Range`）。
   → 所以“4K 能不能播”取决于插件有没有授权，**不取决于宿主的播放器能力**。
3. **规格（分辨率/码率/HDR）来源**：宿主不解析媒体规格，展示的是
   ① 文件名/线路名里的声明（如 `4K`、`原画`）；② 插件返回的 `vod_remarks`/线路名；
   ③ 播放器（mpv/Exo）探测到的轨道信息。宿主不做“猜规格”。

## 3. 我们项目（NextMovie）当前的差距

| 环节 | Marco / FongMi | 我们 | 影响 |
|---|---|---|---|
| 二维码内容 | 插件给字符串，宿主画 | 宿主自建 CAS token，再画 token | 扫出来是纯文本，夸克 App 不当登录码（已实测） |
| 登录态落地 | 宿主 WebView 登录后把 Cookie 回灌插件（`Net`/`CookieManager`） | 宿主自己存，再反射 `api.Quark.setCookie` / `Pref.putString` | Guard 插件里反射基本无效 → 插件仍无 Cookie |
| Cookie 注入目标 | 插件的 `com.github.catvod.net.Net.setCookie(url, value)`（进程级 CookieStore） | `api.<Pan>.setCookie` | 注入点不对，插件读不到 |
| 播放中继 | `127.0.0.1/proxy?do=jar&siteKey=…`，Range 透传 | `SpiderProxyServer.rewrite()` + `route` 回调，已保留 `Range` 头并回传 `[status,mime,stream,headers]` | **这一层我们已经对齐**，是唯一做对的部分 |
| 规格展示 | 文件名/线路名/播放器轨信息 | 同（246 行 detail 上有 4K/评分标） | 一致，无需改 |

## 4. 落地方案（按优先级）

**P0：把 Cookie 注入改到正确的 API**
- 在 `JarSpiderLoader` 里通过插件 ClassLoader 调
  `com.github.catvod.net.Net.setCookie("https://pan.quark.cn", cookie)`，
  同时保留 `api.Quark.setCookie` 作为兜底；
- 注入后让插件重新 `detailContent`，验证 `quark: resolve timeout` 是否消失。

**P1：登录入口改成“官方网页登录 + Cookie 回灌”**
- 复用已有的内嵌 WebView（当前给阿里/百度用的那条），把夸克/UC 也接进来；
- 页面就是 `https://pan.quark.cn` 官方登录页，**页面里的二维码是官方的，可正常扫**；
- 登录成功后从 `CookieManager.getCookie(url)` 取整段 Cookie，
  走 P0 的 `Net.setCookie` 回灌，不再自建 CAS token。

**P2（可选）：接插件 dialog 的 `qrcode`**
- 若来源插件支持弹窗登录（`qrcode` 字段），直接照 FongMi 的做法渲染，
  插件自己轮询、自己存 Cookie，宿主零逻辑——这是最省事的形态，
  但取决于具体插件是否实现，需逐源探测。

**验收**：同一部片（花卷《裙下之臣》）→ 详情 3s 内出线路 → 点播放 → 首帧出图；
日志里不再出现 `quark: resolve timeout`，`play-resolve` 里 URL 已是 `127.0.0.1:<port>/proxy?...`。
