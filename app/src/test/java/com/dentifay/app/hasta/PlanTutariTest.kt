package com.dentifay.app.hasta

import com.dentifay.app.core.DfyJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Plan satırı tutarları (web `treatment-pricing.js` ikizi), `Para.tl` ve `Tarih` biçimleri. */
class PlanTutariTest {

    private fun kalem(json: String): TedaviKalemi = DfyJson.decodeFromString(json)

    @Test
    fun kalemSatirToplami() {
        assertEquals("sunucunun TL toplamı", 1800.0, PlanTutari.kalemSatirToplami(kalem("""{"id":"a","name":"x","totalAmountTRY":"1800.00","price":1,"quantity":1}""")), 0.0)
        assertEquals(
            "TL toplamı 0 → fiyat × adet × kur − indirim",
            6850.0,
            PlanTutari.kalemSatirToplami(kalem("""{"id":"a","name":"x","totalAmountTRY":0,"price":100,"quantity":2,"exchangeRate":34.5,"discount":50}""")),
            0.0,
        )
        assertEquals("sıfırın altına inmez", 0.0, PlanTutari.kalemSatirToplami(kalem("""{"id":"a","name":"x","price":100,"discount":500}""")), 0.0)
        assertEquals("kuruşa yuvarlanır (yarıda yukarı)", 33.13, PlanTutari.kalemSatirToplami(kalem("""{"id":"a","name":"x","price":33.125}""")), 0.0)
    }

    @Test
    fun satirTutariVeIndirimi() {
        val grup = kalem(
            """{"id":"g","name":"Kuron","teeth":[{"toothCode":"11"},{"toothCode":"12"}],"children":[
            {"id":"c1","name":"Kuron","tooth":"11","price":6000,"totalAmountTRY":6000,"discount":0},
            {"id":"c2","name":"Kuron","tooth":"12","price":6000,"discount":600,"totalAmountTRY":5400},
            {"id":"c3","name":"Kuron","tooth":"21","price":6000,"totalAmountTRY":6000,"deletedAt":"2026-10-01T00:00:00Z","discount":100}]}""",
        )
        assertEquals("canlı çocukların toplamı", 11400.0, PlanTutari.satirTutari(grup)!!, 0.0)
        assertEquals(600.0, PlanTutari.satirIndirimi(grup), 0.0)
        val sunucu = kalem("""{"id":"g","name":"Kuron","grupToplami":"9000.50","grupIndirimi":"99.50","children":[{"id":"c1","name":"Kuron","totalAmountTRY":1}]}""")
        assertEquals(9000.5, PlanTutari.satirTutari(sunucu)!!, 0.0)
        assertEquals(99.5, PlanTutari.satirIndirimi(sunucu), 0.0)
        assertNull(
            "çok dişli, fiyatsız, çocuksuz satır → tutar yok (₺0 DEĞİL)",
            PlanTutari.satirTutari(kalem("""{"id":"x","name":"Köprü","teeth":[{"toothCode":"11"},{"toothCode":"12"}],"price":0}""")),
        )
        assertEquals("—", Para.tl(PlanTutari.satirTutari(kalem("""{"id":"x","name":"Köprü","teeth":[{"toothCode":"11"},{"toothCode":"12"}]}"""))))
        assertEquals(250.0, PlanTutari.satirTutari(kalem("""{"id":"x","name":"Köprü","teeth":[{"toothCode":"11"},{"toothCode":"12"}],"price":250}"""))!!, 0.0)
        assertEquals(40.0, PlanTutari.satirIndirimi(kalem("""{"id":"x","name":"x","discount":"40.00"}""")), 0.0)
    }

    @Test
    fun paraBicimi() {
        assertEquals("₺1.234,50", Para.tl(1234.5))
        assertEquals("₺0,00", Para.tl(0.0))
        assertEquals("—", Para.tl(null))
        assertEquals("—", Para.tl(Double.NaN))
    }

    @Test
    fun tarihBicimleri() {
        val onceki = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"))
            assertEquals("24 Eki 2026", Tarih.plan("2026-10-24"))
            assertEquals("24 Eki 26", Tarih.asama("2026-10-23T21:30:00.000Z"))
            assertEquals("6 Ağustos 2026", Tarih.medya("2026-08-06T09:00:00Z"))
            assertEquals("—", Tarih.plan(null))
            assertEquals("—", Tarih.plan("geçersiz"))
            assertEquals(LocalDate.of(2026, 10, 24).atStartOfDay(ZoneId.of("Europe/Istanbul")).toInstant(), Tarih.tarih("2026-10-24T00:00:00"))
        } finally {
            TimeZone.setDefault(onceki)
        }
    }
}
