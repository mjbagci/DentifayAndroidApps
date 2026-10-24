package com.dentifay.app.trial

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTileSize
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.LocalTextStyleOverride
import com.dentifay.app.theme.PrimaryButton
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.openInBrowser
import com.dentifay.app.theme.openSystemUrl
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import androidx.compose.material3.LocalContentColor

/** Kalan ücretsiz hak çipi ("3/5 hak"). */
@Composable
fun TrialQuotaChip(feature: GuestTrialFeature, compact: Boolean = false, modifier: Modifier = Modifier) {
    val store = GuestTrialStore.shared
    val q = store.quota(feature)
    val remaining = q?.remaining
    val limit = q?.limit ?: store.limit
    val empty = remaining == 0
    val tint = if (empty) DS.Colors.warning else DS.Colors.cyan400
    val unit = if (compact) "hak" else "ücretsiz hak"
    val label = remaining?.let { "$it/$limit $unit" } ?: "$limit $unit"
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics { contentDescription = remaining?.let { "$limit haktan $it kaldı" } ?: "$limit ücretsiz hak" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(if (empty) "lock" else "gift", size = 10.dp, tint = tint)
        Text(label, style = sys(11, FontWeight.SemiBold).monoDigits(), color = tint, maxLines = 1)
    }
}

/**
 * "Daha fazlası için bizimle iletişime geçin" sayfası. Kanallar sunucudan gelir
 * (`GUEST_TRIAL_CONTACT_*`); yoksa web sitesindeki demo formu açılır.
 * Sunan taraf `DfySheet` içinde gösterir.
 */
@Composable
fun GuestContactSheet(exhausted: GuestTrialFeature? = null, onDismiss: () -> Unit) {
    val store = GuestTrialStore.shared
    val context = LocalContext.current
    val contact = store.contact

    LaunchedEffect(Unit) { store.refresh() }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(Modifier.fillMaxSize()) {
            // Gezinme çubuğu: sağda "Kapat".
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    "Kapat", Modifier.plainClickable(onClick = onDismiss).padding(vertical = 8.dp),
                    style = sys(17), color = DS.Colors.cyan400,
                )
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconTile(
                        icon = if (exhausted == null) "bubble.left.and.text.bubble.right" else "lock.open",
                        tone = IconTone.Brand, size = IconTileSize.Md,
                    )
                    Text(
                        exhausted?.let { "${it.title} deneme hakkınız doldu" } ?: "Dentifay'ı kliniğinizde kullanın",
                        style = sys(24, FontWeight.Bold, tracking = -0.4), color = Color.White,
                    )
                    Text(
                        "Daha fazla röntgen analizi ve gülüş simülasyonu için bizimle iletişime geçin. Klinik hesabınızla analizler hasta kartına kaydedilir, tedavi planına dönüşür ve ekibiniz birlikte çalışır.",
                        style = sys(15).copy(lineHeight = 21.sp), color = DS.Colors.onSurfaceVariant,
                    )
                }

                Column(
                    Modifier.fillMaxWidth().glassCard(16.dp).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuotaRow(GuestTrialFeature.xray)
                    QuotaRow(GuestTrialFeature.smile)
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val demoUrl = contact.url ?: GuestTrialContact.fallback.url!!
                    PrimaryButton(onClick = { openInBrowser(context, demoUrl) }) {
                        DfyLabel("Demo talep et", "arrow.up.right")
                    }
                    contact.phone?.let { phone ->
                        val digits = phone.filter { it.isDigit() || it == '+' }
                        SecondaryButton(onClick = { openSystemUrl(context, "tel:$digits") }) {
                            DfyLabel("Ara · $phone", "phone")
                        }
                    }
                    contact.whatsapp?.let { wa ->
                        val digits = wa.filter { it.isDigit() }
                        SecondaryButton(onClick = { openSystemUrl(context, "https://wa.me/$digits") }) {
                            DfyLabel("WhatsApp'tan yazın", "message")
                        }
                    }
                    contact.email?.let { email ->
                        SecondaryButton(onClick = { openSystemUrl(context, "mailto:$email?subject=" + Uri.encode("Dentifay deneme")) }) {
                            DfyLabel(email, "envelope")
                        }
                    }
                }

                Text(
                    "Deneme hakları cihazınızın internet bağlantısına tanımlıdır.",
                    style = sys(12), color = DS.Colors.outline,
                )
            }
        }
    }
}

@Composable
private fun QuotaRow(f: GuestTrialFeature) {
    val store = GuestTrialStore.shared
    val q = store.quota(f)
    val limit = q?.limit ?: store.limit
    val remaining = q?.remaining ?: limit
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            DfyIcon(if (f == GuestTrialFeature.xray) "rectangle.split.3x1" else "mouth", size = 15.dp, tint = DS.Colors.metal)
        }
        Text(f.title, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(limit) { i ->
                Box(
                    Modifier
                        .size(width = 10.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (i < remaining) DS.Colors.cyan400 else Color.White.copy(alpha = 0.12f))
                )
            }
        }
        Text(
            "$remaining/$limit", style = sys(13, FontWeight.SemiBold).monoDigits(),
            color = if (remaining == 0) DS.Colors.warning else DS.Colors.onSurface,
        )
    }
}

/** SwiftUI `Label(_:dfy:)`: ikon + metin, düğme stilini miras alır. */
@Composable
private fun DfyLabel(text: String, icon: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DfyIcon(icon, size = LocalTextStyleOverride.current.fontSize.value.dp, tint = LocalContentColor.current)
        ButtonLabel(text)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun GuestContactSheetPreview() {
    PreviewEnv(loggedIn = false) {
        GuestContactSheet(exhausted = GuestTrialFeature.smile, onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 120)
@Composable
private fun TrialQuotaChipPreview() {
    PreviewEnv(loggedIn = false) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrialQuotaChip(GuestTrialFeature.xray)
            TrialQuotaChip(GuestTrialFeature.smile, compact = true)
        }
    }
}
