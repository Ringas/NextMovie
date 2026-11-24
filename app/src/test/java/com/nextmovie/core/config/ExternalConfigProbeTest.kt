package com.nextmovie.core.config

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional local samples supplied explicitly; never shipped with the application. */
class ExternalConfigProbeTest {
    @Test fun decodeProvidedSamples() {
        val dir = System.getenv("NEXTMOVIE_COMPAT_SAMPLES")?.let(::File)
        assumeTrue("No private compatibility samples supplied", dir?.isDirectory == true)
        for (file in dir!!.listFiles().orEmpty().filter { it.name.matches(Regex("config-[0-3]\\.bin")) }) {
            val root = JSONObject(ConfigDecoder.sanitizeJson(ConfigDecoder.decode(file.readBytes())))
            val sites = root.optJSONArray("sites")
            check(sites != null && sites.length() > 0) { "${file.name}: no sites" }
            File(dir, file.nameWithoutExtension + ".decoded.json").writeText(root.toString())
        }
    }
}
