@file:Suppress("ClassName")

package com.dentifay.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlin.math.max

/**
 * Ajan yanıtları için hafif markdown çizici: satır içi biçimli paragraflar, başlıklar, madde listeleri, çitli kod ve GFM
 * tabloları (SQL sonuçları böyle gelir). Tam CommonMark değil — bilerek küçük ve sağlam.
 *
 * Asistanın serbest metni DÖNÜŞTÜRÜLMEZ — sunucunun cümlesi AYNEN (M-E1.7A.2, M0-6).
 * Akış sırasında metin her olayda büyür: ayrıştırma metne göre hatırlanır, değişmeyen bloklar yeniden çizilmez
 * (bloklar `@Immutable` — eşitse atlanır), tablo tek çizim düğümüdür.
 */
@Composable
fun MarkdownMessageView(text: String, modifier: Modifier = Modifier, textColor: Color = DS.Colors.onSurface) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> BlockView(block, textColor) }
    }
}

private val paragrafStili = sys(16)
private val kodStili = sys(13).copy(fontFamily = FontFamily.Monospace)
private val baslikStilleri = listOf(sys(20, FontWeight.Bold), sys(18, FontWeight.Bold), sys(16, FontWeight.Bold))

@Composable
private fun BlockView(block: MarkdownParser.Block, textColor: Color) {
    when (block) {
        is MarkdownParser.Block.paragraph -> {
            val s = remember(block.text) { MarkdownParser.inline(block.text) }
            SelectionContainer { Text(s, style = paragrafStili, color = textColor) }
        }

        is MarkdownParser.Block.header -> {
            val s = remember(block.text) { MarkdownParser.inline(block.text) }
            Text(s, style = baslikStilleri[(block.level - 1).coerceIn(0, 2)], color = textColor)
        }

        is MarkdownParser.Block.bullets -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            block.items.forEach { item ->
                val s = remember(item) { MarkdownParser.inline(item) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", style = paragrafStili, color = DS.Colors.cyan400)
                    Text(s, style = paragrafStili, color = textColor)
                }
            }
        }

        is MarkdownParser.Block.code -> {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(block.text, Modifier.padding(12.dp), style = kodStili, color = DS.Colors.onSurface, softWrap = false)
            }
        }

        is MarkdownParser.Block.table -> TableView(block.headers, block.rows)
    }
}

// MARK: - Tablo (tek çizim düğümü)

private val tabloBaslikStili = sys(13, FontWeight.Bold).copy(color = DS.Colors.cyan400)
private val tabloHucreStili = sys(13).copy(color = DS.Colors.onSurfaceVariant)

/** Ölçülmüş tablo: hücre metinleri ve konumları (px) — çizim yalnız `drawText` + tek ayraç çizgisi. */
private class TabloDuzeni(
    val genislik: Dp,
    val yukseklik: Dp,
    private val hucreler: List<Pair<TextLayoutResult, Offset>>,
    private val ayracY: Float,
    private val ayracGenislik: Float,
    private val ayracKalinlik: Float,
    val aciklama: String,
) {
    fun ciz(scope: DrawScope) = with(scope) {
        hucreler.forEach { (layout, konum) -> drawText(layout, topLeft = konum) }
        drawRect(Color.White.copy(alpha = 0.1f), topLeft = Offset(0f, ayracY), size = Size(ayracGenislik, ayracKalinlik))
    }
}

/**
 * Swift `Grid` karşılığı: her sütun en geniş hücresi kadar, sütunlar arası 16 dp, satırlar arası 8 dp; başlık ile satırlar
 * arasında ince ayraç. Hücre ölçüleri `onbellek`te tutulur — akışta tablo büyürken yalnız yeni hücreler ölçülür.
 */
