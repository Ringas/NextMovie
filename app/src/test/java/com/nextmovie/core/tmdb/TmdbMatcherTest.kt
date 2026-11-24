package com.nextmovie.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMatcherTest {

    @Test
    fun `exact title and year scores full`() {
        assertEquals(1.0, TmdbMatcher.score("流浪地球2", "2023", "流浪地球2", "2023"), 1e-6)
    }

    @Test
    fun `exact title without year is accepted`() {
        assertTrue(TmdbMatcher.score("The Batman", "", "The Batman", "2022") >= TmdbMatcher.ACCEPT_THRESHOLD)
    }

    @Test
    fun `year conflict is rejected`() {
        assertTrue(TmdbMatcher.score("繁花", "2023", "繁花", "2024") < TmdbMatcher.ACCEPT_THRESHOLD)
    }

    @Test
    fun `sequel number is rejected`() {
        assertTrue(TmdbMatcher.score("流浪地球", "2023", "流浪地球2", "2023") < TmdbMatcher.ACCEPT_THRESHOLD)
    }

    @Test
    fun `season suffix is normalized away`() {
        assertEquals(1.0, TmdbMatcher.score("庆余年 第二季", "2024", "庆余年", "2024"), 1e-6)
    }

    @Test
    fun `punctuation and spacing do not matter`() {
        assertEquals(1.0, TmdbMatcher.score("Spider-Man: No Way Home", "2021", "Spider Man No Way Home", "2021"), 1e-6)
    }
}
