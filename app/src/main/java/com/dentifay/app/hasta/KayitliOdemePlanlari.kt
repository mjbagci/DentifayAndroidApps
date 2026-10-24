package com.dentifay.app.hasta

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.ApiClient
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.YetkiliButon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * "Kayıtlı Ödeme Planları" — web hasta kartı listesi (DentAIWeb @ 160244a5 `PatientDetailPage` + `OdemePlaniEylemleri`; M-E9.2).
 * `fin.view` yoksa istek atılmaz; sil / elle "Ödendi" işaretle / geri al `fin.payment.add` (yetkisizde sönük — M-E7.4); düzenleme sunucuda
 * `fin.payment.add`. Boşken hiçbir şey çizmez. `yenile` değişince yeniden okunur.
 */
@Composable
fun KayitliOdemePlanlari(yenile: Int) {
    val model = LocalHastaSayfasiModeli.current
    val caps = LocalAuthManager.current.yetenekler
    val scope = rememberCoroutineScope()

    var planlar by remember { mutableStateOf<List<KayitliOdemePlani>>(emptyList()) }
    var acik by remember { mutableStateOf<String?>(null) }
    var duzenlenen by remember { mutableStateOf<String?>(null) }
    val satirlar = remember { mutableStateListOf<DuzenSatiri>() }
    var odendiUyarisiVerildi by remember { mutableStateOf(false) }
    var degisti by remember { mutableStateOf(false) }
    var cikisSorusu by remember { mutableStateOf(false) }
    var silinecek by remember { mutableStateOf<KayitliOdemePlani?>(null) }
    var isaretlenecek by remember { mutableStateOf<KayitliOdemePlani.Taksit?>(null) }
    var mesaj by remember { mutableStateOf<IslemSonucu?>(null) }
    var calisiyor by remember { mutableStateOf(false) }

    suspend fun yukle() {
        if ("fin.view" !in caps) {   // web: yetki yoksa istek atılmaz
            planlar = emptyList()
            return
        }
        planlar = sessizce { ApiClient.odemePlanlari(model.hasta.id) } ?: emptyList()
    }

    LaunchedEffect(yenile) { yukle() }

    fun duzenlemeyeBasla(p: KayitliOdemePlani) {
        satirlar.clear()
        satirlar.addAll(
            p.installments.map {
                DuzenSatiri(
                    id = it.id, yeni = false, tutar = String.format(Locale.ROOT, "%.2f", it.amount.deger),
                    vade = it.dueDate?.let { d -> runCatching { LocalDate.parse(d.take(10)) }.getOrNull() }, durum = it.status,
                )
            },
        )
        degisti = false
        odendiUyarisiVerildi = false
        mesaj = null
        duzenlenen = p.id
    }

    fun kaydet(id: String) {
        val govde = satirlar.map { s ->
            DuzenTaksiti(id = if (s.yeni) null else s.id, tutar = ondalikOku(s.tutar) ?: 0.0, vade = s.vade?.toString(), durum = s.durum)
        }
        scope.launch {
            calisiyor = true
            try {
                val dusen = ApiClient.odemePlaniDuzenle(id, govde)
                mesaj = if (dusen.isEmpty()) IslemSonucu(true, "Ödeme planı güncellendi.")
                else IslemSonucu(true, "Tutarı değişen taksidin elle \"Ödendi\" işareti kaldırıldı.")
                duzenlenen = null
                degisti = false
                yukle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mesaj = IslemSonucu(false, e.message?.takeIf { it.isNotEmpty() } ?: "Ödeme planı güncellenirken hata oluştu.")
            } finally {
                calisiyor = false
            }
        }
    }

    fun sil(p: KayitliOdemePlani) {
        scope.launch {
            try {
                ApiClient.odemePlaniSil(p.id)
                mesaj = IslemSonucu(true, "Ödeme planı silindi")
                yukle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mesaj = IslemSonucu(false, hataMetni(e))
            }
        }
    }

    fun isaretle(t: KayitliOdemePlani.Taksit, geriAl: Boolean) {
        scope.launch {
            try {
                ApiClient.taksitIsaretle(t.id, geriAl)
                mesaj = IslemSonucu(true, if (geriAl) "Taksitin elle Ödendi işareti geri alındı" else "Taksit elle Ödendi işaretlendi")
                yukle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mesaj = IslemSonucu(false, hataMetni(e))
            }
        }
    }

    // Boşken de bileşen ağaçta kalır (yükleme etkisi çalışsın) — yalnız içerik çizilmez.
    if (planlar.isNotEmpty()) {
        val sekil = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(sekil)
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, DS.Colors.hairline, sekil)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Kayıtlı Ödeme Planları", style = sys(14, FontWeight.Bold), color = DS.Colors.onSurface)
                    Text("Aktif taksitlendirme planları", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
                DurumHapi(metin = "${planlar.size} Plan", renk = DS.Colors.cyan400)
            }
            mesaj?.let { SonucBandi(basari = it.basari, metin = it.metin) }
            for (p in planlar) {
                PlanKarti(p, acik = acik == p.id, degistir = { acik = if (acik == p.id) null else p.id }) {
                    if (duzenlenen == p.id) {
                        Duzenleme(
                            satirlar = satirlar,
                            calisiyor = calisiyor,
                            tutarDegisti = { i, yeni ->
                                val s = satirlar[i]
                                if (s.durum == "PAID" && !odendiUyarisiVerildi && yeni != s.tutar) {
                                    odendiUyarisiVerildi = true
                                    mesaj = IslemSonucu(
                                        false,
                                        "Uyarı: Önceden \"Ödendi\" olarak işaretlenmiş bir taksitin tutarını değiştiriyorsunuz. Bu finansal tutarsızlığa yol açabilir.",
                                    )
                                }
                                satirlar[i] = s.copy(tutar = yeni)
                                degisti = true
                            },
                            satirDegisti = { i, yeni ->
                                satirlar[i] = yeni
                                degisti = true
                            },
                            satirSil = { id ->
                                satirlar.removeAll { it.id == id }
                                degisti = true
                            },
                            taksitEkle = {
                                satirlar.add(DuzenSatiri(id = "temp-${UUID.randomUUID()}", yeni = true, tutar = "0", vade = null, durum = "PENDING"))
                                degisti = true
                            },
                            iptal = { if (degisti) cikisSorusu = true else duzenlenen = null },
                            kaydet = { kaydet(p.id) },
                        )
                    } else {
                        Ayrinti(
                            p,
                            geriAl = { t -> isaretle(t, geriAl = true) },
                            isaretle = { t -> isaretlenecek = t },
                            duzenle = { duzenlemeyeBasla(p) },
                            sil = { silinecek = p },
                        )
                    }
                }
            }
        }
    }

    val silinen = silinecek
    IslemOnayi(
        acik = silinen != null,
        baslik = "Ödeme planını sil",
        mesaj = silinen?.let { OdemePlani.silmeMetni(it) },
        secenekler = listOf(OnaySecenegi("Ödeme planını sil", yikici = true) { silinecek?.let { sil(it) } }),
        kapat = { silinecek = null },
    )
    IslemOnayi(
        acik = isaretlenecek != null,
        baslik = "Ödendi işaretle",
        mesaj = OdemePlani.ISARET_METNI,
        secenekler = listOf(OnaySecenegi("Ödendi işaretle") { isaretlenecek?.let { isaretle(it, geriAl = false) } }),
        kapat = { isaretlenecek = null },
    )
    IslemOnayi(
        acik = cikisSorusu,
        baslik = "Kaydedilmemiş Değişiklikler",
        mesaj = "Ödeme planı düzenlemeleriniz henüz kaydedilmedi. Çıkmadan önce n'apalım?",
        secenekler = listOf(
            OnaySecenegi("Değişiklikleri Sil", yikici = true) {
                duzenlenen = null
                degisti = false
            },
            OnaySecenegi("Kaydet & Kapat") { duzenlenen?.let { kaydet(it) } },
        ),
        kapat = { cikisSorusu = false },
        vazgecMetni = "İptal",
    )
}

