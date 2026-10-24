package com.dentifay.app.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv

/**
 * 44 dp dinlenme yüksekliğinde sohbet kompoziti.
 * Tek yükseklik otoritesi `maxLines = 5`, tek taban `heightIn(min = 32.dp)`.
 *
 * Sondaki kümede aynı anda EN FAZLA 2 düğme:
 *   ÇIPLAK GLİF = bu alana etki eden eylem (dikte).
 *   DOLU DAİRE  = başka moda götüren eylem (sesli sohbet / gönder / durdur).
 */
@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isStreaming: Boolean,
    isDictating: Boolean,
    errorText: String?,
    elapsedSeconds: Int,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleDictation: () -> Unit,
    onVoiceMode: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(isDictating) {
        if (isDictating) pulse.animateTo(1f, infiniteRepeatable(tween(1100, easing = Motion.EaseInOut), RepeatMode.Reverse))
        else pulse.animateTo(0f, Motion.easeOut(200))
    }
    val hasText = draft.isNotBlank()

    Column(
        modifier
            .fillMaxWidth()
            // `.ultraThinMaterial` karşılığı: yarı saydam koyu yüzey + üstte 0.5 dp ışık çizgisi.
            .background(DS.Colors.surfaceContainerLow.copy(alpha = 0.86f))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.07f), topLeft = Offset.Zero, size = size.copy(height = 0.5.dp.toPx()))
            }
            .navigationBarsPadding()
    ) {
        StatusStrip(errorText, isDictating, isStreaming, elapsedSeconds, onRetry, onStop)
        Pill(
            draft, onDraftChange, isStreaming, isDictating, errorText, hasText, focused, { focused = it }, pulse.value,
            onSend, onStop, onToggleDictation, onVoiceMode,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

// MARK: Hap

@Composable
private fun Pill(
    draft: String,
    onDraftChange: (String) -> Unit,
    isStreaming: Boolean,
    isDictating: Boolean,
    errorText: String?,
    hasText: Boolean,
    focused: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    pulse: Float,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleDictation: () -> Unit,
    onVoiceMode: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val placeholder = when {
        isStreaming -> "Sıradaki sorunuzu yazabilirsiniz"
        isDictating -> "Dinliyorum…"
        else -> "Bir şey sorun…"
    }
    val strokeTarget = when {
        errorText != null -> DS.Colors.error.copy(alpha = 0.45f)
        isDictating -> DS.Colors.error.copy(alpha = 0.32f + (0.70f - 0.32f) * pulse)
        focused -> DS.Colors.cyan400.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    // Dikte nabzı kendi eğrisiyle akar; diğer geçişler 0.18 sn easeOut.
    val stroke by animateColorAsState(strokeTarget, if (isDictating) tween(0) else Motion.easeOut(180), label = "stroke")
    val fill by animateColorAsState(Color.White.copy(alpha = if (focused) 0.07f else 0.05f), Motion.easeOut(180), label = "fill")

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
            // 🔴 akış sırasında yazılabilir; yalnız gönderme kilitli.
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusedChange(it.isFocused) },
                textStyle = sys(16).copy(color = DS.Colors.onSurface),
                cursorBrush = SolidColor(DS.Colors.cyan400),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (hasText && !isStreaming) onSend() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Text(placeholder, style = sys(16), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1)
                        inner()
                    }
                },
            )
        }
        TrailingCluster(isStreaming, isDictating, hasText, onSend, onStop, onToggleDictation, onVoiceMode)
    }
}

// MARK: Sondaki küme (≤ 2 düğme)

