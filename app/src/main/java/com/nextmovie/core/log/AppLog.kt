package com.nextmovie.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内运行日志（环形缓冲）。
 *
 * 电视端没有开 USB 调试时看不到 logcat，出问题只能靠「复现一次 → 截图给我」来回问。
 * 这里在内存里留最近 240 条事件，设置 → 关于 → 运行日志 可以直接看、复制、清空，
 * 报问题时把日志粘出来就够了，不用连 adb。
 *
 * 只记关键节点（启动、配置加载结果、更新检查、缓存清理、播放失败），不做全量埋点：
 * 全量会拖慢冷启动，也会把真正有用的那几行淹掉。
 */
object AppLog {

    data class Entry(val at: Long, val tag: String, val message: String)

    private const val CAPACITY = 240
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun record(tag: String, message: String) {
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), tag, message))
        }
    }

    fun recordThrowable(tag: String, message: String, t: Throwable) {
        record(tag, "$message：${t.javaClass.simpleName} ${t.message.orEmpty()}".trim())
    }

    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun count(): Int = synchronized(buffer) { buffer.size }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** 纯文本，直接进剪贴板。 */
    fun dump(): String {
        val entries = snapshot()
        if (entries.isEmpty()) return "（暂无日志）"
        val head = "NextMovie 运行日志 · ${entries.size} 条"
        return buildString {
            appendLine(head)
            entries.forEach { appendLine("${fmt.format(Date(it.at))} [${it.tag}] ${it.message}") }
        }
    }
}
