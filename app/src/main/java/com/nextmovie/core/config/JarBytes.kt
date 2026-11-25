package com.nextmovie.core.config

import java.security.MessageDigest

/**
 * 从下载体中取出真正的 jar 字节，对齐 TVBox / FongMi 常见的 spider 发布形态：
 *
 * 1. URL 虽是 `.jpg` / `.png`，内容仍是标准 zip（`PK\x03\x04`）——当前生态最常见
 * 2. 真 JPEG，jar 接在 EOI（`FF D9`）之后
 * 3. 其它包装：在文件中扫描第一个 `PK\x03\x04` 作为 zip 起点
 *
 * MD5 校验：优先对**原始下载体**（配置里写的 md5 通常指整包），
 * 若不匹配再对**抽出的 jar** 校验一次，兼容少数「只 hash 内层」的写法。
 */
object JarBytes {

    data class Result(
        /** 可供 DexClassLoader 使用的 zip/jar 内容 */
        val jar: ByteArray,
        /** 是否从图片/扫描中剥离得到（非原样） */
        val extracted: Boolean,
        /** 最终通过校验时使用的摘要（小写 hex），可能为空（未提供期望值） */
        val matchedDigest: String
    )

    class InvalidJarException(message: String) : Exception(message)

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    /**
     * @param expectedMd5 配置 `;md5;` 后的值，可空；空则只做格式检查
     */
    fun materialize(download: ByteArray, expectedMd5: String = ""): Result {
        if (download.size < 1024) {
            throw InvalidJarException("插件体积异常（${download.size}B）")
        }
        val want = expectedMd5.trim().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        val fullDigest = md5Hex(download)

        val jar = extractZipPayload(download)
            ?: throw InvalidJarException("不是有效的 jar 包（未找到 zip 头）")

        if (jar.size < 1024) {
            throw InvalidJarException("抽出的 jar 过小（${jar.size}B）")
        }

        if (want.isEmpty()) {
            return Result(jar, extracted = jar !== download && !download.contentEquals(jar), matchedDigest = "")
        }

        if (fullDigest == want) {
            return Result(jar, extracted = !isZip(download), matchedDigest = fullDigest)
        }
        val jarDigest = md5Hex(jar)
        if (jarDigest == want) {
            return Result(jar, extracted = true, matchedDigest = jarDigest)
        }
        throw InvalidJarException(
            "MD5 校验失败（期望 ${want.take(8)}…，整包 ${fullDigest.take(8)}…，内层 ${jarDigest.take(8)}…）"
        )
    }

    /** 从任意下载体中定位 zip/jar 载荷；找不到返回 null。 */
    fun extractZipPayload(bytes: ByteArray): ByteArray? {
        if (isZip(bytes)) return bytes
        if (isJpeg(bytes)) {
            val eoi = lastJpegEoi(bytes)
            if (eoi >= 0 && eoi + 2 < bytes.size) {
                val tail = bytes.copyOfRange(eoi + 2, bytes.size)
                if (isZip(tail)) return tail
                findZipOffset(tail)?.let { off ->
                    return tail.copyOfRange(off, tail.size)
                }
            }
        }
        val off = findZipOffset(bytes) ?: return null
        return if (off == 0) bytes else bytes.copyOfRange(off, bytes.size)
    }

    private fun lastJpegEoi(bytes: ByteArray): Int {
        var i = bytes.size - 2
        while (i >= 0) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) return i
            i--
        }
        return -1
    }

    /** 扫描 `PK\x03\x04`（local file header）。 */
    private fun findZipOffset(bytes: ByteArray): Int? {
        val n = bytes.size - 4
        var i = 0
        while (i <= n) {
            if (bytes[i] == 'P'.code.toByte() &&
                bytes[i + 1] == 'K'.code.toByte() &&
                bytes[i + 2] == 0x03.toByte() &&
                bytes[i + 3] == 0x04.toByte()
            ) return i
            i++
        }
        return null
    }
}
