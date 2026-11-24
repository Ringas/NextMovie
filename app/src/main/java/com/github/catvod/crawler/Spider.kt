package com.github.catvod.crawler

import android.content.Context

/**
 * 插件宿主 ABI（独立实现）。
 *
 * 第三方 JAR 爬虫在编译时继承 `com.github.catvod.crawler.Spider`，
 * 运行时通过反射拿到本类的实例。因此包名、类名与方法签名必须与生态一致，
 * 但内部实现由本项目独立完成，不包含任何第三方源码。
 *
 * 已知边界：只保证主流程方法（init / home / category / detail / search / player / proxy）。
 * 依赖宿主私有能力（本地持久化、对话弹窗、WebView 动作等）的插件可能不兼容。
 */
open class Spider {

    @JvmField
    var siteKey: String = ""

    open fun init(context: Context?, extend: String?) {}

    open fun init(context: Context?) = init(context, null)

    open fun homeContent(filter: Boolean): String = ""

    open fun homeVideoContent(): String = ""

    open fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?
    ): String = ""

    open fun detailContent(ids: List<String>?): String = ""

    open fun searchContent(key: String?, quick: Boolean): String = ""

    open fun searchContent(key: String?, quick: Boolean, pg: String?): String = ""

    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    open fun liveContent(url: String?): String = ""

    open fun proxy(params: MutableMap<String, String>?): Array<Any>? = null

    open fun action(action: String?): String = ""

    open fun isVideoFormat(url: String?): Boolean = false

    open fun manualVideoCheck(): Boolean = false

    open fun destroy() {}
}
