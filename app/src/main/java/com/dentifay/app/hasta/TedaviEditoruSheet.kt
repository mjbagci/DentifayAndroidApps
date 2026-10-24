package com.dentifay.app.hasta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIDoctorMember
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.HakedisIptal
import com.dentifay.app.core.HakedisOnayi
import com.dentifay.app.core.Unvan
import com.dentifay.app.core.govde
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

/**
 * Plan penceresi (MOBIL_IS_EMRI M-E8.4) — web `NewPlanModal` (yeni plan · bu plana ekle · planı düzenle) ve `AddItemDirectModal`
 * (aşama listesinden doğrudan ekleme) tek ekranda. Fiyat ve toplam sunucudan; toplu yüzde Yapılan satırlara uygulanmaz.
 * Swift `TedaviEditoruSheet.Kip` → `TedaviEditoruSheet.Kip` (nesne içinde).
 */
object TedaviEditoruSheet {
    sealed class Kip {
        data object yeniPlan : Kip()
        data class planaEkle(val plan: HastaPlani) : Kip()
        data class dogrudan(val asama: String) : Kip()
        data class duzenle(val plan: HastaPlani) : Kip()
    }
}

/** 2+ diş seçiliyken kip sorusunun bekleyen seçimi (Swift `(KatalogKalemi?, String, Double)`). */
private data class KipSorusu(val katalog: KatalogKalemi?, val ad: String, val fiyat: Double)

/**
 * Çağıran `DfySheet` içinde açar (`TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.yeniPlan, onDismiss = dismiss)`); hasta ve plan ayrıntısı
 * `LocalHastaSayfasiModeli`nden. Uzun içerik tembel listede (katalog yüzlerce satır olabilir); yatay pencerede de kayar.
 */
