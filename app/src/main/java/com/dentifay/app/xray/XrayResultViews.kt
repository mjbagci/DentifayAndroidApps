package com.dentifay.app.xray

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Yardımcılar

/** Sistemde animasyonlar kapalı mı (iOS `accessibilityReduceMotion` karşılığı)? */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Çocuğu kısıtsız ölçüp (x, y) kaymasıyla yerleştirir; yerleşimde yer kaplamaz (SwiftUI `.fixedSize().offset`). */
internal fun Modifier.xrayFloatAt(x: Dp, y: Dp): Modifier = layout { m, _ ->
    val p = m.measure(Constraints())
    layout(0, 0) { p.place(x.roundToPx(), y.roundToPx()) }
}

/** Çocuğu verilen px noktasına ORTALAYARAK yerleştirir (SwiftUI `.position`). */
internal fun Modifier.xrayCenterAt(x: Float, y: Float): Modifier = layout { m, _ ->
    val p = m.measure(Constraints())
    layout(0, 0) { p.place((x - p.width / 2f).roundToInt(), (y - p.height / 2f).roundToInt()) }
}

/** `xrayCenterAt`'in sağlayıcılı hâli: konum yerleşim evresinde okunur (animasyon / sürükleme yeniden kurmaz). */
internal fun Modifier.xrayCenterAt(position: () -> Offset): Modifier = layout { m, _ ->
    val p = m.measure(Constraints())
    layout(0, 0) {
        val c = position()
        p.place((c.x - p.width / 2f).roundToInt(), (c.y - p.height / 2f).roundToInt())
    }
}

/** Yuvarlak köşeli dikdörtgen çizgisi (+ isteğe bağlı dolgu, kesikli çizgi) — SwiftUI `RoundedRectangle.stroke`. */
internal fun DrawScope.xrayRoundRect(
    stroke: Color?, lineWidth: Dp, radius: Dp, dash: List<Float>? = null, fill: Color? = null,
    topLeft: Offset = Offset.Zero, size: Size = this.size,
) {
    val cr = CornerRadius(radius.toPx())
    if (fill != null) drawRoundRect(fill, topLeft, size, cr)
    if (stroke != null) {
        val effect = dash?.let { d -> PathEffect.dashPathEffect(d.map { it * density }.toFloatArray(), 0f) }
        drawRoundRect(stroke, topLeft, size, cr, style = Stroke(lineWidth.toPx(), pathEffect = effect))
    }
}

// MARK: - Sonuç tuvali

/**
 * Tuvalin yakınlaştırma / kaydırma / karşılaştırma durumu. Çağıran tutar: tembel listede tuval görünümden çıkıp geri
 * gelince (telefon) ya da pencere yatay ↔ dikey değişince (tablet) yakınlaştırma kaybolmasın.
 */
@Stable
class XrayCanvasState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var split by mutableFloatStateOf(0.5f)
}

/**
 * Röntgen + diş kutuları. Web stüdyosuyla aynı çizim dili: öneri kutusu tam çizgi
 * (güven < %65 ise kesikli), yalnız mevcut restorasyon dolgulu, sağlıklı diş soluk kesikli,
 * FDI rozeti sol üstte, öneri etiketi altta. Kıstırarak yakınlaştırma, sürükleyerek gezinme,
 * çift dokunuşla 1×/2,5×. `compare` açıkken ayraç solunda ham görüntü.
 *
 * Boyut: görselin en-boy oranında, verilen kısıtlara SIĞAR (telefonda `Modifier.fillMaxWidth()` ile tam genişlik; tablette
 * bölmenin içinde hem genişliğe hem yüksekliğe sığar). Yakınlaştırma ve kaydırma yalnız katman/çizim evresinde okunur.
 */
