package com.nextmovie.core.spider

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * 宿主侧的插件对话框（对应 FongMi `Runtime.setDialog(SpiderDialog::show)`）。
 *
 * 插件弹登录框的完整链路是：插件 action -> Runtime.dialog(options, callback) ->
 * 宿主渲染二维码/输入框 -> 用户扫码或填写 -> 结果回给插件。
 * 之前宿主没装这个 handler，Runtime.dialog 直接抛 UnsupportedOperationException，
 * 插件捕获后 action 返回 "{}"，于是"扫不出码、登录不了"。
 *
 * 协议（与 FongMi 一致）：
 * - options: title/message/qrcode/image/inputs/buttons/cancelable
 * - 无 callback：点按钮直接以 {"button":id,"values":{...}} 完成
 * - 有 callback：点按钮先回调插件拿 patch，patch 里 qrcode/message/values 用于刷新界面，
 *   close=true 时才结束；否则对话框留着继续轮询（扫码登录就是这么持续刷新的）
 */
object SpiderDialogHost {

    fun show(options: String, callback: Function<String, CompletableFuture<String>>?): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        Handler(Looper.getMainLooper()).post {
            runCatching { build(options, callback, result) }
                .onFailure { result.completeExceptionally(it) }
        }
        return result
    }

    private class Holder(
        val result: CompletableFuture<String>,
        val callback: Function<String, CompletableFuture<String>>?,
        val dialog: AlertDialog,
        val fields: LinkedHashMap<String, EditText>,
        val buttons: LinkedHashMap<Int, JSONObject>,
        val qr: ImageView,
        val message: TextView,
        val activity: Activity
    ) {
        @Volatile var busy = false
    }

    private fun build(options: String, callback: Function<String, CompletableFuture<String>>?, result: CompletableFuture<String>) {
        val host = PluginDialogs.current() ?: com.github.catvod.Runtime.activity()
        ?: throw IllegalStateException("没有可用的 Activity，插件对话框无法显示")
        val json = JSONObject(options)
        val density = host.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val column = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val message = TextView(host).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        message.text = json.optString("message")
        message.visibility = if (message.text.isBlank()) ViewGroup.GONE else ViewGroup.VISIBLE
        column.addView(message)

        val qr = ImageView(host).apply {
            visibility = ViewGroup.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        column.addView(qr, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val qrCode = json.optString("qrcode")
        if (qrCode.isNotBlank()) setQr(qr, qrCode)

        val fields = LinkedHashMap<String, EditText>()
        json.optJSONArray("inputs")?.let { inputs ->
            for (i in 0 until inputs.length()) {
                val input = inputs.optJSONObject(i) ?: continue
                val name = input.optString("name").takeIf { it.isNotBlank() } ?: continue
                column.addView(TextView(host).apply {
                    text = input.optString("label", name)
                    textSize = 16f
                    setTextColor(0xFFB0B0B0.toInt())
                })
                val edit = EditText(host).apply {
                    setText(input.optString("value"))
                    setTextColor(Color.WHITE)
                    textSize = 19f
                    inputType = when (input.optString("type")) {
                        "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        "number" -> InputType.TYPE_CLASS_NUMBER
                        "phone" -> InputType.TYPE_CLASS_PHONE
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                }
                column.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                fields[name] = edit
            }
        }

        val builder = AlertDialog.Builder(host)
            .setTitle(json.optString("title", "来源账户"))
            .setView(ScrollView(host).apply { addView(column) })
            .setCancelable(json.optBoolean("cancelable", true))

        val buttons = LinkedHashMap<Int, JSONObject>()
        val declared = json.optJSONArray("buttons")
        if (declared == null || declared.length() == 0) {
            buttons[AlertDialog.BUTTON_POSITIVE] = JSONObject("""{"id":"ok","label":"确定","close":true}""")
            builder.setPositiveButton("确定", null).setNegativeButton("取消", null)
        } else {
            for (i in 0 until minOf(declared.length(), 3)) {
                val button = declared.optJSONObject(i) ?: continue
                val which = when (i) {
                    0 -> AlertDialog.BUTTON_POSITIVE
                    1 -> AlertDialog.BUTTON_NEGATIVE
                    else -> AlertDialog.BUTTON_NEUTRAL
                }
                buttons[which] = button
                when (which) {
                    AlertDialog.BUTTON_POSITIVE -> builder.setPositiveButton(button.optString("label", "确定"), null)
                    AlertDialog.BUTTON_NEGATIVE -> builder.setNegativeButton(button.optString("label", "取消"), null)
                    else -> builder.setNeutralButton(button.optString("label", "关闭"), null)
                }
            }
        }

        val dialog = builder.create()
        val holder = Holder(result, callback, dialog, fields, buttons, qr, message, host)
        dialog.setOnDismissListener { if (!result.isDone) result.complete(null) }
        dialog.setOnShowListener {
            buttons.keys.forEach { which ->
                dialog.getButton(which)?.let { view ->
                    view.setOnClickListener { onButton(holder, which) }
                    view.requestFocus()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.window?.setLayout((host.resources.displayMetrics.widthPixels * 0.62f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
        android.util.Log.i("NextMoviePan", "dialog-show title=${json.optString("title")} qr=${qrCode.isNotBlank()} inputs=${fields.size} buttons=${buttons.size} callback=${callback != null}")
    }

    private fun setQr(view: ImageView, code: String) {
        runCatching {
            val size = 560
            val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
            val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            view.setImageBitmap(bitmap)
            view.contentDescription = code
            view.visibility = ViewGroup.VISIBLE
        }.onFailure { android.util.Log.w("NextMoviePan", "qr-encode failed: $it") }
    }

    private fun onButton(holder: Holder, which: Int) {
        if (holder.result.isDone || holder.busy) return
        val button = holder.buttons[which] ?: return
        val id = button.optString("id", "ok")
        val values = JSONObject()
        holder.fields.forEach { (name, edit) -> values.put(name, edit.text.toString()) }
        var invalid = false
        holder.fields.forEach { (name, edit) ->
            if (!invalid && edit.text.isBlank()) {
                // 只有插件标记 required 的字段才拦截
                val required = holder.buttons.values.any { it.optBoolean("required", false) }
                if (required) { edit.error = "请填写"; invalid = true }
            }
        }
        if (invalid) return
        val event = JSONObject().put("button", id).put("values", values).toString()
        val callback = holder.callback
        if (callback == null) {
            if (!holder.result.isDone) holder.result.complete(event)
            holder.dialog.dismiss()
            return
        }
        holder.busy = true
        setEnabled(holder, false, "处理中…", which)
        Thread {
            val future = runCatching { callback.apply(event) }.getOrNull()
            if (future == null) {
                Handler(Looper.getMainLooper()).post {
                    if (!holder.result.isDone) holder.result.completeExceptionally(IllegalStateException("插件未返回更新"))
                }
                return@Thread
            }
            future.whenComplete { update, error ->
                Handler(Looper.getMainLooper()).post {
                    holder.busy = false
                    if (error != null) {
                        if (!holder.result.isDone) holder.result.completeExceptionally(error)
                        return@post
                    }
                    val patch = runCatching { JSONObject(update ?: "{}") }.getOrDefault(JSONObject())
                    patch.optString("qrcode").takeIf { it.isNotBlank() }?.let { setQr(holder.qr, it) }
                    patch.optString("message").takeIf { it.isNotBlank() }?.let {
                        holder.message.text = it
                        holder.message.visibility = ViewGroup.VISIBLE
                    }
                    patch.optJSONObject("values")?.let { values ->
                        values.keys().forEach { key -> holder.fields[key]?.setText(values.optString(key)) }
                    }
                    val close = if (patch.has("close")) patch.optBoolean("close") else button.optBoolean("close", true)
                    if (close) {
                        if (!holder.result.isDone) holder.result.complete(event)
                        holder.dialog.dismiss()
                    } else {
                        setEnabled(holder, true, button.optString("label", "确定"), which)
                        android.util.Log.i("NextMoviePan", "dialog-refresh qr=${patch.optString("qrcode").take(12)} close=false")
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun setEnabled(holder: Holder, enabled: Boolean, label: String, which: Int) {
        holder.fields.values.forEach { it.isEnabled = enabled }
        holder.buttons.keys.forEach { key ->
            holder.dialog.getButton(key)?.let {
                it.isEnabled = enabled
                if (key == which) it.text = label
            }
        }
    }
}
