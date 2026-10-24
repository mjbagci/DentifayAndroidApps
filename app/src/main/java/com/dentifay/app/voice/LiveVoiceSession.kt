package com.dentifay.app.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.agent.AgentChoice
import com.dentifay.app.agent.AgentClient
import com.dentifay.app.agent.AgentMessage
import com.dentifay.app.agent.AgentSessionManager
import com.dentifay.app.agent.AgentWritePreview
import com.dentifay.app.auth.BiometricAuth
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.Haptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Canlı sesli asistan orkestratörü. Ses grafiği + STT merdiveni + VAD/endpointing
 * + Azure TTS hattı + AgentSessionManager köprüsünü tek bir durum makinesinde birleştirir.
 *
 * Tasarım kuralları:
 * - Oturum içinde ses oturumu yapılandırması **hiç değişmez** (AudioSessionCoordinator).
 * - **VAD veto**: kullanıcı hâlâ konuşuyorsa endpoint ASLA tetiklenmez.
 * - **Barge-in**: asistan konuşurken kullanıcı orb'a dokunursa kuyruk temizlenir.
 * - Ham ses mümkün olduğunca cihazdan çıkmaz; yalnız temiz `speak` metni Azure'a gider.
 */
class LiveVoiceSession(private val vm: AgentSessionManager) {

    /** `credit`: sunucu turu kredi yüzünden başlatmadı (402) — kart gösterilir, mikrofon kapalı. */
    enum class Phase { starting, listening, thinking, speaking, confirming, credit, error, ended }

    // MARK: Gözlemlenen durum
    var phase by mutableStateOf(Phase.starting)
    var transcript by mutableStateOf("")          // kullanıcının canlı sözcesi
    var assistantText by mutableStateOf("")       // asistanın konuştuğu metin
    var choices by mutableStateOf<List<AgentChoice>>(emptyList())
    var pendingWrite by mutableStateOf<AgentWritePreview?>(null)
    var errorText by mutableStateOf<String?>(null)
    var micLevel by mutableDoubleStateOf(0.0)
    /** Kredi gerekli (402 · `CreditRequired`) — `phase == credit` iken dolu. */
    var creditRequired by mutableStateOf<CreditRequired?>(null)
    /** Seslendirme bilgisi (ör. günlük seslendirme hakkı doldu → cihaz sesi). null = yok. */
    var noticeText by mutableStateOf<String?>(null)

    // MARK: Bileşenler
    private val coordinator = AudioSessionCoordinator
    private val graph = VoiceAudioGraph()
    private val meter = VoiceEnergyMeter()
    private val tts = TTSPipeline(graph)
    private var pipeline: SpeechPipeline? = null
    private val micGate = MicGate()
    private val scope = MainScope()

    // MARK: İç durum
    private var active = false
    // Transkript iki parçada birikir: finalize edilmiş cümleler + o anki volatile.
    // (Uzun sözcede parça kaybını önler; manuel bitirmede tümü gönderilir.)
    private var finalizedText = ""
    private var volatileText = ""
    /** restartListening sonrası flush edilen eski segment yok sayılsın. */
    private var discardFinals = false
    private var streamComplete = false
    private var bringUpJob: Job? = null
    private var tickJob: Job? = null
    private var watchdog: Job? = null

    /** Modele gönderilecek/ekranda gösterilecek birleşik metin. */
    private val combinedTranscript: String
        get() {
            if (volatileText.isEmpty()) return finalizedText
            if (finalizedText.isEmpty()) return volatileText
            return "$finalizedText $volatileText"
        }

    // MARK: - Yaşam döngüsü

    fun start() {
        if (active) return
        active = true
        phase = Phase.starting
        wireCallbacks()
        bringUpJob = scope.launch { bringUp() }
    }