private fun tabloDuzeni(
    headers: List<String>,
    rows: List<List<String>>,
    measurer: TextMeasurer,
    onbellek: HashMap<String, TextLayoutResult>,
    density: Density,
): TabloDuzeni = with(density) {
    fun olc(metin: String, baslik: Boolean): TextLayoutResult = onbellek.getOrPut((if (baslik) "b\u0000" else "h\u0000") + metin) {
        measurer.measure(metin, style = if (baslik) tabloBaslikStili else tabloHucreStili, softWrap = false, maxLines = 1, density = density)
    }

    val sutunSayisi = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0, 1)
    val yatayBosluk = 16.dp.toPx()
    val dikeyBosluk = 8.dp.toPx()
    val ayrac = 1.dp.toPx()

    val baslikOlcu = headers.map { olc(it, true) }
    val satirOlcu = rows.map { r -> r.map { olc(it, false) } }
    val sutunGenislik = FloatArray(sutunSayisi)
    baslikOlcu.forEachIndexed { i, l -> sutunGenislik[i] = max(sutunGenislik[i], l.size.width.toFloat()) }
    satirOlcu.forEach { r -> r.forEachIndexed { i, l -> sutunGenislik[i] = max(sutunGenislik[i], l.size.width.toFloat()) } }
    val xler = FloatArray(sutunSayisi)
    for (i in 1 until sutunSayisi) xler[i] = xler[i - 1] + sutunGenislik[i - 1] + yatayBosluk
    val toplamGenislik = xler[sutunSayisi - 1] + sutunGenislik[sutunSayisi - 1]

    val hucreler = ArrayList<Pair<TextLayoutResult, Offset>>(baslikOlcu.size + satirOlcu.sumOf { it.size })
    var y = 0f
    val baslikYuk = baslikOlcu.maxOfOrNull { it.size.height.toFloat() } ?: 0f
    baslikOlcu.forEachIndexed { i, l -> hucreler += l to Offset(xler[i], y) }
    y += baslikYuk + dikeyBosluk
    val ayracY = y
    y += ayrac
    satirOlcu.forEach { r ->
        y += dikeyBosluk
        val yuk = r.maxOfOrNull { it.size.height.toFloat() } ?: 0f
        r.forEachIndexed { i, l -> hucreler += l to Offset(xler[i], y) }
        y += yuk
    }
    val aciklama = buildString {
        append(headers.joinToString(", "))
        rows.forEach { append(". "); append(it.joinToString(", ")) }
    }
    TabloDuzeni(
        genislik = (toplamGenislik + 1f).toDp(),
        yukseklik = y.toDp(),
        hucreler = hucreler,
        ayracY = ayracY,
        ayracGenislik = toplamGenislik,
        ayracKalinlik = ayrac,
        aciklama = aciklama,
    )
}

