package com.dentifay.app.credit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * KREDİ PAKETİ SATIN ALMA — özet + Kredi Satın Alma Koşulları + açık onay.
 *
 * Web'deki `features/krediler/SatinAlmaPenceresi.jsx`in mobil karşılığı; iki giriş noktası
 * (Kredi Yönetimi › Kredi Yükle ve Gülüş Simülasyonu › Krediler) AYNI pencereyi açar.
 *
 * · Koşul metni SUNUCUDAN gelir (`GET {API}/ai-credits/purchase-terms?packId=`): paketten ve
 *   kliniğin tarifesinden türer; tutar kliniğin sözleşmesindeki kredi indirimini içerir.
 * · "Satın al" yalnız metin yüklendiyse VE onay işaretliyse açılır — sessiz kabul yok.
 *   Sunucu da kabulsüz satın almayı reddeder (428).
 * · Sunucu metni değişmiş bulursa (409) güncel metin gösterilir ve onay yeniden boşalır.
 * · ⚖ Metin Türkçe SABİTTİR (hukuki belge).
 *
 * `onPurchased` başarılı satın almadan SONRA, pencere kapanmadan önce çağrılır.
 */
@Composable
fun CreditPurchaseSheet(
    pack: SmileCreditPack,
    onPurchased: suspend (SmilePurchaseResult) -> Unit,
    onDismiss: () -> Unit,
) {
    CreditPurchaseSheetContent(pack, onPurchased, onDismiss, previewTerms = null)
}

@Composable
private fun CreditPurchaseSheetContent(
    pack: SmileCreditPack,
    onPurchased: suspend (SmilePurchaseResult) -> Unit,
    onDismiss: () -> Unit,
    previewTerms: SmilePurchaseTerms?,
) {
    val store = SmileCreditsStore.shared
    val scope = rememberCoroutineScope()
    var terms by remember { mutableStateOf(previewTerms) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    var buyError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun loadTerms() {
        loading = true
        loadError = null
        try {
            terms = store.purchaseTerms(pack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terms = null
            accepted = false
            loadError = creditErrorText(e)
        } finally {
            loading = false
        }
    }

    suspend fun buy() {
        val t = terms
        if (t == null || !accepted || buying) return
        buying = true
        buyError = null
        notice = null
        try {
            val r = store.purchase(pack, t)
            onPurchased(r)
            onDismiss()
        } catch (e: SmileCreditsClient.ClientError.termsOutdated) {
            // Metin değişti (ya da kabul geçersiz sayıldı): güncel metni göster, onayı boşalt.
            Haptics.warning()
            accepted = false
            val guncel = e.terms
            if (guncel != null) terms = guncel else loadTerms()
            notice = e.m ?: "Satın alma koşulları güncellendi; yeni metni okuyup yeniden onaylayın."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            buyError = creditErrorText(e)
        } finally {
            buying = false
        }
    }

    LaunchedEffect(Unit) { if (previewTerms == null) loadTerms() }
    // `.interactiveDismissDisabled(buying)` — satın alma sürerken geri tuşu pencereyi kapatmaz.
    BackHandler(enabled = buying) {}

    /** Faturaya yansıyacak tutar: koşul metnindeki ödenecek bedel (sözleşme indirimi dahil); metin gelene dek liste fiyatı. */
    val payableTL = terms?.odenecekTL ?: pack.priceTL
    val canBuy = terms != null && accepted && !buying

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar(
            "Kredi paketi satın al",
            leading = { CreditBarButton("Vazgeç", enabled = !buying) { onDismiss() } },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Özet
            Column(
                Modifier.fillMaxWidth().glassCard(18.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${SmileCreditFormat.credits(pack.credits)} kredi", style = sys(20, FontWeight.Bold), color = DS.Colors.onSurface)
                Text(
                    "${SmileCreditFormat.credits(pack.credits)} kredi kliniğinize hemen eklenir; ${SmileCreditFormat.lira(payableTL)} bu ayın Dentifay faturasına yansır.",
                    style = sys(13), color = DS.Colors.onSurfaceVariant,
                )
            }

            // Koşullar
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon("doc.text", tint = DS.Colors.onSurfaceVariant)
                    Text(terms?.baslik ?: "Kredi Satın Alma Koşulları", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                }
                terms?.let { t ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.taslak == true) {
                            Text(
                                "Taslak — hukukçu onayı bekliyor",
                                Modifier
                                    .border(1.dp, DS.Colors.warning.copy(alpha = 0.5f), RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                style = sys(11, FontWeight.SemiBold), color = DS.Colors.warning,
                            )
                        }
                        Text("Sürüm ${t.surum}", style = sys(11), color = DS.Colors.onSurfaceVariant)
                    }
                }
                val bodyShape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(bodyShape)
                        .background(DS.Colors.surfaceContainerLowest)
                        .border(1.dp, DS.Colors.hairline, bodyShape)
                        .padding(14.dp),
                ) {
                    val err = loadError
                    val t = terms
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CreditSpinner() }
                        err != null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(err, style = sys(13), color = DS.Colors.error)
                            Text(
                                "Tekrar dene",
                                Modifier.plainClickable { scope.launch { loadTerms() } },
                                style = sys(13, FontWeight.SemiBold), color = CreditSystemBlue,
                            )
                        }
                        t != null -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            t.bolumler.forEach { bolum ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(bolum.baslik, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
                                    bolum.maddeler.forEach { madde ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("•", style = sys(12.5), color = DS.Colors.onSurfaceVariant)
                                            Text(madde, style = sys(12.5), color = DS.Colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Onay
            val acceptEnabled = terms != null && !buying
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (terms == null) 0.5f else 1f)
                    .plainClickable(enabled = acceptEnabled) {
                        accepted = !accepted
                        Haptics.selection()
                    }
                    .semantics { stateDescription = if (accepted) "işaretli" else "işaretsiz" },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DfyIcon(
                    if (accepted) "checkmark.square.fill" else "square", size = 20.dp,
                    tint = if (accepted) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant,
                )
                Text(
                    "Kredi Satın Alma Koşulları'nı okudum ve kabul ediyorum.",
                    Modifier.weight(1f), style = sys(13), color = DS.Colors.onSurface,
                )
            }

            notice?.let {
                CreditIconLabel(it, "arrow.triangle.2.circlepath", size = 12f, color = DS.Colors.warning)
            }
            buyError?.let {
                Text(it, style = sys(13), color = DS.Colors.error)
            }
        }

        // Satın al şeridi (`.safeAreaInset(edge: .bottom)`)
        Box(
            Modifier
                .fillMaxWidth()
                .background(DS.Colors.background.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(if (canBuy) 1f else 0.45f)
                    .pressable(enabled = canBuy) { scope.launch { buy() } }
                    .clip(RoundedCornerShape(50))
                    .background(DS.Gradients.primaryGradient),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (buying) CreditSpinner(color = Color.White, size = 18.dp) else DfyIcon("cart", tint = Color.White)
                Text("Satın al · ${SmileCreditFormat.lira(payableTL)}", style = sys(15, FontWeight.Bold), color = Color.White)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditPurchaseSheetPreview() {
    PreviewEnv {
        CreditPurchaseSheetContent(
            pack = CreditPreviewData.packs[1],
            onPurchased = {},
            onDismiss = {},
            previewTerms = CreditPreviewData.terms,
        )
    }
}