@Composable
fun XrayResultCanvas(
    image: Bitmap,
    findings: List<XrayToothFinding>,
    selectedID: String?,
    onSelectedIDChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    anatomy: List<Pair<XrayLayerItem, Rect>> = emptyList(),
    showAnatomy: Boolean = false,
    compare: Boolean = false,
    /** İlk gösterimde bulgulara sırayla kilitlenen nişangâh (web XrayScanOverlay). */
    playLockOn: Boolean = false,
    state: XrayCanvasState = remember { XrayCanvasState() },
) {
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val bmp = remember(image) { image.asImageBitmap() }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    var reticleVisible by remember { mutableStateOf(false) }
    val reticle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val reticleAlpha = remember { Animatable(1f) }
    var reticleLocked by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }

    // Yakınlaştırma her karede değişir; ekranı yalnız eşik/basamak değişince yeniden kurar.
    val zoomedIn by remember(state) { derivedStateOf { state.scale >= 1.8f } }
    val textScale by remember(state) { derivedStateOf { (max(1f, state.scale * 0.7f) * 4).roundToInt() / 4f } }

    val aspect = image.width.toFloat() / max(image.height, 1)
    val shape = RoundedCornerShape(14.dp)

    fun animateTo(targetScale: Float, targetOffset: Offset, ms: Int) {
        val fromS = state.scale; val fromO = state.offset
        scope.launch {
            animate(0f, 1f, animationSpec = tween(ms, easing = Motion.EaseOut)) { p, _ ->
                state.scale = fromS + (targetScale - fromS) * p
                state.offset = Offset(fromO.x + (targetOffset.x - fromO.x) * p, fromO.y + (targetOffset.y - fromO.y) * p)
            }
        }
    }

    fun resetZoom(ms: Int) = animateTo(1f, Offset.Zero, ms)

    Box(
        modifier
            .aspectRatio(aspect)
            .clip(shape)
            .border(1.dp, DS.Colors.hairlineStrong, shape),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            fun rect(r: Rect) = Rect(r.left * w, r.top * h, r.right * w, r.bottom * h)
            fun clamp(o: Offset): Offset {
                val mx = w * (state.scale - 1) / 2; val my = h * (state.scale - 1) / 2
                return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
            }
            val density = LocalDensity.current
            val slop = with(density) { 8.dp.toPx() }

            // Pencere boyutu değişince (döndürme, bölünmüş ekran) kaydırma yeni sınırlara çekilir.
            LaunchedEffect(w, h) { state.offset = clamp(state.offset) }

            // Kilitlenme animasyonu (web XrayScanOverlay "lock" evresi)
            LaunchedEffect(Unit) {
                val quiet = findings.filter { it.kind != XrayToothFinding.Kind.finding }.map { it.id }
                val targets = findings.filter { it.kind == XrayToothFinding.Kind.finding }.sortedBy { it.rect.center.x }
                if (!playLockOn || reduceMotion || targets.isEmpty()) {
                    revealed = findings.map { it.id }.toSet(); return@LaunchedEffect
                }
                locking = true
                revealed = quiet.toSet()
                reticle.snapTo(Offset(w / 2, h * 0.08f))
                reticleAlpha.snapTo(1f)
                reticleVisible = true
                val step = max(0.26, 2.4 / targets.size)
                for (t in targets) {
                    val r = rect(t.rect)
                    reticleLocked = false
                    reticle.animateTo(r.center, tween((step * 620).toInt(), easing = Motion.EaseOut))
                    reticleLocked = true
                    revealed = revealed + t.id
                    Haptics.selection()
                    delay((step * 380).toLong())
                }
                launch {
                    reticleAlpha.animateTo(0f, tween(300, easing = Motion.EaseOut))
                    reticleVisible = false
                }
                revealed = findings.map { it.id }.toSet()
                locking = false
            }
            val ids = remember(findings) { findings.map { it.id } }
            var firstIds by remember { mutableStateOf(true) }
            LaunchedEffect(ids) {
                if (firstIds) { firstIds = false; return@LaunchedEffect }
                if (!locking) revealed = ids.toSet()
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(w, h) {
                        // Kıstırma (iki parmak) her zaman; tek parmakla gezinme yalnız yakınken —
                        // 1×'te dikey kaydırma üst kaydırmaya kalır.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pinched = false
                            var panning = false
                            var travel = Offset.Zero
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                if (pressed >= 2) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) {
                                        pinched = true
                                        state.scale = (state.scale * z).coerceIn(1f, 5f)
                                        state.offset = clamp(state.offset)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (state.scale > 1.01f) {
                                    val pan = event.calculatePan()
                                    travel += pan
                                    if (!panning && travel.getDistance() >= slop) panning = true
                                    if (panning) {
                                        state.offset = clamp(state.offset + pan)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                            if (pinched) {
                                state.offset = clamp(state.offset)
                                if (state.scale <= 1.01f) resetZoom(200)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            Haptics.selection()
                            if (state.scale > 1.01f) resetZoom(250) else animateTo(2.5f, state.offset, 250)
                        })
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = state.scale; scaleY = state.scale
                            translationX = state.offset.x; translationY = state.offset.y
                        },
                ) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
                    // Katman
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (compare) Modifier.drawWithContent {
                                    clipRect(left = size.width * state.split) { this@drawWithContent.drawContent() }
                                } else Modifier,
                            ),
                    ) {
                        if (showAnatomy) {
                            Canvas(Modifier.fillMaxSize()) {
                                for ((item, nr) in anatomy) {
                                    val r = Rect(nr.left * size.width, nr.top * size.height, nr.right * size.width, nr.bottom * size.height)
                                    val c = XrayCatalog.color(item.cls, item.color)
                                    xrayRoundRect(c, 1.dp, 4.dp, listOf(4f, 3f), c.copy(alpha = 0.05f), Offset(r.left, r.top), Size(r.width, r.height))
                                }
                            }
                        }
                        for (f in findings) {
                            XrayToothBox(
                                f = f, r = rect(f.rect), selected = selectedID == f.id,
                                dimmed = selectedID != null && selectedID != f.id,
                                revealed = f.id in revealed, lineScale = { state.scale },
                                textScale = textScale, zoomedIn = zoomedIn,
                                onTap = {
                                    Haptics.selection()
                                    onSelectedIDChange(if (selectedID == f.id) null else f.id)
                                },
                            )
                        }
                        if (reticleVisible) {
                            XrayReticle(reticleLocked, Modifier.xrayCenterAt { reticle.value }.graphicsLayer { alpha = reticleAlpha.value })
                        }
                    }
                }
            }

            if (compare) {
                XrayCompareHandle({ state.split }, w, h) { state.split = it }
            }
        }

        // Yakınlaştırma düğmeleri
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .width(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, DS.Colors.hairlineStrong, RoundedCornerShape(10.dp)),
        ) {
            XrayZoomButton("plus", "Yakınlaştır") {
                animateTo(min(5f, state.scale + 0.75f), state.offset, 200)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(DS.Colors.hairlineStrong))
            XrayZoomButton("minus", "Uzaklaştır") {
                val t = max(1f, state.scale - 0.75f)
                if (t <= 1.01f) resetZoom(200) else animateTo(t, state.offset, 200)
            }
        }
    }
}

