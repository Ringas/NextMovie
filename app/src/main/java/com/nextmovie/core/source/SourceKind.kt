package com.nextmovie.core.source

import com.nextmovie.core.config.Site

/**
 * 源运行时种类。与配置 `type` / `api` 形态对应，由 [SourceRouter] 选择具体引擎。
 *
 * 目标能力（产品级）：
 * 1. CMS JSON/XML
 * 2. Java JAR（csp_*）
 * 3. QuickJS / JS
 * 4. Python（Chaquopy，评估中）
 * 5. CatPaw / Node 服务包
 * 6. 网盘代理（夸克/UC/阿里/百度等，本机回环）
 * 7. 纯数据适配器（不执行第三方字节码，协议级解析）
 */
enum class SourceKind(
    val label: String,
    /** 当前版本是否可作为主路径加载内容 */
    val runtimeReady: Boolean,
    val notes: String
) {
    CMS("CMS", true, "JSON / XML 苹果 CMS 接口"),
    JAR("JAR", true, "csp_* DexClassLoader 插件"),
    JS("JS", true, "QuickJS 脚本站点"),
    PYTHON("Python", false, "需 Chaquopy 运行时，默认关闭"),
    CATPAW("CatPaw", false, "Node 服务包，本机 worker"),
    NETDISK("网盘代理", true, "依赖插件或本机代理回环，非独立站点类型"),
    ADAPTER("纯适配器", true, "adapter:json/cms 纯数据，无第三方 DEX"),
    UNSUPPORTED("不支持", false, "无法识别的 api 形态");

    companion object {
        fun of(site: Site): SourceKind {
            val api = site.api
            return when {
                site.isJavaScript -> JS
                api.endsWith(".py", ignoreCase = true) -> PYTHON
                api.startsWith("node:", ignoreCase = true) ||
                    api.contains("catpaw", ignoreCase = true) -> CATPAW
                api.startsWith("adapter:", ignoreCase = true) ||
                    api.startsWith("csp_XBPQ") ||
                    api.startsWith("csp_XYQ") ||
                    api.startsWith("csp_XPath") -> ADAPTER
                api.startsWith("csp_") || api.endsWith(".jar") || site.type == 3 -> JAR
                site.isCms -> CMS
                else -> UNSUPPORTED
            }
        }
    }
}
