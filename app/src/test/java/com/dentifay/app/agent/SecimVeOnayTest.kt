package com.dentifay.app.agent

import com.dentifay.app.core.DfyJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** iOS `SecimVeOnayTests` (M-E1.4 mühürleri) — seçim kartları, "Listede yok", Türkçe onay ve deterministik onay. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecimVeOnayTest {

    @Before fun kur() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun boz() = Dispatchers.resetMain()

    private fun obj(s: String): Map<String, JSONValue> = requireNotNull(JSONValue.parse(s)?.objectValue) { "JSON nesnesi değil: $s" }

    /** Kural 1: başlık etiketlerden; etiketsizde Ek MO.11a; `treatment_items`/`insert` hiçbir çıktıda yok. */
    @Test
    fun onayBasligi() {
        val etiketli = AgentWritePreview.parse(
            obj("""{"operation":"insert","entity":"treatment_items","affectedCount":2,"entityLabel":"Tedavi kalemi","operationLabel":"Ekleme"}"""),
        )
        assertEquals("Ekleme · Tedavi kalemi · 2 kayıt", OnayBilgisi.kur(etiketli).baslik)
        val etiketsiz = AgentWritePreview.parse(obj("""{"operation":"insert","entity":"treatment_items","affectedCount":1}"""))
        val b = OnayBilgisi.kur(etiketsiz)
        assertEquals("Kayıt · 1 kayıt", b.baslik)
        for (o in listOf(OnayBilgisi.kur(etiketli), b)) {
            val hepsi = (listOf(o.baslik, o.ozet ?: "", o.kalanSatiri ?: "", o.klinik ?: "") + o.satirlar).joinToString(" ")
            assertFalse(hepsi.contains("treatment_items"))
            assertFalse(hepsi.contains("insert"))
        }
    }

    /** Kural 2: değişiklik satırı biçimi (eski dolu / boş). */
    @Test
    fun degisiklikSatiri() {
        assertEquals("Aşama: Planlandı → Yapıldı", Degisiklik(kayit = null, alanAdi = "Aşama", eski = "Planlandı", yeni = "Yapıldı").satir)
        assertEquals("Hekim: Dt. A", Degisiklik(kayit = null, alanAdi = "Hekim", eski = null, yeni = "Dt. A").satir)
    }

    /** Kural 3: `AgentChoiceSet.parse` — `elleSecim`, `priceListId`; 0 aday + `elleSecim` → kart var. */
    @Test
    fun secimKartiCozumu() {
        val set = AgentChoiceSet.parse(
            obj("""{"type":"entity_choices","table":"service_catalog","term":"ozon","choices":[],"priceListId":"m1","elleSecim":true}"""),
        )
        assertTrue(set.elleSecim)
        assertEquals("m1", set.priceListId)
        assertTrue(set.cizilir)
        assertTrue(set.listedeYokVar)
        val elsiz = AgentChoiceSet.parse(obj("""{"table":"patients","term":"ali","choices":[]}"""))
        assertFalse(elsiz.cizilir)
        assertFalse(elsiz.listedeYokVar)
        // Akış: olay mesaja bağlanır (0 adaylı kart dahil).
        var msg = AgentMessage(role = AgentRole.assistant)
        if (set.cizilir) msg = msg.copy(choiceSet = set)
        assertNotNull(msg.choiceSet)
    }

    /** Kural 4: menüden seçimin `pick`i — `service_catalog`, `term` = olayın term'i (ad DEĞİL). */
    @Test
    fun menuPick() {
        val kart = AgentChoiceSet(table = "service_catalog", term = "ozon", priceListId = "m1", elleSecim = true, choices = emptyList())
        val oge = MenuSecimYaniti.Oge(id = "s9", name = "Ozon Tedavisi", grup = null, price = 50.0, currency = "USD", priceReason = null)
        val p = MenuSecimYaniti.pick(oge, kart)
        assertEquals("service_catalog", p.table)
        assertEquals("ozon", p.term)
        assertEquals("s9", p.id)
        assertEquals("Ozon Tedavisi", p.name)
    }

    /** Kural 5: `/secim` yanıt çözümü — `truncated`, `priceReason`, USD satırı. */
    @Test
    fun secimYaniti() {
        val r = DfyJson.decodeFromString(
            MenuSecimYaniti.serializer(),
            """
            {"items":[{"id":"a","name":"Dolgu","grup":"Restoratif","price":1800,"currency":"TRY"},
                      {"id":"b","name":"Ozon","grup":null,"price":200,"currency":"USD"},
                      {"id":"c","name":"Plak","price":null,"currency":"EUR","priceReason":"KUR_YOK"}],
             "truncated":true,"menu":{"id":"m1","name":"Varsayılan"}}
            """,
        )
        assertEquals(true, r.truncated)
        assertEquals("200,00 USD", r.items[1].fiyatMetni)
        assertEquals("Fiyat şu an hesaplanamıyor", r.items[2].fiyatMetni)
        assertTrue(r.items[0].fiyatMetni, r.items[0].fiyatMetni.contains("1.800,00"))
    }

    /** Kural 6: kimliksiz öğe → `pick` üretilmez, metin gönderilmez. */
    @Test
    fun kimliksizOge() {
        val c = requireNotNull(AgentChoice.parse(obj("""{"name":"Deneme Hasta","phone":"0555","tc":"123"}"""), "patients", "deneme"))
        assertNull(c.pick)
        assertEquals("0555 · TC 123", c.subtitle)
        val vm = AgentSessionManager().apply { ornekVeri = true }
        assertFalse(vm.sendChoice(c))
        assertTrue("kimliksiz kart hiçbir mesaj üretmemeli", vm.messages.isEmpty())
        vm.dispose()
    }

    /** Kural 7: `changesKalan: 3` → Ek MO.11d satırı; `kayit` öneki. */
    @Test
    fun kalanVeKayitOneki() {
        val p = AgentWritePreview.parse(
            obj("""{"entityLabel":"Tedavi kalemi","operationLabel":"Güncelleme","changes":[{"kayit":"Dolgu (36)","alan":"stage","alanAdi":"Aşama","eski":"Planlandı","yeni":"Yapıldı"}],"changesKalan":3}"""),
        )
        val b = OnayBilgisi.kur(p)
        assertEquals(listOf("Dolgu (36) · Aşama: Planlandı → Yapıldı"), b.satirlar)
        assertEquals("3 değişiklik daha", b.kalanSatiri)
    }

    /** Kural 8: onay/vazgeç `onay.islem` + `onizlemeId` taşır; kimlik ya da sohbet yoksa `onay` yok; süresi geçmişte onay YOK. */
    @Test
    fun deterministikOnay() {
        val simdi = Instant.ofEpochSecond(1_800_000_000)
        val p = AgentWritePreview.parse(obj("""{"onizlemeId":"o1"}""")).copy(gecerlilikSonu = simdi.plusSeconds(60))

        val onayla = requireNotNull(OnayIstegi.kur(OnayIstegi.Islem.onayla, p, cift = false, conversationId = "c1", simdi = simdi))
        assertEquals("Onayla", onayla.metin)
        assertEquals(AgentClient.OnayPayload(islem = "onayla", onizlemeId = "o1"), onayla.onay)
        val vazgec = requireNotNull(OnayIstegi.kur(OnayIstegi.Islem.vazgec, p, cift = false, conversationId = "c1", simdi = simdi))
        assertEquals("vazgec", vazgec.onay?.islem)
        assertNull(OnayIstegi.kur(OnayIstegi.Islem.onayla, p, cift = false, conversationId = null, simdi = simdi)?.onay)
        val kimliksiz = AgentWritePreview.parse(obj("{}"))
        assertNull(OnayIstegi.kur(OnayIstegi.Islem.onayla, kimliksiz, cift = false, conversationId = "c1", simdi = simdi)?.onay)
        assertEquals("Evet, kalıcı olarak onaylıyorum.", OnayIstegi.kur(OnayIstegi.Islem.onayla, p, cift = true, conversationId = "c1", simdi = simdi)?.metin)

        // Gövde: conversationId yoksa `onay` anahtarı hiç yok.
        val govde = AgentClient.ChatRequest(message = "Onayla", conversationId = null, pick = null, moduleScope = null, onay = onayla.onay).toJson()
        assertNull(govde["onay"])
        val govde2 = AgentClient.ChatRequest(message = "Onayla", conversationId = "c1", pick = null, moduleScope = null, onay = onayla.onay).toJson()
        assertEquals("o1", (govde2["onay"] as JsonObject)["onizlemeId"]?.jsonPrimitive?.content)

        // Süresi geçmiş önizlemede onay isteği ÜRETİLMEZ.
        assertNull(OnayIstegi.kur(OnayIstegi.Islem.onayla, p, cift = false, conversationId = "c1", simdi = simdi.plusSeconds(120)))
        assertTrue(OnayIstegi.suresiDoldu(p, simdi.plusSeconds(60)))
        assertFalse(OnayIstegi.suresiDoldu(p, simdi))
    }

    /** Süresi geçmiş önizlemede `confirmWrite` hiçbir tur başlatmaz; vazgeç yine gider. */
    @Test
    fun suresiGecmisOnayGonderilmez() {
        var cagri = 0
        val vm = AgentSessionManager { _, _, _, _, _, _ ->
            cagri += 1
            kotlinx.coroutines.flow.emptyFlow()
        }.apply { ornekVeri = true }
        vm.conversationId = "c1"
        val p = AgentWritePreview.parse(obj("""{"onizlemeId":"o1","gecerlilikSonu":"2020-01-01T00:00:00Z"}"""))
        vm.messages = listOf(AgentMessage(role = AgentRole.assistant, content = "önizleme", writePreview = p))
        assertFalse(vm.confirmWrite(doubleConfirm = false))
        assertEquals(0, cagri)
        vm.cancelWrite()
        assertEquals(1, cagri)
        assertEquals(AgentClient.OnayPayload("vazgec", "o1"), vm.lastOnay)
        vm.dispose()
    }
}
