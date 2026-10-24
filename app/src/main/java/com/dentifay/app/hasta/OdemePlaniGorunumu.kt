package com.dentifay.app.hasta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.ApiClient
import com.dentifay.app.home.RandevuSms
import com.dentifay.app.home.fetchSmsKinds
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * "Ödeme Planı" — web `PaymentPlanModal` (DentAIWeb @ 33c26896; M-E8.12): tutar (varsayılan planlanan toplam), taksit sayısı 1–12
 * (varsayılan 12), ilk taksit tarihi (bugünden bir ay sonra aynı gün), nakit taksit planı, banka/POS karşılaştırması; `fin.payment.add`.
 * Plan İşlemleri penceresinin içinde (kaydırılan sütunda) çizilir. Tablolarda adlar kırpılır, tutarlar sağa yaslı kalır.
 */
@Composable
fun OdemePlaniGorunumu(plan: HastaPlani, bitti: (String) -> Unit, vazgec: () -> Unit) {
    val model = LocalHastaSayfasiModeli.current
    val auth = LocalAuthManager.current
    val scope = rememberCoroutineScope()

    var tutarMetni by remember { mutableStateOf("") }
    var adet by remember { mutableStateOf(12) }
    /** Takvim günü (Swift'te UTC saat dilimli `DatePicker`). */
    var ilkVade by remember { mutableStateOf(LocalDate.now()) }
    var poslar by remember { mutableStateOf<List<PosKomisyonu>>(emptyList()) }
    var calisiyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    /** Hastaya SMS (web `NotifyPatientCheckbox`): AUTO → bilgi satırı; ASK → ön seçimsiz Gönder/Gönderme, seçilmeden oluşturulamaz. */
    var smsKipi by remember { mutableStateOf<RandevuSms.Kip>(RandevuSms.Kip.yok) }
    var smsCevap by remember { mutableStateOf<Boolean?>(null) }
    var baski by remember { mutableStateOf<String?>(null) }

    val tutar = ondalikOku(tutarMetni) ?: 0.0
    val taksitler = remember(tutar, adet, ilkVade) { OdemePlani.taksitler(tutar, adet, ilkVade.toString()) }
    val baslik = plan.title?.takeIf { it.isNotEmpty() } ?: "Plan"
    val onayAcik = RandevuSms.onayAcik(smsKipi, smsCevap)

    LaunchedEffect(Unit) {
        val v = plan.plannedTotal?.deger ?: plan.totalPrice?.deger ?: 0.0
        if (tutarMetni.isEmpty() && v > 0) tutarMetni = String.format(Locale.ROOT, "%.2f", v)
        val bugun = OdemePlani.bugun(auth.currentUser?.timezone)
        OdemePlani.varsayilanIlkVade(bugun)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull()?.let { ilkVade = it } }
        coroutineScope {
            val p = async { sessizce { ApiClient.posKomisyonlari() } }
            val k = async { sessizce { ApiClient.fetchSmsKinds() } }
            poslar = p.await() ?: emptyList()
            smsKipi = OdemePlani.smsKipi(k.await())
        }
    }

    fun yazdir() {
        val tablo = taksitler
        val toplam = tutar
        val n = adet
        scope.launch {
            val (ayar, kunye) = coroutineScope {
                val a = async { ApiClient.yazdirmaAyari() }
                val k = async { ApiClient.kurumKunyesi() }
                a.await() to k.await()
            }
            val tz = saatDilimi(auth.currentUser?.timezone)
            baski = withContext(Dispatchers.Default) {
                OdemePlani.baskiHtml(plan.title, model.hasta.ad, toplam, tablo, poslar, n, kunye, ayar, tz)
            }
        }
    }

    fun olustur() {
        if (tutar <= 0) {
            hata = "Geçerli bir tutar girin"
            return
        }
        val toplam = tutar
        val tablo = taksitler
        val n = adet
        scope.launch {
            calisiyor = true
            hata = null
            try {
                ApiClient.odemePlaniOlustur(
                    model.hasta.id, toplam, "Tedavi Planı: $baslik · $n taksit", tablo,
                    hastayaBildir = RandevuSms.notifyPatient(smsKipi, smsCevap),
                )
                Haptics.success()
                bitti("Ödeme planı oluşturuldu.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                hata = e.message?.takeIf { it.isNotEmpty() } ?: "Ödeme planı oluşturulamadı"
            } finally {
                calisiyor = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KayitliOdemePlanlari(yenile = 0)
        Text("$baslik · ${model.hasta.ad} — taksit planı + banka komisyon karşılaştırması", style = sys(12), color = DS.Colors.onSurfaceVariant)
        hata?.let { SonucBandi(basari = false, metin = it) }
        Etiket("Ödeme Tutarı (TL)")
        KutuAlani(tutarMetni, { tutarMetni = it }, "0", klavye = ONDALIK)
        Text("Plan tutarı: ${Para.tl(plan.plannedTotal?.deger ?: plan.totalPrice?.deger)}", style = sys(11), color = DS.Colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Etiket("Taksit Sayısı", Modifier.weight(1f))
            IslemAdimi("$adet", adet, 1..12, { adet = it })
        }
        IslemTarihi("İlk Taksit Tarihi", ilkVade, { ilkVade = it }, yazi = sys(13))
        Etiket("Nakit Taksit Planı · $adet taksit")
        Column {
            for (t in taksitler) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${t.no}. Taksit", Modifier.weight(1f), style = sys(12), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Tarih.asama(t.vade), style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                    Text(
                        Para.tl(t.tutar), Modifier.widthIn(min = 100.dp), style = sys(12, FontWeight.SemiBold).monoDigits(),
                        color = DS.Colors.onSurface, textAlign = TextAlign.End, maxLines = 1,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
            }
        }
        Karsilastirma(poslar, tutar, adet)
        when (val k = smsKipi) {
            is RandevuSms.Kip.otomatik -> Text(RandevuSms.otomatikMetni(k.tur), style = sys(12), color = DS.Colors.onSurfaceVariant)
            is RandevuSms.Kip.sor -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(RandevuSms.soruMetni(k.tur), style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmsSecenegi("Gönder", smsCevap == true) { smsCevap = true }
                    SmsSecenegi("Gönderme", smsCevap == false) { smsCevap = false }
                }
                if (smsCevap == null) Text(RandevuSms.secimsizMetni, style = sys(11), color = hex("FCD34D"))
            }
            RandevuSms.Kip.yok -> Unit
        }
        Row(
            Modifier.plainClickable { yazdir() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DfyIcon("printer", size = 13.dp, tint = DS.Colors.cyan400)
            Text("Yazdır", style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400)
        }
        BirincilDugme("Ödeme Planı Oluştur", etkin = !calisiyor && onayAcik, calisiyor = calisiyor, eylem = { olustur() })
        SecondaryButton(onClick = vazgec, height = 46.dp) { ButtonLabel("Vazgeç") }
    }
    BaskiOnizlemePenceresi("Ödeme Planı", baski) { baski = null }
}

@Composable
private fun Etiket(s: String, modifier: Modifier = Modifier) {
    Text(s, modifier, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
}

/** Banka / POS karşılaştırması — taksit başına tutar; en avantajlı hücre yeşil. */
@Composable
private fun Karsilastirma(poslar: List<PosKomisyonu>, tutar: Double, adet: Int) {
    Etiket("Banka / POS Karşılaştırması (taksit başına tutar)")
    if (poslar.isEmpty()) {
        Text(
            "Kayıtlı POS komisyon oranı yok. Ayarlar → Finans → Banka/POS Komisyon Oranları'ndan ekleyin.",
            style = sys(11), color = DS.Colors.onSurfaceVariant,
        )
        return
    }
    val tek = poslar.map { p -> p.oran(1)?.let { tutar * (1 + it / 100) } }
    val cok = poslar.map { p -> p.oran(adet)?.let { tutar * (1 + it / 100) } }
    val enTek = tek.filterNotNull().minOrNull()
    val enCok = cok.filterNotNull().minOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Banka / POS", Modifier.weight(1f), style = sys(10, FontWeight.Bold), color = DS.Colors.onSurfaceVariant, maxLines = 1)
            Text("Tek Çekim", Modifier.widthIn(min = 90.dp), style = sys(10, FontWeight.Bold), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1)
            Text("$adet Taksit", Modifier.widthIn(min = 90.dp), style = sys(10, FontWeight.Bold), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1)
        }
        poslar.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.ad, Modifier.weight(1f), style = sys(12), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hucre(tek[i], enTek, 1)
                Hucre(cok[i], enCok, adet)
            }
        }
        Text("yeşil = en avantajlı", style = sys(10), color = DS.Colors.onSurfaceVariant)
    }
}

