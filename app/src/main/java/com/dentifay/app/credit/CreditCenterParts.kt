package com.dentifay.app.credit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.YetkiliButon
import com.dentifay.app.ui.YetkiliEylem
import com.dentifay.app.ui.yetkiliMi
import kotlin.math.max

// MARK: - Renk paleti (web'deki grafik paletiyle AYNI sıra: özellikler kredi azalan sırayla boyanır)

internal object CreditPalette {
    val colors: List<Color> = listOf("a78bfa", "22d3ee", "34d399", "fbbf24", "f472b6", "818cf8", "94a3b8").map { hex(it) }
    fun color(feature: String, order: List<String>): Color {
        val i = order.indexOf(feature)
        if (i < 0) return colors[colors.size - 1]
        return colors[i % colors.size]
    }
}

// MARK: - Ortak parçalar

@Composable
internal fun CcSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
}

@Composable
internal fun CcFootnote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = sys(11), color = DS.Colors.onSurfaceVariant)
}

@Composable
internal fun CcEmptyNote(text: String) {
    Box(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(horizontal = 16.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun CcLoadingBlock() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        CreditSpinner(color = DS.Colors.cyan400)
    }
}

@Composable
internal fun CcFilterChip(icon: String, text: String, active: Boolean, modifier: Modifier = Modifier) {
    val tint = if (active) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant
    Row(
        modifier
            .heightIn(min = 34.dp)
            .hapZemin(
                if (active) DS.Colors.cyan400.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f),
                if (active) DS.Colors.cyan400.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 11.dp, tint = tint)
        Text(
            text, Modifier.widthIn(max = 240.dp),
            style = sys(13, FontWeight.SemiBold), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        DfyIcon("chevron.down", Modifier.alpha(0.7f), size = 9.dp, tint = tint)
    }
}

/** `Menu { Button… }` öğesi; `checked` = `Picker` içindeki seçili satır. */
internal data class CcMenuItem(val title: String, val checked: Boolean = false, val action: () -> Unit)

/** `Menu { … } label: { FilterChip(…) }` */
@Composable
internal fun CcMenu(items: List<CcMenuItem>, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(Modifier.semantics { role = Role.DropdownList }.plainClickable { expanded = true }) { label() }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title, style = sys(16), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        item.action()
                    },
                    trailingIcon = if (item.checked) ({ DfyIcon("checkmark", size = 15.dp, tint = DS.Colors.onSurface) }) else null,
                )
            }
        }
    }
}

/** `Card(title:subtitle:)` — başlık, isteğe bağlı alt başlık, içerik; cam kart. */
@Composable
internal fun CcCard(title: String, subtitle: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
            if (subtitle != null) Text(subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant)
        }
        content()
    }
}

@Composable
internal fun CcBanner(icon: String, tint: Color, text: String, actionTitle: String? = null, action: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.1f))
            .border(1.dp, tint.copy(alpha = 0.3f), shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon(icon, size = 15.dp, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface)
            if (actionTitle != null && action != null) {
                Text(
                    actionTitle,
                    Modifier.semantics { role = Role.Button }.plainClickable(onClick = action),
                    style = sys(13, FontWeight.SemiBold), color = tint,
                )
            }
        }
    }
}

@Composable
internal fun CcErrorCard(title: String, text: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CreditIconLabel(title, "wifi.exclamationmark", size = 14f, weight = FontWeight.SemiBold, verticalAlignment = Alignment.CenterVertically)
        Text(text, style = sys(12), color = DS.Colors.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .semantics { role = Role.Button }
                .pressable(onClick = retry)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold, verticalAlignment = Alignment.CenterVertically)
        }
    }
}

/** "Daha fazla göster" metin düğmesi. */
@Composable
internal fun CcMoreButton(onClick: () -> Unit) {
    Text(
        "Daha fazla göster",
        Modifier.semantics { role = Role.Button }.plainClickable(onClick = onClick).padding(vertical = 6.dp),
        style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400,
    )
}

// MARK: - Genel Bakış parçaları

