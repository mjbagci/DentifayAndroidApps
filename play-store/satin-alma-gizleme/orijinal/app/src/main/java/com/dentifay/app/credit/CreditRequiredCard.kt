package com.dentifay.app.credit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import java.util.UUID

/**
 * Kredi gerekli kartı (402 · `CreditRequired`) — sohbet balonu, sesli ekran ve
 * Tedavi Planı aynı kartı çizer. Ham kod/JSON göstermez.
 *
 * - Kredi yetersiz + satın alabilir → "Kredi Al" (kredi ekranını açar) + isteğe bağlı "Tekrar dene".
 * - Satın alamaz / demo / aylık limit → yalnız ne yapılacağını söyleyen metin.
 */
@Composable
fun CreditRequiredCard(
    credit: CreditRequired,
    modifier: Modifier = Modifier,
    onBuy: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val icon = if (credit.kind == CreditRequired.Kind.limitReached) "gauge.with.dots.needle.100percent" else "creditcard.trianglebadge.exclamationmark"
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
                Text(credit.detail, style = sys(13), color = DS.Colors.onSurfaceVariant)
            }
        }

        if (credit.offersPurchase && onBuy != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .pressable(onClick = onBuy)
                        .clip(RoundedCornerShape(50))
                        .background(DS.Gradients.primaryGradient)
                        .semantics { contentDescription = "Kredi Al. Kredi paketlerini açar" },
                    contentAlignment = Alignment.Center,
                ) {
                    CreditIconLabel("Kredi Al", "creditcard", size = 14f, weight = FontWeight.Bold, color = Color.White, verticalAlignment = Alignment.CenterVertically)
                }
                if (onRetry != null) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .pressable(onClick = onRetry)
                            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold, color = DS.Colors.onSurface, verticalAlignment = Alignment.CenterVertically)
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

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditRequiredCardPreview() {
    PreviewEnv {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CreditRequiredCard(
                CreditRequired(CreditRequired.Kind.insufficient, requiredCredits = 4.0, availableCredits = 2.0, canPurchase = true),
                onBuy = {}, onRetry = {},
            )
            CreditRequiredCard(
                CreditRequired(CreditRequired.Kind.limitReached, requiredCredits = 1.0, scope = "ai_agent", limitCredits = 50.0, usedCredits = 50.0),
            )
            CreditRequiredCard(CreditRequired(CreditRequired.Kind.insufficient, availableCredits = 0.0, isDemo = true))
        }
    }
}
