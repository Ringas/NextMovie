package com.nextmovie.core.cache

import com.nextmovie.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DetailSnapshotStoreTest {
    private val value = VodDetail(Vod("id", "影片", content = "简介", score = "8.2"),
        listOf(PlayLine("线路", listOf(Episode("第#1集", "https://example.test/video?a=1&b=2")))))

    @Test fun restartPreservesSynopsisAndExactEpisodeIdentifiersUntilExpiry() {
        val dir = Files.createTempDirectory("detail-cache").toFile()
        try {
            var clock = 1_000_000L
            DetailSnapshotStore(dir) { clock }.write("key", value)
            val reopened = DetailSnapshotStore(dir) { clock }
            assertEquals(value, reopened.read("key"))
            clock += DetailSnapshotStore.TTL_MS
            assertNull(reopened.read("key"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun clearingRejectsLateResponseAndCorruptFilesAreMisses() {
        val dir = Files.createTempDirectory("detail-cache").toFile()
        try {
            val store = DetailSnapshotStore(dir)
            val generation = store.generation
            store.clear()
            store.write("key", value, generation)
            assertNull(store.read("key"))
            java.io.File(dir, "key.json").writeText("{broken")
            assertNull(store.read("key"))
            store.write("key", value.copy(lines = emptyList()))
            assertNull(store.read("key"))
        } finally { dir.deleteRecursively() }
    }
}
