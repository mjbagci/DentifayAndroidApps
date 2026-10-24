package com.dentifay.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Cihaz-içi STT merdiveni. **Ham ses mümkün olduğunca cihazdan ÇIKMAZ** (KVKK vaadi).
 *
 * Kademe 1 (ModernSpeechPipeline): Android 13+ cihaz-içi tanıyıcı (`createOnDeviceSpeechRecognizer`)
 * ve tr-TR modeli KURULU ise — ses grafiğin tap'inden (AEC'li) boru üzerinden beslenir.
 * Kademe 2 (LegacySpeechPipeline): standart `SpeechRecognizer` (mikrofonu kendisi açar,
 * `EXTRA_PREFER_OFFLINE`) + rolling restart.
 *
 * Uygulayıcı `onVolatile`/`onFinal`/`onError`'ı `start()`'tan ÖNCE atamalıdır.
 * Geri çağrılar ana thread'de gelir.
 */
interface SpeechPipeline {
    var onVolatile: ((String) -> Unit)?
    var onFinal: ((String) -> Unit)?
    var onError: ((Throwable) -> Unit)?
    /** Mikrofonu KENDİSİ açan kademe ses seviyesini (yaklaşık dBFS, süre sn) bununla bildirir. */
    var onLevel: ((db: Float, dt: Double) -> Unit)?

    /** true → ses grafiğin tap'inden `ingest` ile beslenir; false → tanıyıcı mikrofonu kendisi açar. */
    val usesExternalAudio: Boolean

    /** Biasing sözlüğü (hasta/doktor/hizmet/ürün adları). En çok 100 ifade. */
    fun setContextualStrings(strings: List<String>)

    suspend fun start()
    /** Ses grafiğinin tap'inden (ses thread'i) çağrılır. */
    fun ingest(buffer: ShortArray, count: Int)
    /** Mikrofon geçidi (yalnız dinleme fazında açık). Mikrofonu kendisi açan kademe dinlemeyi durdurur/sürdürür. */
    fun setMicOpen(open: Boolean)
    /** Endpoint: mevcut sözceyi zorla finalize et (kullanıcı sustu). */
    suspend fun endpoint()
    suspend fun stop()
}

sealed class SpeechPipelineError(message: String) : Exception(message) {
    class noCompatibleAudioFormat : SpeechPipelineError("noCompatibleAudioFormat")
    class localeUnsupported : SpeechPipelineError("localeUnsupported")
    class recognizer(val code: Int) : SpeechPipelineError("SpeechRecognizer error $code")
}

/** Ortak tanıma isteği (tr-TR, kısmi sonuçlar, cihaz-içi tercih, biasing). */
internal fun recognizerIntent(locale: String, contextual: List<String>, context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)        // KVKK — mümkünse ses cihazdan çıkmaz
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (Build.VERSION.SDK_INT >= 33 && contextual.isNotEmpty()) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(contextual.take(100)))
        }
    }

internal fun Bundle?.bestText(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

/** Yeniden denenebilir (sessizlik / eşleşme yok / meşgul) tanıyıcı hataları. */
internal fun isTransientRecognizerError(code: Int): Boolean = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_AUDIO -> true
    else -> false
}

// MARK: - Factory

object SpeechPipelineFactory {
    /**
     * Diagnostik gerçeğine göre kademe seçer. Cihaz-içi tr-TR modeli kurulu değilse ama
     * destekleniyorsa indirme tetiklenir (iOS AssetInventory gibi) ve bu oturum Kademe 2 ile sürer.
     */
    suspend fun make(context: Context, locale: String = "tr-TR"): SpeechPipeline {
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            if (onDeviceInstalled(context, locale)) return ModernSpeechPipeline(context, locale)
        }
        return LegacySpeechPipeline(context, locale)
    }

    @RequiresApi(33)
    private suspend fun onDeviceInstalled(context: Context, locale: String): Boolean {
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return false
        val intent = recognizerIntent(locale, emptyList(), context)
        val lang = locale.substringBefore('-').lowercase()
        fun has(list: List<String>) = list.any { it.lowercase().startsWith(lang) }
        val support: RecognitionSupport? = withTimeoutOrNull(2500) {
            suspendCancellableCoroutine<RecognitionSupport?> { cont ->
                recognizer.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (cont.isActive) cont.resume(recognitionSupport)
                    }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        }
        val installed = support != null && has(support.installedOnDeviceLanguages)
        if (support != null && !installed && (has(support.supportedOnDeviceLanguages) || has(support.pendingOnDeviceLanguages))) {
            runCatching { recognizer.triggerModelDownload(intent) }
        }
        recognizer.destroy()
        return installed
    }
}

