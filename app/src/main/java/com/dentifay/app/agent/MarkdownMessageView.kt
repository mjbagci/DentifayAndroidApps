package com.dentifay.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv

/**
 * Ajan yanıtları için hafif markdown çizici: satır içi biçimli paragraflar, başlıklar, madde listeleri,
 * çitli kod ve GFM tabloları (SQL sonuçları böyle gelir). Tam CommonMark değil — bilerek küçük ve sağlam.
 */
@Composable
fun MarkdownMessageView(text: String, modifier: Modifier = Modifier, textColor: Color = DS.Colors.onSurface) {
    // Asistan yanıtındaki hekim adları da "Dt." gösterilir (veride "Dr." kalmış olabilir).
    val blocks = remember(text) { MarkdownParser.parse(Unvan.dt(text)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> BlockView(block, textColor) }
    }
}

@Composable
private fun BlockView(block: MarkdownParser.Block, textColor: Color) {
    when (block) {
        is MarkdownParser.Block.paragraph -> SelectionContainer {
            Text(MarkdownParser.inline(block.text), style = sys(16), color = textColor)
        }

        is MarkdownParser.Block.header -> Text(
            MarkdownParser.inline(block.text),
            style = sys(if (block.level <= 1) 20 else if (block.level == 2) 18 else 16, FontWeight.Bold),
            color = textColor,
        )

        is MarkdownParser.Block.bullets -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", style = sys(16), color = DS.Colors.cyan400)
                    Text(MarkdownParser.inline(item), style = sys(16), color = textColor)
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
                Text(
                    block.text, Modifier.padding(12.dp),
                    style = sys(13).copy(fontFamily = FontFamily.Monospace), color = DS.Colors.onSurface, softWrap = false,
                )
            }
        }

        is MarkdownParser.Block.table -> TableView(block.headers, block.rows)
    }
}

@Composable
private fun TableView(headers: List<String>, rows: List<List<String>>) {
    val shape = RoundedCornerShape(12.dp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = sys(13, FontWeight.Bold)
    val cellStyle = sys(13)
    val columnCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0, 1)
    // Grid karşılığı: her sütunun genişliği en geniş hücresi kadar.
    val widths = remember(headers, rows) {
        (0 until columnCount).map { c ->
            val hw = headers.getOrNull(c)?.let { measurer.measure(it, headerStyle).size.width } ?: 0
            val cw = rows.maxOfOrNull { r -> r.getOrNull(c)?.let { measurer.measure(it, cellStyle).size.width } ?: 0 } ?: 0
            with(density) { (maxOf(hw, cw) + 1).toDp() }
        }
    }
    val hSpacing = 16.dp
    val totalWidth = widths.fold(0.dp) { acc, w -> acc + w } + hSpacing * (columnCount - 1)

    Box(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .horizontalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(hSpacing)) {
                headers.forEachIndexed { i, h ->
                    Text(h, Modifier.width(widths[i]), style = headerStyle, color = DS.Colors.cyan400, softWrap = false)
                }
            }
            Box(Modifier.width(totalWidth).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(hSpacing)) {
                    row.forEachIndexed { i, cell ->
                        Text(cell, Modifier.width(widths[i]), style = cellStyle, color = DS.Colors.onSurfaceVariant, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Blok ayrıştırıcı + satır içi (kalın/italik/kod/bağlantı/üstü çizili) biçimleyici. */
object MarkdownParser {

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
        fun flush() { if (plain.isNotEmpty()) { b.append(plain.toString()); plain.setLength(0) } }
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

            if (trimmed.isEmpty()) { i++; continue }

            // Çitli kod bloğu
            if (trimmed.startsWith("```")) {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
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
                    if (isBullet(t)) { items += t.drop(2); i++ } else break
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
                ) break
                para += t; i++
            }
            if (para.isNotEmpty()) blocks += Block.paragraph(para.joinToString(" "))
        }
        return blocks
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun MarkdownMessageViewPreview() {
    PreviewEnv {
        MarkdownMessageView(
            """
            ## Bu ayın cirosu
            Toplam ciro **184.250 TL**; geçen aya göre *%12* artış. Dr. Ayşe Demir en yüksek payda.
            - İmplant: 62.000 TL
            - Kanal tedavisi: 31.400 TL

            | Hekim | Ciro | Hasta |
            |---|---|---|
            | Dt. Ayşe Demir | 72.300 TL | 41 |
            | Dt. Mehmet Kaya | 58.900 TL | 37 |

            ```
            SELECT SUM(amount) FROM payments;
            ```
            """.trimIndent(),
            Modifier.padding(16.dp),
        )
    }
}
