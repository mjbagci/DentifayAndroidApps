package com.dentifay.app.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ham ses buffer'ından enerji (RMS dBFS) + konuşma tespiti (VAD) + dalga formu halka tamponu.
 *
 * ⚠️ Compose state DEĞİL — saniyede ~25 güncelleme burada, kilit arkasında ölür.
 * Bu karar KORUNACAK. Ses thread'inden `process`; main'den `snapshot`/`envelope`.
 *
 * 🔴 FAZ 2 DÜZELTMESİ — zaman sabitleri SANİYE cinsinden (buffer sayısı DEĞİL).
 */
class VoiceEnergyMeter {

    data class Snapshot(
        val level: Float,      // 0…1 normalize
        val db: Float,
        val isSpeech: Boolean,
    )

    private val lock = Any()

    private var noiseFloor = -60f
    private var calibrated = false
    private var margin = 12f

    // Zaman sabitleri — SANİYE.
    private var onsetSeconds = 0.0
    private var hangoverSeconds = 0.0

    private var lastLevel = 0f
    private var lastDb = -80f
    private var lastSpeech = false

    // Konuşma oranı (boş kayıt tespiti).
    private var speechSeconds = 0.0
    private var totalSeconds = 0.0

    // Dalga formu halka tamponu.
    private val ring = FloatArray(RING_CAPACITY)
    private var head = 0

    // MARK: - Ses thread'i girişi

    /** PCM16 mono buffer (AudioRecord). */
    fun process(buffer: ShortArray, count: Int, sampleRate: Int) {
        if (count <= 0) return
        var sum = 0f
        for (i in 0 until count) {
            val v = buffer[i] / 32768f
            sum += v * v
        }
        val rms = sqrt(sum / count)
        val db = 20f * log10(max(rms, 1e-7f))
        val dt = count.toDouble() / sampleRate
        processDb(db, dt)
    }

    /** Hazır dBFS değeri (mikrofonu kendisi açan tanıyıcının `onRmsChanged`'i) + süre. */
    fun processDb(db: Float, dt: Double) = synchronized(lock) {
        totalSeconds += dt

        // Gürültü tabanı: GERÇEK 1,5 sn zaman sabiti (yalnız sessizlikte).
        if (!calibrated) {
            noiseFloor = db
            calibrated = true
        } else if (db < noiseFloor + margin) {
            noiseFloor += (db - noiseFloor) * min(1.0, dt / 1.5).toFloat()
        }

        val threshold = noiseFloor + margin
        if (db > threshold) {
            onsetSeconds += dt
            if (onsetSeconds >= 0.06) hangoverSeconds = 0.30   // GERÇEK 300 ms
        } else {
            onsetSeconds = 0.0
            hangoverSeconds = max(0.0, hangoverSeconds - dt)
        }

        lastSpeech = hangoverSeconds > 0
        if (lastSpeech) speechSeconds += dt

        val span = max(6f, -noiseFloor)
        lastLevel = ((db - noiseFloor) / span).coerceIn(0f, 1f)
        lastDb = db

        ring[head] = lastLevel
        head = (head + 1) % RING_CAPACITY
    }

    // MARK: - Main tarafı okuma

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(lastLevel, lastDb, lastSpeech) }

    /** Konuşulan sürenin toplam süreye oranı. `< 0.08` → boş kayıt. */
    val speechRatio: Float
        get() = synchronized(lock) { if (totalSeconds > 0) (speechSeconds / totalSeconds).toFloat() else 0f }

    /** Çağıranın tamponuna eleman eleman kopyalar (ayırma yok). Çıktı: en eski → en yeni. */
    fun envelope(dst: FloatArray) = synchronized(lock) {
        val n = min(dst.size, RING_CAPACITY)
        for (i in 0 until n) {
            val src = (head + RING_CAPACITY - n + i) % RING_CAPACITY
            dst[i] = ring[src]
        }
    }

    fun reset() = synchronized(lock) {
        calibrated = false
        noiseFloor = -60f
        onsetSeconds = 0.0
        hangoverSeconds = 0.0
        lastLevel = 0f
        lastDb = -80f
        lastSpeech = false
        speechSeconds = 0.0
        totalSeconds = 0.0
        ring.fill(0f)
        head = 0
    }

    fun setMargin(db: Float) = synchronized(lock) { margin = db }

    /** Rota adına göre marj: Bluetooth daha gürültülü → daha yüksek marj. reset() de yapar. */
    fun applyRoute(route: String) {
        val m = if (route.contains("bluetooth", ignoreCase = true)) 15f else 9f
        setMargin(m)
        reset()
    }

    private companion object {
        const val RING_CAPACITY = 96
    }
}
