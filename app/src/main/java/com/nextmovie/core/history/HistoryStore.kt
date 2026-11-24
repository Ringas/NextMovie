package com.nextmovie.core.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条观看记录。以 `siteKey + vodId` 作为唯一键：同一个剧在不同源里是两条记录，
 * 因为播放地址与线路名都跟源绑定。
 */
data class HistoryEntry(
    val siteKey: String,
    val vodId: String,
    val name: String,
    val pic: String = "",
    val remark: String = "",
    val playFrom: String = "",
    val episodeName: String = "",
    val episodeUrl: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {

    val key: String get() = siteKey + "|" + vodId

    /** 0..1；时长未知（HLS 常见）时返回 0，UI 不画进度条。 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 值得出现在「继续观看」里的条目：看过一会儿，且没有基本看完。 */
    val resumable: Boolean
        get() = positionMs >= MIN_RESUME_MS &&
            (durationMs <= 0L || progress < FINISHED_RATIO)

    companion object {
        const val MIN_RESUME_MS = 30_000L
        const val FINISHED_RATIO = 0.95f
    }
}

/**
 * 观看历史：单个 JSON 文件，写入即落盘。
 *
 * 没有用 Room —— 这个数据量（上限 120 条）与查询复杂度（全量按时间排序）不需要数据库，
 * 一个文件既省依赖也省一次 schema 迁移。
 */
class HistoryStore(private val file: File) {

    private val lock = Any()
    private var cache: MutableList<HistoryEntry>? = null

    fun list(): List<HistoryEntry> = synchronized(lock) {
        load().sortedByDescending { it.updatedAt }
    }

    fun continueWatching(limit: Int = 10): List<HistoryEntry> =
        list().filter { it.resumable }.take(limit)

    fun find(siteKey: String, vodId: String): HistoryEntry? =
        list().firstOrNull { it.siteKey == siteKey && it.vodId == vodId }

    /** 同键覆盖并把该条移到最前；超过上限时淘汰最旧的。 */
    fun upsert(entry: HistoryEntry) {
        synchronized(lock) {
            val items = load().toMutableList()
            items.removeAll { it.siteKey == entry.siteKey && it.vodId == entry.vodId }
            items.add(0, entry)
            while (items.size > MAX_ENTRIES) items.removeAt(items.lastIndex)
            save(items)
        }
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

    private fun load(): List<HistoryEntry> {
        cache?.let { return it }
        val items = runCatching {
            if (!file.exists()) emptyList() else parse(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
        cache = items.toMutableList()
        return items
    }

    private fun save(items: List<HistoryEntry>) {
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
        const val MAX_ENTRIES = 120

        fun toJson(items: List<HistoryEntry>): JSONObject {
            val arr = JSONArray()
            items.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("site", e.siteKey)
                        .put("vod", e.vodId)
                        .put("name", e.name)
                        .put("pic", e.pic)
                        .put("remark", e.remark)
                        .put("from", e.playFrom)
                        .put("ep", e.episodeName)
                        .put("url", e.episodeUrl)
                        .put("pos", e.positionMs)
                        .put("dur", e.durationMs)
                        .put("at", e.updatedAt)
                )
            }
            return JSONObject().put("items", arr)
        }

        /** 容错解析：单条坏数据只丢这一条，不让整个历史清空。 */
        fun parse(text: String): List<HistoryEntry> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val arr = root.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val vod = o.optString("vod", "").trim()
                val name = o.optString("name", "").trim()
                if (vod.isEmpty() || name.isEmpty()) continue
                out += HistoryEntry(
                    siteKey = o.optString("site", ""),
                    vodId = vod,
                    name = name,
                    pic = o.optString("pic", ""),
                    remark = o.optString("remark", ""),
                    playFrom = o.optString("from", ""),
                    episodeName = o.optString("ep", ""),
                    episodeUrl = o.optString("url", ""),
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    updatedAt = o.optLong("at", 0L)
                )
            }
            return out
        }
    }
}
