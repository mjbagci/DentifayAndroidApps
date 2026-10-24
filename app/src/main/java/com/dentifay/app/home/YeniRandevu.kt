package com.dentifay.app.home

import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.govde as nesne
import com.dentifay.app.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Yerli yeni randevu (DentAIWeb 52A; MOBIL_IS_EMRI M-E8.13 + M-E9.3) — `POST /api/appointments` (`appt.create`), misafirden hasta
 * kaydı `POST /api/appointments/:id/hasta-kaydi` (`patient.create`). İşlem varsayılanı web `varsayilanIslem`: ayarlardaki varsayılan →
 * Muayene → boş.
 */
object YeniRandevu {
    /** `GET /api/settings-collections?type=APPOINTMENT_TYPE&active=true` satırı (süre alanı YOK). */
    @Serializable
    data class IslemTuru(
        val id: String,
        val name: String? = null,
        val systemKey: String? = null,
        val isActive: Boolean? = null,
        val isDefault: Boolean? = null,
    ) {
        val ad: String get() = YeniRandevu.kirp(name ?: "")
    }

    /** Liste okunamazsa web'in yedek adları (ilki "Muayene"). */
    val yedekIslemler: List<String> = listOf(
        "Muayene", "Konsültasyon", "Kontrol", "Beyazlatma", "Total Bakım", "Cerrahi", "İmplant", "Kanal Tedavisi",
        "Ortodonti", "Periodontoloji", "Protez", "Tedavi (Dolgular)", "Pedodonti",
    )

    fun varsayilanIslem(liste: List<IslemTuru>): String {
        val etkin = liste.filter { it.isActive != false && it.ad.isNotEmpty() }
        etkin.firstOrNull { it.isDefault == true }?.let { return it.ad }
        return etkin.firstOrNull { it.systemKey == "muayene" }?.ad ?: ""
    }

    /** Randevu türleri (web `CATEGORY_VALUES` sırası ve etiketleri) — (kod, ad). Türe göre yetki kapısı YOK (sunucuda tek kapı `appt.create`). */
    val kategoriler: List<Pair<String, String>> = listOf(
        "PATIENT_VISIT" to "Hasta Randevu", "VIDEO_CONSULTATION" to "Görüntülü Randevu", "MEETING" to "Toplantı / Görüşme",
        "LEAVE" to "İzinli", "CONFERENCE" to "Kongre / Eğitim", "BREAK" to "Mola / Yemek", "RESERVED" to "Rezerve",
        "PUBLIC_HOLIDAY" to "Resmi Tatil", "LAB" to "Laboratuvar",
    )

    /** Hasta bölümü çizilen türler · İşlem alanı çizilen türler. */
    val hastaKategorileri: Set<String> = setOf("PATIENT_VISIT", "VIDEO_CONSULTATION")
    val islemKategorileri: Set<String> = setOf("PATIENT_VISIT", "VIDEO_CONSULTATION", "LAB")

    /** Şubenin dilim dakikası (`GET /api/branch-settings` `branch.slotMinutes`); küme dışıysa 30. */
    fun dilim(dk: Int?): Int = if ((dk ?: 0) in listOf(5, 10, 15, 20, 30, 45, 60)) dk ?: 30 else 30

    /** Swift `struct Form` (değer türü) — ekranda `copy` ile güncellenir. */
    data class Form(
        val kategori: String = "PATIENT_VISIT",
        val hastaId: String? = null,
        val ad: String = "",
        val soyad: String = "",
        val telefon: String = "",
        val hastaKaydiOlustur: Boolean = false,
        val hekimId: String? = null,
        val tumGun: Boolean = false,
        val baslangic: Instant = Instant.now(),
        val bitis: Instant = Instant.now(),
        val islem: String = "",
    )

    const val islemGerekli = "İşlem seçin ya da işlem adını yazın."
    const val tarihGecersiz = "Tarih ya da saat geçerli değil; günü ve başlangıç–bitiş saatlerini kontrol edin."
    const val denetimYapilamadi = "Çakışma denetimi yapılamadı; randevu yine kaydedilecek, olası çakışma kayıttan sonra bildirilecek."
    const val agHatasi = "Randevu kaydedilemedi. Bağlantınızı kontrol edip tekrar deneyin."

    /** Tüm Gün: kliniğin duvar saatiyle 00:00–23:59. */
    fun aralik(f: Form, saatDilimi: ZoneId): Pair<Instant, Instant> {
        if (!f.tumGun) return f.baslangic to f.bitis
        val gun = f.baslangic.atZone(saatDilimi).toLocalDate().atStartOfDay(saatDilimi).toInstant()
        return gun to gun.plusSeconds(23L * 3600 + 59 * 60)
    }

