package com.nextmovie.core.pan

/**
 * 网盘 provider 唯一事实源：全部字段都直接对应插件 `/proxy?do=pan` 的 action/参数，
 * 宿主不再为任何单一网盘写第二套逻辑。
 *
 * 覆盖清单对齐插件配置中心（Wex 面板）：
 * 夸克 / UC(Cookie+TV Token) / 百度 / 115 / 天翼 / 移动 / 123 / 光鸭 / 哔哩 / 迅雷 /
 * 123分享社区 / 蜗牛影视 / AList / WebDAV / VodDIY / Emby。
 *
 * 四类登录方式（kind）：
 *  - QR      插件 loginStart + loginPoll 出官方码（quark/ucCookie/ucToken/baidu/pan115/guangya/bili）
 *  - COOKIE  只能粘贴凭据（pan189/pan1Me 等）
 *  - ACCOUNT 账号密码 / 短信验证码，走插件配置中心页（pan123/pan139/thunder/woniu4k）
 *  - URL     远程配置地址（alist/webdav/diyvod/emby）
 */
object PanProviders {

    enum class Kind { QR, COOKIE, ACCOUNT, SMS, URL }

    /** 播放页卡片里怎么完成登录：扫码 / 卡内短信 / 卡内账号密码 / 只能跳配置中心 / 填 URL。 */
    enum class LoginMode { QR, SMS, PASSWORD, CONFIG, URL }

    /**
     * 卡内二维码从哪来（决定宿主走哪条链路，别的地方不要再判断 provider 名字）：
     *  - PLUGIN 插件 loginStart 直接给官方码（quark/baidu/pan115/bili/guangya）
     *  - HOST   宿主离屏 WebView 打开官方页面抓码（移动 139）
     *  - PHONE  宿主局域网起一次性填单页，手机扫码后填写，写完回执（天翼/123/UC/迅雷/1Me/蜗牛/盘链）
     *  - NATIVE 宿主原生调用官方 CAS 接口出码（UC：token → su.uc.cn 码 → 换 __pus）
     *  - URL    非登录型（AList/WebDAV/VodDIY），填地址
     */
    enum class Route { PLUGIN, HOST, NATIVE, PHONE, URL }

    data class Provider(
        /** 插件 provider id：同时用于 loginStart/loginPoll 的 provider 参数 */
        val id: String,
        val label: String,
        val kind: Kind,
        /** status 里代表「已配置」的布尔字段 */
        val statusFlag: String,
        /** credentials 里的字段名（可为空） */
        val credentialsKey: String = "",
        /** 写凭据的 action（POST 到 ?do=pan&action=xxx&value=） */
        val writeAction: String? = null,
        val clearAction: String? = null,
        /** loginStart 的 provider 值；为空表示该类登录不走扫码接口 */
        val loginProvider: String? = null,
        /** 插件配置中心锚点，账号类直接跳这里 */
        val configAnchor: String = "",
        /** 线路优选时的默认顺序（与插件 panOrder 默认一致：夸克 > UC > 百度 > 115 > 123 > 光鸭 > 迅雷） */
        val rank: Int = 100,
        /** 播放页登录卡片的形态（与 Kind 分开：Kind 表示插件侧的凭据类型，这里是宿主侧的交互形态） */
        val loginMode: LoginMode = LoginMode.QR,
        /** 卡内二维码的来源链路 */
        val route: Route = Route.PLUGIN,
    )

