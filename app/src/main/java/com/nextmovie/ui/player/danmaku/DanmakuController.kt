package com.nextmovie.ui.player.danmaku

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 弹幕控制器脚手架。
 *
 * 完整实现依赖 `com.github.ctiao:DanmakuFlameMaster`（或同类库）。
 * 未引入依赖时 [isAvailable] 为 false，[attach] 返回空容器，不影响播放。
 * 接好依赖后在 [createEngine] 里换成真实 DanmakuView。
 */
class DanmakuController(private val context: Context) {

    private var host: FrameLayout? = null
    private var engine: Any? = null

    val isAvailable: Boolean
        get() = runCatching {
            Class.forName("master.flame.danmaku.ui.widget.DanmakuView")
            true
        }.getOrDefault(false)

    fun attach(parent: ViewGroup): View {
        val box = host ?: FrameLayout(context).also {
            it.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            it.isClickable = false
            it.isFocusable = false
            host = it
        }
        (box.parent as? ViewGroup)?.removeView(box)
        parent.addView(box)
        if (isAvailable && engine == null) {
            engine = createEngine(box)
        }
        return box
    }

    fun loadUrl(url: String?) {
        if (url.isNullOrBlank() || engine == null) return
        // 真实实现：解析 Bilibili XML / 通用 JSON 弹幕并 prepare
        android.util.Log.d(TAG, "danmaku load url=$url (engine pending full bind)")
    }

    fun seekTo(positionMs: Long) {
        // engine?.seekTo(positionMs)
    }

    fun resume() {
        // engine?.resume()
    }

    fun pause() {
        // engine?.pause()
    }

    fun release() {
        host?.let { (it.parent as? ViewGroup)?.removeView(it) }
        host = null
        engine = null
    }

    private fun createEngine(parent: FrameLayout): Any? = runCatching {
        val clazz = Class.forName("master.flame.danmaku.ui.widget.DanmakuView")
        val view = clazz.getConstructor(Context::class.java).newInstance(context) as View
        parent.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        view
    }.getOrNull()

    companion object {
        private const val TAG = "Danmaku"
    }
}
