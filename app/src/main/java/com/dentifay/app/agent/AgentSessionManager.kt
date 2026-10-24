@file:Suppress("ConstPropertyName")

package com.dentifay.app.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.KREDI_KUR_YOK_METNI
import com.dentifay.app.core.Yetki
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.voice.DictationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.UUID

/** Akış kaynağı: (mesaj, sohbet, pick, moduleScope, hedefPlanId, onay) → SSE olayları. Testler sahte akış verir. */
internal typealias AkisKaynagi =
    (String, String?, AgentClient.PickPayload?, String?, String?, AgentClient.OnayPayload?) -> Flow<AgentEvent>

/**
 * Sohbet ajanı oturumu (Swift `@Observable final class AgentSessionManager`). Sohbet ekranı ve Tedavi Planı paylaşır;
 * alanlar Compose anlık durumudur, iş ana iş parçacığında (`MainScope`) yürür. Ekran kapanınca `dispose()`.
 */
class AgentSessionManager internal constructor(private val akisAc: AkisKaynagi) {

    constructor() : this({ m, c, p, s, h, o -> AgentClient.shared.streamChat(m, c, p, s, h, o) })

    // MARK: - State
    var messages by mutableStateOf<List<AgentMessage>>(emptyList())
    var conversationId by mutableStateOf<String?>(null)
    var conversations by mutableStateOf<List<AgentConversation>>(emptyList())

    /** Sohbet listesinin sayfası ve durumu (Ek MO.18). */
    var sohbetSayfasi by mutableIntStateOf(1)
        private set
    var sohbetDahaVar by mutableStateOf(false)
    var sohbetListeHatasi by mutableStateOf(false)
        private set
    var sohbetListeYukleniyor by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
    var isStreaming by mutableStateOf(false)

    /**
     * Bağlantı durumu — modelin değil, BAĞLANTININ durumu (M-E1.5.1). Sağlık ucu yoklanmaz: tur bağlantı hatasıyla
     * düşerse `false`, akıştan ilk olay gelince `true`.
     */
    var agentOnline by mutableStateOf(true)
    var isListening by mutableStateOf(false)        // dikte (konuşma → düzenlenebilir taslak)
    var elapsedSeconds by mutableIntStateOf(0)

    /**
     * Tek ses yığını (AudioSessionCoordinator → VoiceAudioGraph → SpeechPipeline). İlk dikte isteğinde kurulur —
     * yalnız yazan kullanıcı için ses grafiği açılmaz.
     */
    private val sesYigini = lazy(LazyThreadSafetyMode.NONE) { DictationSession() }
    val speech: DictationSession get() = sesYigini.value

    /** Önizleme / test: ağ çağrısı yapılmaz (sohbet listesi ve bakiye okunmaz). */
    var ornekVeri = false

    private val scope = MainScope()

    // Dikte (yeniden) başladığında yakalanan taslak. Yeni konuşma bunun ARKASINA eklenir;
    // mikrofona tekrar basmak metni ezmez, sonundan devam eder.
    private var dictationBase = ""

    private var streamTask: Job? = null
    private var tickTask: Job? = null

    /** Bir asistan turu akışı bitince çağrılır (Tedavi Planı faz kararını buradan verir). */
    var onAssistantComplete: ((AgentMessage) -> Unit)? = null

    // MARK: - Modül köprüsü (Tedavi Planı bunları bağlar)

    /** Sunucu-sahipli modül odağı (tek enum id). Yetki/kapsam GENİŞLETMEZ. */
    var moduleScope: String? = null

    /**
     * Her turda YENİDEN gönderilecek kimlik sabitlemesi (ör. seçili hasta). `resolvedPicks` biriktirici olduğu için
     * tekrar göndermek zararsızdır ve konuşma yeniden yüklense bile kimliği sabit tutar.
     */
    var stickyPick: AgentClient.PickPayload? = null

    /** Tedavi Planı'nda açık plana ekleme — oturumun HER sohbet isteğinde gövdeye girer (M-E1.2.4). */
    var hedefPlanId: String? = null