@Composable
private fun Hucre(toplam: Double?, en: Double?, bol: Int) {
    Text(
        toplam?.let { Para.tl(it / maxOf(1, bol).toDouble()) } ?: "—",
        Modifier.widthIn(min = 90.dp),
        style = sys(12).monoDigits(),
        color = if (toplam != null && toplam == en) hex("34D399") else DS.Colors.onSurface,
        textAlign = TextAlign.End, maxLines = 1,
    )
}

@Composable
private fun SmsSecenegi(ad: String, secili: Boolean, sec: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.04f))
            .plainClickable(onClick = sec)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val renk = if (secili) DS.Colors.cyan400 else DS.Colors.onSurface
        DfyIcon(if (secili) "largecircle.fill.circle" else "circle", size = 13.dp, tint = renk)
        Text(ad, style = sys(13), color = renk)
    }
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 1400)
@Composable
private fun OdemePlaniGorunumuPreview() {
    PreviewEnv {
        OrnekModelSaglayici {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                OdemePlaniGorunumu(OrnekIslemVerisi.plan, bitti = {}, vazgec = {})
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun OdemePlaniGorunumuTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OrnekModelSaglayici {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    OdemePlaniGorunumu(OrnekIslemVerisi.plan, bitti = {}, vazgec = {})
                }
            }
        }
    }
}
