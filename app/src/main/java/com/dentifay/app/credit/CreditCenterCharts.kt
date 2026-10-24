package com.dentifay.app.credit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// MARK: - Kullanım grafiği (Swift Charts `BarMark` yığını → tek Canvas)

/** Bilinmeyen özellik kimliği "Diğer"e düşmez: grafik alanı TEKİL etiket ister. */
private fun chartLabel(f: String): String = CreditLabels.features[f] ?: f

/** Çizime hazır grafik (veri değişince bir kez kurulur; çizim yalnız piksele çevirir). */
private class GrafikVerisi(
    val dilimSayisi: Int,
    val cubuklar: List<Cubuk>,
    val cizgiler: List<Double>,
    val cizgiEtiketleri: List<String>,
    val yAlt: Double,
    val yUst: Double,
    /** X ekseni: (dilim, etiket) — ~5 eşit aralıklı. */
    val xEtiketleri: List<Pair<Int, String>>,
)

/** Yığın parçası (değer uzayında): dilim, alt ve üst değer, renk. */
private class Cubuk(val dilim: Int, val alt: Double, val ust: Double, val renk: Color)

private val yEksenBicimi = ThreadLocal.withInitial {
    NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).apply { maximumFractionDigits = 2 }
}

private fun grafikVerisi(analysis: CreditAnalysis, order: List<String>): GrafikVerisi? {
    val monthly = analysis.unit == "month"
    class Nokta(val date: LocalDate, val feature: String, val credits: Double)

    val noktalar = mutableListOf<Nokta>()
    for (b in analysis.series) {
        val d = CreditDates.bucketDate(b.key) ?: continue
        for (f in order) {
            val v = b.byFeature[f] ?: continue
            if (v == 0.0) continue
            noktalar += Nokta(d, f, v)
        }
    }
    if (noktalar.isEmpty()) return null

    // Eksen alanı: ilk noktanın gününden son noktanın gününe (birim: gün ya da ay).
    val ilk = noktalar.minOf { it.date }.let { if (monthly) it.withDayOfMonth(1) else it }
    val son = noktalar.maxOf { it.date }.let { if (monthly) it.withDayOfMonth(1) else it }
    val dilimSayisi = (if (monthly) ChronoUnit.MONTHS.between(ilk, son) else ChronoUnit.DAYS.between(ilk, son)).toInt() + 1
    fun dilimi(d: LocalDate): Int =
        if (monthly) ChronoUnit.MONTHS.between(ilk, d.withDayOfMonth(1)).toInt() else ChronoUnit.DAYS.between(ilk, d).toInt()

    // Yığın: pozitifler yukarı, negatifler (iade ağırlıklı gün) aşağı; özellik sırasıyla.
    val cubuklar = mutableListOf<Cubuk>()
    var posMax = 0.0
    var negMin = 0.0
    noktalar.groupBy { dilimi(it.date) }.forEach { (dilim, liste) ->
        var arti = 0.0
        var eksi = 0.0
        order.forEach { f ->
            val p = liste.firstOrNull { it.feature == f } ?: return@forEach
            val renk = CreditPalette.color(f, order)
            if (p.credits > 0) {
                cubuklar += Cubuk(dilim, arti, arti + p.credits, renk)
                arti += p.credits
            } else {
                cubuklar += Cubuk(dilim, eksi + p.credits, eksi, renk)
                eksi += p.credits
            }
        }
        posMax = max(posMax, arti)
        negMin = min(negMin, eksi)
    }
    val cizgiler = niceTicks(negMin, posMax)
    val bicim = yEksenBicimi.get()!!
    val adim = max(1, ceil(dilimSayisi / 5.0).toInt())
    val xEtiketleri = (0 until dilimSayisi step adim).map { s ->
        val tarih = if (monthly) ilk.plusMonths(s.toLong()) else ilk.plusDays(s.toLong())
        s to CreditDates.axisLabel(tarih, monthly)
    }
    return GrafikVerisi(
        dilimSayisi = dilimSayisi,
        cubuklar = cubuklar,
        cizgiler = cizgiler,
        cizgiEtiketleri = cizgiler.map { bicim.format(it) },
        yAlt = cizgiler.first(),
        yUst = cizgiler.last(),
        xEtiketleri = xEtiketleri,
    )
}

private val IzgaraRengi = Color.White.copy(alpha = 0.06f)

