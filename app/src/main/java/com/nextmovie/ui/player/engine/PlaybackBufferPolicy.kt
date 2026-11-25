package com.nextmovie.ui.player.engine

/** Fast initial playback with a separate rebuffer threshold and bounded memory use. */
data class PlaybackBufferPolicy(
    val minMs: Int,
    val maxMs: Int,
    val bytes: Int,
    val startMs: Int = 500,
    val rebufferMs: Int = 1_400
) {
    companion object {
        fun forDevice(memoryMb: Int, lowRam: Boolean): PlaybackBufferPolicy = when {
            lowRam || memoryMb in 1..128 -> PlaybackBufferPolicy(6_000, 16_000, 16 * 1024 * 1024)
            memoryMb in 129..256 -> PlaybackBufferPolicy(10_000, 30_000, 32 * 1024 * 1024)
            else -> PlaybackBufferPolicy(12_000, 40_000, 64 * 1024 * 1024)
        }
    }
}
