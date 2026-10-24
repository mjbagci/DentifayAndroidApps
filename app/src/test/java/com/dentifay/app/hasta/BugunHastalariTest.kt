package com.dentifay.app.hasta

import com.dentifay.app.core.APIAppointment
import com.dentifay.app.core.APIDoctorBrief
import com.dentifay.app.core.APIPatientBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** MOBIL_IS_EMRI M-E8.7 — iOS `MobilEkleme8Tests.test1_BugunHastalariBolumleme` + kural ayrıntıları. */
class BugunHastalariTest {

    private fun r(
        id: String,
        saat: String,
        hasta: String?,
        hekim: String,
        durum: String = "SCHEDULED",
        kategori: String = "PATIENT_VISIT",
    ) = BugunHastalari.Randevu(
        id = id, baslangic = Instant.parse("2026-10-24T$saat:00Z"), durum = durum, kategori = kategori, hastaId = hasta,
        hastaAdi = hasta?.let { "Deneme $it" } ?: "Misafir", telefon = null, hekimId = hekim, hekimAdi = null, islem = null,
    )

    private val liste = listOf(
        r("1", "11:00", hasta = "B", hekim = "dtB"),
        r("2", "09:00", hasta = "A", hekim = "dtA"),
        r("3", "08:00", hasta = "C", hekim = "dtA", durum = "CANCELLED"),
        r("4", "07:00", hasta = "D", hekim = "dtA", kategori = "MEETING"),
        r("5", "10:00", hasta = "B", hekim = "dtA"),          // B'nin kendi randevusu → "Benim"e, tek kart
        r("6", "08:30", hasta = "E", hekim = "dtB"),
        r("7", "12:00", hasta = "F", hekim = "dtB", durum = "RESCHEDULED"),
    )

    /** 1. Kendi → öteki, "Benim" yoksa boş, saat sırası, iptal/ertelenen ve hasta dışı yok, hasta başına tek kart. */
    @Test
    fun bolumleme() {
        val b = BugunHastalari.bolumle(liste, kullaniciId = "dtA")
        assertEquals("kendi hastaları, saat sırası", listOf("A", "B"), b.benim.map { it.hastaId })
        assertEquals(
            "kendi randevusu (10:00) gösterilir, öteki hekiminki (11:00) değil",
            Instant.parse("2026-10-24T10:00:00Z"), b.benim.last().saat,
        )
        assertEquals("B ikinci kez yok; iptal, ertelenen ve hasta dışı sayılmaz", listOf("E"), b.diger.map { it.hastaId })

        val yalnizOteki = BugunHastalari.bolumle(liste, kullaniciId = "dtX")
        assertTrue("\"Benim\" yoksa bölüm çizilmez", yalnizOteki.benim.isEmpty())
        assertEquals(listOf("E", "A", "B"), yalnizOteki.diger.map { it.hastaId })

        val oturumsuz = BugunHastalari.bolumle(liste, kullaniciId = null)
        assertTrue(oturumsuz.benim.isEmpty())
        assertEquals(listOf("E", "A", "B"), oturumsuz.diger.map { it.hastaId })
        assertTrue(BugunHastalari.bolumle(emptyList(), "dtA").bos)
    }

    /** Gün sınırı oturumun saat dilimiyle, `Z` biçimi: İstanbul 24 Ekim 01:30 (= 23 Ekim 22:30Z) → 23 Ekim 21:00Z … 24 Ekim 20:59:59.999Z. */
    @Test
    fun gunSiniri() {
        val simdi = Instant.parse("2026-10-23T22:30:00Z")
        val gun = BugunHastalari.gunAraligi(simdi, "Europe/Istanbul")
        assertEquals("2026-10-23T21:00:00.000Z", BugunHastalari.zDamgasi(gun.bas))
        assertEquals("2026-10-24T20:59:59.999Z", BugunHastalari.zDamgasi(gun.son))
        assertTrue("+ karakteri gönderilmez", '+' !in BugunHastalari.zDamgasi(gun.bas))
        // Saat dilimi yok ya da tanınmıyor → İstanbul.
        assertEquals(gun, BugunHastalari.gunAraligi(simdi, null))
        assertEquals(gun, BugunHastalari.gunAraligi(simdi, "Gecersiz/Dilim"))
        // Başka dilim: UTC'de aynı an 23 Ekim'dir.
        val utc = BugunHastalari.gunAraligi(simdi, "UTC")
        assertEquals("2026-10-23T00:00:00.000Z", BugunHastalari.zDamgasi(utc.bas))
    }

    /** Misafir randevu: hasta kaydı yok → randevu başına sönük kart; kategorisiz randevu hasta randevusu sayılır; hekim adı `Unvan` kuralıyla. */
    @Test
    fun misafirVeHekimAdi() {
        val misafir = BugunHastalari.Randevu(
            id = "g1", baslangic = Instant.parse("2026-10-24T09:00:00Z"), durum = "SCHEDULED", kategori = null, hastaId = null,
            hastaAdi = "Misafir", telefon = null, hekimId = "dtA", hekimAdi = "Selin Demir", islem = "Muayene",
        )
        val b = BugunHastalari.bolumle(listOf(misafir, misafir.copy(id = "g2", hekimId = "dtB")), kullaniciId = "dtA")
        assertEquals(listOf("misafir-g1"), b.benim.map { it.id })
        assertTrue(b.benim.single().misafir)
        assertEquals(listOf("misafir-g2"), b.diger.map { it.id })
        assertEquals("Dt. Selin Demir", b.benim.single().hekimAdi)
    }

    @Test
    fun apiRandevusundan() {
        val a = APIAppointment(
            id = "r1", patientId = null, doctorUserId = "dtA", startAt = "2026-10-24T09:00:00.000Z", endAt = "2026-10-24T09:30:00.000Z",
            status = "SCHEDULED", patient = APIPatientBrief(id = "p1", firstName = "Ayşe", lastName = "Deneme", phone = "05550000000"),
            doctor = APIDoctorBrief(id = "dtA", name = "Selin Demir"), procedureLabel = "Kontrol", category = "PATIENT_VISIT",
        )
        val r = BugunHastalari.randevu(a)
        assertEquals("p1", r?.hastaId)
        assertEquals("Ayşe Deneme", r?.hastaAdi)
        assertEquals("05550000000", r?.telefon)
        assertEquals("Kontrol", r?.islem)
        assertEquals(Instant.parse("2026-10-24T09:00:00Z"), r?.baslangic)

        val misafir = BugunHastalari.randevu(a.copy(patient = null, guestFirstName = "Örnek", guestLastName = "Kişi", guestPhone = "0444"))
        assertNull(misafir?.hastaId)
        assertEquals("Örnek Kişi", misafir?.hastaAdi)
        assertEquals("0444", misafir?.telefon)
        assertEquals("Misafir", BugunHastalari.randevu(a.copy(patient = null))?.hastaAdi)
        assertNull("okunamayan an → randevu düşer", BugunHastalari.randevu(a.copy(startAt = "bozuk")))
    }

    @Test
    fun isoAn() {
        assertEquals(Instant.parse("2026-10-24T06:00:00Z"), BugunHastalari.tarih("2026-10-24T09:00:00+03:00"))
        assertEquals(Instant.parse("2026-10-24T09:00:00.250Z"), BugunHastalari.tarih("2026-10-24T09:00:00.250Z"))
        assertEquals(Instant.parse("2026-10-24T09:00:00Z"), BugunHastalari.tarih("2026-10-24T09:00:00Z"))
        assertNull(BugunHastalari.tarih("2026-10-24"))
    }
}
