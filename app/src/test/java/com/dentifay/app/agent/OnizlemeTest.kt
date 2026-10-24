package com.dentifay.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS `OnizlemeTests` (M-E1.3 mühürleri) — çekirdek dışı kısımlar: düzeltme numarası, tutar sunucudan, klinik, hekim
 * düzeltmesi. (FDI ve hekim kümesi `core` testlerindedir.)
 */
class OnizlemeTest {

    private fun json(s: String): Map<String, JSONValue> = requireNotNull(JSONValue.parse(s)?.objectValue) { "JSON nesnesi değil: $s" }

    /** Kural 2: numara sunucunun `index`inden — plan adımı 0 olan önizlemede ilk kalem "2. adım"; konumsuzda konum eki yok. */
    @Test
    fun duzeltmeNumarasiSunucudan() {
        val p = AgentWritePreview.parse(
            json(
                """
                {"steps":[
                  {"index":0,"entity":"treatment_plans","operation":"insert","title":"Plan 2","fields":{}},
                  {"index":1,"entity":"treatment_items","operation":"insert","title":"Dolgu",
                   "fields":{"tooth":{"raw":"36","label":"36"},"catalogServiceId":{"raw":"s1","label":"Kompozit Dolgu"}}},
                  {"index":2,"entity":"treatment_items","operation":"insert","title":"Detartraj",
                   "fields":{"catalogServiceId":{"raw":"s2","label":"Detartraj"}}}]}
                """,
            ),
        )
        val kalemler = p.steps.filter { it.entity == "treatment_items" }
        assertEquals("Önizlemedeki 2. adım («Kompozit Dolgu», 36)", DuzeltmeMesaji.tanim(kalemler[0]))
        assertEquals(
            "Önizlemedeki 2. adım («Kompozit Dolgu», 36): dişi 37 yap; diğer adımları aynen koru.",
            DuzeltmeMesaji.konum(kalemler[0], KonumSecimi.dis(listOf("37"))),
        )
        assertEquals("Önizlemedeki 3. adım («Detartraj»)", DuzeltmeMesaji.tanim(kalemler[1]))
        assertEquals(
            "Önizlemedeki 3. adım («Detartraj»): konumu 2. bölge (Sol üst) yap; diğer adımları aynen koru.",
            DuzeltmeMesaji.konum(kalemler[1], KonumSecimi.bolge(2)),
        )
        // Ek MO.9h–j
        assertEquals("Önizlemedeki 3. adım («Detartraj»): tedaviyi «Polisaj» yap; diğer adımları aynen koru.", DuzeltmeMesaji.tedavi(kalemler[1], "Polisaj"))
        assertEquals("Önizlemedeki 3. adım («Detartraj»): listeden çıkar; diğer adımları aynen koru.", DuzeltmeMesaji.sil(kalemler[1]))
        assertEquals("Plana «Dolgu» kalemini ekle: konumu üst çene; mevcut adımları aynen koru.", DuzeltmeMesaji.ekle("Dolgu", KonumSecimi.ustCene))
        assertEquals("Plana «Dolgu» kalemini ekle; mevcut adımları aynen koru.", DuzeltmeMesaji.ekle("Dolgu", null))
    }

    /** Kural 3: kart fiyatı sunucunun etiketi ("200,00 USD", ₺ yok); TL toplam alanı yoksa toplam satırı yok. */
    @Test
    fun tutarSunucudan() {
        val p = AgentWritePreview.parse(
            json(
                """
                {"steps":[{"index":0,"entity":"treatment_items","operation":"insert","title":"Kanal",
                  "fields":{"price":{"raw":"200","label":"200,00 USD"},"quantity":{"raw":"2","label":"2"}}}]}
                """,
            ),
        )
        val adim = p.steps.first()
        assertEquals("200,00 USD", adim.fiyatEtiketi)
        assertFalse(adim.fiyatEtiketi?.contains("₺") ?: true)
        assertNull(adim.tutarTL)
        assertEquals(2, adim.quantity)
        assertNull("toplamTL yoksa toplam satırı çizilmez", p.toplamTL)
        val ileToplam = AgentWritePreview.parse(json("""{"toplamTL":1800,"steps":[{"index":0,"entity":"treatment_items","tutarTL":1800,"fields":{}}]}"""))
        assertEquals(1800.0, ileToplam.toplamTL!!, 0.0)
        assertEquals(1800.0, ileToplam.steps.first().tutarTL!!, 0.0)
        val kursuz = AgentWritePreview.parse(json("""{"toplamTL":null,"steps":[]}"""))
        assertNull(kursuz.toplamTL)
    }

    /** Kural 4: `klinik.ad` çözülür. */
    @Test
    fun klinikAdi() {
        assertEquals("Deneme Klinik", AgentWritePreview.parse(json("""{"klinik":{"ad":"Deneme Klinik"}}""")).klinikAdi)
        assertNull(AgentWritePreview.parse(json("{}")).klinikAdi)
    }

    /** Kural 5: hekim düzeltmesi `pick{table:"users", id:userId}`; planın hekimi kalıbı "kalem" taşımaz. */
    @Test
    fun hekimDuzeltmesi() {
        val pick = DuzeltmeMesaji.hekimPick(userId = "u-42", ad = "Dt. B")
        assertEquals("users", pick.table)
        assertEquals("u-42", pick.id)
        assertEquals("Dt. B", pick.term)
        val m = DuzeltmeMesaji.planHekimi("Dt. B")
        assertEquals("Planın hekimini Dt. B yap.", m)
        assertFalse(m.lowercase().contains("kalem"))
    }

    /** Kalemin hekimi sunucunun etiketinden ve hekim unvanı kuralıyla (kişinin kendi unvanı korunur); konum etiketi sırası. */
    @Test
    fun hekimVeKonumMetni() {
        fun adim(fields: String) = requireNotNull(AgentWriteStep.parse(json("""{"index":1,"entity":"treatment_items","fields":$fields}""")))
        assertEquals("Dt. Deneme A", adim("""{"assignedDoctorId":{"raw":"d1","label":"Deneme A"}}""").hekimMetni)
        assertEquals("Prof. Dr. B", adim("""{"assignedDoctorId":{"raw":"d2","label":"Prof. Dr. B"}}""").hekimMetni)
        assertNull(adim("{}").hekimMetni)
        assertEquals("Dt. Deneme A", adim("""{"doctorUserId":{"raw":"d1","label":"Deneme A"}}""").planHekimiMetni)
        assertEquals("Üst çene", adim("""{"jawType":{"raw":"UPPER","label":"Üst çene"}}""").konumMetni)
        assertEquals(
            "36, 37",
            requireNotNull(AgentWriteStep.parse(json("""{"index":1,"entity":"treatment_items","toothCodes":["36","37"],"fields":{}}"""))).konumMetni,
        )
        // Etiketsiz kimlik ekrana basılmaz.
        assertEquals("(ilgili kayıt)", adim("""{"catalogServiceId":{"raw":"cm1abc2def3ghi"}}""").fields["catalogServiceId"]?.text)
        assertTrue(AgentFieldValue.looksLikeId("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(AgentFieldValue.looksLikeId("36"))
        assertEquals("Sağ alt", KonumSecimi.bolgeAdi(4))
    }
}