@Composable
private fun XrayToothBox(
    f: XrayToothFinding, r: Rect, selected: Boolean, dimmed: Boolean, revealed: Boolean,
    lineScale: () -> Float, textScale: Float, zoomedIn: Boolean, onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val color = f.color
    val baseLine = if (selected) 2.4f else 1.6f
    val revealAlpha by animateFloatAsState(if (revealed) 1f else 0f, Motion.easeOut(250), label = "reveal")
    val revealScale by animateFloatAsState(if (revealed) 1f else 1.08f, Motion.easeOut(250), label = "revealScale")
    val dimAlpha by animateFloatAsState(if (dimmed) 0.35f else 1f, Motion.easeOut(200), label = "dim")
    val fillA by animateFloatAsState(if (selected) 0.16f else 0.06f, Motion.easeOut(200), label = "fill")
    val boxW = with(density) { max(r.width, 6.dp.toPx()).toDp() }
    val boxH = with(density) { max(r.height, 6.dp.toPx()).toDp() }
    val rh = with(density) { r.height.toDp() }
    val slate = hex("94a3b8")
    val top = f.top

    Box(
        Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(boxW, boxH)
            .graphicsLayer {
                alpha = revealAlpha * dimAlpha
                scaleX = revealScale; scaleY = revealScale
            }
            .semantics {
                contentDescription = "Diş ${f.fdi}" + (top?.let { ", ${it.title} yüzde ${(it.probability * 100).toInt()}" } ?: "")
            }
            .plainClickable(onClick = onTap)
            .drawBehind {
                // Çizgi kalınlığı yakınlaştırmayla incelir — çizim evresinde okunur.
                val lw = (baseLine / max(1f, lineScale() * 0.8f)).dp
                when (f.kind) {
                    XrayToothFinding.Kind.finding ->
                        xrayRoundRect(color, lw, 3.dp, if (f.isHighConfidence) null else listOf(5f, 3f), color.copy(alpha = fillA))
                    XrayToothFinding.Kind.existing ->
                        xrayRoundRect(color.copy(alpha = 0.9f), lw, 3.dp, null, color.copy(alpha = 0.13f))
                    XrayToothFinding.Kind.missing ->
                        xrayRoundRect(slate.copy(alpha = 0.8f), lw, 3.dp, listOf(3f, 3f))
                    XrayToothFinding.Kind.healthy ->
                        xrayRoundRect(slate.copy(alpha = if (selected) 0.9f else 0.42f), lw * 0.8f, 3.dp, listOf(3f, 3f))
                }
            },
    ) {
        // Dar ekranda 32 rozet üst üste biner: sağlıklı dişin numarası yakınlaştırınca
        // (ya da seçilince) görünür; bulgu etiketi seçili dişte ve yakınlaştırınca.
        if (f.kind != XrayToothFinding.Kind.healthy || selected || zoomedIn) {
            val bg = if (f.kind == XrayToothFinding.Kind.healthy || f.kind == XrayToothFinding.Kind.missing) hex("0f172a").copy(alpha = 0.85f) else color
            Text(
                "${f.fdi}",
                Modifier
                    .xrayFloatAt((-3).dp, (-7).dp)
                    .background(bg, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                style = sys(8.5f / textScale, FontWeight.Bold), color = Color.White, maxLines = 1, softWrap = false,
            )
        }
        val text = f.badgeText
        if ((selected || (zoomedIn && f.kind == XrayToothFinding.Kind.finding)) && f.kind != XrayToothFinding.Kind.healthy && text != null) {
            Text(
                text,
                Modifier
                    .xrayFloatAt((-2).dp, rh + 2.dp)
                    .background(color.copy(alpha = if (f.kind == XrayToothFinding.Kind.missing) 0.6f else 0.92f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                style = sys(7.5f / textScale, FontWeight.SemiBold), color = Color.White, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun XrayReticle(locked: Boolean, modifier: Modifier) {
    val c by animateColorAsState(if (locked) mutedHex("ff6b61") else DS.Colors.cyan400, Motion.easeOut(150), label = "retColor")
    val s by animateDpAsState(if (locked) 24.dp else 32.dp, Motion.easeOut(150), label = "retSize")
    val outer by animateFloatAsState(if (locked) 1f else 0f, Motion.easeOut(150), label = "retOuter")
    Canvas(modifier.size(48.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val sp = s.toPx()
        xrayRoundRect(c.copy(alpha = 0.9f), 1.dp, 3.dp, topLeft = center - Offset(sp / 2, sp / 2), size = Size(sp, sp))
        drawCircle(c, 1.5.dp.toPx(), center)
        if (outer > 0f) {
            val op = sp + 12.dp.toPx()
            xrayRoundRect(c.copy(alpha = 0.5f * outer), 1.dp, 4.dp, topLeft = center - Offset(op / 2, op / 2), size = Size(op, op))
        }
    }
}

@Composable
private fun XrayZoomButton(icon: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp, 30.dp)
            .plainClickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        DfyIcon(icon, size = 12.dp, tint = Color.White.copy(alpha = 0.85f))
    }
}

// MARK: Karşılaştırma ayracı

/** Ayraç: konumu sağlayıcıdan çizim/yerleşim evresinde okunur (sürüklerken tuval yeniden kurulmaz). */
@Composable
private fun XrayCompareHandle(split: () -> Float, w: Float, h: Float, onSplit: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(w) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onSplit((down.position.x / max(w, 1f)).coerceIn(0.05f, 0.95f))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        onSplit((ch.position.x / max(w, 1f)).coerceIn(0.05f, 0.95f))
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split()
            val lw = 2.dp.toPx()
            drawRect(
                Brush.verticalGradient(listOf(DS.Colors.cyan400, DS.Colors.brandViolet), startY = 0f, endY = size.height),
                topLeft = Offset(x - lw / 2, 0f), size = Size(lw, size.height),
            )
            val r = 17.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.6f), r, Offset(x, size.height / 2))
            drawCircle(Color.White.copy(alpha = 0.85f), r - 0.75.dp.toPx(), Offset(x, size.height / 2), style = Stroke(1.5.dp.toPx()))
        }
        val density = LocalDensity.current
        DfyIcon("arrow.left.and.right", Modifier.xrayCenterAt { Offset(w * split(), h / 2) }, size = 12.dp, tint = Color.White)
        with(density) {
            XrayCompareTag("ÖNCE", Modifier.xrayCenterAt(28.dp.toPx(), h - 16.dp.toPx()))
            XrayCompareTag("BULGULAR", Modifier.xrayCenterAt(w - 42.dp.toPx(), h - 16.dp.toPx()))
        }
    }
}

@Composable
private fun XrayCompareTag(text: String, modifier: Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = sys(9, FontWeight.Bold, tracking = 0.8), color = Color.White, maxLines = 1,
    )
}

// MARK: - Tarama göstergesi (analiz sürerken)

private val scanPath: List<Offset> = listOf(
    8 to 26, 24 to 16, 42 to 20, 58 to 16, 76 to 24, 92 to 34, 78 to 56, 58 to 64, 40 to 62, 22 to 56, 10 to 42,
).map { Offset(it.first / 100f, it.second / 100f) }

/**
 * Web `XrayScanOverlay` arama evresi: nişangâh çene kavsini izler. Yalnız görsel ipucu;
 * analiz sunucuda sürer. Animasyonlar kapalıysa (sistem ayarı) durağan kalır; yalnız ekrandayken döner.
 */
@Composable
fun XrayScanningOverlay(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(560)
            index += 1
        }
    }
    val target = scanPath[index % scanPath.size]
    val p by animateOffsetAsState(target, if (reduceMotion) tween(0) else Motion.easeInOut(520), label = "scan")
    Canvas(modifier.fillMaxSize()) {
        val pt = Offset(p.x * size.width, p.y * size.height)
        val c = DS.Colors.cyan400.copy(alpha = 0.5f)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, c, Color.Transparent), startY = 0f, endY = size.height),
            topLeft = Offset(pt.x - 0.5.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height),
        )
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, c, Color.Transparent), startX = 0f, endX = size.width),
            topLeft = Offset(0f, pt.y - 0.5.dp.toPx()), size = Size(size.width, 1.dp.toPx()),
        )
        val s = 32.dp.toPx()
        xrayRoundRect(DS.Colors.cyan400.copy(alpha = 0.9f), 1.dp, 3.dp, topLeft = pt - Offset(s / 2, s / 2), size = Size(s, s))
    }
}

