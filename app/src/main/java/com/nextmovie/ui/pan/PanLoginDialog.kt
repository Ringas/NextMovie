package com.nextmovie.ui.pan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextmovie.ui.common.tvClickable
import com.nextmovie.ui.theme.Tokens

/**
 * 网盘登录卡片 —— 播放页内直接弹，不跳配置中心。
 *
 * 三种形态共用同一张卡：
 *  - QR：插件自带扫码接口 / 宿主离屏 WebView 抓到的官方码；
 *  - INPUT：插件没有扫码接口的网盘（移动短信、迅雷短信、123 账号），
 *    在卡片里用遥控器数字键盘完成，登录成功立刻续播；
 *  - MANUAL：兜底，留一个「打开配置中心」入口。
 */
enum class PanPhase { LOADING, WAIT, SCANNED, OK, MANUAL, FAIL, INPUT }

/** 卡片里正在填哪一栏 */
enum class PanField { PHONE, CODE }

/** 输入态的全部 UI 数据：宿主只管往里写，卡片只管画。 */
data class PanInputUi(
    val title: String = "短信验证码登录",
    val hint: String = "",
    val field: PanField = PanField.PHONE,
    val phone: String = "",
    val code: String = "",
    val sent: Boolean = false,
    val busy: Boolean = false,
    val error: String = "",
    val countdown: Int = 0,
)

@Composable
private fun panPx(v: Float): Dp = with(LocalDensity.current) { (v / density).dp }

@Composable
private fun panTx(v: Float): TextUnit = with(LocalDensity.current) { (v / density / fontScale).sp }

