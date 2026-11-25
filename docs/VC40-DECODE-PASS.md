# vc40 播放器与交互修复记录

统一测试源：`https://9280.kstore.vip/newwex.json`（28 个站点，首页默认站点「【免费分享】」）。
测试设备：`emulator-5556`（TV 1920×1080@320dpi，Android TV ARM 转译）。版本：`0.6.24-dev` / `versionCode 40`。

## 一、这一版改了什么

### 1. 编译修复（dev 分支此前整体不可编译，30 个错误）
| 文件 | 问题 | 处理 |
|---|---|---|
| `ui/home/HomeScreen.kt` | `import androidx.compose.ui.Modifier.Modifier`（错误自动导入） | 改为正确导入 |
| `ui/common/TvMenu.kt` | `e.type` 未解析 | 补 `input.key.type` 导入 |
| `ui/common/TvKeys.kt` | `Key.Space` 不存在 | 改 `Key.Spacebar` |
| `core/spider/JsSpider.kt` | 显式 `JSONObject?` 后 elvis 丢智能转换 | 改为非空声明 |
| `ui/shell/MainShell.kt` | `AmbientBackdrop(tint=)` 参数不存在 | 改 `posterTint=` |

### 2. 自动换线路真正接线
`PlayerScreen` 里的 `SWITCH_LINE` 过去只弹一句「正在切换下一条线路」，没有调用 `onSwitchSlot`，播放器仍停在坏线路上。
现在：真正切换 → 同时用 `triedLines` 计数，换满一轮（= 线路数）仍失败就停下报错，不会在死线路之间无限循环。

### 3. 软解判定不再空转
`PlaybackRecovery.decide()` 增加 `softAvailable` 参数：本机没有 FFmpeg AAR 时，「切软解」只是同一套 MediaCodec 重来一遍，因此直接跳到换线路。

### 4. 解码自检面板（设置页「播放」）
新增 `engine/DecoderProbe.kt`：
- 视频：H.264 / HEVC / **HEVC 10bit（按 profileLevels 查 Main10）** / AV1 / VP9 / Dolby Vision
- 音频：AAC / AC3 / EAC3 / DTS / DTS-HD / TrueHD
- 每项标「硬解 / 仅软解 / 不支持」，并显示 FFmpeg 扩展是否就绪
- 缺 HEVC 10bit 时直接提示「4K 10bit 会黑屏或花屏，请选 1080p 或换线路」

### 5. 遥控器按一次执行两次（这是"界面根本用不了"的主因）
- `Modifier.tvClickable` 同时挂了 `onPreviewKeyEvent` 和 `clickable`，一次确认会回调两次 → 加 150ms 去重。
- `HomeScreen` 的「换源」「刷新」把动作写成了 **trailing lambda**，而 `TvPill` 的最后一个参数是 `content`（@Composable），导致动作在**组合期**执行：进首页就自动弹「切换片源」，而且关掉立刻又被设回 true，弹窗永远关不掉。已改为 `onClick = {}`。
- `TvPill.autofocus` 参数此前声明了但从未生效，弹窗里的「关闭」拿不到焦点。已落地 `focusRequester`。

### 6. 测试基础设施
- `testOptions { unitTests.isReturnDefaultValues = true }`：Media3 的 `PlaybackException` 会用 `SystemClock`，否则单测全挂。
- `PlaybackRecoveryTest` 补 `noSoftRendererSkipsStraightToLine`。
- `JarBytesTest` 改为高熵 payload（原用例全 0 被 deflate 压到 200B，触发生产侧最小体积护栏）。
- `GuardStringScanner` 改为正则从长串里挖 URL（真实 jar 里 URL 常嵌在长串中间）。

## 二、实机结果

| 项 | 结果 | 证据 |
|---|---|---|
| 首页 | 20 部，秒开；「切换片源」弹窗不再自弹，可正常关闭 | `docs/ui-test/80-vc40-home.png` |
| 设置 → 播放 | 解码模式/自动切软解/自动换线路都在，FFmpeg 显示「未检测到」 | `docs/ui-test/82-vc40-settings-playback.png` |
| 详情页 | **仍失败**：约 20s 后显示「加载详情失败：所有站点都没有返回这部片的可用线路」 | `docs/ui-test/81-vc40-detail-fail.png` |
| 播放 | 详情拿不到线路 → 无法进入播放（未验证解码矩阵） | — |

## 三、新发现的详情页/插件问题（未修）

1. **每次插件调用都在重新初始化宿主**：日志里 `jar-ready → native-extracted → dexloader → pre-init` 在每个 `plugin-source action=5` 前都重复出现。
2. **单次调用 2.8–7.4s**：`plugin-obtain action=5 5528ms`、`plugin-source action=5 4707ms`，首页 20 部 × 每部多站点探测，就是「几分钟」的来源。
3. **`AiNewHuaJuan fast url failed: 测速超时（6000ms）`**，且有 `127.0.0.1:39677 ECONNREFUSED`（插件本地端口已死）。
4. **设置页右栏无法用遥控器滚动**：焦点上不去，解码自检面板在首屏之下根本看不到（截图 82 只到「失败自动换线路」）。

结论：详情拿不到线路 ≠ 解码问题，是**插件运行时（SpiderHost / Guard 端口 / 每请求重绑定）**的问题，属于下一轮的主线。

## 四、产物

```
dist/NextMovie-0.6.24-dev-vc40-arm64-v8a.apk
  sha256 e754a06d8bb6a4f212af5ed62e09f5fa744ac288fd5b0df73b89beb7b9b6d436
dist/NextMovie-0.6.24-dev-vc40-armeabi-v7a.apk
  sha256 09a3ebea4d27cc3b3e87618348279353de86c980563d20eeeaefc0f50bb27269
```
