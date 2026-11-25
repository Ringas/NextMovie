package com.nextmovie.ui.player

import com.nextmovie.ui.player.engine.PlaybackBufferPolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test fun lowMemoryDevicesKeepTheSmallCapRegardlessOfReportedHeap() {
        val low = PlaybackBufferPolicy.forDevice(512, true)
        assertEquals(16 * 1024 * 1024, low.bytes)
        assertTrue(low.rebufferMs > low.startMs)
    }

    @Test fun allProfilesHaveValidThresholdsAndBoundedMemory() {
        for (memory in listOf(0, 64, 128, 129, 256, 512)) {
            val p = PlaybackBufferPolicy.forDevice(memory, false)
            assertTrue(p.startMs > 0 && p.startMs <= p.rebufferMs)
            assertTrue(p.rebufferMs <= p.minMs && p.minMs <= p.maxMs)
            assertTrue(p.bytes in 16 * 1024 * 1024..64 * 1024 * 1024)
        }
    }
}
