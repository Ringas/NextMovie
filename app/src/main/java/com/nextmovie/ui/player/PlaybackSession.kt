package com.nextmovie.ui.player

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 跨页面存的播放会话（对齐 Marco 的「返回详情后影片继续播」）。
 *
 * Marco 播放器是叠在详情页上的一层：退出播放只收起播控层，ExoPlayer 不销毁，
 * 详情页的剧照区继续显示正在播放的画面。这里用同一个 PlayerView 实例在
 * 播放页与详情页之间「搬家」，画面不会因重建 surface 而重新起播。
 */
class PlaybackSession(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private var playerView: PlayerView? = null

    /** 当前会话是否仍在播放（返回详情后置 true，进首页/换片时置 false） */
    var alive by mutableStateOf(false)

    var vodName: String? = null
    var episodeName: String? = null

    fun view(context: Context): PlayerView {
        val v = playerView ?: PlayerView(context).apply {
            player = this@PlaybackSession.player
            useController = false
            keepScreenOn = true
        }.also { playerView = it }
        // 同一个 PlayerView 在两个页面之间复用：先摘掉旧父容器，避免 IllegalStateException
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    fun release() {
        alive = false
        playerView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        runCatching { player.release() }
    }
}
