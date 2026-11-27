# NextMovie

Android TV 点播播放器（零内置源）：应用本身不带内容，靠订阅配置 + CatVod 插件获取片源，网盘扫码登录后直接播放。

## 代码边界说明

本仓库只包含一个 App，包名 `com.nextmovie`（applicationId 与 namespace 相同），不要与其它影视类工程混放。

| 目录 / 模块 | 归属 | 说明 |
|---|---|---|
| `app/src/main/java/com/nextmovie/**` | 自研 | 唯一业务代码：UI（首页/片库/详情/播放/网盘/搜索/设置）、`core/`（订阅解析、CMS/插件宿主、海报、网盘登录与代理、更新检查、运行日志） |
| `app/src/main/java/com/github/catvod/crawler/**` | 第三方接口 | CatVod 蜘蛛协议的最小接口声明（`Spider` 等），非业务实现 |
| `catvodlib/` | vendored 第三方 | CatVod / FongMi 生态的插件运行时（`com.github.catvod.crawler` / `api` / `net` / `utils`，Java，约 350 个文件）。**只允许作为本地 library 模块被 `:app` 依赖，不参与任何业务改写**；保留上游许可证与版权头 |
| `dist/` | 分发产物 | 各轮验收 APK，仅本地留存，不纳入版本控制 |
| `docs/` | 文档 | 界面/网盘/真机验收记录 |

约束：

- `settings.gradle.kts` 只允许 include `:app`、`:catvodlib`；不得通过 `project(":xxx")` 或绝对路径引用本机其它工程。
- `catvodlib/build/`、`app/build/`、`dist/`、`*.apk` 均为构建/分发产物，已在 `.gitignore` 排除；不要把编译中间件提交进仓库。
- 同名旧工程（0.3.x 时代的 `com.nextmovie`）与本仓库**不同源**，历史点从 0.6.8 起。构建与交付只以本仓库 `dev` 分支为准，避免同名包降级覆盖。
- 相邻但独立的项目：NextTV / Next-TV（IPTV，包名 `com.nexttv`）、iptv-studio、iptv-logos，各自独立仓库，与本项目无代码共享。

## 构建

```bash
export JAVA_HOME="$HOME/toolchain/jdk-17.0.21.1+2/Contents/Home"
export ANDROID_HOME="$HOME/toolchain/sdk"
./gradlew :app:assembleTv64Debug
# 产物：app/build/outputs/apk/tv64/debug/app-tv64-debug.apk
```

真机安装后启动：`adb shell am start -n com.nextmovie/.MainActivity`。
