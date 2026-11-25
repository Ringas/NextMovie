package com.nextmovie.ui.player.engine

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun playbackLoadControl(context: Context): DefaultLoadControl {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val policy = PlaybackBufferPolicy.forDevice(manager?.memoryClass ?: 0, manager?.isLowRamDevice ?: false)
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(policy.minMs, policy.maxMs, policy.startMs, policy.rebufferMs)
        .setTargetBufferBytes(policy.bytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}