    /** Her SSE olayında çağrılır — Tedavi Planı'nın hareketsizlik bekçisi buradan kurulur (M-E1.2.8). */
    var onActivity: (() -> Unit)? = null

    /** Yazma önizlemesi geldiğinde (Tedavi Planı önizleme ekranı için). */
    var onWritePreview: ((AgentWritePreview) -> Unit)? = null

    /** Tur "yapamadım" ile bitti (`soft_fail` / `error`) — GERÇEK hata sinyali. */
    var onSoftFail: ((String) -> Unit)? = null

    /** İlerleme olayları (queue/schema_link/thinking/tool_result) — Tedavi Planı ilerleme rayı için. */
    var onProgress: ((AgentProgress) -> Unit)? = null

    /** Akış sürerken tıklanıp KUYRUĞA alınan seçim, tur bitince gönderildiğinde. */
    var onChoiceDispatched: ((AgentChoice) -> Unit)? = null

    // MARK: - Tur kimliği + kompozer hatası

    /** Her tur benzersiz kimlik alır; finishStreaming yalnız KENDİ turunu bitirir (eski tur yeni turun bayrağını düşürmesin). */
    private var currentTurnId: UUID = UUID.randomUUID()

    /** Son gönderilen kullanıcı metni — retry için korunur. */
    var lastUserText = ""
        private set

    /** Son turun pick'i — "Tekrar dene" AYNI kimlikle koşsun. */
    var lastPick: AgentClient.PickPayload? = null
        private set

    /** Önceki turun `onay` alanı — "Tekrar dene" aynı alanla koşsun (M-E1.5.6). */
    var lastOnay: AgentClient.OnayPayload? = null
        private set

    /** Kompozer altında gösterilecek hata (null = yok). */
    var composerError by mutableStateOf<String?>(null)

    /**
     * 🔴 AKIŞ SÜRERKEN TIKLANAN KART YUTULMAZ — KUYRUĞA ALINIR. Kartlar tur BİTMEDEN çizilir (backend onları turun sonuç
     * olayından ÖNCE yayar) ama gönderme kilitlidir; tıklanan kart arayüzde "seçildi" olarak işaretlenir, tur bitince gider.
     */
    var queuedChoice by mutableStateOf<AgentChoice?>(null)
        private set

    /** "Yeniden gönder"in pick'i (silinmiş sohbet). */
    private var yenidenGonderPick: AgentClient.PickPayload? = null

    // MARK: - Lifecycle

    fun onAppear() {
        if (ornekVeri) return
        // Açılışta YALNIZ sohbet listesi — sağlık ucuna istek ATILMAZ (M-E1.5.1). Mikrofon izni dikte başlarken istenir.
        scope.launch { loadConversations() }
    }

    fun onDisappear() {
        stop()
        releaseAudio()
    }

    /**
     * 🔴 SES YIĞINI SERBEST BIRAKILIR. `stopDictation()` grafiği BİLEREK açık bırakır (mikrofona tekrar basmak ucuz
     * olsun); ekran kapanırken (sohbet ve Tedavi Planı `teardown`) oturum burada tamamen kapanır.
     */
    fun releaseAudio() {
        stopDictation()
        if (sesYigini.isInitialized()) speech.close()
    }

    /** Ekran kapanınca: tur durur, ses yığını bırakılır (kurulduysa), eşyordamlar bırakılır (Compose `DisposableEffect`). İdempotent. */
    fun dispose() {
        onDisappear()
        if (sesYigini.isInitialized()) speech.dispose()
        scope.cancel()
    }

    // MARK: - Sending

    fun send() {
        if (isListening) stopDictation()
        val text = draft.trim()
        if (text.isEmpty() || isStreaming) return
        draft = ""
        sendMessage(text)
    }

    fun sendSuggested(text: String) {
        if (isStreaming) return
        sendMessage(text)
    }

