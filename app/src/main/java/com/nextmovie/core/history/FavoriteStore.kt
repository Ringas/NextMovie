package com.nextmovie.core.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条收藏。以 `siteKey + vodId` 作为唯一键：同一部片在不同源里是两条收藏，
 * 因为详情与播放线路都跟源绑定。
 */
data class FavoriteEntry(
    val siteKey: String,
    val vodId: String,
    val name: String,
    val pic: String = "",
    val remark: String = "",
    val year: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    val key: String get() = siteKey + "|" + vodId
}

/**
 * 收藏夹：与 [HistoryStore] 同一套实现思路 —— 单个 JSON 文件，写入即落盘，
 * 不做数据库（数据量小、查询只有按时间排序）。
 */
class FavoriteStore(private val file: File) {

    private val lock = Any()
    private var cache: MutableList<FavoriteEntry>? = null

    fun list(): List<FavoriteEntry> = synchronized(lock) {
        load().sortedByDescending { it.addedAt }
    }

    fun count(): Int = list().size

    fun find(siteKey: String, vodId: String): FavoriteEntry? =
        list().firstOrNull { it.siteKey == siteKey && it.vodId == vodId }

    fun contains(siteKey: String, vodId: String): Boolean = find(siteKey, vodId) != null

    /** 返回操作后的状态：true = 已收藏。 */
    fun toggle(entry: FavoriteEntry): Boolean = synchronized(lock) {
        val items = load().toMutableList()
        val exists = items.any { it.siteKey == entry.siteKey && it.vodId == entry.vodId }
        if (exists) {
            items.removeAll { it.siteKey == entry.siteKey && it.vodId == entry.vodId }
            save(items)
            false
        } else {
            items.add(0, entry)
            while (items.size > MAX_ENTRIES) items.removeAt(items.lastIndex)
            save(items)
            true
        }
    }

    fun add(entry: FavoriteEntry) {
        synchronized(lock) {
            val items = load().toMutableList()
            items.removeAll { it.siteKey == entry.siteKey && it.vodId == entry.vodId }
            items.add(0, entry)
            while (items.size > MAX_ENTRIES) items.removeAt(items.lastIndex)
            save(items)
        }
    }

    fun merge(incoming: List<FavoriteEntry>) = synchronized(lock) {
        if (incoming.isEmpty()) return@synchronized
        val merged = (load() + incoming).sortedByDescending { it.addedAt }
            .distinctBy { it.key }.take(MAX_ENTRIES)
        save(merged)
    }

    fun remove(siteKey: String, vodId: String) {
        synchronized(lock) {
            val items = load().toMutableList()
            if (items.removeAll { it.siteKey == siteKey && it.vodId == vodId }) save(items)
        }
    }

    fun clear() {
        synchronized(lock) {
            cache = mutableListOf()
            runCatching { file.delete() }
        }
    }

    fun sizeBytes(): Long = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)

    // ---- 持久化 ----

    private fun load(): List<FavoriteEntry> {
        cache?.let { return it }
        val items = runCatching {
            if (!file.exists()) emptyList() else parse(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
        cache = items.toMutableList()
        return items
    }

    private fun save(items: List<FavoriteEntry>) {
        cache = items.toMutableList()
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(toJson(items).toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(toJson(items).toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 300

        fun toJson(items: List<FavoriteEntry>): JSONObject {
            val arr = JSONArray()
            items.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("site", e.siteKey)
                        .put("vod", e.vodId)
                        .put("name", e.name)
                        .put("pic", e.pic)
                        .put("remark", e.remark)
                        .put("year", e.year)
                        .put("at", e.addedAt)
                )
            }
            return JSONObject().put("items", arr)
        }

        /** 容错解析：单条坏数据只丢这一条，不让整个收藏夹清空。 */
        fun parse(text: String): List<FavoriteEntry> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = root.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<FavoriteEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val vod = o.optString("vod", "").trim()
                val name = o.optString("name", "").trim()
                if (vod.isEmpty() || name.isEmpty()) continue
                out += FavoriteEntry(
                    siteKey = o.optString("site", ""),
                    vodId = vod,
                    name = name,
                    pic = o.optString("pic", ""),
                    remark = o.optString("remark", ""),
                    year = o.optString("year", ""),
                    addedAt = o.optLong("at", 0L)
                )
            }
            return out
        }
    }
}
