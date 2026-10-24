package com.dentifay.app.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dentifay.app.agent.AgentSessionManager
import com.dentifay.app.agent.AgentWritePreview
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.credit.CreditRequiredCard
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.SmileCreditsView
import com.dentifay.app.theme.AuroraBackground
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots

// MARK: - Canlı sesli asistan ekranı
//
// Yeni ses altyapısına (LiveVoiceSession) bağlı, eller serbest ekran:
// AudioSessionCoordinator + VoiceAudioGraph (AEC) + SpeechPipeline + Azure TTS hattı.
//
// Otomatik kesme YOK — kullanıcı bitince "Bitir & Gönder"e basar. Asistanı kesmek
// için orb'a dokunmak yeterli.

@Composable
fun VoiceConversationView(vm: AgentSessionManager, onDismiss: () -> Unit) {
    val session = remember { LiveVoiceSession(vm) }
    /** Kredi gerekli (402) → kredi paketleri ekranı. */
    var creditSheet by remember { mutableStateOf<CreditSheetItem?>(null) }
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current

    // RECORD_AUDIO izni: ekran açılırken istenir; sonuç oturuma iletilir (reddedilirse
    // oturum "Mikrofon/konuşma izni gerekli." hatasını gösterir).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.speech.notePermissionResult(granted)
        session.start()
    }

    LaunchedEffect(Unit) {
        if (inPreview) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) session.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { session.dispose() } }

    val phase = session.phase
    val credit = session.creditRequired
    val preview = session.pendingWrite

    // Çıkış animasyonu sürerken son değer çizilsin.
    val lastNotice = remember { mutableStateOf("") }
    session.noticeText?.let { lastNotice.value = it }
    val lastCredit = remember { mutableStateOf<CreditRequired?>(null) }
    if (credit != null) lastCredit.value = credit
    val lastPreview = remember { mutableStateOf<AgentWritePreview?>(null) }
    if (preview != null) lastPreview.value = preview

    val springF = Motion.spring<Float>(0.35, 0.82)
    val springO = Motion.spring<IntOffset>(0.35, 0.82)

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        AuroraBackground(intensity = 0.14)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            TopBar(onClose = {
                session.stop()
                onDismiss()
            })
            AnimatedVisibility(session.noticeText != null, enter = fadeIn(springF), exit = fadeOut(springF)) {
                NoticeBanner(lastNotice.value)
            }
            Spacer(Modifier.weight(1f))
            OrbSection(session)
            Spacer(Modifier.weight(1f))
            TranscriptSection(displayText(session, vm), listening = phase == LiveVoiceSession.Phase.listening)
            AnimatedVisibility(session.choices.isNotEmpty(), enter = fadeIn(springF), exit = fadeOut(springF)) {
                ChoicesSection(session)
            }
            AnimatedVisibility(
                phase == LiveVoiceSession.Phase.listening,
                enter = fadeIn(springF) + slideInVertically(springO) { it },
                exit = fadeOut(springF) + slideOutVertically(springO) { it },
            ) {
                ListeningControls(session)
            }
            AnimatedVisibility(
                phase == LiveVoiceSession.Phase.thinking,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(springF) + scaleIn(springF, initialScale = 0.92f),
                exit = fadeOut(springF) + scaleOut(springF, targetScale = 0.92f),
            ) {
                CancelThinkingButton(session)
            }
            AnimatedVisibility(
                phase == LiveVoiceSession.Phase.credit && credit != null,
                enter = fadeIn(springF) + slideInVertically(springO) { it },
                exit = fadeOut(springF) + slideOutVertically(springO) { it },
            ) {
                lastCredit.value?.let { c ->
                    CreditSection(
                        credit = c,
                        onBuy = { creditSheet = CreditSheetItem(reason = c.message) },
                        onRetry = { session.retryAfterCredit() },
                        onBackToListening = { session.dismissCreditRequired() },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        AnimatedVisibility(
            phase == LiveVoiceSession.Phase.confirming && preview != null,
            enter = fadeIn(springF), exit = fadeOut(springF),
        ) {
            lastPreview.value?.let { ConfirmOverlay(it, session) }
        }
    }

    DfySheet(isPresented = creditSheet != null, onDismiss = { creditSheet = null }) { dismiss ->
        SmileCreditsView(reason = creditSheet?.reason, onDismiss = dismiss)
    }
}

// MARK: Seslendirme bilgisi (ör. günlük hak doldu → cihaz sesi)

@Composable
private fun NoticeBanner(text: String) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DS.Colors.orange400.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DfyIcon("speaker.slash.fill", size = 12.dp, tint = DS.Colors.orange400)
        Text(text, Modifier.weight(1f), style = sys(12, FontWeight.Medium), color = DS.Colors.orange400)
    }
}

// MARK: Kredi gerekli (yalnız .credit)

