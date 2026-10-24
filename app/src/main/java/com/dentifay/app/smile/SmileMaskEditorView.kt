package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Maske düzenleyicinin girdisi (`fullScreenCover(item:)` için). */
class MaskEditorInput(val crop: Bitmap, val mask: Bitmap, val auto: Bitmap) {
    val id: UUID = UUID.randomUUID()
}

/**
 * Hekimin maskeyi parmakla seçtiği ekran. Turkuaz alan yapay zekanın
 * değiştireceği bölgedir: fırça ekler, silgi çıkarır. Örn. yalnızca eksik
 * dişin bölgesi, yalnızca üst ön dişler ya da köşedeki dişleri dahil etmek.
 */
@Composable
fun SmileMaskEditorView(input: MaskEditorInput, onDone: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val canvas = remember(input.id) { MaskCanvas(input.mask) }
    var erasing by remember { mutableStateOf(false) }
    /** Fırça yarıçapı, kırpım genişliğine oran. */
    var brush by remember { mutableFloatStateOf(0.035f) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val cropImg = remember(input.crop) { input.crop.asImageBitmap() }
    val maskImg = remember(canvas) { canvas.bitmap.asImageBitmap() }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Üst bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "İptal", Modifier.height(44.dp).plainClickable(onClick = onDismiss).padding(top = 12.dp),
                    style = sys(16), color = DS.Colors.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Maskeyi Seç", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
                    Text(
                        "Turkuaz alan değişir · fırçayla ekleyin, silgiyle çıkarın",
                        style = sys(11), color = DS.Colors.cyan400, textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "Bitti",
                    Modifier.height(44.dp).plainClickable { onDone(canvas.image); onDismiss() }.padding(top = 12.dp),
                    style = sys(16, FontWeight.Bold), color = DS.Colors.cyan400,
                )
            }

            // Tuval
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                val aspect = input.crop.width.toFloat() / max(input.crop.height, 1).toFloat()
                val wDp = min(maxWidth.value, maxHeight.value * aspect)
                val hDp = wDp / aspect
                val fitPx = with(density) { IntSize(wDp.dp.roundToPx(), hDp.dp.roundToPx()) }
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(wDp.dp, hDp.dp)
                        .clip(shape)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                        .semantics { contentDescription = "Maske tuvali — parmakla boyayın" }
                        .pointerInput(fitPx, canvas) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var last: Offset? = null
                                fun handle(pos: Offset) {
                                    val fw = size.width.toFloat()
                                    val fh = size.height.toFloat()
                                    val p = Offset(pos.x.coerceIn(0f, fw), pos.y.coerceIn(0f, fh))
                                    cursor = p
                                    // görünüm noktası → kırpım pikseli
                                    val k = canvas.width.toFloat() / max(fw, 1f)
                                    val px = Offset(p.x * k, p.y * k)
                                    val radius = brush * canvas.width
                                    if (last == null) canvas.beginStroke()
                                    canvas.stroke(last ?: px, px, radius, erasing)
                                    last = px
                                }
                                handle(down.position)
                                down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    handle(ch.position)
                                    ch.consume()
                                }
                                cursor = null
                            }
                        }
                ) {
                    Image(cropImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    val cyan = DS.Colors.cyan400
                    val filter = remember(cyan) {
                        // Gri maske → turkuaz, alfa = parlaklık × 0.5 (luminanceToAlpha)
                        ColorFilter.colorMatrix(
                            ColorMatrix(
                                floatArrayOf(
                                    0f, 0f, 0f, 0f, cyan.red * 255f,
                                    0f, 0f, 0f, 0f, cyan.green * 255f,
                                    0f, 0f, 0f, 0f, cyan.blue * 255f,
                                    0.5f, 0f, 0f, 0f, 0f,
                                )
                            )
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_VARIABLE") val v = canvas.version   // her değişiklikte yeniden çiz
                        drawImage(
                            maskImg,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            colorFilter = filter,
                        )
                        cursor?.let { c ->
                            val r = brush * size.width
                            drawCircle(if (erasing) DS.Colors.orange400 else Color.White, r, c, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }

            // Araçlar
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SmileSegmented(
                    options = listOf(false, true),
                    selected = erasing,
                    onSelect = { erasing = it; Haptics.selection() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { if (it) "Silgi" else "Fırça" },
                    icon = { if (it) "eraser.fill" else "paintbrush.pointed.fill" },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(DS.Colors.onSurfaceVariant))
                    SmileSlider(brush, { brush = it }, 0.012f..0.09f, Modifier.weight(1f).semantics { contentDescription = "Fırça boyutu" })
                    Box(Modifier.size(18.dp).clip(CircleShape).background(DS.Colors.onSurfaceVariant))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolButton("Geri al", "arrow.uturn.backward", canvas.canUndo, Modifier.weight(1f)) { canvas.undo() }
                    ToolButton("Otomatik", "viewfinder", true, Modifier.weight(1f)) { canvas.replace(input.auto) }
                    ToolButton("Temizle", "trash", true, Modifier.weight(1f)) { canvas.clear() }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(title: String, icon: String, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    val tint = if (enabled) DS.Colors.onSurface else DS.Colors.outline
    Row(
        modifier
            .pressable(enabled = enabled) { action(); Haptics.light() }
            .height(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 14.dp, tint = tint)
        Text(title, style = sys(14, FontWeight.SemiBold), color = tint, maxLines = 1)
    }
}

/** Düzenlenebilir gri maske (kırpım boyutu, beyaz = değişecek alan) + geri alma. */
class MaskCanvas(mask: Bitmap) {
    val width: Int = mask.width
    val height: Int = mask.height
    /** Çizim yüzeyi (opak gri: beyaz = değişecek alan). */
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val ctx = AndroidCanvas(bitmap)
    private val undoStack = ArrayDeque<Bitmap>()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Her değişiklikte artar → tuval yeniden çizilir (`display` yenilemesi). */
    var version by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set

    /** Düzenlenmiş maskenin kopyası. */
    val image: Bitmap get() = bitmap.copy(Bitmap.Config.ARGB_8888, false)

    init {
        fill(mask)
    }

    private fun fill(source: Bitmap?) {
        ctx.drawColor(android.graphics.Color.BLACK)
        source?.let { ctx.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), drawPaint) }
    }

    fun beginStroke() {
        undoStack.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        if (undoStack.size > 20) undoStack.removeFirst()
        canUndo = undoStack.isNotEmpty()
    }

    /** Noktalar kırpım pikselinde, sol-üst orijinli. */
    fun stroke(from: Offset, to: Offset, radius: Float, erase: Boolean) {
        val color = if (erase) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        linePaint.color = color
        linePaint.strokeWidth = max(1f, radius * 2)
        dotPaint.color = color
        ctx.drawLine(from.x, from.y, to.x, to.y, linePaint)
        ctx.drawCircle(to.x, to.y, max(0.5f, radius), dotPaint)
        refresh()
    }

    fun replace(image: Bitmap) {
        beginStroke()
        fill(image)
        refresh()
    }

    fun clear() {
        beginStroke()
        fill(null)
        refresh()
    }

    fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        fill(last)
        last.recycle()
        canUndo = undoStack.isNotEmpty()
        refresh()
    }

    private fun refresh() { version++ }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileMaskEditorPreview() {
    PreviewEnv {
        val src = rememberSampleBitmap(R.drawable.smile_before)
        val input = remember(src) {
            val w = src.width; val h = (src.width / 1.5f).toInt().coerceAtMost(src.height)
            val crop = Bitmap.createBitmap(src, 0, (src.height - h) / 2, w, h)
            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                val c = AndroidCanvas(it); c.drawColor(android.graphics.Color.BLACK)
                c.drawOval(w * 0.3f, h * 0.4f, w * 0.7f, h * 0.6f, Paint().apply { color = android.graphics.Color.WHITE })
            }
            MaskEditorInput(crop, mask, mask)
        }
        SmileMaskEditorView(input, onDone = {}, onDismiss = {})
    }
}
