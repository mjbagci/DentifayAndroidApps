package com.dentifay.app.agent

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.sys
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

/** Ajanın `chart_spec`ini (çubuk / çizgi / pasta) çizer — Swift Charts yerine Compose `Canvas`. */
@Composable
fun AgentChartView(spec: AgentChartSpec, modifier: Modifier = Modifier) {
    val progress = remember(spec.id) { Animatable(0f) }
    LaunchedEffect(spec.id) { progress.animateTo(1f, Motion.easeOut(800)) }

    val seriesDefs = remember(spec) {
        if (spec.series.isNotEmpty()) spec.series
        else listOf(AgentChartSpec.Series(spec.valueKey ?: "value", spec.title ?: ""))
    }
    val points = remember(spec) {
        val xKey = spec.xKey ?: spec.nameKey ?: "label"
        spec.rows.flatMap { row ->
            val x = row[xKey]?.stringValue ?: ""
            seriesDefs.mapNotNull { s -> row[s.key]?.doubleValue?.let { y -> ChartPoint(x, s.label, y) } }
        }
    }
    val slices = remember(spec) {
        val nameKey = spec.nameKey ?: spec.xKey ?: "label"
        val valueKey = spec.valueKey ?: spec.series.firstOrNull()?.key ?: "value"
        spec.rows.mapNotNull { row ->
            row[valueKey]?.doubleValue?.let { v -> ChartSlice(row[nameKey]?.stringValue ?: "", v) }
        }
    }

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
        Column(Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val measurer = rememberTextMeasurer()
            when (spec.type.lowercase()) {
                "pie", "donut" -> {
                    Canvas(Modifier.fillMaxWidth().weight(1f)) { drawPie(slices, progress.value) }
                    Legend(slices.map { it.name }.distinct())
                }
                "line", "area" -> {
                    Canvas(Modifier.fillMaxWidth().weight(1f)) { drawXY(points, progress.value, measurer, line = true) }
                    if (seriesDefs.size > 1) Legend(seriesDefs.map { it.label })
                }
                else -> {
                    Canvas(Modifier.fillMaxWidth().weight(1f)) { drawXY(points, progress.value, measurer, line = false) }
                    if (seriesDefs.size > 1) Legend(seriesDefs.map { it.label })
                }
            }
        }
    }
}

private data class ChartPoint(val x: String, val series: String, val y: Double)
private data class ChartSlice(val name: String, val value: Double)

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

private val axisFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).apply { maximumFractionDigits = 2 }

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