    private suspend fun bringUp() {
        // İzin
        if (!vm.speech.isAuthorized) vm.speech.requestAuthorization()
        if (!vm.speech.isAuthorized) { fail("Mikrofon/konuşma izni gerekli."); return }

        // TTS durumu (enabled:false ise cihaz fallback devreye girer, kullanıcı hata görmez)
        tts.checkStatus()
        if (!active) return

        // Ses oturumu (odak + iletişim modu)
        try {
            coordinator.acquire(AudioSessionCoordinator.Consumer.conversation)
        } catch (e: Exception) { fail("Ses oturumu başlatılamadı: ${e.localizedMessage ?: e}"); return }

        // STT merdiveni (diagnostik gerçeğine göre kademe)
        val ctx = Prefs.appContext ?: run { fail("Konuşma tanıma başlatılamadı."); return }
        val pipe = SpeechPipelineFactory.make(ctx)
        if (!active) { scope.launch { pipe.stop() }; return }
        pipeline = pipe
        pipe.onVolatile = { t -> handleVolatile(t) }
        pipe.onFinal = { t -> handleFinal(t) }
        pipe.onError = { _ -> beginListening() }
        // Mikrofonu kendisi açan kademe: seviye doğrudan ölçere.
        pipe.onLevel = { db, dt -> meter.processDb(db, dt) }

        // Biasing sözlüğü (hasta/doktor/hizmet/ürün adları)
        val lexicon = AgentClient.shared.fetchSTTLexicon()
        if (lexicon.isNotEmpty()) pipe.setContextualStrings(lexicon)
        if (!active) return

        // Grafiği başlat: tap → meter + pipeline (ses thread'i). Yalnız harici ses kullanan kademede.
        if (pipe.usesExternalAudio) {
            val meter = this.meter
            val gate = this.micGate
            try {
                graph.start { buffer, count, rate ->
                    meter.process(buffer, count, rate)
                    if (gate.isOpen()) pipe.ingest(buffer, count)
                }
            } catch (e: Exception) { fail("Ses motoru başlatılamadı: ${e.localizedMessage ?: e}"); return }
        }

        try { pipe.start() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail("Konuşma tanıma başlatılamadı."); return }

        // Rota'ya göre VAD marjı
        applyRoute(coordinator.currentRoute)

        // 500 ms kalibrasyon (gürültü tabanı) → dinleme
        delay(500)
        if (!active) return
        beginListening()
        startTicking()
    }

    fun stop() {
        if (!active) return
        active = false
        phase = Phase.ended
        bringUpJob?.cancel(); bringUpJob = null
        tickJob?.cancel(); tickJob = null
        watchdog?.cancel(); watchdog = null
        tts.stop()
        teardownAudio()
        coordinator.release(AudioSessionCoordinator.Consumer.conversation)
        vm.voiceMode = false
        vm.onSpeakDelta = null; vm.onSpeakFull = null
        vm.onChoices = null; vm.onWritePreview = null; vm.onAssistantComplete = null
        coordinator.onInterruptionBegan = null; coordinator.onInterruptionEnded = null
        coordinator.onRouteChanged = null; coordinator.onConfigurationChange = null
    }

    /** Ekran kapanınca: oturumu bitir ve iş kapsamlarını bırak. */
    fun dispose() {
        stop()
        tts.dispose()
        scope.cancel()
    }

    /** Tanıyıcı + ses grafiği. Tanıyıcının durdurulması askıya alındığı için kendi kapsamında
     *  koşar (ekran kapsamı iptal edilse bile mikrofon serbest kalsın). */
    private fun teardownAudio() {
        val p = pipeline
        pipeline = null
        if (p != null) CoroutineScope(Dispatchers.Main).launch { p.stop() }
        graph.stop()
    }

    // MARK: - Callback bağlama

    private fun wireCallbacks() {
        vm.voiceMode = true
        vm.onSpeakDelta = { text -> enqueueSpeech(text) }
        vm.onSpeakFull = { text -> enqueueSpeech(text) }
        vm.onChoices = { list -> choices = list }
        vm.onWritePreview = { preview -> showConfirm(preview) }
        vm.onAssistantComplete = { msg -> turnStreamFinished(msg) }

        tts.onStartedSpeaking = { onSpeakingStarted() }
        tts.onFinishedAll = { onSpeakingFinished() }
        // Günlük seslendirme hakkı doldu / seslendirme kredi istedi → cihaz sesine düşülür,
        // metin ekranda kalır; kullanıcıya NEDENİ söylenir (sessizlik ya da ham hata yok).
        tts.onNotice = { text -> noticeText = text }
        // Mikrofon geçidi tamamen faza bağlı (tick). Fallback sırasında da faz .speaking
        // olduğu için geçit zaten kapalı — ayrı toggle gerekmez.

        coordinator.onInterruptionBegan = { tts.flush() }
        coordinator.onInterruptionEnded = { resume -> if (resume) beginListening() }
        coordinator.onRouteChanged = { reason, route ->
            // Kulaklık çıkarıldı → hasta bilgisi bekleme salonuna okunmasın.
            if (reason == AudioSessionCoordinator.RouteChangeReason.oldDeviceUnavailable) tts.flush()
            applyRoute(route)
        }
        coordinator.onConfigurationChange = {
            runCatching { graph.rebuild() }
        }
    }

