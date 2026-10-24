package com.dentifay.app.stock

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Swift `BarcodeScannerSheet.isAvailable` karşılığı. */
object BarcodeScannerSheet {
    /** Cihazda kamera var mı (izin ayrıca istenir). */
    fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

/**
 * Ürün barkodu / QR tarayıcı (CameraX + ML Kit). İlk okunan kod `onScan` ile döner ve sayfa kapanır.
 * Sunan taraf `DfySheet` içine koyar.
 */
@Composable
fun BarcodeScannerSheet(onScan: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    var granted by remember { mutableStateOf(inPreview || BarcodeScannerSheet.hasPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && !inPreview) {
            BarcodeCameraView(onScan = { code ->
                Haptics.success()
                onScan(code)
                onDismiss()
            })
        } else if (denied) {
            Text(
                "Kamera izni kapalı. Barkodu arama alanına yazabilirsiniz.",
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                style = sys(14, FontWeight.Medium), color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
            )
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(38.dp)
                        // `.ultraThinMaterial` yerine yarı saydam koyu yüzey.
                        .background(Color(0xFF2A2A2E).copy(alpha = 0.72f), CircleShape)
                        .semantics { contentDescription = "Kapat" }
                        .plainClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    DfyIcon("xmark", size = 15.dp, tint = Color.White)
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Ürünün barkodunu ya da QR kodunu çerçeveye getirin",
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
                    .background(Color(0xFF2A2A2E).copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                style = sys(14, FontWeight.Medium), color = Color.White, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Kamera önizlemesi + ML Kit barkod çözümleme; yalnız ilk geçerli kod teslim edilir. */
@Composable
private fun BarcodeCameraView(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScan by rememberUpdatedState(onScan)
    val previewView = remember {
        PreviewView(context).apply {
            // Sayfa animasyonu / kırpma ile uyumlu olsun diye TextureView.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy -> analyze(proxy, scanner, delivered) { code -> mainExecutor.execute { currentOnScan(code) } } }

        providerFuture.addListener({
            if (disposed) return@addListener
            runCatching {
                val p = providerFuture.get()
                provider = p
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            delivered.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun analyze(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    delivered: AtomicBoolean,
    deliver: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null || delivered.get()) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            if (delivered.get()) return@addOnSuccessListener
            for (b in barcodes) {
                val value = b.rawValue?.trim().orEmpty()
                if (value.isNotEmpty() && delivered.compareAndSet(false, true)) {
                    deliver(value)
                    return@addOnSuccessListener
                }
            }
        }
        .addOnCompleteListener { proxy.close() }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun BarcodeScannerSheetPreview() {
    PreviewEnv {
        BarcodeScannerSheet(onScan = {}, onDismiss = {})
    }
}
