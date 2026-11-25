package com.nextmovie.core.cache

import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodParser
import org.json.JSONObject
import java.io.File

/** Short-lived private home snapshots, so cold start can show real content before plugin networking. */
class HomeSnapshotStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var generation: Long = 0
        private set

    /** 冷启动先渲染用：快照最长可复用 24 小时，是否联网刷新由上层按 ageMs 决定。 */
    fun read(key: String, maxAgeMs: Long = 86_400_000): List<Vod>? = runCatching {
        val file = File(directory, "$key.json")
        if (now() - file.lastModified() !in 0..maxAgeMs) return null
        VodParser.list(JSONObject(file.readText())).takeIf { it.isNotEmpty() }
    }.getOrNull()
    @Synchronized fun write(key: String, values: List<Vod>, expectedGeneration: Long = generation) {
        if (generation != expectedGeneration || values.isEmpty()) return
        runCatching {
            directory.mkdirs()
            val file = File(directory, "$key.json")
            val temp = File(directory, "$key.tmp")
            temp.writeText(VodParser.toJsonList(values).toString())
            if (!temp.renameTo(file)) { temp.copyTo(file, true); temp.delete() }
            file.setLastModified(now())
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(16)?.forEach { it.delete() }
        }
    }
    /** 快照年龄，用来区分「可秒开」与「该后台刷新」。 */
    fun ageMs(key: String): Long = runCatching {
        val file = File(directory, "$key.json")
        if (!file.exists()) Long.MAX_VALUE else (now() - file.lastModified()).coerceAtLeast(0)
    }.getOrDefault(Long.MAX_VALUE)

    @Synchronized fun invalidate(key: String) {
        generation++
        File(directory, "$key.json").delete()
    }

    @Synchronized fun clear() {
        generation++
        directory.listFiles()?.forEach { it.delete() }
    }
}
