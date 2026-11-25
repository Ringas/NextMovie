package com.nextmovie.core.cache
import com.nextmovie.core.model.Vod
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class HomeSnapshotStoreTest {
    @Test fun coldHomeCanReadAFreshSnapshotWithoutNetwork() {
        val dir = Files.createTempDirectory("home-snapshot").toFile()
        try {
            var now = 1_000_000L
            val store = HomeSnapshotStore(dir) { now }
            store.write("source", listOf(Vod("1", "Example", "https://example.test/poster.jpg")))
            assertEquals("Example", store.read("source")!!.single().name)
            now += 300_001
            assertEquals(300_001L, store.ageMs("source"))
            assertNull(store.read("source", maxAgeMs = 300_000))
            assertEquals("Example", store.read("source")!!.single().name)
            now += 86_400_000
            assertNull(store.read("source"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedEmptyResponsesDoNotEraseVisibleContent() {
        val dir = Files.createTempDirectory("home-snapshot").toFile()
        try {
            val store = HomeSnapshotStore(dir)
            store.write("source", listOf(Vod("1", "Example")))
            store.write("source", emptyList())
            assertEquals(1, store.read("source")!!.size)
            store.clear(); assertNull(store.read("source"))
        } finally { dir.deleteRecursively() }
    }
}