    /** İLK SINIF gönderim yolu: bir turu isteğe bağlı `pick` ile gönderir (modüller — seçili hasta kimliği her turda gider). */
    fun send(text: String, pick: AgentClient.PickPayload? = null) {
        if (isStreaming) return
        sendMessage(text, pick = pick)
    }

    /**
     * Belirsizlik kartı seçimi. 🔴 TEK KANAL: her tabloda `pick` gönderilir (`patientPick` YOLU KALDIRILDI).
     * @return true = hemen gönderildi, false = kuyruğa alındı (tur akıyor) ya da kimliksiz kart.
     */
    fun sendChoice(choice: AgentChoice): Boolean {
        // Kimliksiz kart dokunulamaz (Ek MO.10c) — hastanın telefonu/TC'si mesaja YAZILMAZ.
        if (choice.pick == null) return false
        if (isStreaming) {
            queuedChoice = choice
            return false
        }
        dispatchChoice(choice)
        return true
    }

    private fun dispatchChoice(choice: AgentChoice) {
        // Ekranda görünen/geçmişe yazılan metin: kartın adı. Seçim YALNIZ `pick` ile.
        val pick = choice.pick ?: return
        sendMessage(choice.title, pick = pick)
    }

    private fun sendMessage(text: String, pick: AgentClient.PickPayload? = null, onay: AgentClient.OnayPayload? = null) {
        // Önceki tur hâlâ sarılıyorsa iptal et (yoksa eski finishStreaming yeni turun bayrağını düşürür).
        streamTask?.cancel()
        lastUserText = text
        // Kimlik sabitlemesi: turun kendi pick'i, yoksa oturumun sabit pick'i.
        val effectivePick = pick ?: stickyPick
        lastPick = effectivePick
        lastOnay = onay
        composerError = null
        queuedChoice = null
        val turnId = UUID.randomUUID()
        currentTurnId = turnId
        val assistant = AgentMessage(role = AgentRole.assistant, streaming = true)
        messages = messages + AgentMessage(role = AgentRole.user, content = text) + assistant
        isStreaming = true
        elapsedSeconds = 0
        tickTask?.cancel()
        tickTask = scope.launch {
            while (isActive) {
                delay(1000)
                if (!isActive) break
                elapsedSeconds += 1
            }
        }
        streamTask = scope.launch { runStream(text, assistant.id, turnId, effectivePick, onay) }
    }

    private suspend fun runStream(
        text: String,
        assistantId: UUID,
        turnId: UUID,
        pick: AgentClient.PickPayload?,
        onay: AgentClient.OnayPayload?,
    ) {
        try {
            akisAc(text, conversationId, pick, moduleScope, hedefPlanId, onay).collect { event ->
                akistanOlay(event, assistantId)
            }
        } catch (e: CancellationException) {
            // İptal (durdur / yeni tur): hata yazılmaz; tur yine de kapatılır.
        } catch (credit: CreditRequired) {
            // 402: sunucu turu BAŞLATMADI. Ham kod yok — Türkçe metin + balonda kredi kartı;
            // Tedavi Planı aynı alanı `onAssistantComplete`te okur.
            applyCreditRequired(credit, assistantId)
        } catch (e: Throwable) {
            turHatasi(e, text, pick, assistantId)
        }
        finishStreaming(assistantId, turnId)
    }

    /** Akış hatası: bağlantı hatası `agentOnline`ı düşürür; 404 sohbeti yeni sohbete çevirir; diğerleri MO.15 / sunucu cümlesi. */
    fun turHatasi(error: Throwable, text: String, pick: AgentClient.PickPayload?, assistantId: UUID) {
        if (baglantiHatasiMi(error)) agentOnline = false
        if (sohbetBulunamadiMi(error)) {
            // Silinmiş/süresi dolmuş sohbet: çıkmaz sokak değil — yeni sohbet + "Yeniden gönder" (O.27C-7).
            sohbetBulunamadi(text, pick, assistantId)
        } else {
            val msg = errorText(error)
            if (msg.isNotEmpty()) {
                val kod = (error as? AgentClient.AgentError)?.serverCode
                update(assistantId) { it.copy(isError = true, errorCode = kod, content = it.content.ifEmpty { msg }) }
                // Kompozer hata çubuğu + "Tekrar dene".
                composerError = msg
                onSoftFail?.invoke(msg)
            }
        }
    }