@Composable
private fun TrailingCluster(
    isStreaming: Boolean,
    isDictating: Boolean,
    hasText: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleDictation: () -> Unit,
    onVoiceMode: () -> Unit,
) {
    val enter = fadeIn(Motion.spring(0.28, 0.80)) + scaleIn(Motion.spring(0.28, 0.80), initialScale = 0.6f)
    val exit = fadeOut(Motion.spring(0.28, 0.80)) + scaleOut(Motion.spring(0.28, 0.80), targetScale = 0.6f)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(isStreaming, enter = enter, exit = exit) {
            FilledCircle("stop.fill", SolidColor(DS.Colors.error.copy(alpha = 0.90f)), DS.Colors.onError) { Haptics.medium(); onStop() }
        }
        AnimatedVisibility(!isStreaming && (isDictating || !hasText), enter = enter, exit = exit) {
            GlyphButton(
                if (isDictating) "mic.fill" else "mic",
                if (isDictating) DS.Colors.error else DS.Colors.onSurfaceVariant,
            ) { Haptics.light(); onToggleDictation() }
        }
        AnimatedVisibility(!isStreaming && hasText, enter = enter, exit = exit) {
            FilledCircle("arrow.up", DS.Gradients.primaryGradient) { Haptics.light(); onSend() }
        }
        AnimatedVisibility(!isStreaming && !isDictating && !hasText, enter = enter, exit = exit) {
            FilledCircle("waveform", DS.Gradients.primaryDiagonal) { Haptics.medium(); onVoiceMode() }
        }
    }
}

@Composable
private fun GlyphButton(symbol: String, tint: Color, action: () -> Unit) {
    // 32 dp glif, ±6 dp dokunma payı → 44 dp hedef.
    Box(Modifier.size(44.dp).pressable(scale = 0.90f, onClick = action).padding(6.dp), contentAlignment = Alignment.Center) {
        DfyIcon(symbol, size = 19.dp, tint = tint)
    }
}

@Composable
private fun FilledCircle(symbol: String, fill: Brush, glyphColor: Color = Color.White, action: () -> Unit) {
    Box(Modifier.size(44.dp).pressable(scale = 0.90f, onClick = action).padding(6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            DfyIcon(symbol, size = 15.dp, tint = glyphColor)
        }
    }
}

// MARK: Durum şeridi

private enum class StripKind { none, error, dictating, streaming }

@Composable
private fun StatusStrip(
    errorText: String?,
    isDictating: Boolean,
    isStreaming: Boolean,
    elapsedSeconds: Int,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    val kind = when {
        errorText != null -> StripKind.error
        isDictating -> StripKind.dictating
        isStreaming && elapsedSeconds >= 8 -> StripKind.streaming
        else -> StripKind.none
    }
    AnimatedContent(kind, transitionSpec = { fadeIn(Motion.easeOut(180)) togetherWith fadeOut(Motion.easeOut(180)) }, label = "strip") { k ->
        when (k) {
            StripKind.error -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("exclamationmark.triangle.fill", size = 11.dp, tint = DS.Colors.error)
                Text(errorText ?: "", Modifier.weight(1f, fill = false), style = sys(11, FontWeight.Medium), color = DS.Colors.error)
                Spacer(Modifier.weight(1f).width(8.dp))
                Text("Tekrar dene", Modifier.plainClickable(onClick = onRetry), style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400)
            }
            StripKind.dictating -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("mic.fill", size = 11.dp, tint = DS.Colors.error)
                Text("Dinliyorum — konuşun, sonra düzenleyip gönderin", style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
            }
            StripKind.streaming -> Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Asistan yanıtlıyor… · $elapsedSeconds sn", style = sys(11, FontWeight.Medium).monoDigits(), color = DS.Colors.outline)
                Spacer(Modifier.weight(1f).width(8.dp))
                Text("İptal et", Modifier.plainClickable { Haptics.rigid(); onStop() }, style = sys(12, FontWeight.SemiBold), color = DS.Colors.error)
            }
            StripKind.none -> Box(Modifier.fillMaxWidth())
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun ChatComposerPreview() {
    PreviewEnv {
        var draft by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            ChatComposer(
                draft = "Bu ayın cirosu nedir?", onDraftChange = {}, isStreaming = false, isDictating = false,
                errorText = "Sunucuya ulaşılamadı.", elapsedSeconds = 0,
                onSend = {}, onStop = {}, onToggleDictation = {}, onVoiceMode = {}, onRetry = {},
            )
            Spacer(Modifier.size(24.dp))
            ChatComposer(
                draft = draft, onDraftChange = { draft = it }, isStreaming = true, isDictating = false,
                errorText = null, elapsedSeconds = 12,
                onSend = {}, onStop = {}, onToggleDictation = {}, onVoiceMode = {}, onRetry = {},
            )
        }
    }
}
