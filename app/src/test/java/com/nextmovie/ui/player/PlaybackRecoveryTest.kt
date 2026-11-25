package com.nextmovie.ui.player

import androidx.media3.common.PlaybackException
import com.nextmovie.ui.player.engine.DecodeMode
import com.nextmovie.ui.player.engine.PlaybackRecovery
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryTest {
    private fun err(code: Int) = PlaybackException("t", null, code)

    @Test
    fun decodeFailSwitchesToSoft() {
        val d = PlaybackRecovery.decide(
            error = err(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
            currentMode = DecodeMode.AUTO,
            alreadyTriedSoft = false,
            hasMoreLines = true,
            autoSoft = true,
            autoLine = true,
        )
        assertEquals(PlaybackRecovery.Action.RETRY_SOFT, d.action)
        assertEquals(DecodeMode.SOFT, d.nextDecodeMode)
    }

    @Test
    fun afterSoftTriesLine() {
        val d = PlaybackRecovery.decide(
            error = err(PlaybackException.ERROR_CODE_DECODING_FAILED),
            currentMode = DecodeMode.SOFT,
            alreadyTriedSoft = true,
            hasMoreLines = true,
            autoSoft = true,
            autoLine = true,
        )
        assertEquals(PlaybackRecovery.Action.SWITCH_LINE, d.action)
    }

    @Test
    fun ioWithoutLinesShowsError() {
        val d = PlaybackRecovery.decide(
            error = err(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            currentMode = DecodeMode.AUTO,
            alreadyTriedSoft = false,
            hasMoreLines = false,
            autoSoft = true,
            autoLine = true,
        )
        assertEquals(PlaybackRecovery.Action.SHOW_ERROR, d.action)
    }

    @Test
    fun noSoftRendererSkipsStraightToLine() {
        val d = PlaybackRecovery.decide(
            error = err(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
            currentMode = DecodeMode.AUTO,
            alreadyTriedSoft = false,
            hasMoreLines = true,
            autoSoft = true,
            autoLine = true,
            softAvailable = false,
        )
        assertEquals(PlaybackRecovery.Action.SWITCH_LINE, d.action)
    }
}
