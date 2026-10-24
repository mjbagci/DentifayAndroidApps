package com.dentifay.app.smile

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rehberli gülüş çekimi: canlı yüz takibiyle hekimi yönlendirir; yüz önden,
 * yakın, düz, dişler görünür ve ışık yeterliyken otomatik çeker. Böylece
 * ağız (maske) bölgesi her fotoğrafta güvenle seçilebilir.
 *
 * Tablet / katlanabilir yatay tutulduğunda (etkinlik yeniden kurulmaz): kamera kullanım durumları ekran dönüşüyle
 * hizalanır, rehber oval pencereye sığar ve yönerge + deklanşör sağdaki sütuna geçer (telefon dikey kilitli).
 */
@Composable
fun SmileCameraView(onCapture: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val camera = remember { SmileCameraController() }
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pencere = LocalPencere.current
    val yatay = pencere.yatay

    // Ekran dönüşünü izle: önizleme / analiz / çekim yönü (180° dönüş de yakalanır — yön değişmeden ekran döner).
    DisposableEffect(view) {
        val dm = view.context.getSystemService(DisplayManager::class.java)
        fun uygula() { view.display?.rotation?.let { camera.setTargetRotation(it) } }
        val dinleyici = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { uygula() }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        uygula()
        dm?.registerDisplayListener(dinleyici, Handler(Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(dinleyici) }
    }

    LaunchedEffect(Unit) {
        camera.start(onPhoto = { image ->
            scope.launch {
                flash.animateTo(0.85f, Motion.easeOut(80))
                flash.animateTo(0f, Motion.easeIn(300))
            }
            Haptics.success()
            onCapture(image)
            onDismiss()
        })
    }
    DisposableEffect(Unit) { onDispose { camera.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (camera.status) {
            SmileCameraController.Status.denied ->
                CameraMessage("Kamera izni yok", "Ayarlar → Dentifay → Kamera iznini açın.", settings = true)
            SmileCameraController.Status.unavailable ->
                CameraMessage("Kamera kullanılamıyor", "Bu cihazda kamera bulunamadı. Galeriden fotoğraf seçebilirsiniz.")
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                CameraPreview(camera.session, Modifier.fillMaxSize())
                val density = LocalDensity.current
                val size = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
                // Rehber ovalinin serbest alanı: üst çubuk + alt panel (dikey) ya da sağ sütun (yatay) hariç.
                val alan = with(density) {
                    if (yatay) Rect(0f, 64.dp.toPx(), size.width - YATAY_PANEL.toPx(), size.height - 16.dp.toPx())
                    else Rect(0f, 0f, size.width, size.height)
                }
                GuideLayer(camera, size, alan, yatay)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
        ) {
            CameraTopBar(camera, onDismiss)
            if (yatay) {
                // Yatay: yönerge, kontrol listesi ve deklanşör sağda dikey sütunda (önizleme alanı serbest kalır).
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    if (camera.status == SmileCameraController.Status.running) {
                        Box(Modifier.width(YATAY_PANEL).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            BottomPanel(camera, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), wrapChecklist = true)
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                if (camera.status == SmileCameraController.Status.running) BottomPanel(camera)
            }
        }

        // Flaş: değer yalnız katmanda okunur.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
    }
}

// MARK: - Üst bar

@Composable
private fun CameraTopBar(camera: SmileCameraController, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraCircleButton("xmark", "Kapat", onDismiss)
        Spacer(Modifier.weight(1f))
        val auto = camera.autoCapture
        Row(
            Modifier
                .plainClickable { camera.autoCapture = !camera.autoCapture; Haptics.selection() }
                .semantics { contentDescription = "Koşullar sağlanınca otomatik çekim" }
                .height(36.dp)
                .clip(CircleShape)
                .background(if (auto) DS.Colors.cyan400 else Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (auto) DS.Colors.onPrimary else Color.White
            DfyIcon(if (auto) "bolt.badge.automatic.fill" else "hand.tap.fill", size = 13.dp, tint = fg)
            Text(if (auto) "Otomatik" else "Manuel", style = sys(13, FontWeight.SemiBold), color = fg)
        }
        if (camera.position == SmileCameraController.Position.back) {
            Box(
                Modifier
                    .plainClickable { camera.toggleZoom() }
                    .semantics { contentDescription = "Yakınlaştırma ${if (camera.zoom2x) "2x" else "1x"}" }
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (camera.zoom2x) "2×" else "1×",
                    style = sys(14, FontWeight.Bold), color = if (camera.zoom2x) DS.Colors.cyan400 else Color.White,
                )
            }
        }
        CameraCircleButton("arrow.triangle.2.circlepath.camera", "Kamerayı çevir") { camera.flipCamera() }
    }
}

@Composable
private fun CameraCircleButton(icon: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .plainClickable(onClick = onClick)
            .semantics { contentDescription = label }
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) { DfyIcon(icon, size = 16.dp, tint = Color.White) }
}

// MARK: - Rehber katmanı

/** Yatay pencerede sağdaki yönerge + deklanşör sütununun genişliği. */
private val YATAY_PANEL = 300.dp

/**
 * Rehber ovali: dikeyde Swift'teki oranlar (genişliğin %72'si, üstten %16); yatayda serbest alanın yüksekliğine
 * sığdırılır ve ortalanır (oval pencereden taşmaz).
 */
private fun guideOval(size: Size, alan: Rect, yatay: Boolean): Rect {
    if (!yatay) {
        val w = size.width * 0.72f
        val h = w / 0.74f
        // Kısa pencerede (bölünmüş ekran) taşmasın: gerekirse yüksekliğe sığdır.
        return if (size.height * 0.16f + h <= size.height * 0.8f || h <= 0f) {
            Rect(Offset(size.width * 0.14f, size.height * 0.16f), Size(w, h))
        } else {
            val hh = size.height * 0.6f
            val ww = hh * 0.74f
            Rect(Offset((size.width - ww) / 2, size.height * 0.12f), Size(ww, hh))
        }
    }
    val h = min(alan.height * 0.92f, (alan.width * 0.8f) / 0.74f)
    val w = h * 0.74f
    return Rect(Offset(alan.left + (alan.width - w) / 2, alan.top + (alan.height - h) / 2), Size(w, h))
}

@Composable
private fun GuideLayer(camera: SmileCameraController, size: Size, alan: Rect, yatay: Boolean) {
    val ready = camera.guide.isReady
    val oval = remember(size, alan, yatay) { guideOval(size, alan, yatay) }
    val ovalColor by animateColorAsState(if (ready) DS.Colors.tertiaryContainer else Color.White.copy(alpha = 0.8f), Motion.easeInOut(250), label = "ovalColor")
    val ovalWidth by animateDpAsState(if (ready) 4.dp else 2.dp, Motion.easeInOut(250), label = "ovalWidth")
    val dashAlpha by animateFloatAsState(if (ready) 0f else 1f, Motion.easeInOut(250), label = "ovalDash")

    // Canlı "yapay zekaya gidecek alan" çerçevesi (gönderilen kırpımla aynı)
    val mouth = camera.guide.mouthRect
    val target = mouth?.let { sentRect(map(it, size, camera.frameSize)) }
    // Canlı dikdörtgen yalnız çizim/yerleşim aşamasında okunur (her animasyon karesinde yeniden kompozisyon yok).
    val animated = animateRectAsState(target ?: Rect.Zero, tween(100, easing = LinearEasing), label = "mouthRect")
    val teethOk = camera.guide.passed.contains(SmileCaptureGuide.Check.teeth)

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Oval dışını karart
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, this@Canvas.size))
                addOval(oval)
            }
            drawPath(dim, Color.Black.copy(alpha = 0.42f))
            val w = ovalWidth.toPx()
            if (dashAlpha > 0.01f) {
                drawOval(
                    ovalColor.copy(alpha = ovalColor.alpha * dashAlpha), oval.topLeft, oval.size,
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                )
            }
            if (dashAlpha < 0.99f) {
                drawOval(ovalColor.copy(alpha = ovalColor.alpha * (1 - dashAlpha)), oval.topLeft, oval.size, style = Stroke(w))
            }

            if (target != null) {
                val color = if (teethOk) DS.Colors.cyan400 else DS.Colors.orange400
                val corner = CornerRadius(10.dp.toPx())
                val a = animated.value
                drawRoundRect(DS.Colors.cyan400.copy(alpha = 0.22f), a.topLeft, a.size, corner, style = Stroke(6.dp.toPx()))
                drawRoundRect(color, a.topLeft, a.size, corner, style = Stroke(2.dp.toPx()))
            }
        }
        if (target != null) {
            Text(
                "GÖNDERİLEN ALAN",
                Modifier
                    .layout { m, c ->
                        val a = animated.value
                        val cx = a.center.x
                        val cy = a.top - 12.dp.toPx()
                        val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                        layout(c.maxWidth, c.maxHeight) { p.place((cx - p.width / 2f).roundToInt(), (cy - p.height / 2f).roundToInt()) }
                    }
                    .clip(CircleShape)
                    .background(DS.Colors.cyan400)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = sys(9, FontWeight.Bold, tracking = 1), color = DS.Colors.onPrimary,
            )
        }
    }
}