// MARK: - Duyarlılık seçici (web segment kontrolü)

@Composable
fun XraySensitivityPicker(value: XraySensitivity, onValueChange: (XraySensitivity) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, DS.Colors.hairline, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (s in XraySensitivity.entries) {
            val on = s == value
            val a by animateFloatAsState(if (on) 1f else 0f, Motion.easeOut(180), label = "seg")
            val fg by animateColorAsState(if (on) Color.White else DS.Colors.onSurfaceVariant, Motion.easeOut(180), label = "segFg")
            val segShape = RoundedCornerShape(9.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(segShape)
                    .drawBehind {
                        // Seçim geçişi yalnız çizimi yeniler.
                        drawRoundRect(Color.White.copy(alpha = 0.08f * a), cornerRadius = CornerRadius(9.dp.toPx()))
                        drawRoundRect(
                            DS.Colors.cyan400.copy(alpha = 0.45f * a), cornerRadius = CornerRadius(9.dp.toPx()),
                            topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                            size = Size(size.width - 1.dp.toPx(), size.height - 1.dp.toPx()),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                    .plainClickable {
                        Haptics.selection()
                        onValueChange(s)
                    }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    s.title, style = sys(13, if (on) FontWeight.SemiBold else FontWeight.Medium), color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "≥%${(s.rawValue * 100).toInt()}", Modifier.graphicsLayer { alpha = 0.7f },
                    style = sys(10, FontWeight.Medium), color = fg, maxLines = 1,
                )
            }
        }
    }
}

