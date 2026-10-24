package com.dentifay.app.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CalendarLocale
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerFormatter
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIDoctorMember
import com.dentifay.app.core.APIPatientWithAppointments
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.KaraListeEngeli
import com.dentifay.app.core.Unvan
import com.dentifay.app.hasta.SonucBandi
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.TurkceYerel
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private val trYerel: Locale = Locale.forLanguageTag("tr-TR")
private val TARIH_DUGMESI: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", trYerel)
private val SAAT_DUGMESI: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", trYerel)

/** Ekranın askıdaki istekleri için düz tutucu (çizimi etkilemez). */
private class Tutucu<T>(var deger: T)

private enum class SaatAlani { baslangic, bitis }

/**
 * "Yeni Randevu" — web penceresiyle aynı (M-E8.13 + M-E9.3): randevu türü (9 tür), hasta bölümü yalnız hasta türlerinde, hekim
 * ("— Seçilmedi —" seçilebilir), tarih · Tüm Gün · başlangıç/bitiş (şubenin dilimi; varsayılan bitiş = +1 dilim), işlem yalnız
 * işlem türlerinde. Kayıttan önce çakışma ön denetimi; kara listedeki hastada gerekçe istemi. Saatler kliniğin saat diliminde,
 * `Z` gönderilir.
 *
 * @param kapat `true` → randevu kaydedildi (sunan taraf randevuları tazeler).
 */
