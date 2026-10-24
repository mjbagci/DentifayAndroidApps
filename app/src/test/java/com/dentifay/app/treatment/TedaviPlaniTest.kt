package com.dentifay.app.treatment

import com.dentifay.app.agent.AgentChoiceSet
import com.dentifay.app.agent.AgentClient
import com.dentifay.app.agent.AgentMessage
import com.dentifay.app.agent.AgentRole
import com.dentifay.app.agent.AgentSessionManager
import com.dentifay.app.agent.AgentWritePreview
import com.dentifay.app.agent.AgentWriteResult
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.serverCode
import com.dentifay.app.treatment.TreatmentPlanViewModel.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** iOS `TedaviPlaniTests` (M-E1.2 mühürleri) — plan kartları, mesaj biçimi, faz kararı, iptal ve bekçi kuralları. */
@OptIn(ExperimentalCoroutinesApi::class)
class TedaviPlaniTest {

    @Before fun kur() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun boz() = Dispatchers.resetMain()

    private fun plan(
        id: String,
        kapali: Boolean = false,
        sistem: Boolean = false,
        kalemler: List<PlanKarti.IlkKalem> = emptyList(),
    ) = PlanKarti(
        id = id, title = "Plan $id", status = if (kapali) "COMPLETED" else "IN_PROGRESS", doctor = null,
        kalemSayisi = kalemler.size, ilkKalemler = kalemler, sistemPlani = sistem, kapali = kapali, onayBekliyor = false,
    )

    private fun asistan(
        content: String = "",
        writeResult: AgentWriteResult? = null,
        isError: Boolean = false,
        errorCode: String? = null,
        choiceSet: AgentChoiceSet? = null,
        softFailCode: String? = null,
    ) = AgentMessage(
        role = AgentRole.assistant, content = content, writeResult = writeResult, isError = isError,
        errorCode = errorCode, choiceSet = choiceSet, softFailCode = softFailCode,
    )

    private val onizleme = AgentWritePreview(operation = null, entity = null, risk = null, affectedCount = null, requiresDoubleConfirm = false)

    private fun tur(
        mesaj: AgentMessage,
        onizleme: AgentWritePreview? = null,
        onceki: AgentWritePreview? = null,
        iptal: Boolean = false,
        hata: String? = null,
    ): Phase = TedaviPlaniFazi.turSonu(mesaj, onizleme, onceki, iptal, hata)

    /** Kural 1: kapalı/sistem → dokunulamaz; sıra açık → kapalı → sistem; ilk kalem satırı ad + etiket (boş etiket ek yapmaz). */
    @Test
    fun planKartiModeli() {
        assertTrue(plan("1").dokunulabilir)
        assertFalse(plan("2", kapali = true).dokunulabilir)
        assertFalse(plan("3", sistem = true).dokunulabilir)
        assertFalse("hekim onayı bekleyen plana yazılmaz", plan("4").copy(onayBekliyor = true).dokunulabilir)

        val sirali = PlanListesi.sirala(listOf(plan("s", sistem = true), plan("k", kapali = true), plan("a"), plan("b")))
        assertEquals(listOf("a", "b", "k", "s"), sirali.map { it.id })

        val p = plan(
            "x",
            kalemler = listOf(
                PlanKarti.IlkKalem("Dolgu", "36"), PlanKarti.IlkKalem("Detartraj", ""),
                PlanKarti.IlkKalem("Kaplama", null), PlanKarti.IlkKalem("Fazla", "11"),
            ),
        )
        assertEquals(listOf("Dolgu · 36", "Detartraj", "Kaplama"), p.ilkKalemSatirlari)

        // Sunucu yanıtı çözülür (sözleşme §6).
        val json = """{"plans":[{"id":"p1","title":"Plan 1","status":"DRAFT","doctor":null,"kalemSayisi":0,"ilkKalemler":[],"sistemPlani":false,"kapali":false}],"pagination":{"page":1,"limit":50,"total":3,"pages":1},"sonrakiPlanAdi":"Plan 2"}"""
        val liste = DfyJson.decodeFromString(PlanListesi.serializer(), json)
        assertEquals("Plan 2", liste.sonrakiPlanAdi)
        assertEquals("Taslak", liste.plans.first().durumEtiketi)
        assertEquals("İlk 1 plan gösteriliyor.", liste.kirpmaMetni)
        assertTrue(liste.plans.first().dokunulabilir)
    }

