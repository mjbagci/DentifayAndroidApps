package com.dentifay.app.credit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys

/** iOS varsayılan vurgu rengi (AccentColor boş → sistem mavisi, koyu tema). */
internal val CreditSystemBlue = hex("0a84ff")

/**
 * `NavigationStack` + `.navigationTitle(…)` + `.navigationBarTitleDisplayMode(.inline)` başlık şeridi:
 * ortada başlık, solda iptal, sağda eylem düğmesi.
 */
@Composable
internal fun CreditNavBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            title,
            Modifier.align(Alignment.Center).padding(horizontal = 76.dp),
            style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** Araç çubuğu metin düğmesi (`Button("Kapat")` · `.bold()`). */
@Composable
internal fun CreditBarButton(text: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .alpha(if (enabled) 1f else 0.35f)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        style = sys(17, if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = CreditSystemBlue,
    )
}

/** `Label(text, dfy: icon)` — ikon + metin, boyut yazı tipinden. */
@Composable
internal fun CreditIconLabel(
    text: String,
    icon: String,
    modifier: Modifier = Modifier,
    size: Float = 13f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = DS.Colors.onSurface,
    iconColor: Color = color,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((size * 0.5f).coerceAtLeast(6f).dp), verticalAlignment = verticalAlignment) {
        DfyIcon(icon, size = size.dp, tint = iconColor)
        Text(text, style = sys(size, weight), color = color)
    }
}

/** `ProgressView()` (dönen gösterge). */
@Composable
internal fun CreditSpinner(modifier: Modifier = Modifier, color: Color = DS.Colors.onSurfaceVariant, size: Dp = 20.dp) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
}

/** `ProgressView(value:)` — ince doğrusal çubuk. */
@Composable
internal fun CreditProgressBar(fraction: Double, tint: Color, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(tint),
        )
    }
}

/** `Divider().overlay(Color.white.opacity(a))` */
@Composable
internal fun CreditDivider(alpha: Float = 0.06f) {
    HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = alpha))
}

/** `.contentTransition(.numericText())` — değer değişince dikey kayarak solar. */
@Composable
internal fun CreditNumericText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(Motion.spring(0.4, 0.85)) { it / 2 } + fadeIn(Motion.easeOut(250)))
                .togetherWith(slideOutVertically(Motion.spring(0.4, 0.85)) { -it / 2 } + fadeOut(Motion.easeOut(200)))
                .using(SizeTransform(clip = false))
        },
        modifier = modifier,
        label = "numericText",
    ) { t ->
        Text(t, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCommonPreview() {
    com.dentifay.app.ui.PreviewEnv {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CreditNavBar("Krediler", trailing = { CreditBarButton("Kapat") {} })
            CreditIconLabel("Tekrar dene", "arrow.clockwise", size = 14f, weight = FontWeight.SemiBold)
            CreditProgressBar(0.6, DS.Colors.tertiaryContainer)
            CreditNumericText("118,5", sys(44, FontWeight.Bold), DS.Colors.onSurface)
            CreditSpinner(color = DS.Colors.cyan400)
        }
    }
}
