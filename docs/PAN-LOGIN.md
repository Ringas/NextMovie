# 网盘登录（Round8）

## 入口
「账户与网盘」页第一项 = 「网盘登录」，其下列出 夸克 / UC / 阿里 / 百度·嘟嘟。
点网盘名 → 当场出二维码（夸克/UC）或内嵌官方登录页（阿里/百度）。

## 夸克 / UC：宿主自建 CAS 扫码
1. `GET https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin` → token 在 `data.members.token`
2. 本地 zxing 把 token 渲染成二维码（官方 `getQRCodeImage` 端点实测返回 0 字节，不可用）
3. 每 2s `getServiceTicketByQrcodeToken?token=`；`50004001 Query result is empty` = 未扫码
4. 拿到 `serviceTicket` → `pan.quark.cn/account/info?st=` 种 Cookie → 写入 `pan_cookie_quark`

## 兜底
「扫码没反应？改用网页登录」→ 同页内嵌 WebView（桌面 UA、700×620dp）打开官方页，
轮询 `__puus` / `SESSIONID` / `BDUSS`，命中即写 prefs 并 `JarSpiderLoader.injectPanCookie` 注入来源。

## 状态
夸克二维码已在真机 `29d395e2` 出图（docs/ui-test/97-round8-pan-qr.png）；
“扫码后换 Cookie” 需人工用夸克 App 扫一次确认，代码路径已打通、日志 `NextMoviePan` 可追。
