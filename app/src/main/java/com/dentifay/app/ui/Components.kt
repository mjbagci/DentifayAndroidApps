package com.dentifay.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr

/** Kapsül gradyan düğme (`GradientButton`). */
@Composable
fun GradientButton(title: String, modifier: Modifier = Modifier, icon: String? = null, isFullWidth: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        modifier
            .pressable(onClick = onClick)
            .then(if (isFullWidth) Modifier.fillMaxWidth() else Modifier)
            .shadow(12.dp, CircleShape, ambientColor = DS.Colors.cyan400.copy(alpha = 0.3f), spotColor = DS.Colors.cyan400.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(DS.Gradients.primaryGradient)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) DfyIcon(icon, size = 14.dp, tint = Color.White)
        Text(title.upperTr(), style = sys(11, FontWeight.Bold, tracking = 1.1), color = Color.White)
    }
}

/** Hayalet kapsül düğme (`GhostButton`). */
@Composable
fun GhostButton(title: String, modifier: Modifier = Modifier, icon: String? = null, isFullWidth: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        modifier
            .plainClickable(onClick = onClick)
            .then(if (isFullWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(CircleShape)
            .background(DS.Colors.surface.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) DfyIcon(icon, size = 14.dp, tint = DS.Colors.slate400)
        Text(title, style = sys(18, FontWeight.SemiBold), color = DS.Colors.onSurface)
    }
}

/** Cam kart kabı (`AppCard`). */
@Composable
fun AppCard(modifier: Modifier = Modifier, cornerRadius: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(modifier.glassCard(cornerRadius)) { content() }
}

/** Sistem ilerleme göstergesi yerine: üç gradyan nokta sırayla nabız atar (`ThinkingDots`). */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier, dotSize: Dp = 6.dp) {
    val t = rememberInfiniteTransition(label = "dots")
    // 0.28 sn'de bir sıradaki nokta (3 nokta → 0.84 sn döngü).
    val tick by t.animateFloat(0f, 3f, infiniteRepeatable(tween(840, easing = LinearEasing), RepeatMode.Restart), label = "tick")
    val phase = tick.toInt().coerceIn(0, 2)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(dotSize * 0.7f), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val active = phase == i
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val s = if (active) 1f else 0.5f
                        scaleX = s; scaleY = s
                        alpha = if (active) 1f else 0.35f
                    }
                    .clip(CircleShape)
                    .background(DS.Gradients.primaryGradient)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E)
@Composable
private fun ComponentsPreview() {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GradientButton("Kaydet", icon = "checkmark")
        GhostButton("Vazgeç", icon = "xmark")
        AppCard { Text("Cam kart", Modifier.padding(20.dp), color = DS.Colors.onSurface) }
        ThinkingDots()
    }
}
