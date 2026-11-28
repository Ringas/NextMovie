package com.nextmovie.core.spider

import android.util.Log
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * drpy / xBPQ 风格的 DOM 选择器桥。
 *
 * 这些 JS 插件不满足于「正则抠字符串」，它们习惯写：
 *   pdfh(html, 'body&&div.list&&li:eq(1)&&a&&Text')
 *   pdfa(html, 'ul&&li')
 *   pd(html, '.pic&&img&&src')
 * 规则串用 `&&` 逐级下钻，末段是 `Text` / `Html` 或属性名（href/src/alt/...）。
 *
 * 这里用 Jsoup 的 CSS 选择器承接（它原生支持 `:eq() :lt() :gt() :contains() :has()`），
 * 并补齐三点 drpy 语义：
 * 1. `Text` 取文本、`Html` 取外层 HTML，其余非选择器末段按属性名取；
 * 2. href / src 等相对地址按脚本地址补成绝对地址；
 * 3. `a||b` 作为「前者空则退后者」的兜底链。
 *
 * 做不到的：需要真实 JS 运行时改 DOM 的站点、以及 `jsp`（JSoup 对象 API）调用。
 */
internal object JsSelector {

    private val TEXT_KEYS = setOf("Text", "text", "TEXT")
    private val HTML_KEYS = setOf("Html", "html", "HTML")
    private val URL_KEYS = setOf("href", "src", "data-src", "data-original", "poster", "Url", "url")

    /** 第一段命中的结果，未命中返回空串。 */
    fun pdfh(html: String, rule: String, base: String?): String {
        for (branch in branches(rule)) {
            val steps = steps(branch)
            if (steps.isEmpty()) continue
            val nodes = walk(html, steps, base, keepTail = true) ?: continue
            if (nodes.isEmpty()) continue
            val tail = steps.last()
            val value = when {
                tail in TEXT_KEYS -> nodes.first().text()
                tail in HTML_KEYS -> nodes.first().outerHtml()
                isAttributeTail(tail, nodes.first()) -> attr(nodes.first(), tail)
                else -> nodes.first().outerHtml()
            }
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /** 所有命中节点的外层 HTML，按 JSON 数组返回（JS 侧再包成 Array）。 */
    fun pdfa(html: String, rule: String, base: String?): String {
        for (branch in branches(rule)) {
            val steps = steps(branch)
            if (steps.isEmpty()) continue
            val selectors = if (steps.last() in TEXT_KEYS || steps.last() in HTML_KEYS ||
                isPlainAttribute(steps.last())
            ) steps.dropLast(1) else steps
            if (selectors.isEmpty()) continue
            val nodes = walk(html, selectors, base, keepTail = false) ?: continue
            if (nodes.isEmpty()) continue
            val arr = JSONArray()
            nodes.forEach { arr.put(it.outerHtml()) }
            return arr.toString()
        }
        return "[]"
    }

    private fun branches(rule: String): List<String> =
        rule.split("||").map { it.trim() }.filter { it.isNotEmpty() }

    private fun steps(branch: String): List<String> =
        branch.split("&&").map { it.trim() }.filter { it.isNotEmpty() }

    private fun isPlainAttribute(token: String): Boolean =
        token.startsWith("@") ||
            Regex("^[A-Za-z_:][-A-Za-z0-9_:.]*$").matches(token) &&
            token !in TEXT_KEYS && token !in HTML_KEYS &&
            token !in setOf("body", "html", "div", "a", "li", "ul", "span", "img", "p", "table", "tr", "td", "class", "id")

    private fun isAttributeTail(token: String, node: Element): Boolean {
        val key = token.removePrefix("@")
        return node.hasAttr(key) || isPlainAttribute(token)
    }

    private fun attr(node: Element, token: String): String {
        val key = token.removePrefix("@")
        return when (key) {
            in URL_KEYS -> runCatching {
                node.absUrl(key).ifBlank { node.attr(key) }
            }.getOrDefault(node.attr(key))
            else -> node.attr(key)
        }
    }

    /** 逐级下钻；末段是「文本/HTML/属性名」这种取值指令时，不当作选择器。 */
    private fun walk(html: String, steps: List<String>, base: String?, keepTail: Boolean): List<Element>? {
        var current: List<Element> = runCatching { listOf(Jsoup.parse(html, base ?: "").body()) }
            .getOrElse { null } ?: return null
        val tailIsValue = steps.last() in TEXT_KEYS || steps.last() in HTML_KEYS || isPlainAttribute(steps.last())
        val selectors = if (keepTail && tailIsValue) steps.dropLast(1) else steps
        for (step in selectors) {
            if (step in TEXT_KEYS || step in HTML_KEYS) break
            val picked = runCatching {
                current.flatMap { node -> node.select(step).toList() }
            }.getOrElse {
                Log.w("NextMovieJs", "选择器解析失败 $step：${it.message}")
                return null
            }
            if (picked.isEmpty()) return emptyList()
            current = picked
        }
        return current
    }
}
