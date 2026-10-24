package com.dentifay.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-E1.7A mühürleri — hekim unvanı web `hekimAdi` ile BİREBİR, kural paketteki sunucu dosyasından (iOS `UnvanTests`). */
class UnvanTest {

    /** Web `hekim-unvani.test.js` ALTIN tablosu: girdi · unvansız ad · hekimAdi. */
    private val webAltin = listOf(
        Triple("Dr. Deneme Hekim", "Deneme Hekim", "Dt. Deneme Hekim"),
        Triple("Dr.Deneme", "Deneme", "Dt. Deneme"),
        Triple("dr deneme hekim", "deneme hekim", "Dt. deneme hekim"),
        Triple("  Dr.   Deneme  ", "Deneme", "Dt. Deneme"),
        Triple("Prof. Dr. Deneme Hekim", "Deneme Hekim", "Prof. Dr. Deneme Hekim"),
        Triple("Doç. Dr. Örnek Kişi", "Örnek Kişi", "Doç. Dr. Örnek Kişi"),
        Triple("Op. Dr. Örnek", "Örnek", "Op. Dr. Örnek"),
        Triple("Dr. Öğr. Üyesi Örnek Kişi", "Örnek Kişi", "Dr. Öğr. Üyesi Örnek Kişi"),
        Triple("Dr. Dt. Örnek", "Örnek", "Dr. Dt. Örnek"),
        Triple("Uzm. Dt. Örnek Kişi", "Örnek Kişi", "Uzm. Dt. Örnek Kişi"),
        Triple("Diş Hekimi Örnek", "Örnek", "Diş Hekimi Örnek"),
        Triple("Dt Örnek", "Örnek", "Dt. Örnek"),
        Triple("Dt. Örnek Kişi", "Örnek Kişi", "Dt. Örnek Kişi"),
        Triple("Örnek Kişi", "Örnek Kişi", "Dt. Örnek Kişi"),
        Triple("Drago Örnek", "Drago Örnek", "Dt. Drago Örnek"),
        Triple("Dr.", "", ""),
        Triple("", "", ""),
    )

    /** İş emrinin asgari listesi (web `hekimAdi` Node'da koşturularak ölçüldü). */
    private val isEmriAltin = listOf(
        "Ayşe Demir" to "Dt. Ayşe Demir",
        "Dr. Ayşe Demir" to "Dt. Ayşe Demir",
        "Dr Ayşe" to "Dt. Ayşe",
        "Dt. Ayşe" to "Dt. Ayşe",
        "Uzm. Dt. Ali Kaya" to "Uzm. Dt. Ali Kaya",
        "Uzm. Dr. Ali" to "Uzm. Dr. Ali",
        "Prof. Dr. Ayşe Demir" to "Prof. Dr. Ayşe Demir",
        "Doç. Dr. Ece" to "Doç. Dr. Ece",
        "Op. Dr. Can" to "Op. Dr. Can",
        "Dr. Öğr. Üyesi Selin" to "Dr. Öğr. Üyesi Selin",
        "Diş Hekimi Selin" to "Diş Hekimi Selin",
        "Drago Petrović" to "Dt. Drago Petrović",
        "" to "",
        "  ayşe  " to "Dt. ayşe",
        "DR. AYŞE" to "Dt. AYŞE",
        "DOÇ. DR. ECE" to "DOÇ. DR. ECE",
    )

    @Test
    fun altinTablo() {
        assertNotNull("kural dosyası okunamadı", Unvan.kural)
        assertEquals("Dt.", Unvan.kural?.unvan)
        for ((girdi, ad, hekim) in webAltin) {
            assertEquals("unvansizAd($girdi)", ad, Unvan.unvansizAd(girdi))
            assertEquals("hekimAdi($girdi)", hekim, Unvan.hekimAdi(girdi))
        }
        for ((girdi, hekim) in isEmriAltin) assertEquals("hekimAdi($girdi)", hekim, Unvan.hekimAdi(girdi))
        val cifte = Regex("""^Dt\. (?:(?:prof|doç|op|dr|dt|uzm)[.\s]|diş hekimi)""", RegexOption.IGNORE_CASE)
        for (g in webAltin.map { it.first } + isEmriAltin.map { it.first }) {
            val h = Unvan.hekimAdi(g)
            assertTrue("çifte unvan: $h", cifte.find(h) == null)
        }
    }

    @Test
    fun bozukKuralAdiAynenDondurur() {
        val bozuklar = listOf(
            "{ bozuk",
            """{"unvan":"Dt."}""",
            """{"unvan":"Dt.","unvanDeseni":"^(("}""",
            """{"unvan":"Dt.","unvanDeseni":"^(dr\\.)","bayraklar":"iy"}""",
        )
        for (d in bozuklar) {
            val k = Unvan.Kural.coz(d)
            assertNull(d, k)
            assertEquals("Ayşe Demir", Unvan.hekimAdi("Ayşe Demir", k))
            assertEquals("Dr. Ayşe", Unvan.hekimAdi("Dr. Ayşe", k))
            assertEquals("", Unvan.hekimAdi(null, k))
        }
    }

    @Test
    fun hekimOlmayanAdAynen() {
        assertEquals("Deneme Kullanıcı", Unvan.oturumAdi("Deneme Kullanıcı", "RECEPTION"))
        assertEquals("Deneme Kullanıcı", Unvan.oturumAdi("Deneme Kullanıcı", "ADMIN"))
        assertEquals("Dr. Deneme", Unvan.oturumAdi("Dr. Deneme", "OWNER"))
        assertEquals("Dt. Deneme Hekim", Unvan.oturumAdi("Deneme Hekim", "DOCTOR"))
        assertEquals("Prof. Dr. Deneme", Unvan.oturumAdi("Prof. Dr. Deneme", "doctor"))
    }
}