/** Dudak çerçevesinden gönderilecek 3:2 ağız kırpımı (`SmileImageProcessor.detectMouth` ile aynı oranlar). */
private fun sentRect(lips: Rect): Rect {
    val aspect = SmileImageProcessor.aspect
    val w = max(lips.width * 1.7f, lips.height * 1.9f * aspect)
    val h = w / aspect
    return Rect(lips.center.x - w / 2, lips.center.y - h / 2, lips.center.x + w / 2, lips.center.y + h / 2)
}

/** Dik karedeki normalize dikdörtgeni, aspectFill önizlemedeki view koordinatına çevirir. */
private fun map(r: Rect, size: Size, frame: Size): Rect {
    val fw = frame.width; val fh = frame.height
    val scale = max(size.width / fw, size.height / fh)
    val dw = fw * scale; val dh = fh * scale
    val ox = (size.width - dw) / 2; val oy = (size.height - dh) / 2
    return Rect(r.left * dw + ox, r.top * dh + oy, r.right * dw + ox, r.bottom * dh + oy)
}

// MARK: - Alt panel

@Composable
private fun BottomPanel(camera: SmileCameraController, modifier: Modifier = Modifier, wrapChecklist: Boolean = false) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Crossfade(camera.guide.hint, animationSpec = Motion.easeInOut(200), label = "hint") { hint ->
            Text(
                hint,
                Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                style = sys(17, FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center,
            )
        }

        Checklist(camera, wrap = wrapChecklist)

        val progress by animateFloatAsState(camera.readyProgress.toFloat(), tween(100, easing = LinearEasing), label = "ready")
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val ring = 4.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.9f), radius = size.minDimension / 2 - ring / 2, style = Stroke(ring))
                if (progress > 0f) {
                    val sw = 5.dp.toPx()
                    drawArc(
                        DS.Colors.tertiaryContainer, -90f, 360f * progress, false,
                        topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                        style = Stroke(sw, cap = StrokeCap.Round),
                    )
                }
            }
            Box(
                Modifier
                    .pressable(scale = 0.92f) { camera.capture() }
                    .semantics { contentDescription = "Fotoğraf çek" }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (camera.guide.isReady) DS.Colors.tertiaryContainer else Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (camera.isCapturing) SmileSpinner(color = Color.Black, size = 20.dp)
            }
        }
    }
}