/** Dört gösterge — telefonda 2, geniş pencerede 4 sütun; satırdaki kutular eşit yükseklikte. */
@Composable
internal fun CcKpiGrid(wallet: SmileWallet, analysis: CreditAnalysis?, sutun: Int) {
    val s = analysis?.summary
    // 7 günden az kırmızı, 30'dan az turuncu (web `yeterlilikTonu` ile aynı eşikler).
    val runwayTint = when (val d = analysis?.runwayDays) {
        null -> DS.Colors.slate400
        else -> if (d < 7) DS.Colors.error else if (d < 30) DS.Colors.orange400 else DS.Colors.tertiaryContainer
    }
    val last30 = analysis?.last30DaysCredits ?: 0.0
    val kutular = listOf(
        KpiVeri(
            "creditcard.fill", DS.Colors.cyan400, "Kullanılabilir kredi", creditText(wallet.availableCredits),
            "Ücretsiz ${creditText(wallet.freeRemainingCredits)} · satın alınmış ${creditText(wallet.balanceCredits)}",
        ),
        KpiVeri(
            "chart.bar.fill", DS.Colors.accentViolet, "Dönemde kullanılan", s?.let { creditText(it.usedCredits) } ?: "—",
            s?.let { "${it.operations} işlem · iade ${creditText(it.refundedCredits)}" } ?: "",
        ),
        KpiVeri(
            "hourglass", runwayTint, "Kaç gün yeter", analysis?.runwayDays?.let { "$it gün" } ?: "—",
            if (last30 > 0) "Son 30 günde ${creditText(last30)} kredi kullanıldı" else "Son 30 günde kullanım yok",
        ),
        KpiVeri("arrow.down.circle.fill", DS.Colors.tertiaryContainer, "Dönemde satın alınan", s?.let { creditText(it.purchasedCredits) } ?: "—", purchasedDetail(s)),
    )
    EsitIzgara(kutular, sutun) { k, mod -> CcKpi(k, mod) }
}

internal class KpiVeri(val icon: String, val tint: Color, val title: String, val value: String, val detail: String)

/** TL yalnız sunucu verirse (`paidTL`); yoksa yalnız adet. */
private fun purchasedDetail(s: CreditAnalysis.Summary?): String {
    if (s == null || s.purchases <= 0) return "Bu dönemde satın alma yok"
    val tl = s.paidTL
    return if (tl != null) "${s.purchases} satın alma · ${SmileCreditFormat.lira(tl)}" else "${s.purchases} satın alma"
}

@Composable
private fun CcKpi(k: KpiVeri, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 112.dp)
            .glassCard(18.dp)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon(k.icon, size = 12.dp, tint = k.tint)
            Text(k.title, style = sys(11, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CreditNumericText(k.value, sys(24, FontWeight.Bold).monoDigits(), DS.Colors.onSurface, Modifier.fillMaxWidth())
        Text(k.detail, style = sys(10), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CcFeatureBreakdown(rows: List<CreditAnalysis.FeatureRow>, order: List<String>) {
    if (rows.isEmpty()) {
        Text("Bu aralıkta kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            // Pay sunucudan (`share`, %); mobil hesaplamaz.
            val share = (r.share ?: 0) / 100f
            val color = CreditPalette.color(r.feature, order)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(CreditLabels.feature(r.feature), Modifier.weight(1f), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                    Text("${creditText(r.credits)} kredi", style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface)
                }
                // Pay çubuğu — tek çizim (`Capsule` zemin + dolgu, en az 4).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .drawBehind {
                            val cr = CornerRadius(size.height / 2)
                            drawRoundRect(PayZemini, cornerRadius = cr)
                            val w = max(4.dp.toPx(), size.width * share.coerceIn(0f, 1f))
                            drawRoundRect(color, size = Size(w, size.height), cornerRadius = cr)
                        },
                )
                Text(
                    "${r.operations} işlem · %${r.share ?: 0}" + (if ((r.refunds ?: 0) > 0) " · ${r.refunds ?: 0} iade" else ""),
                    style = sys(11), color = DS.Colors.onSurfaceVariant,
                )
            }
        }
    }
}

private val PayZemini = Color.White.copy(alpha = 0.06f)

