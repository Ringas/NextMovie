package com.nextmovie.core.spider

import com.quickjs.QuickJS
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** Adapter for the pinned QuickJS Android binding, which exposes no Java job-queue API. */
internal object QuickJsJobs {
    private val runtimePointer by lazy {
        QuickJS::class.java.getDeclaredField("runtimePtr").apply { isAccessible = true }
    }
    private val executePendingJob by lazy {
        NativeLibrary.getInstance("quickjs").getFunction("JS_ExecutePendingJob")
    }

    // QuickJS requires that job execution and evaluation run on the same runtime thread.
    fun complete(runtime: QuickJS, readResult: () -> String): String {
        val pointer = Pointer(runtimePointer.getLong(runtime))
        val jobContext = PointerByReference()
        val deadline = System.nanoTime() + 2_000_000_000L
        repeat(4096) {
            readResult().takeIf { it.isNotEmpty() }?.let { return it }
            check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                "Promise 执行超时"
            }
            val status = executePendingJob.invokeInt(arrayOf(pointer, jobContext))
            check(status >= 0) { "JavaScript Promise 任务执行失败" }
            check(status != 0) { "Promise 未完成，脚本可能依赖宿主未提供的异步 API" }
        }
        error("Promise 任务数超过限制")
    }

    /**
     * 只推进一次微任务队列，返回状态：1 = 执行了一个 job，0 = 当前没有待执行 job，-1 = 出错。
     *
     * 定时器场景下 host 需要自己驱动循环：没有 job 时睡几毫秒、到点再灌入回调，
     * 不能像 [complete] 那样「没有 job 就判死」。
     */
    fun step(runtime: QuickJS): Int {
        val pointer = Pointer(runtimePointer.getLong(runtime))
        val jobContext = PointerByReference()
        return runCatching { executePendingJob.invokeInt(arrayOf(pointer, jobContext)) }.getOrDefault(-1)
    }
}
