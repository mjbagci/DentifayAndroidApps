package com.dentifay.app.credit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.launch

/**
 * Uygulama içi "Yapay Zeka Kredileri" — kalan kredi, kademe fiyatları ve
 * kredi paketi satın alma. Gülüş simülasyonunda, asistan sohbetinde, sesli modda ve
 * Tedavi Planı'nda kredi bitince (402 · `CreditRequired`), Ayarlar'dan ve AI Hub'dan
 * açılır. Ödeme uygulamada alınmaz: paket bedeli kliniğin o ayki
 * Dentifay faturasına yazılır (yalnız klinik sahibi / yöneticisi satın alır).
 *
 * `reason`: ekranın neden açıldığı (ör. "Bu tasarım 4 kredi, kalan 2").
 */
@Composable
fun SmileCreditsView(
    reason: String? = null,
    onPurchased: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val store = SmileCreditsStore.shared
    val scope = rememberCoroutineScope()
    var confirmPack by remember { mutableStateOf<SmileCreditPack?>(null) }
    var purchased by remember { mutableStateOf<SmilePurchaseResult?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.refresh() }

    /** `CreditPurchaseSheet` koşulları onaylatıp satın aldıktan SONRA. */
    fun purchaseDone(r: SmilePurchaseResult) {
        purchased = r
        Haptics.success()
        onPurchased?.invoke()
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar("Krediler", trailing = { CreditBarButton("Kapat") { onDismiss() } })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    store.refresh()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (reason != null) {
                    CreditIconLabel(
                        reason, "exclamationmark.circle.fill",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(DS.Colors.orange400.copy(alpha = 0.12f))
                            .padding(12.dp),
                        size = 13f, weight = FontWeight.Medium, color = DS.Colors.orange400,
                    )
                }

                if (!store.isMetered) {
                    // Yalnız gülüş simülasyonu doğrudan gidiyor; asistan ve sesli mod bu
                    // derlemede de sunucuda krediyle çalışır → cüzdan ve paketler yine çizilir.
                    SmileInfoCard(
                        icon = "hammer.fill",
                        title = "Geliştirici modu",
                        text = "Bu derlemede gülüş simülasyonu doğrudan görüntü servisine gidiyor (cihazda anahtar var); simülasyon için kredi düşülmüyor. Asistan ve sesli mod yine kliniğinizin kredisini kullanır.",
                    )
                }
                val wallet = store.wallet
                val error = store.errorMessage
                if (wallet != null) {
                    SmileBalanceCard(wallet)
                    wallet.myLimit?.let { SmileLimitCard(it) }
                    AnimatedVisibility(
                        visible = purchased != null,
                        enter = fadeIn(Motion.spring(0.4, 0.85)) + expandVertically(Motion.spring(0.4, 0.85)),
                    ) {
                        purchased?.let { r ->
                            SmileInfoCard(
                                icon = "checkmark.seal.fill",
                                title = "${SmileCreditFormat.credits(r.creditsGranted)} kredi eklendi",
                                text = "${SmileCreditFormat.lira(r.priceTL)} bu ayın Dentifay faturanıza yansıyacak.",
                                tint = DS.Colors.tertiaryContainer,
                            )
                        }
                    }
                    SmileTiersCard(wallet)
                    // Kredi paketleri
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("KREDİ PAKETLERİ", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
                        wallet.purchaseBlockedMessage?.let { blocked ->
                            SmileInfoCard(icon = "person.badge.key.fill", title = "Satın alma yetkisi", text = blocked)
                        }
                        wallet.packs.forEach { pack ->
                            SmilePackCard(pack, enabled = wallet.canPurchase, busy = store.isPurchasing) { confirmPack = pack }
                        }
                    }
                } else if (error != null) {
                    SmileInfoCard(icon = "wifi.exclamationmark", title = "Krediler yüklenemedi", text = error)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .pressable { scope.launch { store.refresh() } }
                            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold, verticalAlignment = Alignment.CenterVertically)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CreditSpinner(color = DS.Colors.cyan400)
                    }
                }

                CreditIconLabel(
                    "Ödeme uygulamada alınmaz; paket bedeli ay sonunda kliniğinizin Dentifay faturasına yansır. Başarısız simülasyonun kredisi otomatik iade edilir, otomatik yerleşim düzeltmesi ücretsizdir.",
                    "doc.text.fill", size = 11f, color = DS.Colors.onSurfaceVariant,
                )
            }
        }
    }

    // Satın alma: Kredi Satın Alma Koşulları okunup onaylanmadan YAPILMAZ (sunucu 428 verir).
    // Hatalar pencerenin içinde gösterilir.
    DfySheet(isPresented = confirmPack != null, onDismiss = { confirmPack = null }, containerColor = DS.Colors.background) { dismiss ->
        confirmPack?.let { pack ->
            CreditPurchaseSheet(pack = pack, onPurchased = { r -> purchaseDone(r) }, onDismiss = dismiss)
        }
    }
}

// MARK: - Kartlar

