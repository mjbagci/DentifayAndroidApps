package com.dentifay.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.dentifay.app.agent.AgentClient
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Dikte oturumu — **modern ses yığınının** ince sarmalayıcısı (Tedavi Planı kaydı ve Asistan kompozeri).
 *
 * Eski `SpeechManager` KENDİ tanıyıcısını ve ses oturumu çağrılarını yönetiyordu; yani uygulamada iki paralel
 * ses yığını vardı ve ikisi aynı süreç-geneli ses oturumunu paylaşıyordu. `AudioSessionCoordinator` bu mimariyi
 * açıkça yasaklıyor. Bu sınıf o ikinci yığını KALDIRIR:
 *
 *   AudioSessionCoordinator (tek oturum sahibi, refcount)
 *     → VoiceAudioGraph      (tek kayıt grafiği + AEC + TTS oynatıcısı)
 *        → SpeechPipeline    (cihaz-içi STT merdiveni; ham ses mümkünse cihazdan ÇIKMAZ)
 *        → VoiceEnergyMeter  (orb seviyesi)
 *        → TTSPipeline       (aynı grafiğin oynatıcısından çalar)
 *
 * Kazanılanlar: `setContextualStrings` ile **STT biasing** (hasta/hekim/hizmet adları), cümle kuyruklu +
 * `TTS_TEXT_TOO_LONG` bölmeli + yedekli TTS, rota/yapılandırma değişimi bildirimi.
 *
 * Android notları: Kademe 2 tanıyıcısı (`LegacySpeechPipeline`) mikrofonu KENDİSİ açar — kayıt grafiği yalnız
 * dışarıdan ses alan Kademe 1'de çalışır (tek mikrofon), Kademe 2'nin geçidi `setMicOpen` ile yürür ve seviyesi
 * `onLevel` ile ölçere gelir. Oturum açık kalsa da mikrofon YALNIZ dinlerken tutulur (Kademe 1'in kaydı dinleme
 * bitince durur): sistemin mikrofon göstergesi önizlemede yanmaz, başka uygulamalar mikrofonu kullanabilir.
 * Dinleme bitince `endpoint()` kesinleştirirken gelen eski sözce yeni dinlemeye sızmaz.
 */
class DictationSession {

    // MARK: - Gözlemlenen durum
    var transcript by mutableStateOf("")
    var isListening by mutableStateOf(false)
    var isAuthorized by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    /** 0…1 — orb/dalga formu için (VoiceEnergyMeter'dan). */
    var audioLevel by mutableDoubleStateOf(0.0)

    /** Uzun sessizlikte dinlemeyi kendiliğinden bitir (metin KORUNUR, gönderilmez).
     *  Tedavi planı modülü bunu KAPATIR (hekim düşünürken kesilmesin). */
    var autoStopOnSilence = true
    /** Saniye. */
    var silenceTimeout: Double = 4.0
    var onAutoStop: (() -> Unit)? = null
    /** Metin her değiştiğinde (volatile dahil) — kompozit taslağına akıtmak için. */
    var onTranscriptChange: ((String) -> Unit)? = null
    /** Biasing sözlüğünün kovaları — Tedavi Planı yalnız hekim + tedavi alır (M-E1.2.10). */
    var sozlukKovalari: List<String> = AgentClient.tumKovalar

    // MARK: - Ses yığını (tek grafik; TTS de buradan çalar)
    val graph: VoiceAudioGraph = VoiceAudioGraph()
    val tts: TTSPipeline = TTSPipeline(graph)

    private val coordinator = AudioSessionCoordinator
    private val meter = VoiceEnergyMeter()
    private val gate = DictationMicGate()
    private var pipeline: SpeechPipeline? = null
    /** Kademe 1'in kayıt tap'i (ölçer + geçitli besleme) — dinleme başlarken grafiğe takılır. */
    private var tap: VoiceAudioGraph.BufferCallback? = null
    private val scope = MainScope()
    /** Eşzamanlı iki `open()` (dikte + özet okuma) ses oturumunu iki kez kurmasın. */
    private val openLock = Mutex()

    // Transkript iki parçada birikir (uzun sözcede parça kaybını önler).
    private var finalizedText = ""
    private var volatileText = ""
    private var sessionOpen = false
    private var tickTask: Job? = null
    private var silenceTask: Job? = null
    private var lexiconLoaded = false
    private var ttsStatusChecked = false