@Composable
private fun CheckChip(camera: SmileCameraController, c: SmileCaptureGuide.Check) {
    val ok = camera.guide.passed.contains(c)
    val fg by animateColorAsState(if (ok) DS.Colors.tertiaryContainer else Color.White.copy(alpha = 0.7f), Motion.easeInOut(200), label = "check")
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(if (ok) "checkmark.circle.fill" else "circle", size = 10.dp, tint = fg)
        Text(c.title, style = sys(10, FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** `wrap`: dar yan sütunda (yatay tablet) çipler satır satır kayar; dikeyde tek satır, sığmazsa %80'e küçülür. */
@Composable
private fun Checklist(camera: SmileCameraController, wrap: Boolean = false) {
    if (wrap) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmileCaptureGuide.Check.entries.forEach { c -> CheckChip(camera, c) }
        }
        return
    }
    // `.minimumScaleFactor(0.8)`: sığmazsa satır en fazla %80'e küçülür.
    Layout(
        content = {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmileCaptureGuide.Check.entries.forEach { c -> CheckChip(camera, c) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val p = measurables.first().measure(Constraints())
        val maxW = constraints.maxWidth
        val s = min(1f, max(0.8f, maxW.toFloat() / max(p.width, 1)))
        val h = (p.height * s).roundToInt()
        layout(maxW, h) {
            p.placeWithLayer(((maxW - p.width) / 2f).roundToInt(), 0) {
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}

@Composable
private fun CameraMessage(title: String, detail: String, settings: Boolean = false) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        DfyIcon("camera.fill", size = 40.dp, tint = DS.Colors.outline)
        Text(title, style = sys(18, FontWeight.SemiBold), color = Color.White)
        Text(detail, Modifier.padding(horizontal = 40.dp), style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        if (settings) {
            Text(
                "Ayarları aç",
                Modifier.plainClickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400,
            )
        }
    }
}

/** CameraX `PreviewView` sarmalayıcısı (aspectFill, dik). Gülüş ve röntgen kamerası ortak kullanır. */
@Composable
fun CameraPreview(session: Preview, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                session.setSurfaceProvider(surfaceProvider)
            }
        },
        modifier = modifier,
    )
}

@ComposePreview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileCameraMessagePreview() {
    PreviewEnv {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraMessage("Kamera izni yok", "Ayarlar → Dentifay → Kamera iznini açın.", settings = true)
        }
    }
}

@ComposePreview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileCameraViewPreview() {
    PreviewEnv { SmileCameraView(onCapture = {}, onDismiss = {}) }
}

@ComposePreview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun SmileCameraViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { SmileCameraView(onCapture = {}, onDismiss = {}) }
}
