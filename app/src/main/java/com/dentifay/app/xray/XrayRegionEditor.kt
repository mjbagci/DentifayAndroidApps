package com.dentifay.app.xray

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.ui.PreviewEnv
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Röntgen alanı seçimi — gülüş simülasyonundaki ağız seçiminin röntgen karşılığı.
 * Köşeler otomatik ayrıştırılan röntgenin kenarına oturmuş gelir; hekim dört köşeyi
 * sürükleyerek düzeltir (sürüklerken büyüteç açılır). Seçilen dörtgen perspektifi
 * düzeltilerek analize gider; dışındaki ekran arayüzü, çerçeve ve masa analize karışmaz.
 */
@Composable
fun XrayRegionEditor(image: Bitmap, quad: XrayQuad, onQuadChange: (XrayQuad) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var fingerInView by remember { mutableStateOf(Offset.Zero) }
    var lastDragged by remember { mutableStateOf(0) }
    val bmp = remember(image) { image.asImageBitmap() }
    val density = LocalDensity.current
    val labels = listOf("Sol üst köşe", "Sağ üst köşe", "Sağ alt köşe", "Sol alt köşe")

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val fit = xrayFitRect(Size(image.width.toFloat(), image.height.toFloat()), Size(boxW, boxH), with(density) { 18.dp.toPx() })
        val pts = quad.points.map { Offset(fit.left + it.x * fit.width, fit.top + it.y * fit.height) }
        val currentQuad by rememberUpdatedState(quad)
        val currentFit by rememberUpdatedState(fit)
        val currentOnChange by rememberUpdatedState(onQuadChange)
        val touchRadius = with(density) { 22.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val f = currentFit
                        val handles = currentQuad.points.map { Offset(f.left + it.x * f.width, f.top + it.y * f.height) }
                        val idx = handles.indices.minByOrNull { (handles[it] - down.position).getDistance() } ?: return@awaitEachGesture
                        if ((handles[idx] - down.position).getDistance() > touchRadius) return@awaitEachGesture
                        down.consume()
                        fun update(pos: Offset) {
                            if (dragging != idx) { dragging = idx; Haptics.selection() }
                            fingerInView = pos
                            val ff = currentFit
                            val p = Offset(
                                ((pos.x - ff.left) / ff.width).coerceIn(0f, 1f),
                                ((pos.y - ff.top) / ff.height).coerceIn(0f, 1f),
                            )
                            val q = currentQuad.with(idx, p)
                            if (q.isValid) currentOnChange(q)
                        }
                        update(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            update(ch.position)
                        }
                        dragging = null
                    }
                },
        ) {
            val guideAlpha by animateFloatAsState(if (dragging == null) 0.14f else 0.28f, Motion.easeOut(120), label = "guides")
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                    dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
                // Alan dışını karart
                drawPath(dimPath(fit, pts), Color.Black.copy(alpha = 0.58f))
                // Üçte bir kılavuz çizgileri (alan içinde)
                val guides = Path()
                for (t in listOf(1f / 3, 2f / 3)) {
                    lerp(pts[0], pts[3], t).let { guides.moveTo(it.x, it.y) }
                    lerp(pts[1], pts[2], t).let { guides.lineTo(it.x, it.y) }
                    lerp(pts[0], pts[1], t).let { guides.moveTo(it.x, it.y) }
                    lerp(pts[3], pts[2], t).let { guides.lineTo(it.x, it.y) }
                }
                drawPath(guides, Color.White.copy(alpha = guideAlpha), style = Stroke(0.8.dp.toPx()))
                drawPath(quadPath(pts), DS.Colors.cyan400, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            }

            for (i in 0 until 4) {
                val active = dragging == i
                XrayRegionHandle(
                    active,
                    Modifier
                        .offset { IntOffset((pts[i].x - 22.dp.toPx()).roundToInt(), (pts[i].y - 22.dp.toPx()).roundToInt()) }
                        .semantics { contentDescription = labels[i] },
                )
            }

            val di = dragging
            if (di != null) lastDragged = di
            AnimatedVisibility(
                visible = di != null,
                enter = fadeIn(Motion.easeOut(120)), exit = fadeOut(Motion.easeOut(120)),
            ) {
                val i = lastDragged
                val pos = loupePosition(fingerInView, boxW, with(density) { 72.dp.toPx() })
                XrayLoupe(
                    bmp, quad[i], fit,
                    Modifier.offset { IntOffset((pos.x - 58.dp.toPx()).roundToInt(), (pos.y - 58.dp.toPx()).roundToInt()) },
                )
            }
        }
    }
}

