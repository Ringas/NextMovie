package com.nextmovie.core.spider

import org.json.JSONObject

/** 纯函数，便于单测：export 清洗与 ESM 判定。 */
object JsScriptSupport {

    fun isEsModule(code: String, scriptUrl: String): Boolean {
        if (scriptUrl.substringBefore('?').endsWith(".mjs", true)) return true
        if (Regex("""(?m)^\s*import\s+(?!\()""").containsMatchIn(code)) return true
        if (Regex("""\bexport\s+default\b""").containsMatchIn(code)) return true
        if (Regex("""\bexport\s+\{""").containsMatchIn(code)) return true
        return false
    }

    fun sanitizeExports(src: String): String {
        var text = src
        text = text.replace(Regex("""(?m)^([ \t]*)export[ \t]+default[ \t]+"""), "\$1var __spider = ")
        text = text.replace(Regex("""\bexport\s+default\s*\{"""), "var __spider = {")
        text = text.replace(Regex("""\bexport\s+default\s+"""), "var __spider = ")
        text = text.replace(
            Regex("""(?m)^([ \t]*)export[ \t]+(?=(?:async\s+)?function\b|(?:class|const|let|var)\b)"""),
            "\$1"
        )
        text = text.replace(Regex("""(?m)^\s*export\s*\{([^}]*)\}\s*;?""")) { match ->
            val members = match.groupValues[1].split(',').mapNotNull { member ->
                val names = member.trim().split(Regex("""\s+as\s+"""))
                if (names.first().isBlank()) null
                else "${JSONObject.quote(names.last().trim())}:${names.first().trim()}"
            }
            "var __spider = {${members.joinToString(",")}};\n"
        }
        return text
    }
}
