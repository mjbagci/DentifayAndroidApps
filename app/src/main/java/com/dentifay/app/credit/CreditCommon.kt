package com.dentifay.app.credit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import kotlin.math.max

/** Araç çubuğu ve bağlantı vurgusu: diğer sayfalar gibi marka camgöbeği (iOS'taki sistem mavisi Android'de yabancı duruyordu). */
internal val CreditVurgu get() = DS.Colors.cyan400

/**
 * `NavigationStack` + `.navigationTitle(…)` + `.navigationBarTitleDisplayMode(.inline)` başlık şeridi:
 * ortada başlık, solda iptal, sağda eylem düğmesi.
 */
@Composable
internal fun CreditNavBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
    ) {
        // Uzun ad başlığı kesmesin: kim olduğu ikinci satırda (telefonda "…aylık kredi li…" diye kırpılıyordu).
        Column(Modifier.align(Alignment.Center).padding(horizontal = 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = sys(if (subtitle == null) 17 else 15, FontWeight.SemiBold), color = DS.Colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** Araç çubuğu metin düğmesi (`Button("Kapat")` · `.bold()`). */
@Composable
internal fun CreditBarButton(text: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .semantics { role = Role.Button }
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        style = sys(17, if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = CreditVurgu,
        maxLines = 1,
    )
}

/** `Label(text, dfy: icon)` — ikon + metin, boyut yazı tipinden. */
@Composable
internal fun CreditIconLabel(
    text: String,
    icon: String,
    modifier: Modifier = Modifier,
    size: Float = 13f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = DS.Colors.onSurface,
    iconColor: Color = color,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((size * 0.5f).coerceAtLeast(6f).dp), verticalAlignment = verticalAlignment) {
        DfyIcon(icon, size = size.dp, tint = iconColor)
        Text(text, style = sys(size, weight), color = color)
    }
}

/** `ProgressView()` (dönen gösterge). */
@Composable
internal fun CreditSpinner(modifier: Modifier = Modifier, color: Color = DS.Colors.onSurfaceVariant, size: Dp = 20.dp) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
}

/** `ProgressView(value:)` — ince doğrusal çubuk (tek çizim; yerleşim yok). */
@Composable
internal fun CreditProgressBar(fraction: Double, tint: Color, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(IlerlemeZemini, cornerRadius = r)
                if (f > 0f) drawRoundRect(tint, size = Size(size.width * f, size.height), cornerRadius = r)
            },
    )
}

private val IlerlemeZemini = Color.White.copy(alpha = 0.14f)

/** `Divider().overlay(Color.white.opacity(a))` */
@Composable
internal fun CreditDivider(alpha: Float = 0.06f) {
    HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = alpha))
}

/**
 * `.contentTransition(.numericText())` + `.minimumScaleFactor` — değer değişince dikey kayarak solar; dar yerde
 * yazı (en çok `minScale`e dek) küçülür, kesilmez.
 */
@Composable
internal fun CreditNumericText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.6f,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(Motion.spring(0.4, 0.85)) { it / 2 } + fadeIn(Motion.easeOut(250)))
                .togetherWith(slideOutVertically(Motion.spring(0.4, 0.85)) { -it / 2 } + fadeOut(Motion.easeOut(200)))
                .using(SizeTransform(clip = false))
        },
        modifier = modifier,
        label = "numericText",
    ) { t ->
        SigdirMetin(t, style, color, minScale = minScale)
    }
}

/**
 * Tek satır, sığmazsa küçülen metin (SwiftUI `.lineLimit(1).minimumScaleFactor(x)`). Taşma ilk yerleşimde ölçülür,
 * yazı adım adım küçültülür; sığana dek çizilmez (titreme yok). İçsel ölçüm (IntrinsicSize) destekler.
 */
@Composable
internal fun SigdirMetin(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.6f,
    textAlign: TextAlign? = null,
) {
    var olcek by remember(text, style) { mutableFloatStateOf(1f) }
    var hazir by remember(text, style) { mutableStateOf(false) }
    Text(
        text,
        modifier.drawWithContent { if (hazir) drawContent() },
        style = if (olcek < 1f) style.copy(fontSize = style.fontSize * olcek) else style,
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { r ->
            if (r.didOverflowWidth && olcek > minScale) olcek = max(minScale, olcek - 0.08f) else hazir = true
        },
    )
}

// MARK: - Tembel listede tek kart görünümü

private val KartZeminAlt = Color.White.copy(alpha = 0.03f)
private val KartZemin = hex("141823").copy(alpha = 0.92f)
private val KartKenar = Color.White.copy(alpha = 0.08f)
private val KartAyirac = Color.White.copy(alpha = 0.06f)

/**
 * `LazyVStack { … }.glassCard()` karşılığı: tembel listedeki her satır, ortak cam kartın KENDİ dilimini çizer
 * (ilk satır üst köşeler, son satır alt köşeler; aradakiler düz kenar). Satır aralarında Swift'teki ince ayraç
 * (`Divider`, yatay iç boşluk kadar girintili). Tek çizim — ek yerleşim düğümü yok.
 */
