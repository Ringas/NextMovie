package com.nextmovie.core.spider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nextmovie.core.config.Site
import com.nextmovie.core.spider.SpiderProtocol.ACTION_REPLY
import com.nextmovie.core.spider.SpiderProtocol.KEY_DATA
import com.nextmovie.core.spider.SpiderProtocol.KEY_ERROR
import com.nextmovie.core.spider.SpiderProtocol.KEY_OK
import com.nextmovie.core.spider.SpiderProtocol.toTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主进程侧的插件客户端：绑定 `:spider` 进程、发请求、收原始 JSON。
 *
 * 插件进程崩溃时 binder 会断开，这里统一转成可读错误，并且下次请求会自动重连。
 */
class SpiderClient(private val context: Context) {

    class SpiderException(message: String) : Exception(message)

    private val connectionState = kotlinx.coroutines.flow.MutableStateFlow<Messenger?>(null)
    private var inbound: Messenger?
        get() = connectionState.value
        set(value) { connectionState.value = value }
    private var binding = false
    private var bound = false
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<SpiderProtocol.Reply>>()
    private val nextId = AtomicInteger(1)
    private val lock = Any()

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        onReply(msg)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            android.util.Log.i("NextMovieTiming", "plugin-bind-connected")
            inbound = service?.let { Messenger(it) }
            synchronized(lock) { binding = false }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inbound = null
            failAll("插件进程已退出（可能是不兼容的插件导致崩溃），应用未受影响")
        }
    }

    private fun onReply(msg: Message) {
        val data = msg.data ?: return
        val id = data.getInt(SpiderProtocol.KEY_ID, -1)
        val deferred = pending.remove(id) ?: return
        deferred.complete(
            SpiderProtocol.Reply(
                ok = data.getBoolean(KEY_OK, false),
                data = data.getString(KEY_DATA).orEmpty(),
                error = data.getString(KEY_ERROR).orEmpty()
            )
        )
    }

    private fun failAll(reason: String) {
        val list = pending.values.toList()
        pending.clear()
        list.forEach {
            it.complete(SpiderProtocol.Reply(ok = false, data = "", error = reason))
        }
    }

    suspend fun preconnect() { ensureConnected() }

    fun startBinding() {
        synchronized(lock) {
            if (!binding && !bound) {
                android.util.Log.i("NextMovieTiming", "plugin-bind-request")
                binding = context.bindService(
                    Intent(context, SpiderService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
                bound = binding
                android.util.Log.i("NextMovieSpider", "bindService -> $binding")
            }
        }
    }

    private suspend fun ensureConnected(): Boolean {
        inbound?.let { return true }
        startBinding()
        // 模拟器/低端盒子上插件进程冷启动可能要十几秒，这里等到 20s 再判失败
        return withTimeoutOrNull(20_000) { connectionState.first { it != null } } != null
    }

    /** 发一个请求并等待插件进程返回原始 JSON。 */
    suspend fun request(
        action: Int,
        site: Site?,
        timeoutMs: Long = 90_000,
        configure: Bundle.() -> Unit = {}
    ): String {
        if (!ensureConnected()) {
            throw SpiderException("插件进程没有启动成功，请重试或换一个站点")
        }
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<SpiderProtocol.Reply>()
        pending[id] = deferred
        val args = Bundle().apply {
            site?.let { putString(SpiderProtocol.KEY_SITE, it.toTransport()) }
            configure()
        }
        val message = Message.obtain(null, action).apply {
            arg1 = id
            data = args
            replyTo = replyMessenger
        }
        try {
            inbound?.send(message) ?: throw SpiderException("插件进程不可用")
        } catch (e: Throwable) {
            pending.remove(id)
            inbound = null
            throw SpiderException("插件进程通讯失败：" + (e.message ?: e.javaClass.simpleName))
        }

        val reply = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
            if (!deferred.isCompleted) runCatching {
                inbound?.send(Message.obtain(null, SpiderProtocol.ACTION_CANCEL).apply { arg1 = id; replyTo = replyMessenger })
            }
        }
        if (reply == null) throw SpiderException("插件响应超时（${timeoutMs / 1000}s），可以重试")
        if (!reply.ok) throw SpiderException(reply.error.ifBlank { "插件调用失败" })
        return reply.data
    }

    /** Called before opening a playback window, after a plugin has retained a cancelled-login state. */
    suspend fun restartForLogin() {
        val previous = inbound
        request(SpiderProtocol.ACTION_RESTART, null, timeoutMs = 5_000)
        val connected = withTimeoutOrNull(25_000) {
            connectionState.first { it != null && it !== previous }
        } != null
        if (!connected) throw SpiderException("登录运行时重置超时，请重试")
    }

    fun releasePlaybackProxy(url: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return
        if (uri.host != "127.0.0.1" || uri.path !in listOf("/proxy", "/proxy/")) return
        runCatching {
            inbound?.send(Message.obtain(null, SpiderProtocol.ACTION_RELEASE_PROXY).apply {
                arg1 = uri.port
            })
        }
    }

    fun close() {
        inbound = null
        synchronized(lock) {
            if (bound) {
                runCatching { context.unbindService(connection) }
                binding = false
                bound = false
            }
        }
        failAll("插件进程已关闭")
    }
}
