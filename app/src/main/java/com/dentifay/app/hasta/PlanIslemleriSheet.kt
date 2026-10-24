package com.dentifay.app.hasta

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIDoctorMember
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.HakedisIptal
import com.dentifay.app.core.HakedisOnayi
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.openSystemUrl
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * "Plan İşlemleri" — web `PlanActionsModal` (DentAIWeb @ afc3170; MOBIL_IS_EMRI M-E8.4). Paylaşım Android uygulamalarıyla (WhatsApp ·
 * SMS · e-posta, web'in paylaşım metni). Bölüm B: Yazdır (istemcide HTML → yazdırma penceresi / PDF) ve Ödeme Planı.
 *
 * Çağıran `DfySheet` içinde açar; `onDuzenle` "Planı düzenle"de pencere kapanırken çağrılır (Swift'teki sondaki kapanış — sayfa
 * düzenleme penceresini açar).
 */
@Composable
fun PlanIslemleriSheet(plan: HastaPlani, onDismiss: () -> Unit, onDuzenle: () -> Unit) {
    val model = LocalHastaSayfasiModeli.current
    val auth = LocalAuthManager.current
    val caps = auth.yetenekler
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var gorunum by remember { mutableStateOf(PlanGorunumu.menu) }
    var calisiyor by remember { mutableStateOf(false) }
    var sonuc by remember { mutableStateOf<IslemSonucu?>(null) }
    var ad by remember { mutableStateOf("") }
    var not by remember { mutableStateOf("") }
    var hekimler by remember { mutableStateOf<List<APIDoctorMember>>(emptyList()) }
    var yeniHekim by remember { mutableStateOf("") }
    var onay by remember { mutableStateOf<PlanOnayi?>(null) }
    /** Baskı önizlemesinin HTML'i (M-E9.1). */
    var baski by remember { mutableStateOf<String?>(null) }

    fun durum(i: IslemKurallari.PlanIslemi) = IslemKurallari.planDurumu(i, plan, caps)
    val baslik = plan.title?.takeIf { it.isNotEmpty() } ?: "Plan"
    val telefon = model.hasta.telefon?.takeIf { it.isNotEmpty() }

    /** Web: plan onay bekliyorsa pasif (`E_PLAN_AWAITING_APPROVAL`); yetki `fin.payment.add`. */
    val odemeDurumu = when {
        "fin.payment.add" !in caps -> IslemKurallari.Durum.pasif(IslemKurallari.YETKI_YOK)
        plan.onayBekliyor == true -> IslemKurallari.Durum.pasif("Bu plan hekim onayı bekliyor; onaylanmadan bu işlem yapılamaz.")
        else -> IslemKurallari.Durum.acik
    }

    suspend fun uygula(mesaj: String, yap: suspend () -> Unit) {
        calisiyor = true
        try {
            HakedisOnayi.korumali { yap() }
            sonuc = IslemSonucu(true, mesaj)
            Haptics.success()
            model.tazele()
            delay(700)
            onDismiss()
        } catch (_: HakedisIptal) {
            // İptal: hiçbir şey yazılmadı, hata gösterilmez (web `HakedisIptal`).
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            sonuc = IslemSonucu(false, hataMetni(e))
        } finally {
            calisiyor = false
        }
    }

    fun uygulaBaslat(mesaj: String, yap: suspend () -> Unit) {
        scope.launch { uygula(mesaj, yap) }
    }

    // MARK: Yazdır (sunucu PDF'i yok — web gibi istemcide)

    fun yazdir() {
        scope.launch {
            val (ayar, kunye) = coroutineScope {
                val a = async { ApiClient.yazdirmaAyari() }
                val k = async { ApiClient.kurumKunyesi() }
                a.await() to k.await()
            }
            val ayrinti = model.ayrintilar[plan.id] ?: plan
            val tz = saatDilimi(auth.currentUser?.timezone)
            val sema = if (ayar.sema && PlanCiktisi.semaVarMi(ayrinti)) SemaGoruntusu.png(context, ayrinti.items ?: emptyList()) else null
            baski = withContext(Dispatchers.Default) { PlanCiktisi.html(ayrinti, model.hasta, kunye, ayar, tz, semaPNG = sema) }
        }
    }

    fun hekimAc() {
        yeniHekim = plan.doctorUserId ?: plan.doctor?.id ?: ""
        gorunum = PlanGorunumu.hekim
        scope.launch { hekimler = sessizce { ApiClient.fetchAssignableDoctors() } ?: emptyList() }
    }

    // MARK: Paylaşım (web bağlantıları)

    fun paylas(k: PaylasimKanali) {
        val ayrinti = model.ayrintilar[plan.id] ?: plan
        val metin = IslemKurallari.paylasimMetni(ayrinti, model.hasta.ad)
        val kodlu = sorguKodla(metin)
        when (k) {
            PaylasimKanali.whatsapp -> {
                val rakam = (telefon ?: "").filter { it.isDigit() }
                openSystemUrl(context, if (rakam.isEmpty()) "https://wa.me/?text=$kodlu" else "https://wa.me/$rakam?text=$kodlu")
            }
            // iOS `sms:<no>&body=` biçimi Android'de çalışmaz → `smsto:` + `sms_body` (Android karşılığı).
            PaylasimKanali.sms -> smsAc(context, (telefon ?: "").filter { it.isDigit() || it == '+' }, metin)
            PaylasimKanali.eposta -> openSystemUrl(context, "mailto:?subject=${sorguKodla(plan.title ?: "Tedavi Planı")}&body=$kodlu")
        }
    }

    HakedisOnayiKaplamasi {
        Column(Modifier.fillMaxSize()) {
            PencereBasligi("Plan İşlemleri", solMetni = "Kapat", solEylem = onDismiss)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(baslik, style = sys(13), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                sonuc?.let { SonucBandi(basari = it.basari, metin = it.metin) }
                when (gorunum) {
                    PlanGorunumu.menu -> {
                        IslemSatiri(
                            "pencil", "Planı düzenle", "Tedavileri, dişleri ve indirimleri değiştirin; kalem ekleyin ya da kaldırın.",
                            IslemSatiri.Ton.vurgu, durum(IslemKurallari.PlanIslemi.duzenle),
                        ) {
                            onDismiss()
                            onDuzenle()
                        }
                        IslemSatiri(
                            "slider.horizontal.3", "Özelleştir", "Plan adı ve notlarını düzenle", IslemSatiri.Ton.varsayilan,
                            durum(IslemKurallari.PlanIslemi.ozellestir),
                        ) {
                            ad = plan.title ?: ""
                            not = plan.notes ?: ""
                            gorunum = PlanGorunumu.ozellestir
                        }
                        IslemSatiri(
                            "stethoscope", "Planın hekimi", plan.doctor?.name?.let { Unvan.hekimAdi(it) } ?: "Atanmamış",
                            IslemSatiri.Ton.varsayilan, durum(IslemKurallari.PlanIslemi.hekimDegistir),
                        ) { hekimAc() }
                        IslemSatiri(
                            "printer", "Yazdır", "Önizleme; yazdırın ya da PDF olarak kaydedin", IslemSatiri.Ton.varsayilan,
                            IslemKurallari.Durum.acik,
                        ) { yazdir() }
                        IslemSatiri(
                            "creditcard", "Ödeme Planı", "Taksit planı + banka komisyon karşılaştırması", IslemSatiri.Ton.varsayilan,
                            odemeDurumu,
                        ) { gorunum = PlanGorunumu.odeme }
                        IslemSatiri(
                            "message", "WhatsApp ile Paylaş", telefon?.let { "→ $it" } ?: "Hasta telefonu yok — boş paylaşım",
                            IslemSatiri.Ton.varsayilan, durum(IslemKurallari.PlanIslemi.paylas),
                        ) { paylas(PaylasimKanali.whatsapp) }
                        IslemSatiri(
                            "message", "SMS ile Paylaş", telefon?.let { "→ $it" } ?: "Hasta telefonu yok",
                            IslemSatiri.Ton.varsayilan, durum(IslemKurallari.PlanIslemi.paylas),
                        ) { paylas(PaylasimKanali.sms) }
                        IslemSatiri(
                            "envelope", "E-posta ile Paylaş", "Hasta e-postası yok", IslemSatiri.Ton.varsayilan,
                            durum(IslemKurallari.PlanIslemi.paylas),
                        ) { paylas(PaylasimKanali.eposta) }
                        IslemSatiri(
                            "arrow.counterclockwise", "Planı Resetle", "Yapılacak/Yapılan'a aktarılan bütün kalemleri planlamaya geri çeker",
                            IslemSatiri.Ton.tehlike, durum(IslemKurallari.PlanIslemi.sifirla),
                        ) { onay = PlanOnayi.sifirla }
                        if (plan.deletedAt != null) {
                            IslemSatiri(
                                "arrow.counterclockwise", "Planı Geri Yükle", "", IslemSatiri.Ton.vurgu,
                                durum(IslemKurallari.PlanIslemi.geriYukle),
                            ) { uygulaBaslat("Plan geri yüklendi.") { ApiClient.planGeriYukle(plan.id) } }
                        } else {
                            IslemSatiri(
                                "trash", "Planı Sil",
                                "Silinen plan listeden kalkar. Yapılmış tedavisi olan plan silinmez — önce o tedaviler iptal edilir.",
                                IslemSatiri.Ton.tehlike, durum(IslemKurallari.PlanIslemi.sil),
                            ) { onay = PlanOnayi.sil }
                        }
                    }

                    PlanGorunumu.ozellestir -> {
                        KutuAlani(ad, { ad = it }, "Plan adı", enAzYukseklik = 44.dp)
                        KutuAlani(not, { not = it }, "Notlar", tekSatir = false, enAzSatir = 3, enCokSatir = 6)
                        val adKirpik = ad.trim()
                        IkiliDugme(
                            "Kaydet", etkin = adKirpik.isNotEmpty() && ad.length <= 200 && not.length <= 2000, calisiyor = calisiyor,
                            vazgec = { gorunum = PlanGorunumu.menu },
                            kaydet = {
                                val yeniAd = ad.trim()
                                val yeniNot = not
                                uygulaBaslat("Plan güncellendi.") { ApiClient.planGuncelle(plan.id, ad = yeniAd, not = yeniNot) }
                            },
                        )
                    }

                    PlanGorunumu.hekim -> {
                        IslemMenusu(
                            listOf("" to "Hekim seçin") + hekimler.map { it.userId to Unvan.hekimAdi(it.user.name ?: "") },
                            yeniHekim, { yeniHekim = it }, Modifier.fillMaxWidth(),
                        )
                        IkiliDugme(
                            "Kaydet", etkin = yeniHekim.isNotEmpty(), calisiyor = calisiyor,
                            vazgec = { gorunum = PlanGorunumu.menu },
                            kaydet = {
                                val h = yeniHekim
                                uygulaBaslat("Plan güncellendi.") { ApiClient.planGuncelle(plan.id, hekimId = h) }
                            },
                        )
                    }

                    PlanGorunumu.odeme -> OdemePlaniGorunumu(
                        plan = plan,
                        bitti = { m ->
                            sonuc = IslemSonucu(true, m)
                            gorunum = PlanGorunumu.menu
                        },
                        vazgec = { gorunum = PlanGorunumu.menu },
                    )
                }
            }
        }
    }

    IslemOnayi(
        acik = onay != null,
        baslik = if (onay == PlanOnayi.sifirla) "\"$baslik\" resetlensin mi? Tüm kalemler Planlamaya geri çekilir." else "\"$baslik\" silinsin mi?",
        secenekler = if (onay == PlanOnayi.sifirla) {
            listOf(OnaySecenegi("Planı Resetle", yikici = true) { uygulaBaslat("Plan resetlendi.") { ApiClient.planSifirla(plan.id) } })
        } else {
            listOf(OnaySecenegi("Planı Sil", yikici = true) { uygulaBaslat("Plan silindi.") { ApiClient.planSil(plan.id) } })
        },
        kapat = { onay = null },
    )
    BaskiOnizlemePenceresi(plan.title ?: "Tedavi Planı", baski) { baski = null }
}

private enum class PlanGorunumu { menu, ozellestir, hekim, odeme }
private enum class PlanOnayi { sifirla, sil }
private enum class PaylasimKanali { whatsapp, sms, eposta }

/** Oturumun saat dilimi (IANA); yoksa ya da geçersizse cihazınki. */
internal fun saatDilimi(ad: String?): ZoneId = ad?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/**
 * Swift `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting("&+="))` — sorgu değerinde güvenli olmayan her
 * karakter UTF-8 `%XX` olur (boşluk `%20`; `&`, `+`, `=` da kodlanır).
 */
internal fun sorguKodla(s: String): String {
    val izinli = "-._~!$'()*,;:@/?"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in izinli)) sb.append(ch) else sb.append('%').append(String.format(java.util.Locale.ROOT, "%02X", c))
    }
    return sb.toString()
}

/** SMS uygulaması: alıcı ve metin hazır (hekim yalnız "Gönder"e basar). */
private fun smsAc(context: Context, numara: String, metin: String) {
    val niyet = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numara)))
        .putExtra("sms_body", metin)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(niyet) }
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 844)
@Composable
private fun PlanIslemleriSheetPreview() {
    PreviewEnv {
        OrnekModelSaglayici { PlanIslemleriSheet(plan = OrnekIslemVerisi.plan, onDismiss = {}, onDuzenle = {}) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun PlanIslemleriSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OrnekModelSaglayici {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.width(640.dp)) { PlanIslemleriSheet(plan = OrnekIslemVerisi.plan, onDismiss = {}, onDuzenle = {}) }
            }
        }
    }
}
