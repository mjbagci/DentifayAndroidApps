package com.dentifay.app.treatment

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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.agent.AgentMessage
import com.dentifay.app.agent.AgentSessionManager
import com.dentifay.app.agent.AgentWritePreview
import com.dentifay.app.agent.AgentWriteResult
import com.dentifay.app.core.APIAppointment
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.Haptics
import com.dentifay.app.voice.SpeechManager
import com.dentifay.app.voice.TTSPipeline
import com.dentifay.app.voice.VoiceProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sesli Tedavi Planı asistanı — **AI Agent mimarisi** üzerinden çalışır (`/api/ai-assistant/chat`, mode:"voice").
 * Eski `/ai/analyze-transcript` yolu KULLANILMAZ.
 *
 * Akış: hasta seç → tedavi planını sesle söyle → agent planı DB'ye yazmak üzere hazırlar
 * (write_preview + sesli özet) → Onayla (agent commit eder) / Tekrar söyle.
 */
class TreatmentPlanViewModel {

    sealed class Phase {
        data object pickPatient : Phase()
        data object recording : Phase()
        data object thinking : Phase()
        data object review : Phase()
        data object submitting : Phase()
        data object done : Phase()
        data class failed(val message: String) : Phase()
    }

    data class PatientRef(val id: String, val name: String, val subtitle: String?)

    // MARK: Durum
    var phase by mutableStateOf<Phase>(Phase.pickPatient)

    // Hasta seçimi
    var loadingLists by mutableStateOf(true)
    var todayPatients by mutableStateOf<List<PatientRef>>(emptyList())
    var weekPatients by mutableStateOf<List<PatientRef>>(emptyList())
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<PatientRef>>(emptyList())
    var isSearching by mutableStateOf(false)
    var selectedPatient by mutableStateOf<PatientRef?>(null)
        private set

    // Kayıt (dikte)
    val speech = SpeechManager()
    var isListening by mutableStateOf(false)
    val transcript: String get() = speech.transcript

    // Agent turu
    var progressText by mutableStateOf("Sorunuz gönderildi")
    val elapsedSeconds: Int get() = agent.elapsedSeconds
    var writePreview by mutableStateOf<AgentWritePreview?>(null)
        private set
    var planSummary by mutableStateOf("")
    /** Tur kredi yüzünden başlamadıysa (402) — hata ekranında kredi kartı çizilir. */
    var creditRequired by mutableStateOf<CreditRequired?>(null)
        private set
    /** Seslendirme bilgisi (ör. günlük hak doldu → özet cihaz sesiyle okunuyor). null = yok. */
    var speechNotice by mutableStateOf<String?>(null)
        private set

    // Dahili
    private val agent = AgentSessionManager()
    private var spoken = ""
    private var ttsPlayer: MediaPlayer? = null
    /** Sunucu seslendirmesi kullanılamazsa (günlük hak / erişim) cihaz sesi. */
    private var deviceSynth: TextToSpeech? = null
    private var searchTask: Job? = null
    private var wired = false
    private val scope = MainScope()

    companion object {
        private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val timeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.forLanguageTag("tr-TR"))