    /** Kart metinleri: başlık yedeği, durum rozeti, hekim adı kuralı (Ek MO.2/MO.3), kırpma yalnız eksikte. */
    @Test
    fun planKartiMetinleri() {
        assertEquals("Plan", plan("1").copy(title = "  ").baslik)
        assertEquals("Devam Ediyor", plan("1").durumEtiketi)
        assertEquals("Tamamlandı", plan("1", kapali = true).durumEtiketi)
        assertEquals("Plan", plan("1").copy(status = "BILINMEYEN").durumEtiketi)
        assertEquals("Hekim atanmadı", plan("1").hekimMetni)
        assertEquals("Dt. Deneme A", plan("1").copy(doctor = PlanKarti.Hekim("d1", "Deneme A")).hekimMetni)
        assertEquals("Prof. Dr. B", plan("1").copy(doctor = PlanKarti.Hekim("d2", "Prof. Dr. B")).hekimMetni)

        val tam = PlanListesi(plans = listOf(plan("1")), pagination = PlanListesi.Sayfalama(total = 1), sonrakiPlanAdi = "Plan 2")
        assertNull("sessiz kırpma yok, ama eksik de yoksa satır yok", tam.kirpmaMetni)
        assertNull(PlanListesi(plans = emptyList(), pagination = null, sonrakiPlanAdi = null).kirpmaMetni)
    }

    /** Kural 2: `ChatRequest` — `hedefPlanId` varken anahtar var, yokken yok. */
    @Test
    fun chatRequestHedefPlanId() {
        val var_ = AgentClient.ChatRequest(message = "m", conversationId = null, pick = null, moduleScope = null, hedefPlanId = "plan-1").toJson()
        assertEquals("plan-1", var_["hedefPlanId"]?.jsonPrimitive?.content)
        val yok = AgentClient.ChatRequest(message = "m", conversationId = null, pick = null, moduleScope = null).toJson()
        assertNull(yok["hedefPlanId"])
    }

    /** Kural 3: ilk mesaj yalnız `«ad» için: "metin"`; istemci talimatı yok. */
    @Test
    fun ilkMesajBicimi() {
        val m = TedaviPlaniMesaji.ilk("Deneme Hasta", "36'ya dolgu")
        assertEquals("«Deneme Hasta» için: \"36'ya dolgu\"", m)
        for (yasak in listOf("clarify", "fiyat", "plan ad", "kaydet")) {
            assertFalse("mesaj '$yasak' taşımamalı", m.lowercase().contains(yasak))
        }
        // Sözleşme §6: ad içindeki «» atılır, metindeki " ' olur.
        assertEquals("«Deneme Hasta» için: \"adı 'Gülüş' olsun\"", TedaviPlaniMesaji.ilk("«Deneme» Hasta", "adı \"Gülüş\" olsun"))
    }

    /** Kural 4: cevap OLDUĞU GİBİ gider (plan adı dalı yok). Akış ağsız sahte kaynaktan gelir. */
    @Test
    fun cevapAynenGider() {
        val agent = AgentSessionManager { _, _, _, _, _, _ -> emptyFlow() }.apply { ornekVeri = true }
        val vm = TreatmentPlanViewModel(agent = agent)
        vm.answerDraft = "36"
        vm.submitAnswer()
        assertEquals("36", vm.agent.messages.firstOrNull { it.role == AgentRole.user }?.content)
        assertEquals("", vm.answerDraft)
        vm.dispose()
    }

    /** Kural 5: `cancelled` + iptal bekleniyor → kayıt; beklenmiyorken → hata dalı. */
    @Test
    fun turSonuIptal() {
        val msg = asistan(content = "İşlem iptal edildi.", writeResult = AgentWriteResult.cancelled)
        assertEquals(Phase.recording, tur(msg, iptal = true))
        assertEquals(Phase.failed("İşlem iptal edildi."), tur(msg, iptal = false))
        // İptal beklenirken sonuçsuz biten tur da kayda döner (bekleyen yazma sunucuda kalmadı).
        assertEquals(Phase.recording, tur(asistan(content = "Tamam."), iptal = true))
    }

