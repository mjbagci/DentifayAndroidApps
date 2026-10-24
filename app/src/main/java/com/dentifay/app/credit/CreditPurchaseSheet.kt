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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * KREDİ PAKETİ SATIN ALMA — özet + Kredi Satın Alma Koşulları + açık onay.
 *
 * Web'deki `features/krediler/SatinAlmaPenceresi.jsx`in mobil karşılığı; Kredi Yönetimi › Kredi Yükle bu
 * pencereyi açar.
 *
 * · Koşul metni SUNUCUDAN gelir (`GET {API}/ai-credits/purchase-terms?packId=`): paketten ve
 *   kliniğin tarifesinden türer; tutar kliniğin sözleşmesindeki kredi indirimini içerir.
 * · "Satın al" yalnız metin yüklendiyse VE onay işaretliyse açılır — sessiz kabul yok (`SatinAlmaDurumu`).
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
    var durum by remember { mutableStateOf(SatinAlmaDurumu(terms = previewTerms)) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var buying by remember { mutableStateOf(false) }
    var buyError by remember { mutableStateOf<String?>(null) }

    val terms = durum.terms
    val notice = durum.not

    suspend fun loadTerms() {
        loading = true
        loadError = null
        try {
            durum = durum.copy(terms = store.purchaseTerms(pack))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            durum = durum.copy(terms = null, kabul = false)
            loadError = "Satın alma koşulları yüklenemedi; tekrar deneyin."
        } finally {
            loading = false
        }
    }

    suspend fun buy() {
        val t = durum.terms
        if (t == null || !durum.gonderilebilir || buying) return
        buying = true
        buyError = null
        durum = durum.copy(not = null)
        try {
            val r = store.purchase(pack, t)
            onPurchased(r)
            onDismiss()
        } catch (e: SmileCreditsClient.ClientError.termsOutdated) {
            // Metin değişti (ya da kabul geçersiz sayıldı): güncel metni göster, onayı boşalt.
            Haptics.warning()
            durum = durum.kosulGuncellendi(e.terms, e.m)
            if (e.terms == null) loadTerms()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            buyError = (e as? SmileCreditsClient.ClientError)?.message ?: "Kredi satın alınamadı."
        } finally {
            buying = false
        }
    }

    LaunchedEffect(Unit) { if (previewTerms == null) loadTerms() }
    // `.interactiveDismissDisabled(buying)` — satın alma sürerken geri tuşu pencereyi kapatmaz.
    BackHandler(enabled = buying) {}

    /** Faturaya yansıyacak tutar: koşul metnindeki ödenecek bedel (sözleşme indirimi dahil); metin gelene dek paketin fiyatı. */
    val payableTL = terms?.odenecekTL ?: pack.priceTL ?: 0.0
    val canBuy = durum.gonderilebilir && !buying

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar(
            "Kredi paketi satın al",
            leading = { CreditBarButton("Vazgeç", enabled = !buying) { onDismiss() } },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.icerikGenisligi(640.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Özet
                Column(
                    Modifier.fillMaxWidth().glassCard(18.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${SmileCreditFormat.credits(pack.credits)} kredi", style = sys(20, FontWeight.Bold), color = DS.Colors.onSurface)
                    if (terms != null && terms.indirimVar) {
                        Text(
                            "${SmileCreditFormat.lira(terms.listeTL)} yerine · %${pack.discountPct?.let { SmileCreditFormat.credits(it) } ?: ""} sözleşme indirimi",
                            style = sys(12), color = DS.Colors.tertiaryContainer,
                        )
                    }
                    Text(
                        "${SmileCreditFormat.credits(pack.credits)} kredi kliniğinize hemen eklenir; ${SmileCreditFormat.lira(payableTL)} bu ayın Dentifay faturasına yansır.",
                        style = sys(13), color = DS.Colors.onSurfaceVariant,
                    )
                }

                // Koşullar
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DfyIcon("doc.text", tint = DS.Colors.onSurfaceVariant)
                        Text(
                            terms?.baslik ?: "Kredi Satın Alma Koşulları", Modifier.weight(1f),
                            style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface,
                        )
                    }
                    if (terms != null) {
                        Text("Sürüm ${terms.surum} · ${terms.ozet}", style = sys(11), color = DS.Colors.onSurfaceVariant)
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
                        when {
                            loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CreditSpinner() }
                            err != null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(err, style = sys(13), color = DS.Colors.error)
                                Text(
                                    "Tekrar dene",
                                    Modifier.semantics { role = Role.Button }.plainClickable { scope.launch { loadTerms() } },
                                    style = sys(13, FontWeight.SemiBold), color = CreditVurgu,
                                )
                            }
                            terms != null -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                terms.bolumler.forEach { bolum ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(bolum.baslik, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
                                        bolum.maddeler.forEach { madde ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("•", style = sys(12.5), color = DS.Colors.onSurfaceVariant)
                                                Text(madde, Modifier.weight(1f), style = sys(12.5), color = DS.Colors.onSurfaceVariant)
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
                        .semantics {
                            role = Role.Checkbox
                            stateDescription = if (durum.kabul) "işaretli" else "işaretsiz"
                        }
                        .plainClickable(enabled = acceptEnabled) {
                            durum = durum.copy(kabul = !durum.kabul)
                            Haptics.selection()
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DfyIcon(
                        if (durum.kabul) "checkmark.square.fill" else "square", size = 20.dp,
                        tint = if (durum.kabul) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant,
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
        }

        // Satın al şeridi (`.safeAreaInset(edge: .bottom)`)
        Box(
            Modifier
                .fillMaxWidth()
                .background(DS.Colors.background.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier
                    .icerikGenisligi(640.dp)
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 52.dp)
                    .alpha(if (canBuy) 1f else 0.45f)
                    .semantics { role = Role.Button }
                    .pressable(enabled = canBuy) { scope.launch { buy() } }
                    .clip(RoundedCornerShape(50))
                    .background(DS.Gradients.primaryGradient)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (buying) CreditSpinner(color = Color.White, size = 18.dp) else DfyIcon("cart", tint = Color.White)
                Text(
                    "Satın al · ${SmileCreditFormat.lira(payableTL)}",
                    style = sys(15, FontWeight.Bold), color = Color.White, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditPurchaseSheetPreview() {
    PreviewEnv {
        val pack = CreditPreviewData.packs[1]
        CreditPurchaseSheetContent(pack, onPurchased = {}, onDismiss = {}, previewTerms = CreditPreviewData.kosullar(pack))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 640, heightDp = 800)
@Composable
private fun CreditPurchaseSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        val pack = CreditPreviewData.packs[0]
        CreditPurchaseSheetContent(pack, onPurchased = {}, onDismiss = {}, previewTerms = CreditPreviewData.kosullar(pack))
    }
}
