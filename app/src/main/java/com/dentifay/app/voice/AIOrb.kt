package com.dentifay.app.voice

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Motion
import com.dentifay.app.ui.PreviewEnv

// MARK: - Ses göstergesi (eski "AI Orb")

/**
 * Sesli akışların ortak göstergesi. Eskiden parçacıklı, parlayan bir küreydi; web
 * arayüzünün sakin diline uyması için düz bir cam disk + ses seviyesine göre genişleyen
 * ince marka halkası oldu. İmza aynı kaldı (`AIOrb(size, isAnimating, audioLevel)`).
 *
 * Başarım: seviye ve nefes animasyonu YALNIZ çizim katmanında (`graphicsLayer`) okunur — saniyede
 * onlarca seviye güncellemesi bileşimi yeniden kurmaz.
 */
@Composable
fun AIOrb(
    size: Dp = 200.dp,
    isAnimating: Boolean = true,
    audioLevel: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Erişilebilirlik "Hareketi azalt" karşılığı: sistem animasyon ölçeği 0.
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    // Swift: yalnız ilk görünüşte (onAppear) isAnimating ise nefes alma başlar.
    val breatheEnabled = remember { isAnimating && !reduceMotion }

    val level = audioLevel.coerceIn(0f, 1f)
    val animatedLevel: State<Float> = animateFloatAsState(level, Motion.easeOut<Float>(120), label = "orbLevel")

    val breathe: State<Float> = if (breatheEnabled) {
        rememberInfiniteTransition(label = "orbBreathe")
            .animateFloat(0f, 1f, infiniteRepeatable(Motion.easeInOut<Float>(2400), RepeatMode.Reverse), label = "breathe")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val diskAnimates = isAnimating && !reduceMotion

    // Görsel yalnız süstür (Swift `accessibilityHidden`): katmanların erişilebilirlik düğümü yoktur; dokunma
    // eylemi ve etiketi çağıranın `modifier`ındadır.
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Ses seviyesi halkası — yalnız konuşurken belirginleşir.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val l = animatedLevel.value
                    val s = 1f + l * 0.22f
                    scaleX = s; scaleY = s
                    alpha = 0.25f + l * 0.6f
                }
                .border(1.5.dp, DS.Gradients.primaryDiagonal, CircleShape)
        )

        Box(
            Modifier
                .fillMaxSize()
                .padding(size * 0.08f)
                .graphicsLayer {
                    val s = if (diskAnimates) 1f + 0.015f * breathe.value else 1f
                    scaleX = s; scaleY = s
                }
                .background(Color.White.copy(alpha = 0.035f), CircleShape)
                .background(DS.Gradients.primaryDiagonal, CircleShape, alpha = 0.14f)
                .border(1.dp, DS.Colors.hairlineStrong, CircleShape)
        )

        // Gradyan dolgulu ikon (foregroundStyle(primaryDiagonal)). iOS'taki `.symbolEffect(.variableColor)`
        // özel (asset) ikonlarda görünür bir etki üretmediği için burada da ek animasyon yoktur.
        DfyIcon(
            "waveform",
            size = size * 0.24f,
            tint = Color.White,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(DS.Gradients.primaryDiagonal, blendMode = BlendMode.SrcIn)
                },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AIOrbPreview() {
    PreviewEnv {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AIOrb(size = 210.dp, audioLevel = 0f)
            AIOrb(size = 160.dp, audioLevel = 0.6f)
            AIOrb(size = 140.dp, isAnimating = false, audioLevel = 0f)
        }
    }
}
