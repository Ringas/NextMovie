package com.nextmovie.core.spider.native

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 从 spider.jar（或伪装 jpg）中只读字符串，不执行代码。
 * 用于发现 api 基址、signKey、User-Agent 等常量（对齐 Marco guard-extractor 的数据面思路）。
 */
object GuardStringScanner {

    data class Findings(
        val httpsUrls: List<String>,
        val likelySignKeys: List<String>,
        val userAgents: List<String>
    )

    fun scan(file: File, limit: Int = 400): Findings {
        if (!file.exists() || file.length() < 64) {
            return Findings(emptyList(), emptyList(), emptyList())
        }
        val blob = runCatching { readZipOrRaw(file) }.getOrDefault(ByteArray(0))
        if (blob.isEmpty()) return Findings(emptyList(), emptyList(), emptyList())
        val strings = extractPrintable(blob, minLen = 8, maxCount = 8_000)
        // URL 常常不是整串的开头（"hello https://..." 这种），按正则从串里挖出来
        val urls = strings.flatMap { s -> URL_RE.findAll(s).map { it.value } }.filter {
            !it.contains("github.com", true) &&
                !it.contains("googleapis", true) &&
                it.length < 200
        }.distinct().take(limit)

        val uas = strings.filter {
            it.startsWith("Mozilla/", true) || it.contains("okhttp", true)
        }.distinct().take(20)

        // signKey 启发式：16–64 位字母数字，且附近上下文不像 URL
        val keys = strings.filter { s ->
            s.length in 16..64 &&
                s.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
                !s.startsWith("http", true) &&
                !s.contains("android", true)
        }.distinct().take(40)

        return Findings(urls, keys, uas)
    }

    private val URL_RE = Regex("""https?://[^\s\"'<>\\)\]]+""")

    private fun readZipOrRaw(file: File): ByteArray {
        // 可能是 PK zip / 或尾部 zip（jpg 伪装）
        val bytes = file.readBytes()
        val pk = findPk(bytes)
        if (pk >= 0) {
            val out = ByteArrayOutputStream()
            // 写临时截取
            val tmp = File(file.parentFile, file.name + ".scan.zip")
            try {
                tmp.writeBytes(bytes.copyOfRange(pk, bytes.size))
                ZipFile(tmp).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        if (e.size > 8_000_000) continue
                        val name = e.name.lowercase()
                        if (name.endsWith(".dex") || name.endsWith(".json") ||
                            name.endsWith(".txt") || name.endsWith(".xml") ||
                            name.contains("config")
                        ) {
                            zip.getInputStream(e).use { ins -> out.write(ins.readBytes()) }
                        }
                    }
                }
            } finally {
                tmp.delete()
            }
            if (out.size() > 0) return out.toByteArray()
        }
        return bytes
    }

    private fun findPk(bytes: ByteArray): Int {
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) return 0
        // EOI + PK or scan last 2MB
        val start = (bytes.size - 2_000_000).coerceAtLeast(0)
        var i = start
        while (i < bytes.size - 4) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte()
            ) return i
            i++
        }
        return -1
    }

    private fun extractPrintable(data: ByteArray, minLen: Int, maxCount: Int): List<String> {
        val out = ArrayList<String>(maxCount)
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= minLen && out.size < maxCount) out += sb.toString()
            sb.setLength(0)
        }
        for (b in data) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) sb.append(c.toChar()) else flush()
        }
        flush()
        // UTF-16LE 粗扫
        sb.setLength(0)
        var i = 0
        while (i + 1 < data.size && out.size < maxCount) {
            val c = (data[i].toInt() and 0xff) or ((data[i + 1].toInt() and 0xff) shl 8)
            if (c in 0x20..0x7e) sb.append(c.toChar()) else {
                if (sb.length >= minLen) out += sb.toString()
                sb.setLength(0)
            }
            i += 2
        }
        return out
    }
}
