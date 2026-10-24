package com.dentifay.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZoneId

/** iOS `AsistanTests` (M-E1.5 mühürleri) — ilerleme rayı, yazma sonucu, sohbet geçmişi, 404, tekrar dene, bağlantı, öneri çipleri. */
@OptIn(ExperimentalCoroutinesApi::class)
class AsistanTest {

    @Before fun kur() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun boz() = Dispatchers.resetMain()

    private fun json(s: String): JSONValue = requireNotNull(JSONValue.parse(s)) { "JSON çözülemedi: $s" }

    /** Kural 1: ray yalnız `stage`ten; `liveText` = `text`; stage'siz adım ilerletmez; tablo adı ve `summary` metin üretmez. */
    @Test
    fun ilerlemeRayi() {
        val s = ProgressStep()
        ProgressMapper.apply(AgentProgress.reasoning("Soru inceleniyor", "analiz"), s)
        assertEquals(ProgressStepKind.understanding, s.step)
        assertEquals("Soru inceleniyor", s.liveText)
        ProgressMapper.apply(AgentProgress.reasoning("Kayıtlar okunuyor", "sorgu"), s)
        assertEquals(ProgressStepKind.retrieving, s.step)
        ProgressMapper.apply(AgentProgress.reasoning("Hesaplanıyor", "islem"), s)
        assertEquals(ProgressStepKind.computing, s.step)
        ProgressMapper.apply(AgentProgress.reasoning("Cevap yazılıyor…", "cevap"), s)
        assertEquals(ProgressStepKind.answering, s.step)
        assertEquals("Cevap yazılıyor…", s.liveText)

        val t = ProgressStep()
        ProgressMapper.apply(AgentProgress.reasoning("Sorgu sürdürülüyor", null), t)
        assertEquals("stage'siz thinking adımı İLERLETMEZ", ProgressStepKind.understanding, t.step)
        val once = t.liveText
        ProgressMapper.apply(AgentProgress.retrieved, t)
        assertEquals(once, t.liveText)
        assertFalse(t.liveText.contains("İlgili kayıtlar"))
        ProgressMapper.apply(AgentProgress.tool("sql_query", "5 paralel sorgu", true, null), t)
        assertEquals("summary tek başına metin ÜRETMEZ", once, t.liveText)
        ProgressMapper.apply(AgentProgress.tool("sql_query", "", true, 3), t)
        assertEquals("3 kayıt bulundu", t.liveText)

        val geri = ProgressStep()
        ProgressMapper.apply(AgentProgress.reasoning("x", "islem"), geri)
        ProgressMapper.apply(AgentProgress.reasoning("y", "analiz"), geri)
        assertEquals("ray ASLA geri gitmez", ProgressStepKind.computing, geri.step)

        // Ek MO.19a–c ve kuyruk metni.
        val u = ProgressStep()
        ProgressMapper.apply(AgentProgress.tool("sql_write", "", false, null), u)
        assertEquals("İşlem düzeltiliyor…", u.liveText)
        ProgressMapper.apply(AgentProgress.tool("sql_query", "", false, null), u)
        assertEquals("Sorgu düzeltiliyor…", u.liveText)
        ProgressMapper.apply(AgentProgress.tool("excel_export", "", true, 12), u)
        assertEquals("Excel raporu hazırlandı", u.liveText)
        ProgressMapper.apply(AgentProgress.queued(2), u)
        assertEquals("Sırada bekliyorsunuz — önünüzde 2 kişi var", u.liveText)
    }

    /** Kural 2: `rejected` Ek MO.17 ("Yetki" geçmez); `denied` "Yetki reddedildi"; tanınmayan `null`. */
    @Test
    fun yazmaSonucu() {
        assertEquals("İşlem doğrulanamadı; tekrar deneyin.", AgentWriteResult.rejected.label)
        assertFalse(AgentWriteResult.rejected.label.contains("Yetki"))
        assertEquals("Yetki reddedildi", AgentWriteResult.denied.label)
        assertNull(AgentWriteResult.from("not_applied"))
    }

