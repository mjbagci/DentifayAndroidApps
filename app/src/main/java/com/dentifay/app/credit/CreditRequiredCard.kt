package com.dentifay.app.credit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import java.util.UUID

/**
 * Kredi yetmedi kartı (`CreditRequired`) — sohbet balonu, Tedavi Planı, röntgen ve gülüş aynı kartı çizer.
 * Ham kod/JSON göstermez; metin `CreditRequired.title/detail` (sunucunun cümlesi ya da Ek MO.23g/h).
 *
 * "Kredi Yönetimi'ni aç" (Ek MO.23a) yalnız demo değilken ve cüzdan `canPurchase` derken çizilir
 * (karar sunucunun — mobil satın alma yetkisini KARARLAŞTIRMAZ); değilse kart düğmesizdir. Android: mağaza
 * derlemesinde (`AppConfig.inAppPurchase` kapalı) satın alma yolu çizilmez.
 */
@Composable
fun CreditRequiredCard(
    credit: CreditRequired,
    modifier: Modifier = Modifier,
    onBuy: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val credits = SmileCreditsStore.shared
    val icon = if (credit.kind == CreditRequired.Kind.limitReached) "gauge.with.dots.needle.100percent" else "creditcard.trianglebadge.exclamationmark"
    val satinAlabilir = AppConfig.inAppPurchase && !credit.isDemo && credits.wallet?.canPurchase == true
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DS.Colors.orange400.copy(alpha = 0.10f))
            .border(1.dp, DS.Colors.orange400.copy(alpha = 0.30f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DfyIcon(icon, size = 18.dp, tint = DS.Colors.orange400)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(credit.title, style = sys(15, FontWeight.Bold), color = DS.Colors.onSurface)
                Text(credit.kartMetni, style = sys(13), color = DS.Colors.onSurfaceVariant)
            }
        }

        val buy = if (satinAlabilir) onBuy else null
        if (buy != null || onRetry != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (buy != null) {
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .semantics { role = Role.Button }
                            .pressable(onClick = buy)
                            .clip(RoundedCornerShape(50))
                            .background(DS.Gradients.primaryGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        CreditIconLabel(
                            "Kredi Yönetimi'ni aç", "creditcard", Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            size = 14f, weight = FontWeight.Bold, color = Color.White, verticalAlignment = Alignment.CenterVertically,
                        )
                    }
                }
                if (onRetry != null) {
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .semantics { role = Role.Button }
                            .pressable(onClick = onRetry)
                            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CreditIconLabel(
                            "Tekrar dene", "arrow.clockwise", Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            size = 14f, weight = FontWeight.SemiBold, color = DS.Colors.onSurface, verticalAlignment = Alignment.CenterVertically,
                        )
                    }
                }
            }
        }
    }
}

/** Kredi ekranının açılış gerekçesi (Swift `.sheet(item:)` öğesi → nullable durum). */
data class CreditSheetItem(val reason: String?) {
    /** Swift `Identifiable` kimliği — her sunum ayrı öğedir (eşitliğe katılmaz). */
    val id: String = UUID.randomUUID().toString()
}

/**
 * 402 girişlerinin açtığı ekran: Kredi Yönetimi görünürse onun "Kredi Yükle" bölümü (yoksa Genel Bakış),
 * değilse salt okur "Krediler" (`SmileCreditsView`) — satın alma TEK yerde (M-E1.7.6). Sunan taraf `DfySheet` içinde açar.
 */
@Composable
fun KrediEkrani(reason: String?, onDismiss: () -> Unit) {
    val caps = LocalAuthManager.current.yetenekler
    if (KrediErisimi.yonetimGorur(caps)) {
        CreditCenterView(
            onDismiss = onDismiss,
            baslangic = if (KrediErisimi.yukleBolumu(caps)) CreditCenterSection.topUp else CreditCenterSection.overview,
        )
    } else {
        SmileCreditsView(reason = reason, onDismiss = onDismiss)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun KrediEkraniPreview() {
    // Örnek oturum `fin.credits` taşır → Kredi Yönetimi (Kredi Yükle ya da mağaza derlemesinde Genel Bakış).
    PreviewEnv {
        remember { SmileCreditsStore.shared.ornekYukle(CreditPreviewData.wallet); 0 }
        KrediEkrani(reason = "Bu işlem için yeterli krediniz yok.", onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditRequiredCardPreview() {
    PreviewEnv {
        remember { SmileCreditsStore.shared.ornekYukle(CreditPreviewData.wallet); 0 }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CreditRequiredCard(
                CreditRequired(CreditRequired.Kind.insufficient, requiredCredits = 4.0, availableCredits = 2.0, canPurchase = true),
                onBuy = {}, onRetry = {},
            )
            CreditRequiredCard(
                CreditRequired(CreditRequired.Kind.limitReached, requiredCredits = 1.0, scope = "ai_agent", limitCredits = 50.0, usedCredits = 50.0),
                onRetry = {},
            )
            CreditRequiredCard(CreditRequired(CreditRequired.Kind.insufficient, availableCredits = 0.0, isDemo = true), onBuy = {})
            CreditRequiredCard(CreditRequired(CreditRequired.Kind.insufficient, yarida = true, canPurchase = true), onBuy = {})
        }
    }
}