// MARK: - Ses köprüsü (tap → boru)

/** Tap'ten gelen PCM16'yı tanıyıcının okuduğu boruya yazar. Ses thread'ini bloklamaz (kuyruk + ayrı thread). */
private class PipeFeeder(private val writeEnd: ParcelFileDescriptor) {
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    @Volatile private var open = true
    private val out = FileOutputStream(writeEnd.fileDescriptor)

    private val thread = Thread({
        while (open) {
            val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            try { out.write(b) } catch (_: IOException) { open = false }
        }
    }, "SpeechPipeFeeder").also { it.isDaemon = true; it.start() }

    fun offer(buffer: ShortArray, count: Int) {
        if (!open) return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = buffer[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        queue.offer(bytes)   // dolu ise düşür (gerçek zamanlı thread asla beklemez)
    }

    fun close() {
        open = false
        runCatching { out.close() }
        runCatching { writeEnd.close() }
    }
}

// MARK: - Modern kademe (cihaz-içi tanıyıcı + harici ses)

@RequiresApi(33)
class ModernSpeechPipeline(private val context: Context, private val locale: String) : SpeechPipeline {

    override var onVolatile: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onLevel: ((Float, Double) -> Unit)? = null
    override val usesExternalAudio: Boolean = true

    private var contextual: List<String> = emptyList()
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var readEnd: ParcelFileDescriptor? = null
    @Volatile private var feeder: PipeFeeder? = null
    private var finalWaiter: CompletableDeferred<Unit>? = null
    private var consecutiveFatal = 0
    private val scope = MainScope()

    override fun setContextualStrings(strings: List<String>) {
        contextual = strings.take(100)
        // Yeni değerler bir sonraki istekte uygulanır.
    }

    override suspend fun start() {
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?: throw SpeechPipelineError.localeUnsupported()
        r.setRecognitionListener(listener)
        recognizer = r
        running = true
        startNewRequest()
    }

    private fun startNewRequest() {
        val r = recognizer ?: return
        if (!running) return
        closePipe()
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) {
            onError?.invoke(SpeechPipelineError.noCompatibleAudioFormat()); return
        }
        readEnd = pipe[0]
        feeder = PipeFeeder(pipe[1])
        val intent = recognizerIntent(locale, contextual, context).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
        runCatching { r.startListening(intent) }.onFailure { onError?.invoke(it) }
    }

    private fun closePipe() {
        feeder?.close(); feeder = null
        runCatching { readEnd?.close() }; readEnd = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            val text = partialResults.bestText()
            if (text.isNotEmpty()) onVolatile?.invoke(text)
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            consecutiveFatal = 0
            onFinal?.invoke(results.bestText())
            finalWaiter?.complete(Unit); finalWaiter = null
            // Segment kesinleşti — grafiği durdurmadan yeni isteğe geç.
            startNewRequest()
        }

        override fun onError(error: Int) {
            if (!running) return
            finalWaiter?.complete(Unit); finalWaiter = null
            if (isTransientRecognizerError(error)) {
                // Hata → sessizce yeni istek (sağırlaşmayı önler).
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    recognizer?.cancel()
                    scope.launch { delay(300); startNewRequest() }
                } else startNewRequest()
                return
            }
            consecutiveFatal += 1
            this@ModernSpeechPipeline.onError?.invoke(SpeechPipelineError.recognizer(error))
            if (consecutiveFatal <= 3) scope.launch { delay(1000); startNewRequest() }
        }
    }

    override fun ingest(buffer: ShortArray, count: Int) {
        feeder?.offer(buffer, count)
    }

    override fun setMicOpen(open: Boolean) { /* geçit üst katmanda (ingest çağrılmaz) */ }

    override suspend fun endpoint() {
        val r = recognizer ?: return
        val waiter = CompletableDeferred<Unit>()
        finalWaiter = waiter
        runCatching { r.stopListening() }   // o ana kadarki ses tanınır → onResults
        withTimeoutOrNull(2000) { waiter.await() }
        if (finalWaiter === waiter) finalWaiter = null
    }

    override suspend fun stop() {
        running = false
        finalWaiter?.complete(Unit); finalWaiter = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        closePipe()
        scope.cancel()
    }
}
