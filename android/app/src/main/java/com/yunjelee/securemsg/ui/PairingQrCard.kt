package com.yunjelee.securemsg.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/**
 * The QR this device shows while it waits for an already-trusted device to
 * approve it. Everything encoded here is public: keys, a one-time nonce, the
 * relay origin. Possession of the image grants nothing — approval still needs
 * the other device's signature and the human safety-number comparison.
 */
private const val QR_LIFETIME_SECONDS = 600L

fun pairingQrPayload(
    server: String,
    username: String,
    sid: String,
    challenge: String,
    boxPk: String,
    sigPk: String,
    nonceNew: String,
    nowSeconds: Long,
): String = JSONObject()
    .put("v", 1)
    .put("type", "securemsg-pairing")
    .put("server", server)
    .put("username", username)
    .put("sid", sid)
    .put("challenge", challenge)
    .put("box_pk", boxPk)
    .put("sig_pk", sigPk)
    .put("nonce_new", nonceNew)
    .put("expires_at", nowSeconds + QR_LIFETIME_SECONDS)
    .toString()

fun encodeQrBitmap(payload: String, size: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    bitmap
}.getOrNull()

@Composable
fun PairingQrCard(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { encodeQrBitmap(payload) }
    Column(modifier) {
        Caption("이미 승인된 기기의 ‘기기 보안 → QR 스캔으로 승인’에서 이 코드를 비추세요.")
        Spacer(Modifier.height(8.dp))
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "기기 페어링 QR 코드",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        } else {
            Caption("QR을 생성하지 못했습니다. 아래 지문으로 직접 확인하세요.")
        }
    }
}
