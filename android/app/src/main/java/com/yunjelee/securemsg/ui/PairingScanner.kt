package com.yunjelee.securemsg.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Camera QR reader for approving a new device.
 *
 * Decoding is entirely on-device (ML Kit's bundled model) — no frame ever
 * leaves the phone. The scan itself authorizes nothing: it only opens a
 * pairing session, and the caller must still show the safety number and get a
 * human to confirm it before signing an approval.
 */
/**
 * Decode one camera frame. Extracted so the ImageProxy.image opt-in is
 * consumed by a named declaration — lint does not track the annotation
 * through an analyzer lambda.
 */
// Android lint reads the androidx annotation, not kotlin.OptIn.
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun scanFrame(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    skip: () -> Boolean,
    onFound: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null || skip()) {
        proxy.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { codes ->
            codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                ?.rawValue
                ?.let(onFound)
        }
        .addOnCompleteListener { proxy.close() }
}

@Composable
fun PairingScanner(
    onPayload: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var failure by remember { mutableStateOf<String?>(null) }
    // Latches on the first successful decode so a burst of frames cannot open
    // several pairing sessions for one scan.
    var handled by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        if (!result) failure = "카메라 권한이 없어 QR을 읽을 수 없습니다."
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier) {
        if (granted) {
            val previewView = remember { PreviewView(context) }
            val executor = remember { Executors.newSingleThreadExecutor() }
            val scanner = remember { BarcodeScanning.getClient() }

            DisposableEffect(Unit) {
                onDispose {
                    executor.shutdown()
                    scanner.close()
                }
            }

            LaunchedEffect(previewView) {
                val provider = ProcessCameraProvider.getInstance(context).get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    scanFrame(proxy, scanner, skip = { handled }) { value ->
                        if (!handled) {
                            handled = true
                            onPayload(value)
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                }.onFailure { failure = "카메라를 열지 못했습니다." }
            }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        failure?.let {
            Spacer(Modifier.height(8.dp))
            Caption(it)
        }
        Spacer(Modifier.height(8.dp))
        SmGhostButton(text = "스캔 취소", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}
