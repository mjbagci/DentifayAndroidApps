package com.dentifay.app.xray

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.util.Size as AndroidSize

/**
 * Rehberli röntgen çekimi: ekrandaki ya da negatoskoptaki panoramik filmi canlı ayrıştırır
 * (monitör/pencere/panel değil, röntgenin kendisi), kenarlarını çizer; odak ve pozlamayı
 * röntgene ayarlar. Röntgen kadrajı yeterince dolduruyor, sabit, odak oturmuş ve görüntü son
 * karelerin en net hâlindeyse otomatik çeker. Çekim analiz ekranına doğrudan kırpılmış gelir.
 *
 * @param onCapture çekilen fotoğraf (JPEG verisi) + canlı görüntüde bulunan röntgen alanı (dik çekimde).
 */
@Composable
fun XrayCameraView(onCapture: (ByteArray, XrayQuad?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { XrayCameraController(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val currentOnCapture by rememberUpdatedState(onCapture)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val onPhoto: (ByteArray, XrayQuad?) -> Unit = { data, hint ->
        scope.launch {
            flash.animateTo(0.8f, Motion.easeOut(80))
            flash.animateTo(0f, Motion.easeIn(300))
        }
        Haptics.success()
        currentOnCapture(data, hint)
        currentDismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { camera.start(lifecycleOwner, previewView, onPhoto) } else camera.markDenied()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.start(lifecycleOwner, previewView, onPhoto)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(Unit) { onDispose { camera.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (camera.status) {
            XrayCameraController.Status.denied -> XrayCameraMessage(
                "Kamera izni yok",
                "Ayarlar → Dentifay → Kamera iznini açın. Röntgeni galeriden ya da dosyadan da yükleyebilirsiniz.",
                settings = true,
            )
            XrayCameraController.Status.unavailable -> XrayCameraMessage(
                "Kamera kullanılamıyor",
                "Bu cihazda kamera bulunamadı. Röntgeni galeriden ya da dosyadan yükleyebilirsiniz.",
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                XrayGuideLayer(camera, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
            }
        }

        Column(Modifier.fillMaxSize()) {
            XrayCameraTopBar(camera, onClose = onDismiss)
            Spacer(Modifier.weight(1f))
            if (camera.status == XrayCameraController.Status.running) XrayCameraBottomPanel(camera)
        }

        Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
    }
}

// MARK: Üst bar

@Composable
private fun XrayCameraTopBar(camera: XrayCameraController, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XrayCameraCircleButton("xmark", "Kapat", onClick = onClose)
        Spacer(Modifier.weight(1f))
        val auto = camera.autoCapture
        Row(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, if (auto) DS.Colors.cyan400.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                .plainClickable { camera.autoCapture = !camera.autoCapture; Haptics.selection() }
                .semantics { contentDescription = "Röntgen bulunup netleşince otomatik çekim" }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DfyIcon(if (auto) "bolt.badge.automatic" else "hand.tap", size = 13.dp, tint = Color.White)
            Text(if (auto) "Otomatik" else "Manuel", style = sys(13, FontWeight.SemiBold), color = Color.White)
        }
        XrayCameraCircleButton(
            if (camera.torchOn) "flashlight.on.fill" else "flashlight.off.fill",
            if (camera.torchOn) "Feneri kapat" else "Feneri aç",
            modifier = Modifier.alpha(if (camera.hasTorch) 1f else 0f),
            enabled = camera.hasTorch,
        ) { camera.toggleTorch() }
    }
}

@Composable
private fun XrayCameraCircleButton(icon: String, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .plainClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        DfyIcon(icon, size = 16.dp, tint = Color.White)
    }
}

// MARK: Rehber katmanı

@Composable
private fun XrayGuideLayer(camera: XrayCameraController, size: Size) {
    val q = camera.quad
    val ready = camera.isReady
    val pts = q?.points?.map { camera.mapToView(it, size) }
    val p0 by animateOffsetAsState(pts?.get(0) ?: Offset.Zero, Motion.easeOut(120), label = "q0")
    val p1 by animateOffsetAsState(pts?.get(1) ?: Offset.Zero, Motion.easeOut(120), label = "q1")
    val p2 by animateOffsetAsState(pts?.get(2) ?: Offset.Zero, Motion.easeOut(120), label = "q2")
    val p3 by animateOffsetAsState(pts?.get(3) ?: Offset.Zero, Motion.easeOut(120), label = "q3")
    val dim by animateFloatAsState(if (q == null) 0.45f else 0.25f, Motion.easeInOut(250), label = "dim")
    val frameAlpha by animateFloatAsState(if (q == null) 0.75f else 0.25f, Motion.easeInOut(250), label = "frame")
    Canvas(Modifier.fillMaxSize()) {
        // Panoramik film ~2:1 — dik telefonda ekran genişliğine yayılan çerçeve.
        val w = size.width * 0.9f
        val frame = Rect(Offset((size.width - w) / 2, size.height * 0.40f - w / 4), Size(w, w / 2))
        val cr = CornerRadius(14.dp.toPx())
        // Çerçeve dışı karartma
        val dimPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(frame, cr))
        }
        drawPath(dimPath, Color.Black.copy(alpha = dim))
        drawRoundRect(
            Color.White.copy(alpha = frameAlpha), frame.topLeft, frame.size, cr,
            style = Stroke(1.5.dp.toPx(), pathEffect = if (q == null) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null),
        )
        // Köşe işaretleri (kalın L)
        val l = 22.dp.toPx()
        val r = frame.inflate(2.dp.toPx())
        val marks = Path().apply {
            moveTo(r.left, r.top + l); lineTo(r.left, r.top); lineTo(r.left + l, r.top)
            moveTo(r.right - l, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + l)
            moveTo(r.right, r.bottom - l); lineTo(r.right, r.bottom); lineTo(r.right - l, r.bottom)
            moveTo(r.left + l, r.bottom); lineTo(r.left, r.bottom); lineTo(r.left, r.bottom - l)
        }
        drawPath(marks, Color.White.copy(alpha = 0.9f), style = Stroke(3.dp.toPx()))

        if (q != null) {
            val c = if (ready) DS.Colors.success else DS.Colors.cyan400
            val path = quadPath(listOf(p0, p1, p2, p3))
            drawPath(path, c.copy(alpha = 0.10f))
            drawPath(path, c, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
            for (p in listOf(p0, p1, p2, p3)) drawCircle(c, 4.5.dp.toPx(), p)
        }
    }
}

// MARK: Alt panel

@Composable
private fun XrayCameraBottomPanel(camera: XrayCameraController) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedContent(
            targetState = camera.hint,
            transitionSpec = { fadeIn(Motion.easeInOut(200)) togetherWith fadeOut(Motion.easeInOut(200)) },
            label = "hint",
        ) { hint ->
            Text(
                hint,
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                style = sys(16, FontWeight.SemiBold), color = Color.White,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            XrayCameraTip("sun.max", "Yansıma olmasın")
            XrayCameraTip("viewfinder", "Tam karşıdan")
            XrayCameraTip("hand.raised", "Sabit tutun")
        }

        val progress by animateFloatAsState(camera.readyProgress.toFloat(), Motion.linear(100), label = "ready")
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(78.dp)) {
                val sw = 4.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.9f), size.minDimension / 2, style = Stroke(sw))
                if (progress > 0f) {
                    val pw = 5.dp.toPx()
                    drawArc(
                        DS.Colors.success, -90f, 360f * progress, false,
                        style = Stroke(pw, cap = StrokeCap.Round),
                    )
                }
            }
            Box(
                Modifier
                    .size(64.dp)
                    .pressable(scale = 0.92f) { camera.capture() }
                    .clip(CircleShape)
                    .background(Color.White)
                    .semantics { contentDescription = "Röntgeni çek" },
                contentAlignment = Alignment.Center,
            ) {
                if (camera.isCapturing) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun XrayCameraTip(icon: String, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 11.dp, tint = Color.White.copy(alpha = 0.85f))
        Text(text, style = sys(11, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

@Composable
private fun XrayCameraMessage(title: String, detail: String, settings: Boolean = false) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        DfyIcon("camera", size = 36.dp, tint = DS.Colors.metal)
        Text(title, style = sys(18, FontWeight.SemiBold), color = Color.White)
        Text(detail, Modifier.padding(horizontal = 40.dp), style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        if (settings) {
            Text(
                "Ayarları aç",
                Modifier.plainClickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400,
            )
        }
    }
}

// MARK: - Denetleyici

class XrayCameraController(context: Context) {
    enum class Status { idle, running, denied, unavailable }

    var status by mutableStateOf(Status.idle)
        private set
    /** Algılanan röntgen (dik karede normalize, sol-üst orijin). */
    var quad by mutableStateOf<XrayQuad?>(null)
        private set
    var frameSize by mutableStateOf(Size(3f, 4f))
        private set
    var readyProgress by mutableDoubleStateOf(0.0)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var torchOn by mutableStateOf(false)
        private set
    var hasTorch by mutableStateOf(false)
        private set
    var autoCapture by mutableStateOf(true)

    private val engine = XrayCameraEngine(context)
    private var readySince by mutableStateOf<Double?>(null)
    private var lastQuad: XrayQuad? = null
    private var stableSince: Double? = null
    private var focusing by mutableStateOf(false)
    private var clipped by mutableDoubleStateOf(0.0)
    /** Son ~1,5 sn'nin netlik ölçüleri — "en net ana" yakın mıyız? */
    private val sharpnessHistory = ArrayList<Pair<Double, Double>>()
    private var sharpEnough by mutableStateOf(false)
    private var lastFocusPoint: Offset? = null
    private var lastFocusAt = 0.0
    private var onPhoto: ((ByteArray, XrayQuad?) -> Unit)? = null

    /** Telefonun fiziksel yönü (fotoğraf o yönde kaydedilir). */
    private var deviceRotation = Surface.ROTATION_0
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            deviceRotation = when (o) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }

    companion object {
        /** Hazır olduktan sonra otomatik çekime kadar bekleme (kullanıcı kadrajı görsün). */
        const val autoCaptureDelay = 0.8
        /** Röntgen kadraj genişliğinin en az bu kadarını kaplamalı (çözünürlük için). */
        const val minWidth = 0.5f
    }

    init {
        engine.onFrame = { detection, size, adjustingFocus -> apply(detection, size, adjustingFocus) }
    }

    val isReady: Boolean get() = readySince != null

    val hint: String
        get() {
            val quad = this.quad ?: return "Röntgeni çerçevenin içine alın"
            val b = quad.bounds
            if (b.left < 0.01f || b.right > 0.99f || b.top < 0.01f || b.bottom > 0.99f) return "Biraz uzaklaşın — röntgenin tamamı görünsün"
            if (b.width < minWidth) return "Biraz yaklaşın — röntgen kadrajı doldursun"
            if (clipped > 0.08) return "Parlama var — telefonu biraz eğin"
            if (focusing || !sharpEnough) return "Netleniyor…"
            if (readySince == null) return "Sabit tutun…"
            return if (autoCapture) "Harika, çekiliyor" else "Hazır — deklanşöre basın"
        }

    suspend fun start(owner: LifecycleOwner, previewView: PreviewView, onPhoto: (ByteArray, XrayQuad?) -> Unit) {
        this.onPhoto = onPhoto
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        val ok = engine.configure(owner, previewView)
        hasTorch = engine.hasTorch
        status = if (ok) Status.running else Status.unavailable
    }

    fun markDenied() { status = Status.denied }

    fun stop() {
        engine.stop()
        orientationListener.disable()
    }

    fun toggleTorch() {
        torchOn = !torchOn
        engine.setTorch(torchOn)
        Haptics.selection()
    }

    fun capture() {
        if (isCapturing || status != Status.running) return
        isCapturing = true
        Haptics.medium()
        // Telefon yan tutulduysa fotoğraf o yönde kaydedilir; canlı alan yalnız dik çekimde geçerli.
        val rotation = deviceRotation
        val hint = if (rotation == Surface.ROTATION_0) quad else null
        engine.capturePhoto(rotation) { data ->
            isCapturing = false
            if (data != null) onPhoto?.invoke(data, hint)
        }
    }

    /** Dik karedeki normalize noktayı aspect-fill önizleme koordinatına çevirir. */
    fun mapToView(p: Offset, size: Size): Offset {
        val fw = frameSize.width; val fh = frameSize.height
        val scale = max(size.width / fw, size.height / fh)
        val dw = fw * scale; val dh = fh * scale
        return Offset(p.x * dw + (size.width - dw) / 2, p.y * dh + (size.height - dh) / 2)
    }

    private fun now(): Double = System.nanoTime() / 1e9

    private fun apply(detection: XrayLiveDetection?, frameSize: Size, adjustingFocus: Boolean) {
        this.frameSize = frameSize
        focusing = adjustingFocus
        if (detection == null) {
            quad = null; lastQuad = null; stableSince = null; readySince = null; readyProgress = 0.0
            sharpnessHistory.clear(); sharpEnough = false; clipped = 0.0
            return
        }
        val detected = detection.quad
        // Titremeyi yumuşat (üstel ortalama).
        val q = quad
        val smoothed = if (q != null) {
            fun mix(a: Offset, b: Offset) = Offset(a.x * 0.55f + b.x * 0.45f, a.y * 0.55f + b.y * 0.45f)
            XrayQuad(mix(q.topLeft, detected.topLeft), mix(q.topRight, detected.topRight), mix(q.bottomRight, detected.bottomRight), mix(q.bottomLeft, detected.bottomLeft))
        } else detected
        quad = smoothed
        clipped = detection.clipped

        val now = now()
        val moved = lastQuad?.let { prev ->
            prev.points.zip(detected.points).maxOf { (a, b) -> hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) }
        } ?: 1.0
        lastQuad = detected
        stableSince = if (moved < 0.015) (stableSince ?: now) else null

        // Netlik: son 1,5 sn'nin en iyisine yakınsa (odak oturmuş, titreme yok).
        sharpnessHistory += now to detection.sharpness
        sharpnessHistory.removeAll { now - it.first > 1.5 }
        val best = sharpnessHistory.maxOfOrNull { it.second } ?: 0.0
        sharpEnough = sharpnessHistory.size >= 3 && detection.sharpness >= best * 0.88

        // Odak ve pozlama röntgene (ekranın koyu arayüzüne değil).
        val c = smoothed.center
        val lf = lastFocusPoint
        if (now - lastFocusAt > 1.2 && (lf == null || hypot((lf.x - c.x).toDouble(), (lf.y - c.y).toDouble()) > 0.06)) {
            lastFocusPoint = c
            lastFocusAt = now
            engine.focus(c, frameSize)
        }
        engine.adjustExposure(detection.clipped)

        val b = smoothed.bounds
        val goodFrame = b.width >= minWidth && b.left >= 0.01f && b.right <= 0.99f && b.top >= 0.01f && b.bottom <= 0.99f
        val steady = stableSince?.let { now - it > 0.35 } ?: false
        if (goodFrame && steady && !focusing && sharpEnough && clipped <= 0.08) {
            if (readySince == null) Haptics.soft()
            val since = readySince ?: now
            readySince = since
            readyProgress = min(1.0, (now - since) / autoCaptureDelay)
            if (autoCapture && readyProgress >= 1 && !isCapturing) capture()
        } else {
            readySince = null
            readyProgress = 0.0
        }
    }
}

/** CameraX işçisi: önizleme + yüksek kaliteli çekim + kare analizi (kendi iş parçacığında). */
class XrayCameraEngine(private val context: Context) {

    /** Algılama (ya da null), kare boyutu, odak hâlâ ayarlanıyor mu — ana iş parçacığında çağrılır. */
    var onFrame: ((XrayLiveDetection?, Size, Boolean) -> Unit)? = null
    var hasTorch = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var frameCounter = 0
    private var processed = 0
    @Volatile private var focusing = false
    private var exposureBias = 0f
    private var lastExposureChange = 0L
    @Volatile private var stopped = false

    suspend fun configure(owner: LifecycleOwner, previewView: PreviewView): Boolean {
        this.previewView = previewView
        val provider = suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({ cont.resume(runCatching { f.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
        } ?: return false
        this.provider = provider
        return runCatching {
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) return false
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)       // flaş filmde/ekranda parlama yapar
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        .build(),
                )
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(AndroidSize(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            analysis.setAnalyzer(analysisExecutor, object : ImageAnalysis.Analyzer {
                override fun analyze(image: ImageProxy) = analyzeFrame(image)
            })
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            camera = cam
            imageCapture = capture
            hasTorch = cam.cameraInfo.hasFlashUnit()
            true
        }.getOrDefault(false)
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (stopped) return
            frameCounter += 1
            if (frameCounter % 3 != 0) return
            processed += 1
            var bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            if (rot != 0) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { setRotate(rot.toFloat()) }, true)
            }
            // Bölütleme her üç işlenen karede bir — dikdörtgenler her karede.
            val detection = runCatching { XrayImageProcessor.detectLive(bmp, useSegmentation = processed % 3 == 0) }.getOrNull()
            val size = Size(bmp.width.toFloat(), bmp.height.toFloat())
            val adjusting = focusing
            main.post { if (!stopped) onFrame?.invoke(detection, size, adjusting) }
        } finally {
            image.close()
        }
    }

    fun stop() {
        stopped = true
        runCatching { camera?.cameraControl?.enableTorch(false) }
        runCatching { provider?.unbindAll() }
        analysisExecutor.shutdown()
    }

    fun setTorch(on: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    /** Odak + pozlama noktası (dik karede normalize nokta → önizleme koordinatı). */
    fun focus(p: Offset, frameSize: Size) {
        val pv = previewView ?: return
        val cam = camera ?: return
        val vw = pv.width.toFloat(); val vh = pv.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val scale = max(vw / frameSize.width, vh / frameSize.height)
        val dw = frameSize.width * scale; val dh = frameSize.height * scale
        val x = (p.x.coerceIn(0f, 1f)) * dw + (vw - dw) / 2
        val y = (p.y.coerceIn(0f, 1f)) * dh + (vh - dh) / 2
        runCatching {
            val point = pv.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
            focusing = true
            val f = cam.cameraControl.startFocusAndMetering(action)
            f.addListener({ focusing = false }, ContextCompat.getMainExecutor(context))
        }.onFailure { focusing = false }
    }

    /**
     * Röntgende beyaza doymuş alan çoksa pozlamayı adım adım kısar (mine dokusu, restorasyon
     * kenarları kaybolmasın); doyma kalkınca geri açar.
     */
    fun adjustExposure(clipped: Double) {
        val cam = camera ?: return
        val now = System.currentTimeMillis()
        if (now - lastExposureChange <= 500) return
        var target = exposureBias
        if (clipped > 0.03) target = max(-1.0f, exposureBias - 0.33f)
        else if (clipped < 0.005 && exposureBias < 0) target = min(0f, exposureBias + 0.33f)
        if (target == exposureBias) return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return
        val step = st.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val range = st.exposureCompensationRange
        val idx = (target / step).roundToInt().coerceIn(range.lower, range.upper)
        runCatching { cam.cameraControl.setExposureCompensationIndex(idx) }
        exposureBias = target.coerceIn(range.lower * step, range.upper * step)
        lastExposureChange = now
    }

    /** Yüksek kaliteli fotoğraf (JPEG). `rotation` = `Surface.ROTATION_*` (telefonun tutuluşu). */
    fun capturePhoto(rotation: Int, completion: (ByteArray?) -> Unit) {
        val ic = imageCapture
        if (ic == null || stopped) { completion(null); return }
        ic.targetRotation = rotation
        ic.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = runCatching {
                    val rot = image.imageInfo.rotationDegrees
                    if (image.format == ImageFormat.JPEG && rot == 0) {
                        val buf = image.planes[0].buffer
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    } else {
                        var bmp = image.toBitmap()
                        if (rot != 0) {
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { setRotate(rot.toFloat()) }, true)
                        }
                        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                    }
                }.getOrNull()
                image.close()
                completion(bytes)
            }

            override fun onError(exception: ImageCaptureException) {
                completion(null)
            }
        })
    }
}

@ComposePreview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun XrayCameraViewPreview() {
    PreviewEnv {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            XrayCameraMessage(
                "Kamera izni yok",
                "Ayarlar → Dentifay → Kamera iznini açın. Röntgeni galeriden ya da dosyadan da yükleyebilirsiniz.",
                settings = true,
            )
        }
    }
}
