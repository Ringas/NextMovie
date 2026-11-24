package com.nextmovie.core.poster

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 海报解析结果缓存（按「归一化标题 + 年份」做 key）。
 *
 * 目的：首页滚动、反复进出详情页时不要重复打 B站接口。
 * 单文件 JSON、容量上限 2000 条、TTL 7 天，超出后按写入时间淘汰。
 */
class PosterCache(dir: File) {

    data class Entry(
        val poster: String,
        val backdrop: String,
        val overview: String,
        val savedAt: Long
    )

    private val file = File(dir.apply { mkdirs() }, "posters.json")
    private val entries = HashMap<String, Entry>()
    private var loaded = false
    private var dirty = false

    @Synchronized
    fun get(key: String): Entry? {
        ensureLoaded()
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.savedAt > TTL_MS) {
            entries.remove(key)
            dirty = true
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, entry: Entry) {
        ensureLoaded()
        entries[key] = entry
        dirty = true
        if (entries.size > MAX_ENTRIES) trim()
        flushLocked()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        dirty = false
        runCatching { file.delete() }
    }

    @Synchronized
    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    @Synchronized
    fun flush() {
        if (dirty) flushLocked()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return
        runCatching {
            val root = JSONObject(text)
            val items = root.optJSONArray("items") ?: return
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val key = o.optString("k", "")
                if (key.isEmpty()) continue
                entries[key] = Entry(
                    poster = o.optString("p", ""),
                    backdrop = o.optString("b", ""),
                    overview = o.optString("d", ""),
                    savedAt = o.optLong("t", 0L)
                )
            }
        }
    }

    private fun trim() {
        val keep = entries.entries.sortedByDescending { it.value.savedAt }.take(MAX_ENTRIES / 2)
        entries.clear()
        keep.forEach { entries[it.key] = it.value }
    }

    private fun flushLocked() {
        val items = JSONArray()
        entries.forEach { (key, entry) ->
            items.put(
                JSONObject()
                    .put("k", key)
                    .put("p", entry.poster)
                    .put("b", entry.backdrop)
                    .put("d", entry.overview)
                    .put("t", entry.savedAt)
            )
        }
        val payload = JSONObject().put("v", 1).put("items", items).toString()
        runCatching { file.writeText(payload) }
        dirty = false
    }

    private companion object {
        const val MAX_ENTRIES = 2000
        const val TTL_MS = 7L * 24 * 3600 * 1000
    }
}
