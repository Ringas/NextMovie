音频 FFmpeg 软解（推荐）
========================
默认策略：硬解优先 + 失败自动软解/换线。

要启用真正的软解，请将编译好的 AAR 放到本目录，文件名匹配其一即可：
  media3-decoder-ffmpeg*.aar
  lib-decoder-ffmpeg*.aar

构建参考：
  https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg

ABI 需与 productFlavor 一致：
  tv   -> armeabi-v7a
  tv64 -> arm64-v8a

放入后重新编译；NmRenderersFactory 会反射加载 FfmpegAudioRenderer。
不放 AAR 时应用仍可运行，仅无 FFmpeg 音频软解能力。
