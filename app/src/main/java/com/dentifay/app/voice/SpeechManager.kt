package com.dentifay.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Sohbet kompozeri dikte yöneticisi (`SpeechManager`). Android `SpeechRecognizer` (tr-TR).
 * Tanıyıcı her sözce sonunda kapandığı için segmentler biriktirilip dinleme sessizlik
 * zaman aşımına dek kesintisiz sürdürülür (iOS sürekli tanıma davranışı).
 */
class SpeechManager {
    var transcript by mutableStateOf("")
    var isRecording by mutableStateOf(false)
    var isAuthorized by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var audioLevel by mutableFloatStateOf(0f)

    /** Zor kelimeleri (hasta adları, stok ürünleri, tedavi terimleri) doğru yazıma yönlendiren
     *  alan sözlüğü. `startRecording()`'ten önce ayarlanır (Android 13+ biasing). */
    var contextualStrings: List<String> = emptyList()

    /** Yeni transkript gelmeyen bu kadar saniye sonra kayıt kendiliğinden durur.
     *  Transkript korunur — hiçbir şey gönderilmez (web STT akışıyla aynı). */
    var silenceTimeout: Double = 4.0
    /** false iken sessizlik zamanlayıcısı hiç çalışmaz (canlı ses modu kendi mantığını yürütür). */
    var autoStopOnSilence = true
    /** Kayıt sessizlik yüzünden kendiliğinden durduğunda ana thread'de çağrılır. */
    var onAutoStop: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var silenceJob: Job? = null
    private val scope = MainScope()
    /** Kesinleşmiş segmentler + o anki kısmi sonuç. */
    private var accumulated = ""
    /** İzin istemi bu yönetici için en çok bir kez gösterilir (iOS sistemi gibi). */
    private var askedOnce = false

    private val context get() = Prefs.appContext

    private fun micGranted(): Boolean = context?.let {
        ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } ?: false

    suspend fun requestAuthorization() {
        var granted = micGranted()
        if (!granted && !askedOnce) {
            askedOnce = true
            granted = requestMicPermission()
        }
        notePermissionResult(granted)
    }

    /** Ekranın kendi izin başlatıcısından (rememberLauncherForActivityResult) gelen sonuç. */
    fun notePermissionResult(granted: Boolean) {
        askedOnce = true
        isAuthorized = granted
        errorMessage = if (!granted) "Mikrofon izni verilmedi. Ayarlar'dan izin verin." else null
    }

    /** Compose dışından (ör. sesli oturum açılışı) RECORD_AUDIO istemi. */
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

    fun startRecording(): Boolean {
        if (!isAuthorized) {
            errorMessage = "Gerekli izinler verilmedi."
            return false
        }
        val ctx = context
        if (ctx == null || !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            errorMessage = "Konuşma tanıma servisi şu an kullanılamıyor."
            return false
        }

        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        transcript = ""
        accumulated = ""
        errorMessage = null

        // Tek-sahip ses oturumu (AudioSessionCoordinator). Gecikmeli kapatma YOK — dikte ve
        // canlı ses aynı oturumu refcount'lu paylaşır. (FAZ 2)
        try {
            AudioSessionCoordinator.acquire(AudioSessionCoordinator.Consumer.dictation)
        } catch (e: Exception) {
            errorMessage = "Ses oturumu başlatılamadı."
            return false
        }

        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) {
            errorMessage = "Ses kaydı başlatılamadı."
            AudioSessionCoordinator.release(AudioSessionCoordinator.Consumer.dictation)
            return false
        }
        r.setRecognitionListener(listener)
        recognizer = r
        isRecording = true
        if (!listenOnce()) {
            isRecording = false
            errorMessage = "Ses kaydı başlatılamadı."
            r.destroy(); recognizer = null
            AudioSessionCoordinator.release(AudioSessionCoordinator.Consumer.dictation)
            return false
        }
        // Baştan itibaren sessizlik de dinlemeyi durdurmalı.
        resetSilenceTimer()
        return true
    }

    private fun listenOnce(): Boolean {
        val ctx = context ?: return false
        val r = recognizer ?: return false
        val intent = recognizerIntent("tr-TR", contextualStrings, ctx).apply {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)   // dikte: noktalama + doğruluk öncelikli
        }
        return runCatching { r.startListening(intent) }.isSuccess
    }

    private fun compose(partial: String): String = when {
        accumulated.isEmpty() -> partial
        partial.isEmpty() -> accumulated
        else -> "$accumulated $partial"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (!isRecording) return
            // Tanıyıcı ölçeği ≈ -2…10 dB → 0…1.
            audioLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isRecording) return
            val text = partialResults.bestText()
            if (text.isEmpty()) return
            transcript = compose(text)
            // Yeni konuşma geldi → sessizlik sayacını yeniden başlat.
            resetSilenceTimer()
        }

        override fun onResults(results: Bundle?) {
            if (!isRecording) return
            val text = results.bestText()
            if (text.isNotEmpty()) {
                transcript = compose(text)
                resetSilenceTimer()
            }
            accumulated = transcript
            // Segment bitti — sessizlik zaman aşımına dek dinlemeyi sürdür.
            listenOnce()
        }

        override fun onError(error: Int) {
            if (!isRecording) return
            if (isTransientRecognizerError(error)) {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    recognizer?.cancel()
                    scope.launch { delay(300); if (isRecording) listenOnce() }
                } else listenOnce()
                return
            }
            if (transcript.isEmpty()) errorMessage = "Konuşma algılanamadı. Tekrar deneyin."
        }
    }

    /** Sessizlik sayacını yeniden başlatır. `silenceTimeout` içinde yeni transkript
     *  gelmezse kaydı durdurur (metin korunur) ve bildirir. */
    private fun resetSilenceTimer() {
        silenceJob?.cancel()
        if (!autoStopOnSilence) { silenceJob = null; return }
        silenceJob = scope.launch {
            delay((silenceTimeout * 1000).toLong())
            if (!isRecording) return@launch
            stopRecording()
            onAutoStop?.invoke()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        audioLevel = 0f
        silenceJob?.cancel()
        silenceJob = null

        recognizer?.let { r ->
            runCatching { r.stopListening() }
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
        accumulated = ""
        // Gecikmeli kapatma YASAK (AudioSessionCoordinator). Coordinator SON tüketici çıkınca oturumu kapatır.
        AudioSessionCoordinator.release(AudioSessionCoordinator.Consumer.dictation)
    }
}
