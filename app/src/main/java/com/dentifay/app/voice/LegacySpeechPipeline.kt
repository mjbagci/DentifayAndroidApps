package com.dentifay.app.voice

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Kademe 2 — standart `SpeechRecognizer` (mikrofonu kendisi açar), cihaz-içi tercihli + rolling restart.
 *
 * Android tanıyıcısı her sözce sonunda (sessizlik) oturumu kapatır. Bu kademe bunu
 * kesinleşen segmenti biriktirip HEMEN yeni istek açarak (sonuç/hata) ve **50. saniyede
 * proaktif rotasyonla** görünmez kılar. `EXTRA_PREFER_OFFLINE` ile ses ve biasing
 * sözlüğü (hasta isimleri) mümkünse cihazdan çıkmaz.
 */
class LegacySpeechPipeline(private val context: Context, private val locale: String) : SpeechPipeline {

    override var onVolatile: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onLevel: ((Float, Double) -> Unit)? = null
    override val usesExternalAudio: Boolean = false

    private var recognizer: SpeechRecognizer? = null
    private var contextual: List<String> = emptyList()
    private var accumulated = ""       // finalize edilmiş segmentlerin birleşimi
    private var currentPartial = ""
    private var rotateJob: Job? = null
    private var running = false
    private var micOpen = true
    private var listening = false
    private var lastRmsAt = 0L
    private val scope = MainScope()

    override fun setContextualStrings(strings: List<String>) {
        contextual = strings.take(100)
        // Yeni değerler bir sonraki istek rotasyonunda uygulanır.
    }

    override suspend fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) throw SpeechPipelineError.localeUnsupported()
        val r = SpeechRecognizer.createSpeechRecognizer(context) ?: throw SpeechPipelineError.localeUnsupported()
        r.setRecognitionListener(listener)
        recognizer = r
        running = true
        accumulated = ""
        currentPartial = ""
        startNewRequest()
        // Proaktif rotasyon: 50 sn'de bir uzun oturum limitinin önüne geç.
        rotateJob = scope.launch {
            while (isActive) {
                delay(50_000)
                if (!running) break
                rotate(finalizeSegment = false)
            }
        }
    }

    private fun startNewRequest() {
        val r = recognizer ?: return
        if (!running || !micOpen) return
        listening = true
        lastRmsAt = 0L
        runCatching { r.startListening(recognizerIntent(locale, contextual, context)) }
            .onFailure { listening = false; onError?.invoke(it) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            // Tanıyıcı ölçeği ≈ -2…10 dB → yaklaşık dBFS (-60…-10).
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastRmsAt == 0L) 0.05 else ((now - lastRmsAt) / 1000.0).coerceIn(0.01, 0.2)
            lastRmsAt = now
            val db = -60f + (rmsdB.coerceIn(-2f, 10f) + 2f) * (50f / 12f)
            onLevel?.invoke(db, dt)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            val text = partialResults.bestText()
            if (text.isEmpty()) return
            currentPartial = text
            onVolatile?.invoke(combine())
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            listening = false
            val text = results.bestText()
            if (text.isNotEmpty()) currentPartial = text
            val combined = combine()
            onVolatile?.invoke(combined)
            // Segment kesinleşti — yeni isteğe geç.
            accumulated = combined
            currentPartial = ""
            startNewRequest()
        }

        override fun onError(error: Int) {
            if (!running) return
            listening = false
            if (!isTransientRecognizerError(error)) {
                this@LegacySpeechPipeline.onError?.invoke(SpeechPipelineError.recognizer(error))
            }
            // Hata → sessizce yeni istek (sağırlaşmayı önler).
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || !isTransientRecognizerError(error)) {
                recognizer?.cancel()
                scope.launch { delay(if (isTransientRecognizerError(error)) 300 else 1000); if (!listening) startNewRequest() }
            } else {
                startNewRequest()
            }
        }
    }

    private fun combine(): String {
        if (accumulated.isEmpty()) return currentPartial
        if (currentPartial.isEmpty()) return accumulated
        return "$accumulated $currentPartial"
    }

    /** Rotasyon: mevcut isteği kapatıp yenisini açar. `finalizeSegment` true ise
     *  mevcut kısmi metni kalıcı birikime ekler. */
    private fun rotate(finalizeSegment: Boolean) {
        if (finalizeSegment) { accumulated = combine(); currentPartial = "" }
        recognizer?.let { runCatching { it.cancel() } }
        listening = false
        if (running) startNewRequest()
    }

    /** Mikrofonu bu kademe açtığı için geçit kapalıyken dinleme DURUR, açılınca sürer. */
    override fun ingest(buffer: ShortArray, count: Int) { /* ses grafiği kullanılmaz */ }

    override fun setMicOpen(open: Boolean) {
        if (open == micOpen) return
        micOpen = open
        if (!running) return
        if (open) {
            if (!listening) startNewRequest()
        } else {
            recognizer?.let { runCatching { it.cancel() } }
            listening = false
            currentPartial = ""
        }
    }

    override suspend fun endpoint() {
        // Kullanıcı sustu: mevcut sözceyi finalize et ve üst katmana ver.
        val text = combine()
        onFinal?.invoke(text)
        accumulated = ""
        currentPartial = ""
        rotate(finalizeSegment = false)
    }

    override suspend fun stop() {
        running = false
        rotateJob?.cancel()
        rotateJob = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        listening = false
        scope.cancel()
    }
}
