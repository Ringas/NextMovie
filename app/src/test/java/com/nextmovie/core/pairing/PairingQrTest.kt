package com.nextmovie.core.pairing
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
class PairingQrTest {
    @Test fun generatedQrDecodesToTheExactPairingUrl() {
        val url = "http://192.168.1.20:34567/?token=abcdef0123456789"
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 560, 560)
        val pixels = IntArray(560 * 560) { i -> if (matrix[i % 560, i / 560]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(560, 560, pixels))))
        assertEquals(url, decoded.text)
    }
}
