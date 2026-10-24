package com.dentifay.app.voice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dentifay.app.BuildConfig
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Prefs
import com.dentifay.app.core.bool
import com.dentifay.app.core.str
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Sunucu (Azure) TTS hattı. Cümle cümle kuyruk + 4-ileri prefetch.
 *
 * - Birincil: `POST /api/ai/tts` → tam mp3 (backend Azure'ı proxy'ler; anahtar
 *   sunucuda kalır). mp3 → **VoiceAudioGraph oynatıcısı** (iletişim kullanımı → AEC referansı).
 * - Fallback: backend 503/erişilemez → Android `TextToSpeech` (cihaz sesi, tr-TR).
 *   ⚠️ Fallback sırasında üst katman mikrofonu kapatır (faz .speaking).
 * - `TTS_TEXT_TOO_LONG` (400): metni ortadan böl, iki çağrıyla tamamla.
 * - `TTS_DAILY_QUOTA` (429): günlük seslendirme hakkı doldu → cihaz sesi; kuyruk boşalana
 *   dek sunucu yeniden denenmez, `onNotice` kullanıcıya nedeni söyler. 402 gelirse aynı biçimde.
 * - Barge-in: `flush()` kuyruğu + prefetch'i + çalan sesi tamamen temizler.
 */
class TTSPipeline(private val graph: VoiceAudioGraph) {

    private val scope = MainScope()
    /** Uzun cümlede ses ortadan değişmesin diye 20 sn. */
    private val client: OkHttpClient = ApiClient.http.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    var enabled = true                       // /tts/status
        private set
    var providerVoice: String? = null        // fiilen kullanılan Azure sesi (X-Tts-Voice)
        private set

    private val queue = mutableListOf<String>()
    private var nextIndex = 0
    private val prefetch = mutableMapOf<Int, Deferred<ByteArray?>>()
    private var draining = false
    private var generation = 0               // barge-in mührü
    /** Günlük seslendirme hakkı doldu → kuyruk boşalana / flush'a dek ağ turu denenmez. */
    private var serverPaused = false
    private var fallbackContinuation: CancellableContinuation<Unit>? = null

    // Cihaz sesi (AVSpeechSynthesizer karşılığı) — tembel kurulur.
    private var synth: TextToSpeech? = null
    private var synthReady: CompletableDeferred<Boolean>? = null

    /** İlk sesli çıktı başladığında (thinking→speaking geçişi için). */
    var onStartedSpeaking: (() -> Unit)? = null
    /** Bir cümlenin sesi çalınmaya başladı (index, metin) — karaoke/senkron için. */
    var onSentenceStarted: ((Int, String) -> Unit)? = null
    /** Kuyruk boşaldı ve akış bitti. */
    var onFinishedAll: (() -> Unit)? = null
    /** Cihaz-içi fallback konuşurken mikrofon kapatılmalı. */
    var onFallbackWillSpeak: (() -> Unit)? = null
    var onFallbackDidFinish: (() -> Unit)? = null
    /** Kullanıcıya gösterilecek seslendirme bilgisi (null = temizle). Ham kod taşımaz. */
    var onNotice: ((String?) -> Unit)? = null

    private val baseURL: String get() = AppConfig.apiBaseURL

    // MARK: - Durum

