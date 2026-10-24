package com.dentifay.app.credit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.BuildConfig
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.launch

/**
 * "Krediler" — SALT OKUR bakiye ekranı (MOBIL_IS_EMRI M-E1.7.6): kullanılabilir kredi, bu ay ve gülüş simülasyonu
 * kalite fiyatları. Satın alma burada YOK — tek satın alma yeri Kredi Yönetimi › Kredi Yükle'dir.
 * 402 girişlerinde (`reason`) üstte gerekçe; altta Kredi Yönetimi görünürse "Kredi Yönetimi'ni aç", değilse Ek MO.23b.
 *
 * `onPurchased`: sözleşme uyumu için korunur; satın alma artık bu ekranda olmadığından çağrılmaz.
 */
@Composable
fun SmileCreditsView(
    reason: String? = null,
    @Suppress("UNUSED_PARAMETER") onPurchased: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val store = SmileCreditsStore.shared
    val caps = LocalAuthManager.current.yetenekler
    val scope = rememberCoroutineScope()
    var yonetim by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.refresh() }

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar("Krediler", trailing = { CreditBarButton("Kapat") { onDismiss() } })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    try {
                        store.refresh()
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .icerikGenisligi(720.dp)
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

                    // Yalnız geliştirme derlemesi: gülüş simülasyonu doğrudan geliştirme hesabıyla çalışır.
                    if (BuildConfig.DEBUG && !store.isMetered) {
                        SmileInfoCard(
                            icon = "hammer.fill",
                            title = "Geliştirici modu",
                            text = "Geliştirme anahtarı tanımlı: gülüş simülasyonu doğrudan geliştirme hesabıyla çalışır, kredi düşülmez.",
                        )
                    }

                    val wallet = store.wallet
                    val error = store.errorMessage
                    if (wallet != null) {
                        // Geniş pencerede bakiye ve kalite fiyatları yan yana (eşit yükseklik).
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 600.dp) {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    SmileBalanceCard(wallet, Modifier.weight(1f).fillMaxHeight())
                                    SmileTiersCard(wallet, Modifier.weight(1f).fillMaxHeight())
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    SmileBalanceCard(wallet)
                                    SmileTiersCard(wallet)
                                }
                            }
                        }
                    } else if (error != null) {
                        SmileInfoCard(icon = "wifi.exclamationmark", title = "Kredi bilgileri yüklenemedi.", text = error)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .semantics { role = Role.Button }
                                .pressable { scope.launch { store.refresh() } }
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold, verticalAlignment = Alignment.CenterVertically)
                        }
                    } else if (!store.yetkisiz) {   // kodsuz 403 → parça çizilmez (M-E7.5)
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            CreditSpinner(color = DS.Colors.cyan400)
                        }
                    }

                    if (KrediErisimi.yonetimGorur(caps)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .semantics { role = Role.Button }
                                .pressable { yonetim = true }
                                .clip(RoundedCornerShape(50))
                                .background(DS.Gradients.primaryGradient),
                            contentAlignment = Alignment.Center,
                        ) {
                            CreditIconLabel(
                                "Kredi Yönetimi'ni aç", "chart.bar.doc.horizontal", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                size = 15f, weight = FontWeight.SemiBold, color = Color.White, verticalAlignment = Alignment.CenterVertically,
                            )
                        }
                    } else {
                        Text("Ek kredi için kliniğinizin Sahibine haber verin.", style = sys(12), color = DS.Colors.onSurfaceVariant)
                    }
                }
            }
        }
    }

    DfySheet(isPresented = yonetim, onDismiss = { yonetim = false }, containerColor = DS.Colors.background) { dismiss ->
        CreditCenterView(
            onDismiss = dismiss,
            baslangic = if (KrediErisimi.yukleBolumu(caps)) CreditCenterSection.topUp else CreditCenterSection.overview,
        )
    }
}

// MARK: - Kartlar

@Composable
private fun SmileBalanceCard(w: SmileWallet, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().glassCard(22.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Kullanılabilir kredi", style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreditNumericText(
                SmileCreditFormat.credits(w.availableCredits), sys(44, FontWeight.Bold), DS.Colors.onSurface,
                Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            Text("kredi", Modifier.alignByBaseline(), style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        }
        Text(
            "Ücretsiz ${SmileCreditFormat.credits(w.freeRemainingCredits)} · satın alınmış ${SmileCreditFormat.credits(w.balanceCredits)}",
            style = sys(12), color = DS.Colors.onSurfaceVariant,
        )
        w.thisMonth?.let { m ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmileStatPill("Bu ay kullanılan", SmileCreditFormat.credits(m.usedCredits), Modifier.weight(1f).fillMaxHeight())
                // ₺ yalnız sunucu verirse (hekim ₺ görmez) ve ödenecek bir tutar varsa.
                val tl = m.purchasedTL
                if (tl != null && tl > 0) SmileStatPill("Bu ay faturaya", SmileCreditFormat.lira(tl), Modifier.weight(1f).fillMaxHeight())
            }
        }
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
        SigdirMetin(value, sys(14, FontWeight.Bold).monoDigits(), DS.Colors.onSurface)
    }
}

/** Ek MO.21n — gülüş simülasyonu kalite fiyatları (sunucunun `tiers`ı; sayı yoksa "—"). */
@Composable
private fun SmileTiersCard(w: SmileWallet, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().glassCard(20.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GÜLÜŞ SİMÜLASYONU · KALİTE BAŞINA", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
        w.tiers.forEach { tier ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tier.label, Modifier.weight(1f), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                Text(GulusBedeli.kart(tier.credits), style = sys(13, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400)
            }
        }
        CreditDivider(0.08f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Otomatik yerleşim düzeltmesi", Modifier.weight(1f), style = sys(13), color = DS.Colors.onSurfaceVariant)
            Text("Ücretsiz", style = sys(13, FontWeight.SemiBold), color = DS.Colors.tertiaryContainer)
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
        remember { SmileCreditsStore.shared.ornekYukle(CreditPreviewData.wallet); 0 }
        SmileCreditsView(reason = "Bu tasarım için yeterli krediniz yok.", onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun SmileCreditsViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        remember { SmileCreditsStore.shared.ornekYukle(CreditPreviewData.wallet); 0 }
        SmileCreditsView(onDismiss = {})
    }
}
