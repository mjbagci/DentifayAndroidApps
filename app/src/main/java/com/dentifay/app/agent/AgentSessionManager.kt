package com.dentifay.app.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.voice.SpeechManager
import com.dentifay.app.voice.VoiceProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.UUID

/** Sohbet ajanı oturumu (Swift `@Observable final class AgentSessionManager`). Sohbet ve sesli mod paylaşır. */
class AgentSessionManager {

    // MARK: - State
    var messages by mutableStateOf<List<AgentMessage>>(emptyList())
    var conversationId by mutableStateOf<String?>(null)
    var conversations by mutableStateOf<List<AgentConversation>>(emptyList())
    var draft by mutableStateOf("")
    var isStreaming by mutableStateOf(false)
    var agentOnline by mutableStateOf(true)
    var isListening by mutableStateOf(false)        // dikte (konuşma → düzenlenebilir taslak)
    var elapsedSeconds by mutableIntStateOf(0)

    val speech = SpeechManager()

    private val scope = MainScope()

    // Dikte (yeniden) başladığında yakalanan taslak. Yeni konuşma bunun ARKASINA eklenir;
    // mikrofona tekrar basmak metni ezmez, sonundan devam eder.
    private var dictationBase = ""

    private var streamTask: Job? = null
    private var tickTask: Job? = null

    /** Asistan turu akışı bitince çağrılır. Canlı sesli mod turun bittiğini (dinlemeye dönüş) buradan öğrenir. */
    var onAssistantComplete: ((AgentMessage) -> Unit)? = null

    // MARK: - Sesli mod köprüsü (LiveVoiceSession bunları bağlar)

    /** True iken turlar `mode:"voice"` ile gönderilir (RAG dar, speak öne alınır). */
    var voiceMode = false
    /** Cümle cümle konuşma metni (`speak_delta`) — TTS kuyruğuna EKLE. */
    var onSpeakDelta: ((String) -> Unit)? = null
    /** Monolitik `speak` — yalnız o role için delta GÖRÜLMEDİYSE çağrılır. */
    var onSpeakFull: ((String) -> Unit)? = null
    /** Yapısal seçenekler (patient_choices/entity_choices) — zengin alanlı, regex tahmini YOK. */
    var onChoices: ((List<AgentChoice>) -> Unit)? = null
    /** Yazma onayı geldiğinde (sesli modda tam ekran kart + biyometrik onay için). */
    var onWritePreview: ((AgentWritePreview) -> Unit)? = null
    /** İlerleme olayları (queue/schema_link/thinking/tool_result/heartbeat) — sesli ekran ilerleme rayı için. */
    var onProgress: ((VoiceProgress) -> Unit)? = null
    /** Bu tur içinde `speak_delta` yayan role'ler; tur başında temizlenir. */
    private val rolesWithDelta = mutableSetOf<String>()

    // MARK: - Tur kimliği + kompozer hatası
    /** Her tur benzersiz kimlik alır; finishStreaming yalnız KENDİ turunu bitirir. */
    private var currentTurnId: UUID = UUID.randomUUID()
    /** Son gönderilen kullanıcı metni — retry için korunur. */
    var lastUserText = ""
        private set
    /** Kompozer altında gösterilecek hata (null = yok). */
    var composerError by mutableStateOf<String?>(null)
    /** Son turun `credits` olayındaki kullanılabilir kredi (null = henüz bilinmiyor). */
    var lastAvailableCredits by mutableStateOf<Double?>(null)
        private set

    // MARK: - Lifecycle

    fun onAppear() {
        scope.launch {
            agentOnline = AgentClient.shared.health()
            loadConversations()
            if (!speech.isAuthorized) speech.requestAuthorization()
        }
    }

    fun onDisappear() {
        stop()
        if (isListening) { speech.stopRecording(); isListening = false }
    }

