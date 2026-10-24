package com.dentifay.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder

/**
 * Uygulamadaki **TÜM** ses oturumu (AudioManager: odak, iletişim modu, rota) çağrılarının tek sahibi.
 * Başka hiçbir dosya `AudioManager` modunu / odağını değiştirmemelidir.
 *
 * Eski mimaride iki ayrı tüketici aynı süreç-geneli oturumu paylaşıyor ve biri gecikmeli
 * kapatma planlayıp diğerinin canlı kaydını öldürüyordu. Buradaki tek-sahip + refcount modeli
 * o yarışı fiziksel olarak imkânsız kılar: oturum yalnız SON tüketici çıkınca kapanır ve
 * **hiçbir zaman gecikmeli deaktivasyon yazılmaz.**
 *
 * Android karşılıkları: `.duckOthers` → `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; `.voiceChat`
 * (AEC) → `MODE_IN_COMMUNICATION`; `.defaultToSpeaker` → hoparlöre yönlendirme;
 * `isIdleTimerDisabled` → `FLAG_KEEP_SCREEN_ON`.
 */
object AudioSessionCoordinator {

    enum class Consumer { dictation, conversation }
    enum class AudioRoute { builtIn, wired, bluetoothHFP, bluetoothA2DP }
    /** `AVAudioSession.RouteChangeReason` karşılığı (kullanılan alt küme). */
    enum class RouteChangeReason { newDeviceAvailable, oldDeviceUnavailable, unknown }

    private val consumers = mutableSetOf<Consumer>()
    private var observersInstalled = false
    private val main = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    /** Geçici odak kaybından sonra geri gelince "devam et" denir. */
    private var transientLoss = false

    /** Kesinti / rota / yapılandırma bildirimleri — üst katman (LiveVoiceSession) abone olur. */
    var onInterruptionBegan: (() -> Unit)? = null
    var onInterruptionEnded: ((shouldResume: Boolean) -> Unit)? = null
    var onRouteChanged: ((reason: RouteChangeReason, route: AudioRoute) -> Unit)? = null
    var onConfigurationChange: (() -> Unit)? = null

    val isActive: Boolean get() = consumers.isNotEmpty()

    private val audioManager: AudioManager?
        get() = Prefs.appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Oturumu kurar. İdempotent. İkinci tüketici için hiçbir şey yapmaz (konuşma modu hariç). */
    fun acquire(consumer: Consumer) {
        installObserversIfNeeded()
        val wasEmpty = consumers.isEmpty()
        val hadConversation = Consumer.conversation in consumers
        consumers.add(consumer)
        val am = audioManager ?: throw IllegalStateException("AudioManager yok")

        if (wasEmpty) {
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
        // Konuşma modu: AEC/AGC/NS'i MÜMKÜN kılan iletişim modu + hoparlör (kulak hoparlörü değil).
        if (consumer == Consumer.conversation && !hadConversation) enterCommunicationMode(am)
    }

    /** Tüketiciyi bırakır. SON tüketici çıkınca oturumu kapatır.
     *  ⚠️ GECİKMELİ DEAKTİVASYON ASLA YAZILMAZ. */
    fun release(consumer: Consumer) {
        if (!consumers.remove(consumer)) return
        val am = audioManager
        if (consumer == Consumer.conversation && am != null) exitCommunicationMode(am)
        if (consumers.isNotEmpty()) return
        focusRequest?.let { am?.abandonAudioFocusRequest(it) }
        focusRequest = null
        transientLoss = false
        setKeepScreenOn(false)
    }

    val currentRoute: AudioRoute
        get() = audioManager?.let { map(it) } ?: AudioRoute.builtIn

    // MARK: - Private

    private fun enterCommunicationMode(am: AudioManager) {
        if (previousMode == null) previousMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        applySpeakerRoute(am)
    }

    private fun exitCommunicationMode(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            previousSpeaker?.let { runCatching { am.isSpeakerphoneOn = it } }
        }
        previousSpeaker = null
        previousMode?.let { runCatching { am.mode = it } }
        previousMode = null
    }

    /** `.defaultToSpeaker`: kulaklık / Bluetooth yoksa hoparlör. */
    private fun applySpeakerRoute(am: AudioManager) {
        val external = map(am) != AudioRoute.builtIn
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val target = if (external) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
            } else {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }
            if (target != null) runCatching { am.setCommunicationDevice(target) }
        } else {
            @Suppress("DEPRECATION")
            run {
                if (previousSpeaker == null) previousSpeaker = am.isSpeakerphoneOn
                runCatching { am.isSpeakerphoneOn = !external }
            }
        }
    }

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
        val am = audioManager
        if (am != null && Consumer.conversation in consumers) applySpeakerRoute(am)
        onRouteChanged?.invoke(reason, currentRoute)
        // iOS'ta rota değişimi motor yapılandırma değişimini de tetikler.
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