    /** Akıştan gelen her olay: bağlantı var (`agentOnline = true`), bekçi kurulur, olay işlenir. */
    fun akistanOlay(event: AgentEvent, assistantId: UUID) {
        agentOnline = true
        onActivity?.invoke()
        handle(event, assistantId)
    }

    fun sohbetBulunamadi(metin: String, pick: AgentClient.PickPayload?, assistantId: UUID) {
        conversationId?.let { eski -> conversations = conversations.filter { it.id != eski } }
        conversationId = null
        update(assistantId) { it.copy(isError = true, content = sohbetBulunamadiMetni, yenidenGonderilecek = metin) }
        yenidenGonderPick = pick
    }

    /** "Yeniden gönder" — son metni YENİ sohbette, aynı `pick` ile; `onay` TAŞIMAZ (önizleme yeni sohbette yok). */
    fun yenidenGonder(mesaj: AgentMessage) {
        val metin = mesaj.yenidenGonderilecek ?: return
        if (isStreaming) return
        conversationId = null
        sendMessage(metin, pick = yenidenGonderPick)
    }

    /**
     * ⚠️ Backend'in FİİLEN yaydığı olaylar. `heartbeat` bir SSE YORUM satırıdır (`: hb`), JSON olay DEĞİL;
     * `queue_choices` / `tool_call` / üst-düzey `chart` hiç yayılmaz (grafik `tool_result name:"chart_spec"` artifact'i
     * olarak gelir). Var olmayan olayı beklemek sözleşme hakkında yalan söyleyen ölü koddur.
     */
    private fun handle(ev: AgentEvent, assistantId: UUID) {
        when (ev.type) {
            "conversation" -> ev.string("conversationId")?.let { conversationId = it }

            "thinking" -> {
                val text = ev.string("text")
                update(assistantId) { it.copy(thinkingText = text) }
                if (text != null) onProgress?.invoke(AgentProgress.reasoning(text, ev.string("stage")))
            }

            "queue" -> onProgress?.invoke(AgentProgress.queued(ev.int("position") ?: 0))

            // Yalnız "anlama" adımı; tablo adı gösterilmez.
            "schema_link", "examples" -> onProgress?.invoke(AgentProgress.retrieved)

            "token" -> {
                val t = ev.string("text") ?: ""
                update(assistantId) { it.copy(content = it.content + t, thinkingText = null) }
            }

            "patient_choices", "entity_choices" -> {
                // Kart MESAJA bağlanır (global state'e DEĞİL). 0 adaylı `elleSecim` kartı da çizilir (26D.7).
                val set = AgentChoiceSet.parse(ev.raw)
                if (set.cizilir) update(assistantId) { it.copy(choiceSet = set) }
            }

            "tool_result" -> {
                handleTool(ev, assistantId)
                onProgress?.invoke(
                    AgentProgress.tool(ev.string("name") ?: "", ev.string("summary") ?: "", ev.bool("ok") ?: true, ev.int("rowCount")),
                )
            }

            "write_preview" -> {
                // Tek ayrıştırıcı (konuşma ayrıntısının `pendingWrite`ı da bunu kullanır).
                val preview = AgentWritePreview.parse(ev.raw)
                update(assistantId) { it.copy(writePreview = preview) }
                onWritePreview?.invoke(preview)
            }

            "write_confirmation_required", "write_double_confirm" -> update(assistantId) { m ->
                val p = m.writePreview
                if (p != null) {
                    if (ev.type == "write_double_confirm") m.copy(writePreview = p.copy(requiresDoubleConfirm = true)) else m
                } else {
                    m.copy(
                        writePreview = AgentWritePreview(
                            operation = null, entity = null, risk = null, affectedCount = null,
                            requiresDoubleConfirm = ev.type == "write_double_confirm" || (ev.bool("requiresDoubleConfirm") ?: false),
                        ),
                    )
                }
            }

            "write_result" -> update(assistantId) {
                it.copy(writeResult = AgentWriteResult.from(ev.string("status") ?: ""), writePreview = null)
            }

            // 🔴 Tur "yapamadım" ile bitti — AÇIK sinyal (Tedavi Planı tahmin yürütmez).
            "soft_fail" -> update(assistantId) { it.copy(softFailCode = ev.string("code") ?: "SOFT_FAIL") }

            "error" -> {
                // Kredi kodu taşıyan olay (eski demo olayı dahil) 402 ile AYNI yoldan geçer.
                val credit = CreditRequired.parse(JsonObject(ev.raw.mapValues { it.value.jsonElement }))
                if (credit != null) {
                    applyCreditRequired(credit, assistantId)
                } else {
                    // `E_CREDIT_FX_MISSING` kredi modeli DEĞİL: sunucunun cümlesi, yoksa Ek MO.23f.
                    val msg = ev.string("message")
                        ?: if (ev.string("code") == "E_CREDIT_FX_MISSING") KREDI_KUR_YOK_METNI else "Bir hata oluştu."
                    update(assistantId) { it.copy(isError = true, content = msg, thinkingText = null) }
                    composerError = msg
                    onSoftFail?.invoke(msg)
                }
            }

            "start", "done", "end" -> Unit

            else -> Unit
        }
    }

