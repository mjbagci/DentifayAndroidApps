package com.dentifay.app.treatment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.dentifay.app.credit.CreditRequiredCard
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.SmileCreditsView
import com.dentifay.app.theme.AuroraBackground
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots
import com.dentifay.app.voice.AIOrb

/**
 * Sesli Tedavi Planı asistanı. Analiz Merkezi'ndeki "Sesli Tedavi Planı" kartından açılır.
 * Planı AI Agent (mode:"voice") oluşturur; write_preview onayıyla sisteme yazılır.
 */
@Composable
fun TreatmentPlanView(onDismiss: () -> Unit) {
    val vm = remember { TreatmentPlanViewModel() }
    /** Kredi gerekli (402) → kredi paketleri ekranı. */
    var creditSheet by remember { mutableStateOf<CreditSheetItem?>(null) }

    LaunchedEffect(Unit) { vm.onAppear() }
    DisposableEffect(Unit) { onDispose { vm.dispose() } }

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        AuroraBackground(intensity = 0.12)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(subtitle(vm)) { vm.teardown(); onDismiss() }
            // Faz değişimleri Swift'teki gibi animasyonsuz (withAnimation yok).
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (val p = vm.phase) {
                    TreatmentPlanViewModel.Phase.pickPatient -> PatientPicker(vm)
                    TreatmentPlanViewModel.Phase.recording -> RecordingView(vm)
                    TreatmentPlanViewModel.Phase.thinking -> ThinkingView(vm)
                    TreatmentPlanViewModel.Phase.review -> ReviewView(vm)
                    TreatmentPlanViewModel.Phase.submitting -> StatusView("Sisteme kaydediliyor…", null)
                    TreatmentPlanViewModel.Phase.done -> DoneView(vm) { vm.teardown(); onDismiss() }
                    is TreatmentPlanViewModel.Phase.failed -> FailedView(
                        vm, p.message,
                        onBuy = { reason -> creditSheet = CreditSheetItem(reason) },
                        onClose = { vm.teardown(); onDismiss() },
                    )
                }
            }
        }
    }

    DfySheet(isPresented = creditSheet != null, onDismiss = { creditSheet = null }) { dismiss ->
        SmileCreditsView(reason = creditSheet?.reason, onDismiss = dismiss)
    }
}

private fun subtitle(vm: TreatmentPlanViewModel): String = when (vm.phase) {
    TreatmentPlanViewModel.Phase.pickPatient -> "Hasta seçin"
    TreatmentPlanViewModel.Phase.recording, TreatmentPlanViewModel.Phase.thinking -> vm.selectedPatient?.name ?: "Sesli giriş"
    TreatmentPlanViewModel.Phase.review, TreatmentPlanViewModel.Phase.submitting -> vm.selectedPatient?.name ?: "Gözden geçir"
    TreatmentPlanViewModel.Phase.done -> "Tamamlandı"
    is TreatmentPlanViewModel.Phase.failed -> "Hata"
}

// MARK: Üst bar

@Composable
private fun TopBar(subtitle: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Tedavi Planı", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(subtitle, style = sys(11, FontWeight.Medium), color = DS.Colors.cyan400)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).plainClickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            DfyIcon("xmark", size = 16.dp, tint = DS.Colors.onSurface)
        }
    }
}

// MARK: - Hasta seçici

@Composable
private fun ColumnScope.PatientPicker(vm: TreatmentPlanViewModel) {
    SearchBar(vm)
    if (vm.loadingLists) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { ThinkingDots(dotSize = 9.dp) }
    } else {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (vm.searchQuery.trim().isNotEmpty()) {
                PatientSection("ARAMA SONUÇLARI", vm.searchResults, if (vm.isSearching) "Aranıyor…" else "Sonuç yok", vm)
            } else {
                PatientSection("BUGÜNKÜ RANDEVULAR", vm.todayPatients, "Bugün randevu yok", vm)
                PatientSection("BU HAFTA", vm.weekPatients, "Bu hafta başka randevu yok", vm)
            }
        }
    }
}

@Composable
private fun SearchBar(vm: TreatmentPlanViewModel) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (focused) DS.Colors.cyan400.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("magnifyingglass", size = 15.dp, tint = DS.Colors.onSurfaceVariant)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = vm.searchQuery,
                onValueChange = { vm.searchQuery = it; vm.searchChanged() },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                textStyle = sys(16).copy(color = DS.Colors.onSurface),
                cursorBrush = SolidColor(DS.Colors.cyan400),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                decorationBox = { inner ->
                    if (vm.searchQuery.isEmpty()) Text("Hasta ara (ad, soyad)…", style = sys(16), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f))
                    inner()
                },
            )
        }
        if (vm.searchQuery.isNotEmpty()) {
            Box(Modifier.plainClickable { vm.searchQuery = ""; vm.searchChanged() }) {
                DfyIcon("xmark.circle.fill", tint = DS.Colors.outline)
            }
        }
    }
}