@Composable
fun PanLoginDialog(
    label: String,
    phase: PanPhase,
    qr: Bitmap?,
    message: String,
    secondsLeft: Int,
    onRetry: () -> Unit,
    onAccount: () -> Unit,
    onClose: () -> Unit,
    secondaryLabel: String = "账号登录",
    /** 这颗码是「手机扫码打开填写页」还是「官方 App 登录码」，决定文案 */
    phoneQr: Boolean = false,
    input: PanInputUi? = null,
    onKey: (Char) -> Unit = {},
    onBackspace: () -> Unit = {},
    onClear: () -> Unit = {},
    onPickField: (PanField) -> Unit = {},
    onSend: () -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA8040609)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(panPx(920f))
                .clip(RoundedCornerShape(panPx(32f)))
                .background(Color(0xF70C111A))
                .padding(horizontal = panPx(48f), vertical = panPx(44f))
        ) {
            // —— 头部 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(panPx(58f))
                        .height(panPx(58f))
                        .clip(RoundedCornerShape(panPx(16f)))
                        .background(Tokens.Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label.take(1),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                Spacer(Modifier.width(panPx(18f)))
                Column {
                    Text(
                        label + "网盘",
                        color = Tokens.Text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = panTx(31f), fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        when (phase) {
                            PanPhase.INPUT -> "验证码登录 · 网盘直连"
                            PanPhase.MANUAL -> "账号登录 · 网盘直连"
                            else -> "扫码登录 · 网盘直连"
                        },
                        color = Tokens.TextFaint,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(17f))
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(panPx(46f))
                        .height(panPx(46f))
                        .clip(RoundedCornerShape(panPx(23f)))
                        .background(Tokens.Surface)
                        .tvClickable(onClick = onClose, corner = panPx(23f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Tokens.TextDim, style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(20f)))
                }
            }

            Spacer(Modifier.height(panPx(28f)))
            Box(Modifier.height(panPx(1f)).fillMaxWidth().background(Tokens.Stroke))
            Spacer(Modifier.height(panPx(30f)))

            if (input != null) {
                PanInputBody(
                    input = input,
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onClear = onClear,
                    onPickField = onPickField,
                    onSend = onSend,
                    onSubmit = onSubmit,
                )
            } else {
                PanQrBody(
                    label = label,
                    phase = phase,
                    qr = qr,
                    message = message,
                    secondsLeft = secondsLeft,
                    onRetry = onRetry,
                    onAccount = onAccount,
                    secondaryLabel = secondaryLabel,
                    phoneQr = phoneQr,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 扫码形态 */

@Composable
private fun PanQrBody(
    label: String,
    phase: PanPhase,
    qr: Bitmap?,
    message: String,
    secondsLeft: Int,
    onRetry: () -> Unit,
    onAccount: () -> Unit,
    secondaryLabel: String = "账号登录",
    /** 这颗码是「手机扫码打开填写页」还是「官方 App 登录码」，决定文案 */
    phoneQr: Boolean = false,
) {
    Row {
        // 左：二维码
        Box(
            modifier = Modifier
                .width(panPx(372f))
                .height(panPx(372f))
                .clip(RoundedCornerShape(panPx(24f)))
                .background(if (qr != null) Color.White else Color(0x0AFFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            when {
                qr != null -> Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "登录二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(panPx(316f)).height(panPx(316f))
                )
                phase == PanPhase.MANUAL -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "账号登录", color = Tokens.Text,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = panTx(34f), fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(panPx(8f)))
                    Text(
                        "手机验证码 / 密码", color = Tokens.TextFaint,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(18f))
                    )
                }
                phase == PanPhase.OK -> Box(
                    modifier = Modifier.width(panPx(96f)).height(panPx(96f)).clip(RoundedCornerShape(panPx(48f)))
                        .background(Tokens.GreenDim),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = Tokens.Green, style = MaterialTheme.typography.titleLarge) }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "生成二维码…", color = Tokens.TextGhost,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(18f))
                    )
                }
            }
        }

        Spacer(Modifier.width(panPx(44f)))

        // 右：说明 + 按钮
        Column(modifier = Modifier.width(panPx(392f))) {
            Text(
                when (phase) {
                    PanPhase.MANUAL -> "账号登录"
                    PanPhase.OK -> "登录成功"
                    PanPhase.SCANNED -> "已扫码"
                    else -> if (phoneQr) "手机扫码填写" else "手机扫码登录"
                },
                color = Tokens.Text,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = panTx(34f), fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(panPx(10f)))
            Text(
                message.ifBlank { if (phoneQr) "手机扫码打开填写页，填完自动续播" else "打开「$label」App 扫一扫二维码" },
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(17f), lineHeight = panTx(28f))
            )

            if (phase == PanPhase.WAIT || phase == PanPhase.SCANNED || phase == PanPhase.LOADING) {
                Spacer(Modifier.height(panPx(24f)))
                if (phoneQr) {
                    PanStep(1, "用手机相机 / 微信扫左侧码")
                    PanStep(2, "在手机上填「$label」的登录信息")
                    PanStep(3, "点「完成」，电视自动续播")
                } else {
                    PanStep(1, "打开手机「$label」App")
                    PanStep(2, "扫一扫对准左侧二维码")
                    PanStep(3, "手机上点「确认登录」")
                }
                Spacer(Modifier.height(panPx(22f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "二维码有效期", color = Tokens.TextFaint,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(16f))
                    )
                    Spacer(Modifier.width(panPx(12f)))
                    Box(
                        modifier = Modifier.weight(1f).height(panPx(6f))
                            .clip(RoundedCornerShape(panPx(3f))).background(Color(0x17FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((secondsLeft / 60f).coerceIn(0f, 1f))
                                .height(panPx(6f))
                                .background(Tokens.Accent)
                        )
                    }
                    Spacer(Modifier.width(panPx(12f)))
                    Text(
                        "${secondsLeft}″", color = Tokens.TextDim,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(16f))
                    )
                }
            }

            Spacer(Modifier.height(panPx(28f)))
            Row(horizontalArrangement = Arrangement.spacedBy(panPx(14f))) {
                val first = remember { FocusRequester() }
                LaunchedEffect(phase) {
                    kotlinx.coroutines.delay(120)
                    runCatching { first.requestFocus() }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(panPx(27f)))
                        .background(Tokens.Accent)
                        .focusRequester(first)
                        .tvClickable(onClick = if (phase == PanPhase.MANUAL) onAccount else onRetry, corner = panPx(27f))
                        .padding(horizontal = panPx(26f), vertical = panPx(15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (phase) {
                            PanPhase.MANUAL -> "打开配置中心"
                            PanPhase.OK -> "立即继续"
                            else -> "重新出码"
                        },
                        color = Tokens.OnAccent,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = panTx(18f), fontWeight = FontWeight.SemiBold)
                    )
                }
                if (phase != PanPhase.MANUAL) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(panPx(27f)))
                            .background(Tokens.Surface)
                            .tvClickable(onClick = onAccount, corner = panPx(27f))
                            .padding(horizontal = panPx(22f), vertical = panPx(15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(secondaryLabel, color = Tokens.TextDim, style = MaterialTheme.typography.labelMedium.copy(fontSize = panTx(18f)))
                    }
                }
            }
            Spacer(Modifier.height(panPx(20f)))
            Text(
                when (phase) {
                    PanPhase.OK -> "凭据已写入本机，下次自动使用"
                    PanPhase.SCANNED -> "已识别到扫码，正在等待手机确认"
                    PanPhase.MANUAL -> "该网盘在本机走账号/短信登录，登录后自动续播"
                    PanPhase.FAIL -> "二维码没拿到，可重新出码或改用账号登录"
                    else -> "扫码成功后自动继续播放"
                },
                color = Tokens.TextGhost,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(15f))
            )
        }
    }
}