/** Düzenlenen taksit satırı (Swift `DuzenSatiri`): `vade` takvim günü (UTC), `null` → gövdede `dueDate: null`. */
private data class DuzenSatiri(val id: String, val yeni: Boolean, val tutar: String, val vade: LocalDate?, val durum: String)

@Composable
private fun PlanKarti(
    p: KayitliOdemePlani,
    acik: Boolean,
    degistir: () -> Unit,
    icerik: @Composable () -> Unit,
) {
    val oz = OdemePlani.ozet(p)
    val tamam = p.status == "COMPLETED"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .animateContentSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().plainClickable(onClick = degistir), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${p.installments.size} Taksitli Ödeme Planı", style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
                Text("${Tarih.plan(p.createdAt)} tarihinde oluşturuldu", style = sys(11), color = DS.Colors.onSurfaceVariant)
                if (oz.elle > 0) Text("Elle kapatılan ${oz.elle} taksit", style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Para.tl(p.totalAmount.deger), style = sys(13, FontWeight.Bold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1)
                DurumHapi(metin = if (tamam) "Tamamlandı" else "Aktif", renk = if (tamam) hex("34D399") else hex("FCD34D"))
            }
            DfyIcon(if (acik) "chevron.up" else "chevron.down", size = 11.dp, tint = DS.Colors.outline)
        }
        if (acik) icerik()
    }
}