@Composable
private fun CreditSection(credit: CreditRequired, onBuy: () -> Unit, onRetry: () -> Unit, onBackToListening: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CreditRequiredCard(credit = credit, onBuy = onBuy, onRetry = onRetry)
        Row(
            Modifier
                .pressable(onClick = onBackToListening)
                .height(44.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DfyIcon("mic.fill", size = 14.dp, tint = DS.Colors.onSurface)
            Text("Dinlemeye dön", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
        }
    }
}

// MARK: Dinleme kontrolleri (yalnız .listening)
// Otomatik kesme YOK — kullanıcı bitince BİTİR & GÖNDER'e basar.
// SIFIRLA: konuşmayı modele göndermeden atıp sıfırdan başlar.

@Composable
private fun ListeningControls(session: LiveVoiceSession) {
    val hasText = session.transcript.trim().isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sıfırla (ikincil, hata tonlu çerçeve)
        val resetTint = if (hasText) DS.Colors.error else DS.Colors.outline
        Row(
            Modifier
                .alpha(if (hasText) 1f else 0.5f)
                .pressable(enabled = hasText) { session.restartListening() }
                .height(52.dp)
                .border(1.dp, resetTint.copy(alpha = 0.4f), CircleShape)
                .padding(horizontal = 18.dp)
                .semantics { contentDescription = "Konuşmayı sıfırla" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            DfyIcon("arrow.counterclockwise", size = 15.dp, tint = resetTint)
            Text("Sıfırla", style = sys(15, FontWeight.SemiBold), color = resetTint)
        }

        // Bitir & Gönder (birincil, gradyan)
        Row(
            Modifier
                .weight(1f)
                .pressable(enabled = hasText) { session.finishListening() }
                .then(
                    if (hasText) Modifier.shadow(
                        12.dp, CircleShape, clip = false,
                        ambientColor = DS.Colors.cyan400.copy(alpha = 0.3f), spotColor = DS.Colors.cyan400.copy(alpha = 0.3f),
                    ) else Modifier
                )
                .height(52.dp)
                .clip(CircleShape)
                .then(
                    if (hasText) Modifier.background(DS.Gradients.primaryGradient)
                    else Modifier.background(Color.White.copy(alpha = 0.08f))
                )
                .semantics { contentDescription = "Bitir ve gönder" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            DfyIcon("paperplane.fill", size = 16.dp, tint = Color.White)
            Text("Bitir & Gönder", style = sys(16, FontWeight.Bold), color = Color.White)
        }
    }
}

// MARK: Düşünmeyi durdur (yalnız .thinking)

@Composable
private fun CancelThinkingButton(session: LiveVoiceSession) {
    Row(
        Modifier
            .padding(top = 10.dp)
            .pressable { session.cancelThinking() }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, DS.Colors.error.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { contentDescription = "Düşünmeyi durdur" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DfyIcon("xmark.circle.fill", size = 15.dp, tint = DS.Colors.error)
        Text("Düşünmeyi durdur", style = sys(15, FontWeight.SemiBold), color = DS.Colors.error)
    }
}

// MARK: Üst bar

@Composable
private fun TopBar(onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dentifay Asistan", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text("Canlı Sesli Asistan", style = sys(11, FontWeight.Medium), color = DS.Colors.cyan400)
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .plainClickable(onClick = onClose)
                .semantics { contentDescription = "Sesli konuşmayı kapat" },
            contentAlignment = Alignment.Center,
        ) {
            DfyIcon("xmark", size = 16.dp, tint = DS.Colors.onSurface)
        }
    }
}

// MARK: Orb + durum

@Composable
private fun OrbSection(session: LiveVoiceSession) {
    val phase = session.phase
    val status = statusText(session)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(
            Modifier
                .plainClickable { session.tapOrb() }
                .semantics { contentDescription = status },
            contentAlignment = Alignment.Center,
        ) {
            AIOrb(
                size = 210.dp,
                isAnimating = phase != LiveVoiceSession.Phase.error,
                audioLevel = if (phase == LiveVoiceSession.Phase.listening || phase == LiveVoiceSession.Phase.speaking) session.micLevel.toFloat() else 0f,
            )
            if (phase == LiveVoiceSession.Phase.thinking) ThinkingDots(dotSize = 9.dp)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phase == LiveVoiceSession.Phase.listening) {
                Box(Modifier.size(8.dp).alpha(0.9f).background(DS.Colors.error, CircleShape))
            }
            Text(status, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private fun statusText(session: LiveVoiceSession): String = when (session.phase) {
    LiveVoiceSession.Phase.starting -> "Başlatılıyor…"
    LiveVoiceSession.Phase.listening -> "Dinliyorum — bitince gönderin"
    LiveVoiceSession.Phase.thinking -> "Düşünüyorum…"
    LiveVoiceSession.Phase.speaking -> "Yanıtlıyor…"
    LiveVoiceSession.Phase.confirming -> "Onayınızı bekliyorum"
    LiveVoiceSession.Phase.credit -> session.creditRequired?.title ?: "Kredi yetersiz"
    LiveVoiceSession.Phase.error -> session.errorText ?: "Bir sorun oluştu"
    LiveVoiceSession.Phase.ended -> ""
}

// MARK: Transkript / yanıt

@Composable
private fun TranscriptSection(text: String, listening: Boolean) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        withFrameNanos { }   // yeni metin ölçülsün
        scroll.animateScrollTo(scroll.maxValue, Motion.easeOut<Float>(150))
    }
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text(
                text,
                Modifier.fillMaxWidth().padding(14.dp),
                style = sys(17, if (listening) FontWeight.Normal else FontWeight.Medium),
                color = if (listening) DS.Colors.onSurface else DS.Colors.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(1.dp))
        }
    }
}

