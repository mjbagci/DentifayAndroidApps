package com.dentifay.app.credit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
internal fun CcSectionHeader(text: String) {
    Text(text, style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
}

@Composable
internal fun CcFootnote(text: String) {
    Text(text, style = sys(11), color = DS.Colors.onSurfaceVariant)
}

@Composable
internal fun CcEmptyNote(text: String) {
    Box(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = sys(13), color = DS.Colors.onSurfaceVariant)
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
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) DS.Colors.cyan400.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (active) DS.Colors.cyan400.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 11.dp, tint = tint)
        Text(text, style = sys(13, FontWeight.SemiBold), color = tint, maxLines = 1)
        DfyIcon("chevron.down", Modifier.alpha(0.7f), size = 9.dp, tint = tint)
    }
}

/** `Menu { Button… }` öğesi; `checked` = `Picker` içindeki seçili satır. */
internal data class CcMenuItem(val title: String, val checked: Boolean = false, val action: () -> Unit)

/** `Menu { … } label: { FilterChip(…) }` */
@Composable
internal fun CcMenu(items: List<CcMenuItem>, label: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.plainClickable { expanded = true }) { label() }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title, style = sys(16), color = DS.Colors.onSurface) },
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

@Composable
internal fun CcCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp),
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
                Text(actionTitle, Modifier.plainClickable(onClick = action), style = sys(13, FontWeight.SemiBold), color = tint)
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
                .height(42.dp)
                .pressable(onClick = retry)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold, verticalAlignment = Alignment.CenterVertically)
        }
    }
}

// MARK: - Özet parçaları

