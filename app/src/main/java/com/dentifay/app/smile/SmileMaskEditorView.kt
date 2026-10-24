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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
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
 *
 * Tuval pencereye sığar (oran korunur). Yatay ve geniş pencerede (tablet) araçlar sağda dikey bir sütundadır —
 * tuval tüm yüksekliği kullanır; dikeyde altta, en çok 560 dp genişlikte ortalanır.
 */
@Composable
fun SmileMaskEditorView(input: MaskEditorInput, onDone: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val canvas = remember(input.id) { MaskCanvas(input.mask) }
    var erasing by remember { mutableStateOf(false) }
    /** Fırça yarıçapı, kırpım genişliğine oran. */
    var brush by remember { mutableFloatStateOf(0.035f) }
    val ikiPanel = LocalPencere.current.smileIkiPanel

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // Tam ekran kapak: yatayda çentik içeriği kesmesin.
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Üst bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "İptal", Modifier.height(44.dp).plainClickable(onClick = onDismiss).padding(top = 12.dp),
                    style = sys(16), color = DS.Colors.onSurfaceVariant, maxLines = 1,
                )
                Column(
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Maskeyi Seç", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1)
                    Text(
                        "Turkuaz alan değişir · fırçayla ekleyin, silgiyle çıkarın",
                        style = sys(11), color = DS.Colors.cyan400, textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "Bitti",
                    Modifier.height(44.dp).plainClickable { onDone(canvas.image); onDismiss() }.padding(top = 12.dp),
                    style = sys(16, FontWeight.Bold), color = DS.Colors.cyan400, maxLines = 1,
                )
            }

            if (ikiPanel) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MaskBoard(input, canvas, brush, erasing, Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        MaskTools(input, canvas, erasing, { erasing = it }, brush, { brush = it }, vertical = true)
                    }
                }
            } else {
                MaskBoard(input, canvas, brush, erasing, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                Column(Modifier.icerikGenisligi(560.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MaskTools(input, canvas, erasing, { erasing = it }, brush, { brush = it }, vertical = false)
                }
            }
        }
    }
}

/** Tuval: kırpım + turkuaz maske; kutuya oranı korunarak sığar. Boyama çizim aşamasında yenilenir (yeniden kompozisyon yok). */
@Composable
private fun MaskBoard(input: MaskEditorInput, canvas: MaskCanvas, brush: Float, erasing: Boolean, modifier: Modifier) {
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val cropImg = remember(input.crop) { input.crop.asImageBitmap() }
    val maskImg = remember(canvas) { canvas.bitmap.asImageBitmap() }
    val density = LocalDensity.current
    // Jest döngüsü uzun yaşar: güncel fırça/silgi değerleri buradan okunur.
    val brushState = rememberUpdatedState(brush)
    val erasingState = rememberUpdatedState(erasing)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
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
                            val radius = brushState.value * canvas.width
                            if (last == null) canvas.beginStroke()
                            canvas.stroke(last ?: px, px, radius, erasingState.value)
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
                    val r = brushState.value * size.width
                    drawCircle(if (erasingState.value) DS.Colors.orange400 else Color.White, r, c, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

/** Fırça/silgi, fırça boyutu, geri al · otomatik · temizle. `vertical`: yan sütunda düğmeler alt alta. */
@Composable
private fun MaskTools(
    input: MaskEditorInput,
    canvas: MaskCanvas,
    erasing: Boolean,
    onErasing: (Boolean) -> Unit,
    brush: Float,
    onBrush: (Float) -> Unit,
    vertical: Boolean,
) {
    SmileSegmented(
        options = listOf(false, true),
        selected = erasing,
        onSelect = { onErasing(it); Haptics.selection() },
        modifier = Modifier.fillMaxWidth(),
        label = { if (it) "Silgi" else "Fırça" },
        icon = { if (it) "eraser.fill" else "paintbrush.pointed.fill" },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(DS.Colors.onSurfaceVariant))
        SmileSlider(brush, onBrush, 0.012f..0.09f, Modifier.weight(1f).semantics { contentDescription = "Fırça boyutu" })
        Box(Modifier.size(18.dp).clip(CircleShape).background(DS.Colors.onSurfaceVariant))
    }
    if (vertical) {
        ToolButton("Geri al", "arrow.uturn.backward", canvas.canUndo, Modifier.fillMaxWidth()) { canvas.undo() }
        ToolButton("Otomatik", "viewfinder", true, Modifier.fillMaxWidth()) { canvas.replace(input.auto) }
        ToolButton("Temizle", "trash", true, Modifier.fillMaxWidth()) { canvas.clear() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolButton("Geri al", "arrow.uturn.backward", canvas.canUndo, Modifier.weight(1f)) { canvas.undo() }
            ToolButton("Otomatik", "viewfinder", true, Modifier.weight(1f)) { canvas.replace(input.auto) }
            ToolButton("Temizle", "trash", true, Modifier.weight(1f)) { canvas.clear() }
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
        Text(title, style = sys(14, FontWeight.SemiBold), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun SmileMaskEditorTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
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