private fun displayText(session: LiveVoiceSession, vm: AgentSessionManager): String = when (session.phase) {
    LiveVoiceSession.Phase.listening ->
        session.transcript.ifEmpty { "Konuşun… Bitirince \"Bitir & Gönder\"e basın." }
    LiveVoiceSession.Phase.speaking, LiveVoiceSession.Phase.thinking, LiveVoiceSession.Phase.confirming ->
        session.assistantText
    // Kartta kredi metni var; burada gönderilemeyen soru durur (Tekrar dene onu yollar).
    LiveVoiceSession.Phase.credit ->
        if (vm.lastUserText.isEmpty()) "" else "Sorunuz: “${vm.lastUserText}”"
    LiveVoiceSession.Phase.error -> session.errorText ?: ""
    LiveVoiceSession.Phase.starting, LiveVoiceSession.Phase.ended -> ""
}

// MARK: Seçenekler (yapısal — regex tahmini YOK)

@Composable
private fun ChoicesSection(session: LiveVoiceSession) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Birini seçin", style = sys(11, FontWeight.Bold, tracking = 1), color = DS.Colors.onSurfaceVariant)
        session.choices.forEach { opt ->
            val label = opt.subtitle?.let { "${opt.title}, $it" } ?: opt.title
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable { session.chooseOption(opt) }
                    .glassCard(14.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(opt.title, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
                    opt.subtitle?.let { subtitle ->
                        Text(subtitle, style = sys(12), color = DS.Colors.onSurfaceVariant)
                    }
                }
                DfyIcon("arrow.up.left.circle.fill", tint = DS.Colors.cyan400)
            }
        }
    }
}

// MARK: Yazma onayı (tam ekran kart + biyometrik L3)

@Composable
private fun ConfirmOverlay(preview: AgentWritePreview, session: LiveVoiceSession) {
    val tone = if (preview.requiresDoubleConfirm) DS.Colors.error else DS.Colors.orange400
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .plainClickable { },   // alttaki ekrana dokunuş geçmesin
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(DS.Colors.surfaceContainer)
                .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DfyIcon("exclamationmark.shield.fill", tint = tone)
                Text(
                    if (preview.requiresDoubleConfirm) "Kalıcı İşlem Onayı" else "İşlem Onayı",
                    style = sys(16, FontWeight.Bold), color = DS.Colors.onSurface,
                )
            }
            preview.entity?.let { entity ->
                val count = preview.affectedCount?.let { " · $it kayıt" } ?: ""
                Text(
                    "${preview.operation ?: "İşlem"} · $entity$count",
                    style = sys(14), color = DS.Colors.onSurfaceVariant,
                )
            }
            if (session.assistantText.isNotEmpty()) {
                Text(session.assistantText, style = sys(15), color = DS.Colors.onSurface)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .then(
                            if (preview.requiresDoubleConfirm) Modifier.background(DS.Colors.error)
                            else Modifier.background(DS.Gradients.primaryGradient)
                        )
                        .plainClickable { session.confirmWrite() }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    if (preview.requiresDoubleConfirm) DfyIcon("faceid", size = 14.dp, tint = Color.White)
                    Text(
                        if (preview.requiresDoubleConfirm) "Evet, kalıcı olarak uygula" else "Onayla",
                        style = sys(14, FontWeight.SemiBold), color = Color.White, maxLines = 1,
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .plainClickable { session.cancelWrite() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Vazgeç", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun VoiceConversationViewPreview() {
    PreviewEnv {
        val vm = remember { AgentSessionManager() }
        VoiceConversationView(vm = vm, onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun VoiceConfirmOverlayPreview() {
    PreviewEnv {
        val vm = remember { AgentSessionManager() }
        val session = remember { LiveVoiceSession(vm) }
        ConfirmOverlay(
            AgentWritePreview(operation = "Güncelle", entity = "Randevu", risk = "medium", affectedCount = 1, requiresDoubleConfirm = true),
            session,
        )
    }
}
