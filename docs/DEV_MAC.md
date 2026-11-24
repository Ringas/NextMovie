# 本地 Mac 开发环境（Next Movie）

## 一、现有环境（2026-11-24 实测）

本机原本**没有** Android 开发环境：无 Android Studio、无 `~/Library/Android/sdk`、无 AVD、无 brew、无系统 JDK。
只有旧项目 `~/Documents/ChatGPT/TV开发/tv-live` 的构建产物（无 `local.properties`，也无 SDK 引用）和 `~/.codex` 目录，不能直接复用。

本次新装（全部在 `~/toolchain/`，不污染系统目录）：

| 组件 | 路径 |
|------|------|
| JDK 17 (Temurin x64) | `~/toolchain/jdk-17.0.21.1+2/Contents/Home` |
| Android SDK | `~/toolchain/sdk` |
| cmdline-tools | `~/toolchain/sdk/cmdline-tools/latest/bin/{sdkmanager,avdmanager}` |
| platform-tools (adb) | `~/toolchain/sdk/platform-tools/adb` |
| emulator | `~/toolchain/sdk/emulator/emulator` |
| 系统镜像 | `system-images;android-33;google_apis;x86_64` |
| AVD | `nxt33`（device `tv_1080p`，1920×1080） |

> AGP 首次构建会自动补 `build-tools;34.0.0` 与 `platforms;android-35` 到同一 SDK。

## 二、构建

```bash
cd ~/Doubao/chats/2026-11-24/new-chat/NextMovie
export JAVA_HOME="$HOME/toolchain/jdk-17.0.21.1+2/Contents/Home"
export ANDROID_HOME="$HOME/toolchain/sdk"
# local.properties 已写入 sdk.dir
./gradlew :app:assembleDebug :app:testDebugUnitTest
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 三、模拟器（命令行，等价 Android Studio 的 AVD）

```bash
export ANDROID_HOME="$HOME/toolchain/sdk"
"$ANDROID_HOME/emulator/emulator" -avd nxt33 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -port 5554 &

"$ANDROID_HOME/platform-tools/adb" wait-for-device
until [ "$("$ANDROID_HOME/platform-tools/adb" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nextmovie/.MainActivity
```

调试技巧：

```bash
adb logcat -c && adb logcat | grep -E "NextMovieSpider|NextMovie"
adb shell settings put global hide_error_dialogs 1   # 模拟器慢导致的 SystemUI ANR 弹窗，仅调试用
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png .
```

无 UI 时预置配置（需要 `adb root`，google_apis 镜像支持）：

```bash
adb shell "mkdir -p /data/data/com.nextmovie/shared_prefs && cat > /data/data/com.nextmovie/shared_prefs/nextmovie.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="config_url">https://cdn09022024.gitlink.org.cn/api/v1/repos/xxooo/in/raw/in.bmp</string>
</map>
XML
```

## 四、本机签名的坑

Mac 构建用的是 `~/.android/debug.keystore`，与云主机（VM）构建的 APK 签名不同，覆盖安装会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。换机时先 `adb uninstall com.nextmovie` 再装；
要长期双机同包，建议后续统一用仓库内的固定 debug keystore。

## 五、ARM 插件限制（重要）

`ftyguard` 类插件只带 ARM 原生库：

```
META-INF/MANIFEST.MF
classes.dex                 38 KB
assets/ftyguard_v7.so       85 KB  ELF 32-bit ARM
assets/ftyguard_v8.so      102 KB  ELF 64-bit AArch64
assets/ftyshinidie.guard   1.26 MB  zip（二级 dex）
```

x86_64 模拟器上 `dlopen` 必然失败（`EM_AARCH64 instead of EM_X86_64`），MuMu 这类「x86 + ARM 翻译」环境会直接 SIGSEGV。
所以：

- 这类站点只能用 **ARM 真机**（arm64 电视盒子 / 手机）验证，`adb connect <ip>:5555` 即可复用本文所有命令；
- 纯 Java 插件（jar 里无 `.so`）在 x86 模拟器上仍可正常运行；
- 0.3.3 起加载前做 ABI 预检，不匹配时直接给出文案、不触碰 native，避免闪退。

## v0.3.6 修复（x86 模拟器能不能跑"这些源"）

结论：**看插件 jar 里有没有 .so，而不是看源本身。**

| 源 | 站点数 | 插件形态 | x86_64 模拟器 |
|---|---|---|---|
| `in.bmp` / `饭太硬.cc/tv` | 50 | 全部 `csp_*Guard`，jar 内含 `ftyguard_v7.so`(ARM32) + `ftyguard_v8.so`(AArch64) | ✗ 无解（插件只有 ARM 原生库） |
| `王二小放牛娃.top` | 95 | `csp_*Guard`，`wexguard_v7/v8.so` | ✗ 同上 |
| `肥猫.net` | 41 | `csp_Douban`，jar **纯 classes.dex（无 .so）** | ✓ 可用（0.3.6 实测首页出片单） |

本轮修掉的三个真 bug：

1. **`未知动作：0`**（肥猫首页报错）——`SpiderService.onRequest` 把 `msg.what` 带进异步线程读，而
   `Message` 在 `onBind` 返回后已被框架回收（`what/arg1/data` 归零）。改为进线程前复制
   `action/id/Bundle`。这条同时是所有插件站点的隐患。
2. **`ClassNotFoundException: com.google.gson.reflect.TypeToken`**——CatVod 宿主自带 Gson，
   插件直接依赖它。项目内置 `app/libs/gson-2.10.1.jar`（离线可用，避免仓库解析）。
3. **`NoSuchMethodError: No static method log(Ljava/lang/String;)V`**——`SpiderDebug` 桩用表达式
   体 `= Log.d(...)`，JVM 签名成了 `(...)I`；改成显式 `: Unit {}` 才是 `(...)V`。

另注：`肥猫` 的 `csp_Douban` 只实现了首页，`detailContent` / `searchContent` 返回空（2ms 直接返回），
属插件自身能力缺失，不是宿主问题；换其他纯 Java 插件站点可验证详情/播放链路。