    /** Ekran kapanınca coroutine'leri bırakır (Compose `DisposableEffect`). */
    fun dispose() {
        onDisappear()
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

    /** Sesli moddan tur gönderir (mode:"voice"). Geçmişi sohbet ile paylaşır. */
    fun sendVoice(text: String) {
        if (isStreaming) return
        sendMessage(text)
    }

    /**
     * Belirsizlik kartı seçimi. Kimlik VARSA seçim `pick`/`patientPick` ile id olarak taşınır
     * (backend yeniden aramaz); YOKSA geriye uyum için metin gider.
     */
    fun sendChoice(choice: AgentChoice) {
        if (isStreaming) return
        // Ekranda görünen/geçmişe yazılan metin: kartın adı.
        val message = choice.title
        val id = choice.recordId
        if (!id.isNullOrEmpty()) {
            val table = choice.table
            if (table == "patients") {
                // Backend patientPick'i term=name ile pick'e çevirir → name boş olmamalı.
                sendMessage(message, patientPick = AgentClient.PatientPick(id, choice.title))
            } else if (!table.isNullOrEmpty()) {
                // pick.term backend'de ZORUNLU + boşsa düşer → olay term'i, yoksa ad.
                val term = choice.term?.takeIf { it.isNotEmpty() } ?: choice.title
                sendMessage(message, pick = AgentClient.PickPayload(table, term, id, choice.title))
            } else {
                sendMessage(choice.sendText)   // tablo yok → metin yolu (geriye uyum)
            }
        } else {
            // Kimlik yok (eski backend / düz string) → metin yolu.
            sendMessage(choice.sendText)
        }
    }

    private fun sendMessage(
        text: String,
        pick: AgentClient.PickPayload? = null,
        patientPick: AgentClient.PatientPick? = null,
    ) {
        // Önceki tur hâlâ sarılıyorsa iptal et (yoksa eski finishStreaming yeni turun bayrağını düşürür).
        streamTask?.cancel()
        lastUserText = text
        composerError = null
        val turnId = UUID.randomUUID()
        currentTurnId = turnId
        val assistant = AgentMessage(role = AgentRole.assistant, streaming = true)
        messages = messages + AgentMessage(role = AgentRole.user, content = text) + assistant
        val assistantId = assistant.id
        isStreaming = true
        rolesWithDelta.clear()
        elapsedSeconds = 0
        tickTask?.cancel()
        tickTask = scope.launch {
            while (isActive) {
                delay(1000)
                if (!isActive) break
                elapsedSeconds += 1
            }
        }
        streamTask = scope.launch { runStream(text, assistantId, turnId, pick, patientPick) }
    }

    private suspend fun runStream(
        text: String,
        assistantId: UUID,
        turnId: UUID,
        pick: AgentClient.PickPayload? = null,
        patientPick: AgentClient.PatientPick? = null,
    ) {
        try {
            AgentClient.shared.streamChat(
                message = text, conversationId = conversationId, mode = if (voiceMode) "voice" else null,
                pick = pick, patientPick = patientPick,
            ).collect { event ->
                if (!currentCoroutineContext().isActive) return@collect
                handle(event, assistantId)
            }
        } catch (e: CancellationException) {
            // İptal (durdur / yeni tur): hata yazılmaz; tur yine de kapatılır.
        } catch (credit: CreditRequired) {
            // 402: sunucu turu BAŞLATMADI (kredi düşülmedi). Ham kod yok — Türkçe metin +
            // balonda kredi kartı; sesli mod aynı alanı `onAssistantComplete`te okur.
            applyCreditRequired(credit, assistantId)
        } catch (e: Throwable) {
            val msg = errorText(e)
            if (msg.isNotEmpty()) {
                update(assistantId) { m -> m.copy(isError = true, content = if (m.content.isEmpty()) msg else m.content) }
            }
            // Başarısız tur yalnızca modelin meşgul olduğunu gösterebilir — afiş gerçeği yansıtsın diye sağlığı yeniden denetle.
            scope.launch { agentOnline = AgentClient.shared.health() }
        }
        finishStreaming(assistantId, turnId)
    }

    private fun errorText(error: Throwable): String {
        if (error is CancellationException) return ""
        if (error is AgentClient.AgentError) return error.errorDescription ?: "Bir hata oluştu."
        if (error is IOException) {
            if (error.message?.contains("Canceled", ignoreCase = true) == true) return ""
            return when (error) {
                is InterruptedIOException ->
                    "Yanıt çok uzun sürdü. Analitik sorular yerel modelde birkaç dakika sürebilir — tekrar deneyin veya daha basit sorun."
                is ConnectException, is UnknownHostException, is NoRouteToHostException, is SocketException ->
                    "Sunucuya ulaşılamadı. Backend ve ağ bağlantısını kontrol edin."
                else -> "Bağlantı hatası: ${error.localizedMessage ?: ""}"
            }
        }
        return "Bağlantı hatası oluştu."
    }

    private fun handle(ev: AgentEvent, assistantId: UUID) {
        when (ev.type) {
            "conversation" -> ev.string("conversationId")?.let { conversationId = it }

            "thinking" -> {
                update(assistantId) { it.copy(thinkingText = ev.string("text")) }
                ev.string("text")?.let { t -> onProgress?.invoke(VoiceProgress.reasoning(t, ev.string("stage"))) }
            }

            "queue" -> onProgress?.invoke(VoiceProgress.queued(ev.int("position") ?: 0))

            "schema_link" -> {
                val tables = (ev.raw["tables"]?.arrayValue ?: emptyList()).mapNotNull {
                    it.stringValue ?: it.objectValue?.get("name")?.stringValue
                }
                onProgress?.invoke(VoiceProgress.retrieved(tables))
            }

            "examples" -> onProgress?.invoke(VoiceProgress.retrieved(emptyList()))   // yalnız adım ilerlet

            "heartbeat" -> onProgress?.invoke(VoiceProgress.heartbeat(ev.int("elapsedMs") ?: 0))

            "token" -> update(assistantId) { it.copy(content = it.content + (ev.string("text") ?: ""), thinkingText = null) }

            "speak_delta" -> {
                // Cümle cümle konuşma metni (yalnız sesli mod). EKLE anlamında.
                val role = ev.string("role") ?: "final"
                rolesWithDelta += role
                val final = ev.bool("final") ?: false
                val text = ev.string("text")
                if (!final && !text.isNullOrEmpty()) onSpeakDelta?.invoke(text)
            }

            "speak" -> {
                // Backend'in temiz (kimliksiz) metni. Ekranda write-preview başlığı;
                // sesli modda o role için delta GÖRÜLMEDİYSE sesli okunur.
                val text = ev.string("text")
                if (!text.isNullOrEmpty()) {
                    update(assistantId) { it.copy(speakText = text) }
                    val role = ev.string("role") ?: "final"
                    if (role !in rolesWithDelta) onSpeakFull?.invoke(text)
                }
            }

            "patient_choices", "entity_choices", "queue_choices" -> {
                val table = ev.string("table")
                val term = ev.string("term")
                val arr = ev.raw["choices"]?.arrayValue ?: ev.raw["options"]?.arrayValue ?: emptyList()
                val choices = arr.mapNotNull { item ->
                    val obj = item.objectValue
                    if (obj == null) {
                        // Düz string dizisi geldiyse (eski backend) sade karta düş.
                        item.stringValue?.let { AgentChoice.plain(it) }
                    } else {
                        AgentChoice.parse(obj, table, term)
                    }
                }
                if (choices.isNotEmpty()) onChoices?.invoke(choices)
            }

            "tool_result", "tool_call" -> {
                handleTool(ev, assistantId)
                onProgress?.invoke(VoiceProgress.tool(ev.string("name") ?: "", ev.string("summary") ?: "", ev.bool("ok") ?: true))
            }

            "chart", "chart_spec" -> AgentChartSpec.from(ev.raw)?.let { spec ->
                update(assistantId) { it.copy(charts = it.charts + spec) }
            }

            "write_preview" -> {
                val preview = AgentWritePreview(
                    operation = ev.string("operation"),
                    entity = ev.string("entity"),
                    risk = ev.string("risk"),
                    affectedCount = ev.int("affectedCount"),
                    requiresDoubleConfirm = ev.bool("requiresDoubleConfirm") ?: false,
                )
                update(assistantId) { it.copy(writePreview = preview) }
                onWritePreview?.invoke(preview)
            }

            "write_confirmation_required", "write_double_confirm" -> update(assistantId) { m ->
                val p = m.writePreview
                if (p == null) {
                    m.copy(
                        writePreview = AgentWritePreview(
                            null, null, null, null,
                            requiresDoubleConfirm = ev.type == "write_double_confirm" || (ev.bool("requiresDoubleConfirm") ?: false),
                        )
                    )
                } else if (ev.type == "write_double_confirm") {
                    m.copy(writePreview = p.copy(requiresDoubleConfirm = true))
                } else m
            }

            "write_result" -> update(assistantId) {
                it.copy(writeResult = AgentWriteResult.from(ev.string("status") ?: ""), writePreview = null)
            }

            "error" -> {
                // Kredi kodu taşıyan olay (eski demo olayı dahil) 402 ile AYNI yoldan geçer.
                val credit = CreditRequired.parse(JsonObject(ev.raw.mapValues { it.value.jsonElement }))
                if (credit != null) {
                    applyCreditRequired(credit, assistantId)
                } else {
                    update(assistantId) {
                        it.copy(isError = true, content = ev.string("message") ?: "Bir hata oluştu.", thinkingText = null)
                    }
                }
            }

            "credits" -> {
                // `{type:"credits", charged, availableCredits}` — `end`ten hemen önce gelir.
                ev.raw["availableCredits"]?.doubleValue?.let { available ->
                    lastAvailableCredits = available
                    SmileCreditsStore.shared.noteBalanceChanged()
                }
            }

            "done", "end" -> Unit

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
            val file = AgentExcelArtifact(
                filename = art["filename"]?.stringValue ?: "rapor.xlsx",
                downloadUrl = art["downloadUrl"]?.stringValue ?: "",
                fileId = art["fileId"]?.stringValue,
                rowCount = art["rowCount"]?.doubleValue?.toInt(),
            )
            if (file.downloadUrl.isNotEmpty()) {
                update(assistantId) { it.copy(excelFiles = it.excelFiles + file) }
            }
        } else if (name.contains("chart") || artType == "chart") {
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
        scope.launch { loadConversations() }
    }

    /** Kompozer hata çubuğundan "Tekrar dene". */
    fun retryLast() {
        if (isStreaming || lastUserText.isEmpty()) return
        sendMessage(lastUserText)
    }

    /**
     * 402 (ya da kredi kodlu SSE `error`) → balon: Türkçe metin + kredi kartı. Tek yol;
     * sohbet, sesli mod ve Tedavi Planı aynı alanı okur. Bakiye göstergesi tazelenir.
     */
    private fun applyCreditRequired(credit: CreditRequired, assistantId: UUID) {
        update(assistantId) {
            it.copy(isError = true, creditRequired = credit, content = credit.message, thinkingText = null)
        }
        val available = credit.availableCredits
        if (credit.kind == CreditRequired.Kind.insufficient && available != null) {
            lastAvailableCredits = available
        }
        SmileCreditsStore.shared.noteBalanceChanged()
    }

    private fun update(id: UUID, mutate: (AgentMessage) -> AgentMessage) {
        val list = messages
        val idx = list.indexOfFirst { it.id == id }
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
    }

    // MARK: - Write confirm

    fun confirmWrite(doubleConfirm: Boolean) {
        sendMessage(if (doubleConfirm) "Evet, kalıcı olarak onaylıyorum." else "Onayla")
    }

    fun cancelWrite() {
        sendMessage("Vazgeç")
    }

    // MARK: - Sesli dikte (kompozeri doldurur, otomatik GÖNDERMEZ; kullanıcı düzeltip gönderir)

    fun toggleDictation() {
        if (isListening) stopDictation() else startDictation()
    }

    fun startDictation() {
        if (isStreaming) return
        if (!speech.isAuthorized) {
            scope.launch {
                speech.requestAuthorization()
                if (speech.isAuthorized) startDictation()
            }
            return
        }
        // Kompozerde ne varsa onun sonundan devam et.
        dictationBase = draft
        speech.transcript = ""
        // Uzun sessizlik dinlemeyi kendiliğinden durdurur; metin kalır, hiçbir şey gönderilmez.
        speech.onAutoStop = { stopDictation() }
        if (speech.startRecording()) isListening = true
    }

    fun stopDictation() {
        if (!isListening) return
        isListening = false
        speech.stopRecording()
        // Yazıya dökülen metin zaten `draft`ta; kullanıcı düzenlesin ya da dikteye devam etsin.
    }

    /**
     * Canlı dökümü dikte başladığındaki metnin arkasına ekler (mikrofona tekrar basmak ezmez).
     * Dinleme sürerken `speech.transcript` her değiştiğinde görünüm çağırır.
     */
    fun applyDictationTranscript() {
        if (!isListening) return
        val t = speech.transcript
        draft = when {
            dictationBase.isEmpty() -> t
            t.isEmpty() -> dictationBase
            else -> dictationBase + (if (dictationBase.endsWith(" ")) "" else " ") + t
        }
    }

    // MARK: - Conversations

    suspend fun loadConversations() {
        conversations = AgentClient.shared.fetchConversations()
    }

    fun newChat() {
        stop()
        if (isListening) { speech.stopRecording(); isListening = false }
        messages = emptyList()
        conversationId = null
        draft = ""
    }

    fun openConversation(conv: AgentConversation) {
        stop()
        conversationId = conv.id
        scope.launch {
            val loaded = AgentClient.shared.fetchConversationMessages(conv.id)
            messages = loaded.map { (role, content) -> AgentMessage(role = role, content = content) }
        }
    }

    fun deleteConversation(conv: AgentConversation) {
        scope.launch {
            AgentClient.shared.deleteConversation(conv.id)
            if (conversationId == conv.id) newChat()
            loadConversations()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentSessionManagerPreview() {
    val vm = remember {
        AgentSessionManager().apply {
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