@Composable
internal fun CcUsageChart(analysis: CreditAnalysis, order: List<String>, yukseklik: Dp = 184.dp) {
    val veri = remember(analysis, order) { grafikVerisi(analysis, order) }
    if (veri == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text("Bu aralıkta kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val eksenStili = remember { TextStyle(fontSize = 10.sp, color = DS.Colors.onSurfaceVariant) }
    // Eksen etiketleri veri değişince bir kez ölçülür; çizimde yalnız konumlanır.
    val yEtiketleri: List<TextLayoutResult> = remember(veri, measurer, eksenStili) {
        veri.cizgiEtiketleri.map { measurer.measure(it, eksenStili) }
    }
    val xEtiketleri: List<Pair<Int, TextLayoutResult>> = remember(veri, measurer, eksenStili) {
        veri.xEtiketleri.map { (s, t) -> s to measurer.measure(t, eksenStili) }
    }
    val etiketYuksekligi = remember(measurer, eksenStili) { measurer.measure("0", eksenStili).size.height.toFloat() }
    val ozet = remember(analysis) {
        "Kredi kullanımı grafiği, ${if (analysis.unit == "month") "ay ay" else "gün gün"}, ${order.size} özellik"
    }

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(yukseklik)
                .semantics { contentDescription = ozet },
        ) {
            val etiketW = (yEtiketleri.maxOfOrNull { it.size.width } ?: 0).toFloat()
            val sol = etiketW + 6.dp.toPx()
            val alt = size.height - etiketYuksekligi - 6.dp.toPx()
            val ust = 6.dp.toPx()
            val cizimW = max(1f, size.width - sol)
            val cizimH = max(1f, alt - ust)
            val aralik = (veri.yUst - veri.yAlt).takeIf { it > 0 } ?: 1.0
            fun yOf(v: Double): Float = (ust + cizimH * (1 - (v - veri.yAlt) / aralik)).toFloat()
            val ince = 1.dp.toPx()

            // Yatay ızgara + Y etiketleri (solda).
            veri.cizgiler.forEachIndexed { i, t ->
                val y = yOf(t)
                drawLine(IzgaraRengi, Offset(sol, y), Offset(size.width, y), ince)
                val l = yEtiketleri[i]
                drawText(l, topLeft = Offset(etiketW - l.size.width, y - l.size.height / 2f))
            }

            val dilimW = cizimW / veri.dilimSayisi
            // Dikey ızgara + X etiketleri.
            xEtiketleri.forEach { (s, l) ->
                val x = sol + dilimW * s
                drawLine(IzgaraRengi, Offset(x, ust), Offset(x, alt), ince)
                val lx = min(x + 2.dp.toPx(), size.width - l.size.width)
                drawText(l, topLeft = Offset(lx, alt + 4.dp.toPx()))
            }

            // Çubuklar (özellik sırasıyla yığılı).
            val cubukW = dilimW * 0.7f
            veri.cubuklar.forEach { c ->
                val x = sol + dilimW * c.dilim + (dilimW - cubukW) / 2
                val y0 = yOf(c.ust)
                val y1 = yOf(c.alt)
                drawRect(c.renk, Offset(x, y0), Size(cubukW, max(0f, y1 - y0)))
            }
        }
        Spacer(Modifier.height(10.dp))
        // Gösterge: alan = sıralı özellik etiketleri.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            order.forEach { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(CreditPalette.color(f, order)))
                    Text(chartLabel(f), style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Swift Charts'ın "yuvarlak" eksen değerleri: 0'ı içeren, 1-2-5 adımlı ~4 aralık. */
private fun niceTicks(lo: Double, hi: Double): List<Double> {
    val span = max(hi - lo, 1e-9)
    if (hi <= 0 && lo >= 0) return listOf(0.0, 1.0)
    val raw = span / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val step = mag * when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val out = mutableListOf<Double>()
    var v = start
    while (v <= end + step * 1e-6) {
        out += if (abs(v) < step * 1e-9) 0.0 else v
        v += step
    }
    return if (out.size < 2) listOf(start, start + step) else out
}

// MARK: - Gün × saat ısı haritası (`/analiz`in `heatmap`ı: 7 gün × 24 saat, kliniğin dilimi)

private val BosHucre = Color.White.copy(alpha = 0.05f)

@Composable
internal fun CcHeatmapGrid(cells: List<List<CreditAnalysis.Cell>>) {
    var selected by remember(cells) { mutableStateOf<Pair<Int, Int>?>(null) }
    val weekdays = CreditLabels.weekdays
    // Hücre renkleri bir kez: karekök ölçek (web `isiYogunlugu`) — tek yoğun saat diğerlerini görünmez yapmasın.
    val renkler = remember(cells) {
        val maxV = cells.flatten().maxOfOrNull { it.credits } ?: 0.0
        cells.map { satir ->
            satir.map { c ->
                if (c.credits <= 0 || maxV <= 0) BosHucre
                else DS.Colors.cyan400.copy(alpha = (0.15 + 0.85 * min(1.0, sqrt(c.credits / maxV))).toFloat())
            }
        }
    }
    val peakText = remember(cells) {
        var best: Triple<Int, Int, Double>? = null
        cells.forEachIndexed { d, row ->
            row.forEachIndexed { h, c -> if (c.credits > (best?.third ?: 0.0)) best = Triple(d, h, c.credits) }
        }
        best?.takeIf { it.first < weekdays.size }?.let { b ->
            "${weekdays[b.first]} ${"%02d".format(b.second)}:00, ${creditText(b.third)} kredi"
        }
    }
    val secim = selected
    val selectedText: String? = secim?.let { s ->
        if (s.first >= cells.size || s.second >= cells[s.first].size) return@let null
        val c = cells[s.first][s.second]
        val day = if (s.first < weekdays.size) weekdays[s.first] else ""
        "$day ${"%02d".format(s.second)}:00 · ${creditText(c.credits)} kredi · ${c.ops} işlem"
    }

    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Gün ve saate göre kullanım"
            stateDescription = peakText ?: "Kullanım yok"
        },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val etiketW = 28.dp
            val bosluk = 2.dp
            val satirBosluk = 3.dp
            val sutun = max(1, cells.maxOfOrNull { it.size } ?: 24)
            val hucreW = (maxWidth - etiketW - bosluk - bosluk * (sutun - 1)) / sutun
            // Telefonda 15 dp; geniş ekranda hücre oranı korunur (yassılaşmaz).
            val satirH = (hucreW * 0.6f).coerceIn(15.dp, 26.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(bosluk)) {
                Column(Modifier.width(etiketW), verticalArrangement = Arrangement.spacedBy(satirBosluk)) {
                    cells.indices.forEach { d ->
                        Box(Modifier.height(satirH), contentAlignment = Alignment.CenterStart) {
                            Text(
                                if (d < weekdays.size) weekdays[d] else "",
                                style = sys(10, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                }
                val izgaraH = satirH * cells.size + satirBosluk * max(0, cells.size - 1)
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(izgaraH)
                        .pointerInput(cells, satirH) {
                            detectTapGestures { pos ->
                                val adim = (satirH + satirBosluk).toPx()
                                val d = (pos.y / adim).toInt().coerceIn(0, max(0, cells.size - 1))
                                val n = cells.getOrNull(d)?.size ?: 0
                                if (n == 0) return@detectTapGestures
                                val gap = bosluk.toPx()
                                val cw = (size.width - gap * (n - 1)) / n
                                val h = (pos.x / (cw + gap)).toInt().coerceIn(0, n - 1)
                                selected = if (selected == (d to h)) null else d to h
                            }
                        },
                ) {
                    val gap = bosluk.toPx()
                    val rh = satirH.toPx()
                    val adim = (satirH + satirBosluk).toPx()
                    val r = CornerRadius(2.dp.toPx())
                    val secili = selected
                    renkler.forEachIndexed { d, satir ->
                        val n = satir.size
                        if (n == 0) return@forEachIndexed
                        val cw = (size.width - gap * (n - 1)) / n
                        satir.forEachIndexed { h, renk ->
                            drawRoundRect(renk, Offset(h * (cw + gap), d * adim), Size(cw, rh), r)
                        }
                    }
                    if (secili != null) {
                        val n = renkler.getOrNull(secili.first)?.size ?: 0
                        if (n > 0 && secili.second < n) {
                            val cw = (size.width - gap * (n - 1)) / n
                            drawRoundRect(
                                Color.White, Offset(secili.second * (cw + gap), secili.first * adim), Size(cw, rh), r,
                                style = Stroke(1.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
        Row {
            Spacer(Modifier.width(30.dp).height(1.dp))
            listOf("00", "06", "12", "18").forEach { h ->
                Text(h, Modifier.weight(1f), style = sys(9), color = DS.Colors.slate400)
            }
        }
        Text(
            selectedText ?: "Ayrıntı için bir hücreye dokunun.",
            Modifier.padding(top = 4.dp),
            style = sys(12, if (secim == null) FontWeight.Normal else FontWeight.SemiBold),
            color = if (secim == null) DS.Colors.onSurfaceVariant else DS.Colors.onSurface,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CcChartsPreview() {
    com.dentifay.app.ui.PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val a = CreditPreviewData.analysis
            CcCard("Kredi kullanımı", "Gün gün, özelliğe göre") { CcUsageChart(a, a.features.map { it.feature }) }
            CcCard("Gün ve saate göre kullanım", "Kliniğin saatiyle (${a.tz}); koyu hücre daha çok kredi") { CcHeatmapGrid(a.heatmap) }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun CcChartsTabletPreview() {
    com.dentifay.app.ui.PreviewEnv(pencere = com.dentifay.app.ui.PencereBilgisi.TABLET_YATAY) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val a = CreditPreviewData.analysis
            CcCard("Kredi kullanımı", "Gün gün, özelliğe göre") { CcUsageChart(a, a.features.map { it.feature }, yukseklik = 240.dp) }
            CcCard("Gün ve saate göre kullanım", "Kliniğin saatiyle (${a.tz}); koyu hücre daha çok kredi") { CcHeatmapGrid(a.heatmap) }
        }
    }
}
