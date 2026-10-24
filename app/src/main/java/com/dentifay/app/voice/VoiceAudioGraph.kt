package com.dentifay.app.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Tek ses grafiği: `AudioRecord` (VOICE_COMMUNICATION kaynağı + AEC/NS/AGC efektleri) yakalama,
 * tap (buffer geri çağrısı) yönetimi ve TTS oynatıcısı (`MediaPlayer`).
 *
 * TTS (yalnız Tedavi Planı önizleme özeti) bu grafiğin oynatıcısından çalar; `flush` kuyruğu ve
 * çalan sesi birlikte keser. Android'de yankı gidermesi kayıt oturumunun efektidir — okuma
 * asistan kullanımıyla (`USAGE_ASSISTANT`) hoparlörden çalar: sistem iletişim kipine geçilmediği
 * için sesli arama kullanımı kulaklık hoparlörüne düşebilirdi. Dikte ve okuma aynı anda çalışmaz.
 */
class VoiceAudioGraph {

    /** Buffer geri çağrısı: PCM16 mono örnekler, geçerli örnek sayısı, örnekleme hızı. */
    fun interface BufferCallback { fun onBuffer(buffer: ShortArray, count: Int, sampleRate: Int) }

    val sampleRate = 16_000
    private var record: AudioRecord? = null
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var thread: Thread? = null
    @Volatile private var running = false
    private var onBuffer: BufferCallback? = null

    private var player: MediaPlayer? = null
    private var playerVolume = 1f
    private var playbackDone: (() -> Unit)? = null

    val isRunning: Boolean get() = running

    /**
     * ⚠️ SIRA KİLİTLİ:
     *   ① AudioRecord'u VOICE_COMMUNICATION kaynağıyla kur
     *   ② SONRA ses işleme efektlerini (AEC/NS/AGC) oturuma bağla
     *   ③ SONRA kaydı başlat ve okuma thread'ini (tap) aç
     * bufferSize 640 örnek (40 ms @ 16 kHz ≈ iOS 2048 @ 48 kHz).
     */
    @SuppressLint("MissingPermission")
    fun start(onBuffer: BufferCallback) {
        if (running) return
        this.onBuffer = onBuffer
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("Mikrofon biçimi desteklenmiyor")
        // ①
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAMES * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Mikrofon açılamadı")
        }
        // ② Voice processing
        val sid = rec.audioSessionId
        val fx = mutableListOf<android.media.audiofx.AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.let { it.setEnabled(true); fx += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.let { it.setEnabled(true); fx += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.let { it.setEnabled(true); fx += it }
        effects = fx
        // ③
        rec.startRecording()
        record = rec
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(FRAMES)
            while (running) {
                val n = rec.read(buf, 0, FRAMES)
                if (n > 0) this.onBuffer?.onBuffer(buf, n, sampleRate)
                else if (n < 0) break
            }
        }, "VoiceAudioGraph").also { it.start() }
    }

    /** Kaydı durdurur ve mikrofonu bırakır; TTS oynatıcısına dokunmaz (dikte dinlemiyorken mikrofon tutulmaz). */
    fun stopCapture() {
        if (!running) return
        running = false
        thread?.join(300)
        thread = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        onBuffer = null
    }

    fun stop() {
        stopCapture()
        flushPlayback()
    }

    /** Yapılandırma (rota) değişimi sonrası grafiği aynı tap ile yeniden kurar. */
    fun rebuild() {
        val cb = onBuffer ?: return
        if (!running) return
        running = false
        thread?.join(300)
        thread = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        start(cb)
    }

    // MARK: - Oynatma (TTS kuyruğu)

    /** mp3 dosyasını çalar ve bitene (ya da kesilene) dek askıda kalır. Çalınamazsa false. */
    suspend fun playAndWait(file: File): Boolean = suspendCancellableCoroutine { cont ->
        flushPlayback()
        val mp = MediaPlayer()
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            if (player === mp) { player = null; playbackDone = null }
            runCatching { mp.release() }
            if (cont.isActive) cont.resume(ok)
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setVolume(playerVolume, playerVolume)
            mp.setOnCompletionListener { finish(true) }
            mp.setOnErrorListener { _, _, _ -> finish(false); true }
            mp.setOnPreparedListener { it.start() }
            player = mp
            playbackDone = { finish(true) }   // flush → çalan cümle "bitti" sayılır
            mp.prepareAsync()
        } catch (e: Exception) {
            finish(false)
        }
        cont.invokeOnCancellation { runCatching { mp.stop() }; finish(true) }
    }

    /** Barge-in: çalan sesi tamamen keser. */
    fun flushPlayback() {
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.stop() }
        val done = playbackDone
        player = null
        playbackDone = null
        done?.invoke()
    }

    fun setPlayerVolume(volume: Float) {
        playerVolume = volume.coerceIn(0f, 1f)
        player?.let { runCatching { it.setVolume(playerVolume, playerVolume) } }
    }

    private companion object {
        const val FRAMES = 640
    }
}
