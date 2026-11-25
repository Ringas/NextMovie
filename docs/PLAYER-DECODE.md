# 播放器解码策略

## 推荐配置（已作为默认）

| 项 | 值 |
|----|-----|
| 解码模式 | **自动**（硬解优先） |
| 解码失败 | **自动切软解** 重试同一 URL |
| 仍失败 | **自动换线路** |
| FFmpeg | **建议补音频 AAR**（`app/libs/media3-decoder-ffmpeg*.aar`） |
| IJK / MPV | 暂不排期 |
| DRM / 弹幕 | 脚手架保留，暂不排期 |

## 代码位置

- `DecodeMode` / `PlayerPrefs`：默认 `AUTO`、`auto_soft=true`、`auto_line=true`
- `NmRenderersFactory`：`EXTENSION_RENDERER_MODE_ON` + 反射 FFmpeg
- `PlaybackRecovery`：`RETRY_SOFT` → `SWITCH_LINE` → `SHOW_ERROR`
- `MediaSourceBuilder`：HLS / DASH / Progressive
- 软解重试后通过 `softRetryToken` 重新 `prepare`

## 接入 FFmpeg AAR

见 `app/libs/README-FFMPEG.txt`。无 AAR 时设置页提示「未检测到 FFmpeg」；有 AAR 后显示「FFmpeg 软解已就绪」。
