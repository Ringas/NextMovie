package com.nextmovie.core.pan

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * 「手机扫码填单」通道 —— 给插件没有官方二维码、宿主也没法自己出码的网盘用。
 *
 * 为什么必须存在：插件只对 quark / baidu / pan115 / bili / guangya 提供 loginStart 出码；
 * 天翼的登录流程在插件里直接抛异常（priorResponse.body != null）；123 / 迅雷 / 1Me / 蜗牛 / 盘链
 * 只有账号或 cookie 写入 action，没有任何扫码接口。而 TV 上没法敲字母密码。
 *
 * 做法：宿主在局域网起一个一次性页面，把「本页地址」画成二维码贴在播放页卡片上。
 * 用户手机扫码 → 手机上填表 → 页面直接调 PanBridge 写进插件 → 电视卡片收到回执 → 自动续播。
 * 全程不打开插件配置中心，也不用回到设置页。
 *
 * 低干扰约束（对齐用户偏好）：单端口、单连接、串行 accept，超时即退；不做并发扫描、不轮询网络。
 */
object PanPhoneLogin {

    private const val TAG = "NextMoviePanPhone"

    /** 卡片上一次登录的结果，MainActivity 只读这里。 */
    @Volatile var lastOk: Boolean = false
        private set
    @Volatile var lastMsg: String = ""
        private set
    @Volatile var panId: String = ""
        private set

    fun reset(id: String) {
        panId = id
        lastOk = false
        lastMsg = ""
    }

    /** 每个网盘在手机上要填什么。 */
    data class Spec(
        val mode: Mode,
        val title: String,
        val hint: String,
        val fields: List<Field> = emptyList(),
        /** 需要先发短信的网盘（迅雷） */
        val sendSms: Boolean = false,
    )

    enum class Mode { COOKIE, TOKEN, ACCOUNT, SMS }

    data class Field(val key: String, val caption: String, val placeholder: String, val secret: Boolean = false)

    fun specOf(id: String): Spec? = when (id) {
        "pan189" -> Spec(
            Mode.COOKIE, "天翼网盘登录",
            "插件里天翼的扫码/账号流程是坏的（实测都报 priorResponse.body != null），只能贴一次网页 Cookie。",
            listOf(Field("value", "天翼 Cookie", "浏览器登录 cloud.189.cn 后复制整段 Cookie", true))
        )
        "ucCookie" -> Spec(
            Mode.COOKIE, "UC 网盘登录",
            "UC 网盘插件侧只有 Cookie 通道，贴一次即可。",
            listOf(Field("value", "UC Cookie", "__pus=…; __puus=…", true))
        )
        "ucToken" -> Spec(
            Mode.TOKEN, "UC TV Token",
            "电视端专用的 UC Token，贴在手机上次一次就行。",
            listOf(Field("value", "TV Token", "粘贴 UC TV Token", true))
        )
        "pan1Me" -> Spec(
            Mode.COOKIE, "123 分享社区登录",
            "插件只有 Cookie 通道。",
            listOf(Field("value", "Cookie", "整段 Cookie", true))
        )
        "pan123" -> Spec(
            Mode.ACCOUNT, "123 网盘登录",
            "插件按手机号 + 密码登录，填完电视直接续播。",
            listOf(
                Field("account", "手机号", "11 位手机号"),
                Field("password", "密码", "123 网盘密码", true),
            )
        )
        "panLian" -> Spec(
            Mode.ACCOUNT, "盘链影视登录",
            "账号密码登录。",
            listOf(
                Field("account", "账号", "盘链账号"),
                Field("password", "密码", "盘链密码", true),
            )
        )
        "woniu4k" -> Spec(
            Mode.ACCOUNT, "蜗牛影视登录",
            "账号密码登录，图形验证码可留空先用一次。",
            listOf(
                Field("account", "账号", "蜗牛账号"),
                Field("password", "密码", "蜗牛密码", true),
                Field("verify", "图形验证码", "如提示需要再填", false),
            )
        )
        "thunder" -> Spec(
            Mode.SMS, "迅雷网盘登录",
            "先在手机上点「发送验证码」，收到后填进来。",
            listOf(
                Field("mobile", "手机号", "11 位手机号"),
                Field("code", "短信验证码", "6 位验证码"),
            ),
            sendSms = true
        )
        else -> null
    }