    // MARK: - STT sonuçları

    private fun handleVolatile(text: String) {
        if (!active || phase != Phase.listening || discardFinals) return
        volatileText = text
        transcript = combinedTranscript
    }

    private fun handleFinal(text: String) {
        // 🔴 Faz koruması: yalnız dinleme fazında; düşünme/konuşmadaki geç finalize
        // bir sonraki turu tetiklemesin.
        if (!active || phase != Phase.listening) return
        if (discardFinals) { discardFinals = false; volatileText = ""; return }
        if (text.isNotEmpty()) {
            finalizedText += if (finalizedText.isEmpty()) text else " $text"
        }
        volatileText = ""
        transcript = combinedTranscript
    }

    // MARK: - Endpointing (tick döngüsü)

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(50)
                if (!active) break
                tick()
            }
        }
    }

    private fun tick() {
        val snap = meter.snapshot()
        micLevel = snap.level.toDouble()
        // 🔴 Mikrofon YALNIZ dinleme fazında açık. Düşünme/konuşma/onay sırasında
        // kapalı → pipeline ses almaz, geç finalize sızmaz. Cevap bitince
        // beginListening() otomatik geri açar.
        // ⚠️ OTOMATİK ENDPOINT YOK — kullanıcı "BİTİR & GÖNDER" butonuyla bitirir.
        val open = phase == Phase.listening
        micGate.set(open)
        pipeline?.setMicOpen(open)
    }

    /** Kullanıcı konuşmasını bitirdi → finalize edip modele gönder. */
    fun finishListening() {
        if (phase != Phase.listening) return
        val text = combinedTranscript.trim()
        if (text.isEmpty()) return
        Haptics.selection()
        finalizedText = ""; volatileText = ""; transcript = ""
        assistantText = ""
        choices = emptyList()
        streamComplete = false
        phase = Phase.thinking
        scope.launch { pipeline?.endpoint() }
        vm.sendVoice(text)
        armThinkingWatchdog()
    }

    /** Konuşmayı modele GÖNDERMEDEN at ve sıfırdan dinlemeye devam et. */
    fun restartListening() {
        if (phase != Phase.listening) return
        Haptics.rigid()
        finalizedText = ""; volatileText = ""; transcript = ""
        // Tanıyıcının o ana kadarki segmentini kapat, sonucunu yok say.
        discardFinals = true
        scope.launch { pipeline?.endpoint(); discardFinals = false }
    }

    // MARK: - Konuşma (TTS)

    private fun enqueueSpeech(text: String) {
        if (!active) return
        assistantText = if (assistantText.isEmpty()) text else "$assistantText $text"
        tts.enqueue(text)
    }

    private fun onSpeakingStarted() {
        if (!active || !(phase == Phase.thinking || phase == Phase.confirming)) return
        watchdog?.cancel(); watchdog = null
        if (phase == Phase.thinking) phase = Phase.speaking
    }

    private fun onSpeakingFinished() {
        if (!active) return
        graph.setPlayerVolume(1f)
        // Akış da bittiyse ve onay beklemiyorsak dinlemeye dön.
        if (streamComplete && phase == Phase.speaking) beginListening()
    }

    private fun turnStreamFinished(message: AgentMessage) {
        // 402: tur hiç başlamadı → dinlemeye SESSİZCE dönme; kartı göster.
        val credit = message.creditRequired
        if (credit != null) {
            showCreditRequired(credit)
            return
        }
        streamComplete = true
        // Hiç konuşma olmadıysa (sadece araç sonucu vb.) düşünmeden dinlemeye dön.
        if (phase == Phase.thinking) beginListening()
    }

    // MARK: - Kredi gerekli (402)

    private fun showCreditRequired(credit: CreditRequired) {
        if (!active) return
        watchdog?.cancel(); watchdog = null
        tts.flush()
        pendingWrite = null
        choices = emptyList()
        creditRequired = credit
        assistantText = credit.message
        phase = Phase.credit
        Haptics.warning()
        // Eller serbest kullanım: kısa sözlü bildirim (seslendirme ücretsiz; hak dolduysa cihaz sesi).
        tts.enqueue(credit.spokenText)
    }

    /** Kredi kartını kapat → dinlemeye dön. */
    fun dismissCreditRequired() {
        if (phase != Phase.credit) return
        tts.flush()
        creditRequired = null
        assistantText = ""
        beginListening()
    }

    /** Kredi yüklendikten sonra son sözceyi yeniden gönder (sesli tur olarak). */
    fun retryAfterCredit() {
        if (phase != Phase.credit) return
        tts.flush()
        creditRequired = null
        if (vm.isStreaming || vm.lastUserText.isEmpty()) { beginListening(); return }
        assistantText = ""
        streamComplete = false
        phase = Phase.thinking
        vm.retryLast()
        armThinkingWatchdog()
    }

    // MARK: - Yazma onayı

    private fun showConfirm(preview: AgentWritePreview) {
        if (!active) return
        pendingWrite = preview
        phase = Phase.confirming
        Haptics.warning()
    }

    fun confirmWrite() {
        val p = pendingWrite ?: return
        if (p.requiresDoubleConfirm) {
            scope.launch {
                val ok = BiometricAuth.authenticate("Kalıcı işlemi onaylamak için kimliğinizi doğrulayın")
                if (!ok) return@launch
                proceedConfirm(double = true)
            }
        } else {
            proceedConfirm(double = false)
        }
    }

    private fun proceedConfirm(double: Boolean) {
        pendingWrite = null
        streamComplete = false
        phase = Phase.thinking
        vm.confirmWrite(doubleConfirm = double)
        armThinkingWatchdog()
    }

    fun cancelWrite() {
        pendingWrite = null
        streamComplete = false
        phase = Phase.thinking
        vm.cancelWrite()
        armThinkingWatchdog()
    }

    // MARK: - Kullanıcı aksiyonları

    fun chooseOption(choice: AgentChoice) {
        if (!active || vm.isStreaming) return
        tts.flush()
        choices = emptyList()
        assistantText = ""
        streamComplete = false
        phase = Phase.thinking
        vm.sendChoice(choice)   // kimlik varsa pick/patientPick ile id; yoksa metin
        armThinkingWatchdog()
    }

    /** Düşünme sürecini iptal et — backend turunu durdurur, dinlemeye döner. */
    fun cancelThinking() {
        if (phase != Phase.thinking) return
        Haptics.rigid()
        watchdog?.cancel(); watchdog = null
        vm.stop()          // backend akışı kapanır
        tts.flush()
        beginListening()
    }

    /** Orb'a dokunma: konuşuyorsa kes → dinle; dinliyorsa bitir & gönder; hatada tekrar dene. */
    fun tapOrb() {
        when (phase) {
            Phase.speaking -> { tts.flush(); beginListening() }
            Phase.listening -> finishListening()
            Phase.credit -> dismissCreditRequired()
            Phase.error -> retry()
            else -> Unit
        }
    }

    fun retry() {
        if (phase != Phase.error) return
        errorText = null
        phase = Phase.starting
        active = false
        // Yarım kalan açılışın tanıyıcısı/grafiği mikrofonu tutmasın (Android'de tek mikrofon).
        bringUpJob?.cancel(); bringUpJob = null
        teardownAudio()
        start()
    }

    // MARK: - Yardımcılar

    private fun beginListening() {
        if (!active) return
        watchdog?.cancel(); watchdog = null
        graph.setPlayerVolume(1f)
        finalizedText = ""; volatileText = ""; discardFinals = false
        transcript = ""
        creditRequired = null
        phase = Phase.listening
    }

    private fun armThinkingWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(90_000)
            if (phase != Phase.thinking) return@launch
            fail("Yanıt zaman aşımına uğradı. Tekrar deneyin.")
        }
    }

    private fun applyRoute(route: AudioSessionCoordinator.AudioRoute) {
        when (route) {
            AudioSessionCoordinator.AudioRoute.bluetoothHFP, AudioSessionCoordinator.AudioRoute.bluetoothA2DP,
            AudioSessionCoordinator.AudioRoute.wired -> meter.setMargin(9f)
            AudioSessionCoordinator.AudioRoute.builtIn -> meter.setMargin(12f)
        }
    }

    private fun fail(message: String) {
        errorText = message
        phase = Phase.error
    }
}

/** Ses thread'indeki tap ile ana thread arasında mikrofon geçidi. */
private class MicGate {
    @Volatile private var open = true
    fun isOpen(): Boolean = open
    fun set(value: Boolean) { open = value }
}
