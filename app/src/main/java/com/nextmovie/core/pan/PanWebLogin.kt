package com.nextmovie.core.pan

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 通用网盘扫码网关（宿主侧）。
 *
 * 插件 `loginStart` 只覆盖夸克/UC/百度/115/光鸭/哔哩；移动、123、迅雷在插件里
 * 只有账号/短信。这里用一条**离屏 WebView** 打开各家官方登录页：
 *
 *   1. 注入脚本切到「扫码登录」tab；
 *   2. 每 1.5s 抓一次页面里的 canvas 二维码（toDataURL），交给宿主 UI 自绘；
 *   3. 同时看 Cookie 与页面文案，判断「已扫码 / 已登录」；
 *   4. 登录成功后把 Cookie 交回上层（能不能落库由 PanProvider 的 writeAction 决定）。
 *
 * 全程只有一个 WebView、一次 1.5s 的轻量朗读，不并发、不影响播放。
 */
class PanWebLogin(
    private val activity: Activity,
    private val spec: Spec,
    private val label: String,
    private val onQr: (Bitmap?, String) -> Unit,
    private val onPhase: (Phase, String) -> Unit,
    private val onLogin: (String) -> Unit,
    private val onFail: (String) -> Unit,
    private val keepAfterLogin: Boolean = false,
) {


    enum class Phase { LOADING, WAIT, SCANNED, OK, FAIL }

    data class Spec(
        /** 官方登录页 */
        val entry: String,
        /** 读 Cookie 的地址（一般与 entry 同域） */
        val cookieUrl: String,
        /** 出现任意一个即视为已登录 */
        val cookieNames: List<String> = emptyList(),
        /** 页面文案里出现这些词就认为「已扫待确认」 */
        val scannedWords: List<String> = listOf("确认登录", "扫描成功", "已扫码"),
    )

    companion object {
        private const val TAG = "NextMoviePanWeb"

        /** 这些网盘插件没有扫码接口，才走网关 */
        private val SPECS = mapOf(
            "pan139" to Spec(
                // 直达登录面板（#/login），避开营销首页
                entry = "https://yun.139.com/w/#/",
                cookieUrl = "https://yun.139.com/",
                // 139 的匿名 token 也叫 token，不能靠 cookie 判登录，交给页面文案
                cookieNames = emptyList(),
            ),
            "pan123" to Spec(
                // 营销首页没有登录框，真正的网盘前端在 yun.123pan.cn，登录走 /login
                entry = "https://yun.123pan.cn/login",
                cookieUrl = "https://yun.123pan.cn/",
                cookieNames = listOf("loginName", "authorToken", "p_u"),
            ),
            "thunder" to Spec(
                entry = "https://pan.xunlei.com/",
                cookieUrl = "https://pan.xunlei.com/",
                cookieNames = listOf("userToken", "sessionid", "device_id"),
            ),
        )

        /** 调试用：保留最近一个存活实例，方便从 adb 跑 JS（登录后不销毁）。 */
        @Volatile var live: PanWebLogin? = null

        fun specOf(panId: String): Spec? = SPECS[panId]

        private val PROBE = """
            (function(){
              try{
                var out={};
                var txt=(document.body&&document.body.innerText||'').slice(0,4000);
                out.text=txt;
                out.logged=/退出登录|退出账号|我的文件|上传文件|回收站|个人中心/.test(txt)&&!/立即登录/.test(txt);
                var cand=document.querySelectorAll('a,button,div,span,li,svg');
                var entered=false;
                for(var i=0;i<cand.length&&i<600;i++){
                  var el=cand[i], t=(el.innerText||'').trim();
                  if(!entered&&(t==='登录'||t==='立即登录'||t==='登录/注册'||t==='马上登录')){ try{el.click();entered=true;}catch(e){} }
                }
                for(var i=0;i<cand.length&&i<600;i++){
                  var el2=cand[i], t2=(el2.innerText||'').trim();
                  if(t2.length<=6&&(t2.indexOf('扫码')>=0||t2.indexOf('二维码')>=0)){ try{el2.click();}catch(e){} }
                }
                var ns=[];
                for(var i=0;i<cand.length&&i<600&&ns.length<24;i++){
                  var e3=cand[i], t3=(e3.innerText||'').trim(), c3=(e3.className&&e3.className.toString()||'');
                  if(t3.length<=10&&(t3.length>0||/login|user|qr|scan/i.test(c3))){ ns.push(t3+'|'+c3.slice(0,36)); }
                }
                out.nodes=ns;
                try{ if(!window.__nmQrOpen){ var qb=document.querySelector('.login-box-position .qr-show'); if(qb&&qb.className.indexOf('qrcode-view')<0){ qb.click(); window.__nmQrOpen=1; } } }catch(e){}
                out.vw=window.innerWidth;
                var qw=document.querySelector('.qrcode-wrap')||document.querySelector('.qrcode');
                if(qw){ var r5=qw.getBoundingClientRect(); if(r5.width>80) out.qrect=[r5.left|0,r5.top|0,r5.width|0,r5.height|0]; }
                try{ var bx=document.querySelector('.login-box-position'); out.box=bx?bx.innerHTML.replace(/\s+/g,' ').slice(0,420):''; }catch(e){}

                out.canvases=document.querySelectorAll('canvas').length;
                var best=null;
                function pick(root){
                  if(!root) return;
                  var cs=root.querySelectorAll?root.querySelectorAll('canvas'):[];
                  for(var i=0;i<cs.length;i++){
                    var c=cs[i], r=c.getBoundingClientRect();
                    if(r.width>=110&&r.width<=460&&Math.abs(r.width-r.height)<=10){
                      if(!best||r.width>best.w) best={c:c,w:r.width};
                    }
                  }
                }
                pick(document);
                var ifs=document.querySelectorAll('iframe');
                for(var i=0;i<ifs.length;i++){ try{ pick(ifs[i].contentDocument); }catch(e){} }
                if(best){ try{ out.qr=best.c.toDataURL('image/png'); out.qw=best.w; }catch(e){ out.qerr=String(e); } }
                if(!out.qr){
                  var imgs=document.querySelectorAll('img');
                  for(var i=0;i<imgs.length;i++){
                    var r2=imgs[i].getBoundingClientRect();
                    if(r2.width>=110&&r2.width<=460&&Math.abs(r2.width-r2.height)<=10&&imgs[i].src&&imgs[i].src.indexOf('data:image')===0){
                      out.qr=imgs[i].src; out.qw=r2.width; break;
                    }
                  }
                }
                try{
                  var btxt=document.body?document.body.innerText:'';
                  if(/二维码已?失效|二维码已?过期|已过期|已失效|重新获取/.test(btxt)){
                    out.expired=1;
                    var rb=document.querySelector('[class*=refresh],[class*=reload],[class*=again],.qrcode-refresh');
                    if(rb){ try{ rb.click(); }catch(e){} }
                  }
                }catch(e){}
                try{
                  if(window.__nmRec){ out.rec=window.__nmRec.slice(-8).map(function(r){ return r.u+' :: '+String(r.b).slice(0,150); }); }
                }catch(e){}
                try{
                  var es=performance.getEntriesByType('resource'), rs=[];
                  for(var i=0;i<es.length;i++){
                    var n=es[i].name||'';
                    if(n.indexOf('orchestration')>=0||/\/hcy\//.test(n)||n.indexOf('file/list')>=0){
                      var pn=n.split('?')[0]; if(rs.indexOf(pn)<0) rs.push(pn);
                    }
                  }
                  out.res=rs;
                }catch(e){}
                try{
                  var ls=0; for(var k in localStorage){ ls++; if(String(k).toLowerCase().indexOf('token')>=0) out.lsToken=true; }
                  out.lsKeys=ls;
                }catch(e){}
                return JSON.stringify(out);
              }catch(e){ return JSON.stringify({err:String(e)}); }
            })()
        """.trimIndent()
    }

    private val RECORDER = """
        (function(){
          try{
            if(window.__nmRec) return 'on';
            var rec=window.__nmRec=[];
            function push(u,b){ try{ rec.push({u:String(u).split('?')[0], b:String(b).slice(0,1200)}); if(rec.length>80) rec.shift(); }catch(e){} }
            var of=window.fetch;
            if(of){ window.fetch=function(input,init){
              var u=(typeof input==='string')?input:(input&&input.url);
              var p=of.apply(this,arguments);
              try{ p.then(function(r){ try{ r.clone().text().then(function(t){ push(u,t); }).catch(function(){}); }catch(e){} }); }catch(e){}
              return p;
            }; }
            var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open=function(m,u){ this.__nmUrl=u; return oo.apply(this,arguments); };
            XMLHttpRequest.prototype.send=function(){ var x=this;
              x.addEventListener('load',function(){ push(x.__nmUrl, x.responseText||''); });
              return os.apply(this,arguments); };
            return 'on';
          }catch(e){ return 'err:'+e; }
        })()
    """.trimIndent()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private lateinit var web: WebView
    private var closed = false
    private var lastQr: String? = null
    private var lastRes: List<String> = emptyList()
    private var lastRec: List<String> = emptyList()
    private var baseCookie: String = ""
    private var polls = 0
    private var loggedIn = false
    /** 单链轮询：onPageFinished 和兜底定时器可能各起一条，重复会把 1.5s 压成 0.7s。 */
    private var polling = false

    fun start() = handler.post { boot() }

    /** 调试通道：在页面上下文里执行 JS，把结果打到 logcat（最多 1800 字符）。 */
    fun evalJs(js: String, tag: String) = handler.post {
        runCatching { web.evaluateJavascript(js) { r -> Log.i(TAG, "js-$tag=" + (r ?: "null").take(1800)) } }
    }

    private fun boot() {
        if (closed) return
        live = this
        runCatching {
            web = WebView(activity)
            host = FrameLayout(activity)
            host.alpha = 0.02f
            host.addView(
                web,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            activity.addContentView(
                host,
                ViewGroup.LayoutParams(1080, 1920)
            )
            host.translationX = -4000f

            val s = web.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            s.cacheMode = WebSettings.LOAD_DEFAULT
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
            s.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // 页面脚本执行前挂上网络录制器，才能读到 139 自己签名的接口响应
                    view?.evaluateJavascript(RECORDER, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "web-loaded pan=${spec.entry} url=$url")
                    onPhase(Phase.LOADING, "正在加载官方登录页…")
                    schedule()
                }
            }
            baseCookie = cookie().orEmpty()
            web.loadUrl(spec.entry)
            onPhase(Phase.LOADING, "正在向官方页面申请二维码…")
            // 页面可能因为重定向/JS 未就绪迟迟不 onPageFinished，兜底也排一次
            handler.postDelayed({ if (polls == 0) schedule() }, 3500)
        }.onFailure {
            fail("WebView 启动失败：${it.message}")
        }
    }

    private fun schedule() {
        if (closed || polling) return
        polling = true
        handler.postDelayed({ polling = false; poll() }, 1500)
    }

    private fun cookie(): String? = runCatching {
        CookieManager.getInstance().getCookie(spec.cookieUrl)
    }.getOrNull()

    private fun poll() {
        if (closed || loggedIn) return
        polls++
        if (polls > 160) { fail("二维码超时，请重新出码"); return }

        val c = cookie().orEmpty()
        val strong = spec.cookieNames.any { c.contains("$it=") }
        if (polls > 2 && strong && c != baseCookie) {
            Log.i(TAG, "cookie-hit names=${spec.cookieNames.joinToString(",")} len=${c.length}")
            // 登录态已就绪：先趁 WebView 还活着采一次页面真实 API 路径，采完再收尾
            val ck = c
            val fired = java.util.concurrent.atomic.AtomicBoolean(false)
            web.evaluateJavascript(PROBE) { raw ->
                if (fired.compareAndSet(false, true)) { logRes(raw, "cookiehit"); login(ck) }
            }
            handler.postDelayed({ if (fired.compareAndSet(false, true)) login(ck) }, 2500)
            return
        }

        web.evaluateJavascript(PROBE) { raw ->
            if (closed || loggedIn) return@evaluateJavascript
            val js = runCatching {
                if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as String else raw
            }.getOrNull()
            val json = js?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json == null) { schedule(); return@evaluateJavascript }

            val text = json.optString("text")
            val qr = json.optString("qr")
            Log.i(TAG, "probe#${polls} canvas=${json.optInt("canvases")} qr=${qr.length} logged=${json.optBoolean("logged")} text=" + text.take(60).replace("\n", " "))
            if (json.optInt("expired") == 1 && lastQr != null) {
                Log.i(TAG, "qr-expired, waiting for refreshed code")
                lastQr = null
                onPhase(Phase.LOADING, "二维码已过期，正在重新申请…")
            }
            if (qr.isNotBlank()) {
                if (qr != lastQr) {
                    lastQr = qr
                    val bmp = decode(qr)?.let { raw -> sharpen(raw) ?: raw }
                    if (bmp != null) {
                        Log.i(TAG, "qr-ready ${bmp.width}x${bmp.height}")
                        lastQr = qr
                        onQr(bmp, "打开「" + label + "」App 扫一扫")
                        onPhase(Phase.WAIT, "等待扫码")
                    }
                }
            }
            json.optJSONArray("rec")?.let { arr ->
                val list = (0 until arr.length()).map { arr.optString(it) }
                if (list.isNotEmpty() && list != lastRec) {
                    lastRec = list
                    Log.i(TAG, "api-rec " + list.joinToString("\n  "))
                }
            }
            val res = json.optJSONArray("res")
            if (res != null) {
                val list = (0 until res.length()).map { res.optString(it) }
                if (list.isNotEmpty() && list != lastRes) {
                    lastRes = list
                    Log.i(TAG, "api-res " + list.takeLast(8).joinToString(" | "))
                }
            }
            runCatching { dumpCookie(cookie().orEmpty()) }
            if (json.optBoolean("logged")) {
                login(cookie().orEmpty())
                return@evaluateJavascript
            }
            if (spec.scannedWords.any { text.contains(it) }) {
                onPhase(Phase.SCANNED, "已扫码，请在手机上确认")
            }
            schedule()
        }
    }

    /** WebView 离屏绘制后按二维码容器坐标裁剪（canvas/表格/SVG 都能兜住）。 */
    private fun captureRect(rect: org.json.JSONArray, cssWidth: Int): Bitmap? = runCatching {
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) return null
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        web.draw(android.graphics.Canvas(full))
        val f = w.toFloat() / cssWidth.toFloat()
        val x = (rect.getDouble(0) * f).toInt().coerceIn(0, w - 1)
        val y = (rect.getDouble(1) * f).toInt().coerceIn(0, h - 1)
        val cw = (rect.getDouble(2) * f).toInt().coerceIn(1, w - x)
        val ch = (rect.getDouble(3) * f).toInt().coerceIn(1, h - y)
        Bitmap.createBitmap(full, x, y, cw, ch).also { if (it !== full) full.recycle() }
    }.getOrNull()

    private fun decode(dataUrl: String): Bitmap? = runCatching {
        val b64 = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /**
     * 官方页里的 canvas 只有 180px（139 的 qrcode.js 写死 180），直接放大到 630px 会糊。
     * 先解出二维码内容，再用 ZXing 重编码成 760px，扫描距离和清晰度才够。
     */
    private fun sharpen(src: Bitmap): Bitmap? = runCatching {
        // 139 的 canvas 无静区、只有 180px，ZXing 直接解会 NotFound；
        // 先按整数放大约 4 倍再补一圈白边（静区），解码成功率才稳。
        val scale = maxOf(1, 640 / maxOf(1, src.width))
        val w = src.width * scale
        val h = src.height * scale
        val pad = 48
        val tw = w + pad * 2
        val th = h + pad * 2
        val px = IntArray(tw * th) { android.graphics.Color.WHITE }
        val sw = src.width
        val sh = src.height
        val gray = IntArray(sw * sh)
        src.getPixels(gray, 0, sw, 0, 0, sw, sh)
        for (y in 0 until h) for (x in 0 until w) {
            val rgb = gray[(y / scale) * sw + (x / scale)]
            val lum = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000
            if (lum < 128) px[(y + pad) * tw + (x + pad)] = android.graphics.Color.BLACK
        }
        val source = com.google.zxing.RGBLuminanceSource(tw, th, px)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val reader = com.google.zxing.MultiFormatReader()
        reader.setHints(
            mapOf(
                com.google.zxing.DecodeHintType.TRY_HARDER to true,
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            )
        )
        val text = reader.decodeWithState(bitmap)?.text
        if (text.isNullOrBlank()) return null
        Log.i(TAG, "qr-decoded payload=${text.length} head=${text.take(46)}")
        val m = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 760, 760, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val out = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            out.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        out
    }.getOrNull()

    @Volatile private var lastCookie = ""

    /** 把扫出来的会话落到 app 私有目录，供宿主侧直接复用/验证（不进 logcat，避免明文外泄）。 */
    private fun dumpCookie(cookie: String) {
        if (cookie.isBlank() || cookie == lastCookie) return
        lastCookie = cookie
        runCatching {
            java.io.File(web.context.filesDir, "panweb-cookie.txt").writeText(cookie)
            Log.i(TAG, "cookie-dump len=${cookie.length}")
        }
    }

    /** 已登录后仍跑几次 PROBE，只为采集页面真实 API 路径（不触发任何状态回调）。 */
    private fun logRes(raw: String?, tag: String) {
        val js = runCatching {
            if (raw != null && raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as String else raw
        }.getOrNull()
        val json = js?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        json.optJSONArray("res")?.let { arr ->
            val list = (0 until arr.length()).map { arr.optString(it) }
            if (list.isNotEmpty()) Log.i(TAG, "api-res-$tag " + list.joinToString(" | "))
        }
    }

    private fun captureRes(round: Int) {
        if (closed) return
        web.evaluateJavascript(PROBE) { raw ->
            val js = runCatching {
                if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as String else raw
            }.getOrNull()
            val json = js?.let { runCatching { JSONObject(it) }.getOrNull() }
            json?.optJSONArray("res")?.let { arr ->
                val list = (0 until arr.length()).map { arr.optString(it) }
                if (list.isNotEmpty()) {
                    lastRes = list
                    Log.i(TAG, "api-res#" + round + " " + list.joinToString(" | "))
                }
            }
            if (round < 4) handler.postDelayed({ captureRes(round + 1) }, 2500)
        }
    }

    private fun login(cookie: String) {
        loggedIn = true
        if (keepAfterLogin) {
            Log.i(TAG, "keep-login cookieLen=${cookie.length}（调试保活，不回调）")
            return
        }
        handler.postDelayed({ captureRes(1) }, 2500)
        dumpCookie(cookie)
        Log.i(TAG, "web-login-ok cookieLen=${cookie.length}")
        onPhase(Phase.OK, "登录成功，正在续播…")
        onLogin(cookie)
    }

    private fun fail(msg: String) {
        Log.w(TAG, "web-login-fail $msg")
        onPhase(Phase.FAIL, msg)
        onFail(msg)
    }

    fun close() {
        if (closed) return
        closed = true
        if (live === this) live = null
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { (host.parent as? ViewGroup)?.removeView(host) }
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
        }
    }
}