@Composable
fun TedaviEditoruSheet(kip: TedaviEditoruSheet.Kip, onDismiss: () -> Unit) {
    val model = LocalHastaSayfasiModeli.current
    val auth = LocalAuthManager.current
    val scope = rememberCoroutineScope()

    // Veri
    var katalog by remember { mutableStateOf<List<KatalogKalemi>>(emptyList()) }
    var katalogHatasi by remember { mutableStateOf<String?>(null) }
    var sik by remember { mutableStateOf<List<SikKullanilan>>(emptyList()) }
    var hekimler by remember { mutableStateOf<List<APIDoctorMember>>(emptyList()) }
    var surum by remember { mutableStateOf<String?>(null) }

    // Plan alanları
    var planAdi by remember { mutableStateOf("") }
    var planHekimi by remember { mutableStateOf("") }
    var tarih by remember { mutableStateOf(LocalDate.now()) }
    var asama by remember { mutableStateOf((kip as? TedaviEditoruSheet.Kip.dogrudan)?.asama ?: "TODO") }

    // Seçim
    var kalemHekimi by remember { mutableStateOf("") }
    var disler by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ceneler by remember { mutableStateOf<Set<String>>(emptySet()) }
    var yarimCeneler by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var arama by remember { mutableStateOf("") }
    var kipSorusu by remember { mutableStateOf<KipSorusu?>(null) }
    var acikGruplar by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Satırlar
    // Düzenlemede satırlar hemen (ağ beklemeden) plan ayrıntısından kurulur.
    val satirlar = remember {
        mutableStateListOf<TedaviEditoru.Satir>().apply {
            if (kip is TedaviEditoruSheet.Kip.duzenle) {
                val ayrinti = model.ayrintilar[kip.plan.id] ?: kip.plan
                addAll((ayrinti.items ?: emptyList()).filter { it.parentItemId == null && it.deletedAt == null }.map(TedaviEditoru::satir))
            }
        }
    }
    val silinenler = remember { mutableStateListOf<String>() }
    var topluYuzde by remember { mutableStateOf("") }
    var topluKip by remember { mutableStateOf(IslemKurallari.TopluKip.yuzde) }
    var manuelAd by remember { mutableStateOf("") }
    var manuelFiyat by remember { mutableStateOf("") }

    var kaydediliyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    /** Etkisi olmayan ama Yapılan/Yapılacak kalemi değiştiren düzenlemede web'in eski onay kutusu. */
    var asamaUyarisi by remember { mutableStateOf<List<String>?>(null) }
    var asamaOnayi by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val baslik = when (kip) {
        TedaviEditoruSheet.Kip.yeniPlan -> "Yeni Tedavi Planı Hazırla"
        is TedaviEditoruSheet.Kip.planaEkle -> "Bu Plana Tedavi Ekle"
        is TedaviEditoruSheet.Kip.dogrudan -> "Tedavi Kalemi Ekle"
        is TedaviEditoruSheet.Kip.duzenle -> "Planı düzenle"
    }
    val kaydetMetni = when (kip) {
        TedaviEditoruSheet.Kip.yeniPlan -> "PLAN OLARAK KAYDET"
        is TedaviEditoruSheet.Kip.planaEkle -> "PLANA EKLE"
        is TedaviEditoruSheet.Kip.dogrudan, is TedaviEditoruSheet.Kip.duzenle -> "Kaydet"
    }
    val hesap by remember {
        derivedStateOf { IslemKurallari.planIndirimleri(satirlar.map { it.indirimSatiri }, topluKip, ondalikOku(topluYuzde) ?: 0.0) }
    }
    val topluVar = hesap.toplu > 0
    /** Web: değer boşken "geçersiz" gösterilmez. */
    val topluGecersiz = topluYuzde.isNotBlank() && hesap.gecersiz
    val kaydedilebilir = when (kip) {
        is TedaviEditoruSheet.Kip.duzenle -> satirlar.any { it.degisti || it.kalemId == null } || silinenler.isNotEmpty() || topluVar
        TedaviEditoruSheet.Kip.yeniPlan -> planHekimi.isNotEmpty() && satirlar.isNotEmpty()
        else -> satirlar.isNotEmpty()
    }
    val hekimSecenekleri = remember(hekimler) { hekimler.map { it.userId to Unvan.hekimAdi(it.user.name ?: "") } }

    // MARK: Yükleme

    /** Tam katalog (`set.catalog.view`); yetki yoksa menünün seçim ucu (200'e kadar). */
    suspend fun katalogYukle() {
        try {
            katalog = ApiClient.tedaviKatalogu().distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val menu = sessizce { ApiClient.varsayilanMenu(model.hasta.id) }
            val y = menu?.let { m -> sessizce { ApiClient.menuSecimListesi(m) } }
            if (y != null) {
                katalog = y.items.distinctBy { it.id }.map {
                    KatalogKalemi(id = it.id, name = it.name, group = it.grup, tarifeKodu = null, price = it.price, currency = it.currency, visualKey = null)
                }
            } else {
                katalogHatasi = hataMetni(e)
            }
        }
    }

    LaunchedEffect(Unit) {
        val ben = auth.currentUser?.id
        hekimler = sessizce { ApiClient.fetchAssignableDoctors() } ?: emptyList()
        val atanabilir = hekimler.map { it.userId }.toSet()
        when (kip) {
            TedaviEditoruSheet.Kip.yeniPlan -> {
                planAdi = ""
                if (ben != null && ben in atanabilir) {
                    planHekimi = ben
                    kalemHekimi = ben
                }
            }
            is TedaviEditoruSheet.Kip.planaEkle ->
                kalemHekimi = kip.plan.doctorUserId ?: kip.plan.doctor?.id ?: (ben?.takeIf { it in atanabilir } ?: "")
            is TedaviEditoruSheet.Kip.dogrudan -> {
                asama = kip.asama
                if (ben != null && ben in atanabilir) kalemHekimi = ben
            }
            is TedaviEditoruSheet.Kip.duzenle -> {
                kalemHekimi = kip.plan.doctorUserId ?: kip.plan.doctor?.id ?: ""
                try {
                    surum = ApiClient.planSurumu(kip.plan.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    hata = hataMetni(e)
                }
            }
        }
        sik = sessizce { ApiClient.sikKullanilanlar(kalemHekimi.ifEmpty { null }) } ?: emptyList()
        katalogYukle()
    }

    // MARK: Seçim

    fun ekle(k: KatalogKalemi?, ad: String, fiyat: Double, dkip: TedaviEditoru.DisKipi) {
        val yeni = TedaviEditoru.satirlar(
            k, ad, fiyat, disler.sorted(), ceneler.sorted(), yarimCeneler.sorted(), dkip, kalemHekimi,
            para = (k?.currency ?: "TRY").uppercase(),
        )
        satirlar.addAll(yeni)
        disler = emptySet()
        ceneler = emptySet()
        yarimCeneler = emptySet()
        arama = ""
        Haptics.selection()
    }

    /** Seçim: hekim yoksa uyarı; 2+ dişte kip sorulur. */
    fun sec(k: KatalogKalemi?, ad: String, fiyat: Double) {
        if (kalemHekimi.isEmpty()) {
            hata = "Önce hekim seçin."
            return
        }
        hata = null
        if (disler.size >= 2) kipSorusu = KipSorusu(k, ad, fiyat) else ekle(k, ad, fiyat, TedaviEditoru.DisKipi.teker)
    }

    // MARK: Kaydet

    suspend fun asamaSor(liste: List<String>): Boolean {
        val d = CompletableDeferred<Boolean>()
        asamaOnayi = d
        asamaUyarisi = liste
        return d.await()
    }

    fun asamaYaniti(onay: Boolean) {
        asamaUyarisi = null
        asamaOnayi?.complete(onay)
        asamaOnayi = null
    }

    /** Satırları sırayla yazar; kısmi hatada kaydedilenler listeden düşer, kalanlar yeniden denenir (web `errors.partialSave`). */
    suspend fun satirlariPlanaYaz(planId: String, h: List<IslemKurallari.SatirSonucu>) {
        val kalan = mutableListOf<TedaviEditoru.Satir>()
        var ilkHata: Exception? = null
        for ((i, s) in satirlar.toList().withIndex()) {
            try {
                ApiClient.planaKalemEkle(TedaviEditoru.planKalemi(s, planId, h[i].toplamIndirim))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                kalan += s
                if (ilkHata == null) ilkHata = e
            }
        }
        ilkHata?.let {
            satirlar.clear()
            satirlar.addAll(kalan)
            throw it
        }
    }

    suspend fun kaydet() {
        kaydediliyor = true
        hata = null
        val h = hesap.satirlar
        val tarihMetni = tarih.toString()
        try {
            when (kip) {
                TedaviEditoruSheet.Kip.yeniPlan -> {
                    val plan = ApiClient.planOlustur(model.hasta.id, planHekimi, tarihMetni, planAdi.trim())
                    satirlariPlanaYaz(plan.id, h)
                }
                is TedaviEditoruSheet.Kip.planaEkle -> satirlariPlanaYaz(kip.plan.id, h)
                is TedaviEditoruSheet.Kip.dogrudan -> for ((i, s) in satirlar.toList().withIndex()) {
                    ApiClient.dogrudanKalemEkle(TedaviEditoru.dogrudanKalem(s, model.hasta.id, asama, tarihMetni, h[i].toplamIndirim))
                }
                is TedaviEditoruSheet.Kip.duzenle -> {
                    val sur = surum ?: throw ApiException.Kodlu(428, "E_IF_MATCH_REQUIRED", "Plan sürümü okunamadı; pencereyi kapatıp yeniden açın.")
                    val orijinal = ((model.ayrintilar[kip.plan.id] ?: kip.plan).items ?: emptyList()).associateBy { it.id }
                    val eklenen = mutableListOf<JsonObject>()
                    val degisen = mutableListOf<JsonObject>()
                    val asamadakiler = mutableListOf<String>()
                    for ((i, s) in satirlar.withIndex()) {
                        if (s.kalemId == null) {
                            eklenen += TedaviEditoru.eklenen(s, h[i].toplamIndirim)
                        } else if (s.degisti || (topluVar && !s.yapilan)) {
                            degisen += TedaviEditoru.degisen(s, h[i].toplamIndirim, grup = orijinal[s.kalemId]?.grupMu ?: false)
                            if (s.asama != "PLANNED") asamadakiler += "${s.ad} (${DisSemasi.asamaAdi(s.asama)})"
                        }
                    }
                    val silinen = silinenler.toList()
                    // Web `submitDuzenle`: önce önizleme; etki varsa "Hakediş değişecek" (değişen Yapılan/Yapılacak listesi aynı pencerede),
                    // etki yoksa ama liste varsa eski onay kutusu; sonra özetle tek kayıt (DEGISTI → pencere yeni satırlarla).
                    val g = govde("eklenen" to eklenen, "degisen" to degisen, "silinen" to silinen)
                    val onizleme = ApiClient.hakedisOnizle("PLAN_DUZENLE", kip.plan.id, g, ifMatch = "\"$sur\"")
                    if (!onizleme.etkiVar && asamadakiler.isNotEmpty()) {
                        if (!asamaSor(asamadakiler)) return
                    }
                    HakedisOnayi.korumali(ek = asamadakiler.ifEmpty { null }, onizleme = onizleme) {
                        ApiClient.planKalemleriniKaydet(kip.plan.id, sur, eklenen, degisen, silinen)
                    }
                }
            }
            Haptics.success()
            model.tazele()
            onDismiss()
        } catch (_: HakedisIptal) {
            // İptal: hiçbir şey yazılmadı, pencere açık kalır.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiException && e.kod == "E_PLAN_VERSION_CONFLICT") {
                // Sürüm çakışması → yeniden yükle (M-E8.0 kendi yolu).
                hata = e.message
                model.tazele()
                if (kip is TedaviEditoruSheet.Kip.duzenle) surum = sessizce { ApiClient.planSurumu(kip.plan.id) }
            } else {
                Haptics.error()
                hata = hataMetni(e)
            }
        } finally {
            kaydediliyor = false
        }
    }

    // MARK: Görünüm

    HakedisOnayiKaplamasi {
        Column(Modifier.fillMaxSize()) {
            PencereBasligi(
                baslik, solMetni = "Vazgeç", solEylem = onDismiss,
                sagMetni = kaydetMetni, sagEtkin = !kaydediliyor && kaydedilebilir && !topluGecersiz,
                sagEylem = { scope.launch { kaydet() } },
            )
            val sorgu = arama.trim()
            val dizin = remember(katalog) { TedaviEditoru.aramaDizini(katalog) }
            val arananlar = remember(katalog, sorgu) { if (sorgu.isEmpty()) null else TedaviEditoru.ara(katalog, sorgu, dizin) }
            val gruplar = remember(katalog) { TedaviEditoru.gruplar(katalog) }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            ) {
                hata?.let { m -> bolum("hata") { SonucBandi(basari = false, metin = m) } }

                // Üst alanlar
                if (kip == TedaviEditoruSheet.Kip.yeniPlan) {
                    bolum("planHekimiBaslik") { AlanBasligi("Planın hekimi") }
                    bolum("planHekimi") { HekimMenusu(planHekimi, listOf("" to "Hekim seçin") + hekimSecenekleri) { planHekimi = it } }
                    bolum("planAdiBaslik") { AlanBasligi("Plan adı") }
                    bolum("planAdi") { KutuAlani(planAdi, { planAdi = it }, model.sonrakiPlanAdi ?: "Plan") }
                    bolum("tarih") { IslemTarihi("Tedavi Tarihi", tarih, { tarih = it }) }
                }
                if (kip is TedaviEditoruSheet.Kip.dogrudan) {
                    bolum("asama") {
                        IslemSegmenti(listOf("Planlandı", "Yapılacak", "Yapıldı"), ASAMALAR.indexOf(asama).coerceAtLeast(0), { asama = ASAMALAR[it] })
                    }
                    bolum("tarih") { IslemTarihi("Tedavi Tarihi", tarih, { tarih = it }) }
                }
                bolum("kalemHekimiBaslik") { AlanBasligi("Eklenecek kalemlerin hekimi *") }
                bolum("kalemHekimi") { HekimMenusu(kalemHekimi, listOf("" to "Hekim seçin") + hekimSecenekleri) { kalemHekimi = it } }

                // Konum
                bolum("konumBaslik") { AlanBasligi("Konum") }
                bolum("sema") {
                    OdontogramView(aciklamalar = emptyMap(), secim = disler, onSecimChange = { disler = it }, karmaGoster = true)
                }
                bolum("ceneler") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Cip("Üst çene", "UPPER" in ceneler) { ceneler = ceneler.degistir("UPPER") }
                        Cip("Alt çene", "LOWER" in ceneler) { ceneler = ceneler.degistir("LOWER") }
                        for (q in 1..4) Cip(DisSemasi.kadranAdlari[q - 1], q in yarimCeneler) { yarimCeneler = yarimCeneler.degistir(q) }
                    }
                }
                bolum("konumNotu") {
                    Text(
                        if (disler.isEmpty()) "Konum seçmezseniz tedavi konumsuz, 1 adet eklenir. Her çene ve her yarım çene ayrı kalem olur."
                        else "Seçili: ${disler.sorted().joinToString(", ")}",
                        style = sys(11), color = DS.Colors.onSurfaceVariant,
                    )
                }

                // Tedavi seçici
                bolum("tedaviBaslik") { AlanBasligi("Tedavi") }
                bolum("arama") { KutuAlani(arama, { arama = it }, "Tedavi ara (ad, kod ya da grup)") }
                val kh = katalogHatasi
                if (kh != null && katalog.isEmpty()) bolum("katalogHatasi") { Text(kh, style = sys(12), color = DS.Colors.warning) }
                if (arananlar == null) {
                    if (sik.isNotEmpty()) {
                        bolum("sikBaslik") {
                            Text("Sık Kullanılan (Son 6 Ay)", style = sys(11, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
                        }
                        bolum("sik") {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (s in sik) {
                                    Cip("${s.name} ×${s.usageCount ?: 0}", false) {
                                        val k = katalog.firstOrNull { it.id == s.catalogServiceId }
                                        sec(k, s.name, k?.price?.deger ?: s.price?.deger ?: 0.0)
                                    }
                                }
                            }
                        }
                    }
                    for (g in gruplar) {
                        val acik = g.ad in acikGruplar
                        item(key = "grup-${g.ad}", contentType = "grup") {
                            GrupBasligi(g.ad, acik) { acikGruplar = if (acik) acikGruplar - g.ad else acikGruplar + g.ad }
                        }
                        if (acik) {
                            items(g.kalemler, key = { "g-${g.ad}-${it.id}" }, contentType = { "katalog" }) { k ->
                                KatalogSatiri(k, Modifier.padding(start = 4.dp)) { sec(k, k.name, k.price?.deger ?: 0.0) }
                            }
                        }
                    }
                    item(key = "gruplarSonu") { Box(Modifier.padding(bottom = 14.dp)) }
                } else {
                    if (arananlar.sonuclar.isEmpty()) {
                        bolum("sonucYok") { Text("Bu aramayla eşleşen tedavi yok.", style = sys(12), color = DS.Colors.onSurfaceVariant) }
                    }
                    items(arananlar.sonuclar, key = { "a-${it.id}" }, contentType = { "katalog" }) { k ->
                        KatalogSatiri(k) { sec(k, k.name, k.price?.deger ?: 0.0) }
                    }
                    item(key = "aramaSonu") { Box(Modifier.padding(bottom = 14.dp)) }
                    if (arananlar.dahaVar) {
                        bolum("dahaVar") { Text("Daha fazla sonuç var; aramayı daraltın.", style = sys(11), color = DS.Colors.onSurfaceVariant) }
                    }
                }
                // Katalog dışı (manuel) satır — web N7.
                bolum("manuel") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KutuAlani(manuelAd, { manuelAd = it }, "Katalog dışı tedavi adı", Modifier.weight(1f))
                        KutuAlani(manuelFiyat, { manuelFiyat = it }, "Tutar", Modifier.width(100.dp), klavye = ONDALIK)
                        Text(
                            "EKLE",
                            Modifier
                                .plainClickable {
                                    val f = ondalikOku(manuelFiyat)
                                    val ad = manuelAd.trim()
                                    if (f != null && ad.isNotEmpty()) {
                                        sec(null, ad, f)
                                        manuelAd = ""
                                        manuelFiyat = ""
                                    }
                                }
                                .padding(vertical = 10.dp),
                            style = sys(12, FontWeight.Bold), color = DS.Colors.cyan400,
                        )
                    }
                }

                // Satırlar
                if (satirlar.isNotEmpty()) {
                    bolum("satirBaslik") { AlanBasligi("Tedaviler (${satirlar.size})") }
                    val sonuclar = hesap.satirlar
                    for ((i, s) in satirlar.withIndex()) {
                        item(key = "s-${s.id}", contentType = "satir") {
                            SatirKarti(
                                s, sonuclar.getOrNull(i), hekimSecenekleri, Modifier.padding(bottom = 14.dp),
                                degisti = { yeni -> satirlar.indexOfFirst { it.id == s.id }.takeIf { it >= 0 }?.let { satirlar[it] = yeni } },
                                kaldir = {
                                    if (s.yapilan) {
                                        hata = IslemKurallari.YAPILAN_SILINMEZ
                                    } else {
                                        s.kalemId?.let { silinenler += it }
                                        satirlar.removeAll { it.id == s.id }
                                    }
                                },
                            )
                        }
                    }
                    // Toplu indirim — % · TL · Hedef toplam (web 52B); kip değişince değer sıfırlanır.
                    bolum("toplu") {
                        val sonuk = hesap.acikToplam <= 0
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Toplu indirim", style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
                            Column(Modifier.alpha(if (sonuk) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                IslemSegmenti(
                                    listOf("%", "TL", "Hedef toplam"), IslemKurallari.TopluKip.entries.indexOf(topluKip),
                                    {
                                        topluKip = IslemKurallari.TopluKip.entries[it]
                                        topluYuzde = ""
                                    },
                                    etkin = !sonuk,
                                )
                                KutuAlani(topluYuzde, { topluYuzde = it }, "0", klavye = ONDALIK, hatali = topluGecersiz, etkin = !sonuk)
                            }
                            if (sonuk) {
                                Text("İndirime açık satır yok.", style = sys(11), color = DS.Colors.onSurfaceVariant)
                            } else if (topluKip == IslemKurallari.TopluKip.hedef) {
                                Text(
                                    "Planın indirimden sonraki toplamını yazın; indirim satırlara dağıtılır. Yapılan tedaviler toplu indirime girmez.",
                                    style = sys(11), color = DS.Colors.onSurfaceVariant,
                                )
                            }
                            if (topluGecersiz) {
                                Text(
                                    "Bu değer geçersiz: indirim, indirime açık satırların toplamını aşamaz.",
                                    style = sys(12, FontWeight.SemiBold), color = DS.Colors.danger,
                                )
                            }
                        }
                    }
                    bolum("toplamlar") {
                        val liste = hesap.liste
                        val son = hesap.net
                        val indirimli = liste - son > 0.005
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (indirimli) {
                                SatirToplam("LİSTE TOPLAMI", Para.tl(liste))
                                SatirToplam("İNDİRİM", "−" + Para.tl(hesap.indirim + hesap.toplu))
                            }
                            SatirToplam(if (indirimli) "TOPLAM (İNDİRİMLİ)" else "TOPLAM", Para.tl(son))
                            if (hesap.toplu > 0) {
                                Text(
                                    if (topluKip == IslemKurallari.TopluKip.yuzde) "Toplu indirim %$topluYuzde her kaleme orantılı dağıtıldı"
                                    else "Toplu indirim ${Para.tl(hesap.toplu)} her kaleme orantılı dağıtıldı",
                                    style = sys(11), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    IslemOnayi(
        acik = kipSorusu != null,
        baslik = "Seçili dişler nasıl kaydedilsin?",
        secenekler = listOf(
            OnaySecenegi("TEKER TEKER KAYDET") { kipSorusu?.let { ekle(it.katalog, it.ad, it.fiyat, TedaviEditoru.DisKipi.teker) } },
            OnaySecenegi("TOPLU KAYDET") { kipSorusu?.let { ekle(it.katalog, it.ad, it.fiyat, TedaviEditoru.DisKipi.toplu) } },
        ),
        kapat = { kipSorusu = null },
    )
    val uyari = asamaUyarisi
    DfyAlert(
        isPresented = uyari != null,
        title = "Yapılan ya da yapılacak kalemler değişiyor",
        message = "Değiştirdiğiniz aşağıdaki kalemler Yapılan ya da Yapılacak bölümünde. Değişiklik hastanın borcunu ve hekim hakedişini " +
            "etkileyebilir. Kaydetmek istediğinize emin misiniz?\n" + (uyari ?: emptyList()).joinToString("\n") { "• $it" },
        confirmText = "Kaydet",
        onConfirm = { asamaYaniti(true) },
        dismissText = "Vazgeç",
        onDismiss = { asamaYaniti(false) },
    )
}

private val ASAMALAR = listOf("PLANNED", "TODO", "DONE")

private fun <T> Set<T>.degistir(v: T): Set<T> = if (v in this) this - v else this + v

/** Üst düzey form parçası: 14 dp ara (Swift `VStack(spacing: 14)`). */
private fun LazyListScope.bolum(anahtar: String, icerik: @Composable () -> Unit) {
    item(key = anahtar, contentType = anahtar) { Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) { icerik() } }
}

@Composable
private fun HekimMenusu(secili: String, secenekler: List<Pair<String, String>>, sec: (String) -> Unit) {
    IslemMenusu(secenekler, secili, sec, Modifier.fillMaxWidth())
}

@Composable
private fun Cip(ad: String, secili: Boolean, eylem: () -> Unit) {
    Text(
        ad,
        Modifier
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .background(if (secili) DS.Colors.cyan400.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (secili) DS.Colors.cyan400.copy(alpha = 0.4f) else DS.Colors.hairline, CircleShape)
            .plainClickable(onClick = eylem)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = sys(12, FontWeight.SemiBold), color = if (secili) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant, maxLines = 1,
    )
}

/** `DisclosureGroup` başlığı — grup adı (büyük harf, pembe) + ok. */
@Composable
private fun GrupBasligi(ad: String, acik: Boolean, degistir: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .plainClickable(onClick = degistir)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ad.uppercase(Locale.forLanguageTag("tr-TR")), Modifier.weight(1f), style = sys(11, FontWeight.Bold), color = hex("FDA4AF"),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        DfyIcon(if (acik) "chevron.down" else "chevron.right", size = 12.dp, tint = DS.Colors.onSurfaceVariant)
    }
}

@Composable
private fun KatalogSatiri(k: KatalogKalemi, modifier: Modifier = Modifier, sec: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .plainClickable(onClick = sec)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(k.name, style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            k.group?.let { Text(it, style = sys(10), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(k.price?.let { fiyatMetni(it.deger, k.currency) } ?: "—", style = sys(12, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1)
    }
}

private fun fiyatMetni(v: Double, para: String?): String {
    val c = (para ?: "TRY").uppercase()
    return if (c == "TRY") Para.tl(v) else "${String.format(Locale.ROOT, "%.2f", v).replace(".", ",")} $c"
}

/** Düzenleme alanındaki indirim metni (Swift `String(Double)` — tam sayıda ondalıksız). */
private fun indirimMetni(x: Double): String = when {
    x == 0.0 -> ""
    x % 1.0 == 0.0 && kotlin.math.abs(x) < 1e15 -> x.toLong().toString()
    else -> x.toString()
}

/**
 * Bölüm B (52D): Yapılan satır düzenlenir (hakediş onayıyla); yalnız kaldırılamaz — sunucu da 409 `E_PLAN_EDIT_DONE_DELETE`.
 * Dar pencerede ve büyük yazıda denetimler alt satıra geçer (taşma yok).
 */
@Composable
private fun SatirKarti(
    s: TedaviEditoru.Satir,
    sonuc: IslemKurallari.SatirSonucu?,
    hekimSecenekleri: List<Pair<String, String>>,
    modifier: Modifier,
    degisti: (TedaviEditoru.Satir) -> Unit,
    kaldir: () -> Unit,
) {
    val sekil = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(sekil)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, DS.Colors.hairline, sekil)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                s.konumEtiketi.ifEmpty { "—" },
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .widthIn(max = 140.dp),
                style = sys(11, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(s.ad, Modifier.weight(1f), style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Para.tl(sonuc?.son), style = sys(13, FontWeight.Bold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1)
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .plainClickable(onClick = kaldir)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { DfyIcon("trash", size = 13.dp, tint = if (s.yapilan) DS.Colors.outline else DS.Colors.danger) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!s.toplu) {
                IslemAdimi(
                    "×${s.adet}", s.adet, 1..99, { degisti(s.copy(adet = max(1, it), degisti = true)) },
                    Modifier.align(Alignment.CenterVertically), yazi = sys(12),
                )
            }
            var metin by remember(s.id) { mutableStateOf(indirimMetni(s.indirim)) }
            KutuAlani(
                metin,
                {
                    metin = it
                    degisti(s.copy(indirim = ondalikOku(it) ?: 0.0, degisti = true))
                },
                "İndirim", Modifier.width(84.dp).align(Alignment.CenterVertically), klavye = ONDALIK,
            )
            IslemSegmenti(
                listOf("TL", "%"), if (s.yuzdeMi) 1 else 0, { degisti(s.copy(yuzdeMi = it == 1, degisti = true)) },
                Modifier.width(80.dp).align(Alignment.CenterVertically),
            )
        }
        IslemMenusu(
            listOf("" to "Atanmamış") + hekimSecenekleri, s.hekimId ?: "",
            { degisti(s.copy(hekimId = it.ifEmpty { null }, degisti = true)) },
            Modifier.fillMaxWidth(), renk = DS.Colors.onSurfaceVariant, yazi = sys(12),
        )
    }
}

@Composable
private fun SatirToplam(e: String, d: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(e, style = sys(10, FontWeight.Bold), color = DS.Colors.onSurfaceVariant)
        Text(d, style = sys(13, FontWeight.Bold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1)
    }
}

// MARK: - Giriş kutusu (Swift `KutuStili`)

/** Swift `KutuStili` zemini — %4 beyaz zemin, 10'luk köşe, ince kenar (hatalıysa kırmızı), 10 yatay boşluk, en az 40 dp. */
fun Modifier.kutuStili(hatali: Boolean = false, enAzYukseklik: Dp = 40.dp): Modifier {
    val sekil = RoundedCornerShape(10.dp)
    return this
        .heightIn(min = enAzYukseklik)
        .clip(sekil)
        .background(Color.White.copy(alpha = 0.04f))
        .border(1.dp, if (hatali) DS.Colors.danger else DS.Colors.hairlineStrong, sekil)
        .padding(horizontal = 10.dp)
}

/** Swift adıyla (`.modifier(KutuStili())`): `Modifier.then(KutuStili())`. */
@Suppress("FunctionName")
fun KutuStili(): Modifier = Modifier.kutuStili()

/**
 * `TextField(ipucu, text:).modifier(KutuStili())` — 14 pt yazı, yer tutucu soluk; tek satırda dikey ortalı, çok satırda üstten.
 * Başka paketler de kullanabilir (yeni randevu, medya notu).
 */
@Composable
fun KutuAlani(
    deger: String,
    degisti: (String) -> Unit,
    ipucu: String,
    modifier: Modifier = Modifier,
    klavye: KeyboardOptions = KeyboardOptions.Default,
    tekSatir: Boolean = true,
    enAzSatir: Int = 1,
    enCokSatir: Int = if (tekSatir) 1 else 6,
    hatali: Boolean = false,
    etkin: Boolean = true,
    enAzYukseklik: Dp = 40.dp,
) {
    BasicTextField(
        value = deger,
        onValueChange = degisti,
        modifier = modifier.fillMaxWidth(),
        enabled = etkin,
        singleLine = tekSatir,
        minLines = enAzSatir,
        maxLines = enCokSatir,
        textStyle = sys(14).copy(color = DS.Colors.onSurface),
        keyboardOptions = klavye,
        cursorBrush = SolidColor(DS.Colors.cyan400),
        decorationBox = { ic ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .kutuStili(hatali, enAzYukseklik)
                    .padding(vertical = if (tekSatir) 0.dp else 10.dp),
                contentAlignment = if (tekSatir) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (deger.isEmpty()) {
                    Text(ipucu, style = sys(14), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ic()
            }
        },
    )
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 844)
@Composable
private fun TedaviEditoruSheetPreview() {
    PreviewEnv {
        OrnekModelSaglayici {
            TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.duzenle(OrnekIslemVerisi.plan), onDismiss = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun TedaviEditoruSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OrnekModelSaglayici {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.width(640.dp)) { TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.yeniPlan, onDismiss = {}) }
            }
        }
    }
}
