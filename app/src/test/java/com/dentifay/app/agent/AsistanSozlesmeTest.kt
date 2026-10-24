package com.dentifay.app.agent

import com.dentifay.app.core.Yetki
import com.dentifay.app.core.hataCumlesi
import com.dentifay.app.core.serverCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Asistan sözleşmesinin pakete düşen mühürleri: iOS `HazirlikTests` (adım çözümü), `SesSokumuTests` (`mode` yok, `ozet`),
 * `TedaviPlaniTests` 2 + 6 (gövde `hedefPlanId`, hata kodu), `MobilEkleme7Tests` 2 (kodsuz 403 = yetki reddi),
 * `UnvanTests` 3 (asistan metnine unvan kuralı uygulanmaz).
 */
class AsistanSozlesmeTest {

    private fun json(s: String): JSONValue = requireNotNull(JSONValue.parse(s)) { "JSON çözülemedi: $s" }

    /** HazirlikTests 1: `AgentWriteStep.parse` sözleşmedeki örnek `steps[]` öğesini çözer. */
    @Test
    fun adimCozumu() {
        val step = requireNotNull(
            json(
                """
                { "index": 1, "entity": "treatment_items", "operation": "create", "title": "Dolgu",
                  "entityLabel": "Tedavi kalemi", "operationLabel": "Ekleme",
                  "fields": { "price": { "raw": "1800", "label": "1.800,00 TL", "alanAdi": "Fiyat" },
                              "tooth": { "raw": "36", "label": "36", "alanAdi": "Diş" } },
                  "tutarTL": 1800, "toothCodes": ["36"], "linkedToStep": 0 }
                """,
            ).objectValue?.let { AgentWriteStep.parse(it) },
        )
        assertEquals(1, step.index)
        assertEquals("treatment_items", step.entity)
        assertEquals("create", step.operation)
        assertEquals("Dolgu", step.title)
        assertEquals(0, step.linkedToStep)
        assertEquals("1.800,00 TL", step.fields["price"]?.text)
        assertEquals("36", step.fields["tooth"]?.text)
        assertEquals(listOf("36"), step.toothCodes)
    }

    /** SesSokumuTests 1: `ChatRequest` JSON'unda `mode` anahtarı hiçbir alan bileşiminde YOK. TedaviPlaniTests 2: `hedefPlanId`. */
    @Test
    fun sohbetGovdesi() {
        val pick = AgentClient.PickPayload(table = "patients", term = "Deneme Hasta", id = "p1", name = "Deneme Hasta")
        val bilesimler = listOf(
            AgentClient.ChatRequest(message = "merhaba", conversationId = null, pick = null, moduleScope = null),
            AgentClient.ChatRequest(message = "merhaba", conversationId = "c1", pick = pick, moduleScope = "mobil-tedavi-plani"),
            AgentClient.ChatRequest(message = "merhaba", conversationId = "c1", pick = null, moduleScope = null),
        )
        for (req in bilesimler) {
            val keys = req.toJson()
            assertNull("gövde `mode` taşımamalı: $keys", keys["mode"])
            assertNull("`patientPick` yolu kaldırıldı", keys["patientPick"])
            assertEquals("merhaba", keys["message"]?.jsonPrimitive?.content)
        }
        val ikinci = bilesimler[1].toJson()
        assertEquals("p1", (ikinci["pick"] as kotlinx.serialization.json.JsonObject)["id"]?.jsonPrimitive?.content)
        assertEquals("mobil-tedavi-plani", ikinci["moduleScope"]?.jsonPrimitive?.content)
        assertNull("boş alan gövdeye konmaz", bilesimler[0].toJson()["conversationId"])

        val var_ = AgentClient.ChatRequest(message = "m", conversationId = null, pick = null, moduleScope = null, hedefPlanId = "plan-1")
        assertEquals("plan-1", var_.toJson()["hedefPlanId"]?.jsonPrimitive?.content)
        val yok = AgentClient.ChatRequest(message = "m", conversationId = null, pick = null, moduleScope = null)
        assertNull(yok.toJson()["hedefPlanId"])
    }

