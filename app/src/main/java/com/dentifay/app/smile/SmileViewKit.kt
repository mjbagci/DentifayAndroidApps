package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Gülüş ekranlarının ortak küçük parçaları (yalnız görünüm katmanı).

/** SwiftUI `Picker(.segmented)` karşılığı: koyu kapsül zemin + kayan seçili segment. */
@Composable
internal fun <T> SmileSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String? = { it.toString() },
    icon: (T) -> String? = { null },
    animationMs: Int = 250,
) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    val pos by animateFloatAsState(index.toFloat(), Motion.easeInOut(animationMs), label = "segment")
    BoxWithConstraints(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(2.dp)
    ) {
        val segW: Dp = maxWidth / max(options.size, 1)
        Box(
            Modifier
                .offset(x = segW * pos)
                .width(segW)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(7.dp))
                .clip(RoundedCornerShape(7.dp))
                .background(hexSegment)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { opt ->
                val on = opt == selected
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .plainClickable { if (!on) onSelect(opt) },
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (on) Color.White else DS.Colors.onSurfaceVariant
                    icon(opt)?.let { DfyIcon(it, size = 13.dp, tint = tint) }
                    label(opt)?.let {
                        Text(it, style = sys(13, if (on) FontWeight.SemiBold else FontWeight.Medium), color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val hexSegment = Color(0xFF636366).copy(alpha = 0.55f)

/** NavigationStack + `.navigationBarTitleDisplayMode(.inline)` + sağda "Kapat" düğmesi. */
@Composable
internal fun SmileInlineNavBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailingText: String? = "Kapat",
    onTrailing: () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Text(
            title, Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
            style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, textAlign = TextAlign.Center,
        )
        if (trailingText != null) {
            Text(
                trailingText,
                Modifier.align(Alignment.CenterEnd).plainClickable(onClick = onTrailing).padding(vertical = 8.dp),
                style = sys(17, FontWeight.Normal), color = DS.Colors.cyan400,
            )
        }
    }
}

/** Geri oku (NavigationStack geri düğmesi). */
@Composable
internal fun SmileBackButton(text: String = "Geri", onClick: () -> Unit) {
    Row(
        Modifier.plainClickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DfyIcon("chevron.left", size = 17.dp, tint = DS.Colors.cyan400)
        Text(text, style = sys(17), color = DS.Colors.cyan400)
    }
}

/** SwiftUI `ProgressView()` (küçük döner gösterge). */
@Composable
internal fun SmileSpinner(modifier: Modifier = Modifier, color: Color = DS.Colors.cyan400, size: Dp = 18.dp) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = (size.value / 9f).coerceAtLeast(1.5f).dp, trackColor = Color.Transparent)
}

/** `cgImage.cropping(to:)` — dikdörtgen görüntü dışına taşarsa kırpılır; boşsa null. */
internal fun Bitmap.croppedTo(rect: Rect?): Bitmap? {
    rect ?: return null
    val l = max(0, rect.left.roundToInt())
    val t = max(0, rect.top.roundToInt())
    val r = min(width, rect.right.roundToInt())
    val b = min(height, rect.bottom.roundToInt())
    if (r - l <= 0 || b - t <= 0) return null
    return Bitmap.createBitmap(this, l, t, r - l, b - t)
}

/** Önizlemeler için paketteki örnek görsel. */
@Composable
internal fun rememberSampleBitmap(@DrawableRes id: Int): Bitmap {
    val ctx = LocalContext.current
    return remember(id) { BitmapFactory.decodeResource(ctx.resources, id) }
}

/** iOS `Slider(...).tint(cyan400)` görünümü: beyaz tutamaç, turkuaz dolu iz. */
@Composable
internal fun SmileSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    Slider(
        value = value, onValueChange = onValueChange, valueRange = range, steps = steps, modifier = modifier.height(28.dp),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = DS.Colors.cyan400,
            inactiveTrackColor = Color.White.copy(alpha = 0.16f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}
