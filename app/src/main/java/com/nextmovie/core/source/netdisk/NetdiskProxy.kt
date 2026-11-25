package com.nextmovie.core.source.netdisk

/**
 * 网盘代理：策略与宿主侧工具。
 *
 * 产品策略（与 Marco 应用内 OAuth 不同）：
 * - **不内置**夸克 / UC / 阿里 / 百度账号
 * - 授权由 JAR 插件（玩偶等）在 `:spider` 完成
 * - 宿主提供：前台扫码 Activity、[SpiderProxyServer] loopback、Range 透传
 */
enum class NetdiskProvider(val label: String, val pluginDriven: Boolean) {
    QUARK("夸克", true),
    UC("UC", true),
    ALIYUN("阿里云盘", true),
    BAIDU("百度网盘", true),
    PAN115("115", true),
    OTHER("其他", true)
}

data class NetdiskCapability(
    val provider: NetdiskProvider,
    val verified: Boolean,
    val notes: String
)

object NetdiskProxy {

    val matrix: List<NetdiskCapability> = listOf(
        NetdiskCapability(NetdiskProvider.QUARK, true, "玩偶等 Guard + loopback 可播"),
        NetdiskCapability(NetdiskProvider.UC, false, "与夸克同源，依赖插件"),
        NetdiskCapability(NetdiskProvider.ALIYUN, false, "需插件 token / 扫码"),
        NetdiskCapability(NetdiskProvider.BAIDU, false, "常限速"),
        NetdiskCapability(NetdiskProvider.PAN115, false, "多为插件账号池"),
        NetdiskCapability(NetdiskProvider.OTHER, false, "通用 proxy(Map)")
    )

    fun summary(): String {
        val ok = matrix.count { it.verified }
        return "网盘代理：插件授权 · 本机回环 · 已验证 $ok/${matrix.size} 类"
    }

    /** 从播放 URL / Referer 推断网盘类型，便于播放器选默认 UA 与日志。 */
    fun detect(url: String, headers: Map<String, String> = emptyMap()): NetdiskProvider {
        val blob = (url + " " + headers.values.joinToString(" ")).lowercase()
        return when {
            "quark.cn" in blob || "drive.quark" in blob || "夸克" in blob -> NetdiskProvider.QUARK
            "uc.cn" in blob || "drive.uc.cn" in blob -> NetdiskProvider.UC
            "aliyundrive" in blob || "alipan.com" in blob || "阿里" in blob -> NetdiskProvider.ALIYUN
            "baidu.com" in blob || "pan.baidu" in blob -> NetdiskProvider.BAIDU
            "115.com" in blob || "anxia.com" in blob -> NetdiskProvider.PAN115
            else -> NetdiskProvider.OTHER
        }
    }

    /** 是否像走本机插件代理的地址（loopback 或插件声明的 proxy 查询串）。 */
    fun isPluginProxyUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]")) {
            return u.contains("/proxy")
        }
        return u.contains("do=proxy") || u.contains("proxy=true")
    }

    /**
     * 为播放器补全常见网盘防盗链头（插件未给 header 时的兜底，不覆盖已有键）。
     */
    fun mergePlayHeaders(
        url: String,
        existing: Map<String, String>
    ): Map<String, String> {
        if (existing.isNotEmpty() && existing.keys.any { it.equals("User-Agent", true) }) {
            return existing
        }
        val out = LinkedHashMap(existing)
        when (detect(url, existing)) {
            NetdiskProvider.QUARK, NetdiskProvider.UC -> {
                out.putIfAbsent(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
                )
                out.putIfAbsent("Referer", "https://pan.quark.cn/")
            }
            NetdiskProvider.ALIYUN -> {
                out.putIfAbsent(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
                )
                out.putIfAbsent("Referer", "https://www.aliyundrive.com/")
            }
            NetdiskProvider.BAIDU -> {
                out.putIfAbsent(
                    "User-Agent",
                    "netdisk;11.0;android"
                )
            }
            else -> Unit
        }
        return out
    }
}