    /** SesSokumuTests 2 + 3: `ozet` okunur (yoksa null); konuşma ayrıntısının `pendingWrite`ı aynı `parse`la çözülür. */
    @Test
    fun ozetVeBekleyenYazma() {
        val dolu = json("""{"type":"write_preview","operation":"create","entity":"treatment_items","ozet":"Deneme Hasta için 1 tedavi eklenecek. Onaylıyor musunuz?","steps":[]}""")
        assertEquals("Deneme Hasta için 1 tedavi eklenecek. Onaylıyor musunuz?", AgentWritePreview.parse(dolu.objectValue!!).ozet)
        assertNull(AgentWritePreview.parse(json("""{"type":"write_preview","operation":"create","entity":"treatment_items","ozet":null}""").objectValue!!).ozet)
        assertNull(AgentWritePreview.parse(json("""{"type":"write_preview","operation":"create"}""").objectValue!!).ozet)
        assertNull("boşluk ozet sayılmaz", AgentWritePreview.parse(json("""{"ozet":"   "}""").objectValue!!).ozet)

        val v = json(
            """
            { "id": "c1", "title": "Deneme", "messages": [
                { "id": "m1", "role": "user", "content": "36'ya dolgu" },
                { "id": "m2", "role": "assistant", "content": "Önizleme hazır." } ],
              "pendingWrite": { "operation": "create", "entity": "treatment_items",
                                "requiresDoubleConfirm": false, "ozet": "1 tedavi eklenecek." } }
            """,
        )
        val detail = AgentClient.ConversationDetail.parse(v)
        assertEquals(2, detail.messages.size)
        assertEquals("1 tedavi eklenecek.", detail.pendingWrite?.ozet)
        assertEquals(detail.pendingWrite, AgentWritePreview.parse(v.objectValue!!["pendingWrite"]!!.objectValue!!))
    }

    /** `hiddenStepCount`: COMMIT edilecek sayı gösterilenden büyükse fark açıkça söylenir (sessiz kırpma yok). */
    @Test
    fun gizliAdimSayisi() {
        val p = AgentWritePreview.parse(json("""{"affectedCount":5,"steps":[{"index":0,"entity":"treatment_items","fields":{}},{"index":1,"entity":"treatment_items","fields":{}}]}""").objectValue!!)
        assertEquals(3, p.hiddenStepCount)
        assertEquals(0, AgentWritePreview.parse(json("""{"affectedCount":5}""").objectValue!!).hiddenStepCount)
    }

    /** TedaviPlaniTests 6 (agent kısmı): `AgentError.http` kodu taşır; sunucu kodu gövdeden. MobilEkleme7Tests 2: kodsuz 403 yetki reddidir. */
    @Test
    fun hataKoduVeYetkiReddi() {
        assertEquals("E_PLAN_CLOSED", AgentClient.AgentError.http(409, "c", "E_PLAN_CLOSED").serverCode)
        assertEquals("E_PLAN_CLOSED", serverCode("""{"error":"Plan kapandı.","code":"E_PLAN_CLOSED"}""".toByteArray()))

        val kodsuz = """{"error":"Bu işlem için yetkiniz yok","capability":"fin.view","role":"BANKO"}""".toByteArray()
        val metin = hataCumlesi(403, kodsuz) ?: ""
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, metin)
        assertTrue(Yetki.reddiMi(AgentClient.AgentError.http(403, metin, null)))
        assertFalse("iş kodlu 403 yetki reddi sayılmaz", Yetki.reddiMi(AgentClient.AgentError.http(403, "Kendi limitinizi değiştiremezsiniz.", "E_CREDIT_LIMIT_SELF")))
        assertFalse(Yetki.reddiMi(AgentClient.AgentError.http(500, null, null)))
        assertFalse(Yetki.reddiMi(AgentClient.AgentError.invalidURL()))
        assertEquals("Bir hata oluştu.", AgentClient.AgentError.http(500, null).errorDescription)
    }

    /** UnvanTests 3: asistanın serbest metni DÖNÜŞTÜRÜLMEZ — markdown çizicisinde unvan kuralı yok, metin AYNEN. */
    @Test
    fun asistanMetniAynen() {
        val bloklar = MarkdownParser.parse("Dr. Deneme bugün 3 hastaya baktı.")
        assertEquals(listOf(MarkdownParser.Block.paragraph("Dr. Deneme bugün 3 hastaya baktı.")), bloklar)
        val kaynak = listOf("src/main/java/com/dentifay/app/agent/MarkdownMessageView.kt", "app/src/main/java/com/dentifay/app/agent/MarkdownMessageView.kt")
            .map(::File).firstOrNull { it.exists() }
        if (kaynak != null) assertFalse("asistan metnine unvan kuralı uygulanıyor", kaynak.readText().contains("Unvan."))
    }

    /** Markdown: tablo, başlık, madde ve çitli kod blokları ayrışır; satır içi biçim metni bozmaz. */
    @Test
    fun markdownBloklari() {
        val b = MarkdownParser.parse("## Başlık\n- bir\n- iki\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```\nSELECT 1;\n```\nSon **kalın** söz.")
        assertEquals(MarkdownParser.Block.header(2, "Başlık"), b[0])
        assertEquals(MarkdownParser.Block.bullets(listOf("bir", "iki")), b[1])
        assertEquals(MarkdownParser.Block.table(listOf("A", "B"), listOf(listOf("1", "2"))), b[2])
        assertEquals(MarkdownParser.Block.code("SELECT 1;"), b[3])
        assertEquals(MarkdownParser.Block.paragraph("Son **kalın** söz."), b[4])
        assertEquals("Son kalın söz.", MarkdownParser.inline("Son **kalın** söz.").text)
    }
}