@Composable
private fun Ayrinti(
    p: KayitliOdemePlani,
    geriAl: (KayitliOdemePlani.Taksit) -> Unit,
    isaretle: (KayitliOdemePlani.Taksit) -> Unit,
    duzenle: () -> Unit,
    sil: () -> Unit,
) {
    Text("Taksit Detayları", style = sys(11, FontWeight.Bold), color = DS.Colors.onSurfaceVariant)
    p.installments.forEachIndexed { i, t ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Dar pencerede taşmasın: ad kırpılır; tutar ve durum sağda alt alta.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}. Taksit", Modifier.weight(1f), style = sys(12), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Tarih.asama(t.dueDate), style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        Para.tl(t.amount.deger), Modifier.widthIn(min = 76.dp), style = sys(12, FontWeight.SemiBold).monoDigits(),
                        color = DS.Colors.onSurface, textAlign = TextAlign.End, maxLines = 1,
                    )
                    DurumHapi(metin = OdemePlani.durumAdi[t.status] ?: t.status, renk = taksitRengi(t.status))
                }
            }
            val elle = t.manualPaidAt
            if (elle != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t.manualPaidByAd?.let { "Elle işaretlendi · $it · ${Tarih.plan(elle)}" } ?: "Elle işaretlendi · ${Tarih.plan(elle)}",
                        Modifier.weight(1f), style = sys(10), color = DS.Colors.onSurfaceVariant,
                    )
                    YetkiliButon(listOf("fin.payment.add"), eylem = { geriAl(t) }) { mod -> EylemEtiketi("İşareti geri al", mod) }
                }
            } else if (t.status == "PENDING" || t.status == "PARTIAL") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    YetkiliButon(listOf("fin.payment.add"), eylem = { isaretle(t) }) { mod -> EylemEtiketi("Ödendi işaretle", mod) }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EylemEtiketi("Düzenle", Modifier.plainClickable(onClick = duzenle))
        Box(Modifier.weight(1f))
        YetkiliButon(listOf("fin.payment.add"), eylem = sil) { mod ->
            Text("Ödeme planını sil", mod.padding(vertical = 6.dp), style = sys(12, FontWeight.SemiBold), color = DS.Colors.danger)
        }
    }
}

@Composable
private fun Duzenleme(
    satirlar: List<DuzenSatiri>,
    calisiyor: Boolean,
    tutarDegisti: (Int, String) -> Unit,
    satirDegisti: (Int, DuzenSatiri) -> Unit,
    satirSil: (String) -> Unit,
    taksitEkle: () -> Unit,
    iptal: () -> Unit,
    kaydet: () -> Unit,
) {
    satirlar.forEachIndexed { i, s ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IslemTarihi(null, s.vade ?: LocalDate.now(), { satirDegisti(i, s.copy(vade = it)) }, Modifier.weight(1f, fill = false))
                KutuAlani(s.tutar, { tutarDegisti(i, it) }, "Tutar", Modifier.weight(1f), klavye = ONDALIK)
                Box(
                    Modifier.heightIn(min = 36.dp).plainClickable { satirSil(s.id) }.padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { DfyIcon("trash", size = 13.dp, tint = DS.Colors.danger) }
            }
            if (s.durum == "PAID") {
                DurumHapi(metin = "Ödendi", renk = hex("34D399"))
            } else {
                val ilk = if (s.durum == "PARTIAL") "PARTIAL" else "PENDING"
                IslemSegmenti(
                    listOf(if (s.durum == "PARTIAL") "Kısmen Ödendi" else "Bekliyor", "İptal"),
                    if (s.durum == "CANCELLED") 1 else 0,
                    { satirDegisti(i, s.copy(durum = if (it == 1) "CANCELLED" else ilk)) },
                )
                Text(
                    "Durum ödemeden otomatik güncellenir. Taksidi kapatmak için Ödeme Al'da bu taksiti seçin.",
                    style = sys(10), color = DS.Colors.onSurfaceVariant,
                )
            }
        }
    }
    EylemEtiketi("Taksit Ekle", Modifier.plainClickable(onClick = taksitEkle))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(onClick = iptal, modifier = Modifier.weight(1f), height = 44.dp) { ButtonLabel("İptal") }
        BirincilDugme("Kaydet", etkin = !calisiyor, calisiyor = calisiyor, eylem = kaydet, modifier = Modifier.weight(1f), yukseklik = 44.dp)
    }
}

@Composable
private fun EylemEtiketi(s: String, modifier: Modifier = Modifier) {
    Text(s, modifier.padding(vertical = 6.dp), style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1)
}

private fun taksitRengi(d: String): Color = when (d) {
    "PAID" -> hex("34D399")
    "CANCELLED" -> hex("FB7185")
    else -> hex("FCD34D")
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 760)
@Composable
private fun KayitliOdemePlanlariPreview() {
    PreviewEnv {
        OrnekModelSaglayici {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { KayitliOdemePlanlariOrnek() }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun KayitliOdemePlanlariTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OrnekModelSaglayici {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { KayitliOdemePlanlariOrnek() }
            }
        }
    }
}

/** Önizlemede ağ yok: kart görünümü örnek planla (`Ayrinti`), ayrıntı açık. */
@Composable
private fun KayitliOdemePlanlariOrnek() {
    val p = OrnekIslemVerisi.odemePlanlari.first()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlanKarti(p, acik = true, degistir = {}) {
            Ayrinti(p, geriAl = {}, isaretle = {}, duzenle = {}, sil = {})
        }
    }
}