    /** Kapanmakta olan sözcenin `endpoint()` sayısı — o sırada gelen sonuçlar eski sözcedir, YOK SAYILIR. */
    private var endpointInFlight = 0
    /** `close()` her çağrıda artar — sürmekte olan `open()` kapatıldığını anlar ve kurduğunu geri alır. */
    private var oturumNesli = 0
    /** Bu oturumun yapılandırma gözlemcisi (başka bir oturumunkini silmemek için kimliğiyle tutulur). */
    private var configHandler: (() -> Unit)? = null
    /** İzin istemi bu oturum için en çok bir kez gösterilir (iOS sistemi gibi). */
    private var askedOnce = false
    private var disposed = false

    private val combined: String
        get() {
            if (volatileText.isEmpty()) return finalizedText
            if (finalizedText.isEmpty()) return volatileText
            return "$finalizedText $volatileText"
        }

    // MARK: - İzin

    suspend fun requestAuthorization() {
        var granted = micGranted()
        if (!granted && !askedOnce) {
            askedOnce = true
            granted = requestMicPermission()
        }
        notePermissionResult(granted)
    }

    /** Ekranın kendi izin başlatıcısından (rememberLauncherForActivityResult) gelen sonuç. Android'de ayrı bir
     *  "konuşma tanıma izni" yoktur; tanıyıcının yokluğu `open()`da "Konuşma tanıma başlatılamadı." olur. */
    fun notePermissionResult(granted: Boolean) {
        askedOnce = true
        isAuthorized = granted
        errorMessage = if (granted) null else "Mikrofon izni verilmedi. Ayarlar'dan izin verin."
    }

    private fun micGranted(): Boolean = Prefs.appContext?.let {
        ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } ?: false

