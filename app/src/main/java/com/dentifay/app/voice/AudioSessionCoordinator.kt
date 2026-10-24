@file:Suppress("EnumEntryName")

package com.dentifay.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder

/**
 * Uygulamadaki **TÜM** ses oturumu (AudioManager: odak, rota gözlemi) çağrılarının tek sahibi.
 * Başka hiçbir dosya `AudioManager` odağını değiştirmemelidir.
 *
 * Eski mimaride iki ayrı dikte yöneticisi aynı süreç-geneli oturumu paylaşıyor ve biri gecikmeli
 * kapatma planlayıp diğerinin canlı kaydını öldürüyordu. Buradaki tek-sahip + refcount modeli
 * o yarışı fiziksel olarak imkânsız kılar: oturum yalnız SON tüketici çıkınca kapanır ve
 * **hiçbir zaman gecikmeli deaktivasyon yazılmaz.**
 *
 * Android karşılıkları: `.duckOthers` → `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`;
 * `isIdleTimerDisabled` → `FLAG_KEEP_SCREEN_ON`. Yankı giderme (AEC/NS/AGC) kayıt tarafında
 * `VoiceAudioGraph`in efektleridir; sistem ses kipi (iletişim kipi) DEĞİŞTİRİLMEZ — dikte ve
 * Tedavi Planı'nın özet okuması aynı anda çalmaz.
 */
object AudioSessionCoordinator {

    enum class Consumer { dictation }
    enum class AudioRoute { builtIn, wired, bluetoothHFP, bluetoothA2DP }
    /** `AVAudioSession.RouteChangeReason` karşılığı (kullanılan alt küme). */
    enum class RouteChangeReason { newDeviceAvailable, oldDeviceUnavailable, unknown }

    private val consumers = mutableSetOf<Consumer>()
    private var observersInstalled = false
    /** Tembel: nesneye yalnız dokunmak (ör. birim testinde) Android ana döngüsünü istemez. */
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    private var focusRequest: AudioFocusRequest? = null
    /** Geçici odak kaybından sonra geri gelince "devam et" denir. */
    private var transientLoss = false

    /** Kesinti / rota / yapılandırma bildirimleri — üst katman (`DictationSession`) abone olur. */
    var onInterruptionBegan: (() -> Unit)? = null
    var onInterruptionEnded: ((shouldResume: Boolean) -> Unit)? = null
    var onRouteChanged: ((reason: RouteChangeReason, route: AudioRoute) -> Unit)? = null
    var onConfigurationChange: (() -> Unit)? = null

    val isActive: Boolean get() = consumers.isNotEmpty()

    private val audioManager: AudioManager?
        get() = Prefs.appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Oturumu kurar. İdempotent. İkinci tüketici için hiçbir şey yapmaz. */
    fun acquire(consumer: Consumer) {
        installObserversIfNeeded()
        val wasEmpty = consumers.isEmpty()
        consumers.add(consumer)
        if (!wasEmpty) return
        val am = audioManager
        if (am == null) {
            consumers.remove(consumer)
            throw IllegalStateException("AudioManager yok")
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change -> handleFocusChange(change) }, main)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
        setKeepScreenOn(true)
    }

    /** Tüketiciyi bırakır. SON tüketici çıkınca oturumu kapatır.
     *  ⚠️ GECİKMELİ DEAKTİVASYON ASLA YAZILMAZ. */
    fun release(consumer: Consumer) {
        if (!consumers.remove(consumer)) return
        if (consumers.isNotEmpty()) return
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
        transientLoss = false
        setKeepScreenOn(false)
    }

    val currentRoute: AudioRoute
        get() = audioManager?.let { map(it) } ?: AudioRoute.builtIn

    // MARK: - Private

    private fun map(am: AudioManager): AudioRoute {
        // Çıkış portu AEC ve endpoint kararları için belirleyicidir.
        for (out in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            when (out.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return AudioRoute.bluetoothHFP
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> return AudioRoute.bluetoothA2DP
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE -> return AudioRoute.wired
                else -> continue
            }
        }
        // Kablolu mikrofonlu kulaklık yalnız girişte görünebilir.
        for (inp in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (inp.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) return AudioRoute.wired
        }
        return AudioRoute.builtIn
    }

    private fun installObserversIfNeeded() {
        if (observersInstalled) return
        val am = audioManager ?: return
        observersInstalled = true
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (!observersReady) { observersReady = true; return }   // kayıt anındaki ilk liste olay sayılmaz
                handleRouteChange(RouteChangeReason.newDeviceAvailable)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                handleRouteChange(RouteChangeReason.oldDeviceUnavailable)
            }
        }, main)
    }

    private var observersReady = false

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                transientLoss = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                onInterruptionBegan?.invoke()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val shouldResume = transientLoss
                transientLoss = false
                onInterruptionEnded?.invoke(shouldResume)
            }
            else -> Unit   // LOSS_TRANSIENT_CAN_DUCK: sistem kısar; kesinti sayılmaz
        }
    }

    private fun handleRouteChange(reason: RouteChangeReason) {
        if (!isActive) return
        onRouteChanged?.invoke(reason, currentRoute)
        // iOS'ta rota değişimi motor yapılandırma değişimini de tetikler (`.AVAudioEngineConfigurationChange`).
        onConfigurationChange?.invoke()
    }

    private fun setKeepScreenOn(on: Boolean) {
        val run = Runnable {
            val w = ActivityHolder.current?.window ?: return@Runnable
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }
}