    /** 全部 provider。顺序即默认优先级参考。 */
    val all: List<Provider> = listOf(
        Provider("quark", "夸克", Kind.QR, "quarkCookie", "quarkCookie",
            "setQuarkCookie", "clearQuarkCookie", "quark", "quark", rank = 0),
        Provider("ali", "阿里", Kind.QR, "aliLogin", "", null, null, null, "ali", rank = 10,
            loginMode = LoginMode.QR, route = Route.NATIVE),
        Provider("ucCookie", "UC", Kind.QR, "ucCookie", "ucCookie",
            "setUcCookie", "clearUc", "ucCookie", "uc", rank = 1, route = Route.NATIVE),
        Provider("ucToken", "UC TV Token", Kind.QR, "ucTvToken", "ucToken",
            "setUcToken", "clearUc", "ucToken", "uc", rank = 2, route = Route.PHONE),
        Provider("baidu", "百度", Kind.QR, "baiduCookie", "baiduCookie",
            "setBaiduCookie", "clearBaiduCookie", "baidu", "baidu", rank = 3),
        Provider("pan115", "115", Kind.QR, "pan115Cookie", "pan115Cookie",
            "setPan115Cookie", "clearPan115Cookie", "pan115", "pan115", rank = 4),
        Provider("pan123", "123", Kind.ACCOUNT, "pan123Configured", "pan123Account",
            null, "clearPan123Account", null, "pan123", rank = 5, loginMode = LoginMode.PASSWORD,
            route = Route.HOST),
        Provider("pan189", "天翼", Kind.COOKIE, "pan189Cookie", "pan189Cookie",
            "setPan189Cookie", "clearPan189Cookie", null, "pan189", rank = 6, loginMode = LoginMode.QR,
            route = Route.PHONE),
        Provider("pan139", "移动", Kind.SMS, "pan139Cookie", "pan139Cookie",
            null, "clearPan139", null, "pan139", rank = 7, loginMode = LoginMode.QR,
            route = Route.HOST),
        Provider("guangya", "光鸭", Kind.QR, "guangYaToken", "guangYaToken",
            "setGuangYaToken", "clearGuangYaToken", "guangya", "guangya", rank = 8),
        Provider("thunder", "迅雷", Kind.SMS, "thunderConfigured", "thunder",
            null, "clearThunder", null, "thunder", rank = 9, loginMode = LoginMode.SMS,
            route = Route.PHONE),
        Provider("bili", "哔哩", Kind.QR, "biliCookie", "biliCookie",
            "setBiliCookie", "clearBiliCookie", "bili", "bili", rank = 20),
        Provider("pan1Me", "123分享社区", Kind.COOKIE, "pan1MeCookie", "pan1MeCookie",
            "setPan1MeCookie", "clearPan1MeCookie", null, "pan1Me", rank = 30, loginMode = LoginMode.QR,
            route = Route.PHONE),
        Provider("woniu4k", "蜗牛影视", Kind.ACCOUNT, "woniu4kConfigured", "woniu4k",
            null, "clearWoNiu4K", null, "settings", rank = 40, loginMode = LoginMode.PASSWORD,
            route = Route.PHONE),
        Provider("alist", "AList", Kind.URL, "alistUrl", "alistUrl",
            "setAListUrl", "clearAListUrl", null, "diy", rank = 60, loginMode = LoginMode.URL,
            route = Route.URL),
        Provider("webdav", "WebDAV", Kind.URL, "webDavUrl", "webDavUrl",
            "setWebDavUrl", "clearWebDavUrl", null, "diy", rank = 61, loginMode = LoginMode.URL,
            route = Route.URL),
        Provider("diyvod", "VodDIY", Kind.URL, "diyVodUrl", "diyVodUrl",
            "setDiyVodUrl", "clearDiyVodUrl", null, "diy", rank = 62, loginMode = LoginMode.URL,
            route = Route.URL),
    )

    private val byId = all.associateBy { it.id }

    fun of(id: String): Provider? = byId[id] ?: all.firstOrNull { it.id.equals(id, true) }

    fun labelOf(id: String): String = of(id)?.label ?: id

    /** 线路名 / 地址 / 报错文案 → provider id（宿主只认这一处）。 */
    fun idOf(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("quark") || t.contains("夸克") -> "quark"
            t.contains("uc.cn") || t.contains("drive.uc") || t.contains("uc网盘") -> "ucCookie"
            t.contains("百度") || t.contains("baidu") -> "baidu"
            t.contains("115") -> "pan115"
            t.contains("天翼") || t.contains("189") -> "pan189"
            t.contains("移动") || t.contains("139") -> "pan139"
            t.contains("123") -> "pan123"
            t.contains("光鸭") -> "guangya"
            t.contains("迅雷") || t.contains("thunder") -> "thunder"
            t.contains("哔哩") || t.contains("bili") -> "bili"
            t.contains("alist") -> "alist"
            t.contains("webdav") -> "webdav"
            t.contains("阿里") || t.contains("ali") -> "ali" // 阿里云盘：插件无独立面板，保留识别
            // 「UC」两个字单独出现时放最后，避免误伤 UC 之外的词
            t.contains("uc") -> "ucCookie"
            else -> null
        }
    }

    /** 账号类（扫码接口不支持）→ 跳插件配置中心对应锚点。 */
    fun needsConfigPage(id: String): Boolean {
        val p = of(id) ?: return true
        return p.kind == Kind.ACCOUNT || p.kind == Kind.SMS
    }
}
