package com.nextmovie.core.spider

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synchronous CatVod dialog ABI, displayed only by a real foreground plugin Activity. */
object PluginDialogs {
    @Volatile private var activity = WeakReference<Activity>(null)
    private val windows = ConcurrentHashMap<Any, AlertDialog>()
    fun attach(value: Activity) { activity = WeakReference(value) }
    fun current(): Activity? = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    fun detach(value: Activity) {
        if (activity.get() === value) { windows.values.toList().forEach { it.dismiss() }; activity.clear() }
    }
    fun close(owner: Any) { Handler(Looper.getMainLooper()).post { windows.remove(owner)?.dismiss() } }
    fun show(owner: Any, options: JSONObject): JSONObject? {
        val host = current() ?: return null
        check(Looper.myLooper() != Looper.getMainLooper()) { "dialog must be called from a plugin worker" }
        val ready = CountDownLatch(1)
        var answer: JSONObject? = null
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (host.isFinishing || host.isDestroyed) { ready.countDown(); return@post }
            runCatching {
                windows.remove(owner)?.dismiss()
                val scale = host.resources.displayMetrics.density
                fun dp(value: Int) = (value * scale).toInt()
                val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
                options.optString("message").takeIf { it.isNotBlank() }?.let { message ->
                    column.addView(TextView(host).apply { text = message; textSize = 20f; setTextColor(android.graphics.Color.WHITE) })
                }
                val qr = options.optString("qrcode")
                if (qr.isNotBlank()) {
                    val matrix = QRCodeWriter().encode(qr, BarcodeFormat.QR_CODE, 560, 560)
                    val pixels = IntArray(560 * 560) { i -> if (matrix[i % 560, i / 560]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    val bitmap = Bitmap.createBitmap(pixels, 560, 560, Bitmap.Config.ARGB_8888)
                    column.addView(ImageView(host).apply { setImageBitmap(bitmap); contentDescription = "网盘登录二维码" }, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
                }
                val image = options.optString("image")
                if (image.startsWith("data:image/") && image.length < 4_000_000) {
                    val bytes = android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) column.addView(ImageView(host).apply { setImageBitmap(bitmap); contentDescription = "来源提供的登录图片" }, LinearLayout.LayoutParams(dp(280), dp(280)))
                }
                val fields = LinkedHashMap<String, Pair<JSONObject, EditText>>()
                val inputs = options.optJSONArray("inputs")
                if (inputs != null) for (i in 0 until inputs.length()) {
                    val input = inputs.optJSONObject(i) ?: continue
                    val name = input.optString("name").takeIf { it.isNotBlank() } ?: continue
                    column.addView(TextView(host).apply { text = input.optString("label", name); textSize = 18f; setTextColor(android.graphics.Color.WHITE) })
                    val edit = EditText(host).apply {
                        setText(input.optString("value")); setTextColor(android.graphics.Color.WHITE); textSize = 20f
                        inputType = when (input.optString("type")) {
                            "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            "number" -> InputType.TYPE_CLASS_NUMBER
                            "phone" -> InputType.TYPE_CLASS_PHONE
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        isEnabled = input.optBoolean("enabled", true)
                    }
                    fields[name] = input to edit; column.addView(edit)
                }
                val scroll = ScrollView(host).apply { addView(column) }
                val buttons = options.optJSONArray("buttons")
                val ok = buttons?.optJSONObject(0)
                val dialog = AlertDialog.Builder(host).setTitle(options.optString("title", "来源账户"))
                    .setView(scroll).setCancelable(options.optBoolean("cancelable", true))
                    .setPositiveButton(ok?.optString("label", "确定") ?: "确定", null)
                    .setNegativeButton("取消") { _, _ -> answer = null }
                    .create()
                dialog.setOnDismissListener { windows.remove(owner, dialog); ready.countDown() }
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val values = JSONObject()
                        var valid = true
                        fields.forEach { (name, pair) ->
                            val value = pair.second.text.toString()
                            if (pair.first.optBoolean("required") && value.isBlank()) { pair.second.error = "请填写此项"; valid = false }
                            values.put(name, value)
                        }
                        if (valid) { answer = JSONObject().put("button", ok?.optString("id", "ok") ?: "ok").put("values", values); dialog.dismiss() }
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                windows[owner] = dialog
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(android.R.color.black)
                dialog.window?.setLayout((host.resources.displayMetrics.widthPixels * 0.8f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            }.onFailure { ready.countDown() }
        }
        try { ready.await(5, TimeUnit.MINUTES) } finally { close(owner) }
        return answer
    }
}
