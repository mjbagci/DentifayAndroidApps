package com.dentifay.app.hasta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIDoctorMember
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.HakedisIptal
import com.dentifay.app.core.HakedisOnayi
import com.dentifay.app.core.Unvan
import com.dentifay.app.home.YeniRandevu
import com.dentifay.app.home.ayarListesi
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Tedavi İşlemleri" — web `TreatmentActionsModal` (DentAIWeb @ afc3170; MOBIL_IS_EMRI M-E8.4). Satırlar sönük kalır ve nedenini söyler
 * (yetki · seçim · silinmiş · Yapılan silinmez). Başarıda sayfa tazelenir ve pencere kapanır. Hakediş etkisi soran yazımlar
 * `HakedisOnayi.korumali` içinde; pencere `HakedisOnayiKaplamasi`.
 *
 * Çağıran `DfySheet` içinde açar; `planHekimi` kalemin plan hekimi (Yapılan'a aktar varsayılanı — Swift'teki sondaki kapanış).
 */
@Composable
fun KalemIslemleriSheet(
    kalemler: List<TedaviKalemi>,
    onDismiss: () -> Unit,
    planHekimi: (TedaviKalemi) -> String? = { null },
) {
    val model = LocalHastaSayfasiModeli.current
    val caps = LocalAuthManager.current.yetenekler
    val scope = rememberCoroutineScope()

    var gorunum by remember { mutableStateOf(KalemGorunumu.menu) }
    var calisiyor by remember { mutableStateOf(false) }
    var sonuc by remember { mutableStateOf<IslemSonucu?>(null) }

    // Alt görünüm durumları
    var hekimler by remember { mutableStateOf<List<APIDoctorMember>>(emptyList()) }
    var tekSecici by remember { mutableStateOf(true) }
    var secimler by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var indirimTutar by remember { mutableStateOf("") }
    var indirimYuzde by remember { mutableStateOf("") }
    var para by remember { mutableStateOf("TRY") }
    var kur by remember { mutableStateOf<KurYaniti?>(null) }
    /** Yapılan'dan geri alma nedeni (isteğe bağlı — web `TreatmentActionsModal`, M-E9.4). */
    var nedenHedef by remember { mutableStateOf("TODO") }
    var nedenMetni by remember { mutableStateOf("") }
    var nedenler by remember { mutableStateOf<List<YeniRandevu.IslemTuru>>(emptyList()) }
    var silmeNedeni by remember { mutableStateOf("") }

    fun durum(i: IslemKurallari.KalemIslemi) = IslemKurallari.durum(i, kalemler, caps)
    val tekKalem = kalemler.singleOrNull()
    val yapilanVar = kalemler.any { k -> k.stage == "DONE" || k.canliCocuklar.any { it.stage == "DONE" } }

    /** Hata: sunucunun cümlesi (kodlu 4xx dahil — hiçbir şey kaydedilmiş sayılmaz); kodsuz 403 "Bu işlem için yetkiniz yok.". */
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

    /** `neden` hakediş onayıyla yeniden gönderimde de korunur (aynı kapanış). */
    fun aktar(asama: String, mesaj: String, neden: String? = null, hekimId: String? = null, hekimlerHaritasi: Map<String, String>? = null) {
        scope.launch {
            uygula(mesaj) {
                ApiClient.kalemleriAktar(IslemKurallari.aktarmaKimlikleri(kalemler), asama, neden, hekimId, hekimlerHaritasi)
            }
        }
    }

    fun calistir(mesaj: String, tek: suspend (TedaviKalemi) -> Unit) {
        val k = tekKalem ?: return
        scope.launch { uygula(mesaj) { tek(k) } }
    }

    fun calistirHepsi(mesaj: String, her: suspend (TedaviKalemi) -> Unit) {
        scope.launch { uygula(mesaj) { for (k in kalemler) her(k) } }
    }

    /** Yapılan'dan geri almada neden adımı; ileri yönde sorulmaz. */
    fun geriAl(hedef: String) {
        val mesaj = if (hedef == "TODO") "Yapılacak'a aktarıldı." else "Planlamaya geri alındı."
        if (!yapilanVar) {
            aktar(hedef, mesaj)
            return
        }
        nedenHedef = hedef
        nedenMetni = ""
        gorunum = KalemGorunumu.neden
        scope.launch {
            nedenler = (sessizce { ApiClient.ayarListesi("TREATMENT_FAILURE_REASON") } ?: emptyList()).filter { it.ad.isNotEmpty() }
        }
    }

    fun hekimGorunumunuAc() {
        val b = IslemKurallari.baslangicSecimi(kalemler, planHekimi)
        tekSecici = b.tek
        secimler = b.secimler
        gorunum = KalemGorunumu.hekim
        scope.launch { hekimler = sessizce { ApiClient.fetchAssignableDoctors() } ?: emptyList() }
    }

    fun menuyeDon() {
        gorunum = KalemGorunumu.menu
        sonuc = null
    }

    val hekimSecenekleri = remember(hekimler) {
        listOf("" to "Hekim seçin") + hekimler.map { it.userId to Unvan.hekimAdi(it.user.name ?: "") }
    }

    LaunchedEffect(para, gorunum) {
        if (gorunum != KalemGorunumu.doviz) return@LaunchedEffect
        kur = null
        val k = tekKalem ?: return@LaunchedEffect
        if (para == "TRY") return@LaunchedEffect
        kur = sessizce { ApiClient.kurCoz(para, k.id) }
    }

    HakedisOnayiKaplamasi {
        Column(Modifier.fillMaxSize()) {
            PencereBasligi("Tedavi İşlemleri", solMetni = "Kapat", solEylem = onDismiss)
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
                Text(
                    when {
                        kalemler.size == 1 -> "Seçili kalem: ${kalemler[0].name}"
                        kalemler.isEmpty() -> "Hiç kalem seçilmedi."
                        else -> "${kalemler.size} kalem seçildi"
                    },
                    style = sys(13), color = DS.Colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                sonuc?.let { SonucBandi(basari = it.basari, metin = it.metin) }
                when (gorunum) {
                    KalemGorunumu.menu -> {
                        IslemSatiri(
                            ikon = "arrow.left.arrow.right",
                            baslik = if (yapilanVar) "Yapılacağa geri al" else "Yapılacak Tedavilere Aktar",
                            ipucu = if (yapilanVar) "Yapılan'dan geri alınıyor — neden sorulur" else "Onaylandı, takvime düşecek",
                            ton = IslemSatiri.Ton.vurgu, durum = durum(IslemKurallari.KalemIslemi.yapilacaga),
                        ) { geriAl("TODO") }
                        IslemSatiri(
                            ikon = "checkmark.circle", baslik = "Yapılan Tedavilere Aktar", ipucu = "Fiilen icra edildi, ödeme akışına girer",
                            ton = IslemSatiri.Ton.basari, durum = durum(IslemKurallari.KalemIslemi.yapilana),
                        ) { hekimGorunumunuAc() }
                        IslemSatiri(
                            ikon = "arrow.counterclockwise", baslik = "Planlamaya Geri Al", ipucu = "Yanlış aşamaya gittiyse geri çek",
                            ton = IslemSatiri.Ton.varsayilan, durum = durum(IslemKurallari.KalemIslemi.planlamaya),
                        ) { geriAl("PLANNED") }
                        IslemSatiri(
                            ikon = "percent", baslik = "İskonto / Artırım", ipucu = "Kaleme yüzde veya TL bazlı indirim",
                            ton = IslemSatiri.Ton.varsayilan, durum = durum(IslemKurallari.KalemIslemi.indirim),
                        ) { gorunum = KalemGorunumu.indirim }
                        IslemSatiri(
                            ikon = "banknote", baslik = "Döviz Cinsi Değiştir",
                            ipucu = tekKalem?.let { "Şu an: ${it.currency ?: "TRY"} (kur ${kurMetni(it.exchangeRate?.deger)})" } ?: "",
                            ton = IslemSatiri.Ton.varsayilan, durum = durum(IslemKurallari.KalemIslemi.doviz),
                        ) {
                            para = tekKalem?.currency ?: "TRY"
                            gorunum = KalemGorunumu.doviz
                        }
                        IslemSatiri(
                            ikon = "scissors", baslik = "Dişleri Parçala", ipucu = "(14, 13) Dolgu → 14 Dolgu + 13 Dolgu",
                            ton = IslemSatiri.Ton.varsayilan, durum = durum(IslemKurallari.KalemIslemi.parcala),
                        ) { calistir("Dişler ayrı kalemlere bölündü.") { ApiClient.disleriParcala(it.id) } }
                        if (tekKalem?.deletedAt != null) {
                            IslemSatiri(
                                ikon = "arrow.counterclockwise", baslik = "Kalemi Geri Yükle", ipucu = "",
                                ton = IslemSatiri.Ton.vurgu, durum = durum(IslemKurallari.KalemIslemi.geriYukle),
                            ) { calistir("Kalem geri yüklendi.") { ApiClient.kalemGeriYukle(it.id) } }
                        } else {
                            IslemSatiri(
                                ikon = "trash", baslik = if (kalemler.size > 1) "${kalemler.size} Kalemi Sil" else "Kalemi Sil",
                                ipucu = "Silinen kalem sonradan geri yüklenebilir", ton = IslemSatiri.Ton.tehlike,
                                durum = durum(IslemKurallari.KalemIslemi.sil),
                            ) { gorunum = KalemGorunumu.sil }
                        }
                    }

                    KalemGorunumu.hekim -> {
                        Text(
                            if (kalemler.size > 1) "Bu kalemler hangi hekim için yapılan tedavilere aktarılsın?"
                            else "Bu kalem hangi hekim için yapılan tedavilere aktarılsın?",
                            style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface,
                        )
                        Text(
                            "Kalemi yapan hekimi seçin. Bu bilgi hekim hakedişi ve hekim performans analizi için önemlidir.",
                            style = sys(12), color = DS.Colors.onSurfaceVariant,
                        )
                        if (tekSecici) {
                            HekimSecimi("Hekim", secimler.values.firstOrNull() ?: "", hekimSecenekleri) { v ->
                                secimler = secimler.mapValues { v }
                            }
                        } else {
                            kalemler.forEach { k ->
                                val konum = DisSemasi.konumEtiketi(k)
                                HekimSecimi(k.name + (if (konum.isEmpty()) "" else " · $konum"), secimler[k.id] ?: "", hekimSecenekleri) { v ->
                                    secimler = secimler + (k.id to v)
                                }
                            }
                            val ilk = kalemler.firstOrNull()?.let { secimler[it.id] } ?: ""
                            Text(
                                "Hepsine uygula",
                                Modifier
                                    .alpha(if (ilk.isEmpty()) 0.4f else 1f)
                                    .plainClickable(enabled = ilk.isNotEmpty()) { secimler = secimler.mapValues { ilk } }
                                    .padding(vertical = 6.dp),
                                style = sys(15), color = DS.Colors.cyan400,
                            )
                        }
                        val g = IslemKurallari.aktarGovdesi(tekSecici, secimler)
                        IkiliDugme("Kaydet", etkin = g != null, calisiyor = calisiyor, vazgec = ::menuyeDon, kaydet = {
                            val govde = IslemKurallari.aktarGovdesi(tekSecici, secimler) ?: return@IkiliDugme
                            aktar("DONE", "Yapılan'a aktarıldı.", hekimId = govde.hekimId, hekimlerHaritasi = govde.hekimler)
                        })
                    }

                    KalemGorunumu.indirim -> {
                        Text("Yalnızca biri doldurulmalı: tutar (TL) veya yüzde.", style = sys(12), color = DS.Colors.onSurfaceVariant)
                        KutuAlani(indirimTutar, { indirimTutar = it }, "İndirim tutarı (TL)", klavye = ONDALIK, enAzYukseklik = 44.dp)
                        KutuAlani(indirimYuzde, { indirimYuzde = it }, "İndirim yüzdesi (%)", klavye = ONDALIK, enAzYukseklik = 44.dp)
                        val tutar = ondalikOku(indirimTutar)
                        val yuzde = ondalikOku(indirimYuzde)
                        val gecerli = (tutar != null) != (yuzde != null) && (tutar?.let { it >= 0 } ?: true) && (yuzde?.let { it in 0.0..100.0 } ?: true)
                        IkiliDugme("Uygula", etkin = gecerli, calisiyor = calisiyor, vazgec = ::menuyeDon, kaydet = {
                            calistir("İskonto uygulandı.") { ApiClient.kalemIndirimi(it.id, tutar = tutar, yuzde = yuzde) }
                        })
                    }

                    KalemGorunumu.doviz -> {
                        IslemSegmenti(PARA_BIRIMLERI, PARA_BIRIMLERI.indexOf(para).coerceAtLeast(0), { para = PARA_BIRIMLERI[it] })
                        if (para != "TRY") {
                            val k = kur
                            if (k != null) {
                                val oran = k.rate
                                if (k.ok && oran != null) {
                                    Text("$para kuru: ${oran.replace(".", ",")}", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                                    Text(
                                        listOfNotNull(k.sourceLabel, k.rateDate?.split("-")?.reversed()?.joinToString(".")).joinToString(" · "),
                                        style = sys(11), color = DS.Colors.onSurfaceVariant,
                                    )
                                } else {
                                    Text(k.message ?: "Kur bulunamadı.", style = sys(12), color = DS.Colors.warning)
                                }
                            } else {
                                IslemIlerleme()
                            }
                        }
                        IkiliDugme("Uygula", etkin = para == "TRY" || kur?.ok == true, calisiyor = calisiyor, vazgec = ::menuyeDon, kaydet = {
                            val gosterilen = kur?.rate
                            val secilen = para
                            calistir("Döviz cinsi güncellendi.") { ApiClient.kalemDovizi(it.id, secilen, gosterilen) }
                        })
                    }

                    KalemGorunumu.sil -> {
                        Text(
                            if (kalemler.size > 1) "${kalemler.size} kalem silinecek." else "Bu kalem silinecek.",
                            style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface,
                        )
                        KutuAlani(silmeNedeni, { silmeNedeni = it }, "İptal nedeni (isteğe bağlı)", enAzYukseklik = 44.dp)
                        IkiliDugme("Sil", etkin = true, calisiyor = calisiyor, tehlike = true, vazgec = ::menuyeDon, kaydet = {
                            val neden = silmeNedeni.trim()
                            calistirHepsi(if (kalemler.size == 1) "1 kalem silindi." else "${kalemler.size} kalem silindi.") {
                                ApiClient.kalemSil(it.id, neden)
                            }
                        })
                    }

                    KalemGorunumu.neden -> NedenGorunumu(
                        nedenler = nedenler,
                        nedenMetni = nedenMetni,
                        degisti = { nedenMetni = it.take(500) },
                        calisiyor = calisiyor,
                        iptal = {
                            gorunum = KalemGorunumu.menu
                            nedenMetni = ""
                        },
                        aktar = {
                            val n = nedenMetni.trim()
                            aktar(
                                nedenHedef, if (nedenHedef == "TODO") "Yapılacak'a aktarıldı." else "Planlamaya geri alındı.",
                                neden = n.ifEmpty { null },
                            )
                        },
                    )
                }
            }
        }
    }
}

private enum class KalemGorunumu { menu, hekim, indirim, doviz, sil, neden }

/** Swift `(basari: Bool, metin: String)`. */
internal data class IslemSonucu(val basari: Boolean, val metin: String)

/** Swift `KalemIslemleriSheet.paraBirimleri`. */
internal val PARA_BIRIMLERI = listOf("TRY", "USD", "EUR", "GBP", "CHF", "MKD", "AZN", "KM")

internal val ONDALIK = KeyboardOptions(keyboardType = KeyboardType.Decimal)

/** Swift `Double(s.replacingOccurrences(of: ",", with: "."))` — boş ya da geçersiz → `null`. */
internal fun ondalikOku(s: String): Double? = s.trim().replace(",", ".").takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** Swift `error.localizedDescription` — sunucunun cümlesi; yoksa genel cümle. */
internal fun hataMetni(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: "İşlem tamamlanamadı."

private fun kurMetni(k: Double?): String = k?.let { String.format(Locale.ROOT, "%.4f", it).replace(".", ",") } ?: "1"

@Composable
private fun HekimSecimi(baslik: String, secili: String, secenekler: List<Pair<String, String>>, sec: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(baslik, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        IslemMenusu(secenekler, secili, sec, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.NedenGorunumu(
    nedenler: List<YeniRandevu.IslemTuru>,
    nedenMetni: String,
    degisti: (String) -> Unit,
    calisiyor: Boolean,
    iptal: () -> Unit,
    aktar: () -> Unit,
) {
    Text(
        "Yapılan bir tedavi geri alınıyor. Nedeni kaydedilirse hangi işlemin neden tutmadığı sonradan raporlanabilir.",
        style = sys(13), color = DS.Colors.onSurface,
    )
    Text("Neden zorunlu değildir — hatalı kayıt düzeltmesi de bu yolu kullanır.", style = sys(11), color = DS.Colors.onSurfaceVariant)
    if (nedenler.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            nedenler.forEach { r ->
                val secili = nedenMetni == r.ad
                Text(
                    r.ad,
                    Modifier
                        .heightIn(min = 30.dp)
                        .clip(CircleShape)
                        .background(if (secili) DS.Colors.cyan400.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                        .border(1.dp, if (secili) DS.Colors.cyan400 else DS.Colors.hairlineStrong, CircleShape)
                        .plainClickable { degisti(r.ad) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    style = sys(12, FontWeight.SemiBold), color = if (secili) DS.Colors.cyan400 else DS.Colors.onSurface, maxLines = 1,
                )
            }
        }
    }
    Text("Neden (isteğe bağlı)", style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
    KutuAlani(nedenMetni, degisti, if (nedenler.isEmpty()) "örn: İmplant başarısızlığı" else "Yukarıdan seçin veya yazın")
    IkiliDugme("Aktar", etkin = true, calisiyor = calisiyor, vazgec = iptal, kaydet = aktar, vazgecMetni = "İptal")
}

/** Web `IslemSatiri`: ikon kutusu (ton) + başlık + ipucu; sönükse ipucu yerine neden, dokununca işlem yok. */
object IslemSatiri {
    enum class Ton { varsayilan, vurgu, basari, tehlike }
}

@Composable
fun IslemSatiri(
    ikon: String,
    baslik: String,
    ipucu: String,
    ton: IslemSatiri.Ton,
    durum: IslemKurallari.Durum,
    eylem: () -> Unit,
) {
    val renk = when (ton) {
        IslemSatiri.Ton.varsayilan -> DS.Colors.onSurfaceVariant
        IslemSatiri.Ton.vurgu -> DS.Colors.cyan400
        IslemSatiri.Ton.basari -> hex("34D399")
        IslemSatiri.Ton.tehlike -> DS.Colors.danger
    }
    val acik = durum.acikMi
    val ikonRengi = if (acik) renk else DS.Colors.outline
    val sekil = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(sekil)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, DS.Colors.hairline, sekil)
            .plainClickable { if (acik) eylem() else Haptics.light() }
            .alpha(if (acik) 1f else 0.6f)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(ikonRengi.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { IslemIkonu(ikon, ikonRengi) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(baslik, style = sys(14, FontWeight.SemiBold), color = if (acik) DS.Colors.onSurface else DS.Colors.onSurfaceVariant)
            val alt = durum.neden ?: ipucu
            if (alt.isNotEmpty()) Text(alt, style = sys(12), color = DS.Colors.onSurfaceVariant)
        }
    }
}

/** SF Symbol adı → uygulamanın ikon seti (karşılığı olmayan "percent" yazıyla). */
@Composable
private fun IslemIkonu(ikon: String, renk: Color) {
    when (ikon) {
        "percent" -> Text("%", style = sys(16, FontWeight.Bold), color = renk)
        else -> DfyIcon(
            when (ikon) {
                "arrow.left.arrow.right" -> "ArrowLeftRight"
                "banknote" -> "Wallet"
                "pencil" -> "PencilLine"
                else -> ikon
            },
            size = 15.dp, tint = renk,
        )
    }
}

/** Web `SonucBandi` — başarı yeşil, hata kırmızı. Başka paketler de kullanır (yeni randevu, şifre). */
@Composable
fun SonucBandi(basari: Boolean, metin: String, modifier: Modifier = Modifier) {
    val renk = if (basari) hex("6EE7B7") else DS.Colors.danger
    val zemin = if (basari) hex("10B981") else DS.Colors.danger
    val sekil = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(sekil)
            .background(zemin.copy(alpha = 0.10f))
            .border(1.dp, zemin.copy(alpha = 0.4f), sekil)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon(if (basari) "checkmark.circle.fill" else "exclamationmark.triangle.fill", Modifier.padding(top = 1.dp), size = 14.dp, tint = renk)
        Text(metin, Modifier.weight(1f), style = sys(13, FontWeight.Medium), color = renk)
    }
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 844)
@Composable
private fun KalemIslemleriSheetPreview() {
    PreviewEnv {
        OrnekModelSaglayici {
            KalemIslemleriSheet(kalemler = listOf(OrnekIslemVerisi.kalemler[1]), onDismiss = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun KalemIslemleriSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OrnekModelSaglayici {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth(0.5f)) {
                    KalemIslemleriSheet(kalemler = OrnekIslemVerisi.kalemler.take(2), onDismiss = {})
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 260)
@Composable
private fun SonucBandiPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SonucBandi(true, "Yapılan'a aktarıldı.")
            SonucBandi(false, "Bu işlem için yetkiniz yok.")
            IslemSatiri("trash", "Kalemi Sil", "Silinen kalem sonradan geri yüklenebilir", IslemSatiri.Ton.tehlike,
                IslemKurallari.Durum.pasif(IslemKurallari.YAPILAN_SILINMEZ)) {}
        }
    }
}
