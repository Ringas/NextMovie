package com.nextmovie.core.config

/**
 * 默认站点选择规则。
 *
 * 聚合型配置动辄 60+ 站点，排在最前面的常常是 `csp_xxxGuard`（空壳/守卫站点）或 `.js` 插件，
 * 直接取第一个会让首页立刻报错。这里的顺序是：
 * 1. 用户手动选过 → 尊重选择（但站点仍须可用）；
 * 2. 第一个可直接访问的 CMS 站点（最稳，不需要插件）；
 * 3. 第一个带 jar 的插件站点；
 * 4. 任何可用站点；
 * 5. 兜底第一个（由 UI 展示「为什么不可用」）。
 */
object SiteSelector {

    fun pick(sites: List<Site>, savedKey: String, manual: Boolean): Site? {
        if (sites.isEmpty()) return null
        if (manual) {
            sites.firstOrNull { it.key == savedKey && it.isPlayable }?.let { return it }
        }
        return sites.firstOrNull { it.isCms && it.isPlayable }
            ?: sites.firstOrNull { it.isSpider && it.jar.isNotBlank() && it.isPlayable }
            ?: sites.firstOrNull { it.isPlayable }
            ?: sites.first()
    }
}