internal fun Modifier.kartDilimi(
    ilk: Boolean,
    son: Boolean,
    yaricap: Dp = 18.dp,
    ayiracGirinti: Dp = 14.dp,
): Modifier = this.drawBehind {
    val r = yaricap.toPx()
    val kenar = 1.dp.toPx()
    // İlk/son değilse yuvarlak köşeler dilimin dışına taşırılır ve kırpılır → düz kenar.
    val ust = if (ilk) 0f else -2 * r
    val alt = if (son) size.height else size.height + 2 * r
    clipRect {
        val cr = CornerRadius(r)
        drawRoundRect(KartZeminAlt, Offset(0f, ust), Size(size.width, alt - ust), cr)
        drawRoundRect(KartZemin, Offset(0f, ust), Size(size.width, alt - ust), cr)
        drawRoundRect(
            KartKenar,
            Offset(kenar / 2, ust + kenar / 2),
            Size(size.width - kenar, alt - ust - kenar),
            CornerRadius(max(0f, r - kenar / 2)),
            style = Stroke(kenar),
        )
    }
    if (!son) {
        val g = ayiracGirinti.toPx()
        val k = 0.5.dp.toPx()
        drawLine(KartAyirac, Offset(g, size.height - k / 2), Offset(size.width - g, size.height - k / 2), strokeWidth = k)
    }
}

// MARK: - Yerleşim yardımcıları

/**
 * Sol metin + sağ değer satırı (`HStack(alignment: .firstTextBaseline) { sol; Spacer(); sağ }`): sağ parça en çok
 * genişliğin `sagEnFazla` oranını alır ve sarılır; sol kalan yeri alır. İlk satır taban çizgileri hizalanır.
 */
@Composable
internal fun SolSagSatir(
    modifier: Modifier = Modifier,
    sagEnFazla: Float = 0.55f,
    bosluk: Dp = 8.dp,
    sol: @Composable () -> Unit,
    sag: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { sol() }
            Box { sag() }
        },
        modifier = modifier,
    ) { olculer, c ->
        val boslukPx = bosluk.roundToPx()
        val genislik = if (c.hasBoundedWidth) c.maxWidth else Constraints.Infinity
        val sagMax = if (c.hasBoundedWidth) (genislik * sagEnFazla).toInt() else Constraints.Infinity
        val sagP = olculer[1].measure(Constraints(maxWidth = sagMax))
        val solMax = if (c.hasBoundedWidth) (genislik - sagP.width - boslukPx).coerceAtLeast(0) else Constraints.Infinity
        val solP = olculer[0].measure(Constraints(maxWidth = solMax))
        val solB = solP[FirstBaseline]
        val sagB = sagP[FirstBaseline]
        val (solY, sagY) = if (solB != androidx.compose.ui.layout.AlignmentLine.Unspecified && sagB != androidx.compose.ui.layout.AlignmentLine.Unspecified) {
            val taban = max(solB, sagB)
            (taban - solB) to (taban - sagB)
        } else 0 to 0
        val w = if (c.hasBoundedWidth) genislik else solP.width + boslukPx + sagP.width
        val h = max(solY + solP.height, sagY + sagP.height)
        layout(w, h) {
            solP.place(0, solY)
            sagP.place(w - sagP.width, sagY)
        }
    }
}

/**
 * Eşit yükseklikli satırlarla ızgara (kısa listeler: göstergeler, paketler). Hücreler satırda eşit genişlikte ve
 * satırın en uzun hücresi kadar yüksek; son satırın boş yerleri doldurulmaz.
 */
@Composable
internal fun <T> EsitIzgara(
    ogeler: List<T>,
    sutun: Int,
    modifier: Modifier = Modifier,
    bosluk: Dp = 10.dp,
    hucre: @Composable (T, Modifier) -> Unit,
) {
    val n = max(1, sutun)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(bosluk)) {
        ogeler.chunked(n).forEach { satir ->
            EsitSatir(satir, n, bosluk, hucre)
        }
    }
}

/** Izgaranın tek satırı (tembel listede satır satır da kullanılır). */
@Composable
internal fun <T> EsitSatir(
    satir: List<T>,
    sutun: Int,
    bosluk: Dp,
    hucre: @Composable (T, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(bosluk),
    ) {
        satir.forEach { hucre(it, Modifier.weight(1f).fillMaxHeight()) }
        repeat(max(0, sutun - satir.size)) { Spacer(Modifier.weight(1f)) }
    }
}

/** Yuvarlak köşeli zemin + kenar (Swift `.background(x, in: Capsule()).overlay(Capsule().stroke(...))`). */
internal fun Modifier.hapZemin(zemin: Color, kenar: Color? = null): Modifier = this
    .clip(RoundedCornerShape(50))
    .background(zemin)
    .then(if (kenar != null) Modifier.drawBehind {
        val k = 1.dp.toPx()
        drawRoundRect(kenar, Offset(k / 2, k / 2), Size(size.width - k, size.height - k), CornerRadius(size.height / 2), style = Stroke(k))
    } else Modifier)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCommonPreview() {
    com.dentifay.app.ui.PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CreditNavBar("Krediler", trailing = { CreditBarButton("Kapat") {} })
            CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold)
            CreditProgressBar(0.6, DS.Colors.tertiaryContainer)
            CreditNumericText("1.250.000,5", sys(44, FontWeight.Bold), DS.Colors.onSurface, Modifier.fillMaxWidth())
            SolSagSatir(sol = { Text("Röntgen analizi", style = sys(13), color = DS.Colors.onSurface) }) {
                Text("21.10.2026 tarihinden itibaren kredi kullanacak; şu an ücretsiz.", style = sys(12), color = DS.Colors.cyan400, textAlign = TextAlign.End)
            }
            Column {
                listOf("Birinci", "İkinci", "Üçüncü").forEachIndexed { i, s ->
                    Text(s, Modifier.fillMaxWidth().kartDilimi(ilk = i == 0, son = i == 2).padding(horizontal = 14.dp, vertical = 11.dp), color = DS.Colors.onSurface)
                }
            }
            CreditSpinner(color = DS.Colors.cyan400)
        }
    }
}