    /** Kural 6: 400 `E_TARGET_PLAN_INVALID` ve 409 `E_PLAN_CLOSED` → plan listesi (+ yenileme). */
    @Test
    fun hedefPlanHatalari() {
        for ((kod, yedek) in listOf(
            "E_TARGET_PLAN_INVALID" to "Seçilen plan kullanılamıyor; liste yenilendi.",
            "E_PLAN_CLOSED" to "Bu plan kapanmış; yeni tedavi eklenemez.",
        )) {
            assertEquals(Phase.plans(yedek), tur(asistan(isError = true, errorCode = kod), hata = "x"))
            assertEquals(
                Phase.plans("Sunucunun cümlesi."),
                tur(asistan(content = "Sunucunun cümlesi.", isError = true, errorCode = kod)),
            )
        }
        assertEquals("E_PLAN_CLOSED", serverCode("""{"error":"Plan kapandı.","code":"E_PLAN_CLOSED"}""".toByteArray()))
    }

    /** Faz kararının sırası: yazma sonucu → önizleme → seçim kartı → gerçek hata → soru (M-E1.2.11). */
    @Test
    fun turSonuSirasi() {
        assertEquals(Phase.done("Plan kaydedildi."), tur(asistan("Plan kaydedildi.", writeResult = AgentWriteResult.committed)))
        assertEquals(Phase.failed("İşlem tamamlanamadı."), tur(asistan(writeResult = AgentWriteResult.rejected)))
        assertEquals(Phase.review, tur(asistan("Önizleme hazır."), onizleme = onizleme))

        val bos = AgentChoiceSet(table = "service_catalog", term = "ozon", priceListId = "menu-1", elleSecim = true, choices = emptyList())
        assertEquals(Phase.choosing(bos, AgentChoiceSet.soruYedegi), tur(asistan(choiceSet = bos)))
        assertEquals(Phase.choosing(bos, "Hangisi?"), tur(asistan("  Hangisi?  ", choiceSet = bos)))
        val cizilmez = bos.copy(elleSecim = false)
        assertEquals("çizilmeyen kart soruya düşer", Phase.asking("Hangisi?"), tur(asistan("Hangisi?", choiceSet = cizilmez)))

        assertEquals(Phase.failed("Bağlantı koptu."), tur(asistan("Soru gibi metin"), hata = "Bağlantı koptu."))
        assertEquals(Phase.failed("Bir hata oluştu."), tur(asistan("Bir hata oluştu.", isError = true)))
        assertEquals(Phase.failed("Bu isteği şu an tamamlayamadım."), tur(asistan(softFailCode = "SOFT_FAIL")))

        assertEquals(Phase.asking("Hangi hekime atayalım?"), tur(asistan(" Hangi hekime atayalım? ")))
        assertEquals(Phase.failed(TedaviPlaniFazi.bosYanit), tur(asistan()))
        assertEquals("boş düzeltme turu önceki önizlemeyi geri getirir", Phase.review, tur(asistan(), onceki = onizleme))
    }

    /** Kural 7: bekçi `submitting`te kurulmaz ve o fazda kapatma pasiftir. */
    @Test
    fun bekciVeKapatma() {
        assertFalse(TedaviPlaniFazi.bekciKurulur(Phase.submitting))
        assertFalse(TedaviPlaniFazi.kapatmaSerbest(Phase.submitting))
        assertTrue(TedaviPlaniFazi.bekciKurulur(Phase.thinking))
        assertTrue(TedaviPlaniFazi.kapatmaSerbest(Phase.thinking))
        assertFalse(TedaviPlaniFazi.bekciKurulur(Phase.review))
        assertTrue(TedaviPlaniFazi.bekciKurulur(Phase.cancelling))
        assertTrue(TedaviPlaniFazi.kapatmaSerbest(Phase.failed("x")))
    }

    /** Dikte alana EKLER — mikrofona yeniden basmak yazılanı ezmez (M-E1.2.6). */
    @Test
    fun dikteAlanaEkler() {
        assertEquals("36 dolgu", TreatmentPlanViewModel.dikteyiEkle("", "36 dolgu"))
        assertEquals("36 dolgu", TreatmentPlanViewModel.dikteyiEkle("36 dolgu", ""))
        assertEquals("36 dolgu 46 kanal", TreatmentPlanViewModel.dikteyiEkle("36 dolgu", "46 kanal"))
        assertEquals("36 dolgu, 46 kanal", TreatmentPlanViewModel.dikteyiEkle("36 dolgu, ", "46 kanal"))
    }
}