@Composable
private fun XrayRegionHandle(active: Boolean, modifier: Modifier) {
    val s by animateDpAsState(if (active) 22.dp else 18.dp, Motion.easeOut(120), label = "handle")
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {   // geniş dokunma alanı
        Box(
            Modifier
                .size(s)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(CircleShape)
                .background(if (active) DS.Colors.cyan400 else Color.White)
                .border(3.dp, DS.Colors.cyan400, CircleShape),
        )
    }
}

/** Parmağın altındaki bölgeyi 3× gösteren büyüteç (artı işaretli). */
@Composable
private fun XrayLoupe(bmp: ImageBitmap, center: Offset, fit: Rect, modifier: Modifier) {
    val zoom = 3f
    Box(
        modifier
            .size(116.dp)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val size = this.size.width
            val w = fit.width * zoom; val h = fit.height * zoom
            drawRect(Color.Black)
            drawImage(
                bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset((size / 2 - center.x * w).roundToInt(), (size / 2 - center.y * h).roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                filterQuality = FilterQuality.High,
            )
            val c = size / 2; val l = 14.dp.toPx()
            val sw = 1.5.dp.toPx()
            drawLine(DS.Colors.cyan400, Offset(c, c - l), Offset(c, c + l), sw)
            drawLine(DS.Colors.cyan400, Offset(c - l, c), Offset(c + l, c), sw)
        }
    }
}

/** Büyüteç parmağın karşı üst köşesinde durur (parmak üstünü kapatmasın). */
private fun loupePosition(finger: Offset, width: Float, inset: Float): Offset {
    val x = if (finger.x < width / 2) width - inset else inset
    return Offset(x, inset)
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

internal fun quadPath(pts: List<Offset>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    pts.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/** Dış dikdörtgen + dörtgen (çift-tek doldurma = dörtgen dışı). */
internal fun dimPath(fit: Rect, pts: List<Offset>): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addRect(fit)
    moveTo(pts[0].x, pts[0].y)
    pts.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/** Görseli kutuya sığdıran dikdörtgen (px). */
internal fun xrayFitRect(image: Size, box: Size, padding: Float = 0f): Rect {
    val aw = max(1f, box.width - padding * 2); val ah = max(1f, box.height - padding * 2)
    val s = min(aw / max(image.width, 1f), ah / max(image.height, 1f))
    val w = image.width * s; val h = image.height * s
    val x = (box.width - w) / 2; val y = (box.height - h) / 2
    return Rect(x, y, x + w, y + h)
}

/** Orijinal fotoğraf + ayrıştırılan röntgen alanı (önizlemede "Orijinal fotoğraf"). */
@Composable
fun XrayCropPreview(image: Bitmap, quad: XrayQuad, modifier: Modifier = Modifier) {
    val bmp = remember(image) { image.asImageBitmap() }
    Canvas(modifier.fillMaxSize().semantics { contentDescription = "Orijinal fotoğrafta ayrıştırılan röntgen alanı" }) {
        val fit = xrayFitRect(Size(image.width.toFloat(), image.height.toFloat()), size)
        val pts = quad.points.map { Offset(fit.left + it.x * fit.width, fit.top + it.y * fit.height) }
        drawFitted(bmp, fit)
        drawPath(dimPath(fit, pts), Color.Black.copy(alpha = 0.5f))
        drawPath(quadPath(pts), DS.Colors.cyan400, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    }
}

internal fun DrawScope.drawFitted(bmp: ImageBitmap, r: Rect) {
    drawImage(
        bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
        dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.High,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun XrayRegionEditorPreview() {
    PreviewEnv {
        val bmp = remember {
            Bitmap.createBitmap(800, 420, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
        }
        var quad by remember {
            mutableStateOf(XrayQuad(Offset(0.08f, 0.1f), Offset(0.92f, 0.06f), Offset(0.95f, 0.9f), Offset(0.05f, 0.94f)))
        }
        XrayRegionEditor(bmp, quad, { quad = it }, Modifier.fillMaxSize())
    }
}