    private fun handleTool(ev: AgentEvent, assistantId: UUID) {
        val name = ev.string("name") ?: "tool"
        val ok = ev.bool("ok") ?: true
        val summary = ev.string("summary") ?: ""
        if (summary.isNotEmpty()) {
            update(assistantId) { it.copy(toolSteps = it.toolSteps + AgentToolStep(name, summary, ok)) }
        }

        val art = ev.`object`("artifact") ?: return
        val artType = art["type"]?.stringValue

        if (name.contains("excel") || artType == "excel") {
            // Ayrıntının `artifacts[]`ı da AYNI kurucuyu kullanır.
            AgentExcelArtifact.from(art)?.let { file -> update(assistantId) { it.copy(excelFiles = it.excelFiles + file) } }
        } else if (name.contains("chart")) {
            // `tool_result name:"chart_spec"` → artifact DOĞRUDAN spec'tir.
            AgentChartSpec.from(art)?.let { spec -> update(assistantId) { it.copy(charts = it.charts + spec) } }
        }
    }

    private fun finishStreaming(assistantId: UUID, turnId: UUID) {
        // Yalnız KENDİ turunu bitir — kullanıcı hemen yeni mesaj gönderdiyse eski
        // runStream buraya düşse bile yeni turun global bayrağını düşürmesin.
        update(assistantId) { it.copy(streaming = false, thinkingText = null) }
        if (turnId != currentTurnId) {
            messages.firstOrNull { it.id == assistantId }?.let { onAssistantComplete?.invoke(it) }
            return
        }
        isStreaming = false
        streamTask = null
        tickTask?.cancel()
        tickTask = null
        messages.firstOrNull { it.id == assistantId }?.let { onAssistantComplete?.invoke(it) }
        // Her tur sonunda bakiye cüzdandan okunur (`credits` olayı YOK — M-E1.5.2, M-E1.7.7).
        if (!ornekVeri) scope.launch { bakiyeyiTazele() }
        // Akış sırasında tıklanan kart varsa ŞİMDİ gönder (sessiz yutma YOK).
        val queued = queuedChoice
        if (queued != null) {
            queuedChoice = null
            onChoiceDispatched?.invoke(queued)
            dispatchChoice(queued)
            return
        }
        scope.launch { loadConversations() }
    }

