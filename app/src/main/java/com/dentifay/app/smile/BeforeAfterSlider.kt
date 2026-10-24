package com.dentifay.app.smile

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.verticalGradient
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sürüklenebilir önce/sonra karşılaştırması. Sol taraf "önce", sağ taraf
 * "sonra" görüntüsünü gösterir; ilk görünümde kaydırılabilir olduğunu
 * anlatan kısa bir ipucu animasyonu oynar.
 *
 * Performans: kaydırıcının konumu (her karede değişir) yalnız çizim/yerleşim aşamasında okunur — `drawWithContent`,
 * `offset { }`, `graphicsLayer { }`; kompozisyon yalnız etiket eşikleri ve %10'luk erişilebilirlik adımlarında değişir.
 * Oran korunur: `modifier`e `heightIn(max = …)` verilirse genişlik orana göre küçülür (pencereye sığar).
 */
@Composable
fun BeforeAfterSlider(
    before: Bitmap,
    after: Bitmap,
    modifier: Modifier = Modifier,
    beforeLabel: String = "ÖNCE",
    afterLabel: String = "SONRA",
    cornerRadius: Dp = 22.dp,
    hintOnAppear: Boolean = true,
) {
    val position = remember { Animatable(0.5f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    val shape = RoundedCornerShape(cornerRadius)
    val lineBrush = remember { verticalGradient(DS.Colors.cyan400, Color.White, DS.Colors.accentViolet) }

    // İpucu: ortadan sola, sağa, ortaya (sürükleme başlarsa durur).
    LaunchedEffect(after) {
        if (!hintOnAppear) return@LaunchedEffect
        position.snapTo(0.5f)
        delay(450)
        for ((target, ms) in listOf(0.22f to 650, 0.78f to 900, 0.5f to 600)) {
            if (dragging) return@LaunchedEffect
            launch { position.animateTo(target, Motion.easeInOut(ms)) }
            delay(ms.toLong())
        }
    }

    // Yalnız eşik geçişlerinde değişen türetilmiş durumlar.
    val showBefore by remember { derivedStateOf { position.value > 0.18f } }
    val showAfter by remember { derivedStateOf { position.value < 0.82f } }
    val percent by remember { derivedStateOf { (position.value * 10).roundToInt() * 10 } }
    // Erişilebilirlik değeri kompozisyonda okunur (%10 adımlarla güncellenir).
    val yuzde = percent

    BoxWithConstraints(
        modifier
            .aspectRatio(before.width.toFloat() / max(before.height, 1).toFloat())
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .semantics {
                contentDescription = "Önce ve sonra karşılaştırması"
                stateDescription = "Yüzde $yuzde önce"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!dragging) { dragging = true; Haptics.selection() }
                    val w = max(size.width, 1).toFloat()
                    scope.launch { position.snapTo((down.position.x / w).coerceIn(0f, 1f)) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val x = change.position.x
                        scope.launch { position.snapTo((x / w).coerceIn(0f, 1f)) }
                        change.consume()
                    }
                    dragging = false
                }
            }
    ) {
        val w = maxWidth
        val h = maxHeight

        Image(afterImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Image(
            beforeImg, null,
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = size.width * position.value) { this@drawWithContent.drawContent() } },
            contentScale = ContentScale.Crop,
        )

        // Etiketler
        val beforeAlpha by animateFloatAsState(if (showBefore) 1f else 0f, Motion.easeInOut(200), label = "bLabel")
        val afterAlpha by animateFloatAsState(if (showAfter) 1f else 0f, Motion.easeInOut(200), label = "aLabel")
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SliderTag(beforeLabel, accent = false, modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = beforeAlpha })
            Spacer(Modifier.width(8.dp))
            SliderTag(afterLabel, accent = true, modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = afterAlpha })
        }

        // Ayırıcı çizgi + tutamaç (konum yalnız yerleşimde okunur)
        Box(
            Modifier
                .offset { IntOffset((w.toPx() * position.value - 1.dp.toPx()).roundToInt(), 0) }
                .width(2.dp)
                .fillMaxHeight()
                .shadow(4.dp, RoundedCornerShape(1.dp), ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
                .background(lineBrush)
        )

        val handleScale by animateFloatAsState(if (dragging) 1.15f else 1f, Motion.spring(0.3, 0.6), label = "handleScale")
        val glow by animateDpAsState(if (dragging) 14.dp else 8.dp, Motion.spring(0.3, 0.6), label = "handleGlow")
        val glowColor = DS.Colors.cyan400.copy(alpha = 0.45f)
        Box(
            Modifier
                .offset { IntOffset((w.toPx() * position.value - 20.dp.toPx()).roundToInt(), (h / 2 - 20.dp).roundToPx()) }
                .size(40.dp)
                .graphicsLayer {
                    scaleX = handleScale; scaleY = handleScale
                    shadowElevation = glow.toPx()
                    this.shape = CircleShape
                    clip = false
                    ambientShadowColor = glowColor
                    spotShadowColor = glowColor
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DfyIcon("arrow.left.and.right", size = 13.dp, tint = Color.White)
        }
    }
}

@Composable
private fun SliderTag(text: String, accent: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(CircleShape)
            .background(if (accent) DS.Gradients.primaryGradient else androidx.compose.ui.graphics.SolidColor(Color.Black.copy(alpha = 0.45f)))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = sys(10, FontWeight.Bold, tracking = 1.1), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun BeforeAfterSliderPreview() {
    PreviewEnv {
        val before = rememberSampleBitmap(R.drawable.smile_before)
        val after = rememberSampleBitmap(R.drawable.smile_after)
        Box(Modifier.padding(20.dp)) {
            BeforeAfterSlider(before, after, afterLabel = "SİMÜLASYON")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun BeforeAfterSliderTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        val before = rememberSampleBitmap(R.drawable.smile_before)
        val after = rememberSampleBitmap(R.drawable.smile_after)
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            BeforeAfterSlider(before, after, afterLabel = "SİMÜLASYON")
        }
    }
}