// MARK: - Özet çipleri

@Composable
fun XraySummaryPill(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text("$value", style = sys(13, FontWeight.Bold), color = Color.White, maxLines = 1)
        Text(label, style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1)
    }
}

// MARK: - Diş kartı (seçili diş / bulgu listesi satırı)

@Composable
fun XrayToothRow(finding: XrayToothFinding, modifier: Modifier = Modifier, selected: Boolean = false, compact: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = if (selected) 0.06f else 0.025f))
            .border(1.dp, if (selected) finding.color.copy(alpha = 0.6f) else DS.Colors.hairline, shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (finding.kind == XrayToothFinding.Kind.healthy) Color.White.copy(alpha = 0.06f) else finding.color.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${finding.fdi}", style = sys(14, FontWeight.Bold), color = Color.White, maxLines = 1)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Diş ${finding.fdi}", style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1)
                Text(
                    finding.quadrant, Modifier.weight(1f, fill = false),
                    style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            if (finding.recs.isEmpty() && finding.existing.isEmpty() && finding.observations.isEmpty()) {
                val tint = if (finding.isSynthetic) DS.Colors.metal else DS.Colors.success
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon(if (finding.isSynthetic) "square.dashed" else "checkmark.seal", size = 13.dp, tint = tint)
                    Text(if (finding.isSynthetic) "Eksik diş alanı" else "Sağlıklı — bulgu yok", style = sys(13, FontWeight.Medium), color = tint)
                }
            }

            for (rec in finding.recs.take(if (compact) 2 else 6)) {
                val rc = XrayCatalog.color(rec.cls, rec.color)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(rc, CircleShape))
                        // Dar bölmede / büyük yazıda başlık kırpılır, yüzde rozeti ve "olası" hep görünür.
                        Text(
                            rec.title, Modifier.weight(1f, fill = false),
                            style = sys(14, FontWeight.SemiBold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "%${(rec.probability * 100).roundToInt()}",
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(rc.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            style = sys(11, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace), color = rc, maxLines = 1,
                        )
                        if (rec.probability < 0.65) {
                            Text("olası", style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    val reason = rec.clinicalReason
                    if (!compact && !reason.isNullOrEmpty()) {
                        Text(reason, Modifier.padding(start = 13.dp), style = sys(12), color = DS.Colors.onSurfaceVariant)
                    }
                }
            }

            if (finding.existing.isNotEmpty() || finding.observations.isNotEmpty()) {
                FlowChips(
                    finding.existing.map { it.title to XrayCatalog.color(it.cls, it.color) } +
                        finding.observations.map { it.title to XrayCatalog.color(it.cls, it.color) },
                )
            }
        }
    }
}