    /** Kural 3: liste yalnız `{items, pagination}`; `lastMessageAt` okunur; kök dizi BOŞ liste. Ek MO.18a tarih biçimi. */
    @Test
    fun sohbetListesi() {
        val s = AgentClient.SohbetSayfasi.parse(
            json("""{"items":[{"id":"c1","title":"Ciro","lastMessageAt":"2026-10-21T09:10:00.000Z","createdAt":"2026-10-20T08:00:00Z"}],"pagination":{"page":1,"limit":25,"total":30,"pages":2}}"""),
        )
        assertEquals(listOf("c1"), s.items.map { it.id })
        assertNotNull(s.items.first().lastMessageAt)
        assertTrue(s.dahaVar)
        assertTrue(AgentClient.SohbetSayfasi.parse(json("""[{"id":"c1","title":"x"}]""")).items.isEmpty())
        assertTrue(AgentClient.SohbetSayfasi.parse(json("""{"conversations":[{"id":"c1"}]}""")).items.isEmpty())

        val takvim = ZoneId.of("Europe/Istanbul")
        val simdi = requireNotNull(SohbetTarihi.cozumle("2026-10-21T12:00:00Z"))
        assertEquals("09:05", SohbetTarihi.metin(requireNotNull(SohbetTarihi.cozumle("2026-10-21T06:05:00Z")), simdi, takvim))
        assertEquals("Dün", SohbetTarihi.metin(requireNotNull(SohbetTarihi.cozumle("2026-10-20T10:00:00Z")), simdi, takvim))
        assertEquals("17 Eki", SohbetTarihi.metin(requireNotNull(SohbetTarihi.cozumle("2026-10-17T10:00:00Z")), simdi, takvim))
        assertEquals("17.10.2025", SohbetTarihi.metin(requireNotNull(SohbetTarihi.cozumle("2025-10-17T10:00:00Z")), simdi, takvim))
        assertNotNull("saat dilimi uzaklıklı biçim de çözülür", SohbetTarihi.cozumle("2026-10-21T12:00:00+03:00"))
        assertNull(SohbetTarihi.cozumle("dün"))
    }

    /** Kural 4: içeriği boş + excel mesajı korunur (`messageId`/`artifactIndex`); grafik olur; son mesaj kullanıcıysa yazma bağlanmaz. */
    @Test
    fun sohbetAyrintisi() {
        val d = AgentClient.ConversationDetail.parse(
            json(
                """
                {"id":"c1","messages":[
                  {"id":"m1","role":"user","content":"rapor"},
                  {"id":"m2","role":"assistant","content":"","artifacts":[
                     {"kind":"chart","data":{"type":"bar","rows":[]}},
                     {"kind":"excel","data":{"filename":"r.xlsx","downloadUrl":"/api/ai-assistant/download/f1","fileId":"f1","rowCount":3}}]},
                  {"id":"m3","role":"user","content":"tamam"}],
                 "pendingWrite":{"operation":"create","entity":"treatment_items","ozet":"x"}}
                """,
            ),
        )
        assertEquals(3, d.messages.size)
        val m2 = d.messages[1]
        assertEquals(1, m2.charts.size)
        assertEquals("m2", m2.excelFiles.first().messageId)
        assertEquals(1, m2.excelFiles.first().artifactIndex)
        assertNull("son mesaj kullanıcıysa bağlanmaz", d.ekranMesajlari().firstOrNull { it.writePreview != null })

        val d2 = AgentClient.ConversationDetail.parse(
            json("""{"messages":[{"id":"a","role":"user","content":"x"},{"id":"b","role":"assistant","content":"y"}],"pendingWrite":{"ozet":"z"}}"""),
        )
        assertEquals("z", d2.ekranMesajlari().last().writePreview?.ozet)
        assertEquals("b", d2.ekranMesajlari().last().serverId)
    }

    /** Kural 5: 404 kodu → `conversationId == null` + "Yeniden gönder"; kodsuz 409 "meşgul" demez. */
    @Test
    fun sohbetBulunamadi() {
        assertTrue(AgentSessionManager.sohbetBulunamadiMi(AgentClient.AgentError.http(404, "x", "E_CONVERSATION_NOT_FOUND")))
        assertTrue(AgentSessionManager.sohbetBulunamadiMi(AgentClient.AgentError.http(404, null, null)))
        assertFalse(AgentSessionManager.sohbetBulunamadiMi(AgentClient.AgentError.http(409, null, null)))
        assertFalse(AgentSessionManager.errorText(AgentClient.AgentError.http(409, null, null)).lowercase().contains("meşgul"))

        val vm = AgentSessionManager().apply { ornekVeri = true }
        vm.conversationId = "c1"
        vm.conversations = listOf(AgentConversation("c1", "x", null, null))
        val a = AgentMessage(role = AgentRole.assistant, streaming = true)
        vm.messages = listOf(AgentMessage(role = AgentRole.user, content = "soru"), a)
        vm.sohbetBulunamadi("soru", null, a.id)
        assertNull(vm.conversationId)
        assertTrue(vm.conversations.isEmpty())
        assertEquals("soru", vm.messages.last().yenidenGonderilecek)
        assertEquals(AgentSessionManager.sohbetBulunamadiMetni, vm.messages.last().content)
        vm.dispose()
    }

