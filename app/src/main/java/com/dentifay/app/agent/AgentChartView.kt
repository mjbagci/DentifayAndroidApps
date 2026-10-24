package com.dentifay.app.agent

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Ajanın `chart_spec`ini (çubuk / çizgi / pasta) çizer — Swift Charts yerine Compose çizimi.
 * Geometri (eksen, etiket ölçüleri, değerler) boyut başına BİR kez kurulur (`drawWithCache`); giriş animasyonunun
 * ilerlemesi yalnız çizim aşamasında okunur. Yükseklik sütun genişliğiyle ölçeklenir (telefonda 220 dp, Swift ile aynı;
 * geniş sütunda en çok 340 dp; kısa pencerede 220 dp).
 */
@Composable
fun AgentChartView(spec: AgentChartSpec, modifier: Modifier = Modifier) {
    val azHareket = rememberAzHareket()
    val progress = remember(spec.id) { Animatable(if (azHareket) 1f else 0f) }
    LaunchedEffect(spec.id) { if (progress.value < 1f) progress.animateTo(1f, Motion.easeOut(800)) }

    val veri = remember(spec.id) { GrafikVerisi.kur(spec) }
    val measurer = rememberTextMeasurer()
    val pencere = LocalPencere.current
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val title = spec.title
        if (!title.isNullOrEmpty()) {
            Text(title, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tavan = if (pencere.kisa) 220.dp else 340.dp
            val yukseklik = (maxWidth * 0.5f).coerceIn(220.dp, tavan)
            Column(Modifier.fillMaxWidth().height(yukseklik), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cizim = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics { contentDescription = veri.aciklama(spec.title) }
                when (veri.tur) {
                    GrafikVerisi.Tur.pasta -> {
                        Spacer(cizim.drawWithCache {
                            val g = pastaGeometrisi(veri)
                            onDrawBehind { drawPie(g, progress.value) }
                        })
                        Legend(veri.dilimAdlari)
                    }
                    GrafikVerisi.Tur.cizgi, GrafikVerisi.Tur.cubuk -> {
                        Spacer(cizim.drawWithCache {
                            val g = xyGeometrisi(veri, size, measurer, this)
                            onDrawBehind { drawXY(g, progress.value) }
                        })
                        if (veri.seriler.size > 1) Legend(veri.seriler)
                    }
                }
            }
        }
    }
}

private val palette: List<Color> = listOf(
    DS.Colors.cyan400, DS.Colors.purple500, DS.Colors.orange400,
    DS.Colors.tertiaryContainer, DS.Colors.secondary, DS.Colors.brandBlue,
)

private fun colorAt(i: Int): Color = palette[i % palette.size]

@Composable
private fun Legend(labels: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colorAt(i)))
                Text(label, style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// MARK: - Veri (spec başına bir kez)

/** Spec'ten türetilen çizim verisi — Swift'teki `seriesDefs` / `points` / `slices`. */
private class GrafikVerisi(
    val tur: Tur,
    /** Kategoriler (x), sırası korunur. */
    val kategoriler: List<String>,
    /** Seri etiketleri (sırası korunur). */
    val seriler: List<String>,
    /** [kategori][seri] → değer (yoksa null). */
    val degerler: Array<Array<Double?>>,
    val dilimAdlari: List<String>,
    val dilimDegerleri: List<Double>,
) {
    enum class Tur { cubuk, cizgi, pasta }

    fun aciklama(baslik: String?): String = buildString {
        if (!baslik.isNullOrEmpty()) append(baslik).append(". ")
        if (tur == Tur.pasta) {
            dilimAdlari.forEachIndexed { i, ad -> append(ad).append(": ").append(eksenBicimi.format(dilimDegerleri[i])).append(". ") }
        } else {
            kategoriler.forEachIndexed { ki, k ->
                append(k).append(": ")
                append(seriler.indices.mapNotNull { si -> degerler[ki][si]?.let { eksenBicimi.format(it) } }.joinToString(", "))
                append(". ")
            }
        }
    }

    companion object {
        fun kur(spec: AgentChartSpec): GrafikVerisi {
            val tur = when (spec.type.lowercase()) {
                "pie", "donut" -> Tur.pasta
                "line", "area" -> Tur.cizgi
                else -> Tur.cubuk
            }
            val seriesDefs = spec.series.ifEmpty { listOf(AgentChartSpec.Series(spec.valueKey ?: "value", spec.title ?: "")) }
            val xKey = spec.xKey ?: spec.nameKey ?: "label"
            val kategoriler = LinkedHashSet<String>()
            val seriler = LinkedHashSet<String>()
            val noktalar = mutableListOf<Triple<String, String, Double>>()
            spec.rows.forEach { row ->
                val x = row[xKey]?.stringValue ?: ""
                seriesDefs.forEach { s ->
                    row[s.key]?.doubleValue?.let { y ->
                        kategoriler += x; seriler += s.label
                        noktalar += Triple(x, s.label, y)
                    }
                }
            }
            val kList = kategoriler.toList()
            val sList = seriler.toList()
            val degerler = Array(kList.size) { arrayOfNulls<Double>(sList.size) }
            noktalar.forEach { (x, s, y) ->
                val ki = kList.indexOf(x); val si = sList.indexOf(s)
                // Swift Charts'ta aynı (x, seri) için ilk nokta çizilir.
                if (degerler[ki][si] == null) degerler[ki][si] = y
            }

            val nameKey = spec.nameKey ?: spec.xKey ?: "label"
            val valueKey = spec.valueKey ?: spec.series.firstOrNull()?.key ?: "value"
            val dilimAd = mutableListOf<String>()
            val dilimDeger = mutableListOf<Double>()
            spec.rows.forEach { row ->
                row[valueKey]?.doubleValue?.let { v -> dilimAd += row[nameKey]?.stringValue ?: ""; dilimDeger += v }
            }
            return GrafikVerisi(tur, kList, sList, degerler, dilimAd.distinct(), dilimDeger)
        }
    }
}

private val eksenBicimi: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).apply { maximumFractionDigits = 2 }

