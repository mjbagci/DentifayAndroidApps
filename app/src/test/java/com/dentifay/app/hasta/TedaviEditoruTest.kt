package com.dentifay.app.hasta

import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.EsnekSayi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan penceresinin saf kuralları (iOS `TedaviEditoru` — M-E8.4): seçici araması, gruplar, satırlar, istek gövdeleri. */
class TedaviEditoruTest {

    private fun k(id: String, ad: String, grup: String? = null, kod: String? = null, fiyat: Double? = null) =
        KatalogKalemi(id = id, name = ad, group = grup, tarifeKodu = kod, price = fiyat?.let { EsnekSayi(it) })

    private fun kalem(json: String): TedaviKalemi = DfyJson.decodeFromString(json)

    private fun JsonObject.metin(a: String): String? = (this[a] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test
    fun katlamaVeArama() {
        assertEquals("cgiosu istanbul isik", TedaviEditoru.katla("ÇĞIÖŞÜ İSTANBUL Işık"))
        val katalog = listOf(
            k("1", "Kompozit Dolgu", "Restoratif", "401.010"),
            k("2", "Kanal Tedavisi (Tek Kanal)", "Endodonti", "402.020"),
            k("3", "Zirkonyum Kuron", "Protez"),
            k("4", "Diş Çekimi", "Cerrahi"),
        )
        assertEquals(listOf("4"), TedaviEditoru.ara(katalog, "dis cek").sonuclar.map { it.id })
        assertEquals("tarife kodunda", listOf("2"), TedaviEditoru.ara(katalog, "402").sonuclar.map { it.id })
        assertEquals("grup adında", listOf("3"), TedaviEditoru.ara(katalog, "PROTEZ").sonuclar.map { it.id })
        assertEquals("her parça geçmeli", emptyList<String>(), TedaviEditoru.ara(katalog, "kanal kuron").sonuclar.map { it.id })
        assertEquals(TedaviEditoru.AramaSonucu(emptyList(), false), TedaviEditoru.ara(katalog, "   "))
        val cok = (1..31).map { k("$it", "Dolgu $it") }
        val sonuc = TedaviEditoru.ara(cok, "dolgu")
        assertEquals(TedaviEditoru.AZAMI_SONUC, sonuc.sonuclar.size)
        assertTrue(sonuc.dahaVar)
        assertFalse(TedaviEditoru.ara(cok.take(30), "dolgu").dahaVar)
    }

    @Test
    fun gruplarDigerEnSonda() {
        val g = TedaviEditoru.gruplar(
            listOf(k("1", "Zirkon", "Protez"), k("2", "Amalgam", null), k("3", "Çekim", "Cerrahi"), k("4", "Abutment", "Protez"), k("5", "X", "")),
        )
        assertEquals(listOf("Cerrahi", "Protez", "Diğer"), g.map { it.ad })
        assertEquals(listOf("Abutment", "Zirkon"), g[1].kalemler.map { it.name })
        assertEquals(listOf("2", "5"), g[2].kalemler.map { it.id }.sorted())
    }

    @Test
    fun secimdenSatirlar() {
        val kat = k("c", "Dolgu", "Restoratif", fiyat = 1500.0)
        fun s(disler: List<String>, ceneler: List<String> = emptyList(), yarim: List<Int> = emptyList(), kip: TedaviEditoru.DisKipi = TedaviEditoru.DisKipi.teker) =
            TedaviEditoru.satirlar(kat, "Dolgu", 1500.0, disler, ceneler, yarim, kip, "dtA")

        assertEquals("konumsuz → 1 satır", 1, s(emptyList()).size)
        assertEquals("", s(emptyList())[0].konumEtiketi)
        assertEquals(listOf(listOf("36")), s(listOf("36")).map { it.disler })
        assertEquals(listOf(listOf("36"), listOf("37")), s(listOf("36", "37")).map { it.disler })
        val toplu = s(listOf("36", "37"), kip = TedaviEditoru.DisKipi.toplu)
        assertEquals(1, toplu.size)
        assertTrue(toplu[0].toplu)
        assertEquals("36, 37", toplu[0].konumEtiketi)
        val cene = s(emptyList(), ceneler = listOf("LOWER", "UPPER"), yarim = listOf(2))
        assertEquals(listOf("Alt çene", "Üst çene", "Sol üst yarım çene"), cene.map { it.konumEtiketi })
        assertTrue("her satır ayrı kimlik", cene.map { it.id }.toSet().size == 3)
        assertFalse(cene[0].manuel)
        assertTrue(TedaviEditoru.satirlar(null, "Özel", 750.0, emptyList(), emptyList(), emptyList(), TedaviEditoru.DisKipi.teker, "dtA")[0].manuel)
    }

    @Test
    fun varOlanKalemdenSatir() {
        val tek = TedaviEditoru.satir(
            kalem("""{"id":"a","name":"Dolgu","stage":"TODO","tooth":"36","price":"1500.00","quantity":2,"discount":100,"currency":"USD","exchangeRate":34.5,"assignedDoctor":{"id":"dtA","name":"A"},"catalogServiceId":"c1"}"""),
        )
        assertEquals("a", tek.kalemId)
        assertEquals("c1", tek.katalogId)
        assertEquals(listOf("36"), tek.disler)
        assertEquals(1500.0, tek.fiyat, 0.0)
        assertEquals(2, tek.adet)
        assertEquals(100.0, tek.indirim, 0.0)
        assertEquals("USD", tek.para)
        assertEquals("34.5", tek.kur)
        assertEquals("dtA", tek.hekimId)
        assertEquals("TODO", tek.asama)
        assertFalse(tek.yapilan)
        assertEquals(IslemKurallari.Satir(fiyat = 1500.0, adet = 2, kur = 34.5, indirim = 100.0), tek.indirimSatiri)

        // Grup satırı: adet 1, birim = (grup toplamı + grup indirimi) / diş sayısı; konum çocuklardan.
        val grup = TedaviEditoru.satir(
            kalem(
                """{"id":"g","name":"Kuron","stage":"DONE","grupToplami":5400,"grupIndirimi":600,"quantity":3,"children":[
                {"id":"c1","name":"Kuron","stage":"DONE","tooth":"11"},{"id":"c2","name":"Kuron","stage":"DONE","tooth":"12"},
                {"id":"c3","name":"Kuron","stage":"DONE","tooth":"21"}]}""",
            ),
        )
        assertEquals(1, grup.adet)
        assertEquals(2000.0, grup.fiyat, 0.0)
        assertEquals(600.0, grup.indirim, 0.0)
        assertEquals(listOf("11", "12", "21"), grup.disler)
        assertTrue(grup.toplu)
        assertTrue(grup.yapilan)

        val yarim = TedaviEditoru.satir(kalem("""{"id":"q","name":"İmplant","stage":"PLANNED","jawType":"lower"}"""))
        assertEquals("yarım/tam çenenin dişleri konum olur", 16, yarim.disler.size)
        assertNull(yarim.cene)
    }

    @Test
    fun istekGovdeleri() {
        val katalogSatiri = TedaviEditoru.Satir(katalogId = "c1", ad = "Dolgu", disler = listOf("36"), fiyat = 1500.0, hekimId = "dtA", adet = 2)
        val p = TedaviEditoru.planKalemi(katalogSatiri, "p1", 150.0)
        assertEquals("p1", p.metin("treatmentPlanId"))
        assertEquals("36", p.metin("tooth"))
        assertNull("dokunulmamış katalog satırında fiyat GİTMEZ", p["price"])
        assertNull(p["toothCodes"])
        assertEquals(JsonNull, p["quadrant"])
        assertEquals(JsonNull, p["jawType"])
        assertEquals("TRY", p.metin("currency"))
        assertNull(p["exchangeRate"])
        assertEquals("PLANNED", p.metin("stage"))
        assertEquals(2, p["quantity"]?.jsonPrimitive?.content?.toInt())
        assertEquals(150.0, p["discount"]?.jsonPrimitive?.content?.toDouble())

        val toplu = TedaviEditoru.Satir(ad = "Özel", disler = listOf("14", "15"), fiyat = 900.0, manuel = true, adet = 4, para = "USD", kur = "34.5")
        val t = TedaviEditoru.planKalemi(toplu, "p1", 0.0)
        assertEquals(JsonNull, t["tooth"])
        assertEquals(listOf("14", "15"), t["toothCodes"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("toplu satır adet 1", "1", t["quantity"]?.jsonPrimitive?.content)
        assertEquals(900.0, t["price"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("USD", t.metin("currency"))
        assertEquals("34.5", t.metin("exchangeRate"))
        assertNull("katalogsuz satırda alan yok", t["catalogServiceId"])

        val d = TedaviEditoru.dogrudanKalem(TedaviEditoru.Satir(ad = "Detartraj", yarimCene = 3, fiyat = 0.0), "h1", "DONE", "2026-10-24", 0.0)
        assertEquals("h1", d.metin("patientId"))
        assertEquals("DONE", d.metin("stage"))
        assertEquals("2026-10-24", d.metin("treatmentDate"))
        assertEquals("doğrudan kalemde boş katalog açık null", JsonNull, d["catalogServiceId"])
        assertEquals(JsonNull, d["assignedDoctorId"])
        assertEquals("3", d["quadrant"]?.jsonPrimitive?.content)

        val e = TedaviEditoru.eklenen(katalogSatiri, 10.0)
        assertNull(e["treatmentPlanId"])
        assertNull(e["stage"])
        assertEquals("c1", e.metin("catalogServiceId"))

        val degisen = TedaviEditoru.degisen(katalogSatiri.copy(kalemId = "k1"), 25.0, grup = false)
        assertEquals(setOf("id", "assignedDoctorId", "quantity", "discount"), degisen.keys)
        val grupDegisen = TedaviEditoru.degisen(katalogSatiri.copy(kalemId = "k1", hekimId = null), 25.0, grup = true)
        assertEquals("grup satırında adet/indirim gönderilmez; boş hekim gönderilmez", setOf("id"), grupDegisen.keys)
    }
}
