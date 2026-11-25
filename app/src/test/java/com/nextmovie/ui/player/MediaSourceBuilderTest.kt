package com.nextmovie.ui.player

import com.nextmovie.ui.player.engine.MediaSourceBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSourceBuilderTest {
    @Test
    fun detectsHls() {
        assertEquals(
            MediaSourceBuilder.Kind.HLS,
            MediaSourceBuilder.detectKind("https://cdn.example/a/index.m3u8"),
        )
        assertEquals(
            MediaSourceBuilder.Kind.HLS,
            MediaSourceBuilder.detectKind("https://cdn.example/play?format=m3u8"),
        )
    }

    @Test
    fun detectsDash() {
        assertEquals(
            MediaSourceBuilder.Kind.DASH,
            MediaSourceBuilder.detectKind("https://cdn.example/manifest.mpd"),
        )
        assertEquals(
            MediaSourceBuilder.Kind.DASH,
            MediaSourceBuilder.detectKind("https://cdn.example/x", mimeHint = "application/dash+xml"),
        )
    }

    @Test
    fun progressiveFallback() {
        assertEquals(
            MediaSourceBuilder.Kind.PROGRESSIVE,
            MediaSourceBuilder.detectKind("https://cdn.example/movie.mp4"),
        )
    }
}
