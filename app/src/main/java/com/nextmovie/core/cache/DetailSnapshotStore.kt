package com.nextmovie.core.cache

import com.nextmovie.core.model.Episode
import com.nextmovie.core.model.PlayLine
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.model.VodParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Short-lived source details, never resolved player URLs. Keys are hashed by the caller. */
class DetailSnapshotStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var generation = 0L
        private set

    @Synchronized fun read(key: String): VodDetail? = runCatching {
        val file = File(directory, "$key.json")
        if (!file.isFile || file.length() > MAX_BYTES || now() - file.lastModified() !in 0 until TTL_MS) return null
        val root = JSONObject(file.readText())
        val vod = VodParser.list(root).firstOrNull() ?: return null
        val lines = root.getJSONArray("lines")
        VodDetail(vod, List(lines.length()) { index ->
            val line = lines.getJSONObject(index)
            val episodes = line.getJSONArray("episodes")
            PlayLine(line.getString("name"), List(episodes.length()) { i ->
                val ep = episodes.getJSONObject(i)
                Episode(ep.getString("name"), ep.getString("url"))
            })
        }).takeIf { it.lines.any { line -> line.episodes.isNotEmpty() } }
    }.getOrNull()

    @Synchronized fun write(key: String, detail: VodDetail, expectedGeneration: Long = generation) {
        if (generation != expectedGeneration || detail.lines.none { it.episodes.isNotEmpty() }) return
        runCatching {
            val root = VodParser.toJsonList(listOf(detail.vod))
            root.put("lines", JSONArray().apply {
                detail.lines.forEach { line -> put(JSONObject().put("name", line.name)
                    .put("episodes", JSONArray().apply {
                        line.episodes.forEach { put(JSONObject().put("name", it.name).put("url", it.url)) }
                    })) }
            })
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return
            directory.mkdirs()
            val file = File(directory, "$key.json")
            val temporary = File(directory, "$key.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) { temporary.copyTo(file, overwrite = true); temporary.delete() }
            file.setLastModified(now())
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.drop(24)?.forEach { it.delete() }
        }
    }

    @Synchronized fun clear() {
        generation++
        directory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val TTL_MS = 15 * 60 * 1000L
        private const val MAX_BYTES = 1024 * 1024
    }
}