    /** 局域网地址：优先 wlan0/eth0 的 site-local IPv4。 */
    fun lanIp(): String {
        return runCatching {
            val e = NetworkInterface.getNetworkInterfaces()
            val list = generateSequence { if (e.hasMoreElements()) e.nextElement() else null }.toList()
            val prefer = list.filter { it.name.startsWith("wlan", true) || it.name.startsWith("eth", true) }
            (prefer + list).asSequence()
                .flatMap { n -> val ie = n.inetAddresses; generateSequence { if (ie.hasMoreElements()) ie.nextElement() else null } }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
                .orEmpty()
        }.getOrDefault("")
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(spec: Spec, token: String, label: String): String {
        val fields = spec.fields.joinToString("") { f ->
            """
            <label>${esc(f.caption)}</label>
            <input name="${f.key}" type="${if (f.secret) "password" else "text"}" placeholder="${esc(f.placeholder)}" autocomplete="off">
            """.trimIndent()
        }
        val smsBtn = if (spec.sendSms) {
            """<button type="button" class="ghost" onclick="sendSms()">发送验证码</button>"""
        } else ""
        return """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${esc(label)}登录</title><style>
:root{color-scheme:dark}
body{margin:0;min-height:100vh;background:radial-gradient(circle at 30% 0,#17325a,#0a0f18 55%,#070b12);color:#eef6ff;
font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif;padding:28px 20px 44px}
.card{max-width:520px;margin:0 auto;background:rgba(20,31,48,.82);border:1px solid rgba(104,189,255,.22);
border-radius:22px;padding:22px 20px;box-shadow:0 20px 60px rgba(0,0,0,.45)}
h1{font-size:20px;margin:0 0 8px}
p{color:#93a6bf;font-size:13px;line-height:1.7;margin:0 0 18px}
label{display:block;color:#aebdd0;font-size:12px;margin:14px 0 6px}
input{width:100%;box-sizing:border-box;padding:13px;border-radius:12px;border:1px solid rgba(104,189,255,.28);
background:#101a2a;color:#eef6ff;font-size:16px}
.act{display:flex;gap:10px;margin-top:22px}
button{flex:1;border:0;border-radius:14px;padding:15px;font-size:16px;font-weight:700;background:#eda93a;color:#102033}
button.ghost{background:#162338;color:#dceaff;border:1px solid rgba(104,189,255,.25)}
.tip{margin-top:16px;font-size:12px;color:#6f8299;text-align:center}
</style></head><body><div class="card">
<h1>${esc(label)} · 在手机上完成登录</h1>
<p>${esc(spec.hint)}</p>
<form method="post" action="/submit">
<input type="hidden" name="t" value="${esc(token)}">
${fields}
<div class="act">$smsBtn<button type="submit">完成，回到电视播放</button></div>
</form>
<div class="tip">提交后电视上的卡片会自动继续播放</div>
</div></body></html>
""".trimIndent()
    }

    private fun donePage(msg: String): String = """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>已完成</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0a0f18;color:#eef6ff;font-family:-apple-system,system-ui,sans-serif;padding:24px}
.b{text-align:center}.i{width:64px;height:64px;border-radius:32px;background:rgba(53,211,153,.16);color:#35d399;
display:flex;align-items:center;justify-content:center;font-size:30px;margin:0 auto 18px}
p{color:#93a6bf;font-size:14px;line-height:1.7}</style></head><body><div class="b">
<div class="i">✓</div><h2>已完成</h2><p>${esc(msg)}</p></div></body></html>
""".trimIndent()

    private fun failPage(msg: String): String = """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>没成功</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0a0f18;color:#eef6ff;font-family:-apple-system,system-ui,sans-serif;padding:24px}
.b{text-align:center}.i{width:64px;height:64px;border-radius:32px;background:rgba(251,113,133,.16);color:#fb7185;
display:flex;align-items:center;justify-content:center;font-size:30px;margin:0 auto 18px}
p{color:#93a6bf;font-size:14px;line-height:1.7}</style></head><body><div class="b">
<div class="i">!</div><h2>没有成功</h2><p>${esc(msg)}</p><p>回到电视重新出码再试</p></div></body></html>
""".trimIndent()

    /** 表单提交：把值写进插件，写成功即算登录完成。 */
    private fun submit(panId: String, spec: Spec, form: Map<String, String>): Pair<Boolean, String> {
        return when (spec.mode) {
            Mode.COOKIE, Mode.TOKEN -> {
                val v = form["value"].orEmpty().trim()
                if (v.isBlank()) return false to "内容为空"
                val ok = PanBridge.setCookie(panId, v)
                ok to if (ok) "已写入插件" else "插件拒绝了这份凭据"
            }
            Mode.ACCOUNT -> {
                val account = form["account"].orEmpty().trim()
                val password = form["password"].orEmpty()
                if (account.isBlank() || password.isBlank()) return false to "账号或密码为空"
                val r = when (panId) {
                    "pan123" -> PanBridge.action("setPan123Account", mapOf("account" to account, "password" to password))
                    "panLian" -> PanBridge.action("setPanLianAccount", mapOf("account" to account, "password" to password))
                    "woniu4k" -> PanBridge.action(
                        "woniu4kLogin",
                        mapOf(
                            "account" to account,
                            "password" to password,
                            "verify" to form["verify"].orEmpty().trim()
                        )
                    )
                    else -> null
                }
                val ok = PanBridge.isOk(r)
                if (ok) runCatching { PanBridge.refresh() }
                ok to (PanBridge.msgOf(r).ifBlank { if (ok) "登录成功" else "插件未返回原因" })
            }
            Mode.SMS -> {
                val code = form["code"].orEmpty().trim()
                if (code.isBlank()) return false to "验证码为空"
                val r = PanBridge.smsLogin(panId, form["mobile"].orEmpty().trim(), code)
                val ok = PanBridge.isOk(r)
                if (ok) runCatching { PanBridge.refresh() }
                ok to (PanBridge.msgOf(r).ifBlank { if (ok) "登录成功" else "验证码不对" })
            }
        }
    }

    /**
     * 起服务。返回 (url, server)；url 为空表示局域网地址没拿到。
     * 调用方负责在卡片关闭时 server.close()。
     */
    fun serve(context: android.content.Context, id: String, label: String): Pair<String, ServerSocket?> {
        reset(id)
        val spec = specOf(id) ?: return "" to null
        val ip = lanIp()
        if (ip.isBlank()) {
            Log.w(TAG, "没有局域网 IPv4，没法出填单码")
            return "" to null
        }
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return "" to null
        val port = server.localPort
        server.soTimeout = 800
        val url = "http://$ip:$port/?t=$token"

        thread(isDaemon = true, name = "pan-phone-http") {
            val deadline = System.currentTimeMillis() + 240_000
            try {
                while (!server.isClosed && System.currentTimeMillis() < deadline && !lastOk) {
                    val sock = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Throwable) { break }
                    Log.i(TAG, "conn from ${sock.inetAddress?.hostAddress}")
                    runCatching { handle(sock, spec, id, label, token) }
                        .onFailure { Log.w(TAG, "handle fail: ${it.javaClass.simpleName} ${it.message}") }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "serve stop: ${e.message}")
            } finally {
                runCatching { server.close() }
            }
        }
        Log.i(TAG, "phone-login url=$url pan=$id")
        return url to server
    }

