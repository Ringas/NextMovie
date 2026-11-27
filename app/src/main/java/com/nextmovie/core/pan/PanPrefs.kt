package com.nextmovie.core.pan

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * 网盘凭据写入通道（宿主侧）。
 *
 * 背景：插件（加壳 dex + native guard）没有对外开 `setPan139Cookie` 之类的写接口，
 * 但它的凭据全部落在同名进程的 SharedPreferences 里，key 规则是 `Wex_<provider>_cookie`：
 * 真机二分验证——把 `Wex_quark_cookie` 改成垃圾值后，插件 status 的 quarkCookieLength 从 415 变 7，
 * 其余 9 个别名 key 全改都不影响，说明这就是插件唯一读取的槽位。
 *
 * 插件只在 :spider 进程初始化时读一次 prefs（进程内缓存），所以写完必须重启 :spider，
 * 由宿主下一次 resolve 把它再拉起来。这条链路 = 「不修改插件、也能给它写凭据」。
 */
object PanPrefs {

    private const val TAG = "NextMoviePan"
    private const val PREFS = "NewWexFnw_preferences.xml"

    fun prefsFile(ctx: Context): File = File(ctx.applicationInfo.dataDir, "shared_prefs/$PREFS")

    /** 按 `Wex_<provider>_cookie` 规则给某个网盘写 cookie；写完不生效，需配合 [restartPlugin]。 */
    fun writeCookie(ctx: Context, provider: String, cookie: String): Boolean =
        writeStrings(ctx, mapOf(keyOf(provider) to cookie))

    fun keyOf(provider: String): String = "Wex_${provider}_cookie"

    /** 写任意 prefs 键（调试/回滚用），返回是否落盘成功。 */
    fun writeStrings(ctx: Context, values: Map<String, String>): Boolean {
        val f = prefsFile(ctx)
        if (!f.exists() || values.isEmpty()) return false
        return runCatching {
            var out = f.readText()
            values.forEach { (k, v) ->
                val esc = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                out = if (out.contains("name=\"$k\"")) {
                    Regex("name=\"${Regex.escape(k)}\">[^<]*<").replaceFirst(out, "name=\"$k\">$esc<")
                } else {
                    out.replace("</map>", "    <string name=\"$k\">$esc</string>\n</map>")
                }
            }
            val tmp = File(f.parentFile, "$PREFS.tmp")
            tmp.writeText(out)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(f)) {
                // 个别机型 rename 跨 inode 失败，退回直接覆盖
                f.writeText(out)
            }
            tmp.delete()
            Log.i(TAG, "pan-prefs 写入 " + values.keys.joinToString(",") + " len=" + values.values.first().length)
            true
        }.getOrElse {
            Log.w(TAG, "pan-prefs 写入失败: ${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /** 读回某个键，确认真的落盘（prefs 是 XML，简单正则就够）。 */
    fun readString(ctx: Context, key: String): String? {
        val f = prefsFile(ctx)
        if (!f.exists()) return null
        val m = Regex("name=\"${Regex.escape(key)}\">([^<]*)<").find(f.readText()) ?: return null
        return m.groupValues[1]
    }

    /**
     * 只重启 `:spider`（插件进程），不动主进程与播放器。
     * 插件的静态初始化会重新读 prefs，凭据即刻生效。
     */
    fun restartPlugin(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val name = ctx.packageName + ":spider"
        val pid = am.runningAppProcesses?.firstOrNull { it.processName == name }?.pid ?: return false
        return runCatching {
            Process.killProcess(pid)
            Log.i(TAG, "pan-prefs 重启插件进程 pid=$pid")
            true
        }.getOrDefault(false)
    }
}