    /** Compose dışından RECORD_AUDIO istemi (etkin Activity'nin sonuç kaydıyla). */
    private suspend fun requestMicPermission(): Boolean {
        val activity = ActivityHolder.current as? ComponentActivity ?: return false
        return suspendCancellableCoroutine { cont ->
            val holder = arrayOfNulls<ActivityResultLauncher<String>>(1)
            val launcher = activity.activityResultRegistry.register(
                "dfy-mic-${UUID.randomUUID()}", ActivityResultContracts.RequestPermission(),
            ) { granted ->
                holder[0]?.unregister()
                if (cont.isActive) cont.resume(granted)
            }
            holder[0] = launcher
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // MARK: - Oturum

    /** Ses oturumunu + grafiği + STT merdivenini ayağa kaldırır. İdempotent.
     *  TTS bu oturum açıkken çalınabilir. Dönüş: oturum açık mı. */
    suspend fun open(): Boolean = openLock.withLock { openLocked() }

    private suspend fun openLocked(): Boolean {
        if (sessionOpen) return true
        if (disposed) return false
        val nesil = oturumNesli
        if (!isAuthorized) requestAuthorization()
        // İzin penceresi açıkken ekran kapandıysa oturum kurulmaz.
        if (!isAuthorized || nesil != oturumNesli || disposed) return false
        val ctx = Prefs.appContext ?: run {
            errorMessage = "Konuşma tanıma başlatılamadı."
            return false
        }

        try {
            coordinator.acquire(AudioSessionCoordinator.Consumer.dictation)
        } catch (e: Exception) {
            errorMessage = "Ses oturumu başlatılamadı: ${e.localizedMessage ?: e}"
            return false
        }

        var pipe: SpeechPipeline? = null
        try {
            val p = SpeechPipelineFactory.make(ctx)
            pipe = p
            if (nesil != oturumNesli || disposed) { acilisiGeriAl(p); return false }
            pipeline = p
            p.onVolatile = { t -> applyVolatile(t) }
            p.onFinal = { t -> applyFinal(t) }
            p.onError = { _ ->
                // 🔴 Hata mikrofonu KALICI kapatmasın: hâlâ dinliyorsak geçit AÇIK kalır → pipeline sonraki
                //    sözcede kendini toparlar (tick de geçidi sürekli `isListening`e eşitler).
                gate.set(isListening)
                pipeline?.setMicOpen(isListening)
            }
            // Mikrofonu kendisi açan kademe (Kademe 2): seviye doğrudan ölçere.
            p.onLevel = { db, dt -> meter.processDb(db, dt) }
            // Geçit kapalı başlar — mikrofonu kendisi açan kademe dinlemeye `startListening()`te başlar.
            p.setMicOpen(false)

            // Biasing sözlüğü (hekim/hizmet … adları) — oturum başına BİR kez, `start()`TAN ÖNCE (tanıyıcı bu
            // bağlamla kurulsun; akış ortasında bağlam değiştirmek sonuç akışını sarsabilir).
            if (!lexiconLoaded) {
                lexiconLoaded = true
                // Sözlük gelmezse dikte yine çalışır (yalnız biasing olmaz).
                val lexicon = try {
                    AgentClient.shared.fetchSTTLexicon(sozlukKovalari)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                if (lexicon.isNotEmpty()) p.setContextualStrings(lexicon)
                if (nesil != oturumNesli || disposed) { acilisiGeriAl(p); return false }
            }

            // 🔴 YAPILANDIRMA GÖZLEMCİSİ grafik başlamadan ÖNCE bağlanır: aksi halde motorun ilk kurulumunda
            //    yayılan değişim kaybolur ve grafik yeniden kurulmaz (dikte sağırlaşır).
            val handler: () -> Unit = { runCatching { graph.rebuild() } }
            configHandler = handler
            coordinator.onConfigurationChange = handler

            // Kademe 1 sesi grafiğin tap'inden alır; tap dinleme başlarken takılır (`startListening`).
            tap = if (p.usesExternalAudio) {
                val meter = this.meter
                val gate = this.gate
                VoiceAudioGraph.BufferCallback { buffer, count, rate ->
                    meter.process(buffer, count, rate)
                    if (gate.isOpen()) p.ingest(buffer, count)
                }
            } else null

            try {
                p.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Konuşma tanıma başlatılamadı."
                acilisiGeriAl(p)
                return false
            }
            if (nesil != oturumNesli || disposed) { acilisiGeriAl(p); return false }
        } catch (e: CancellationException) {
            acilisiGeriAl(pipe)
            throw e
        } catch (e: Exception) {
            // Tanıyıcı kademesi seçilemedi / kurulamadı — ekran çökmez, dikte "başlatılamadı" der.
            errorMessage = "Konuşma tanıma başlatılamadı."
            acilisiGeriAl(pipe)
            return false
        }

        sessionOpen = true
        startTicking()
        return true
    }

    /** Yarıda kalan açılışın kurduklarını geri alır (grafik, tanıyıcı, ses oturumu). */
    private fun acilisiGeriAl(pipe: SpeechPipeline?) {
        if (pipeline === pipe) pipeline = null
        tap = null
        pipe?.let { stopDetached(it) }
        graph.stop()
        if (configHandler != null && coordinator.onConfigurationChange === configHandler) coordinator.onConfigurationChange = null
        configHandler = null
        coordinator.release(AudioSessionCoordinator.Consumer.dictation)
    }

    /** Tanıyıcının durdurulması askıya alındığı için kendi kapsamında koşar — ekran kapsamı iptal edilse bile
     *  mikrofon serbest kalır (Android'de tek mikrofon). */
    private fun stopDetached(pipe: SpeechPipeline) {
        CoroutineScope(Dispatchers.Main + NonCancellable).launch { runCatching { pipe.stop() } }
    }

    /** Oturumu tamamen kapatır (grafik + STT + ses oturumu). */
    fun close() {
        oturumNesli += 1   // sürmekte olan `open()` da kapanır
        if (!sessionOpen) return
        sessionOpen = false
        isListening = false
        gate.set(false)
        tickTask?.cancel(); tickTask = null
        silenceTask?.cancel(); silenceTask = null
        tts.stop()
        val pipe = pipeline
        pipeline = null
        tap = null
        pipe?.let { stopDetached(it) }
        graph.stop()
        if (configHandler != null && coordinator.onConfigurationChange === configHandler) coordinator.onConfigurationChange = null
        configHandler = null
        coordinator.release(AudioSessionCoordinator.Consumer.dictation)
        audioLevel = 0.0
        endpointInFlight = 0
    }

    /** Ekran kapanınca (Android): oturumu kapatır, cihaz sesini ve iş kapsamını bırakır. İdempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        close()
        tts.dispose()
        scope.cancel()
    }

    // MARK: - Dinleme

    /** Dinlemeyi başlatır (gerekiyorsa oturumu açar). Metin SIFIRLANIR. Dönüş: dinleme başladı mı. */
    suspend fun startListening(): Boolean {
        if (!open()) return false
        val t = tap
        if (t != null && !graph.isRunning) {
            try {
                graph.start(t)
            } catch (e: Exception) {
                errorMessage = "Ses motoru başlatılamadı: ${e.localizedMessage ?: e}"
                return false
            }
        }
        finalizedText = ""; volatileText = ""; transcript = ""
        errorMessage = null
        isListening = true
        gate.set(true)
        pipeline?.setMicOpen(true)
        resetSilenceTimer()
        return true
    }

    /** Dinlemeyi durdurur; oturum AÇIK KALIR (TTS çalabilsin, tekrar başlatmak ucuz olsun). */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        gate.set(false)
        pipeline?.setMicOpen(false)
        silenceTask?.cancel(); silenceTask = null
        audioLevel = 0.0
        endpoint()
        // Mikrofon bırakılır (Kademe 1); kesinleştirme o ana kadar beslenen sesle yapılır, TTS oynatıcısı sürer.
        graph.stopCapture()
    }

    /** Konuşmayı at ve sıfırdan dinlemeye devam et (oturum kapanmaz). */
    fun resetTranscript() {
        finalizedText = ""; volatileText = ""; transcript = ""
        onTranscriptChange?.invoke("")
        // Android: Kademe 2 kesinleşen parçaları kendi içinde biriktirir — dinleme sürüyorsa o birikim de atılır.
        if (isListening) endpoint()
    }

    /** Mevcut sözceyi kapatır; kapanırken gelen sonuçlar yok sayılır. */
    private fun endpoint() {
        val pipe = pipeline ?: return
        endpointInFlight += 1
        scope.launch {
            try {
                pipe.endpoint()
            } finally {
                endpointInFlight = maxOf(0, endpointInFlight - 1)
            }
        }
    }

    // MARK: - TTS (aynı grafikten)

    /** YALNIZ Tedavi Planı çağırır (M-E1.1.4). TTS durum yoklaması (`/api/ai/tts/status`) ilk okumaya
     *  ertelenir — sohbet ekranını açmak TTS ucuna istek ATMAZ. */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            if (!open()) return@launch
            if (!ttsStatusChecked) {
                ttsStatusChecked = true
                tts.checkStatus()
            }
            tts.enqueue(clean)
        }
    }

