package com.nextmovie.core.config

/** Strip formatting marks around a published digest, never the digest itself. */
object PluginArtifact {
    fun digest(value: String): String {
        val clean = value.replace(Regex("[\\s\\p{Cf}]"), "").lowercase()
        require(clean.isEmpty() || clean.matches(Regex("[0-9a-f]{32}"))) { "插件 MD5 格式无效" }
        return clean
    }
    fun split(raw: String): Pair<String, String> {
        val parts = raw.trim().split(';').map { it.trim() }
        return when {
            parts.size >= 3 && parts[1].equals("md5", true) -> parts[0] to digest(parts[2])
            parts.size == 2 -> parts[0] to digest(parts[1])
            else -> raw.trim() to ""
        }
    }
}
