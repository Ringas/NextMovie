package com.nextmovie.core.match

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayLine
import com.nextmovie.core.model.VodDetail

/**
 * 源/线路质量打分。
 *
 * 目标不是"猜谁清晰"，而是把**能验证的信号**加起来，得到一个可解释的排序：
 *  - 线路名里的画质词（蓝光/4K/1080/高清/标清）
 *  - 首集地址的容器（mp4 比 m3u8 少一层分片索引，起播更稳；两者都算直链）
 *  - 剧集完整性（集数越多越可能是完整版）
 *  - 本站历史播放成功率（真跑出来的结果，权重最高）
 *  - CMS 直连比插件宿主少一层进程隔离
 *
 * 打分只用于排序与展示，不改变可播性判断：任何源只要能拿到线路就仍可手动切换。
 */
object SourceRanker {

    data class LineQuality(val score: Int, val label: String)

    private val UHD = listOf("4k", "2160", "蓝光", "原画", "uhd", "remux")
    private val FHD = listOf("1080", "超清", "蓝光", "fhd")
    private val HD = listOf("高清", "hd", "720", "高码")
    private val SD = listOf("标清", "流畅", "480", "试看", "预告")

    private fun matches(name: String, tokens: List<String>): Boolean =
        tokens.any { name.contains(it, ignoreCase = true) }

    fun lineQuality(line: PlayLine): LineQuality {
        val name = line.name
        val episodes = line.episodes
        var score = 10
        var label = ""

        when {
            matches(name, UHD) -> { score += 9; label = "4K 蓝光" }
            matches(name, FHD) -> { score += 7; label = "1080P" }
            matches(name, HD) -> { score += 4; label = "高清" }
            matches(name, SD) -> { score -= 6; label = "标清" }
        }

        val sample = episodes.firstOrNull()?.url.orEmpty().lowercase()
        when {
            sample.contains(".mp4") -> score += 3
            sample.contains(".m3u8") -> score += 2
            sample.contains(".flv") || sample.contains(".ts") -> score += 1
            sample.startsWith("http") -> score += 1
        }

        when {
            episodes.size >= 20 -> score += 3
            episodes.size >= 8 -> score += 2
            episodes.size >= 2 -> score += 1
        }
        if (episodes.isEmpty()) score -= 20

        if (label.isBlank()) {
            label = when {
                episodes.size >= 20 -> "全 ${episodes.size} 集"
                episodes.isNotEmpty() -> "${episodes.size} 集"
                else -> "无剧集"
            }
        }
        return LineQuality(score, label)
    }

    /** 一个源的整体分：取它最好的一条线路，再叠加"源本身是否靠谱"的修正。 */
    fun sourceScore(site: Site, detail: VodDetail, successRate: Double, isConfigured: Boolean): LineQuality {
        val best = detail.lines.maxByOrNull { lineQuality(it).score }
        val bestQuality = best?.let { lineQuality(it) } ?: LineQuality(-100, "无线路")
        var score = bestQuality.score
        score += (successRate.coerceIn(0.0, 1.0) * 6).toInt()
        if (site.isCms) score += 2
        if (isConfigured) score += 1
        if (detail.lines.size >= 3) score += 1
        return LineQuality(score, bestQuality.label)
    }

    fun bestLineIndex(detail: VodDetail): Int {
        if (detail.lines.isEmpty()) return 0
        var best = 0
        var bestScore = Int.MIN_VALUE
        detail.lines.forEachIndexed { index, line ->
            val score = lineQuality(line).score
            if (score > bestScore) { bestScore = score; best = index }
        }
        return best
    }
}