@Composable
private fun TableView(headers: List<String>, rows: List<List<String>>) {
    val shape = RoundedCornerShape(12.dp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val onbellek = remember(density) { HashMap<String, TextLayoutResult>() }
    val duzen = remember(headers, rows, density) { tabloDuzeni(headers, rows, measurer, onbellek, density) }
    Box(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .horizontalScroll(rememberScrollState())
    ) {
        Box(
            Modifier
                .padding(12.dp)
                .size(duzen.genislik, duzen.yukseklik)
                .semantics { contentDescription = duzen.aciklama }
                .drawBehind { duzen.ciz(this) }
        )
    }
}

/** Blok ayrıştırıcı + satır içi (kalın/italik/kod/bağlantı/üstü çizili) biçimleyici. */
object MarkdownParser {

    @Immutable
    sealed class Block {
        data class paragraph(val text: String) : Block()
        data class header(val level: Int, val text: String) : Block()
        data class bullets(val items: List<String>) : Block()
        data class code(val text: String) : Block()
        data class table(val headers: List<String>, val rows: List<List<String>>) : Block()
    }

    // MARK: - Inline

    /** Swift `AttributedString(markdown:, .inlineOnlyPreservingWhitespace)` karşılığı. */
    fun inline(s: String): AnnotatedString = buildAnnotatedString { appendInline(this, s) }

    private fun appendInline(b: AnnotatedString.Builder, s: String) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                b.append(plain.toString()); plain.setLength(0)
            }
        }
        while (i < s.length) {
            val c = s[i]
            // Kaçış karakteri
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_[]()#~-|!") {
                plain.append(s[i + 1]); i += 2; continue
            }
            // `kod`
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                    i = end + 1; continue
                }
            }
            // **kalın** / __kalın__
            if ((c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c) {
                val marker = "$c$c"
                val end = s.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(this, s.substring(i + 2, end)) }
                    i = end + 2; continue
                }
            }
            // ~~üstü çizili~~
            if (c == '~' && i + 1 < s.length && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(this, s.substring(i + 2, end)) }
                    i = end + 2; continue
                }
            }
            // *italik* / _italik_ (kelime içi alt çizgi italik sayılmaz)
            if (c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()))) {
                val end = s.indexOf(c, i + 1)
                if (end > i + 1 && !s[i + 1].isWhitespace() && !s[end - 1].isWhitespace() &&
                    (c != '_' || end + 1 >= s.length || !s[end + 1].isLetterOrDigit())
                ) {
                    flush()
                    b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(this, s.substring(i + 1, end)) }
                    i = end + 1; continue
                }
            }
            // [bağlantı](url)
            if (c == '[') {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                    val paren = s.indexOf(')', close + 2)
                    if (paren > close) {
                        flush()
                        b.withStyle(SpanStyle(color = DS.Colors.cyan400, textDecoration = TextDecoration.Underline)) {
                            appendInline(this, s.substring(i + 1, close))
                        }
                        i = paren + 1; continue
                    }
                }
            }
            plain.append(c); i++
        }
        flush()
    }

    // MARK: - Block parsing

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (!t.contains("-") || !t.contains("|")) return false
        return t.all { it in "-|: " }
    }

    private fun splitRow(line: String): List<String> {
        var l = line.trim()
        if (l.startsWith("|")) l = l.drop(1)
        if (l.endsWith("|")) l = l.dropLast(1)
        return l.split("|").map { it.trim() }
    }

    private fun isBullet(t: String) = t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")

    fun parse(text: String): List<Block> {
        val lines = text.split("\n")
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++; continue
            }

            // Çitli kod bloğu
            if (trimmed.startsWith("```")) {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code += lines[i]; i++
                }
                i++ // kapanış çiti
                blocks += Block.code(code.joinToString("\n"))
                continue
            }

            // GFM tablosu: başlık satırı + ayraç satırı
            if (trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val headers = splitRow(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                    rows += splitRow(lines[i]); i++
                }
                blocks += Block.table(headers, rows)
                continue
            }

            // Başlık
            if (trimmed.startsWith("#")) {
                val hashes = trimmed.takeWhile { it == '#' }.length
                val content = trimmed.dropWhile { it == '#' }.trim()
                blocks += Block.header(minOf(hashes, 3), content)
                i++
                continue
            }

            // Madde listesi
            if (isBullet(trimmed)) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (isBullet(t)) {
                        items += t.drop(2); i++
                    } else {
                        break
                    }
                }
                blocks += Block.bullets(items)
                continue
            }

            // Paragraf (ardışık düz satırlar birleştirilir)
            val para = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") || isBullet(t) ||
                    (t.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]))
                ) {
                    break
                }
                para += t; i++
            }
            if (para.isNotEmpty()) blocks += Block.paragraph(para.joinToString(" "))
        }
        return blocks
    }
}

private const val ORNEK_YANIT = """
## Bu ayın cirosu
Toplam ciro **184.250 TL**; geçen aya göre *%12* artış. En yüksek pay `implant` tedavilerinde.
- İmplant: 62.000 TL
- Kanal tedavisi: 31.400 TL

| Hekim | Ciro | Hasta |
|---|---|---|
| Ayşe Demir | 72.300 TL | 41 |
| Mehmet Kaya | 58.900 TL | 37 |

```
SELECT SUM(amount) FROM payments;
```
"""

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun MarkdownMessageViewPreview() {
    PreviewEnv { MarkdownMessageView(ORNEK_YANIT.trimIndent(), Modifier.padding(16.dp)) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun MarkdownMessageViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Box(Modifier.padding(24.dp)) { MarkdownMessageView(ORNEK_YANIT.trimIndent()) }
    }
}