/** Satır kaydıran küçük etiketler: sığarsa yan yana, sığmazsa alt alta (SwiftUI `ViewThatFits`). */
@Composable
fun FlowChips(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Layout(
        content = {
            for ((text, c) in items) {
                Text(
                    text,
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.copy(alpha = 0.12f))
                        .border(1.dp, c.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = sys(11, FontWeight.SemiBold), color = c, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val spacing = 6.dp.roundToPx()
        val ps = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val rowWidth = ps.sumOf { it.width } + spacing * max(0, ps.size - 1)
        if (rowWidth <= constraints.maxWidth) {
            val hh = ps.maxOfOrNull { it.height } ?: 0
            layout(rowWidth, hh) {
                var x = 0
                ps.forEach { it.place(x, (hh - it.height) / 2); x += it.width + spacing }
            }
        } else {
            val ww = ps.maxOfOrNull { it.width } ?: 0
            val hh = ps.sumOf { it.height } + spacing * max(0, ps.size - 1)
            layout(ww, hh) {
                var y = 0
                ps.forEach { it.place(0, y); y += it.height + spacing }
            }
        }
    }
}

/** Sonuçtaki renk açıklaması (yalnız görünen sınıflar). */
@Composable
fun XrayLegend(findings: List<XrayToothFinding>, modifier: Modifier = Modifier) {
    val recClasses = remember(findings) { findings.flatMap { f -> f.recs.map { it.cls } }.distinct() }
    val existing = remember(findings) { findings.flatMap { f -> f.existing.map { it.cls } }.distinct() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recClasses.isNotEmpty()) XrayLegendRow("Öneriler", recClasses)
        if (existing.isNotEmpty()) XrayLegendRow("Mevcut", existing)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            XrayLegendSample(dashed = false, text = "Yüksek güven")
            XrayLegendSample(dashed = true, text = "Olası (< %65)")
        }
    }
}

@Composable
private fun XrayLegendRow(title: String, classes: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = sys(11, FontWeight.Bold, tracking = 0.8), color = DS.Colors.onSurfaceVariant)
        FlowChips(classes.map { XrayCatalog.label(it) to XrayCatalog.color(it) })
    }
}

