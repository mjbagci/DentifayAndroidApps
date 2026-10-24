package com.dentifay.app.smile

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import kotlin.math.max

// MARK: - Bölge + maske önizlemesi

/**
 * Fotoğrafı gösterir; yapay zekaya gidecek bölge dışını karartır, diş
 * açıklığı maskesini turkuaz olarak boyar.
 */
@Composable
internal fun SmileRegionPreview(image: Bitmap, region: MouthRegion, mask: Bitmap?, modifier: Modifier = Modifier) {
    val img = remember(image) { image.asImageBitmap() }
    val maskImg = remember(mask) { mask?.asImageBitmap() }
    val pulse by rememberInfiniteTransition(label = "maskPulse")
        .animateFloat(0.3f, 0.55f, infiniteRepeatable(Motion.easeInOut(1400), RepeatMode.Reverse), label = "maskAlpha")
    val iw = region.imageSize.width.toFloat()
    val ih = region.imageSize.height.toFloat()
    val shape = RoundedCornerShape(22.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 420.dp)
                .aspectRatio(iw / max(ih, 1f))
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
                .semantics { contentDescription = "Fotoğraf; yalnızca işaretli ağız bölgesi işlenecek" }
        ) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val dst = IntSize(size.width.toInt(), size.height.toInt())
                if (maskImg != null) {
                    drawImage(maskImg, dstSize = dst, colorFilter = ColorFilter.tint(DS.Colors.cyan400.copy(alpha = pulse), BlendMode.SrcIn))
                }
                val scale = size.width / max(iw, 1f)
                val c = region.cropRect
                val r = Rect(c.left * scale, c.top * scale, c.right * scale, c.bottom * scale)
                val corner = CornerRadius(12.dp.toPx())
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(r, corner))
                }
                drawPath(path, Color.Black.copy(alpha = 0.55f))
                // Işıltı (shadow radius 8) + çerçeve
                drawRoundRect(DS.Colors.cyan400.copy(alpha = 0.25f), r.topLeft, r.size, corner, style = Stroke(8.dp.toPx()))
                drawRoundRect(DS.Colors.cyan400, r.topLeft, r.size, corner, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

// MARK: - Tarama efekti

@Composable
internal fun SmileScanOverlay(modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "scan")
        .animateFloat(-0.2f, 0.85f, infiniteRepeatable(Motion.easeInOut(1800), RepeatMode.Reverse), label = "scanPhase")
    Canvas(modifier) {
        val bandH = size.height * 0.35f
        val top = phase * size.height
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Transparent, DS.Colors.cyan400.copy(alpha = 0.35f), Color.Transparent),
                startY = top, endY = top + bandH,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, bandH),
            blendMode = BlendMode.Screen,
        )
    }
}

// MARK: - Önce / sonra (paylaşılan görselle aynı düzen)

/** Gülüş (yatay) alt alta, tüm yüz yan yana; etiketli. */
@Composable
internal fun SmileComparisonView(before: Bitmap, after: Bitmap, caption: String, modifier: Modifier = Modifier) {
    val stacked = before.width > before.height
    val sem = Modifier.semantics(mergeDescendants = true) { contentDescription = "Önce ve sonra karşılaştırması: $caption" }
    val afterLabel = "SONRA · ${caption.upperTr()}"
    if (stacked) {
        Column(modifier.fillMaxSize().then(sem), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ComparisonTile(before, "ÖNCE", false, Modifier.weight(1f).fillMaxWidth())
            ComparisonTile(after, afterLabel, true, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(modifier.fillMaxSize().then(sem), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComparisonTile(before, "ÖNCE", false, Modifier.weight(1f).fillMaxSize())
            ComparisonTile(after, afterLabel, true, Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable
private fun ComparisonTile(image: Bitmap, label: String, highlight: Boolean, modifier: Modifier) {
    val img = remember(image) { image.asImageBitmap() }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(image.width.toFloat() / max(image.height, 1).toFloat())
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
        ) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Text(
                label,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (highlight) DS.Gradients.primaryGradient else SolidColor(Color.Black.copy(alpha = 0.55f)))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                style = sys(10, FontWeight.Bold, tracking = 1), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - Karşılaştır sayfası

/** Tüm sonuçlar ızgarada (yakın plan); dokununca o sonuç seçilir. */
@Composable
internal fun SmileCompareSheet(vm: SmileSimulationViewModel, onDismiss: () -> Unit) {
    val zoomRect = vm.zoomRect()
    fun zoomed(b: Bitmap): Bitmap = b.croppedTo(zoomRect) ?: b
    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        SmileInlineNavBar("Karşılaştır", onTrailing = onDismiss)
        val done = vm.jobs.filter { it.result != null }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            vm.original?.let { before ->
                item(key = "before") { CompareTile("Önce", remember(before, zoomRect) { zoomed(before) }) }
            }
            items(done, key = { it.id.toString() }) { job ->
                val res = job.result ?: return@items
                CompareTile(job.title, remember(res, zoomRect) { zoomed(res) }, Modifier.plainClickable {
                    vm.activeJobID = job.id
                    onDismiss()
                })
            }
        }
    }
}

@Composable
private fun CompareTile(title: String, image: Bitmap, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(
            remember(image) { image.asImageBitmap() }, null,
            Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(title, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurface)
    }
}

/** Alt eylem çubuğu zemini (web: düz yüzey + üst ince çizgi). */
internal fun Modifier.smileBarBackground(): Modifier = this
    .background(DS.Colors.background)
    .drawBehind { drawRect(DS.Colors.hairline, size = Size(size.width, 1.dp.toPx())) }

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileComparisonPreview() {
    PreviewEnv {
        val b = rememberSampleBitmap(R.drawable.smile_before)
        val a = rememberSampleBitmap(R.drawable.smile_after)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.height(360.dp)) { SmileComparisonView(b, a, "Doğal Estetik") }
            Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp))) {
                Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                SmileScanOverlay(Modifier.fillMaxSize())
            }
        }
    }
}
