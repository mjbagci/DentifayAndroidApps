package com.dentifay.app.voice

import com.dentifay.app.agent.AgentClient
import com.dentifay.app.agent.AgentWritePreview
import com.dentifay.app.agent.JSONValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * iOS `SesSokumuTests` (M-E1.1 mühürleri) — sesli karşılıklı konuşma modunun sökümü: istek `mode` taşımaz, yalnız dikte
 * tüketicisi kalır, Tedavi Planı'nın sesli okuması `write_preview.ozet`ten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SesSokumuTest {

    @Before fun kur() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun boz() = Dispatchers.resetMain()

    private fun nesne(s: String): Map<String, JSONValue> = requireNotNull(JSONValue.parse(s)?.objectValue) { "JSON nesnesi değil: $s" }

    /** Kural 1: `ChatRequest` JSON'unda `mode` anahtarı hiçbir alan bileşiminde YOK. */
    @Test
    fun chatRequestModeTasimaz() {
        val pick = AgentClient.PickPayload("patients", "Deneme Hasta", "p1", "Deneme Hasta")
        val bilesimler = listOf(
            AgentClient.ChatRequest(message = "merhaba", conversationId = null, pick = null, moduleScope = null),
            AgentClient.ChatRequest(message = "merhaba", conversationId = "c1", pick = pick, moduleScope = "mobil-tedavi-plani"),
            AgentClient.ChatRequest(message = "merhaba", conversationId = "c1", pick = null, moduleScope = null),
        )
        for (req in bilesimler) {
            val govde = req.toJson()
            assertNull("gövde `mode` taşımamalı: $govde", govde["mode"])
            assertEquals("merhaba", govde["message"]?.jsonPrimitive?.content)
        }
    }

    /** Ses oturumunun tek tüketicisi dikte — konuşma modu tüketicisi kalkmıştır (M-E1.1.1). */
    @Test
    fun yalnizDikteTuketicisi() {
        assertEquals(listOf(AudioSessionCoordinator.Consumer.dictation), AudioSessionCoordinator.Consumer.entries.toList())
    }

    /** Kural 2: `AgentWritePreview.parse` `ozet`i okur; olayda yoksa ya da `null`sa `null`. */
    @Test
    fun writePreviewOzet() {
        val dolu = nesne("""{"type":"write_preview","operation":"create","entity":"treatment_items","ozet":"Deneme Hasta için 1 tedavi eklenecek. Onaylıyor musunuz?","steps":[]}""")
        assertEquals("Deneme Hasta için 1 tedavi eklenecek. Onaylıyor musunuz?", AgentWritePreview.parse(dolu).ozet)
        assertNull(AgentWritePreview.parse(nesne("""{"type":"write_preview","operation":"create","entity":"treatment_items","ozet":null}""")).ozet)
        assertNull(AgentWritePreview.parse(nesne("""{"type":"write_preview","operation":"create"}""")).ozet)
    }

    /** Kural 3: konuşma ayrıntısının `pendingWrite.ozet`i aynı `parse`la çözülür. */
    @Test
    fun pendingWriteOzetAyniParse() {
        val v = requireNotNull(
            JSONValue.parse(
                """
                { "id": "c1", "title": "Deneme", "messages": [
                    { "id": "m1", "role": "user", "content": "36'ya dolgu" },
                    { "id": "m2", "role": "assistant", "content": "Önizleme hazır." } ],
                  "pendingWrite": { "operation": "create", "entity": "treatment_items",
                                    "requiresDoubleConfirm": false, "ozet": "1 tedavi eklenecek." } }
                """,
            ),
        )
        val detail = AgentClient.ConversationDetail.parse(v)
        assertEquals(2, detail.messages.size)
        assertEquals("1 tedavi eklenecek.", detail.pendingWrite?.ozet)
        val ham = requireNotNull(v.objectValue?.get("pendingWrite")?.objectValue)
        assertEquals(AgentWritePreview.parse(ham), detail.pendingWrite)
    }

    /** Özet okumasının metin yardımcıları: cümle kuyruğu, `TTS_TEXT_TOO_LONG` bölmesi, işaretlerin atılması. */
    @Test
    fun ozetOkumaYardimcilari() {
        assertEquals(
            listOf("Deneme Hasta için Plan 4 açılacak.", "Onaylıyor musunuz?"),
            TTSPipeline.splitSentences("Deneme Hasta için Plan 4 açılacak. Onaylıyor musunuz?"),
        )
        assertEquals(listOf("Tek cümle"), TTSPipeline.splitSentences("  Tek cümle "))
        assertTrue(TTSPipeline.splitSentences("   ").isEmpty())
        assertEquals("a b" to "c d", TTSPipeline.splitInHalf("a b c d"))
        assertNull(TTSPipeline.splitInHalf("tek"))
        assertEquals("Kalın metin", TTSPipeline.stripMarkdown("**Kalın** _metin_"))
    }

    /** Dikte oturumu: varsayılanlar; dinlemiyorken durdurmak güvenli; sıfırlama taslağa boş metin akıtır; boş metin okunmaz. */
    @Test
    fun dikteOturumu() {
        val s = DictationSession()
        assertTrue(s.autoStopOnSilence)
        assertEquals(4.0, s.silenceTimeout, 0.0)
        assertFalse(s.isListening)
        assertEquals(0.0, s.audioLevel, 0.0)

        var son: String? = null
        s.onTranscriptChange = { son = it }
        s.stopListening()
        s.resetTranscript()
        assertEquals("", son)
        assertEquals("", s.transcript)
        s.speak("   ")
        s.close()
        s.dispose()
        s.dispose()   // ikinci kez çağrı zararsız
        assertFalse(s.isListening)
    }
}
