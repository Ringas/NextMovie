# 播放器解码能力（P0–P2）

## 已落地

| 级别 | 项 | 实现 |
|------|----|------|
| P0 | DASH | `media3-exoplayer-dash` + `MediaSourceBuilder` 自动识别 `.mpd` / mime |
| P0 | FFmpeg 软解挂载点 | `NmRenderersFactory` 反射挂 `FfmpegAudioRenderer`；无 AAR 时仅 MediaCodec |
| P0 | 硬/软解偏好 | `DecodeMode` + 设置页「自动 / 硬解优先 / 软解优先」 |
| P1 | 解码失败 → 软解 | `PlaybackRecovery.RETRY_SOFT` + `PlaybackSession.rebuildWithDecodeMode` |
| P1 | 仍失败 → 换线 | `PlaybackRecovery.SWITCH_LINE`（聚合 slots） |
| P2 | 多引擎抽象 | `PlayerEngine` / `ExoPlayerEngine`；IJK/MPV 类存在时探测，完整引擎可后续 module |
| P2 | DRM 脚手架 | `DrmSupport`：Widevine / ClearKey / PlayReady 配置与 session manager |
| P2 | 弹幕脚手架 | `DanmakuController`；设置项开关；需自备 DanmakuFlameMaster |

## FFmpeg AAR

官方 Media3 不发布预编译 FFmpeg。任选其一：

1. 按 [Media3 FFmpeg extension](https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg) 自编译，产出 AAR 放入 `app/libs/`
2. 使用与 FongMi 同源的 decoder AAR（注意许可证与 ABI：`armeabi-v7a` / `arm64-v8a`）

放入后 `NmRenderersFactory.ffmpegAvailable()` 为 true，设置页会提示「FFmpeg 软解已就绪」。

## 设置项

- 解码模式：`player_preferences` / `decode_mode`
- 解码失败自动切软解：`auto_soft_on_decode_error`（默认开）
- 失败自动换线路：`auto_line_on_error`（默认开）
- 弹幕层：`danmaku_enabled`（默认关）

## 尚未内置（需额外 so / AAR）

- 完整 IJK / MPV 引擎实现（仅有类探测与 Exo 回退）
- 弹幕解析与时间轴同步
- DRM 许可证服务器联调（需真实 license URL）
