package com.dentifay.app.home

import com.dentifay.app.core.DfyJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** iOS `MobilEkleme9Tests.test2_CakismaOnDenetimi` + yerli yeni randevunun saf kuralları (M-E8.13 · M-E9.3). */
class YeniRandevuTest {

    private class Kayit {
        val sorular = mutableListOf<String>()
        val uyarilar = mutableListOf<String>()
        var kayit = 0
    }

    private suspend fun kos(
        form: YeniRandevu.Form,
        denetim: Result<List<YeniRandevu.Cakisma>>,
        cevap: Boolean,
    ): Pair<YeniRandevu.Sonuc, Kayit> {
        val k = Kayit()
        var denetlendi = false
        val s = YeniRandevu.kaydetAkisi(
            form, ZoneOffset.UTC,
            denetle = { denetlendi = true; denetim.getOrThrow() },
            sor = { k.sorular += it; cevap },
            uyar = { k.uyarilar += it },
            kaydet = { k.kayit += 1 },
        )
        if (!YeniRandevu.denetlenirMi(form)) assertFalse("denetlenmemeliydi", denetlendi)
        return s to k
    }

    /**
     * Yeni randevuda çakışma ön denetimi: çakışma → soru (web cümlesi) → "Yine de kaydet" kaydeder / vazgeç kaydetmez; çakışma
     * yoksa soru yok; denetim düşerse uyarı + yine kayıt; yalnız hekimli, tüm gün olmayan Hasta Randevu'da denetlenir.
     */
    @Test
    fun cakismaOnDenetimi() = runTest {
        val f = YeniRandevu.Form(hekimId = "dtA")
        val cakisma = listOf(
            YeniRandevu.Cakisma("2026-10-24T07:00:00.000Z", YeniRandevu.Cakisma.Kisi(firstName = "Örnek", lastName = "Kişi")),
            YeniRandevu.Cakisma("2026-10-24T07:30:00.000Z", null),
        )

        val (evet, k1) = kos(f, Result.success(cakisma), cevap = true)
        assertEquals(YeniRandevu.Sonuc.kaydedildi, evet)
        assertEquals(1, k1.kayit)
        assertEquals(
            listOf("Hekim çakışması: 2 mevcut randevu (07:00 Örnek Kişi, 07:30 (misafir)). Yine de kaydedilsin mi?"),
            k1.sorular,
        )

        val (hayir, k2) = kos(f, Result.success(cakisma), cevap = false)
        assertEquals(YeniRandevu.Sonuc.vazgecildi, hayir)
        assertEquals("hayır → kayıt YOK", 0, k2.kayit)

        val (_, k3) = kos(f, Result.success(emptyList()), cevap = false)
        assertTrue(k3.sorular.isEmpty())
        assertEquals("çakışma yok → sorusuz kayıt", 1, k3.kayit)

        val (_, k4) = kos(f, Result.failure(java.net.SocketTimeoutException("zaman aşımı")), cevap = false)
        assertEquals(listOf(YeniRandevu.denetimYapilamadi), k4.uyarilar)
        assertEquals("denetim düştü → uyarı + yine kayıt", 1, k4.kayit)

        for (x in listOf(f.copy(tumGun = true), f.copy(kategori = "BREAK"), f.copy(hekimId = null))) {
            val (s, k) = kos(x, Result.success(cakisma), cevap = false)
            assertEquals(YeniRandevu.Sonuc.kaydedildi, s)
            assertTrue(k.sorular.isEmpty())
        }
    }

    /** Gövde web `POST /api/appointments` ile aynı: hasta alanları yalnız hasta türlerinde, işlem yalnız işlem türlerinde, `Z` + ms. */
    @Test
    fun govde() {
        val bas = Instant.parse("2026-10-24T07:00:00Z")
        val misafir = YeniRandevu.Form(
            ad = " Deneme ", soyad = "", telefon = "0555 000 00 00", hastaKaydiOlustur = true, hekimId = "dtA",
            baslangic = bas, bitis = bas.plusSeconds(1800), islem = "Muayene",
        )
        val g = YeniRandevu.govde(misafir, ZoneOffset.UTC, hastaAcabilir = true)
        assertEquals(JsonPrimitive("2026-10-24T07:00:00.000Z"), g["startAt"])
        assertEquals(JsonPrimitive("2026-10-24T07:30:00.000Z"), g["endAt"])
        assertEquals(JsonPrimitive(false), g["isFullDay"])
        assertEquals(JsonPrimitive("PATIENT_VISIT"), g["category"])
        assertEquals(JsonPrimitive("SCHEDULED"), g["status"])
        assertEquals(JsonPrimitive("NONE"), g["urgency"])
        assertEquals(JsonPrimitive("dtA"), g["doctorUserId"])
        assertEquals(JsonPrimitive("Muayene"), g["procedureCustomLabel"])
        assertEquals(JsonPrimitive("Deneme"), g["guestFirstName"])
        assertNull("boş alan gönderilmez", g["guestLastName"])
        assertEquals(JsonPrimitive("+90"), g["guestPhoneCountryCode"])
        assertEquals(JsonPrimitive(true), g["hastaKaydiOlustur"])
        assertNull(g["patientId"])
        // Yetkisi yoksa kutu işaretli kalsa da `false`.
        assertEquals(JsonPrimitive(false), YeniRandevu.govde(misafir, ZoneOffset.UTC, hastaAcabilir = false)["hastaKaydiOlustur"])

        val kayitli = YeniRandevu.govde(misafir.copy(hastaId = "p1"), ZoneOffset.UTC, hastaAcabilir = true)
        assertEquals(JsonPrimitive("p1"), kayitli["patientId"])
        assertNull(kayitli["guestFirstName"])
        assertNull(kayitli["hastaKaydiOlustur"])

        // Mola: hasta ve işlem alanı yok; hekimsiz → `doctorUserId` yok.
        val mola = YeniRandevu.govde(misafir.copy(kategori = "BREAK", hekimId = null), ZoneOffset.UTC, hastaAcabilir = true)
        assertNull(mola["procedureCustomLabel"])
        assertNull(mola["guestFirstName"])
        assertNull(mola["doctorUserId"])
        // Laboratuvar: işlem var, hasta yok.
        val lab = YeniRandevu.govde(misafir.copy(kategori = "LAB"), ZoneOffset.UTC, hastaAcabilir = true)
        assertEquals(JsonPrimitive("Muayene"), lab["procedureCustomLabel"])
        assertNull(lab["guestFirstName"])
    }