@Composable
internal fun CcKpiGrid(usage: CreditUsage) {
    val w = usage.wallet
    val s = usage.summary
    // 7 günden az kırmızı, 30'dan az turuncu (web `yeterlilikTonu` ile aynı eşikler).
    val runwayTint = when (val d = usage.runwayDays) {
        null -> DS.Colors.slate400
        else -> if (d < 7) DS.Colors.error else if (d < 30) DS.Colors.orange400 else DS.Colors.tertiaryContainer
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            CcKpi(
                "creditcard.fill", DS.Colors.cyan400, "Kullanılabilir kredi", creditText(w.availableCredits),
                "Ücretsiz ${creditText(w.freeRemainingCredits)} · satın alınmış ${creditText(w.balanceCredits)}",
                Modifier.weight(1f),
            )
            CcKpi(
                "chart.bar.fill", DS.Colors.accentViolet, "Dönemde kullanılan", creditText(s.usedCredits),
                "${s.operations} işlem · iade ${creditText(s.refundedCredits)}",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            CcKpi(
                "hourglass", runwayTint, "Tahmini yeterlilik", usage.runwayDays?.let { "$it gün" } ?: "—",
                if (usage.last30DaysCredits > 0)
                    "Son 30 gün ortalaması günde ${creditText((usage.last30DaysCredits / 30 * 10).roundToInt() / 10.0)} kredi"
                else "Son 30 günde kullanım yok",
                Modifier.weight(1f),
            )
            CcKpi(
                "arrow.down.circle.fill", DS.Colors.tertiaryContainer, "Dönemde yüklenen", creditText(s.purchasedCredits),
                if (s.purchases > 0) "${s.purchases} satın alma · ${SmileCreditFormat.lira(s.paidTL)}" else "Bu dönemde satın alma yok",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CcKpi(icon: String, tint: Color, title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 112.dp)
            .glassCard(18.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon(icon, size = 12.dp, tint = tint)
            Text(title, style = sys(11, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CreditNumericText(value, sys(24, FontWeight.Bold).monoDigits(), DS.Colors.onSurface)
        Text(detail, style = sys(10), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CcFeatureBreakdown(rows: List<CreditUsage.FeatureRow>, order: List<String>) {
    val total = rows.sumOf { max(0.0, it.credits) }
    if (rows.isEmpty()) {
        Text("Bu dönemde kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            val share = if (total > 0) max(0.0, r.credits) / total else 0.0
            val color = CreditPalette.color(r.feature, order)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(CreditLabels.feature(r.feature), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text("${creditText(r.credits)} kredi", style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp)) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f)))
                    Box(
                        Modifier
                            .width((maxWidth * share.toFloat()).coerceAtLeast(4.dp))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(color),
                    )
                }
                Text(
                    "${r.operations} işlem · %${(share * 100).roundToInt()}" + (if (r.refunds > 0) " · ${r.refunds} iade" else ""),
                    style = sys(11), color = DS.Colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CcUserBreakdown(rows: List<CreditUsage.UserRow>, tz: ZoneId, onSelect: (String) -> Unit) {
    if (rows.isEmpty()) {
        Text("Bu dönemde kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        return
    }
    Column {
        rows.forEachIndexed { i, r ->
            val selectable = !(r.userId ?: "").isEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .plainClickable(enabled = selectable) { r.userId?.takeIf { it.isNotEmpty() }?.let(onSelect) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(DS.Colors.cyan400.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(ccInitials(r.user?.name), style = sys(12, FontWeight.Bold), color = DS.Colors.cyan400)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.user?.displayName ?: "Sistem / Dentifay", style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                    Text(
                        listOfNotNull(r.user?.departmentLabel, "${r.operations} işlem", r.lastAt?.let { "son: ${CreditDates.short(it, tz)}" }).joinToString(" · "),
                        style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(creditText(r.credits), style = sys(15, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
                if (selectable) DfyIcon("chevron.right", size = 11.dp, tint = DS.Colors.slate500)
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
internal fun CcActionList(rows: List<CreditUsage.ActionRow>) {
    if (rows.isEmpty()) {
        Text("Bu dönemde kredi kullanımı yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        CreditLabels.action(r.feature, r.action) ?: CreditLabels.feature(r.feature),
                        style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface,
                    )
                    Text("${CreditLabels.feature(r.feature)} · ${r.operations} işlem", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
                Text("${creditText(r.credits)} kredi", style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400)
            }
        }
    }
}

// MARK: - Hareket satırı

@Composable
internal fun CcLedgerRow(row: CreditTransactionsPage.Row, tz: ZoneId, modifier: Modifier = Modifier) {
    val signed = if (row.credits == 0.0) creditText(0.0) else (if (row.credits > 0) "+" else "−") + creditText(abs(row.credits))
    val amountTint = if (row.credits < 0) mutedHex("fda4af") else if (row.credits > 0) DS.Colors.tertiaryContainer else DS.Colors.onSurfaceVariant
    val kindTint = when (row.kind) {
        "refund", "purchase", "grant" -> DS.Colors.tertiaryContainer
        "adjustment" -> DS.Colors.orange400
        else -> DS.Colors.slate400
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(CreditLabels.operation(row.feature, row.action), Modifier.weight(1f, fill = false), style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                if (row.parts > 1) Text("(${row.parts} parça)", style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
            Text("${Unvan.dt(row.actorName)} · ${CreditDates.short(row.at, tz)}", style = sys(12), color = DS.Colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    CreditLabels.kinds[row.kind] ?: row.kind,
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(kindTint.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    style = sys(10, FontWeight.Bold), color = kindTint,
                )
                val label = row.source?.let { CreditLabels.sources[it] }
                if (label != null) Text(label, style = sys(11), color = DS.Colors.slate400)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(signed, style = sys(15, FontWeight.Bold).monoDigits(), color = amountTint)
            row.priceTL?.let { Text(SmileCreditFormat.lira(it), style = sys(11).monoDigits(), color = DS.Colors.onSurfaceVariant) }
        }
    }
}

// MARK: - Kişi kartı

@Composable
internal fun CcPersonCard(person: CreditPeople.Person, canEdit: Boolean, onEdit: () -> Unit) {
    val subtitle = run {
        val parts = mutableListOf<String>()
        val d = person.user.departmentLabel
        if (d != null) parts += d else if (person.user.former == true) parts += "ayrılmış"
        if (person.user.active == false && person.user.former != true) parts += "pasif"
        if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }
    Column(
        Modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Unvan.dt(person.user.name ?: "Bilinmeyen kişi"), style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
                Text(subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${creditText(person.usedCredits)} kredi", style = sys(15, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
                Text("${person.operations} işlem", style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
        }

        val used = person.byFeature.entries.filter { it.value.credits != 0.0 }.sortedByDescending { it.value.credits }
        if (used.isNotEmpty()) {
            Text(
                used.joinToString(" · ") { "${CreditLabels.feature(it.key)} ${creditText(it.value.credits)}" },
                style = sys(12), color = DS.Colors.onSurfaceVariant,
            )
        }

        CreditDivider()

        val total = person.limits.total
        if (total != null) {
            val pct = if (total <= 0) 1.0 else min(1.0, max(0.0, person.usedCredits) / total)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aylık limit", style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${creditText(person.usedCredits)} / ${creditText(total)} · kalan ${creditText(person.remainingCredits ?: 0.0)}",
                        style = sys(12, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface,
                    )
                }
                CreditProgressBar(pct, if (pct >= 1) DS.Colors.error else if (pct >= 0.8) DS.Colors.orange400 else DS.Colors.tertiaryContainer)
            }
        } else {
            Text("Aylık toplam: Sınırsız", style = sys(12), color = DS.Colors.onSurfaceVariant)
        }
        person.limits.features.entries.sortedBy { it.key }.forEach { (f, limit) ->
            Text(
                "${CreditLabels.feature(f)}: ${creditText(person.byFeature[f]?.credits ?: 0.0)} / ${creditText(limit)}",
                style = sys(11), color = DS.Colors.onSurfaceVariant,
            )
        }

        if (canEdit) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .pressable(onClick = onEdit)
                    .clip(RoundedCornerShape(50))
                    .background(DS.Colors.cyan400.copy(alpha = 0.1f))
                    .semantics { contentDescription = "${Unvan.dt(person.user.name ?: "Kişi")} için kredi limitini düzenle" },
                contentAlignment = Alignment.Center,
            ) {
                CreditIconLabel("Limiti düzenle", "slider.horizontal.3", size = 13f, weight = FontWeight.SemiBold, color = DS.Colors.cyan400, verticalAlignment = Alignment.CenterVertically)
            }
        }
    }
}

@Composable
internal fun CcThresholdCard(threshold: Double, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon("bell.badge.fill", size = 16.dp, tint = DS.Colors.orange400)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Düşük bakiye uyarısı", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(
                if (threshold > 0) "Kullanılabilir kredi ${creditText(threshold)} altına düşünce bildirim gider." else "Kapalı",
                style = sys(12), color = DS.Colors.onSurfaceVariant,
            )
        }
        Text("Düzenle", Modifier.plainClickable(onClick = onEdit), style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400)
    }
}

// MARK: - Yükle parçaları

@Composable
internal fun CcWalletCard(wallet: CreditUsage.Wallet) {
    Column(Modifier.fillMaxWidth().glassCard(22.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreditNumericText(creditText(wallet.availableCredits), sys(40, FontWeight.Bold), DS.Colors.onSurface)
            Text("kredi", Modifier.padding(bottom = 6.dp), style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (wallet.isLow) {
                Text(
                    "DÜŞÜK",
                    Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DS.Colors.orange400)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = sys(10, FontWeight.Bold, tracking = 1), color = DS.Colors.onError,
                )
            }
        }
        if (wallet.freeGrantCredits > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row {
                    Text("Ücretsiz kredi", style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${creditText(wallet.freeRemainingCredits)} / ${creditText(wallet.freeGrantCredits)}",
                        style = sys(12, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface,
                    )
                }
                CreditProgressBar(wallet.freeRemainingCredits / max(wallet.freeGrantCredits, 1.0), DS.Colors.tertiaryContainer)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CcPill("Satın alınmış", creditText(wallet.balanceCredits), Modifier.weight(1f))
            wallet.thisMonth?.let { m ->
                CcPill("Bu ay kullanılan", creditText(m.usedCredits), Modifier.weight(1f))
                if (m.purchasedTL > 0) CcPill("Bu ay faturaya", SmileCreditFormat.lira(m.purchasedTL), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CcPill(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = sys(10, FontWeight.SemiBold), color = DS.Colors.outline, maxLines = 1)
        Text(value, style = sys(14, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface, maxLines = 1)
    }
}

@Composable
internal fun CcPackCard(pack: SmileCreditPack, enabled: Boolean, onBuy: () -> Unit) {
    val highlight = pack.popular == true
    // Rozet sunucunun serbest metni değil paketin BAYRAĞINDAN (web ile aynı üç etiket).
    val badge = when {
        pack.popular == true -> "Popüler"
        pack.bestValue == true -> "En iyi teklif"
        else -> if (pack.badge == null) null else "Başlangıç"
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard(20.dp)
            .border(1.2.dp, if (highlight) DS.Colors.cyan400.copy(alpha = 0.7f) else Color.Transparent, shape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${creditText(pack.credits)} kredi", style = sys(17, FontWeight.Bold), color = DS.Colors.onSurface)
                if (badge != null) {
                    Text(
                        badge.upperTr(),
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (highlight) DS.Colors.cyan400 else DS.Colors.cyan400.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        style = sys(9, FontWeight.Bold, tracking = 0.8),
                        color = if (highlight) DS.Colors.onPrimary else DS.Colors.cyan400,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pack.unitPriceTL?.let { Text("kredi başı ${SmileCreditFormat.lira(it)}", style = sys(11), color = DS.Colors.onSurfaceVariant) }
                val save = pack.savePct
                if (save != null && save > 0) Text("%$save tasarruf", style = sys(11, FontWeight.SemiBold), color = DS.Colors.tertiaryContainer)
            }
        }
        Column(
            Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .pressable(enabled = enabled, onClick = onBuy)
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(DS.Gradients.primaryGradient)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(SmileCreditFormat.lira(pack.priceTL), style = sys(15, FontWeight.Bold), color = Color.White)
            Text("Satın al", Modifier.alpha(0.85f), style = sys(10, FontWeight.SemiBold), color = Color.White)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CcPartsPreview() {
    com.dentifay.app.ui.PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val u = CreditPreviewData.usage
            CcKpiGrid(u)
            CcPersonCard(CreditPreviewData.people.people[0], canEdit = true) {}
            CcPackCard(CreditPreviewData.packs[1], enabled = true) {}
            Column(Modifier.fillMaxWidth().glassCard(18.dp).padding(horizontal = 14.dp)) {
                CcLedgerRow(CreditPreviewData.rows[0], java.time.ZoneId.of("Europe/Istanbul"))
            }
        }
    }
}