/* ------------------------------------------------------------------ 输入形态 */

private val PAN_KEY_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("⌫", "0", "清空"),
)

@Composable
private fun PanInputBody(
    input: PanInputUi,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onPickField: (PanField) -> Unit,
    onSend: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        // 左：数字键盘（遥控器上下左右直接选）
        Column(
            modifier = Modifier
                .width(panPx(372f))
                .clip(RoundedCornerShape(panPx(24f)))
                .background(Color(0x0AFFFFFF))
                .padding(panPx(14f)),
            verticalArrangement = Arrangement.spacedBy(panPx(10f))
        ) {
            PAN_KEY_ROWS.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(panPx(10f))) {
                    row.forEach { key ->
                        PanKeyButton(
                            text = key,
                            modifier = Modifier.weight(1f),
                            onPress = {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "清空" -> onClear()
                                    else -> onKey(key[0])
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(panPx(44f)))

        Column(modifier = Modifier.width(panPx(392f))) {
            Text(
                input.title,
                color = Tokens.Text,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = panTx(34f), fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(panPx(10f)))
            Text(
                input.hint.ifBlank { "验证码会发到你填的手机号，登录成功后自动继续播放" },
                color = Tokens.TextFaint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(17f), lineHeight = panTx(28f))
            )

            Spacer(Modifier.height(panPx(24f)))
            PanFieldBox(
                caption = "手机号",
                value = input.phone,
                placeholder = "请输入 11 位手机号",
                active = input.field == PanField.PHONE,
                onClick = { onPickField(PanField.PHONE) },
            )
            Spacer(Modifier.height(panPx(14f)))
            PanFieldBox(
                caption = "短信验证码",
                value = input.code,
                placeholder = if (input.sent) "请输入 6 位验证码" else "先点「发送验证码」",
                active = input.field == PanField.CODE,
                onClick = { onPickField(PanField.CODE) },
            )

            if (input.error.isNotBlank()) {
                Spacer(Modifier.height(panPx(12f)))
                Text(input.error, color = Tokens.Danger, style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(15f)))
            } else if (input.busy) {
                Spacer(Modifier.height(panPx(12f)))
                Text("正在调用插件…", color = Tokens.TextGhost, style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(15f)))
            }

            Spacer(Modifier.height(panPx(20f)))
            Row(horizontalArrangement = Arrangement.spacedBy(panPx(14f))) {
                val first = remember { FocusRequester() }
                LaunchedEffect(input.field, input.sent) {
                    kotlinx.coroutines.delay(80)
                    runCatching { first.requestFocus() }
                }
                val sendLocked = input.countdown > 0 || input.busy
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(panPx(27f)))
                        .background(if (sendLocked) Tokens.Surface else Tokens.Accent)
                        .focusRequester(first)
                        .tvClickable(onClick = if (sendLocked) ({}) else onSend, corner = panPx(27f))
                        .padding(horizontal = panPx(24f), vertical = panPx(15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (input.countdown > 0) "已发送 ${input.countdown}s" else "发送验证码",
                        color = if (sendLocked) Tokens.TextDim else Tokens.OnAccent,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = panTx(18f), fontWeight = FontWeight.SemiBold)
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(panPx(27f)))
                        .background(Tokens.Surface)
                        .tvClickable(onClick = onSubmit, corner = panPx(27f))
                        .padding(horizontal = panPx(24f), vertical = panPx(15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("登录并继续", color = Tokens.TextDim, style = MaterialTheme.typography.labelMedium.copy(fontSize = panTx(18f)))
                }
            }
            Spacer(Modifier.height(panPx(20f)))
            Text(
                "凭据直接写进插件，不跳配置中心；登录成功后自动续播当前线路",
                color = Tokens.TextGhost,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(15f))
            )
        }
    }
}

