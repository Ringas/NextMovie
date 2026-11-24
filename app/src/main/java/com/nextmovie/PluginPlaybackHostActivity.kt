package com.nextmovie

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.*
import android.widget.TextView
import com.nextmovie.core.spider.PluginDialogs
import com.nextmovie.core.spider.PluginRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** A foreground window in the plugin process, also available to plugins using Init.activity(). */
class PluginPlaybackHostActivity : Activity() {
    private var receiver: ResultReceiver? = null
    private var attached = false
    private var ready = false
    private val commands = Messenger(Handler(Looper.getMainLooper()) { finish(); true })
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION")
        receiver = intent.getParcelableExtra("receiver")
        if (receiver == null) { finish(); return }
        PluginRuntime.acquire(this)
        attached = true
        PluginDialogs.attach(this)
        setContentView(TextView(this).apply {
            text = "正在解析播放…\n\n若需要网盘登录，请在来源弹窗中扫码。按返回可取消。"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(16, 18, 24))
            setPadding(64, 64, 64, 64)
        })
    }
    override fun onResume() {
        super.onResume()
        PluginDialogs.attach(this)
        if (!ready) {
            ready = true
            receiver?.send(1, Bundle().apply { putBinder("commands", commands.binder) })
        }
    }
    override fun onDestroy() {
        receiver?.send(2, Bundle())
        PluginDialogs.detach(this)
        if (attached) PluginRuntime.release()
        super.onDestroy()
    }
}

/** Start resolving only after the remote Activity is resumed; keep it alive until resolution ends. */
object PluginPlaybackWindow {
    suspend fun <T> resolve(context: Context, block: suspend () -> T): T = coroutineScope {
        val ready = CompletableDeferred<Messenger>()
        val closed = CompletableDeferred<Unit>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if (code == 1) data?.getBinder("commands")?.let {
                    val command = Messenger(it)
                    if (!ready.complete(command)) runCatching { command.send(Message.obtain(null, 1)) }
                }
                if (code == 2) closed.complete(Unit)
            }
        }
        var commands: Messenger? = null
        try {
            context.startActivity(Intent(context, PluginPlaybackHostActivity::class.java).putExtra("receiver", receiver))
            commands = withTimeout(20_000) {
                select { ready.onAwait { it }; closed.onAwait { throw CancellationException("已取消播放") } }
            }
            val work = async { block() }
            select { work.onAwait { it }; closed.onAwait { work.cancel(); throw CancellationException("已取消播放") } }
        } finally {
            ready.cancel()
            commands?.let { runCatching { it.send(Message.obtain(null, 1)) } }
        }
    }
}
