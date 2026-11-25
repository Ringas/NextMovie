package com.nextmovie.core.model

/**
 * 一条可播「线路槽位」：把不同源里的同一集收成一张可切换的清单。
 * 播放器拿到它就能在不回详情页的前提下换源/换线路，并从同一时间点续播。
 */
data class PlaySlot(
    val siteKey: String,
    val siteName: String,
    val lineName: String,
    /** 画质标签，如 4K 蓝光 / 1080P / 全 20 集 */
    val badge: String,
    /** 排序分（越高越优先），仅用于展示与默认选中 */
    val score: Int,
    val episodeName: String,
    val episodeUrl: String
)
