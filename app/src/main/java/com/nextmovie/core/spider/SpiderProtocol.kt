package com.nextmovie.core.spider

import android.os.Bundle
import com.nextmovie.core.config.Site
import org.json.JSONObject

/**
 * 插件进程协议。
 *
 * 插件（尤其 Guard 系）会加载第三方 native 库，一旦崩溃就是 SIGSEGV，宿主进程里的 try/catch 拦不住。
 * 因此所有插件调用都放到 `:spider` 独立进程，主进程只通过 Messenger 收发「原始 JSON 字符串」。
 */
object SpiderProtocol {

    const val ACTION_REPLY = 100
    const val ACTION_PING = 1
    const val ACTION_HOME = 2
    const val ACTION_CATEGORY = 3
    const val ACTION_DETAIL = 4
    const val ACTION_SEARCH = 5
    const val ACTION_PLAY = 6
    const val ACTION_CANCEL = 7
    const val ACTION_RESTART = 8

    const val KEY_ID = "id"
    const val KEY_OK = "ok"
    const val KEY_DATA = "data"
    const val KEY_ERROR = "error"
    const val KEY_SITE = "site"
    const val KEY_ARG1 = "arg1"
    const val KEY_ARG2 = "arg2"
    const val KEY_ARG3 = "arg3"
    const val KEY_PAGE = "page"
    const val KEY_FILTER = "filter"

    fun Site.toTransport(): String = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("api", api)
        put("type", type)
        put("ext", ext)
        put("jar", jar)
        put("jarHash", jarHash)
        put("origin", origin)
        put("searchable", searchable)
        put("quickSearch", quickSearch)
        put("filter", filterable)
        put("headers", JSONObject(headers))
    }.toString()

    fun siteFromTransport(json: String): Site =
        JSONObject(json).let { obj ->
            Site.fromJson(obj, origin = obj.optString("origin")).copy(jarHash = obj.optString("jarHash"))
        }

    data class Reply(val ok: Boolean, val data: String, val error: String)

    fun okBundle(id: Int, data: String): Bundle = Bundle().apply {
        putInt(KEY_ID, id)
        putBoolean(KEY_OK, true)
        putString(KEY_DATA, data)
    }

    fun errorBundle(id: Int, message: String): Bundle = Bundle().apply {
        putInt(KEY_ID, id)
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, message)
    }
}