    /** Tüm Gün: kliniğin duvar saatiyle 00:00–23:59 (İstanbul gününün sınırı `Z` ile). */
    @Test
    fun tumGunAraligi() {
        val f = YeniRandevu.Form(tumGun = true, baslangic = Instant.parse("2026-10-24T10:00:00Z"), bitis = Instant.parse("2026-10-24T10:30:00Z"))
        val ist = ZoneId.of("Europe/Istanbul")
        val (bas, son) = YeniRandevu.aralik(f, ist)
        assertEquals("2026-10-23T21:00:00.000Z", YeniRandevu.zDamgasi(bas))
        assertEquals("2026-10-24T20:59:00.000Z", YeniRandevu.zDamgasi(son))
        assertEquals(JsonPrimitive(true), YeniRandevu.govde(f, ist, hastaAcabilir = false)["isFullDay"])
        // Tüm gün değilse seçilen aralık aynen.
        assertEquals(f.baslangic to f.bitis, YeniRandevu.aralik(f.copy(tumGun = false), ist))
    }

    /** İşlem varsayılanı (DentAIWeb 52A): ayarlardaki varsayılan → Muayene (`systemKey`) → boş; dilim kümesi dışında 30. */
    @Test
    fun varsayilanlar() {
        val muayene = YeniRandevu.IslemTuru(id = "1", name = "Muayene ", systemKey = "muayene")
        val kanal = YeniRandevu.IslemTuru(id = "2", name = "Kanal Tedavisi", isDefault = true)
        val pasif = YeniRandevu.IslemTuru(id = "3", name = "Eski", isActive = false, isDefault = true)
        assertEquals("Kanal Tedavisi", YeniRandevu.varsayilanIslem(listOf(muayene, pasif, kanal)))
        assertEquals("Muayene", YeniRandevu.varsayilanIslem(listOf(pasif, muayene)))
        assertEquals("", YeniRandevu.varsayilanIslem(listOf(YeniRandevu.IslemTuru(id = "4", name = "Kontrol"))))
        assertEquals("Muayene", YeniRandevu.yedekIslemler.first())
        assertEquals(9, YeniRandevu.kategoriler.size)

        assertEquals(45, YeniRandevu.dilim(45))
        assertEquals(30, YeniRandevu.dilim(7))
        assertEquals(30, YeniRandevu.dilim(null))
    }

    /** Kayıt sonrası uyarılar (web `RandevuUyariPenceresi`): şablon + değişkenler; okunamayan değişkende sunucunun cümlesi. */
    @Test
    fun kayitSonrasiUyarilar() {
        val yanit = DfyJson.parseToJsonElement(
            """{"warnings":[
                {"kind":"WINDOW_ON_BREAK","severity":"warn","detail":"x","vars":{"person":"Dt. A","start":"12:00","end":"13:00"}},
                {"kind":"WINDOW_PERSON_CLOSED","detail":"Hekim o gün çalışmıyor.","vars":{"person":5}},
                {"kind":"DOCTOR_CONFLICT","detail":"Hekim çakışması var."},
                {"kind":"BILINMEYEN","detail":"Sunucunun cümlesi."},
                "bozuk"
            ]}""",
        ).jsonObject["warnings"]!!.jsonArray
        val uyarilar = yanit.mapNotNull { YeniRandevu.Uyari.coz(it) }
        assertEquals(4, uyarilar.size)
        assertEquals("Dt. A o saatte molada (12:00–13:00).", YeniRandevu.uyariMetni(uyarilar[0]))
        assertNull("metin olmayan değişken → vars yok sayılır", uyarilar[1].vars)
        assertEquals("Hekim o gün çalışmıyor.", YeniRandevu.uyariMetni(uyarilar[1]))
        assertEquals("Sunucunun cümlesi.", YeniRandevu.uyariMetni(uyarilar[3]))

        // Kayıttan önce onaylanan hekim çakışması tekrar gösterilmez.
        assertEquals(4, YeniRandevu.kayitSonrasiUyarilar(uyarilar, cakismaOnaylandi = false).size)
        val onayli = YeniRandevu.kayitSonrasiUyarilar(uyarilar, cakismaOnaylandi = true)
        assertEquals(3, onayli.size)
        assertFalse(onayli.contains("Hekim çakışması var."))
        assertTrue(YeniRandevu.kayitSonrasiUyarilar(emptyList(), cakismaOnaylandi = false).isEmpty())
    }
}
