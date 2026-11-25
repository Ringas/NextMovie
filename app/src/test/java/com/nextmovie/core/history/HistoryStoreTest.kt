package com.nextmovie.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = HistoryStore(tmp.newFile("history.json"))

    private fun entry(
        id: String,
        pos: Long = 0L,
        dur: Long = 0L,
        at: Long = System.currentTimeMillis(),
        site: String = "源A"
    ) = HistoryEntry(
        siteKey = site,
        vodId = id,
        name = "剧名$id",
        pic = "https://img/p$id.jpg",
        remark = "更新至12集",
        playFrom = "线路一",
        episodeName = "第3集",
        episodeUrl = "https://m3u8/$id.m3u8",
        positionMs = pos,
        durationMs = dur,
        updatedAt = at
    )

    @Test
    fun `writes and reads back`() {
        val s = store()
        s.upsert(entry("1", pos = 90_000, dur = 2_700_000))
        s.upsert(entry("2", pos = 10_000, dur = 1_800_000))

        val reopened = HistoryStore(tmp.root.resolve("history.json"))
        val list = reopened.list()
        assertEquals(2, list.size)
        assertEquals("剧名2", list[0].name) // 后写入的在前
        assertEquals(90_000L, reopened.find("源A", "1")!!.positionMs)
    }

    @Test
    fun `same key updates instead of duplicating`() {
        val s = store()
        s.upsert(entry("1", pos = 60_000))
        s.upsert(entry("1", pos = 300_000, at = System.currentTimeMillis() + 1000))

        val list = s.list()
        assertEquals(1, list.size)
        assertEquals(300_000L, list[0].positionMs)
    }

    @Test
    fun `same title on different sites keeps latest playback progress`() {
        val s = store()
        s.upsert(entry("1", pos = 60_000, site = "源A"))
        val latest = entry("2", pos = 120_000, site = "源B").copy(name = "剧名1")
        s.upsert(latest)
        s.upsert(entry("3", site = "源A"))
        assertEquals(2, s.list().size)
        assertEquals(latest, s.findByName("剧名1"))
        assertNull(s.find("源A", "1"))
    }

    @Test
    fun `caps entry count`() {
        val s = store()
        repeat(HistoryStore.MAX_ENTRIES + 5) { i ->
            s.upsert(entry("e$i", at = i.toLong()))
        }
        assertEquals(HistoryStore.MAX_ENTRIES, s.list().size)
    }

    @Test
    fun `continue watching skips too short and nearly finished`() {
        val s = store()
        s.upsert(entry("short", pos = 5_000, dur = 1_800_000))
        s.upsert(entry("fresh", pos = 120_000, dur = 1_800_000))
        s.upsert(entry("done", pos = 1_750_000, dur = 1_800_000))
        s.upsert(entry("unknownDuration", pos = 120_000, dur = 0L))

        val ids = s.continueWatching().map { it.vodId }.toSet()
        assertEquals(setOf("fresh", "unknownDuration"), ids)
    }

    @Test
    fun `progress is clamped and zero when duration unknown`() {
        assertEquals(0f, entry("x", pos = 100, dur = 0).progress, 0.0001f)
        assertEquals(0.5f, entry("x", pos = 900, dur = 1_800).progress, 0.0001f)
        assertEquals(1f, entry("x", pos = 2_000, dur = 1_800).progress, 0.0001f)
    }

    @Test
    fun `remove and clear`() {
        val s = store()
        s.upsert(entry("1"))
        s.upsert(entry("2"))
        s.remove("源A", "1")
        assertEquals(1, s.list().size)

        s.clear()
        assertTrue(s.list().isEmpty())
        assertEquals(0L, s.sizeBytes())
    }

    @Test
    fun `corrupt entry is skipped not fatal`() {
        val text = """{"items":[{"vod":"1","name":"好条目","pos":100},{"name":"缺id"},{"vod":"3"}]}"""
        val parsed = HistoryStore.parse(text)
        assertEquals(1, parsed.size)
        assertEquals("好条目", parsed[0].name)
        assertEquals(100L, parsed[0].positionMs)
        assertTrue(HistoryStore.parse("not json").isEmpty())
    }
}
