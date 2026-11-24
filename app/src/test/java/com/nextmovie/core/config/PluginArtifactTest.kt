package com.nextmovie.core.config
import org.junit.Assert.*
import org.junit.Test
class PluginArtifactTest {
    @Test fun directionMarksDoNotBreakAValidDigest() {
        val hash = "252d4d890b6e61a4cd29c3b67f4b8a30"
        assertEquals("https://example.test/plugin.jar" to hash,
            PluginArtifact.split("https://example.test/plugin.jar;md5;\u200e$hash\u200f"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDigestIsNotSilentlyIgnored() {
        PluginArtifact.split("https://example.test/plugin.jar;md5;not-a-digest")
    }
}