@Composable
internal fun CcUserBreakdown(rows: List<CreditAnalysis.UserRow>, onSelect: (String) -> Unit) {
    if (rows.isEmpty()) {
        Text("Bu aralıkta kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        return
    }
    Column {
        rows.forEachIndexed { i, r ->
            val id = r.userId?.takeIf { it.isNotEmpty() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (id != null) Modifier.semantics { role = Role.Button }.plainClickable { onSelect(id) } else Modifier)
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(DS.Colors.cyan400.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(ccInitials(r.user?.name), style = sys(12, FontWeight.Bold), color = DS.Colors.cyan400, maxLines = 1)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        r.user?.displayName ?: "Dentifay", style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(r.user?.departmentLabel, "${r.operations} işlem", r.lastAt?.let { "son: ${CreditDates.short(it)}" }).joinToString(" · "),
                        style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(creditText(r.credits), style = sys(15, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
                if (id != null) DfyIcon("chevron.right", size = 11.dp, tint = DS.Colors.slate500)
            }
            if (i != rows.lastIndex) CreditDivider()
        }
    }
}

private fun ccInitials(name: String?): String {
    val parts = (name ?: "").split(" ").filter { it.isNotEmpty() }
    val s = parts.take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    return if (s.isEmpty()) "·" else s.upperTr()
}

@Composable
internal fun CcActionList(rows: List<CreditAnalysis.ActionRow>) {
    if (rows.isEmpty()) {
        Text("Bu aralıkta kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(CreditLabels.operation(r.feature, r.action), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                    Text("${r.operations} işlem", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
                Text("${creditText(r.credits)} kredi", style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400)
            }
        }
    }
}

/** Aylık döküm — ay `YYYY-AA` → "Ekim 2026"; ₺ yalnız `purchasedTL > 0` iken; durum `durumMetni` kuralıyla. */
@Composable
internal fun CcMonthlyList(months: List<CreditUsage.Month>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        months.forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).alignBy(FirstBaseline), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(CreditDates.monthLabel(m.period), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                    m.durumMetni?.let { d ->
                        Text(d, style = sys(11), color = if (m.billed) DS.Colors.tertiaryContainer else DS.Colors.onSurfaceVariant)
                    }
                }
                Column(Modifier.alignBy(FirstBaseline), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${creditText(m.usedCredits)} kredi", style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface)
                    val tl = m.purchasedTL
                    if (tl != null && tl > 0) {
                        Text("Satın alınan paket ${SmileCreditFormat.lira(tl)}", style = sys(11), color = DS.Colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** e-Fatura kartı (Ek MO.21r; salt okur — Fatura Ayarları bağlantısı mobilde yok). */
@Composable
internal fun CcEDocumentCard(
    usage: EDocumentUsage?,
    error: String?,
    refreshError: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CcCard("e-Fatura", null, modifier) {
        if (usage != null) {
            if (usage.hesap == null) {
                Text("Aktif bir e-belge hesabınız yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    usage.buAy?.let { b ->
                        Text("Bu ay kesilen belge: ${b.toplam}", style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                        Text(
                            "e-Fatura ${b.turler["EFATURA"] ?: 0} · e-Arşiv ${b.turler["EARSIV"] ?: 0} · e-SMM ${b.turler["ESMM"] ?: 0}",
                            style = sys(12), color = DS.Colors.onSurfaceVariant,
                        )
                    }
                    usage.kontor?.let { k ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Kalan kontör: ${k.kalan?.let { creditText(it) } ?: "—"}", Modifier.weight(1f),
                                style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface,
                            )
                            Text(
                                "Yenile",
                                Modifier.semantics { role = Role.Button }.plainClickable(onClick = onRefresh).padding(vertical = 4.dp),
                                style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400,
                            )
                        }
                        k.okumaZamani?.let { z ->
                            Text("${CreditDates.short(z)} itibarıyla", style = sys(11), color = DS.Colors.outline)
                        }
                    }
                    if (usage.uyari == "KONTOR_AZ") {
                        Text(
                            "Kalan kontörünüz son 30 günde kestiğiniz belge sayısından az. e-Belge firmanızdan kontör yükleyin.",
                            style = sys(12), color = DS.Colors.orange400,
                        )
                    }
                    if (refreshError != null) Text(refreshError, style = sys(12), color = DS.Colors.error)
                    Text("Kontörünüzü e-belge firmanızdan satın alırsınız.", style = sys(11), color = DS.Colors.outline)
                }
            }
        } else if (error != null) {
            Text(error, style = sys(12), color = DS.Colors.error)
        } else {
            CreditSpinner(color = DS.Colors.cyan400)
        }
    }
}

/** "Kredi ile çalışan işlemler" (Ek MO.27e) — `/tarife`. */
@Composable
internal fun CcTariffList(tarife: KrediTarifesi?, hata: Boolean) {
    if (tarife != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tarife.tarife.forEach { s ->
                SolSagSatir(
                    sol = { Text(CreditLabels.operation(s.feature, s.action), style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface) },
                ) {
                    Text(tarife.listeMetni(s), style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400, textAlign = TextAlign.End)
                }
            }
        }
    } else if (hata) {
        Text("Liste yüklenemedi.", style = sys(12), color = DS.Colors.error)
    } else {
        CreditSpinner(color = DS.Colors.cyan400)
    }
}

// MARK: - Hareket satırı

/**
 * Hareket satırı. "Koşullar" bağlantısı yalnız `fin.credits` iken (Ek MO2.4). Tutar yalnız sunucu verirse (hekim ₺ görmez).
 * `modifier` tembel listede kart dilimini taşır.
 */
@Composable
internal fun CcLedgerRow(row: CreditTransactionsPage.Row, showTerms: Boolean, onTerms: () -> Unit, modifier: Modifier = Modifier) {
    val amountTint = if (row.credits > 0) DS.Colors.tertiaryContainer else DS.Colors.onSurface
    val kindTint = when (row.tur) {
        "iade", "satin_alma", "tanim" -> DS.Colors.tertiaryContainer
        "duzeltme" -> DS.Colors.orange400
        else -> DS.Colors.slate400
    }
    Row(
        // `.accessibilityElement(children: .combine)` — satır tek öğe okunur ("Koşullar" ayrı düğme kalır).
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.islemMetni, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text("${row.kisiMetni} · ${CreditDates.short(row.at)}", style = sys(12), color = DS.Colors.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    row.turMetni,
                    Modifier
                        .align(Alignment.CenterVertically)
                        .hapZemin(kindTint.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    style = sys(10, FontWeight.Bold), color = kindTint,
                )
                row.kaynakMetni?.let { Text(it, Modifier.align(Alignment.CenterVertically), style = sys(11), color = DS.Colors.slate400) }
                if (showTerms && row.kosulVar == true) {
                    Text(
                        "Koşullar",
                        Modifier
                            .align(Alignment.CenterVertically)
                            .semantics { role = Role.Button }
                            .plainClickable(onClick = onTerms),
                        style = sys(11, FontWeight.SemiBold), color = DS.Colors.cyan400,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.isaretliKredi, style = sys(15, FontWeight.Bold).monoDigits(), color = amountTint)
            row.tutarTL?.let { Text(SmileCreditFormat.lira(it), style = sys(11).monoDigits(), color = DS.Colors.onSurfaceVariant) }
        }
    }
}

// MARK: - Kişi kartı

@Composable
internal fun CcPersonCard(
    person: CreditPeople.Person,
    canEdit: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Eşit yükseklikli ızgara satırında: düğme kartın dibine iner. */
    esitYukseklik: Boolean = false,
) {
    val subtitle = run {
        val parts = mutableListOf<String>()
        person.user.departmentLabel?.let { parts += it }
        if (person.user.active == false && person.user.former != true) parts += "pasif"
        if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }
    Column(
        modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.user.displayName, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
                Text(subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${creditText(person.usedCredits)} kredi", style = sys(15, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
                Text("${person.operations} işlem", style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
        }

        val used = remember(person.byFeature) {
            person.byFeature.entries.filter { it.value.credits != 0.0 }.sortedByDescending { it.value.credits }
        }
        if (used.isNotEmpty()) {
            Text(
                used.joinToString(" · ") { "${CreditLabels.feature(it.key)} ${creditText(it.value.credits)}" },
                style = sys(12), color = DS.Colors.onSurfaceVariant,
            )
        }

        CreditDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aylık limit", Modifier.weight(1f), style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
            Text(
                person.limits.total?.let { "${creditText(it)} · Kalan ${person.remainingCredits?.let { r -> creditText(r) } ?: "—"}" } ?: "Sınırsız",
                style = sys(12, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface,
            )
        }
        person.limitOrani?.let { oran ->
            CreditProgressBar(oran, if (oran >= 1) DS.Colors.error else if (oran >= 0.8) DS.Colors.orange400 else DS.Colors.tertiaryContainer)
        }
        person.limits.features.entries.sortedBy { it.key }.forEach { (f, limit) ->
            Text(
                "${CreditLabels.feature(f)}: ${creditText(person.byFeature[f]?.credits ?: 0.0)} / ${creditText(limit)}",
                style = sys(11), color = DS.Colors.onSurfaceVariant,
            )
        }

        if (canEdit) {
            if (esitYukseklik) Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .semantics { contentDescription = "${person.user.displayName} · aylık kredi limiti"; role = Role.Button }
                    .pressable(onClick = onEdit)
                    .hapZemin(DS.Colors.cyan400.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                CreditIconLabel(
                    "Limiti düzenle", "slider.horizontal.3", Modifier.padding(vertical = 8.dp),
                    size = 13f, weight = FontWeight.SemiBold, color = DS.Colors.cyan400, verticalAlignment = Alignment.CenterVertically,
                )
            }
        }
    }
}

/** Kişiye atanmamış kullanım satırı. */
@Composable
internal fun CcUnattributedRow(un: CreditPeople.Unattributed) {
    Row(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon("questionmark.circle", tint = DS.Colors.slate400)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Kişiye atanmamış kullanım", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text("${un.operations} işlem · kişi limitine sayılmaz", style = sys(12), color = DS.Colors.onSurfaceVariant)
        }
        Text("${creditText(un.credits)} kredi", style = sys(14, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
    }
}

@Composable
internal fun CcThresholdCard(threshold: Double, canEdit: Boolean, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon("bell.badge", size = 16.dp, tint = DS.Colors.metal)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Düşük bakiye uyarısı", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(
                "Kullanılabilir kredi bu eşiğin altına düştüğünde kredi yükleme yetkisi olanlara bildirim gider (0 = kapalı).",
                style = sys(12), color = DS.Colors.onSurfaceVariant,
            )
            Text("Uyarı eşiği (kredi): ${creditText(threshold)}", style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurface)
        }
        if (canEdit) {
            Text(
                "Düzenle",
                Modifier.semantics { role = Role.Button }.plainClickable(onClick = onEdit),
                style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400,
            )
        }
    }
}

// MARK: - Kredi Yükle parçaları

@Composable
internal fun CcWalletCard(wallet: SmileWallet) {
    Column(Modifier.fillMaxWidth().glassCard(22.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreditNumericText(
                creditText(wallet.availableCredits), sys(40, FontWeight.Bold), DS.Colors.onSurface,
                Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            Text("kredi", Modifier.alignByBaseline(), style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        }
        Text(
            "Ücretsiz ${creditText(wallet.freeRemainingCredits)} · satın alınmış ${creditText(wallet.balanceCredits)}",
            style = sys(12), color = DS.Colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CcPackCard(pack: SmileCreditPack, enabled: Boolean, onBuy: () -> Unit, modifier: Modifier = Modifier) {
    val highlight = pack.popular == true
    val liste = pack.listPriceTL
    // Ek MO.21i rozetleri — paketin BAYRAĞINDAN.
    val badge = when {
        pack.popular == true -> "En çok tercih edilen"
        pack.bestValue == true -> "En avantajlı"
        else -> null
    }
    Row(
        modifier
            .fillMaxWidth()
            .glassCard(20.dp)
            .then(
                if (highlight) Modifier.drawBehind {
                    val k = 1.2.dp.toPx()
                    drawRoundRect(
                        DS.Colors.cyan400.copy(alpha = 0.7f), Offset(k / 2, k / 2), Size(size.width - k, size.height - k),
                        CornerRadius(20.dp.toPx() - k / 2), style = Stroke(k),
                    )
                } else Modifier,
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${creditText(pack.credits)} kredi", Modifier.align(Alignment.CenterVertically),
                    style = sys(17, FontWeight.Bold), color = DS.Colors.onSurface,
                )
                if (badge != null) {
                    Text(
                        badge.upperTr(),
                        Modifier
                            .align(Alignment.CenterVertically)
                            .hapZemin(if (highlight) DS.Colors.cyan400 else DS.Colors.cyan400.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        style = sys(9, FontWeight.Bold, tracking = 0.8),
                        color = if (highlight) DS.Colors.onPrimary else DS.Colors.cyan400,
                    )
                }
            }
            if (pack.indirimVar && liste != null) {
                Text(
                    "${SmileCreditFormat.lira(liste)} yerine · %${pack.discountPct?.let { creditText(it) } ?: ""} sözleşme indirimi",
                    style = sys(11), color = DS.Colors.tertiaryContainer,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                pack.unitPriceTL?.let { Text("kredi başı ${SmileCreditFormat.lira(it)}", style = sys(11), color = DS.Colors.onSurfaceVariant) }
                val save = pack.savePct
                if (save != null && save > 0) Text("%$save tasarruf", style = sys(11, FontWeight.SemiBold), color = DS.Colors.tertiaryContainer)
            }
            if (pack.priceTL == null) {
                Text("Paket fiyatı şu an okunamıyor.", style = sys(11), color = DS.Colors.orange400)
            }
        }
        val price = pack.priceTL
        if (price != null) {
            // Satın alma `fin.credits` ister — yetkisizse sönük, dokununca uyarı (M-E7.4); sunucunun `canPurchase` kararı AYNEN.
            val etiket: @Composable (Modifier) -> Unit = { mod ->
                Column(
                    mod
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DS.Gradients.primaryGradient)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (pack.indirimVar && liste != null) {
                        Text(
                            SmileCreditFormat.lira(liste), Modifier.alpha(0.75f),
                            style = sys(10).copy(textDecoration = TextDecoration.LineThrough), color = Color.White, maxLines = 1,
                        )
                    }
                    Text(SmileCreditFormat.lira(price), style = sys(15, FontWeight.Bold), color = Color.White, maxLines = 1)
                    Text("Satın al", Modifier.alpha(0.85f), style = sys(10, FontWeight.SemiBold), color = Color.White, maxLines = 1)
                }
            }
            if (enabled) {
                YetkiliButon(listOf("fin.credits"), eylem = onBuy) { mod -> etiket(mod) }
            } else {
                // `.disabled(!enabled).opacity(0.4)` — dokunuş yok; yetkisizse YetkiliButon'un sönüklüğü de eklenir.
                val izinli = yetkiliMi(listOf("fin.credits"))
                etiket(Modifier.alpha(0.4f * (if (izinli) 1f else YetkiliEylem.SONUK_OPAKLIK)))
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 1400)
@Composable
private fun CcPartsPreview() {
    com.dentifay.app.ui.PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val a = CreditPreviewData.analysis
            CcKpiGrid(CreditPreviewData.wallet, a, sutun = 2)
            CcCard("Özelliğe göre kullanım", null) { CcFeatureBreakdown(a.features, a.features.map { it.feature }) }
            CcPersonCard(CreditPreviewData.people.people[0], canEdit = true, onEdit = {})
            CcPackCard(CreditPreviewData.packs[1], enabled = true, onBuy = {})
            CcPackCard(CreditPreviewData.packs[2], enabled = false, onBuy = {})
            Column {
                CreditPreviewData.rows.take(3).forEachIndexed { i, r ->
                    CcLedgerRow(r, showTerms = true, onTerms = {}, Modifier.kartDilimi(ilk = i == 0, son = i == 2).padding(horizontal = 14.dp))
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun CcPartsTabletPreview() {
    com.dentifay.app.ui.PreviewEnv(pencere = com.dentifay.app.ui.PencereBilgisi.TABLET_YATAY) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CcKpiGrid(CreditPreviewData.wallet, CreditPreviewData.analysis, sutun = 4)
            EsitIzgara(CreditPreviewData.packs, sutun = 3, bosluk = 12.dp) { p, mod -> CcPackCard(p, enabled = true, onBuy = {}, modifier = mod) }
        }
    }
}
