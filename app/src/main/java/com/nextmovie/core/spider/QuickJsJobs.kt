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
}
