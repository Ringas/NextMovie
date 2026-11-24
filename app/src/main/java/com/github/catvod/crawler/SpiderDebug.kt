package com.github.catvod.crawler

import android.util.Log

/**
 * 插件侧日志 ABI（独立实现）。
 *
 * 很多第三方 JAR 里直接调 SpiderDebug.log(...)，缺这个类会在加载时抛
 * NoClassDefFoundError。这里只补齐最常用的静态方法，转到本项目的日志 tag。
 *
 * 注意：方法体必须显式返回 Unit。Log.d 返回 Int，表达式体会编出 (...)I 签名，
 * 而插件要的是 (...)V，运行期抛 NoSuchMethodError。
 */
object SpiderDebug {

    private const val TAG = "NextMovie/Spider"

    @JvmStatic
    fun log(error: Throwable?): Unit { Log.e(TAG, error?.message.orEmpty(), error) }

    @JvmStatic
    fun log(msg: String?): Unit { Log.d(TAG, msg.orEmpty()) }

    @JvmStatic
    fun log(tag: String?, msg: String?): Unit { Log.d(tag ?: TAG, msg.orEmpty()) }

    @JvmStatic
    fun i(msg: String?): Unit { Log.i(TAG, msg.orEmpty()) }

    @JvmStatic
    fun i(tag: String?, msg: String?): Unit { Log.i(tag ?: TAG, msg.orEmpty()) }

    @JvmStatic
    fun d(msg: String?): Unit { Log.d(TAG, msg.orEmpty()) }

    @JvmStatic
    fun d(tag: String?, msg: String?): Unit { Log.d(tag ?: TAG, msg.orEmpty()) }

    @JvmStatic
    fun w(msg: String?): Unit { Log.w(TAG, msg.orEmpty()) }

    @JvmStatic
    fun w(tag: String?, msg: String?): Unit { Log.w(tag ?: TAG, msg.orEmpty()) }

    @JvmStatic
    fun e(msg: String?): Unit { Log.e(TAG, msg.orEmpty()) }

    @JvmStatic
    fun e(tag: String?, msg: String?): Unit { Log.e(tag ?: TAG, msg.orEmpty()) }

    @JvmStatic
    fun e(msg: String?, tr: Throwable?): Unit { Log.e(TAG, msg.orEmpty(), tr) }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Unit { Log.e(tag ?: TAG, msg.orEmpty(), tr) }
}