@Composable
fun YeniRandevuSheet(kapat: (kaydedildi: Boolean) -> Unit) {
    val auth = LocalAuthManager.current
    val onizleme = LocalInspectionMode.current
    val scope = rememberCoroutineScope()

    var f by remember { mutableStateOf(YeniRandevu.Form()) }
    var arama by remember { mutableStateOf("") }
    var sonuclar by remember { mutableStateOf<List<APIPatientWithAppointments>>(emptyList()) }
    val aramaGorevi = remember { Tutucu<Job?>(null) }
    var secili by remember { mutableStateOf<APIPatientWithAppointments?>(null) }
    var hekimler by remember { mutableStateOf<List<APIDoctorMember>>(emptyList()) }
    var islemler by remember { mutableStateOf<List<YeniRandevu.IslemTuru>>(emptyList()) }
    var kaydediliyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    var bilgi by remember { mutableStateOf<String?>(null) }
    val anahtar = remember { UUID.randomUUID().toString().uppercase(Locale.ROOT) }
    /** Randevu süresi (sn): başlangıç kayınca bitiş bu süreyle birlikte kayar; kullanıcı bitişi değiştirince güncellenir. */
    var sure by remember { mutableLongStateOf(1800L) }
    // Çakışma sorusu ve kara liste gerekçesi (sonucu bekleyen akış için).
    var cakismaSorusu by remember { mutableStateOf<String?>(null) }
    val cakismaDevam = remember { Tutucu<CompletableDeferred<Boolean>?>(null) }
    var karaListe by remember { mutableStateOf<KaraListeEngeli?>(null) }
    var gerekce by remember { mutableStateOf("") }
    /** Kayıt sonrası uyarılar ("Randevu oluşturuldu" — web `RandevuUyariPenceresi`). */
    var sonrakiUyarilar by remember { mutableStateOf<List<String>?>(null) }
    var tarihSecici by remember { mutableStateOf(false) }
    var saatSecici by remember { mutableStateOf<SaatAlani?>(null) }

    val caps = auth.yetenekler
    val saatDilimi: ZoneId = remember(auth.currentUser?.timezone) {
        auth.currentUser?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    val tarihBicimi = remember(saatDilimi) { TARIH_DUGMESI.withZone(saatDilimi) }
    val saatBicimi = remember(saatDilimi) { SAAT_DUGMESI.withZone(saatDilimi) }
    val hastaAcabilir = "patient.create" in caps
    val hastaTuru = f.kategori in YeniRandevu.hastaKategorileri
    val islemTuru = f.kategori in YeniRandevu.islemKategorileri
    val islemAdlari = remember(islemler) {
        islemler.filter { it.isActive != false && it.ad.isNotEmpty() }.map { it.ad }.ifEmpty { YeniRandevu.yedekIslemler }
    }
    val hazir = !kaydediliyor && (!hastaTuru || secili != null || YeniRandevu.kirp(f.ad).isNotEmpty())

    /** Swift `.onChange(of: f.baslangic)`: bitiş süreyle birlikte kayar. */
    fun baslangicDegisti(yeni: Instant) {
        f = f.copy(baslangic = yeni, bitis = yeni.plusSeconds(sure))
    }

    /** Swift `.onChange(of: f.bitis)`: kullanıcı bitişi değiştirince süre güncellenir. */
    fun bitisDegisti(yeni: Instant) {
        val s = Duration.between(f.baslangic, yeni).seconds
        f = f.copy(bitis = yeni)
        if (s > 0) sure = s
    }

    fun ara(q: String) {
        aramaGorevi.deger?.cancel()
        val t = YeniRandevu.kirp(q)
        if (t.length < 2) {
            sonuclar = emptyList()
            return
        }
        aramaGorevi.deger = scope.launch {
            delay(300)
            val r = try {
                ApiClient.searchPatients(query = t, limit = 8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            sonuclar = r
        }
    }

    suspend fun cakismaSor(metin: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        cakismaDevam.deger?.complete(false)
        cakismaDevam.deger = d
        cakismaSorusu = metin
        return d.await()
    }

    fun cakismaYaniti(evet: Boolean) {
        cakismaSorusu = null
        cakismaDevam.deger?.complete(evet)
        cakismaDevam.deger = null
    }

    fun govde(gerekce: String? = null): JsonObject {
        val g = YeniRandevu.govde(f, saatDilimi, hastaAcabilir)
        if (gerekce == null) return g
        return JsonObject(g + mapOf("overrideBlocked" to JsonPrimitive(true), "overrideReason" to JsonPrimitive(gerekce.take(500))))
    }

    fun kaydet() {
        hata = null
        bilgi = null
        if (!f.tumGun && !f.bitis.isAfter(f.baslangic)) {
            hata = YeniRandevu.tarihGecersiz
            return
        }
        if (islemTuru && YeniRandevu.kirp(f.islem).isEmpty()) {
            hata = YeniRandevu.islemGerekli
            return
        }
        kaydediliyor = true
        scope.launch {
            try {
                val form = f
                val (bas, son) = YeniRandevu.aralik(form, saatDilimi)
                var onaylandi = false
                var uyarilar = emptyList<YeniRandevu.Uyari>()
                val s = YeniRandevu.kaydetAkisi(
                    form, saatDilimi,
                    denetle = { ApiClient.randevuCakismasi(hekimId = form.hekimId ?: "", baslangic = bas, bitis = son) },
                    sor = { metin -> cakismaSor(metin).also { onaylandi = it } },
                    uyar = { bilgi = it },
                    kaydet = { uyarilar = ApiClient.randevuOlustur(govde(), anahtar) },
                )
                if (s == YeniRandevu.Sonuc.kaydedildi) {
                    Haptics.success()
                    val m = YeniRandevu.kayitSonrasiUyarilar(uyarilar, cakismaOnaylandi = onaylandi)
                    if (m.isEmpty()) kapat(true) else sonrakiUyarilar = m
                }
            } catch (e: KaraListeEngeli) {
                Haptics.error()
                if (e.gecilebilir) {
                    gerekce = ""
                    karaListe = e
                } else {
                    hata = e.cumle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                hata = if (e is ApiException.NetworkError) YeniRandevu.agHatasi else (e.message ?: YeniRandevu.agHatasi)
            } finally {
                kaydediliyor = false
            }
        }
    }

    /** Kara liste gerekçesiyle AYNI istek, AYNI `Idempotency-Key` (web). */
    fun gonder(gerekce: String) {
        kaydediliyor = true
        scope.launch {
            try {
                ApiClient.randevuOlustur(govde(gerekce), anahtar)
                Haptics.success()
                kapat(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                hata = e.message?.takeIf { it.isNotEmpty() } ?: "Kayıt hatası"
            } finally {
                kaydediliyor = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val simdi = System.currentTimeMillis() / 1000.0
        if (onizleme) {
            val bas = Instant.ofEpochSecond((ceil(simdi / 1800) * 1800).toLong())
            f = f.copy(baslangic = bas, bitis = bas.plusSeconds(1800), islem = "Muayene")
            return@LaunchedEffect
        }
        val dilim = ApiClient.subeDilimi()
        // Varsayılan saat: sonraki dilim sınırı; bitiş = başlangıç + 1 dilim (web hücre seçimi).
        val adim = dilim * 60L
        val bas = Instant.ofEpochSecond((ceil(simdi / adim) * adim).toLong())
        sure = adim
        f = f.copy(baslangic = bas, bitis = bas.plusSeconds(adim))
        coroutineScope {
            val h = async { yumusak { ApiClient.fetchAssignableDoctors() } }
            val i = async { yumusak { ApiClient.randevuIslemleri() } }
            hekimler = h.await() ?: emptyList()
            islemler = i.await() ?: emptyList()
        }
        val ben = auth.currentUser?.id
        if (ben != null && hekimler.any { it.userId == ben }) f = f.copy(hekimId = ben)
        if (f.islem.isEmpty()) f = f.copy(islem = if (islemler.isEmpty()) "Muayene" else YeniRandevu.varsayilanIslem(islemler))
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.surfaceContainer)) {
        UstCubuk(hazir = hazir, onVazgec = { kapat(false) }, onKaydet = { kaydet() })

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .icerikGenisligi(640.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            hata?.let { SonucBandi(basari = false, metin = it) }
            bilgi?.let { SonucBandi(basari = true, metin = it) }

            if (hastaTuru) {
                Etiket("Hasta Bilgisi")
                val s = secili
                if (s != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${s.firstName} ${s.lastName} seçildi", Modifier.weight(1f),
                            style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        DfyIcon(
                            "xmark.circle.fill", size = 17.dp, tint = DS.Colors.outline,
                            modifier = Modifier
                                .plainClickable {
                                    secili = null
                                    f = f.copy(hastaId = null)
                                }
                                .semantics { contentDescription = "Seçimi kaldır" },
                        )
                    }
                } else {
                    KutuAlani(
                        arama, { arama = it; ara(it) }, "İsim veya telefon...",
                        klavye = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                    )
                    sonuclar.forEach { p ->
                        key(p.id) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .plainClickable {
                                        aramaGorevi.deger?.cancel()
                                        secili = p
                                        f = f.copy(hastaId = p.id)
                                        arama = ""
                                        sonuclar = emptyList()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "${p.firstName} ${p.lastName}", Modifier.weight(1f), style = sys(14), color = DS.Colors.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(p.phone ?: "", style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                    Etiket("Ya da yeni hasta bilgileri girin")
                    KutuAlani(f.ad, { f = f.copy(ad = it) }, "Ad *", klavye = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                    KutuAlani(f.soyad, { f = f.copy(soyad = it) }, "Soyad", klavye = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                    KutuAlani(f.telefon, { f = f.copy(telefon = it) }, "5XX XXX XX XX", klavye = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    if (hastaAcabilir && YeniRandevu.kirp(f.ad + f.soyad).isNotEmpty()) {
                        AnahtarSatiri(
                            etiket = { Text("Hasta kaydı oluşturulsun", style = sys(14), color = DS.Colors.onSurface) },
                            acik = f.hastaKaydiOlustur,
                            onDegis = { f = f.copy(hastaKaydiOlustur = it) },
                        )
                        Text(
                            "İşaretlemezseniz randevu misafir olarak kaydedilir; hasta kaydını sonra randevudan açabilirsiniz.",
                            style = sys(11), color = DS.Colors.onSurfaceVariant,
                        )
                    }
                }
            }

            Etiket("Randevu Detayları")
            SeciciMenu(
                secili = f.kategori,
                secenekler = YeniRandevu.kategoriler,
                onSec = { f = f.copy(kategori = it) },
            )
            Etiket("Hekim *")
            SeciciMenu(
                secili = f.hekimId ?: "",
                secenekler = listOf("" to "— Seçilmedi —") + hekimler.map { it.userId to Unvan.hekimAdi(it.user.name ?: "") },
                onSec = { f = f.copy(hekimId = it.ifEmpty { null }) },
            )
            TarihSatiri("Tarih *", tarihBicimi.format(f.baslangic)) { tarihSecici = true }
            AnahtarSatiri(
                etiket = {
                    Text(
                        buildAnnotatedString {
                            append("Tüm Gün ")
                            withStyle(SpanStyle(fontSize = 12.sp, color = DS.Colors.onSurfaceVariant)) { append("— saat aralığı yok sayılır") }
                        },
                        style = sys(14), color = DS.Colors.onSurface,
                    )
                },
                acik = f.tumGun,
                onDegis = { f = f.copy(tumGun = it) },
            )
            if (!f.tumGun) {
                TarihSatiri("Başlangıç *", saatBicimi.format(f.baslangic)) { saatSecici = SaatAlani.baslangic }
                TarihSatiri("Bitiş *", saatBicimi.format(f.bitis)) { saatSecici = SaatAlani.bitis }
            }
            if (islemTuru) {
                Etiket("İşlem")
                val ozel = if (f.islem.isNotEmpty() && f.islem !in islemAdlari) listOf(f.islem to f.islem) else emptyList()
                SeciciMenu(
                    secili = f.islem,
                    secenekler = listOf("" to "İşlem seçin") + islemAdlari.map { it to it } + ozel,
                    onSec = { f = f.copy(islem = it) },
                )
                KutuAlani(f.islem, { f = f.copy(islem = it) }, "İşlem adını yazın")
            }
        }
    }

    // MARK: Pencereler

    if (tarihSecici) {
        val yerel = f.baslangic.atZone(saatDilimi)
        TarihPenceresi(
            gun = yerel.toLocalDate(),
            onTamam = { gun -> baslangicDegisti(ZonedDateTime.of(gun, yerel.toLocalTime(), saatDilimi).toInstant()); tarihSecici = false },
            onVazgec = { tarihSecici = false },
        )
    }

    saatSecici?.let { alan ->
        val an = if (alan == SaatAlani.baslangic) f.baslangic else f.bitis
        val yerel = an.atZone(saatDilimi)
        SaatPenceresi(
            baslik = if (alan == SaatAlani.baslangic) "Başlangıç" else "Bitiş",
            saat = yerel.toLocalTime(),
            onTamam = { saat ->
                val yeni = ZonedDateTime.of(yerel.toLocalDate(), saat, saatDilimi).toInstant()
                if (alan == SaatAlani.baslangic) baslangicDegisti(yeni) else bitisDegisti(yeni)
                saatSecici = null
            },
            onVazgec = { saatSecici = null },
        )
    }

    DfyAlert(
        isPresented = cakismaSorusu != null,
        title = "Hekim çakışması",
        message = cakismaSorusu,
        confirmText = "Yine de kaydet",
        onConfirm = { cakismaYaniti(true) },
        dismissText = "Vazgeç",
        onDismiss = { cakismaYaniti(false) },
    )

    karaListe?.let { engel ->
        AlertDialog(
            onDismissRequest = { karaListe = null },
            containerColor = DS.Colors.surfaceContainerHigh,
            title = { Text("Kara liste", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(KaraListeEngeli.istemMetni(engel.cumle), style = sys(14), color = DS.Colors.onSurfaceVariant)
                    KutuAlani(gerekce, { gerekce = it }, "Gerekçe", tekSatir = false)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val g = gerekce.trim()
                    karaListe = null
                    // Web: vazgeçilirse ya da gerekçe 3 karakterden kısaysa sessizce biter.
                    if (g.length >= 3) gonder(g)
                }) { Text("Yine de aç", style = sys(15, FontWeight.SemiBold), color = DS.Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { karaListe = null }) { Text("Vazgeç", style = sys(15), color = DS.Colors.onSurfaceVariant) }
            },
        )
    }

    sonrakiUyarilar?.let { uyarilar ->
        DfyAlert(
            isPresented = true,
            title = "Randevu oluşturuldu",
            message = "Dikkat edilmesi gereken noktalar:\n" + uyarilar.joinToString("\n") { "• $it" },
            confirmText = "Kapat",
            onConfirm = { sonrakiUyarilar = null; kapat(true) },
            onDismiss = { sonrakiUyarilar = null; kapat(true) },
        )
    }
}

/** `try?` — iptal dışındaki hatada null. */
private suspend fun <T> yumusak(blok: suspend () -> T): T? = try {
    blok()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

// MARK: - Parçalar

@Composable
private fun UstCubuk(hazir: Boolean, onVazgec: () -> Unit, onKaydet: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(DS.Colors.background).heightIn(min = 52.dp).padding(horizontal = 16.dp)) {
        Text(
            "Vazgeç",
            Modifier
                .align(Alignment.CenterStart)
                .plainClickable(onClick = onVazgec)
                .semantics { role = Role.Button }
                .padding(vertical = 10.dp),
            style = sys(17), color = DS.Colors.cyan400, maxLines = 1,
        )
        Text(
            "Yeni Randevu",
            Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Kaydet",
            Modifier
                .align(Alignment.CenterEnd)
                .alpha(if (hazir) 1f else 0.4f)
                .plainClickable(enabled = hazir, onClick = onKaydet)
                .semantics { role = Role.Button }
                .padding(vertical = 10.dp),
            style = sys(17, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1,
        )
    }
}

@Composable
private fun Etiket(metin: String) {
    Text(metin, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
}

/** Giriş kutusu — Swift `KutuStili` (14 pt, 40 yükseklik, %4 beyaz zemin, 10'luk köşe, ince kenar). */
@Composable
private fun KutuAlani(
    deger: String,
    onDeger: (String) -> Unit,
    yerTutucu: String,
    modifier: Modifier = Modifier,
    klavye: KeyboardOptions = KeyboardOptions.Default,
    tekSatir: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = deger,
        onValueChange = onDeger,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, DS.Colors.hairlineStrong, shape),
        singleLine = tekSatir,
        minLines = 1,
        maxLines = if (tekSatir) 1 else 5,
        textStyle = sys(14).copy(color = DS.Colors.onSurface),
        cursorBrush = SolidColor(DS.Colors.cyan400),
        keyboardOptions = klavye,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = if (tekSatir) 0.dp else 10.dp),
                contentAlignment = if (tekSatir) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (deger.isEmpty()) Text(yerTutucu, style = sys(14), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** Menü biçimli seçici (Swift `Picker(.menu)`) — seçili değer camgöbeği, dokununca liste. */
@Composable
private fun SeciciMenu(secili: String, secenekler: List<Pair<String, String>>, onSec: (String) -> Unit) {
    var acik by remember { mutableStateOf(false) }
    val etiket = secenekler.firstOrNull { it.first == secili }?.second ?: secili
    Box {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .plainClickable { acik = true }
                .semantics { role = Role.DropdownList }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                etiket, Modifier.weight(1f, fill = false),
                style = sys(14, FontWeight.Medium), color = DS.Colors.cyan400,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            DfyIcon("chevron.down", size = 11.dp, tint = DS.Colors.cyan400)
        }
        DropdownMenu(expanded = acik, onDismissRequest = { acik = false }, containerColor = DS.Colors.surfaceContainerHigh) {
            secenekler.forEach { (kod, ad) ->
                val bu = kod == secili
                DropdownMenuItem(
                    text = {
                        Text(
                            ad, style = sys(15, if (bu) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (bu) DS.Colors.cyan400 else DS.Colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = if (bu) ({ DfyIcon("checkmark", size = 14.dp, tint = DS.Colors.cyan400) }) else null,
                    onClick = {
                        acik = false
                        onSec(kod)
                    },
                )
            }
        }
    }
}

/** Swift `DatePicker` (kompakt) satırı: başlık + dokunulabilir değer kapsülü. */
@Composable
private fun TarihSatiri(baslik: String, deger: String, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(baslik, Modifier.weight(1f), style = sys(14), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            deger,
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .plainClickable(onClick = onTap)
                .semantics { role = Role.Button }
                .padding(horizontal = 11.dp, vertical = 7.dp),
            style = sys(15).monoDigits(), color = DS.Colors.onSurface, maxLines = 1,
        )
    }
}

/** Swift `Toggle` satırı — satırın tamamı dokunulabilir. */
@Composable
private fun AnahtarSatiri(etiket: @Composable () -> Unit, acik: Boolean, onDegis: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = acik, role = Role.Switch, onValueChange = onDegis),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { etiket() }
        Switch(
            checked = acik,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DS.Colors.cyan400,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = DS.Colors.metal,
                uncheckedTrackColor = DS.Colors.surfaceContainerHighest,
                uncheckedBorderColor = DS.Colors.hairlineStrong,
            ),
        )
    }
}

@Composable
private fun TarihPenceresi(gun: java.time.LocalDate, onTamam: (java.time.LocalDate) -> Unit, onVazgec: () -> Unit) {
    // Kliniğin dili (Swift `.environment(\.locale, tr_TR)`): ay ve gün adları Türkçe.
    val durum: DatePickerState = remember {
        DatePickerState(locale = trYerel, initialSelectedDateMillis = gun.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    }
    val renkler = DatePickerDefaults.colors(
        containerColor = DS.Colors.surfaceContainerHigh,
        weekdayContentColor = DS.Colors.onSurfaceVariant,
        subheadContentColor = DS.Colors.onSurfaceVariant,
        navigationContentColor = DS.Colors.cyan400,
        yearContentColor = DS.Colors.onSurface,
        currentYearContentColor = DS.Colors.cyan400,
        selectedYearContainerColor = DS.Colors.cyan400,
        selectedYearContentColor = Color.White,
        dayContentColor = DS.Colors.onSurface,
        selectedDayContainerColor = DS.Colors.cyan400,
        selectedDayContentColor = Color.White,
        todayContentColor = DS.Colors.cyan400,
        todayDateBorderColor = DS.Colors.cyan400,
    )
    DatePickerDialog(
        onDismissRequest = onVazgec,
        confirmButton = {
            TextButton(onClick = {
                val ms = durum.selectedDateMillis
                if (ms != null) onTamam(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) else onVazgec()
            }) { Text("Tamam", style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400) }
        },
        dismissButton = {
            TextButton(onClick = onVazgec) { Text("Vazgeç", style = sys(15), color = DS.Colors.onSurfaceVariant) }
        },
        colors = renkler,
    ) {
        TurkceYerel {
            DatePicker(
                state = durum, dateFormatter = TrTarihBicimi, title = null, headline = null, showModeToggle = false, colors = renkler,
            )
        }
    }
}

/** Takvim başlığı da Türkçe (Material başlığı cihaz dilini kullanır; Swift ekranı `tr_TR` ile sabit). */
private object TrTarihBicimi : DatePickerFormatter {
    private val ayYil = DateTimeFormatter.ofPattern("LLLL yyyy", trYerel)
    private val gun = DateTimeFormatter.ofPattern("d MMMM yyyy", trYerel)
    private val uzunGun = DateTimeFormatter.ofPattern("d MMMM yyyy EEEE", trYerel)

    override fun formatMonthYear(monthMillis: Long?, locale: CalendarLocale): String? =
        monthMillis?.let { ayYil.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) }

    override fun formatDate(dateMillis: Long?, locale: CalendarLocale, forContentDescription: Boolean): String? =
        dateMillis?.let { (if (forContentDescription) uzunGun else gun).format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) }
}

@Composable
private fun SaatPenceresi(baslik: String, saat: LocalTime, onTamam: (LocalTime) -> Unit, onVazgec: () -> Unit) {
    val durum = rememberTimePickerState(initialHour = saat.hour, initialMinute = saat.minute, is24Hour = true)
    Dialog(onDismissRequest = onVazgec, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(DS.Colors.surfaceContainerHigh)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(baslik, Modifier.fillMaxWidth(), style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            TimePicker(
                state = durum,
                colors = TimePickerDefaults.colors(
                    clockDialColor = DS.Colors.surfaceContainerHighest,
                    clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = DS.Colors.onSurface,
                    selectorColor = DS.Colors.cyan400,
                    containerColor = DS.Colors.surfaceContainerHigh,
                    timeSelectorSelectedContainerColor = DS.Colors.cyan400.copy(alpha = 0.22f),
                    timeSelectorUnselectedContainerColor = DS.Colors.surfaceContainerHighest,
                    timeSelectorSelectedContentColor = DS.Colors.cyan400,
                    timeSelectorUnselectedContentColor = DS.Colors.onSurface,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onVazgec) { Text("Vazgeç", style = sys(15), color = DS.Colors.onSurfaceVariant) }
                TextButton(onClick = { onTamam(LocalTime.of(durum.hour, durum.minute)) }) {
                    Text("Tamam", style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun YeniRandevuSheetPreview() {
    PreviewEnv { YeniRandevuSheet(kapat = {}) }
}
