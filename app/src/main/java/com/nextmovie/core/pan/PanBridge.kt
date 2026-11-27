package com.nextmovie.core.pan

import android.util.Log
import com.github.catvod.Proxy
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主 ↔ 插件网盘能力的唯一通道。
 *
 * 结论（已真机验证）：FongMi 的 `/proxy?do=pan&...` 不是宿主实现的，
 * 而是插件 jar 里 `com.github.catvod.spider.Proxy` 的 **static** `proxy(Map)`。
 * 我们把它接进 SpiderProxyServer（9978）以后，插件自带的网盘接口就全部可用：
 *   - action=status / credentials
 *   - action=loginStart&provider=quark   → 官方登录二维码 qrUrl + taskId
 *   - action=loginPoll&provider=quark&taskId=...
 *   - action=setQuarkCookie&value=...
 *
 * 所以登录不再由宿主自建 CAS 页面，而是「向来源要码 → 宿主展示 → 宿主轮询 → 来源自己落库」，
 * 与 FongMi / Marco 完全同一套。
 */
object PanBridge {

    private const val TAG = "NextMoviePan"

    private val state = ConcurrentHashMap<String, Boolean>()
    private var lastRefresh = 0L

    @Volatile private var cachedPort = 0

    /**
     * 9978 的监听方是 `:spider` 进程里的 SpiderProxyServer，
     * 主进程拿 `Proxy.getPort()` 只会得到 -1（那是本进程 set 过的值）。
     * 所以这里自己扫 9978..9999，命中 `/proxy?do=pan&action=status` 的才算。
     */
    fun base(): String = "http://127.0.0.1:" + port()

    fun port(): Int {
        if (cachedPort > 0) return cachedPort
        val own = runCatching { Proxy.getPort() }.getOrDefault(-1)
        if (own > 0) { cachedPort = own; return own }
        for (p in 9978..9999) {
            if (probe(p)) { cachedPort = p; Log.i(TAG, "pan-port 探测到宿主代理口=$p"); return p }
        }
        Log.w(TAG, "pan-port 没找到宿主代理（9978..9999 全不通）")
        return 0
    }

