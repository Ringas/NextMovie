package com.nextmovie.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvKeysTest {
    @Test
    fun decodeModeWire() {
        // sanity: section digit mapping indices stable for IA
        assertEquals(0, indexOfSectionDigitKey("1"))
        assertEquals(5, indexOfSectionDigitKey("6"))
        assertNull(indexOfSectionDigitKey("0"))
    }

    /** Mirror of TvKeys.sectionDigit wire order without Compose KeyEvent. */
    private fun indexOfSectionDigitKey(label: String): Int? = when (label) {
        "1" -> 0; "2" -> 1; "3" -> 2; "4" -> 3; "5" -> 4; "6" -> 5; "7" -> 6; "8" -> 7; "9" -> 8
        else -> null
    }
}