    suspend fun checkStatus() {
        val obj = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$baseURL/ai/tts/status").get().build()).execute().use { r ->
                    if (r.code != 200) null
                    else DfyJson.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                }
            }.getOrNull()
        }
        if (obj == null) { enabled = false; return }
        enabled = obj.bool("enabled") ?: false
        obj.str("voice")?.let { providerVoice = it }
        if (BuildConfig.DEBUG) Log.d("TTS", "status enabled=$enabled provider=${obj.str("provider")} voice=$providerVoice")
    }

    // MARK: - Kuyruk

    /** Cümle(ler)i kuyruğa ekler; boşuna kesmeden seri çalar. */
    fun enqueue(text: String) {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return
        queue.addAll(sentences)
        // 🔴 draining SENKRON set edilir (çift-drain yarışı fix).
        if (draining) return
        draining = true
        scope.launch { drain() }
    }

    /** Barge-in / atla: her şeyi temizle, çalan sesi kes. */
    fun flush() {
        generation += 1
        queue.clear()
        nextIndex = 0
        prefetch.values.forEach { it.cancel() }
        prefetch.clear()
        graph.flushPlayback()
        synth?.let { if (it.isSpeaking) it.stop() }
        resumeFallback()
        // flush sonrası yeni enqueue temiz başlasın diye draining düşürülür.
        draining = false
        serverPaused = false
    }

    fun stop() {
        flush()
        draining = false
    }

    /** Oturum sonu: cihaz sesini ve iş kapsamını bırakır. */
    fun dispose() {
        stop()
        synth?.shutdown()
        synth = null
        scope.cancel()
    }

    // MARK: - Drain

    private suspend fun drain() {
        val gen = generation
        var started = false
        while (nextIndex < queue.size) {
            if (gen != generation) break
            val index = nextIndex
            val text = queue[index]
            prefetchAhead(index)                 // 4-paralel fan-out

            if (!started) { started = true; onStartedSpeaking?.invoke() }
            onSentenceStarted?.invoke(index, text)

            // enabled==false (ya da günlük hak doldu) ise ağ turu HİÇ denenmez → cihaz fallback.
            if (enabled && !serverPaused) {
                val data = fetch(index, text)
                if (gen != generation) break
                val played = data != null && playMp3(data, gen)
                if (gen != generation) break
                if (!played) speakFallback(text, gen)   // çalınamayan ses SESSİZCE DÜŞMEZ
            } else {
                speakFallback(text, gen)
            }
            if (gen != generation) break
            nextIndex += 1
        }
        if (gen == generation) draining = false
        if (gen == generation && nextIndex >= queue.size && started) {
            queue.clear(); nextIndex = 0
            serverPaused = false   // sonraki tur sunucuyu yeniden dener
            onFinishedAll?.invoke()
        }
    }

    /** 4-paralel fan-out: `index`'ten sonraki en çok 4 cümleyi paralel iste, sırayla çal. */
    private fun prefetchAhead(index: Int) {
        if (!enabled || serverPaused) return
        val last = minOf(index + 4, queue.size - 1)
        if (last <= index) return
        for (n in (index + 1)..last) {
            if (prefetch[n] != null) continue
            val text = queue[n]
            prefetch[n] = scope.async { requestTTS(text) }
        }
    }

    private suspend fun fetch(index: Int, text: String): ByteArray? {
        prefetch.remove(index)?.let { return runCatching { it.await() }.getOrNull() }
        return requestTTS(text)
    }

    // MARK: - Ağ

    /** `POST /api/ai/tts`. 200 → mp3. 400 TTS_TEXT_TOO_LONG → böl+birleştir.
     *  503 → enabled=false (fallback). Diğer hata → null (fallback). */
    private suspend fun requestTTS(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val req = runCatching {
            ApiClient.requestBuilder("POST", "/ai/tts", buildJsonObject { put("text", text); put("language", "tr") }).build()
        }.getOrNull() ?: return null
        val raw = runCatching { ApiClient.execute(req, client) }.getOrNull() ?: return null

        fun code(): String? = runCatching {
            (DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)) as? JsonObject)?.str("code")
        }.getOrNull()

        return when (raw.status) {
            200 -> {
                if (BuildConfig.DEBUG) raw.headers["X-Tts-Ms"]?.let { Log.d("TTS", "X-Tts-Ms=$it") }
                raw.headers["X-Tts-Voice"]?.let { v ->
                    if (providerVoice != v) {
                        providerVoice = v
                        if (BuildConfig.DEBUG) Log.d("TTS", "X-Tts-Voice=$v")
                    }
                }
                if (!serverPaused) onNotice?.invoke(null)   // sunucu sesi geri geldi → bilgi kalkar
                raw.body
            }
            400 -> {
                // Normalizasyon metni büyütür; tavanı aşarsa böl.
                val halves = if (code() == "TTS_TEXT_TOO_LONG") splitInHalf(text) else null
                if (halves != null) {
                    val first = requestTTS(halves.first)
                    val second = requestTTS(halves.second)
                    if (first != null && second != null) first + second else first ?: second
                } else null
            }
            402 -> {
                // Seslendirme kredi düşmez; yine de gelirse cihaz sesi + aynı Türkçe kredi metni.
                serverPaused = true
                onNotice?.invoke(CreditRequired.parse(402, raw.body)?.message)
                null
            }
            429 -> {
                if (code() == "TTS_DAILY_QUOTA") {
                    serverPaused = true
                    onNotice?.invoke(quotaNotice)
                }
                null   // oran sınırı vb. → yalnız bu cümle cihaz sesiyle
            }
            503 -> { enabled = false; null }
            else -> null
        }
    }

    // MARK: - mp3 → oynatıcı

    private suspend fun playMp3(mp3: ByteArray, gen: Int): Boolean {
        val ctx = Prefs.appContext ?: return false
        val tmp = File(ctx.cacheDir, "tts-${UUID.randomUUID()}.mp3")
        return try {
            withContext(Dispatchers.IO) { tmp.writeBytes(mp3) }
            if (gen != generation) true else graph.playAndWait(tmp)
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    // MARK: - Fallback (cihaz sesi)

    private suspend fun ensureSynth(): Boolean {
        synthReady?.let { return it.await() }
        val ctx = Prefs.appContext ?: return false
        val ready = CompletableDeferred<Boolean>()
        synthReady = ready
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                engine?.setLanguage(Locale.forLanguageTag("tr-TR"))   // ASLA varsayılana bırakma
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { scope.launch { resumeFallback() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { scope.launch { resumeFallback() } }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { scope.launch { resumeFallback() } }
                })
            }
            ready.complete(ok)
        }
        synth = engine
        return ready.await()
    }

    private suspend fun speakFallback(text: String, gen: Int) {
        val clean = stripMarkdown(text)
        if (clean.isEmpty() || gen != generation) return
        if (!ensureSynth() || gen != generation) return
        val engine = synth ?: return
        onFallbackWillSpeak?.invoke()
        suspendCancellableCoroutine<Unit> { cont ->
            fallbackContinuation = cont
            val params = Bundle()
            val r = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
            if (r != TextToSpeech.SUCCESS) resumeFallback()
        }
        // Kısa nefes (postUtteranceDelay 0.05).
        kotlinx.coroutines.delay(50)
        onFallbackDidFinish?.invoke()
    }

    private fun resumeFallback() {
        val c = fallbackContinuation
        fallbackContinuation = null
        if (c != null && c.isActive) c.resume(Unit)
    }

    companion object {
        /** Günlük hak dolduğunda gösterilen metin (sunucu kodu `TTS_DAILY_QUOTA`). */
        const val quotaNotice = "Bugünkü seslendirme hakkı doldu. Yanıtlar cihaz sesiyle okunuyor ve ekranda gösteriliyor."

        // MARK: - Metin yardımcıları

        fun stripMarkdown(md: String): String {
            var t = md
            for (ch in listOf("*", "_", "`", "#", ">", "|")) t = t.replace(ch, "")
            return t.trim()
        }

        /** Türkçe cümle bölme (kaba): . ! ? … sonrası. Backend zaten `speak_delta`'yı cümle cümle
         *  gönderdiği için normal yolda tek cümle gelir; bu yalnız uzun monolitik `speak` için yedek. */
        fun splitSentences(text: String): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in trimmed) {
                current.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
                    val s = current.toString().trim()
                    if (s.length > 1) { result += s; current.clear() }
                }
            }
            val tail = current.toString().trim()
            if (tail.isNotEmpty()) result += tail
            return if (result.isEmpty()) listOf(trimmed) else result
        }

        /** Metni ~ortadan, kelime sınırından ikiye böler (TTS_TEXT_TOO_LONG için). */
        fun splitInHalf(text: String): Pair<String, String>? {
            val words = text.split(" ").filter { it.isNotEmpty() }
            if (words.size <= 1) return null
            val mid = words.size / 2
            return words.subList(0, mid).joinToString(" ") to words.subList(mid, words.size).joinToString(" ")
        }
    }
}
