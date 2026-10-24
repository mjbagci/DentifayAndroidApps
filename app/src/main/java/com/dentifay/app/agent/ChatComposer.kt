package com.dentifay.app.agent

import android.provider.Settings
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.credit.KrediIpucu
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi

/**
 * 44 dp dinlenme yüksekliğinde sohbet kompoziti.
 * Tek yükseklik otoritesi `maxLines = 5`, tek taban `heightIn(min = 32.dp)` (sabit yükseklik YOK).
 *
 * Sondaki kümede aynı anda EN FAZLA 2 düğme:
 *   ÇIPLAK GLİF = bu alana etki eden eylem (dikte).
 *   DOLU DAİRE  = turu yöneten eylem (gönder / durdur).
 *
 * Geniş pencerede zemin şeridi tam genişliktir; hap ve durum şeridi sohbet sütununa (`AgentViewDefaults.sohbetSutunu`)
 * hizalanır.
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
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Kredi ipucunun işlemi (ör. `ai_agent:chat`); null → ipucu yok. */
    krediIslemi: String? = null,
) {
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
        Column(Modifier.icerikGenisligi(AgentViewDefaults.sohbetSutunu + 32.dp)) {
            StatusStrip(errorText, isDictating, isStreaming, elapsedSeconds, onRetry, onStop)
            Pill(
                draft = draft,
                onDraftChange = onDraftChange,
                isStreaming = isStreaming,
                isDictating = isDictating,
                errorText = errorText,
                krediIslemi = krediIslemi,
                onSend = onSend,
                onStop = onStop,
                onToggleDictation = onToggleDictation,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private val alanStili = sys(16)

// MARK: Hap

@Composable
private fun Pill(
    draft: String,
    onDraftChange: (String) -> Unit,
    isStreaming: Boolean,
    isDictating: Boolean,
    errorText: String?,
    krediIslemi: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleDictation: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val hasText = draft.isNotBlank()
    val azHareket = rememberAzHareket()

    // Dikte nabzı: değer YALNIZ çizimde okunur (her karede yeniden oluşturma yok).
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(isDictating, azHareket) {
        when {
            isDictating && !azHareket ->
                pulse.animateTo(1f, infiniteRepeatable(tween(1100, easing = Motion.EaseInOut), RepeatMode.Reverse))
            isDictating -> pulse.snapTo(1f)
            else -> pulse.animateTo(0f, Motion.easeOut(200))
        }
    }
    // Diğer kenar geçişleri 0.18 sn easeOut.
    val cizgi = animateColorAsState(
        when {
            errorText != null -> DS.Colors.error.copy(alpha = 0.45f)
            focused -> DS.Colors.cyan400.copy(alpha = 0.35f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        Motion.easeOut(180), label = "stroke",
    )
    val dolgu = animateColorAsState(Color.White.copy(alpha = if (focused) 0.07f else 0.05f), Motion.easeOut(180), label = "fill")
    val nabizli = isDictating && errorText == null

    val shape = RoundedCornerShape(22.dp)
    val placeholder = when {
        isStreaming -> "Sıradaki sorunuzu yazabilirsiniz"
        isDictating -> "Dinliyorum…"
        else -> "Bir şey sorun…"
    }
    val textStyle = remember { alanStili.copy(color = DS.Colors.onSurface) }

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .drawWithCache {
                val sw = 1.dp.toPx()
                val r = CornerRadius(22.dp.toPx() - sw / 2)
                onDrawWithContent {
                    drawRect(dolgu.value)
                    drawContent()
                    val renk = if (nabizli) DS.Colors.error.copy(alpha = 0.32f + 0.38f * pulse.value) else cizgi.value
                    drawRoundRect(
                        renk, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                        cornerRadius = r, style = Stroke(sw),
                    )
                }
            }
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
            // 🔴 akış sırasında yazılabilir; yalnız gönderme kilitli.
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    // Donanım klavyesi (tablet): Enter gönderir, Shift+Enter yeni satır.
                    .onPreviewKeyEvent { e ->
                        if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        if (e.isShiftPressed) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown && hasText && !isStreaming) onSend()
                        true
                    }
                    .semantics { contentDescription = "Mesaj" },
                textStyle = textStyle,
                cursorBrush = SolidColor(DS.Colors.cyan400),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (hasText && !isStreaming) onSend() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                placeholder, style = alanStili, color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        TrailingCluster(isStreaming, isDictating, hasText, krediIslemi, onSend, onStop, onToggleDictation)
    }
}

// MARK: Sondaki küme (≤ 2 düğme)

@Composable
private fun TrailingCluster(
    isStreaming: Boolean,
    isDictating: Boolean,
    hasText: Boolean,
    krediIslemi: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleDictation: () -> Unit,
) {
    val enter = fadeIn(Motion.spring(0.28, 0.80)) + scaleIn(Motion.spring(0.28, 0.80), initialScale = 0.6f)
    val exit = fadeOut(Motion.spring(0.28, 0.80)) + scaleOut(Motion.spring(0.28, 0.80), targetScale = 0.6f)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (krediIslemi != null && !isStreaming) KrediIpucu(islem = krediIslemi)
        AnimatedVisibility(isStreaming, enter = enter, exit = exit) {
            FilledCircle("stop.fill", SolidColor(DS.Colors.error.copy(alpha = 0.90f)), "Yanıtı durdur", DS.Colors.onError) {
                Haptics.medium(); onStop()
            }
        }
        AnimatedVisibility(!isStreaming && (isDictating || !hasText), enter = enter, exit = exit) {
            GlyphButton(
                if (isDictating) "mic.fill" else "mic",
                if (isDictating) DS.Colors.error else DS.Colors.onSurfaceVariant,
                if (isDictating) "Sesli yazmayı durdur" else "Sesli yaz",
            ) { Haptics.light(); onToggleDictation() }
        }
        AnimatedVisibility(!isStreaming && hasText, enter = enter, exit = exit) {
            FilledCircle("arrow.up", DS.Gradients.primaryGradient, "Gönder") { Haptics.light(); onSend() }
        }
    }
}

@Composable
private fun GlyphButton(symbol: String, tint: Color, label: String, action: () -> Unit) {
    // 32 dp glif, ±6 dp dokunma payı → 44 dp hedef.
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .pressable(scale = 0.90f, onClick = action)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        DfyIcon(symbol, size = 19.dp, tint = tint)
    }
}

@Composable
private fun FilledCircle(symbol: String, fill: Brush, label: String, glyphColor: Color = Color.White, action: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .pressable(scale = 0.90f, onClick = action)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
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
        val satir = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp)
        when (k) {
            StripKind.error -> Row(satir, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DfyIcon("exclamationmark.triangle.fill", size = 11.dp, tint = DS.Colors.error)
                Text(errorText ?: "", Modifier.weight(1f), style = sys(11, FontWeight.Medium), color = DS.Colors.error)
                Spacer(Modifier.size(2.dp))
                Text(
                    "Tekrar dene",
                    Modifier
                        .semantics { role = Role.Button }
                        .plainClickable(onClick = onRetry)
                        .padding(vertical = 4.dp),
                    style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1,
                )
            }
            StripKind.dictating -> Row(satir, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DfyIcon("mic.fill", size = 11.dp, tint = DS.Colors.error)
                Text(
                    "Dinliyorum — konuşun, sonra düzenleyip gönderin", Modifier.weight(1f),
                    style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant,
                )
            }
            StripKind.streaming -> Row(satir, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Asistan yanıtlıyor… · $elapsedSeconds sn", Modifier.weight(1f),
                    style = sys(11, FontWeight.Medium).monoDigits(), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "İptal et",
                    Modifier
                        .semantics { role = Role.Button }
                        .plainClickable { Haptics.rigid(); onStop() }
                        .padding(vertical = 4.dp),
                    style = sys(12, FontWeight.SemiBold), color = DS.Colors.error, maxLines = 1,
                )
            }
            StripKind.none -> Box(Modifier.fillMaxWidth())
        }
    }
}

/** Sistem "animasyon süresi ölçeği = 0" (hareketi azalt) — sonsuz animasyonlar durur. */
@Composable
internal fun rememberAzHareket(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
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
                errorText = "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin.", elapsedSeconds = 0,
                onSend = {}, onStop = {}, onToggleDictation = {}, onRetry = {}, krediIslemi = "ai_agent:chat",
            )
            Spacer(Modifier.size(24.dp))
            ChatComposer(
                draft = draft, onDraftChange = { draft = it }, isStreaming = true, isDictating = false,
                errorText = null, elapsedSeconds = 12,
                onSend = {}, onStop = {}, onToggleDictation = {}, onRetry = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 300)
@Composable
private fun ChatComposerTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            ChatComposer(
                draft = "", onDraftChange = {}, isStreaming = false, isDictating = true,
                errorText = null, elapsedSeconds = 0,
                onSend = {}, onStop = {}, onToggleDictation = {}, onRetry = {}, krediIslemi = "ai_agent:chat",
            )
        }
    }
}