/** Swift Charts benzeri "güzel" eksen adımı (1-2-5 dizisi). */
private fun niceStep(range: Double, targetTicks: Int = 4): Double {
    if (range <= 0) return 1.0
    val raw = range / targetTicks
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val nice = when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

// MARK: - XY (çubuk · çizgi)

private class XYGeometri(
    val cizgi: Boolean,
    val veri: GrafikVerisi,
    val lo: Double,
    val hi: Double,
    val plotLeft: Float,
    val plotRight: Float,
    val plotTop: Float,
    val plotBottom: Float,
    val band: Float,
    /** Kılavuz çizgilerinin y'leri + eksen etiketleri (sağda). */
    val kilavuzY: FloatArray,
    val yEtiketleri: List<Pair<TextLayoutResult, Offset>>,
    val xEtiketleri: List<Pair<TextLayoutResult, Offset>>,
    val kilavuzKalinlik: Float,
    val cubukGenislik: Float,
    val koseYaricapi: Float,
    val cizgiKalinlik: Float,
    val noktaYaricapi: Float,
    /** Çizgi grafikte seri başına yeniden kullanılan yol (her karede yeni nesne yok). */
    val yollar: List<Path>,
) {
    fun yPos(v: Double): Float = (plotBottom - ((v - lo) / (hi - lo)) * max(1f, plotBottom - plotTop)).toFloat()
    fun cx(i: Int): Float = plotLeft + band * (i + 0.5f)
}

private fun xyGeometrisi(veri: GrafikVerisi, size: Size, measurer: TextMeasurer, density: Density): XYGeometri = with(density) {
    val labelStyle = TextStyle(fontSize = sys(10).fontSize, color = DS.Colors.onSurfaceVariant)
    val cizgi = veri.tur == GrafikVerisi.Tur.cizgi
    val kategoriler = veri.kategoriler

    // Y alanı: çubukta yığın toplamları, çizgide tekil değerler; 0 her zaman dahil.
    val values = mutableListOf<Double>()
    veri.degerler.forEach { satir ->
        if (cizgi) {
            satir.forEach { v -> if (v != null) values += v }
        } else {
            values += satir.filterNotNull().filter { it > 0 }.sum()
            values += satir.filterNotNull().filter { it < 0 }.sum()
        }
    }
    var lo = min(0.0, values.minOrNull() ?: 0.0)
    var hi = max(0.0, values.maxOrNull() ?: 0.0)
    if (hi == lo) hi = lo + 1
    val step = niceStep(hi - lo)
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step
    val ticks = generateSequence(lo) { it + step }.takeWhile { it <= hi + step * 0.001 }.toList()

    // Sağda Y etiketleri (Swift Charts varsayılanı), altta X etiketleri.
    val yLayouts = ticks.map { measurer.measure(eksenBicimi.format(it), labelStyle, density = density) }
    val yLabelWidth = yLayouts.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    val xLabelHeight = measurer.measure("Ag", labelStyle, density = density).size.height.toFloat()
    val gap = 6.dp.toPx()
    val plotLeft = 0f
    val plotRight = size.width - yLabelWidth - gap
    val plotTop = xLabelHeight / 2
    val plotBottom = size.height - xLabelHeight - gap
    val plotW = max(1f, plotRight - plotLeft)
    val band = if (kategoriler.isEmpty()) plotW else plotW / kategoriler.size
    fun yPos(v: Double): Float = (plotBottom - ((v - lo) / (hi - lo)) * max(1f, plotBottom - plotTop)).toFloat()

    val kilavuzY = FloatArray(ticks.size) { yPos(ticks[it]) }
    val yEtiketleri = ticks.indices.map { i ->
        val l = yLayouts[i]
        l to Offset(plotRight + gap, kilavuzY[i] - l.size.height / 2f)
    }

    // X etiketleri (bant ortası; sığmayanlar seyreltilir)
    val xLayouts = kategoriler.map { measurer.measure(it, labelStyle, maxLines = 1, softWrap = false, density = density) }
    val maxLabelW = xLayouts.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    val every = max(1, ceil((maxLabelW + 4.dp.toPx()) / band).toInt())
    val xEtiketleri = xLayouts.mapIndexedNotNull { i, l ->
        if (i % every != 0) return@mapIndexedNotNull null
        val cx = plotLeft + band * (i + 0.5f)
        l to Offset(cx - l.size.width / 2f, plotBottom + gap)
    }

    XYGeometri(
        cizgi = cizgi, veri = veri, lo = lo, hi = hi,
        plotLeft = plotLeft, plotRight = plotRight, plotTop = plotTop, plotBottom = plotBottom, band = band,
        kilavuzY = kilavuzY, yEtiketleri = yEtiketleri, xEtiketleri = xEtiketleri,
        kilavuzKalinlik = 1.dp.toPx(), cubukGenislik = band * 0.6f, koseYaricapi = 4.dp.toPx(),
        cizgiKalinlik = 2.dp.toPx(), noktaYaricapi = 3.dp.toPx(),
        yollar = List(if (cizgi) veri.seriler.size else 0) { Path() },
    )
}

/** Çubuk (seriler üst üste yığılır — Swift Charts varsayılanı) ya da çizgi + nokta grafiği. */
private fun DrawScope.drawXY(g: XYGeometri, progress: Float) {
    if (g.veri.kategoriler.isEmpty()) return
    val kilavuz = Color.White.copy(alpha = 0.06f)
    g.kilavuzY.forEach { y -> drawLine(kilavuz, Offset(g.plotLeft, y), Offset(g.plotRight, y), strokeWidth = g.kilavuzKalinlik) }
    g.yEtiketleri.forEach { (l, o) -> drawText(l, topLeft = o) }
    g.xEtiketleri.forEach { (l, o) -> drawText(l, topLeft = o) }

    val p = progress.toDouble()
    val seriSayisi = g.veri.seriler.size
    if (!g.cizgi) {
        g.veri.degerler.forEachIndexed { ki, satir ->
            val cx = g.cx(ki)
            var posBase = 0.0
            var negBase = 0.0
            for (si in 0 until seriSayisi) {
                val v = satir[si] ?: continue
                val scaled = v * p
                val from: Double
                val to: Double
                if (scaled >= 0) {
                    from = posBase; to = posBase + scaled; posBase = to
                } else {
                    from = negBase; to = negBase + scaled; negBase = to
                }
                val top = min(g.yPos(from), g.yPos(to))
                val bottom = max(g.yPos(from), g.yPos(to))
                if (bottom - top < 0.5f) continue
                val r = min(g.koseYaricapi, (bottom - top) / 2)
                drawRoundRect(
                    colorAt(si), topLeft = Offset(cx - g.cubukGenislik / 2, top), size = Size(g.cubukGenislik, bottom - top),
                    cornerRadius = CornerRadius(r),
                )
            }
        }
    } else {
        for (si in 0 until seriSayisi) {
            val pts = ArrayList<Offset>(g.veri.kategoriler.size)
            g.veri.degerler.forEachIndexed { ki, satir -> satir[si]?.let { v -> pts += Offset(g.cx(ki), g.yPos(v * p)) } }
            if (pts.isEmpty()) continue
            val color = colorAt(si)
            val path = g.yollar[si]
            catmullRom(path, pts)
            drawPath(path, color, style = Stroke(width = g.cizgiKalinlik, cap = StrokeCap.Round))
            pts.forEach { pt -> drawCircle(color, radius = g.noktaYaricapi, center = pt) }
        }
    }
}

/** `.interpolationMethod(.catmullRom)` karşılığı yumuşak eğri (yol yeniden kullanılır). */
private fun catmullRom(path: Path, pts: List<Offset>) {
    path.reset()
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[min(pts.size - 1, i + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
}

// MARK: - Pasta (halka)

private class PastaGeometri(
    val degerler: List<Double>,
    val toplam: Double,
    val merkez: Offset,
    val ortaYaricap: Float,
    val kalinlik: Float,
    val araAci: Float,
)

/** Halka grafik: iç yarıçap %50, dilimler arası 1.5 dp boşluk. */
private fun androidx.compose.ui.draw.CacheDrawScope.pastaGeometrisi(veri: GrafikVerisi): PastaGeometri {
    val outer = min(size.width, size.height) / 2f
    val inner = outer * 0.5f
    val thickness = outer - inner
    val mid = inner + thickness / 2
    val insetDeg = if (mid > 0f) Math.toDegrees((1.5.dp.toPx() / mid).toDouble()).toFloat() else 0f
    return PastaGeometri(
        degerler = veri.dilimDegerleri,
        toplam = veri.dilimDegerleri.sumOf { abs(it) },
        merkez = Offset(size.width / 2, size.height / 2),
        ortaYaricap = mid,
        kalinlik = thickness,
        araAci = insetDeg,
    )
}

private fun DrawScope.drawPie(g: PastaGeometri, progress: Float) {
    if (g.toplam <= 0 || progress <= 0f) return
    var start = -90f
    val totalSweep = 360f * progress
    val stil = Stroke(width = g.kalinlik, cap = StrokeCap.Butt)
    val kose = Offset(g.merkez.x - g.ortaYaricap, g.merkez.y - g.ortaYaricap)
    val boyut = Size(g.ortaYaricap * 2, g.ortaYaricap * 2)
    g.degerler.forEachIndexed { i, v ->
        val sweep = (abs(v) / g.toplam * totalSweep).toFloat()
        val drawSweep = sweep - g.araAci
        if (drawSweep > 0f) {
            drawArc(colorAt(i), startAngle = start + g.araAci / 2, sweepAngle = drawSweep, useCenter = false, topLeft = kose, size = boyut, style = stil)
        }
        start += sweep
    }
}

private fun ornekGrafikSatiri(vararg pairs: Pair<String, Any>): Map<String, JSONValue> = pairs.associate { (k, v) ->
    k to (if (v is Number) JSONValue.number(v.toDouble()) else JSONValue.string(v.toString()))
}

private val ornekGrafikSatirlari = listOf(
    ornekGrafikSatiri("ay" to "Oca", "ciro" to 120000, "gider" to 40000), ornekGrafikSatiri("ay" to "Şub", "ciro" to 145000, "gider" to 52000),
    ornekGrafikSatiri("ay" to "Mar", "ciro" to 132000, "gider" to 47000), ornekGrafikSatiri("ay" to "Nis", "ciro" to 168000, "gider" to 61000),
)

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentChartViewPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AgentChartView(
                AgentChartSpec("bar", "Aylık ciro", "ay", null, null, "currency",
                    listOf(AgentChartSpec.Series("ciro", "Ciro"), AgentChartSpec.Series("gider", "Gider")), ornekGrafikSatirlari),
            )
            AgentChartView(AgentChartSpec("line", "Ciro eğilimi", "ay", null, "ciro", null, emptyList(), ornekGrafikSatirlari))
            AgentChartView(AgentChartSpec("pie", "Tedavi dağılımı", null, "ay", "ciro", null, emptyList(), ornekGrafikSatirlari))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun AgentChartViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.Center) {
            Spacer(Modifier.width(200.dp))
            Box(Modifier.width(760.dp)) {
                AgentChartView(
                    AgentChartSpec("bar", "Aylık ciro", "ay", null, null, "currency",
                        listOf(AgentChartSpec.Series("ciro", "Ciro"), AgentChartSpec.Series("gider", "Gider")), ornekGrafikSatirlari),
                )
            }
        }
    }
}