    private fun probe(port: Int): Boolean {
        val url = runCatching { URL("http://127.0.0.1:$port/proxy?do=pan&action=status") }.getOrNull() ?: return false
        val conn = runCatching { url.openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            conn.connectTimeout = 600
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            // 只要 TCP 通、HTTP 有响应，就说明这是宿主代理口；
            // 502 = 插件还没加载完，交给上层重试，不要误判成「端口不存在」。
            val code = conn.responseCode
            if (code in 200..599) {
                val text = runCatching { conn.errorStream ?: conn.inputStream }.getOrNull()
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                (code == 200 && text.contains("\"msg\":\"ok"))
                    || text.contains("Plugin proxy unavailable")
                    || text.contains("Pan debug proxy")
            } else false
        } catch (e: Throwable) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun get(params: String, timeoutMs: Int = 4000): JSONObject? {
        val p = port()
        if (p <= 0) return null
        val url = runCatching { URL("http://127.0.0.1:$p/proxy?$params") }.getOrNull() ?: return null
        val conn = runCatching { url.openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "pan-bridge http=${conn.responseCode} q=$params")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(text) }.getOrElse {
                Log.w(TAG, "pan-bridge 非 JSON len=${text.length}")
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "pan-bridge 失败: ${e.javaClass.simpleName} ${e.message} q=$params")
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 拉一次全量状态，写进本地缓存，供 UI 同步读。 */
    fun refresh(): JSONObject? {
        val s = get("do=pan&action=status", 3000) ?: return null
        // 全部网盘共用一份 status 字段表，新增网盘只改 PanProviders 一处
        PanProviders.all.forEach { p ->
            state[p.id] = s.optBoolean(p.statusFlag)
        }
        lastRefresh = System.currentTimeMillis()
        Log.i(TAG, "pan-status " + state.entries.joinToString(" ") { "${it.key}=${it.value}" })
        return s
    }

    /** 非阻塞读：没有缓存时按「未登录」处理，让播放先跑一次，失败再弹码。 */
    fun has(panId: String): Boolean {
        if (System.currentTimeMillis() - lastRefresh > 30_000) {
            Thread { runCatching { refresh() } }.start()
        }
        return state[panId] ?: false
    }

    fun known(panId: String): Boolean = state.containsKey(panId)

    /** 申请登录二维码。返回 qrUrl（官方登录短链）和 taskId。 */
    fun loginStart(panId: String): JSONObject? =
        get("do=pan&action=loginStart&provider=" + URLEncoder.encode(PanProviders.of(panId)?.loginProvider ?: panId, "UTF-8"), 12000)

    fun loginPoll(panId: String, taskId: String): JSONObject? =
        get(
            "do=pan&action=loginPoll&provider=" + URLEncoder.encode(PanProviders.of(panId)?.loginProvider ?: panId, "UTF-8") +
                "&taskId=" + URLEncoder.encode(taskId, "UTF-8"),
            8000
        )

    /** 通用写凭据：任何网盘都走 provider 表里的 writeAction，不再逐个分支。 */
    fun setCookie(panId: String, cookie: String): Boolean {
        val id = PanProviders.of(panId)?.id ?: return false
        val action = PanProviders.of(id)?.writeAction ?: return false
        val r = get("do=pan&action=$action&value=" + URLEncoder.encode(cookie, "UTF-8"), 8000)
        val ok = r?.optInt("code", -1) == 0
        if (ok) refresh()
        Log.i(TAG, "pan-setCookie pan=$id ok=$ok msg=" + r?.optString("msg"))
        return ok
    }

    fun clear(panId: String): Boolean {
        val id = PanProviders.of(panId)?.id ?: return false
        val action = PanProviders.of(id)?.clearAction ?: return false
        val ok = get("do=pan&action=$action", 8000) != null
        if (ok) refresh()
        return ok
    }

    /** 网盘顺序：插件自己也有 panOrder，宿主排序沿用同一份默认优先级。 */
    fun rankOf(panId: String?): Int = panId?.let { PanProviders.of(it)?.rank } ?: 500

    /** 账号/短信类网盘不走扫码接口，直接打开插件配置中心对应页。 */
    fun configUrl(panId: String): String {
        val anchor = PanProviders.of(panId)?.configAnchor.orEmpty()
        return base() + "/proxy?do=pan&action=config" + if (anchor.isNotBlank()) "#$anchor" else ""
    }


    /** 插件统一用 code=0 表示成功；部分动作回 success=true。 */
    fun isOk(r: JSONObject?): Boolean = r != null && (r.optInt("code", -1) == 0 || r.optBoolean("success", false))

    fun msgOf(r: JSONObject?): String =
        r?.optString("msg").orEmpty().ifBlank { r?.optString("message").orEmpty() }

    /** 通用动作：插件里非扫码的登录动作（短信/账号）都从这一条口子走，避免宿主散落魔法串。 */
    fun action(name: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): JSONObject? {
        val q = StringBuilder("do=pan&action=").append(URLEncoder.encode(name, "UTF-8"))
        params.forEach { (k, v) -> q.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8")) }
        return get(q.toString(), timeoutMs)
    }

    /** 发送短信验证码。移动=sendPan139Sms&phone=，迅雷=sendThunderSms&mobile=。 */
    fun smsSend(panId: String, phone: String): JSONObject? = when (panId) {
        "pan139" -> action("sendPan139Sms", mapOf("phone" to phone))
        "thunder" -> action("sendThunderSms", mapOf("mobile" to phone))
        else -> null
    }

    /** 短信验证码登录。移动需要 phone+code，迅雷只用 code（手机号在上一步记住了）。 */
    fun smsLogin(panId: String, phone: String, code: String): JSONObject? = when (panId) {
        "pan139" -> action("loginPan139Sms", mapOf("phone" to phone, "code" to code))
        "thunder" -> action("loginThunderSms", mapOf("code" to code))
        else -> null
    }

    /** 账号密码登录（123 / 蜗牛等）。 */
    fun accountLogin(panId: String, account: String, password: String): JSONObject? = when (panId) {
        "pan123" -> action("setPan123Account", mapOf("account" to account, "password" to password))
        else -> null
    }

    fun logoutAll() {
        state.clear()
        lastRefresh = 0
    }
}