    private suspend fun bakiyeyiTazele() {
        try {
            SmileCreditsStore.shared.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Bakiye okunamadı — sohbet etkilenmez.
        }
    }

    /**
     * Kompozer hata çubuğundan "Tekrar dene" — başarısız turun KULLANICI ve ASİSTAN balonları kalkar, aynı metin aynı
     * `pick` ve aynı `onay` ile gider (M-E1.5.6). Sunucunun bekleyen yazmayı bir kez tüketmesi çift kaydı önler.
     */
    fun retryLast() {
        if (isStreaming || lastUserText.isEmpty()) return
        val metin = lastUserText
        val pick = lastPick
        val onay = lastOnay
        val liste = messages.toMutableList()
        if (liste.lastOrNull()?.role == AgentRole.assistant) {
            liste.removeAt(liste.lastIndex)
            val onceki = liste.lastOrNull()
            if (onceki != null && onceki.role == AgentRole.user && onceki.content == metin) liste.removeAt(liste.lastIndex)
        }
        messages = liste
        sendMessage(metin, pick = pick, onay = onay)
    }

    /**
     * 402 (ya da kredi kodlu SSE `error`) → balon: Türkçe metin + kredi kartı. Tek yol; sohbet ve Tedavi Planı aynı
     * alanı okur. Bakiye göstergesi tazelenir.
     */
    private fun applyCreditRequired(credit: CreditRequired, assistantId: UUID) {
        update(assistantId) {
            val m = it.copy(isError = true, creditRequired = credit, content = credit.message, thinkingText = null)
            // Tur ortasında kredi bitti: o tura ait yarım çıktı ÇİZİLMEZ (sunucu yazma yapmadı).
            if (credit.yarida) {
                m.copy(writePreview = null, choiceSet = null, charts = emptyList(), excelFiles = emptyList(), toolSteps = emptyList())
            } else {
                m
            }
        }
        SmileCreditsStore.shared.noteBalanceChanged()
    }

    /** Akan mesaj listenin sonundadır — arama sondan başlar. */
    private inline fun update(id: UUID, mutate: (AgentMessage) -> AgentMessage) {
        val list = messages
        val idx = list.indexOfLast { it.id == id }
        if (idx < 0) return
        messages = list.toMutableList().also { it[idx] = mutate(it[idx]) }
    }

    fun stop() {
        streamTask?.cancel()
        streamTask = null
        val idx = messages.indexOfLast { it.role == AgentRole.assistant }
        if (idx >= 0) {
            messages = messages.toMutableList().also { it[idx] = it[idx].copy(streaming = false, thinkingText = null) }
        }
        isStreaming = false
        queuedChoice = null
    }

    // MARK: - Write confirm

    /** Ekrandaki bekleyen önizleme (son önizlemeli mesaj). */
    val bekleyenOnizleme: AgentWritePreview? get() = messages.lastOrNull { it.writePreview != null }?.writePreview

    /** Onay — bugünkü metin AYNEN + `onay {islem:"onayla", onizlemeId}` (27C.2). Süresi dolmuşsa gönderilmez. @return gönderildi mi */
    fun confirmWrite(doubleConfirm: Boolean): Boolean {
        val istek = OnayIstegi.kur(OnayIstegi.Islem.onayla, bekleyenOnizleme, doubleConfirm, conversationId) ?: return false
        sendMessage(istek.metin, onay = istek.onay)
        return true
    }

    /** Vazgeç — `onay {islem:"vazgec"}` (model çağrılmadan biter). */
    fun cancelWrite() {
        val istek = OnayIstegi.kur(OnayIstegi.Islem.vazgec, bekleyenOnizleme, false, conversationId) ?: return
        sendMessage(istek.metin, onay = istek.onay)
    }

    // MARK: - Sesli dikte (kompozeri doldurur, otomatik GÖNDERMEZ; kullanıcı düzeltip gönderir)

    fun toggleDictation() {
        if (isListening) stopDictation() else startDictation()
    }