    fun stopSpeaking() = tts.flush()

    // MARK: - Özel

    private fun applyVolatile(text: String) {
        if (!isListening || endpointInFlight > 0) return
        volatileText = text
        publish()
    }

    private fun applyFinal(text: String) {
        if (!isListening || endpointInFlight > 0) return
        if (text.isNotEmpty()) {
            finalizedText += if (finalizedText.isEmpty()) text else " $text"
        }
        volatileText = ""
        publish()
    }

    private fun publish() {
        transcript = combined
        onTranscriptChange?.invoke(transcript)
        resetSilenceTimer()
    }

    private fun startTicking() {
        tickTask?.cancel()
        tickTask = scope.launch {
            while (isActive) {
                delay(60)
                if (!sessionOpen) break
                // 🔴 GEÇİDİ SÜREKLİ isListening'e EŞİTLE: pipeline'ın geçici bir hatası (ya da geçidi kapatan
                //    başka bir yol) 60 ms içinde kendini onarır — mikrofon kalıcı ölmez.
                gate.set(isListening)
                pipeline?.setMicOpen(isListening)
                audioLevel = if (isListening) meter.snapshot().level.toDouble() else 0.0
            }
        }
    }

    private fun resetSilenceTimer() {
        silenceTask?.cancel()
        if (!autoStopOnSilence || !isListening) { silenceTask = null; return }
        val timeoutMs = (silenceTimeout * 1000).toLong()
        silenceTask = scope.launch {
            delay(timeoutMs)
            if (!isListening) return@launch
            stopListening()
            onAutoStop?.invoke()
        }
    }
}

/** Gerçek zamanlı ses iş parçacığındaki tap ile dinleme fazı arasındaki mikrofon geçidi. */
private class DictationMicGate {
    @Volatile private var open = false
    fun isOpen(): Boolean = open
    fun set(value: Boolean) { open = value }
}
