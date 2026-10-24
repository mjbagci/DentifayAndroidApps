package com.dentifay.app.aihub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv

/** Gradyan ikon kutulu büyük modül kartı (`AIHubCard`). */
@Composable
fun AIHubCard(
    title: String,
    subtitle: String,
    icon: String,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    action: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .plainClickable(onClick = action)
            .glassCard(28.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val glow = gradientColors.firstOrNull()?.copy(alpha = 0.4f) ?: Color.Transparent
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .size(56.dp)
                .shadow(10.dp, shape, ambientColor = glow, spotColor = glow)
                .clip(shape)
                .background(
                    relativeLinearGradient(
                        gradientColors.mapIndexed { i, c -> (if (gradientColors.size == 1) 0f else i / (gradientColors.size - 1f)) to c },
                        Offset(0f, 0f), Offset(1f, 1f),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            DfyIcon(icon, size = 28.dp, tint = Color.White)
        }
        Text(title, style = sys(24, FontWeight.SemiBold), color = DS.Colors.onSurface)
        Text(subtitle, style = sys(14), color = DS.Colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AIHubCardPreview() {
    PreviewEnv {
        AIHubCard(
            title = "Dentifay Asistan",
            subtitle = "Soru sorun, raporlar ve grafikler oluşturun.",
            icon = "bubble.left.and.text.bubble.right",
            gradientColors = listOf(DS.Colors.purple500, DS.Colors.cyan400),
            modifier = Modifier.padding(16.dp),
        )
    }
}
