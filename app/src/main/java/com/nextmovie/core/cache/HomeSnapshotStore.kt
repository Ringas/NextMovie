package com.nextmovie.core.cache

import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodParser
import org.json.JSONObject
import java.io.File

/** Short-lived private home snapshots, so cold start can show real content before plugin networking. */
class HomeSnapshotStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var generation: Long = 0
        private set

    fun read(key: String, maxAgeMs: Long = 300_000): List<Vod>? = runCatching {
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
    @Synchronized fun invalidate(key: String) {
        generation++
        File(directory, "$key.json").delete()
    }

    @Synchronized fun clear() {
        generation++
        directory.listFiles()?.forEach { it.delete() }
    }
}