@Composable
private fun PatientSection(title: String, patients: List<TreatmentPlanViewModel.PatientRef>, empty: String, vm: TreatmentPlanViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = sys(11, FontWeight.Bold, tracking = 1), color = DS.Colors.onSurfaceVariant)
        if (patients.isEmpty()) {
            Text(empty, Modifier.padding(vertical = 8.dp), style = sys(14), color = DS.Colors.outline)
        } else {
            patients.forEach { p -> PatientRow(p) { vm.selectPatient(p) } }
        }
    }
}

@Composable
private fun PatientRow(p: TreatmentPlanViewModel.PatientRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .glassCard(16.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(DS.Colors.cyan400.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(initials(p.name), style = sys(15, FontWeight.Bold), color = DS.Colors.cyan400)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface)
            p.subtitle?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant) }
        }
        DfyIcon("chevron.right", size = 13.dp, tint = DS.Colors.outline)
    }
}

private fun initials(name: String): String {
    val parts = name.split(" ").filter { it.isNotEmpty() }
    val first = parts.firstOrNull()?.take(1) ?: ""
    val last = if (parts.size > 1) parts.last().take(1) else ""
    return (first + last).upperTr()
}

// MARK: - Kayıt

@Composable
private fun ColumnScope.RecordingView(vm: TreatmentPlanViewModel) {
    Spacer(Modifier.weight(1f))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AIOrb(
            size = 190.dp, isAnimating = true, audioLevel = if (vm.isListening) vm.speech.audioLevel else 0f,
            modifier = Modifier.plainClickable { vm.finishAndSend() },
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(0.9f).clip(CircleShape).background(DS.Colors.error))
        Text("Dinliyorum — tedavi planını söyleyin", style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
    }
    Spacer(Modifier.weight(1f))

    val transcript = vm.transcript
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            if (transcript.isEmpty()) "Örn: \"26 numaralı diş kanal tedavisi, 34 implant, 11 ve 21 zirkonyum kaplama.\"" else transcript,
            Modifier.fillMaxWidth().padding(14.dp),
            style = sys(17, if (transcript.isEmpty()) FontWeight.Normal else FontWeight.Medium),
            color = if (transcript.isEmpty()) DS.Colors.outline else DS.Colors.onSurface,
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlineCapsule("Sıfırla", "arrow.counterclockwise", DS.Colors.error, DS.Colors.error.copy(alpha = 0.4f), height = 52.dp) { vm.restartRecording() }
        GradientCapsule("Bitir & Çözümle", "waveform.and.magnifyingglass", Modifier.weight(1f), height = 52.dp, shadow = true) { vm.finishAndSend() }
    }
}

// MARK: - Düşünme (agent çalışıyor)

@Composable
private fun ColumnScope.ThinkingView(vm: TreatmentPlanViewModel) {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            AIOrb(size = 150.dp, isAnimating = true, audioLevel = 0f)
            ThinkingDots(dotSize = 8.dp)
        }
        AnimatedContent(vm.progressText, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "progress") { t ->
            Text(
                t, Modifier.padding(horizontal = 40.dp),
                style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface, textAlign = TextAlign.Center,
            )
        }
        if (vm.elapsedSeconds >= 8) {
            Text("${vm.elapsedSeconds} sn · Bu tür işlemler ~30 sn sürebilir", style = sys(12).monoDigits(), color = DS.Colors.outline)
        }
        Spacer(Modifier.weight(1f))
        OutlineCapsule(
            "İptal et", "xmark", DS.Colors.error, DS.Colors.error.copy(alpha = 0.4f), height = 50.dp, hPadding = 22.dp,
            modifier = Modifier.padding(bottom = 24.dp),
        ) { vm.cancelThinking() }
    }
}

// MARK: - Gözden geçir (agent write_preview + sesli özet)

@Composable
private fun ColumnScope.ReviewView(vm: TreatmentPlanViewModel) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        vm.writePreview?.let { pv ->
            Row(
                Modifier.fillMaxWidth().glassCard(16.dp).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("checkmark.shield.fill", size = 18.dp, tint = DS.Colors.cyan400)
                Text(previewHeadline(pv), Modifier.weight(1f), style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
            }
        }

        vm.speechNotice?.let { notice ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                DfyIcon("speaker.slash.fill", size = 12.dp, tint = DS.Colors.orange400)
                Text(notice, style = sys(12, FontWeight.Medium), color = DS.Colors.orange400)
            }
        }

        Text("PLAN", style = sys(11, FontWeight.Bold, tracking = 1), color = DS.Colors.onSurfaceVariant)
        Text(
            vm.planSummary.ifEmpty { "Plan hazırlandı. Onaylarsanız sisteme kaydedilecek." },
            style = sys(17).copy(lineHeight = TextUnit(17f + 4f + 4f, TextUnitType.Sp)),
            color = DS.Colors.onSurface,
        )
    }
    // Alt kontrol şeridi (`.ultraThinMaterial`).
    Row(
        Modifier
            .fillMaxWidth()
            .background(DS.Colors.surfaceContainerLow.copy(alpha = 0.86f))
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlineCapsule("Tekrar söyle", "mic", DS.Colors.onSurface, Color.White.copy(alpha = 0.18f), height = 54.dp) { vm.retryDictation() }
        GradientCapsule("Onayla", "checkmark", Modifier.weight(1f), height = 54.dp, shadow = true) { vm.confirm() }
    }
}

