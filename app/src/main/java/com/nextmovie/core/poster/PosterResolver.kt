package com.nextmovie.core.poster

import com.nextmovie.core.match.TitleMatcher
import com.nextmovie.core.model.Vod
import com.nextmovie.core.tmdb.TmdbClient

/**
 * 海报链路总入口，按「先糊后清」的顺序工作：
 *
 * 1. **L0 尺寸升级**：同步、零请求，列表加载时立刻套用；
 * 2. **TMDB**（若用户填了 Token）：最准、海报最干净，优先；
 * 3. **L1 B站 PGC**：无 Key 兜底，标题 + 年份校验通过才采用；
 * 4. 都失败就保持源图。
 *
 * 所有对外方法都是幂等的：重复调用不会把图来回改。
 */
class PosterResolver(
    private val tmdb: TmdbClient,
    private val bilibili: BilibiliPoster,
    private val cache: PosterCache
) {

    /** 只做 L0，用于列表首屏毫秒级出图。 */
    fun upgrade(vod: Vod, detail: Boolean): Vod = vod.copy(
        pic = PosterUrlRules.upgrade(vod.pic, detail),
        backdrop = PosterUrlRules.upgrade(vod.backdrop, detail)
    )

    /**
     * 完整解析：L0 → TMDB（可选）→ B站（开关打开时）。
     * 返回**新的** Vod，调用方用结果替换原对象即可。
     */
    suspend fun resolve(vod: Vod, detail: Boolean, allowBilibili: Boolean): Vod {
        val base = upgrade(vod, detail)
        if (base.name.isBlank()) return base

        if (tmdb.isConfigured()) {
            val enriched = runCatching { tmdb.enrich(base) }.getOrDefault(base)
            if (enriched.tmdb != null) return enriched
        }

        // 源图已经是豆瓣/TMDB 的大图，就直接用，不再打 B站（省请求也能避开 412）
        if (!allowBilibili || (base.pic.isNotBlank() && PosterUrlRules.looksDecent(base.pic))) return base

        val key = cacheKey(vod)
        val cached = cache.get(key)
        val entry = cached ?: run {
            val hit = runCatching { bilibili.find(vod.name, base.displayYear) }.getOrNull()
                ?: return base
            val fresh = PosterCache.Entry(
                poster = hit.poster,
                backdrop = hit.backdrop,
                overview = hit.overview,
                savedAt = System.currentTimeMillis(),
                score = hit.score
            )
            cache.put(key, fresh)
            fresh
        }

        return base.copy(
            pic = entry.poster.ifBlank { base.pic },
            backdrop = entry.backdrop.ifBlank { base.backdrop },
            // B站 的简介 / 评分只在源站没提供时才补
            content = base.content.ifBlank { entry.overview },
            score = base.score.ifBlank { entry.score }
        )
    }

    fun cacheKey(vod: Vod): String =
        TitleMatcher.normalize(vod.name) + "|" + vod.displayYear

    fun flush() = cache.flush()
}