    fun startDictation() {
        if (isStreaming || isListening) return
        // Kompozerde ne varsa onun sonundan devam et.
        dictationBase = draft
        val s = speech
        s.autoStopOnSilence = true
        // Uzun sessizlik dinlemeyi kendiliğinden durdurur; metin kalır, hiçbir şey gönderilmez.
        s.onAutoStop = { stopDictation() }
        // 🔴 Canlı metin taslağa AKITILIR.
        s.onTranscriptChange = { t -> applyDictationTranscript(t) }
        isListening = true
        scope.launch {
            val ok = s.startListening()
            if (!ok) {
                isListening = false
                composerError = s.errorMessage ?: "Mikrofon başlatılamadı."
            }
        }
    }

    fun stopDictation() {
        if (!isListening) return
        isListening = false
        speech.stopListening()
        speech.onTranscriptChange = null
        speech.onAutoStop = null
        // Yazıya dökülen metin zaten `draft`ta; kullanıcı düzenlesin ya da dikteye devam etsin.
    }

    /** Canlı dökümü dikte başladığındaki metnin arkasına ekler (mikrofona tekrar basmak ezmez). */
    fun applyDictationTranscript(t: String) {
        if (!isListening) return
        draft = when {
            dictationBase.isEmpty() -> t
            t.isEmpty() -> dictationBase
            else -> dictationBase + (if (dictationBase.endsWith(" ")) "" else " ") + t
        }
    }

    // MARK: - Conversations

    /** İlk sayfa (yeniler). Yükleme hatası boş listeyle aynı görünmez (Ek MO.18c). */
    suspend fun loadConversations() {
        if (ornekVeri) return
        sohbetListeYukleniyor = true
        try {
            val sayfa = AgentClient.shared.fetchConversations(page = 1)
            conversations = sayfa.items
            sohbetSayfasi = 1
            sohbetDahaVar = sayfa.dahaVar
            sohbetListeHatasi = false
        } catch (e: CancellationException) {
            sohbetListeYukleniyor = false
            throw e
        } catch (e: Exception) {
            sohbetListeHatasi = !Yetki.reddiMi(e)   // kodsuz 403 → boş liste, kırmızı kart yok (M-E7.5)
        }
        sohbetListeYukleniyor = false
    }

    /** Ek MO.18b — "Daha fazla sohbet": sonraki sayfayı listeye EKLER. */
    suspend fun dahaFazlaSohbet() {
        if (!sohbetDahaVar || sohbetListeYukleniyor) return
        sohbetListeYukleniyor = true
        try {
            val sayfa = AgentClient.shared.fetchConversations(page = sohbetSayfasi + 1)
            val mevcut = conversations.map { it.id }.toHashSet()
            conversations = conversations + sayfa.items.filter { it.id !in mevcut }
            sohbetSayfasi += 1
            sohbetDahaVar = sayfa.dahaVar
        } catch (e: CancellationException) {
            sohbetListeYukleniyor = false
            throw e
        } catch (e: Exception) {
            sohbetListeHatasi = !Yetki.reddiMi(e)
        }
        sohbetListeYukleniyor = false
    }

    fun newChat() {
        stop()
        stopDictation()
        messages = emptyList()
        conversationId = null
        draft = ""
    }