    /** Gövde (web `POST /api/appointments`). Hasta alanları yalnız hasta türlerinde; `procedureCustomLabel` yalnız işlem türlerinde. */
    fun govde(f: Form, saatDilimi: ZoneId, hastaAcabilir: Boolean): JsonObject {
        fun dolu(s: String): String? = kirp(s).ifEmpty { null }
        val (bas, son) = aralik(f, saatDilimi)
        val alanlar = mutableListOf<Pair<String, Any?>>(
            "startAt" to zDamgasi(bas), "endAt" to zDamgasi(son), "isFullDay" to f.tumGun,
            "category" to f.kategori, "status" to "SCHEDULED", "urgency" to "NONE",
            "doctorUserId" to f.hekimId,
            "procedureCustomLabel" to (if (f.kategori in islemKategorileri) dolu(f.islem) else null),
        )
        if (f.kategori in hastaKategorileri) {
            val h = f.hastaId
            if (h != null) {
                alanlar += "patientId" to h
            } else {
                alanlar += "guestFirstName" to dolu(f.ad)
                alanlar += "guestLastName" to dolu(f.soyad)
                alanlar += "guestPhone" to dolu(f.telefon)
                alanlar += "guestPhoneCountryCode" to "+90"
                alanlar += "hastaKaydiOlustur" to (hastaAcabilir && f.hastaKaydiOlustur)
            }
        }
        return nesne(*alanlar.toTypedArray())
    }

    // MARK: Çakışma ön denetimi (web `randevuyuKaydet`)

    /** Yalnız hekimli, tüm gün olmayan Hasta Randevu'da. */
    fun denetlenirMi(f: Form): Boolean = f.hekimId != null && !f.tumGun && f.kategori == "PATIENT_VISIT"

    @Serializable
    data class Cakisma(val startAt: String, val patient: Kisi? = null) {
        @Serializable
        data class Kisi(val firstName: String? = null, val lastName: String? = null)
    }

    fun cakismaMetni(c: List<Cakisma>, saatDilimi: ZoneId): String {
        val f = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("tr-TR")).withZone(saatDilimi)
        val adlar = c.map { k ->
            val saat = tarih(k.startAt)?.let { f.format(it) } ?: ""
            val ad = kirp(listOfNotNull(k.patient?.firstName, k.patient?.lastName).joinToString(" "))
            "$saat ${ad.ifEmpty { "(misafir)" }}"
        }
        return "Hekim çakışması: ${c.size} mevcut randevu (${adlar.joinToString(", ")}). Yine de kaydedilsin mi?"
    }

    enum class Sonuc { kaydedildi, vazgecildi }

    /** Kayıt yanıtının uyarıları (web `RandevuUyariPenceresi`): tanınan `WINDOW_*` kodu istemci sözlüğünden, değilse sunucunun `detail` cümlesi. */
    data class Uyari(
        val kind: String?,
        val severity: String? = "warn",
        val detail: String?,
        val vars: Map<String, String>? = null,
    ) {
        companion object {
            /**
             * Swift'in alan alan `try?` çözümüyle aynı: metin olmayan alan yok sayılır; `vars` içindeki değerlerden biri metin değilse
             * `vars` hiç yok sayılır (şablonda yer tutucu kalır → sunucunun cümlesi). Nesne olmayan öğe atlanır.
             */
            fun coz(el: JsonElement): Uyari? {
                val o = el as? JsonObject ?: return null
                val vars = (o["vars"] as? JsonObject)?.let { v ->
                    val m = v.mapValues { (_, d) -> (d as? JsonPrimitive)?.takeIf { it.isString }?.content }
                    if (m.values.any { it == null }) null else m.mapValues { it.value!! }
                }
                return Uyari(kind = o.str("kind"), severity = o.str("severity"), detail = o.str("detail"), vars = vars)
            }
        }
    }

    val uyariSablonlari: Map<String, String> = mapOf(
        "WINDOW_BRANCH_CLOSED" to "Şube o gün kapalı görünüyor (Ayarlar › Klinik Bilgileri › Çalışma Bilgileri).",
        "WINDOW_PERSON_CLOSED" to "{{person}} o gün çalışmıyor.",
        "WINDOW_OUTSIDE" to "Randevu, çalışma saatleri ({{windows}}) dışında.",
        "WINDOW_ON_LEAVE" to "{{person}} bu tarihte izinli.",
        "WINDOW_ON_BREAK" to "{{person}} o saatte molada ({{start}}–{{end}}).",
    )

    fun uyariMetni(u: Uyari): String {
        val k = u.kind ?: return u.detail ?: ""
        var s = uyariSablonlari[k] ?: return u.detail ?: ""
        for ((a, d) in u.vars ?: emptyMap()) s = s.replace("{{$a}}", d)
        // Değişken okunamadıysa (metin olmayan değer) sunucunun cümlesi — boş yer tutucu basılmaz.
        return if (s.contains("{{")) (u.detail ?: s) else s
    }

    /** Web `kayitSonrasiSonuc`: kayıttan önce onaylanmış hekim çakışması tekrar gösterilmez; uyarı yoksa pencere yok. */
    fun kayitSonrasiUyarilar(u: List<Uyari>, cakismaOnaylandi: Boolean): List<String> =
        u.filter { !(cakismaOnaylandi && it.kind == "DOCTOR_CONFLICT") }.map(::uyariMetni).filter { it.isNotEmpty() }

    /** Akış: denetim (gerekiyorsa) → çakışma varsa sor → hayırsa dur; denetim düşerse uyar ve YİNE kaydet. */
    suspend fun kaydetAkisi(
        f: Form,
        saatDilimi: ZoneId,
        denetle: suspend () -> List<Cakisma>,
        sor: suspend (String) -> Boolean,
        uyar: (String) -> Unit,
        kaydet: suspend () -> Unit,
    ): Sonuc {
        if (denetlenirMi(f)) {
            val c = try {
                denetle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uyar(denetimYapilamadi)
                null
            }
            if (c != null && c.isNotEmpty() && !sor(cakismaMetni(c, saatDilimi))) return Sonuc.vazgecildi
        }
        kaydet()
        return Sonuc.kaydedildi
    }

    // MARK: Yardımcılar

    private val Z_BICIMI: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    /** Sunucuya giden an — `Z` biçimi, milisaniyeli (Swift `ISO8601DateFormatter` `.withFractionalSeconds`). */
    fun zDamgasi(an: Instant): String = Z_BICIMI.format(an)

    /** ISO 8601 an (kesirli saniyeli ya da değil; `Z` ya da ofsetli). */
    fun tarih(s: String): Instant? =
        runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull() ?: runCatching { Instant.parse(s) }.getOrNull()

    /** Swift `trimmingCharacters(in: .whitespaces)` — boşluk ve sekme (satır sonu değil). */
    internal fun kirp(s: String): String = s.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
}

