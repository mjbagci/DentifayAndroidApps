package com.dentifay.app.credit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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

// MARK: - Kullanım grafiği (Swift Charts `BarMark` yığını → Canvas)

private data class CcChartPoint(val id: String, val date: LocalDate, val feature: String, val featureKey: String, val credits: Double)

/** Bilinmeyen özellik kimliği "Diğer"e düşmez: grafik alanı TEKİL etiket ister. */
private fun chartLabel(f: String): String = CreditLabels.features[f] ?: f

@Composable
internal fun CcUsageChart(usage: CreditUsage, order: List<String>) {
    val monthly = usage.unit == "month"
    val points = remember(usage, order) {
        val out = mutableListOf<CcChartPoint>()
        for (b in usage.series) {
            val d = CreditDates.bucketDate(b.key) ?: continue
            for (f in order) {
                val v = b.byFeature[f] ?: continue
                if (v == 0.0) continue
                out += CcChartPoint("${b.key}|$f", d, chartLabel(f), f, v)
            }
        }
        out
    }
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text("Bu dönemde kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = DS.Colors.onSurfaceVariant)
    val gridColor = Color.White.copy(alpha = 0.06f)

    // Eksen alanı: ilk noktanın gününden son noktanın gününe (birim: gün ya da ay).
    val minDate = points.minOf { it.date }
    val maxDate = points.maxOf { it.date }
    val slots = if (monthly) ChronoUnit.MONTHS.between(minDate.withDayOfMonth(1), maxDate.withDayOfMonth(1)).toInt() + 1
    else ChronoUnit.DAYS.between(minDate, maxDate).toInt() + 1
    fun slotOf(d: LocalDate): Int =
        if (monthly) ChronoUnit.MONTHS.between(minDate.withDayOfMonth(1), d.withDayOfMonth(1)).toInt()
        else ChronoUnit.DAYS.between(minDate, d).toInt()

    // Yığın: pozitifler yukarı, negatifler (iade ağırlıklı gün) aşağı.
    val bySlot = points.groupBy { slotOf(it.date) }
    var posMax = 0.0
    var negMin = 0.0
    bySlot.values.forEach { list ->
        posMax = max(posMax, list.filter { it.credits > 0 }.sumOf { it.credits })
        negMin = min(negMin, list.filter { it.credits < 0 }.sumOf { it.credits })
    }
    val ticks = remember(posMax, negMin) { niceTicks(negMin, posMax) }
    val yMin = ticks.first()
    val yMax = ticks.last()
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).apply { maximumFractionDigits = 2 } }
    val tickLabels = ticks.map { numberFormat.format(it) }

    // X etiketleri: ~5 tane, eşit aralıklı.
    val stride = max(1, ceil(slots / 5.0).toInt())
    val xTicks = (0 until slots step stride).toList()

    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(184.dp)) {
            val labelLayouts = tickLabels.map { measurer.measure(it, axisStyle) }
            val labelW = (labelLayouts.maxOfOrNull { it.size.width } ?: 0).toFloat()
            val left = labelW + 6.dp.toPx()
            val xLabelH = measurer.measure("0", axisStyle).size.height.toFloat()
            val bottom = size.height - xLabelH - 6.dp.toPx()
            val top = 6.dp.toPx()
            val plotW = size.width - left
            val plotH = bottom - top
            fun yOf(v: Double): Float = (top + plotH * (1 - (v - yMin) / (yMax - yMin))).toFloat()

            // Yatay ızgara + Y etiketleri (solda).
            ticks.forEachIndexed { i, t ->
                val y = yOf(t)
                drawLine(gridColor, Offset(left, y), Offset(size.width, y), 1.dp.toPx())
                val l = labelLayouts[i]
                drawText(l, topLeft = Offset(labelW - l.size.width, y - l.size.height / 2f))
            }

            val slotW = plotW / slots
            // Dikey ızgara + X etiketleri.
            xTicks.forEach { s ->
                val x = left + slotW * s
                drawLine(gridColor, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                val date = if (monthly) minDate.withDayOfMonth(1).plusMonths(s.toLong()) else minDate.plusDays(s.toLong())
                val l = measurer.measure(CreditDates.axisLabel(date, monthly), axisStyle)
                val lx = min(x + 2.dp.toPx(), size.width - l.size.width)
                drawText(l, topLeft = Offset(lx, bottom + 4.dp.toPx()))
            }

            // Çubuklar (özellik sırasıyla yığılır).
            val barW = slotW * 0.7f
            bySlot.forEach { (s, list) ->
                val x = left + slotW * s + (slotW - barW) / 2
                var posAcc = 0.0
                var negAcc = 0.0
                order.forEach { f ->
                    val p = list.firstOrNull { it.featureKey == f } ?: return@forEach
                    val color = CreditPalette.color(f, order)
                    if (p.credits > 0) {
                        val y0 = yOf(posAcc)
                        val y1 = yOf(posAcc + p.credits)
                        drawRect(color, Offset(x, y1), Size(barW, y0 - y1))
                        posAcc += p.credits
                    } else {
                        val y0 = yOf(negAcc)
                        val y1 = yOf(negAcc + p.credits)
                        drawRect(color, Offset(x, y0), Size(barW, y1 - y0))
                        negAcc += p.credits
                    }
                }
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

// MARK: - Gün × saat ısı haritası

@Composable
internal fun CcHeatmapGrid(cells: List<List<CreditUsage.Cell>>) {
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val maxV = cells.flatten().maxOfOrNull { it.credits } ?: 0.0
    val weekdays = CreditLabels.weekdays

    fun isSelected(d: Int, h: Int) = selected?.first == d && selected?.second == h

    /** Karekök ölçek (web `isiYogunlugu`): tek yoğun saat diğerlerini görünmez yapmasın. */
    fun fill(v: Double): Color {
        if (v <= 0 || maxV <= 0) return Color.White.copy(alpha = 0.05f)
        val i = min(1.0, sqrt(v / maxV))
        return DS.Colors.cyan400.copy(alpha = (0.15 + 0.85 * i).toFloat())
    }

    val selectedText: String? = selected?.let { s ->
        if (s.first >= cells.size || s.second >= cells[s.first].size) return@let null
        val c = cells[s.first][s.second]
        val day = if (s.first < weekdays.size) weekdays[s.first] else ""
        "$day ${"%02d".format(s.second)}:00 · ${creditText(c.credits)} kredi · ${c.ops} işlem"
    }
    val peakText: String? = run {
        var best: Triple<Int, Int, Double>? = null
        cells.forEachIndexed { d, row ->
            row.forEachIndexed { h, c -> if (c.credits > (best?.third ?: 0.0)) best = Triple(d, h, c.credits) }
        }
        val b = best
        if (b == null || b.first >= weekdays.size) null
        else "En yoğun: ${weekdays[b.first]} ${"%02d".format(b.second)}:00, ${creditText(b.third)} kredi"
    }

    val rowH = 15.dp
    val rowGap = 3.dp
    val cellGap = 2.dp
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Gün ve saate göre kredi kullanımı"
            stateDescription = peakText ?: "Kullanım yok"
        },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
            Column(Modifier.width(28.dp), verticalArrangement = Arrangement.spacedBy(rowGap)) {
                cells.indices.forEach { d ->
                    Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                        Text(if (d < weekdays.size) weekdays[d] else "", style = sys(10, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
                    }
                }
            }
            val gridH = rowH * cells.size + rowGap * max(0, cells.size - 1)
            Canvas(
                Modifier
                    .weight(1f)
                    .height(gridH)
                    .pointerInput(cells) {
                        detectTapGestures { pos ->
                            val rowPitch = (rowH + rowGap).toPx()
                            val d = (pos.y / rowPitch).toInt().coerceIn(0, max(0, cells.size - 1))
                            val n = cells.getOrNull(d)?.size ?: 0
                            if (n == 0) return@detectTapGestures
                            val gap = cellGap.toPx()
                            val cw = (size.width - gap * (n - 1)) / n
                            val h = (pos.x / (cw + gap)).toInt().coerceIn(0, n - 1)
                            selected = if (isSelected(d, h)) null else d to h
                        }
                    },
            ) {
                val gap = cellGap.toPx()
                val rh = rowH.toPx()
                val pitch = (rowH + rowGap).toPx()
                val r = CornerRadius(2.dp.toPx())
                cells.forEachIndexed { d, row ->
                    val n = row.size
                    if (n == 0) return@forEachIndexed
                    val cw = (size.width - gap * (n - 1)) / n
                    row.forEachIndexed { h, cell ->
                        val tl = Offset(h * (cw + gap), d * pitch)
                        drawRoundRect(fill(cell.credits), tl, Size(cw, rh), r)
                        if (isSelected(d, h)) {
                            drawRoundRect(Color.White, tl, Size(cw, rh), r, style = Stroke(1.dp.toPx()))
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
            style = sys(12, if (selected == null) FontWeight.Normal else FontWeight.SemiBold),
            color = if (selected == null) DS.Colors.onSurfaceVariant else DS.Colors.onSurface,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CcChartsPreview() {
    com.dentifay.app.ui.PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val u = CreditPreviewData.usage
            CcCard("Kredi kullanımı", "Gün gün, özelliğe göre (iade düşülmüş)") { CcUsageChart(u, u.features.map { it.feature }) }
            CcCard("Gün ve saate göre kullanım", "Kliniğin saatiyle (${u.tz}); koyu hücre daha çok kredi") { CcHeatmapGrid(u.heatmap) }
        }
    }
}