    fun openConversation(conv: AgentConversation) {
        stop()
        conversationId = conv.id
        scope.launch {
            try {
                val detail = AgentClient.shared.fetchConversationDetail(conv.id)
                messages = detail.ekranMesajlari()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sohbetBulunamadiMi(e)) {
                    // Ayrıntı ucu 404 → sohbet listeden düşer, ekranda O.27C-7.
                    conversations = conversations.filter { it.id != conv.id }
                    conversationId = null
                    messages = listOf(AgentMessage(role = AgentRole.assistant, content = sohbetBulunamadiMetni, isError = true))
                } else {
                    composerError = errorText(e)
                }
            }
        }
    }

    /**
     * Excel indirme — süresi dolmuşsa (404) ve mesaj kimliği varsa BİR KEZ yeniden üretilip indirilir (M-E1.5.5).
     * Canlı akıştaki (kimliksiz) Excel'de yeniden üretim yok (web ile aynı).
     */
    suspend fun excelIndir(file: AgentExcelArtifact, uretiliyor: (Boolean) -> Unit = {}): File =
        try {
            AgentClient.shared.downloadArtifact(file.downloadUrl, file.filename)
        } catch (e: AgentClient.AgentError.http) {
            val mesajId = file.messageId
            val sira = file.artifactIndex
            if (e.code != 404 || mesajId == null || sira == null) throw e
            uretiliyor(true)
            try {
                val yeni = AgentClient.shared.regenerateExcel(mesajId, sira)
                AgentClient.shared.downloadArtifact(yeni.downloadUrl, yeni.filename)
            } finally {
                uretiliyor(false)
            }
        }

    fun deleteConversation(conv: AgentConversation) {
        // Satır hemen kalkar (kaydırarak silme geri zıplamasın); liste sonra sunucudan yenilenir.
        conversations = conversations.filter { it.id != conv.id }
        scope.launch {
            AgentClient.shared.deleteConversation(conv.id)
            if (conversationId == conv.id) newChat()
            loadConversations()
        }
    }

    companion object {
        /** Ek O.27C-7 */
        const val sohbetBulunamadiMetni = "Sohbet bulunamadı; yeni bir sohbet başlatıldı. Mesajınızı yeniden gönderebilirsiniz."

        /** `ApiException.NetworkError` sarmalını açar. */
        private fun kok(error: Throwable): Throwable = (error as? ApiException.NetworkError)?.cause ?: error

        /**
         * Bağlantı hatası mı (`agentOnline = false` için) — M-E1.5.1'in dört kodunun Android karşılığı: ad çözülemedi
         * (`UnknownHostException`), bağlanılamadı / yol yok / bağlantı koptu (`SocketException` ailesi).
         * Zaman aşımı bağlantı hatası DEĞİLDİR.
         */
        fun baglantiHatasiMi(error: Throwable): Boolean {
            val e = kok(error)
            return e is UnknownHostException || e is SocketException
        }

        /** Sohbet ucunun 404'ü (`E_CONVERSATION_NOT_FOUND`). */
        fun sohbetBulunamadiMi(error: Throwable): Boolean {
            val e = error as? AgentClient.AgentError.http ?: return false
            return e.kod == "E_CONVERSATION_NOT_FOUND" || (e.code == 404 && e.kod == null)
        }

        /** Ek MO.15 — ham istisna metni ekrana basılmaz; sunucunun cümlesi varsa o (M0-7). */
        fun errorText(error: Throwable): String {
            if (error is CancellationException) return ""
            val e = kok(error)
            if (e is IOException) {
                if (e.message?.contains("Canceled", ignoreCase = true) == true) return ""   // istek iptal edildi
                if (e is InterruptedIOException) {
                    return "Yanıt çok uzun sürdü. Analitik sorular birkaç dakika sürebilir; tekrar deneyin ya da soruyu sadeleştirin."
                }
                if (baglantiHatasiMi(e)) return "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin."
                return "Bağlantı hatası oluştu. Tekrar deneyin."
            }
            if (error is AgentClient.AgentError) return error.errorDescription ?: "Bir hata oluştu."
            return "Bir hata oluştu."
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun AgentSessionManagerPreview() {
    val vm = remember {
        AgentSessionManager().apply {
            ornekVeri = true
            messages = listOf(
                AgentMessage(role = AgentRole.user, content = "Kritik stok"),
                AgentMessage(role = AgentRole.assistant, content = "3 ürün kritik seviyede."),
            )
        }
    }
    PreviewEnv {
        Column(Modifier.padding(16.dp)) {
            vm.messages.forEach { Text("${it.role}: ${it.content}", style = sys(14), color = DS.Colors.onSurface) }
        }
    }
}
