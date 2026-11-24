package com.nextmovie.core.config

/**
 * 站点能力体检：把“配置里写了多少个站点”翻译成“到底能用几个”。
 *
 * TVBox 生态的配置动辄几十个站点，但真正能播的比例往往只有两三成：
 * CMS 接口能直连，`csp_*` 依赖加壳插件，`.js` 依赖 JS 运行时。
 * 面板的价值就是把这三种一眼分开，避免用户以为是软件坏了。
 */
enum class SiteKind(val label: String) {
    READY("可直接用"),
    PLUGIN("插件源"),
    JS("JS 源"),
    UNSUPPORTED("不支持")
}

data class HealthReport(
    val total: Int,
    val ready: Int,
    val plugin: Int,
    val js: Int,
    val unsupported: Int,
    val jarUrl: String = "",
    val jarHash: String = ""
) {
    /** 当前版本真正能打开的比例（插件 / JS 均未计入）。 */
    val usablePercent: Int get() = if (total == 0) 0 else ready * 100 / total

    val summary: String
        get() = if (total == 0) {
            "尚未导入配置"
        } else {
            "共 $total 个站点：可直接用 $ready · 插件源 $plugin · JS 源 $js · 不支持 $unsupported"
        }
}

object SiteHealth {

    fun kindOf(site: Site): SiteKind {
        val api = site.api
        return when {
            site.isJavaScript -> SiteKind.JS
            api.endsWith(".py") || api.startsWith("node:") -> SiteKind.UNSUPPORTED
            api.startsWith("csp_") || api.endsWith(".jar") || site.type == 3 -> SiteKind.PLUGIN
            site.isCms -> SiteKind.READY
            else -> SiteKind.UNSUPPORTED
        }
    }

    fun audit(sites: List<Site>): HealthReport {
        var ready = 0
        var plugin = 0
        var js = 0
        var unsupported = 0
        sites.forEach {
            when (kindOf(it)) {
                SiteKind.READY -> ready++
                SiteKind.PLUGIN -> plugin++
                SiteKind.JS -> js++
                SiteKind.UNSUPPORTED -> unsupported++
            }
        }
        val withJar = sites.firstOrNull { it.jar.isNotBlank() }
        return HealthReport(
            total = sites.size,
            ready = ready,
            plugin = plugin,
            js = js,
            unsupported = unsupported,
            jarUrl = withJar?.jar.orEmpty(),
            jarHash = withJar?.jarHash.orEmpty()
        )
    }

    /** 面板里每个站点的短标签，带不上原因时不刷屏。 */
    fun shortTag(site: Site): String = when (kindOf(site)) {
        SiteKind.READY -> "CMS"
        SiteKind.PLUGIN -> "JAR"
        SiteKind.JS -> "JS"
        SiteKind.UNSUPPORTED -> "N/A"
    }
}