/** Çubuk (seriler üst üste yığılır — Swift Charts varsayılanı) ya da çizgi + nokta grafiği. */
private fun DrawScope.drawXY(points: List<ChartPoint>, progress: Float, measurer: TextMeasurer, line: Boolean) {
    if (points.isEmpty()) return
    val categories = points.map { it.x }.distinct()
    val seriesNames = points.map { it.series }.distinct()
    val labelStyle = TextStyle(fontSize = sys(10).fontSize, color = DS.Colors.onSurfaceVariant)

    // Y alanı: çubukta yığın toplamları, çizgide tekil değerler; 0 her zaman dahil.
    val values: List<Double> = if (line) points.map { it.y } else categories.flatMap { c ->
        val pos = points.filter { it.x == c && it.y > 0 }.sumOf { it.y }
        val neg = points.filter { it.x == c && it.y < 0 }.sumOf { it.y }
        listOf(pos, neg)
    }
    var lo = min(0.0, values.minOrNull() ?: 0.0)
    var hi = max(0.0, values.maxOrNull() ?: 0.0)
    if (hi == lo) hi = lo + 1
    val step = niceStep(hi - lo)
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step
    val ticks = generateSequence(lo) { it + step }.takeWhile { it <= hi + step * 0.001 }.toList()

    // Sağda Y etiketleri (Swift Charts varsayılanı), altta X etiketleri.
    val yLabelWidth = ticks.maxOf { measurer.measure(axisFormat.format(it), labelStyle).size.width }.toFloat()
    val xLabelHeight = measurer.measure("Ag", labelStyle).size.height.toFloat()
    val gap = 6.dp.toPx()
    val plotLeft = 0f
    val plotRight = size.width - yLabelWidth - gap
    val plotTop = xLabelHeight / 2
    val plotBottom = size.height - xLabelHeight - gap
    val plotW = max(1f, plotRight - plotLeft)
    val plotH = max(1f, plotBottom - plotTop)
    fun yPos(v: Double): Float = (plotBottom - ((v - lo) / (hi - lo)) * plotH).toFloat()

    // Kılavuz çizgileri + Y etiketleri
    ticks.forEach { t ->
        val y = yPos(t)
        drawLine(Color.White.copy(alpha = 0.06f), Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
        val layout = measurer.measure(axisFormat.format(t), labelStyle)
        drawText(layout, topLeft = Offset(plotRight + gap, y - layout.size.height / 2f))
    }

    // X etiketleri (bant ortası; sığmayanlar seyreltilir)
    val band = plotW / categories.size
    val maxLabelW = categories.maxOf { measurer.measure(it, labelStyle).size.width }.toFloat()
    val every = max(1, ceil((maxLabelW + 4.dp.toPx()) / band).toInt())
    categories.forEachIndexed { i, c ->
        if (i % every != 0) return@forEachIndexed
        val layout = measurer.measure(c, labelStyle)
        val cx = plotLeft + band * (i + 0.5f)
        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, plotBottom + gap))
    }

    val p = progress.toDouble()
    if (!line) {
        val barW = band * 0.6f
        val r = 4.dp.toPx()
        categories.forEachIndexed { i, c ->
            val cx = plotLeft + band * (i + 0.5f)
            var posBase = 0.0
            var negBase = 0.0
            seriesNames.forEachIndexed { si, s ->
                val v = points.firstOrNull { it.x == c && it.series == s }?.y ?: return@forEachIndexed
                val scaled = v * p
                val from: Double
                val to: Double
                if (scaled >= 0) { from = posBase; to = posBase + scaled; posBase = to } else { from = negBase; to = negBase + scaled; negBase = to }
                val top = min(yPos(from), yPos(to))
                val bottom = max(yPos(from), yPos(to))
                if (bottom - top < 0.5f) return@forEachIndexed
                val path = Path().apply {
                    addRoundRect(RoundRect(cx - barW / 2, top, cx + barW / 2, bottom, CornerRadius(min(r, (bottom - top) / 2))))
                }
                drawPath(path, colorAt(si))
            }
        }
    } else {
        seriesNames.forEachIndexed { si, s ->
            val pts = categories.mapIndexedNotNull { i, c ->
                points.firstOrNull { it.x == c && it.series == s }?.let { Offset(plotLeft + band * (i + 0.5f), yPos(it.y * p)) }
            }
            if (pts.isEmpty()) return@forEachIndexed
            val color = colorAt(si)
            drawPath(catmullRom(pts), color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            pts.forEach { pt -> drawCircle(color, radius = 3.dp.toPx(), center = pt) }
        }
    }
}

/** `.interpolationMethod(.catmullRom)` karşılığı yumuşak eğri. */
private fun catmullRom(pts: List<Offset>): Path {
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[min(pts.size - 1, i + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

/** Halka grafik: iç yarıçap %50, dilimler arası 1.5 dp boşluk. */
private fun DrawScope.drawPie(slices: List<ChartSlice>, progress: Float) {
    val total = slices.sumOf { abs(it.value) }
    if (total <= 0 || progress <= 0f) return
    val outer = min(size.width, size.height) / 2f
    val inner = outer * 0.5f
    val thickness = outer - inner
    val mid = inner + thickness / 2
    val center = Offset(size.width / 2, size.height / 2)
    val insetDeg = Math.toDegrees((1.5.dp.toPx() / mid).toDouble()).toFloat()
    var start = -90f
    val totalSweep = 360f * progress
    slices.forEachIndexed { i, s ->
        val sweep = (abs(s.value) / total * totalSweep).toFloat()
        val drawSweep = sweep - insetDeg
        if (drawSweep > 0f) {
            drawArc(
                colorAt(i), startAngle = start + insetDeg / 2, sweepAngle = drawSweep, useCenter = false,
                topLeft = Offset(center.x - mid, center.y - mid), size = Size(mid * 2, mid * 2),
                style = Stroke(width = thickness, cap = StrokeCap.Butt),
            )
        }
        start += sweep
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentChartViewPreview() {
    fun row(vararg pairs: Pair<String, Any>): Map<String, JSONValue> = pairs.associate { (k, v) ->
        k to (if (v is Number) JSONValue.number(v.toDouble()) else JSONValue.string(v.toString()))
    }
    val rows = listOf(row("ay" to "Oca", "ciro" to 120000, "gider" to 40000), row("ay" to "Şub", "ciro" to 145000, "gider" to 52000),
        row("ay" to "Mar", "ciro" to 132000, "gider" to 47000), row("ay" to "Nis", "ciro" to 168000, "gider" to 61000))
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AgentChartView(AgentChartSpec("bar", "Aylık ciro", "ay", null, null, "currency",
                listOf(AgentChartSpec.Series("ciro", "Ciro"), AgentChartSpec.Series("gider", "Gider")), rows))
            AgentChartView(AgentChartSpec("pie", "Tedavi dağılımı", null, "ay", "ciro", null, emptyList(), rows))
        }
    }
}