private fun previewHeadline(pv: com.dentifay.app.agent.AgentWritePreview): String {
    val parts = mutableListOf<String>()
    pv.operation?.let { parts += it }
    pv.entity?.let { parts += it }
    pv.affectedCount?.let { parts += "$it kayıt" }
    return if (parts.isEmpty()) "Onay bekleyen işlem" else parts.joinToString(" · ")
}

// MARK: - Durum ekranları

@Composable
private fun ColumnScope.StatusView(title: String, detail: String?) {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        ThinkingDots(dotSize = 10.dp)
        Text(title, style = sys(18, FontWeight.SemiBold), color = DS.Colors.onSurface)
        if (detail != null) {
            Text(detail, Modifier.padding(horizontal = 40.dp), style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.DoneView(vm: TreatmentPlanViewModel, onClose: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.weight(1f))
        DfyIcon("checkmark.seal.fill", size = 56.dp, tint = DS.Colors.tertiaryContainer)
        Text("Tedavi planı oluşturuldu", style = sys(20, FontWeight.Bold), color = DS.Colors.onSurface)
        if (vm.planSummary.isNotEmpty()) {
            Text(vm.planSummary, Modifier.padding(horizontal = 32.dp), style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        GradientCapsule("Kapat", null, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).fillMaxWidth(), height = 54.dp, onClick = onClose)
    }
}

@Composable
private fun ColumnScope.FailedView(vm: TreatmentPlanViewModel, message: String, onBuy: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        val credit = vm.creditRequired
        if (credit != null) {
            // 402: ham hata yerine kredi kartı (Kredi Al → paketler; satın alamıyorsa ne yapılacağı).
            CreditRequiredCard(credit = credit, modifier = Modifier.padding(horizontal = 20.dp), onBuy = { onBuy(credit.message) })
        } else {
            DfyIcon("exclamationmark.triangle.fill", size = 44.dp, tint = DS.Colors.orange400)
            Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                Text(message, Modifier.padding(horizontal = 32.dp), style = sys(15), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineCapsule(
                "Kapat", null, DS.Colors.onSurface, Color.White.copy(alpha = 0.18f), height = 52.dp,
                modifier = Modifier.weight(1f), fill = true, textStyle = sys(15, FontWeight.SemiBold),
                onClick = onClose,
            )
            GradientCapsule("Tekrar dene", null, Modifier.weight(1f), height = 52.dp) { vm.beginRecording() }
        }
    }
}

// MARK: - Düğmeler

/** Çerçeveli kapsül düğme (ikon + metin). */
@Composable
private fun OutlineCapsule(
    text: String,
    icon: String?,
    color: Color,
    stroke: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    hPadding: Dp = 18.dp,
    fill: Boolean = false,
    textStyle: TextStyle = sys(15, FontWeight.SemiBold),
    onClick: () -> Unit,
) {
    Row(
        modifier
            .pressable(onClick = onClick)
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .clip(CircleShape)
            .border(1.dp, stroke, CircleShape)
            .padding(horizontal = hPadding),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) DfyIcon(icon, size = textStyle.fontSize.value.dp, tint = color)
        Text(text, style = textStyle, color = color, maxLines = 1)
    }
}

/** Marka gradyanlı kapsül düğme (ikon + kalın metin). */
@Composable
private fun GradientCapsule(
    text: String,
    icon: String?,
    modifier: Modifier = Modifier,
    height: Dp,
    shadow: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .pressable(onClick = onClick)
            .height(height)
            .then(
                if (shadow) Modifier.shadow(12.dp, CircleShape, ambientColor = DS.Colors.cyan400.copy(alpha = 0.3f), spotColor = DS.Colors.cyan400.copy(alpha = 0.3f))
                else Modifier
            )
            .clip(CircleShape)
            .background(DS.Gradients.primaryGradient),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) DfyIcon(icon, size = 16.dp, tint = Color.White)
        Text(text, style = sys(16, FontWeight.Bold), color = Color.White, maxLines = 1)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanViewPreview() {
    PreviewEnv { TreatmentPlanView(onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanReviewPreview() {
    val vm = remember {
        TreatmentPlanViewModel().apply {
            planSummary = "26 numaralı dişe kanal tedavisi, 34 numaralı dişe implant ve 11–21 zirkonyum kaplama; toplam 48.500 TL."
            phase = TreatmentPlanViewModel.Phase.review
        }
    }
    PreviewEnv {
        Column(Modifier.fillMaxSize()) {
            TopBar("Ayşe Yılmaz") {}
            ReviewView(vm)
        }
    }
}
