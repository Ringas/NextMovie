package com.nextmovie.core.spider

import android.content.Context
import java.io.File

/**
 * 最近一次插件 detailContent 的入参与返回。
 *
 * 首页能列片、详情返回空，几乎都是「二级 dex 没解出来」：
 * 主 dex 里的 home/category 正常，detail/play 走解密后的实现，拿不到就回空串。
 * 把入参 id 和返回长度摊给用户看，才能区分「id 传错」和「Init 没完成」。
 */
object DetailRaw {
    private const val NAME = "detail-raw.txt"

    fun save(context: Context, id: String, raw: String, tag: String = "") {
        val body = raw.trim()
        val text = buildString {
            append("id=").append(id.take(90))
            if (tag.isNotBlank()) append(" (").append(tag).append(')')
            append(" · len=").append(body.length)
            append('\n')
            append(body.take(180).replace('\n', ' ').ifBlank { "（空返回）" })
        }
        runCatching { File(context.filesDir, NAME).writeText(text) }
    }

    fun read(context: Context): String = runCatching {
        val f = File(context.filesDir, NAME)
        if (f.exists()) f.readText().trim() else ""
    }.getOrDefault("")
}
