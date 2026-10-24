package com.dentifay.app.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SwiftUI animasyon eğrilerinin Compose karşılıkları.
 * - `.easeOut(duration:)`   → `Motion.easeOut(ms)`
 * - `.easeIn / .easeInOut`  → `Motion.easeIn(ms)` / `Motion.easeInOut(ms)`
 * - `.spring(response:dampingFraction:)` → `Motion.spring(response, damping)`
 * - `.spring(duration:bounce:)`          → `Motion.springDuration(duration, bounce)`
 * - `.snappy / .smooth / .bouncy`        → `Motion.snappy()` / `Motion.smooth()` / `Motion.bouncy()`
 */
object Motion {
    // SwiftUI'nin kullandığı CSS eğrileri.
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    fun <T> easeOut(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, EaseOut)
    fun <T> easeIn(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, EaseIn)
    fun <T> easeInOut(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, EaseInOut)
    fun <T> linear(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, androidx.compose.animation.core.LinearEasing)

    /** SwiftUI `.spring(response:dampingFraction:)` — response = periyot (sn). */
    fun <T> spring(response: Double = 0.55, dampingFraction: Double = 0.825, visibilityThreshold: T? = null): SpringSpec<T> {
        val stiffness = (2 * PI / response).pow(2).toFloat()
        return spring(dampingRatio = dampingFraction.toFloat(), stiffness = stiffness, visibilityThreshold = visibilityThreshold)
    }

    /** SwiftUI `.spring(duration:bounce:)` — bounce 0…1 → damping = 1 - bounce. */
    fun <T> springDuration(duration: Double = 0.5, bounce: Double = 0.0): SpringSpec<T> =
        spring(response = duration, dampingFraction = 1.0 - bounce)

    fun <T> snappy(): SpringSpec<T> = springDuration(0.5, 0.15)
    fun <T> smooth(): SpringSpec<T> = springDuration(0.5, 0.0)
    fun <T> bouncy(): SpringSpec<T> = springDuration(0.5, 0.3)
    /** SwiftUI varsayılan `withAnimation { }` = `.default` (yaylı, ~0.55 sn). */
    fun <T> default(): SpringSpec<T> = spring(0.55, 1.0)
}

// MARK: - Haptics

/** UIKit geri bildirim üreteçlerinin karşılığı. `DentifayApplication` başlatır. */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun predefined(id: Int, fallbackMs: Long, amp: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(id))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp))
        }
    }

    private fun waveform(timings: LongArray, amps: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amps, -1)) }
    }

    fun selection() = predefined(VibrationEffect.EFFECT_TICK, 8, 60)
    fun light() = predefined(VibrationEffect.EFFECT_TICK, 10, 80)
    fun medium() = predefined(VibrationEffect.EFFECT_CLICK, 15, 140)
    fun soft() = predefined(VibrationEffect.EFFECT_TICK, 12, 50)
    fun rigid() = predefined(VibrationEffect.EFFECT_HEAVY_CLICK, 12, 200)
    fun success() = waveform(longArrayOf(0, 12, 60, 18), intArrayOf(0, 120, 0, 180))
    fun warning() = waveform(longArrayOf(0, 18, 80, 12), intArrayOf(0, 180, 0, 120))
    fun error() = waveform(longArrayOf(0, 15, 50, 15, 50, 25), intArrayOf(0, 200, 0, 200, 0, 255))
}

// MARK: - Sakinleştirilmiş efektler (Motion.swift)

/** Statik, üstten alta sönen ince marka kenarı (`.borderBeam`). */
fun Modifier.borderBeam(cornerRadius: Dp = 28.dp, colors: List<Color> = listOf(DS.Colors.cyan400, DS.Colors.purple500), lineWidth: Dp = 1.dp): Modifier =
    border(lineWidth, verticalGradient(colors[0].copy(alpha = 0.35f), colors[1].copy(alpha = 0.15f)), RoundedCornerShape(cornerRadius))

/** Eskiden parıltı süpürmesi; web'de yok → etkisiz (`.shimmer()`). */
@Suppress("UNUSED_PARAMETER")
fun Modifier.shimmer(duration: Double = 2.6): Modifier = this

/** Sayfa başlığı — düz beyaz, kalın, sıkı aralık. */
@Composable
fun ShinyTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = sys(28, FontWeight.Bold)) {
    Text(text, modifier, style = style.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(-0.4f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Color.White)
}

/** Eski "aurora" zemini → web gövde zemini (statik). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, intensity: Double = 0.10) {
    WebBackdrop(modifier, intensity = min(1.0, max(0.4, intensity * 2)))
}

/** Değere kadar sayan sayaç (`NumberTicker`). */
@Composable
fun NumberTicker(value: Int, modifier: Modifier = Modifier, style: TextStyle = sys(10, FontWeight.Bold), color: Color = DS.Colors.cyan400) {
    var display by remember { mutableIntStateOf(0) }
    LaunchedEffect(value) {
        val target = value
        if (target <= 0) { display = 0; return@LaunchedEffect }
        val steps = min(target, 18)
        for (i in 0..steps) {
            display = (target.toDouble() * i / steps).roundToInt()
            delay(28)
        }
        display = target
    }
    Text("$display", modifier, style = style, color = color)
}

// MARK: - MotionExtras

/** Nefes alan ölçek (`.pulse`). */
fun Modifier.pulse(minScale: Float = 1f, maxScale: Float = 1.12f, duration: Double = 1.3): Modifier = composed {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(minScale, maxScale, infiniteRepeatable(Motion.easeInOut<Float>((duration * 1000).toInt()), RepeatMode.Reverse), label = "pulseScale")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Durum noktası: düz nokta + zemin renginde ince halka. */
@Composable
fun PingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .background(color, CircleShape)
            .border(max(1f, size.value * 0.18f).dp, DS.Colors.background, CircleShape)
    )
}

/** Çok hafif, sabit gölge (`.breathingGlow`). */
@Suppress("UNUSED_PARAMETER")
fun Modifier.breathingGlow(color: Color, minRadius: Dp = 12.dp, maxRadius: Dp = 26.dp, duration: Double = 2.6): Modifier =
    shadow(minRadius * 0.6f, CircleShape, clip = false, ambientColor = color.copy(alpha = 0.14f), spotColor = color.copy(alpha = 0.14f))

/** Konumlu öğeler için ölçek + solma girişi (`.blockEntrance`). Gün/hekim değişince yeniden oynar (key). */
fun Modifier.blockEntrance(delay: Double = 0.0, key: Any? = Unit): Modifier = composed {
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay((delay * 1000).toLong())
        anim.animateTo(1f, Motion.spring(0.5, 0.82))
    }
    graphicsLayer {
        val p = anim.value
        alpha = p.coerceIn(0f, 1f)
        val s = 0.92f + 0.08f * p
        scaleX = s; scaleY = s
        transformOrigin = TransformOrigin(0f, 0f)
    }
}