    private fun handle(sock: Socket, spec: Spec, id: String, label: String, token: String) {
        sock.use { s ->
            s.soTimeout = 4000
            val input = BufferedInputStream(s.getInputStream())
            val head = readHeaders(input)
            Log.i(TAG, "req line=" + head.firstOrNull().orEmpty().take(80))
            if (head.isEmpty()) return
            val first = head.first()
            val method = first.substringBefore(' ')
            val path = first.substringAfter(' ').substringBefore(' ')
            val headers = head.drop(1).mapNotNull {
                val i = it.indexOf(':')
                if (i < 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (len in 1..8192) {
                val buf = ByteArray(len); var read = 0
                while (read < len) {
                    val n = input.read(buf, read, len - read)
                    if (n <= 0) break
                    read += n
                }
                buf
            } else ByteArray(0)
            val query = path.substringAfter('?', "")
            val form = LinkedHashMap<String, String>()
            parseForm(query).forEach { (k, v) -> form[k] = v }
            parseForm(bodyBytes.toString(Charsets.UTF_8)).forEach { (k, v) -> form[k] = v }

            val out = s.getOutputStream()
            val t = form["t"].orEmpty()
            if (t != token) {
                write(out, 403, "text/html;charset=utf-8", failPage("这个链接已经失效，请回电视重新出码"))
                return
            }

            // 短信第一步
            if (path.startsWith("/sms")) {
                val phone = form["mobile"].orEmpty().trim()
                val r = PanBridge.smsSend(id, phone)
                val ok = r?.optInt("code", -1) == 0
                write(
                    out, 200, "text/html;charset=utf-8",
                    if (ok) page(spec.copy(hint = "验证码已发到 $phone，填进下方继续"), token, label)
                    else page(spec.copy(hint = PanBridge.msgOf(r).ifBlank { "验证码没发出去，检查手机号" }), token, label)
                )
                return
            }

            if (method != "POST") {
                write(out, 200, "text/html;charset=utf-8", page(spec, token, label))
                return
            }

            val (ok, msg) = submit(id, spec, form)
            lastMsg = msg
            if (ok) {
                lastOk = true
                write(out, 200, "text/html;charset=utf-8", donePage("$label 已登录，电视正在续播"))
                runCatching { sock.close() }
            } else {
                write(out, 200, "text/html;charset=utf-8", failPage(msg))
            }
        }
    }

    /** 读到空行为止。之前按「上一个字符是换行」判断会漏掉 \r\n\r\n，导致一直挂在读上。 */
    private fun readHeaders(input: BufferedInputStream): List<String> {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toInt().toChar())
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) break
        }
        return sb.toString().replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseForm(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        s.split('&').forEach { kv ->
            if (kv.isBlank()) return@forEach
            val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            if (k.isNotBlank()) m[k] = v
        }
        return m
    }

    private fun write(out: OutputStream, code: Int, type: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    /** 供调试：列出本机所有 IPv4，排查「扫码打不开」时用。 */
    fun ips(): List<String> = runCatching {
        val e = NetworkInterface.getNetworkInterfaces()
        generateSequence { if (e.hasMoreElements()) e.nextElement() else null }
            .flatMap { n -> val ie = n.inetAddresses; generateSequence { if (ie.hasMoreElements()) ie.nextElement() else null } }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())
}
