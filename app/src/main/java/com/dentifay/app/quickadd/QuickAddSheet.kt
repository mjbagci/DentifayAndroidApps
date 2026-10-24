package com.dentifay.app.quickadd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.Yetki
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv

/**
 * Ortadaki "Ekle" düğmesinin hızlı işlem listesi. Her satır uygulamada ÇALIŞAN bir akışı açar; "Yeni randevu" yerli
 * ekrandır (M-E8.13). Satır yalnız oturumun yeteneği varsa çizilir (M-E3.6).
 */
enum class QuickAction(val rawValue: String) {
    xray("xray"), smile("smile"), treatmentPlan("treatmentPlan"), assistant("assistant"), appointment("appointment");

    val id: String get() = rawValue

    /** Satırın yüzeyi — yalnız oturumun yeteneği varsa çizilir (M-E3.6). */
    val yuzey: Yetki.Yuzey
        get() = when (this) {
            xray -> Yetki.Yuzey.rontgen
            smile -> Yetki.Yuzey.gulus
            treatmentPlan -> Yetki.Yuzey.tedaviPlani
            assistant -> Yetki.Yuzey.asistan
            appointment -> Yetki.Yuzey.yeniRandevu
        }

    companion object {
        /** Oturumun görebildiği satırlar, sabit sırayla ("Yeni randevu" `appt.create` yoksa görünmez). */
        fun satirlar(caps: Set<String>): List<QuickAction> = entries.filter { Yetki.gorur(caps, it.yuzey) }
    }
}

/**
 * Hızlı işlem sayfası (Swift `.presentationDetents([.height(420)])`, kendi tutamacı, köşe 24).
 * Sunan taraf `DfySheet(showDragHandle = false, containerColor = DS.Colors.surfaceContainerLow)` ile sarar; seçim önce
 * bildirilir (`onSelect`), sonra sayfa kapanır — akış sayfa kapanınca açılır.
 * Yükseklik en az 420 dp; büyük yazı boyutunda ya da kısa pencerede içerik kaydırılır (kesilmez).
 */
@Composable
fun QuickAddSheet(onSelect: (QuickAction) -> Unit, onDismiss: () -> Unit) {
    val caps = LocalAuthManager.current.yetenekler
    val satirlar = remember(caps) { QuickAction.satirlar(caps) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp)
            .background(DS.Colors.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 38.dp, height = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f))
            )
        }

        Text("Hızlı işlem", Modifier.padding(top = 2.dp), style = sys(20, FontWeight.Bold), color = Color.White)

        if (satirlar.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().glassCard(16.dp)) {
                satirlar.forEachIndexed { i, eylem ->
                    if (i > 0) RowDivider()
                    Satir(eylem, onSelect = onSelect, onDismiss = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Satir(eylem: QuickAction, onSelect: (QuickAction) -> Unit, onDismiss: () -> Unit) {
    when (eylem) {
        QuickAction.xray -> ActionRow(
            QuickAction.xray, "rectangle.split.3x1", listOf(mutedHex("2dd4bf")),
            "Röntgen analizi", "Panoramik röntgeni çek ya da yükle", onSelect = onSelect, onDismiss = onDismiss,
        )
        QuickAction.smile -> ActionRow(
            QuickAction.smile, "mouth", listOf(mutedHex("f472b6")),
            "Gülüş simülasyonu", "Önce / sonra görseli hazırla", onSelect = onSelect, onDismiss = onDismiss,
        )
        QuickAction.treatmentPlan -> ActionRow(
            QuickAction.treatmentPlan, "mic", listOf(mutedHex("fbbf24")),
            "Sesli Tedavi Planı", "Planı söyle, kalemlere ayrılsın", onSelect = onSelect, onDismiss = onDismiss,
        )
        QuickAction.assistant -> ActionRow(
            QuickAction.assistant, "bubble.left.and.text.bubble.right", listOf(DS.Colors.brandIcon),
            "Dentifay Asistan", "Soru sorun, raporlar ve grafikler oluşturun", onSelect = onSelect, onDismiss = onDismiss,
        )
        QuickAction.appointment -> ActionRow(
            QuickAction.appointment, "calendar.badge.plus", null,
            "Yeni randevu", "Hasta, hekim, tarih ve işlem", onSelect = onSelect, onDismiss = onDismiss,
        )
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
}

@Composable
private fun ActionRow(
    action: QuickAction,
    icon: String,
    accent: List<Color>?,
    title: String,
    subtitle: String,
    external: Boolean = false,
    onSelect: (QuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable {
                Haptics.light()
                // Seçim önce bildirilir; sunan taraf sayfa kapanınca akışı açar.
                onSelect(action)
                onDismiss()
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Web `IconTile`: modül renginde kutusuz çift tonlu ikon; renksiz satır gri.
        IconTile(icon = icon, tone = accent?.let { IconTone.Accent(it) } ?: IconTone.Metal, customSize = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(subtitle, style = sys(13), color = DS.Colors.onSurfaceVariant)
        }
        DfyIcon(if (external) "arrow.up.right" else "chevron.right", size = 12.dp, tint = DS.Colors.outline)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun QuickAddSheetPreview() {
    PreviewEnv {
        QuickAddSheet(onSelect = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun QuickAddSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.size(width = 640.dp, height = 460.dp)) { QuickAddSheet(onSelect = {}, onDismiss = {}) }
        }
    }
}