// MARK: - Uçlar (Swift `extension APIClient`)

@Serializable
private data class IslemYaniti(val items: List<YeniRandevu.IslemTuru>)

/** `POST /api/appointments/:id/hasta-kaydi` yanıtı. */
@Serializable
data class HastaKaydiYaniti(val patientId: String)

@Serializable
private data class RandevuYaniti(val warnings: List<JsonElement>? = null)

@Serializable
private data class CakismaYaniti(val conflicts: List<YeniRandevu.Cakisma>? = null)

@Serializable
private data class SubeAyari(val branch: Sube? = null) {
    @Serializable
    data class Sube(val slotMinutes: Int? = null)
}

suspend fun ApiClient.randevuIslemleri(): List<YeniRandevu.IslemTuru> = ayarListesi("APPOINTMENT_TYPE")

/** `GET /api/settings-collections?type=<tür>&active=true` (ad listesi — işlem türleri, başarısızlık nedenleri …). */
suspend fun ApiClient.ayarListesi(tur: String): List<YeniRandevu.IslemTuru> =
    request<IslemYaniti>(path = "/settings-collections", query = listOf("type" to tur, "active" to "true")).items

/** `Idempotency-Key` aynı pencerede aynı kalır (yeniden denemede çift kayıt olmaz). */
suspend fun ApiClient.randevuOlustur(govde: JsonObject, anahtar: String): List<YeniRandevu.Uyari> =
    request<RandevuYaniti>(method = "POST", path = "/appointments", body = govde, headers = mapOf("Idempotency-Key" to anahtar))
        .warnings.orEmpty().mapNotNull { YeniRandevu.Uyari.coz(it) }

/** `POST /api/appointments/check-conflict` (`appt.view`). */
suspend fun ApiClient.randevuCakismasi(hekimId: String, baslangic: Instant, bitis: Instant): List<YeniRandevu.Cakisma> =
    request<CakismaYaniti>(
        method = "POST", path = "/appointments/check-conflict",
        body = nesne("doctorUserId" to hekimId, "startAt" to YeniRandevu.zDamgasi(baslangic), "endAt" to YeniRandevu.zDamgasi(bitis)),
    ).conflicts.orEmpty()

/** Şubenin dilim dakikası; okunamazsa 30. */
suspend fun ApiClient.subeDilimi(): Int {
    val y = try {
        request<SubeAyari>(path = "/branch-settings")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return YeniRandevu.dilim(y?.branch?.slotMinutes)
}

suspend fun ApiClient.hastaKaydiOlustur(randevuId: String): String =
    request<HastaKaydiYaniti>(method = "POST", path = "/appointments/$randevuId/hasta-kaydi", body = nesne()).patientId