    /**
     * Kural 6: `retryLast` — mesaj sayısı artmaz (başarısız çift kalkar, yenisi gelir); `onay` ve `pick` aynı.
     * Kural 7: bağlantı hatası → `agentOnline == false`; akıştan ilk olayla `true`.
     * (Sunucu yerine bağlantı hatası veren sahte akış — ağ çağrısı yok.)
     */
    @Test
    fun tekrarDeneVeBaglanti() {
        val cagrilar = mutableListOf<Pair<AgentClient.PickPayload?, AgentClient.OnayPayload?>>()
        val vm = AgentSessionManager { _, _, pick, _, _, onay ->
            cagrilar += pick to onay
            flow<AgentEvent> { throw ConnectException("bağlantı yok") }
        }.apply { ornekVeri = true }
        val pick = AgentClient.PickPayload(table = "patients", term = "Deneme Hasta", id = "p1", name = "Deneme Hasta")
        vm.stickyPick = pick
        vm.conversationId = "c1"
        val onceki = AgentMessage(
            role = AgentRole.assistant, content = "önizleme",
            writePreview = AgentWritePreview.parse(requireNotNull(json("""{"onizlemeId":"o1"}""").objectValue)),
        )
        vm.messages = listOf(onceki)
        vm.cancelWrite()                 // → "Vazgeç" + onay{vazgec,o1}; sahte akış bağlantı hatası verir
        assertEquals(3, vm.messages.size)
        assertFalse("tur bitti", vm.isStreaming)
        assertEquals(AgentClient.OnayPayload("vazgec", "o1"), vm.lastOnay)
        assertEquals("Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin.", vm.composerError)
        assertTrue(vm.messages.last().isError)

        vm.retryLast()
        assertEquals("başarısız çift kalkar, yenisi gelir", 3, vm.messages.size)
        assertEquals("Vazgeç", vm.messages[1].content)
        assertEquals(AgentClient.OnayPayload("vazgec", "o1"), vm.lastOnay)
        assertEquals(pick.id, vm.lastPick?.id)
        assertEquals(listOf(pick to AgentClient.OnayPayload("vazgec", "o1"), pick to AgentClient.OnayPayload("vazgec", "o1")), cagrilar)

        // Kural 7 — bağlantı hatası (doğrudan) → false; ilk olay → true.
        vm.turHatasi(ConnectException(), "x", null, vm.messages.last().id)
        assertFalse("bağlantı hatası agentOnline'ı düşürmeli", vm.agentOnline)
        val olay = requireNotNull(AgentEvent.from(mapOf("type" to JSONValue.string("start"))))
        vm.akistanOlay(olay, vm.messages.last().id)
        assertTrue(vm.agentOnline)
        assertTrue(AgentSessionManager.baglantiHatasiMi(ConnectException()))
        assertTrue(AgentSessionManager.baglantiHatasiMi(UnknownHostException()))
        assertFalse(AgentSessionManager.baglantiHatasiMi(SocketTimeoutException()))
        assertEquals(
            "Yanıt çok uzun sürdü. Analitik sorular birkaç dakika sürebilir; tekrar deneyin ya da soruyu sadeleştirin.",
            AgentSessionManager.errorText(SocketTimeoutException()),
        )
        vm.stop()
        vm.dispose()
    }

    /** Kural 8: `fin.view`siz oturumda iki finans çipi YOK, `fin.view`le var. */
    @Test
    fun oneriCipleri() {
        val yok = AgentViewDefaults.oneriler(finansGorur = false).map { it.text }
        assertFalse(yok.contains("Bu ayın cirosu"))
        assertFalse(yok.contains("Vadesi geçmiş ödemeler"))
        assertEquals(2, yok.size)
        val var_ = AgentViewDefaults.oneriler(finansGorur = true).map { it.text }
        assertTrue(var_.contains("Bu ayın cirosu"))
        assertTrue(var_.contains("Vadesi geçmiş ödemeler"))
    }

    /** Akış olayları: seçim kartı mesaja bağlanır; kuyruğa alınan kart tur bitince gönderilir; kredi `yarida` yarım çıktıyı siler. */
    @Test
    fun akisOlaylari() {
        val olaylar = listOf(
            """{"type":"conversation","conversationId":"c9"}""",
            """{"type":"token","text":"Hangi "}""",
            """{"type":"token","text":"dolgu?"}""",
            """{"type":"entity_choices","table":"service_catalog","term":"dolgu","choices":[{"id":"s1","name":"Kompozit Dolgu","detail":"1.800,00 TL"}]}""",
            """{"type":"tool_result","name":"chart_spec","summary":"","artifact":{"type":"bar","xKey":"ay","rows":[{"ay":"Eki","adet":3}]}}""",
            """{"type":"done"}""",
        ).map { requireNotNull(AgentEvent.parse(it)) }
        var gonderilen = 0
        val vm = AgentSessionManager { _, _, _, _, _, _ ->
            gonderilen += 1
            flow { if (gonderilen == 1) olaylar.forEach { emit(it) } }
        }.apply { ornekVeri = true }
        vm.send("dolgu ekle")
        assertEquals("c9", vm.conversationId)
        val cevap = vm.messages.last()
        assertEquals("Hangi dolgu?", cevap.content)
        assertEquals("s1", cevap.choices.single().recordId)
        assertEquals("1.800,00 TL", cevap.choices.single().subtitle)
        assertEquals(1, cevap.charts.size)
        assertFalse(cevap.streaming)

        // Kimlikli kart → `pick` ile gider, metin kartın adı.
        assertTrue(vm.sendChoice(cevap.choices.single()))
        assertEquals("Kompozit Dolgu", vm.messages[vm.messages.size - 2].content)
        assertEquals("service_catalog", vm.lastPick?.table)
        assertEquals("dolgu", vm.lastPick?.term)
        vm.dispose()
    }
}