@Composable
private fun SmileBalanceCard(w: SmileWallet) {
    Column(Modifier.fillMaxWidth().glassCard(22.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreditNumericText(SmileCreditFormat.credits(w.availableCredits), sys(44, FontWeight.Bold), DS.Colors.onSurface)
            Text("kredi", Modifier.padding(bottom = 7.dp), style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (w.isOnFreeCredits) {
                Text(
                    "ÜCRETSİZ",
                    Modifier
                        .padding(bottom = 9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DS.Colors.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = sys(10, FontWeight.Bold, tracking = 1), color = DS.Colors.onPrimary,
                )
            }
        }
        if (w.freeGrantCredits > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("Ücretsiz kredi", style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${SmileCreditFormat.credits(w.freeRemainingCredits)} / ${SmileCreditFormat.credits(w.freeGrantCredits)}",
                        style = sys(12, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface,
                    )
                }
                CreditProgressBar(w.freeRemainingCredits / maxOf(w.freeGrantCredits, 1.0), DS.Colors.tertiaryContainer)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmileStatPill("Satın alınan", SmileCreditFormat.credits(w.balanceCredits), Modifier.weight(1f))
            w.thisMonth?.let { m ->
                SmileStatPill("Bu ay kullanılan", SmileCreditFormat.credits(m.usedCredits), Modifier.weight(1f))
                if (m.purchasedTL > 0) SmileStatPill("Bu ay faturaya", SmileCreditFormat.lira(m.purchasedTL), Modifier.weight(1f))
            }
        }
    }
}

/** Kişinin aylık kredi limiti (Kredi Yönetimi › Kişiler). Kredi satın almak bu limiti AŞMAZ. */
@Composable
private fun SmileLimitCard(limit: SmileWallet.MyLimit) {
    Column(Modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AYLIK KREDİ LİMİTİNİZ", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "Kalan ${SmileCreditFormat.credits(limit.remainingCredits)} kredi",
                style = sys(12, FontWeight.SemiBold),
                color = if (limit.remainingCredits > 0) DS.Colors.tertiaryContainer else DS.Colors.orange400,
            )
        }
        limit.limits.forEach { line ->
            val pct = if (line.limitCredits <= 0) 1.0 else minOf(1.0, maxOf(0.0, line.usedCredits) / line.limitCredits)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(
                        if (line.scope == "*") "Aylık toplam" else CreditLabels.feature(line.scope),
                        style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${SmileCreditFormat.credits(line.usedCredits)} / ${SmileCreditFormat.credits(line.limitCredits)}",
                        style = sys(12, FontWeight.SemiBold).monoDigits(), color = DS.Colors.onSurface,
                    )
                }
                CreditProgressBar(
                    pct,
                    if (pct >= 1) DS.Colors.error else if (pct >= 0.8) DS.Colors.orange400 else DS.Colors.tertiaryContainer,
                )
            }
        }
        Text(
            "Limit kliniğin takvim ayıyla sayılır ve ay başında sıfırlanır; değiştirmek için klinik sahibine başvurun.",
            style = sys(11), color = DS.Colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SmileStatPill(title: String, value: String, modifier: Modifier = Modifier) {
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
private fun SmileTiersCard(w: SmileWallet) {
    Column(Modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GÜLÜŞ SİMÜLASYONU · KALİTE BAŞINA", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
        w.tiers.forEach { tier ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tier.label, style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                Spacer(Modifier.weight(1f))
                Text("${SmileCreditFormat.credits(tier.credits)} kredi", style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400)
            }
        }
        CreditDivider(0.08f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Otomatik yerleşim düzeltme", style = sys(13), color = DS.Colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Ücretsiz", style = sys(13, FontWeight.SemiBold), color = DS.Colors.tertiaryContainer)
        }
    }
}

@Composable
private fun SmilePackCard(pack: SmileCreditPack, enabled: Boolean, busy: Boolean, onBuy: () -> Unit) {
    val highlight = pack.popular == true
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
                Text("${SmileCreditFormat.credits(pack.credits)} kredi", style = sys(17, FontWeight.Bold), color = DS.Colors.onSurface)
                pack.badge?.let { badge ->
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
                pack.unitPriceTL?.let { unit ->
                    Text("kredi başı ${SmileCreditFormat.lira(unit)}", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
                val save = pack.savePct
                if (save != null && save > 0) {
                    Text("%$save tasarruf", style = sys(11, FontWeight.SemiBold), color = DS.Colors.tertiaryContainer)
                }
            }
        }
        Column(
            Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .pressable(enabled = enabled && !busy, onClick = onBuy)
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

@Composable
private fun SmileInfoCard(icon: String, title: String, text: String, tint: Color = DS.Colors.cyan400) {
    Row(
        Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon(icon, size = 18.dp, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(text, style = sys(12), color = DS.Colors.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileCreditsViewPreview() {
    PreviewEnv {
        remember { SmileCreditsStore.shared.previewFill(CreditPreviewData.wallet); 0 }
        SmileCreditsView(reason = "Bu tasarım 12 kredi, kalan 4.", onDismiss = {})
    }
}
