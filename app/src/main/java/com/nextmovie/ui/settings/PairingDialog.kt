package com.nextmovie.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nextmovie.core.pairing.LanImportServer
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PairingDialog(onDismiss: () -> Unit, onReceived: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableIntStateOf(300) }
    val latestReceived by rememberUpdatedState(onReceived)
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val server = runCatching { LanImportServer { text -> main.post { latestReceived(text) } } }
            .onFailure { error = "无法启动扫码导入，请检查网络后重试" }.getOrNull()
        if (server != null) {
            val address = runCatching { LanImportServer.addresses().firstOrNull() }.getOrNull()
            if (address == null) { error = "请先将电视连接到 Wi-Fi 或有线局域网" }
            else url = server.url(address)
        }
        onDispose { server?.close() }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (remaining > 0) { delay(1000); remaining-- }
        onDismiss()
    }
    val bitmap by produceState<Bitmap?>(null, url) {
        if (url.isBlank()) return@produceState
        value = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 560, 560)
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { b ->
                val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                b.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.width(760.dp).background(Tokens.Surface).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            bitmap?.let { Image(it.asImageBitmap(), "手机扫码导入配置", Modifier.size(264.dp).background(androidx.compose.ui.graphics.Color.White)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("手机扫码导入", color = Tokens.Text, style = MaterialTheme.typography.headlineMedium)
                Text(error ?: "手机与电视连接同一网络，使用相机或浏览器扫码，粘贴配置网址或 JSON。发送后在电视上确认。", color = Tokens.TextDim, style = MaterialTheme.typography.bodyLarge)
                if (url.isNotBlank()) Text(url.substringBefore("?"), color = Tokens.TextDim, style = MaterialTheme.typography.bodyMedium)
                Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} 后关闭", color = Tokens.Accent)
                Text("关闭", color = Tokens.Text, modifier = Modifier.focusRequester(focus).tvClickable(onClick = onDismiss).padding(horizontal = 24.dp, vertical = 14.dp))
            }
        }
    }
}
