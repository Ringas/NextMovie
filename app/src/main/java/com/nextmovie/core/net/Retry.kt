package com.nextmovie.core.net

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 源头请求的自动重试：只在「临时的网络/服务端故障」重试一次。
 *
 * 不重试的：403/404（源站明确拒绝，重试只会被当成盗链）、解析错误、脚本错误。
 * 单次重试而不是指数退避，是因为电视端体感比「省流量」重要：用户等 1.2 秒重连，
 * 比看到错误页再手点「重试」要好。
 */
object Retry {

    suspend fun <T> once(delayMs: Long = 1_200, block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Throwable) {
            if (!isTransient(e)) throw e
            android.util.Log.d("NextMovieRetry", "首次失败（${e.javaClass.simpleName}: ${e.message}），${delayMs}ms 后重试")
            delay(delayMs)
            block()
        }
    }

    private fun isTransient(e: Throwable): Boolean = when (e) {
        is Http.HttpError -> e.code >= 500 || e.code == 408 || e.code == 429
        is SocketTimeoutException, is UnknownHostException, is ConnectException -> true
        is IOException -> true
        else -> false
    }
}
