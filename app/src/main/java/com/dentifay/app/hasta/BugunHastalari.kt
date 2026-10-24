package com.dentifay.app.hasta

import com.dentifay.app.core.APIAppointment
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.Unvan
import com.dentifay.app.data.randevuTuruEtiketi
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bugünkü randevuların hastaları — Tedavi Planı sekmesi (M-E8.2) ve sesli tedavi planı modülünün (M-E8.5) TEK kuralı.
 * Önce kullanıcının KENDİ randevularındaki hastalar ("Benim hastalarım"), sonra öteki hekimlerinkiler; başka gün YOK.
 */
object BugunHastalari {

    /** Kuraldaki randevu girdisi (API modelinden bağımsız — test edilebilir). */
    data class Randevu(
        val id: String,
        val baslangic: Instant,
        val durum: String?,
        val kategori: String?,
        val hastaId: String?,
        val hastaAdi: String,
        val telefon: String?,
        val hekimId: String?,
        val hekimAdi: String?,
        val islem: String?,
    )

    /** Hasta kartı — hasta başına TEK kart (misafir randevunun hasta kaydı yok: randevu başına kart, sönük). */
    data class Kart(
        val id: String,
        val hastaId: String?,
        val hastaAdi: String,
        val telefon: String?,
        val saat: Instant,
        val hekimAdi: String?,
        val islem: String?,
    ) {
        val misafir: Boolean get() = hastaId == null
    }

    data class Bolumler(val benim: List<Kart>, val diger: List<Kart>) {
        val bos: Boolean get() = benim.isEmpty() && diger.isEmpty()
    }

    /** Swift `(bas: Date, son: Date)`. */
    data class GunAraligi(val bas: Instant, val son: Instant)

    /** Sayılmayan durumlar: iptal edilmiş ve ertelenmiş (randevu bugün gerçekleşmeyecek). */
    val sayilmayanDurumlar: Set<String> = setOf("CANCELLED", "RESCHEDULED")

    /** Gün aralığı: başlangıç = oturum saat diliminde bugünün 00:00'ı; bitiş = yarının 00:00'ından 1 ms önce (sunucu `lte`). */
    fun gunAraligi(simdi: Instant, saatDilimi: String?): GunAraligi {
        val dilim = saatDilimi?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: runCatching { ZoneId.of("Europe/Istanbul") }.getOrNull()
            ?: ZoneId.systemDefault()
        val bas = simdi.atZone(dilim).toLocalDate().atStartOfDay(dilim)
        val yarin = bas.plusDays(1)
        return GunAraligi(bas.toInstant(), yarin.toInstant().minusMillis(1))
    }

    private val zBicimi: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    /** UTC `Z` zaman damgası, milisaniyeli ("2026-10-23T21:00:00.000Z") — `+` karakteri gönderilmez. */
    fun zDamgasi(tarih: Instant): String = zBicimi.format(tarih)

    /**
     * İki bölüm. "Benim" = bugün kullanıcının KENDİ (`hekimId == kullaniciId`) en az bir randevusu olan hastalar; kart o hastanın
     * kullanıcıyla İLK randevusunu gösterir. "Diğer" = geri kalanlar; kart ilk randevuyu gösterir. Bölüm içinde ilk saat sırası.
     */
    fun bolumle(randevular: List<Randevu>, kullaniciId: String?): Bolumler {
        val sayilan = randevular
            .filter { (it.durum ?: "") !in sayilmayanDurumlar }
            .filter { (it.kategori ?: "PATIENT_VISIT") == "PATIENT_VISIT" }
            .sortedBy { it.baslangic }

        val benim = mutableListOf<Kart>()
        val diger = mutableListOf<Kart>()
        val gorulen = HashSet<String>()
        val hastaGruplari = sayilan.filter { it.hastaId != null }.groupBy { it.hastaId ?: "" }

        for (r in sayilan) {
            val hastaId = r.hastaId
            if (hastaId == null) {
                // Misafir: hasta kaydı yok — randevu başına kart.
                val kart = kart(r, "misafir-${r.id}")
                if (kullaniciId != null && r.hekimId == kullaniciId) benim += kart else diger += kart
                continue
            }
            if (!gorulen.add(hastaId)) continue
            val hepsi = hastaGruplari[hastaId] ?: listOf(r)
            val kendi = kullaniciId?.let { k -> hepsi.firstOrNull { it.hekimId == k } }
            if (kendi != null) benim += kart(kendi, hastaId) else diger += kart(r, hastaId)
        }
        return Bolumler(benim = benim.sortedBy { it.saat }, diger = diger.sortedBy { it.saat })
    }

    private fun kart(r: Randevu, id: String): Kart =
        Kart(
            id = id, hastaId = r.hastaId, hastaAdi = r.hastaAdi, telefon = r.telefon, saat = r.baslangic,
            hekimAdi = r.hekimAdi?.let { Unvan.hekimAdi(it) }, islem = r.islem,
        )

    /** API modelinden kural girdisine. */
    fun randevu(a: APIAppointment): Randevu? {
        val bas = tarih(a.startAt) ?: return null
        val p = a.patient
        val ad = if (p != null) "${p.firstName} ${p.lastName}".trim(' ', '\t')
        else listOfNotNull(a.guestFirstName, a.guestLastName).joinToString(" ")
        return Randevu(
            id = a.id, baslangic = bas, durum = a.status, kategori = a.category, hastaId = a.patientId ?: p?.id,
            hastaAdi = ad.ifEmpty { "Misafir" }, telefon = p?.phone ?: a.guestPhone,
            hekimId = a.doctorUserId, hekimAdi = a.doctor?.name, islem = a.appointmentType?.let(::randevuTuruEtiketi),
        )
    }

    /** ISO 8601 an (kesirli saniyeli ya da değil; `Z` ya da ±hh:mm) — Swift `ISO8601DateFormatter` `.withInternetDateTime`. */
    fun tarih(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

    /** Bugünkü randevuları tek istekle getirir ve bölümler (`category=PATIENT_VISIT`, gün sınırı oturum saat dilimiyle). */
    suspend fun yukle(kullaniciId: String?, saatDilimi: String?, simdi: Instant = Instant.now()): Bolumler {
        val gun = gunAraligi(simdi, saatDilimi)
        val liste = ApiClient.fetchAppointments(
            startDate = zDamgasi(gun.bas), endDate = zDamgasi(gun.son), category = "PATIENT_VISIT",
        )
        return bolumle(liste.mapNotNull { randevu(it) }, kullaniciId)
    }
}