        private fun patients(from: List<APIAppointment>): List<PatientRef> {
            val seen = HashSet<String>()
            val out = mutableListOf<PatientRef>()
            for (a in from) {
                val p = a.patient ?: continue
                if (!seen.add(p.id)) continue
                val name = "${p.firstName} ${p.lastName}".trim()
                var subtitle: String? = p.phone
                runCatching { Instant.parse(a.startAt) }.getOrNull()?.let { d ->
                    subtitle = timeFormatter.format(d.atZone(ZoneId.systemDefault()))
                }
                out += PatientRef(p.id, name.ifEmpty { "İsimsiz hasta" }, subtitle)
            }
            return out
        }
    }

    init {
        speech.autoStopOnSilence = false   // manuel bitir — erken kesme yok
    }

    // MARK: - Yükleme

    fun onAppear() {
        scope.launch {
            if (!speech.isAuthorized) speech.requestAuthorization()
            loadInitial()
        }
    }

    private suspend fun loadInitial() {
        loadingLists = true
        val today = LocalDate.now()
        val start = dayFormatter.format(today)
        val tomorrow = today.plusDays(1)
        val weekEnd = today.plusDays(7)

        val todayAppts = runCatchingNonCancel { ApiClient.fetchAppointments(start, dayFormatter.format(tomorrow)) } ?: emptyList()
        val weekAppts = runCatchingNonCancel {
            ApiClient.fetchAppointments(dayFormatter.format(tomorrow), dayFormatter.format(weekEnd))
        } ?: emptyList()

        todayPatients = patients(todayAppts)
        val todayIds = todayPatients.map { it.id }.toSet()
        weekPatients = patients(weekAppts).filter { it.id !in todayIds }
        loadingLists = false
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // MARK: - Arama

    fun searchChanged() {
        searchTask?.cancel()
        val q = searchQuery.trim()
        if (q.length < 2) { searchResults = emptyList(); isSearching = false; return }
        isSearching = true
        searchTask = scope.launch {
            delay(300)
            if (!isActive) return@launch
            val results = runCatchingNonCancel { ApiClient.searchPatients(q) } ?: emptyList()
            if (!isActive) return@launch
            searchResults = results.map { PatientRef(it.id, "${it.firstName} ${it.lastName}".trim(), it.phone) }
            isSearching = false
        }
    }

    // MARK: - Kayıt

    fun selectPatient(p: PatientRef) {
        selectedPatient = p
        beginRecording()
    }

    fun beginRecording() {
        writePreview = null; planSummary = ""; spoken = ""
        creditRequired = null; speechNotice = null
        speech.transcript = ""
        phase = Phase.recording
        scope.launch {
            if (!speech.isAuthorized) { phase = Phase.failed("Mikrofon izni gerekli."); return@launch }
            if (speech.startRecording()) isListening = true
            else phase = Phase.failed(speech.errorMessage ?: "Kayıt başlatılamadı.")
        }
    }

    /** Konuşmayı modele göndermeden sıfırla, baştan dinle. */
    fun restartRecording() {
        Haptics.rigid()
        speech.stopRecording()
        speech.transcript = ""
        isListening = false
        beginRecording()
    }

    /** Kaydı bitir → agent'a gönder. */
    fun finishAndSend() {
        val text = speech.transcript.trim()
        speech.stopRecording()
        isListening = false
        if (text.isEmpty()) { phase = Phase.failed("Bir şey söylemediniz. Tekrar deneyin."); return }
        Haptics.selection()
        wireAgentIfNeeded()
        spoken = ""
        progressText = "Sorunuz gönderildi"
        phase = Phase.thinking
        val name = selectedPatient?.name ?: ""
        val msg = "$name adlı hasta için yeni bir tedavi planı oluştur. " +
            "Hekimin söylediği tedaviler: \"$text\". " +
            "Diş numaralarını ve tedavi türlerini hizmet kataloğuyla eşleştir, fiyatlandır ve planı kaydet."
        agent.sendVoice(msg)
    }

    // MARK: - Agent köprüsü

    private fun wireAgentIfNeeded() {
        if (wired) return
        wired = true
        agent.voiceMode = true
        agent.onSpeakDelta = { t -> spoken += if (spoken.isEmpty()) t else " $t" }
        agent.onSpeakFull = { t -> spoken = t }
        agent.onWritePreview = { pv -> writePreview = pv }
        agent.onAssistantComplete = { msg -> handleTurnComplete(msg) }
        agent.onProgress = { p -> applyProgress(p) }
    }

    private fun applyProgress(p: VoiceProgress) {
        when (p) {
            is VoiceProgress.queued -> progressText = if (p.position > 0) "Sırada bekliyorsunuz — önünüzde ${p.position} kişi" else "Bağlanıldı"
            is VoiceProgress.retrieved -> progressText = "İlgili kayıtlar hazırlanıyor…"
            is VoiceProgress.reasoning -> progressText = p.text
            is VoiceProgress.tool -> progressText = if (p.ok) p.summary.ifEmpty { "İşleniyor…" } else "Düzeltiliyor…"
            is VoiceProgress.heartbeat -> Unit
        }
    }

    private fun handleTurnComplete(msg: AgentMessage) {
        // 402: tur hiç başlamadı (kredi düşülmedi) → kredi kartlı hata ekranı.
        msg.creditRequired?.let { credit ->
            creditRequired = credit
            Haptics.warning()
            phase = Phase.failed(credit.message)
            return
        }
        // Yazma sonucu geldiyse (onay turunun sonu)
        msg.writeResult?.let { wr ->
            if (wr == AgentWriteResult.committed) {
                planSummary = spoken.ifEmpty { msg.speakText ?: msg.content }
                Haptics.success(); phase = Phase.done
            } else {
                phase = Phase.failed(msg.content.ifEmpty { "İşlem tamamlanamadı." })
            }
            return
        }
        // Yazma önizlemesi hazır → gözden geçir + sesli özet
        if (writePreview != null) {
            planSummary = spoken.ifEmpty { msg.speakText ?: msg.content }
            phase = Phase.review
            speak(planSummary)
            return
        }
        // Ne write_result ne preview → agent yazmadı (soru sordu / hata)
        if (phase == Phase.thinking) {
            val text = spoken.ifEmpty { msg.speakText?.takeIf { it.isNotEmpty() } ?: msg.content }
            phase = Phase.failed(text.ifEmpty { "Tedavi planı oluşturulamadı. Tekrar deneyin." })
        }
    }

    // MARK: - Onay / tekrar

    fun confirm() {
        val pv = writePreview ?: return
        stopTTS()
        spoken = ""
        progressText = "Kaydediliyor…"
        phase = Phase.submitting
        agent.confirmWrite(pv.requiresDoubleConfirm)
    }

    /** Hiçbir veri girmeden yeniden söyle. */
    fun retryDictation() {
        stopTTS()
        if (writePreview != null) agent.cancelWrite()
        writePreview = null
        beginRecording()
    }

    fun cancelThinking() {
        agent.stop()
        beginRecording()
    }

    // MARK: - TTS (anlaşılan planı seslendir)

    private fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            try {
                val data = ApiClient.synthesizeSpeech(clean, "tr")
                val ctx = Prefs.appContext ?: return@launch
                val file = withContext(Dispatchers.IO) {
                    File(ctx.cacheDir, "treatment-plan-tts.mp3").apply { writeBytes(data) }
                }
                stopTTS()
                ttsPlayer = runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(file.absolutePath)
                        prepare()
                        start()
                    }
                }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sessiz kalma: özet ekranda; nedeni söylenir ve cihaz sesiyle okunur.
                if (e is ApiException.SpeechQuotaExceeded) {
                    speechNotice = "Bugünkü seslendirme hakkı doldu; özet cihaz sesiyle okunuyor."
                } else if (e is CreditRequired) {
                    speechNotice = e.message
                }
                speakOnDevice(TTSPipeline.stripMarkdown(clean))
            }
        }
    }

    private fun speakOnDevice(text: String) {
        val ctx = Prefs.appContext ?: return
        deviceSynth?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { status ->
            val e = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            e.language = Locale.forLanguageTag("tr-TR")
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "treatment-plan-summary")
        }
        deviceSynth = engine
    }

    private fun stopTTS() {
        ttsPlayer?.let { runCatching { it.stop() }; it.release() }
        ttsPlayer = null
        deviceSynth?.let { if (it.isSpeaking) it.stop() }
    }

    fun teardown() {
        searchTask?.cancel()
        speech.stopRecording()
        isListening = false
        agent.stop()
        agent.onSpeakDelta = null; agent.onSpeakFull = null
        agent.onWritePreview = null; agent.onAssistantComplete = null; agent.onProgress = null
        stopTTS()
    }

    /** Ekran kapanınca: teardown + coroutine'leri ve cihaz sesini bırak. */
    fun dispose() {
        teardown()
        deviceSynth?.shutdown()
        deviceSynth = null
        agent.dispose()
        scope.cancel()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanViewModelPreview() {
    val vm = remember {
        TreatmentPlanViewModel().apply {
            todayPatients = listOf(TreatmentPlanViewModel.PatientRef("p1", "Ayşe Yılmaz", "16 Eki · 10:30"))
        }
    }
    PreviewEnv {
        Column(Modifier.padding(16.dp)) {
            vm.todayPatients.forEach { Text("${it.name} · ${it.subtitle}", style = sys(14), color = DS.Colors.onSurface) }
        }
    }
}