@Composable
private fun PanKeyButton(text: String, modifier: Modifier = Modifier, onPress: () -> Unit) {
    Box(
        modifier = modifier
            .height(panPx(76f))
            .clip(RoundedCornerShape(panPx(16f)))
            .background(Color(0x14FFFFFF))
            .tvClickable(onClick = onPress, corner = panPx(16f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Tokens.Text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = if (text.length > 1) panTx(18f) else panTx(30f),
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun PanFieldBox(
    caption: String,
    value: String,
    placeholder: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Text(caption, color = Tokens.TextFaint, style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(14f)))
        Spacer(Modifier.height(panPx(6f)))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panPx(62f))
                .clip(RoundedCornerShape(panPx(16f)))
                .background(if (active) Color(0x1FFFFFFF) else Color(0x0DFFFFFF))
                .tvClickable(onClick = onClick, corner = panPx(16f))
                .padding(horizontal = panPx(18f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (value.isEmpty()) placeholder else value,
                    color = if (value.isEmpty()) Tokens.TextGhost else Tokens.Text,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = panTx(24f),
                        fontWeight = if (value.isEmpty()) FontWeight.Normal else FontWeight.SemiBold
                    )
                )
                if (active && value.isNotEmpty()) {
                    Spacer(Modifier.width(panPx(4f)))
                    Box(Modifier.width(panPx(2f)).height(panPx(26f)).background(Tokens.Accent))
                }
                Spacer(Modifier.weight(1f))
                if (value.isNotEmpty()) {
                    Text(
                        "${value.length}", color = Tokens.TextGhost,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(14f))
                    )
                }
            }
        }
    }
}

@Composable
private fun PanStep(n: Int, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = panPx(18f))
    ) {
        Box(
            modifier = Modifier.width(panPx(26f)).height(panPx(26f)).clip(RoundedCornerShape(panPx(13f)))
                .background(Tokens.AccentDim),
            contentAlignment = Alignment.Center
        ) { Text("$n", color = Tokens.Accent, style = MaterialTheme.typography.labelSmall.copy(fontSize = panTx(14f))) }
        Spacer(Modifier.width(panPx(14f)))
        Text(text, color = Tokens.TextDim, style = MaterialTheme.typography.bodySmall.copy(fontSize = panTx(18f)))
    }
}
