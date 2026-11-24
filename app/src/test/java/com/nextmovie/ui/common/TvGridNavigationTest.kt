package com.nextmovie.ui.common
import org.junit.Assert.*
import org.junit.Test
class TvGridNavigationTest {
    @Test fun dpadPreservesTheColumnAcrossVirtualRows() {
        assertEquals(1, TvGridNavigation.target(0, 5, 15, 22))
        assertEquals(6, TvGridNavigation.target(1, 5, 15, 20))
        assertEquals(5, TvGridNavigation.target(6, 5, 15, 21))
        assertEquals(0, TvGridNavigation.target(5, 5, 15, 19))
    }
    @Test fun edgesDoNotWrapAndUpCanReturnToTheMenu() {
        assertEquals(0, TvGridNavigation.target(0, 5, 15, 21))
        assertEquals(4, TvGridNavigation.target(4, 5, 15, 22))
        assertNull(TvGridNavigation.target(3, 5, 15, 19))
        assertEquals(11, TvGridNavigation.target(8, 5, 12, 20))
        assertEquals(11, TvGridNavigation.target(11, 5, 12, 20))
    }
    @Test fun normalizedTvWidthUsesCurrentPosterSizeAndSpacing() {
        assertEquals(6, TvGridNavigation.columns(896f))
        assertEquals(5, TvGridNavigation.columns(704f))
        assertEquals(4, TvGridNavigation.columns(703f))
    }
}
