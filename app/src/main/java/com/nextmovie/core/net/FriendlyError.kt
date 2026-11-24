package com.nextmovie.core.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把网络异常翻译成人能行动的句子。
 * 电视上没有浏览器、没法看英文堆栈，用户只能靠这一句话决定「重试 / 换站 / 换网络」。
 */
object FriendlyError {

    /** 返回「主文案 + 原始原因」两段，前者给用户看，后者留着排查。 */
    fun describe(t: Throwable): String {
        val root = rootCause(t)
        val headline = when (root) {
            is UnknownHostException ->
                "域名解析失败：这台设备的 DNS 解析不到源站（多为路由器 DNS 被污染或断网）。\n" +
                    "可以先重试，或在设置里换一个站点；应用已尝试过 DoH 抗污染解析。"
            is SocketTimeoutException ->
                "连接源站超时：域名可能被 DNS 污染指到了错误 IP，或源站当前不可达。\n" +
                    "可以先重试，或换一个站点。"
            is Http.HttpError -> when (root.code) {
                403 -> "源站拒绝访问（403）：多为区域限制或防盗链，换一条线路/站点。"
                404 -> "接口不存在（404）：这个站点可能已失效，换一个站点。"
                500, 502, 503, 504 -> "源站服务器出错（HTTP ${root.code}），稍后重试或换站点。"
                else -> "源站返回异常（HTTP ${root.code}），换个站点试试。"
            }
            is IOException -> {
                val msg = root.message.orEmpty()
                when {
                    msg.contains("CLEARTEXT", ignoreCase = true) ->
                        "系统禁止明文 HTTP 请求，请改用 https 配置地址。"
                    msg.contains("SSL", ignoreCase = true) || msg.contains("certificate", ignoreCase = true) ->
                        "HTTPS 证书校验失败：源站证书异常，或被中间设备劫持。"
                    msg.contains("failed to connect", ignoreCase = true) ->
                        "连接失败：网络不通、端口被拦，或域名解析到了不可达的 IP。"
                    else -> "网络请求失败。"
                }
            }
            else -> {
                val msg = root.message.orEmpty()
                when {
                    msg.contains("原生库") || msg.contains("ABI") ->
                        "$msg\n这是插件自带的 ARM 原生库，x86 模拟器/Intel 设备无法加载；请把 tv 或 tv64 包装到盒子上运行。"
                    msg.contains("异步脚本") -> msg
                    msg.isNotBlank() -> msg
                    else -> "加载失败：${root.javaClass.simpleName}"
                }
            }
        }
        val raw = root.message?.take(160).orEmpty()
        // 主文案已经原样带上了原始原因时不再重复贴一遍，避免错误页上下两段一模一样
        return if (raw.isBlank() || headline.contains(raw)) headline else "$headline\n\n原始原因：$raw"
    }

    fun rootCause(t: Throwable): Throwable {
        var cur = t
        var guard = 0
        while (cur.cause != null && guard < 6) {
            cur = cur.cause!!
            guard++
        }
        return cur
    }
}