@Composable
private fun XrayLegendSample(dashed: Boolean, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, 10.dp)) {
            xrayRoundRect(DS.Colors.metal, 1.4.dp, 2.dp, if (dashed) listOf(3f, 2f) else null)
        }
        Text(text, style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1)
    }
}

// MARK: - Önizleme

internal object XrayPreviewData {
    val result = XrayResult(
        imageSize = listOf(1000.0, 500.0),
        teeth = mapOf(
            "t46" to XrayTooth(
                fdi = 46, box = listOf(280.0, 215.0, 353.0, 318.0),
                highRecs = listOf(XrayRec(cls = "KANAL_TEDAVISI", probability = 0.92, clinicalReason = "Periapikal radyolüsens alan")),
            ),
            "t21" to XrayTooth(
                fdi = 21, box = listOf(494.0, 100.0, 542.0, 210.0),
                midRecs = listOf(XrayRec(cls = "DOLGU", probability = 0.58)),
                existingTreatments = listOf(XrayMark(cls = "EXISTING_KURON", probability = 0.8)),
            ),
            "t11" to XrayTooth(fdi = 11, box = listOf(445.0, 100.0, 492.0, 210.0), existingTreatments = listOf(XrayMark(cls = "EXISTING_DOLGU", probability = 0.9))),
            "t31" to XrayTooth(fdi = 31, box = listOf(480.0, 290.0, 520.0, 380.0)),
            "gap_38" to XrayTooth(fdi = 38, box = listOf(755.0, 190.0, 827.0, 273.0), gapInferred = true),
        ),
    )

    /** Önizlemelerde röntgen yerine koyu gri zemin. */
    fun image(): Bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
}

@Composable
private fun XrayResultViewsOrnek() {
    val bmp = remember { XrayPreviewData.image() }
    var sel by remember { mutableStateOf<String?>(null) }
    var sens by remember { mutableStateOf(XraySensitivity.low) }
    val findings = remember(sens) { XrayPreviewData.result.findings(sens.rawValue) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).icerikGenisligi(640.dp).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XraySummaryPill(4, "diş", DS.Colors.success)
            XraySummaryPill(2, "öneri", DS.Colors.danger)
            XraySummaryPill(2, "mevcut", mutedHex("06b6d4"))
        }
        XrayResultCanvas(bmp, findings, sel, { sel = it }, Modifier.fillMaxWidth(), anatomy = XrayPreviewData.result.anatomy())
        Box(Modifier.fillMaxWidth().aspectRatio(2f).background(Color.Black)) { XrayScanningOverlay() }
        XraySensitivityPicker(sens, { sens = it })
        findings.forEach { XrayToothRow(it, selected = it.id == sel) }
        XrayLegend(findings, Modifier.padding(14.dp))
        Spacer(Modifier.height(20.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun XrayResultViewsPreview() {
    PreviewEnv { XrayResultViewsOrnek() }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun XrayResultViewsTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { XrayResultViewsOrnek() }
}
