package com.nextmovie.core.config

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置文本解码。
 *
 * 兼容 TVBox / CatVod 生态里实际流通的几种「包装」形式：
 *  1. 裸 JSON / JSONC（带 `//` 注释、尾逗号）
 *  2. gzip 压缩体（部分站点直接吐 gzip 字节）
 *  3. UTF-8 BOM 前缀 / 尾部零字节
 *  4. `xxxxxxxx**` + Base64（前 8 位是占位标识，`**` 之后才是正文）
 *  5. `2423` 十六进制 + AES-CBC（`$#key#$ + 密文 + 13 字节 IV`）
 *  6. 把配置塞进 JPEG 尾部（先 `FF D9` 截尾，再按 4/5 处理）
 *
 * 全部按实测样本归纳，独立实现，不复制任何第三方源码。
 */
object ConfigDecoder {

    /** `xxxxxxxx**` 前缀可能出现在任意位置（例如 JPEG 二进制之后），因此不锚定行首。 */
    private val BASE64_WRAPPER = Regex("[A-Za-z0-9]{8}\\*\\*")
    private val WHITESPACE = Regex("\\s+")
    private const val IV_BYTES = 13

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun isDecodable(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** 解码任意响应体为配置文本。 */
    fun decode(bytes: ByteArray): String {
        val payload = when {
            isDecodable(bytes) -> gunzip(bytes)
            isJpeg(bytes) -> jpegTail(bytes)
            else -> bytes
        }
        return decodeText(String(payload, Charsets.UTF_8))
    }

    fun decodeText(input: String): String {
        var data = stripBom(input)
        if (data.isEmpty()) return data

        BASE64_WRAPPER.find(data)?.let { match ->
            // 前 8 位只是占位/校验标识，正文在 `**` 之后
            data = decodeBase64(data.substring(match.range.last + 1))
        }

        // 已经是 JSON 就不要再做任何「封装格式」猜测：
        // CMS 返回体里经常包含 "2423" 这类数字子串，之前会误判成十六进制报文直接报错。
        if (data.startsWith("{") || data.startsWith("[")) return data

        data = stripBom(data)

        // `2423` 私有格式只在真正能解出 JSON 时采用，否则保持原文（源站正常 JSON 不该被这里改写）
        val marker = data.indexOf("2423")
        if (marker >= 0) {
            runCatching { decode2423(data.substring(marker)) }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
                ?.let { data = stripBom(it) }
        }

        if (!data.startsWith("{") && !data.startsWith("[")) {
            // 有些站点会把 JSON 再包一层纯 Base64，只在能完整解出对象时采用
            val guess = runCatching { decodeBase64(data) }.getOrNull()
            if (guess != null && guess.trimStart().startsWith("{")) data = stripBom(guess)
        }
        return data
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** 配置藏在图片里：取 JPEG EOI 之后的附加负载。 */
    private fun jpegTail(bytes: ByteArray): ByteArray {
        for (i in bytes.size - 2 downTo 0) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
                return bytes.copyOfRange(i + 2, bytes.size)
            }
        }
        return ByteArray(0)
    }

    private fun stripBom(text: String): String =
        text.replaceFirst("\uFEFF", "").replace("\u0000", "").trim()

    private fun gunzip(bytes: ByteArray): ByteArray =
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (e: Exception) {
            throw DecodeException("gzip 解压失败", e)
        }

    private fun decodeBase64(raw: String): String {
        val cleaned = raw.replace(WHITESPACE, "")
        return try {
            String(java.util.Base64.getMimeDecoder().decode(cleaned), Charsets.UTF_8)
        } catch (e: Exception) {
            throw DecodeException("Base64 解码失败", e)
        }
    }

    /**
     * `2423` 私有格式。
     *
     * 形态：`2423`（即 ASCII `$#` 的十六进制）+ key + `#$`（`$#` 反写）+ AES 密文 + 13 字节 IV。
     * key / IV 不足 16 字节时用 `0` 右补足；全部按原始字节定位，避免二进制密文经过 String 转换被破坏。
     */
    private fun decode2423(hexText: String): String {
        val compact = hexText.replace(WHITESPACE, "")
        if (compact.length < 40) throw DecodeException("2423 报文过短")
        val raw = hexToBytes(compact)
        if (raw.size < 40) throw DecodeException("2423 报文过短")
        if (raw[0] != '$'.code.toByte() || raw[1] != '#'.code.toByte()) {
            throw DecodeException("2423 格式缺少 $# 头部")
        }

        val keyEnd = indexOf(raw, "#\$".toByteArray(), 2)
        if (keyEnd <= 2) throw DecodeException("2423 格式缺少 #$ 密钥标记")
        val key = String(raw, 2, keyEnd - 2, Charsets.UTF_8)

        // 尾部 IV 固定 13 字节；若密文不齐 16 字节，再尝试 16 字节 IV 兜底
        for (ivLen in intArrayOf(IV_BYTES, 16)) {
            val cipherLen = raw.size - keyEnd - 2 - ivLen
            if (cipherLen <= 0 || cipherLen % 16 != 0) continue
            val iv = String(raw, raw.size - ivLen, ivLen, Charsets.UTF_8)
            val cipher = raw.copyOfRange(keyEnd + 2, keyEnd + 2 + cipherLen)
            val plain = runCatching { aesCbcDecrypt(cipher, padTo16(key), padTo16(iv)) }.getOrNull() ?: continue
            val text = stripBom(String(plain, Charsets.UTF_8))
            if (text.startsWith("{")) return text
        }
        throw DecodeException("2423 解密失败（密钥或 IV 不匹配）")
    }

    private fun indexOf(source: ByteArray, target: ByteArray, from: Int): Int {
        if (target.isEmpty()) return -1
        var i = from
        while (i <= source.size - target.size) {
            var ok = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun padTo16(value: String): ByteArray {
        val chars = if (value.length >= 16) value.substring(0, 16) else value + "0".repeat(16 - value.length)
        return chars.toByteArray(Charsets.UTF_8)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        if (clean.length % 2 != 0) throw DecodeException("十六进制长度不是偶数")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    /**
     * 生态里大量配置是 JSONC：带 `//` 行注释、块注释，以及对象/数组尾逗号。
     * 这里做一次「字符串安全」的清洗，只在字符串外动刀。
     */
    fun sanitizeJson(text: String): String {
        val sb = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        // 去掉 `,}` / `,]`（允许中间有空白）
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }
}
