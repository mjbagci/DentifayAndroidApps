package com.dentifay.app.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.credit.CreditRequiredCard
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.SmileCreditsView
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.shareContent
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots
import com.dentifay.app.voice.VoiceConversationView
import kotlinx.coroutines.launch
import java.io.File

/** Dentifay Asistan — sohbet ekranı (tam ekran). Sesli mod `VoiceConversationView` ile aynı oturumu paylaşır. */
@Composable
fun AgentView(onDismiss: () -> Unit) {
    val vm = remember { AgentSessionManager() }
    var showThreads by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    /** Kredi gerekli (402) → kredi paketleri ekranı. */
    var creditSheet by remember { mutableStateOf<CreditSheetItem?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.onAppear() }
    // Temizlik sunumun kapanmasına bağlı — sesli mod kapağı bu ekranı örttüğünde akan tur iptal edilmez.
    DisposableEffect(Unit) { onDispose { vm.dispose() } }
    // Dikte dökümü kompozere yazılır (düzenlenip gönderilsin diye).
    LaunchedEffect(vm.speech.transcript, vm.isListening) { vm.applyDictationTranscript() }

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        WebBackdrop()

        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            ChatTopBar(
                online = vm.agentOnline,
                onHistory = { scope.launch { vm.loadConversations() }; showThreads = true },
                onNewChat = { vm.newChat() },
                onClose = onDismiss,
            )
            if (!vm.agentOnline) OfflineBanner()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessagesList(
                    vm = vm,
                    onShare = { file ->
                        shareContent(context, files = listOf(file), mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    },
                    onBuyCredits = { creditSheet = CreditSheetItem(reason = it.message) },
                    onTapBackground = { focusManager.clearFocus() },
                )
            }

            // Kompozit liste ile aynı sütunda, altta — içerik kompozitin altına kaymaz.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(
                    vm.messages.isEmpty(),
                    enter = fadeIn(Motion.easeOut(250)) + slideInVertically(Motion.easeOut(250)) { it },
                    exit = fadeOut(Motion.easeOut(250)) + slideOutVertically(Motion.easeOut(250)) { it },
                ) {
                    SuggestionRail(AgentViewDefaults.suggestionItems) { vm.sendSuggested(it.text) }
                }
                ChatComposer(
                    draft = vm.draft,
                    onDraftChange = { vm.draft = it },
                    isStreaming = vm.isStreaming,
                    isDictating = vm.isListening,
                    errorText = vm.composerError,
                    elapsedSeconds = vm.elapsedSeconds,
                    onSend = { vm.send() },
                    onStop = { vm.stop() },
                    onToggleDictation = { focusManager.clearFocus(); vm.toggleDictation() },
                    onVoiceMode = {
                        if (vm.isListening) vm.stopDictation()
                        showVoice = true
                    },
                    onRetry = { vm.retryLast() },
                )
            }
        }
    }

    DfySheet(isPresented = showThreads, onDismiss = { showThreads = false }, containerColor = DS.Colors.background) { dismiss ->
        ThreadsSheet(vm, close = dismiss)
    }

    DfySheet(isPresented = creditSheet != null, onDismiss = { creditSheet = null }) { dismiss ->
        SmileCreditsView(reason = creditSheet?.reason, onDismiss = dismiss)
    }

    FullScreenCover(isPresented = showVoice, onDismiss = { showVoice = false }) {
        VoiceConversationView(vm = vm, onDismiss = { showVoice = false })
    }
}

/** Swift `AgentView.suggestionItems`. */
internal object AgentViewDefaults {
    val suggestionItems: List<SuggestionRail.Item> = listOf(
        SuggestionRail.Item("chart.line.uptrend.xyaxis", DS.Colors.cyan400, "Bu ayın cirosu"),
        SuggestionRail.Item("calendar", DS.Colors.tertiaryContainer, "Bugünkü randevular"),
        SuggestionRail.Item("creditcard.trianglebadge.exclamationmark", DS.Colors.orange400, "Vadesi geçmiş ödemeler"),
        SuggestionRail.Item("shippingbox", DS.Colors.purple500, "Kritik stok"),
    )
}

// MARK: - Üst bar

@Composable
private fun ChatTopBar(online: Boolean, onHistory: () -> Unit, onNewChat: () -> Unit, onClose: () -> Unit) {
    // Box → başlık EKRANIN gerçek merkezinde (asimetrik ikonlar başlığı kaydırmasın).
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dentifay Asistan", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (online) DS.Colors.tertiaryContainer else DS.Colors.orange400))
                Text(if (online) "Çevrimiçi" else "Çevrimdışı", style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton44("clock.arrow.circlepath", onHistory)
            Spacer(Modifier.weight(1f))
            IconButton44("square.and.pencil", onNewChat)
            IconButton44("xmark", onClose)
        }
    }
}

@Composable
private fun IconButton44(name: String, action: () -> Unit) {
    // 44 dp dokunma alanı, 40 dp daire zemin.
    Box(Modifier.size(44.dp).plainClickable(onClick = action), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
        DfyIcon(name, size = 15.dp, tint = DS.Colors.onSurface)
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().background(DS.Colors.orange400.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("exclamationmark.triangle.fill", size = 12.dp, tint = DS.Colors.orange400)
        Text("Asistan şu an çevrimdışı. Yanıt gecikebilir.", style = sys(12, FontWeight.Medium), color = DS.Colors.orange400)
    }
}

// MARK: - Mesaj listesi

@Composable
private fun MessagesList(
    vm: AgentSessionManager,
    onShare: (File) -> Unit,
    onBuyCredits: (CreditRequired) -> Unit,
    onTapBackground: () -> Unit,
) {
    if (vm.messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState() }
        return
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    // Kaydırınca klavye kapanır (`.scrollDismissesKeyboard(.interactively)`).
    LaunchedEffect(dragged) { if (dragged) focusManager.clearFocus() }
    val count = vm.messages.size
    LaunchedEffect(count) { listState.animateScrollToItem(count) }
    LaunchedEffect(vm.messages.lastOrNull()?.content) { listState.scrollToItem(vm.messages.size) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTapBackground() } },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(vm.messages, key = { it.id.toString() }) { msg ->
            Box(
                Modifier.animateItem(
                    fadeInSpec = Motion.spring(0.4, 0.82),
                    placementSpec = Motion.spring(0.4, 0.82),
                    fadeOutSpec = Motion.easeOut(200),
                )
            ) {
                MessageBubble(msg, vm, onShare, onBuyCredits)
            }
        }
        item(key = "BOTTOM") { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.padding(horizontal = 24.dp).appear(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Web IconTile tone="brand".
        IconTile("bubble.left.and.text.bubble.right", tone = IconTone.Brand, customSize = 52.dp)
        Text("Bir soruyla başlayın", style = sys(24, FontWeight.Bold, tracking = -0.4), color = Color.White)
        Text(
            "Klinik verileriniz hakkında soru sorun; yanıtı tablo, grafik ya da Excel dosyası olarak alın.",
            style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Sohbetler sayfası

@Composable
private fun ThreadsSheet(vm: AgentSessionManager, close: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        // Gezinme çubuğu: ortada başlık, sağda "Kapat".
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("Sohbetler", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(
                "Kapat", Modifier.align(Alignment.CenterEnd).plainClickable(onClick = close),
                style = sys(17), color = DS.Colors.cyan400,
            )
        }
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DS.Colors.surfaceContainer)
                .verticalScroll(rememberScrollState())
        ) {
            ThreadRow(onClick = { vm.newChat(); close() }) {
                DfyLabel("Yeni Sohbet", "square.and.pencil", sys(17), DS.Colors.cyan400)
            }
            vm.conversations.forEach { conv ->
                Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                key(conv.id) {
                    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                        if (v == SwipeToDismissBoxValue.EndToStart) { vm.deleteConversation(conv); true } else false
                    })
                    SwipeToDismissBox(
                        state = state,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(DS.Colors.danger).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                Text("Sil", style = sys(15, FontWeight.SemiBold), color = Color.White)
                            }
                        },
                    ) {
                        ThreadRow(onClick = { vm.openConversation(conv); close() }) {
                            Text(conv.title, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).background(DS.Colors.surfaceContainer).plainClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** SwiftUI `Label(text, dfy: icon)` — ikon + metin. */
@Composable
internal fun DfyLabel(text: String, icon: String, style: TextStyle, color: Color, iconSize: Dp? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        DfyIcon(icon, size = iconSize ?: style.fontSize.value.dp, tint = color)
        Text(text, style = style, color = color)
    }
}

// MARK: - Mesaj balonu

@Composable
private fun MessageBubble(
    message: AgentMessage,
    vm: AgentSessionManager,
    onShare: (File) -> Unit,
    onBuyCredits: (CreditRequired) -> Unit,
) {
    if (message.role == AgentRole.user) {
        Box(Modifier.fillMaxWidth().padding(start = 40.dp), contentAlignment = Alignment.CenterEnd) {
            val shape = RoundedCornerShape(20.dp)
            Text(
                message.content,
                Modifier
                    .clip(shape)
                    .background(DS.Colors.cyan400.copy(alpha = 0.15f))
                    .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.2f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = sys(16), color = DS.Colors.onSurface,
            )
        }
    } else {
        AssistantBubble(message, vm, onShare, onBuyCredits)
    }
}

@Composable
private fun AssistantBubble(
    message: AgentMessage,
    vm: AgentSessionManager,
    onShare: (File) -> Unit,
    onBuyCredits: (CreditRequired) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.streaming && message.content.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ThinkingDots()
                    Text(message.thinkingText ?: "Düşünüyorum…", style = sys(13), color = DS.Colors.onSurfaceVariant)
                    if (vm.elapsedSeconds > 0) {
                        Text("· ${vm.elapsedSeconds}s", style = sys(13, FontWeight.Medium), color = DS.Colors.outline)
                    }
                }
                if (vm.elapsedSeconds >= 8) {
                    Text("Analitik sorular yerel modelde birkaç dakika sürebilir…", style = sys(11), color = DS.Colors.outline)
                }
            }
        }

        if (message.toolSteps.isNotEmpty()) ToolTrace(message)

        val credit = message.creditRequired
        if (message.writePreview != null) {
            // Yazma önizlemesi: kısa başlık (backend `speak` metni) önde, ayrıntılı döküm katlanabilir alanda.
            val speak = message.speakText
            if (!speak.isNullOrEmpty()) {
                Text(speak, style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface)
            }
            if (message.content.isNotEmpty()) {
                Disclosure(label = { Text("İşlem detayı", style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400) }) {
                    MarkdownMessageView(message.content, Modifier.padding(top = 6.dp), textColor = DS.Colors.onSurfaceVariant)
                }
            }
        } else if (credit != null) {
            // 402: ham hata yerine kredi kartı. "Tekrar dene" yalnız son balonda.
            val isLast = vm.messages.lastOrNull()?.id == message.id
            CreditRequiredCard(
                credit = credit,
                onBuy = { onBuyCredits(credit) },
                onRetry = if (isLast && !vm.isStreaming) ({ vm.retryLast() }) else null,
            )
        } else if (message.content.isNotEmpty()) {
            MarkdownMessageView(message.content, textColor = if (message.isError) DS.Colors.error else DS.Colors.onSurface)
        }

        message.charts.forEach { spec -> key(spec.id) { AgentChartView(spec) } }

        message.excelFiles.forEach { file -> ExcelPill(file, onShare) }

        message.writePreview?.let { WritePreviewCard(it, vm) }

        message.writeResult?.let { WriteResultBadge(it) }
    }
}

/** SwiftUI `DisclosureGroup`: etiket + sağda dönen ok, içerik açılıp kapanır. */
@Composable
private fun Disclosure(modifier: Modifier = Modifier, label: @Composable () -> Unit, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 90f else 0f, Motion.easeInOut(250), label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().plainClickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { label() }
            DfyIcon("chevron.right", Modifier.rotate(angle), size = 13.dp, tint = DS.Colors.cyan400)
        }
        AnimatedVisibility(expanded, enter = expandVertically(Motion.easeInOut(250)) + fadeIn(), exit = shrinkVertically(Motion.easeInOut(250)) + fadeOut()) {
            content()
        }
    }
}

@Composable
private fun ToolTrace(message: AgentMessage) {
    Disclosure(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).padding(12.dp),
        label = { Text("${message.toolSteps.size} işlem yapıldı", style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400) },
    ) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            message.toolSteps.forEach { step ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon(
                        if (step.ok) "checkmark.circle.fill" else "xmark.circle.fill", size = 11.dp,
                        tint = if (step.ok) DS.Colors.tertiaryContainer else DS.Colors.error,
                    )
                    Text(step.summary, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun ExcelPill(file: AgentExcelArtifact, onShare: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable {
                scope.launch {
                    runCatching { AgentClient.shared.downloadArtifact(file.downloadUrl, file.filename) }.getOrNull()?.let(onShare)
                }
            }
            .glassCard(14.dp)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("tablecells.fill", size = 16.dp, tint = DS.Colors.tertiaryContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.filename, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1)
            file.rowCount?.let { n -> Text("$n satır", style = sys(11), color = DS.Colors.onSurfaceVariant) }
        }
        DfyIcon("square.and.arrow.down", size = 15.dp, tint = DS.Colors.cyan400)
    }
}

@Composable
private fun WritePreviewCard(preview: AgentWritePreview, vm: AgentSessionManager) {
    val accent = if (preview.requiresDoubleConfirm) DS.Colors.error else DS.Colors.orange400
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, accent.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon("exclamationmark.shield.fill", tint = accent)
            Text(
                if (preview.requiresDoubleConfirm) "Kalıcı İşlem Onayı" else "İşlem Onayı",
                style = sys(14, FontWeight.Bold), color = DS.Colors.onSurface,
            )
        }
        preview.entity?.let { entity ->
            Text(
                "${preview.operation ?: "İşlem"} · $entity${preview.affectedCount?.let { " · $it kayıt" } ?: ""}",
                style = sys(13), color = DS.Colors.onSurfaceVariant,
            )
        }
        val enabled = !vm.isStreaming
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val confirmFill: Brush = if (preview.requiresDoubleConfirm) SolidColor(DS.Colors.error) else DS.Gradients.primaryGradient
            Box(
                Modifier
                    .weight(1f)
                    .alpha(if (enabled) 1f else 0.5f)
                    .clip(CircleShape)
                    .background(confirmFill)
                    .plainClickable(enabled = enabled) { vm.confirmWrite(preview.requiresDoubleConfirm) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (preview.requiresDoubleConfirm) "Evet, kalıcı olarak uygula" else "Onayla",
                    style = sys(14, FontWeight.SemiBold),
                    color = if (preview.requiresDoubleConfirm) DS.Colors.onError else Color.White,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .alpha(if (enabled) 1f else 0.5f)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .plainClickable(enabled = enabled) { vm.cancelWrite() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Vazgeç", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            }
        }
        // Düzeltme ipucu: kullanıcı tüm isteği yeniden söylemek yerine yalnız düzeltmeyi yazabilir.
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            DfyIcon("pencil.line", size = 11.dp, tint = DS.Colors.onSurfaceVariant)
            Text(
                "Yanlış bir şey mi var? Aşağıya sadece düzeltmeyi yazın (ör. \"beden M olsun\").",
                style = sys(11), color = DS.Colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WriteResultBadge(result: AgentWriteResult) {
    val color = if (result == AgentWriteResult.committed) DS.Colors.tertiaryContainer else DS.Colors.onSurfaceVariant
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(if (result == AgentWriteResult.committed) "checkmark.seal.fill" else "info.circle.fill", size = 13.dp, tint = color)
        Text(result.label, style = sys(13, FontWeight.SemiBold), color = color)
    }
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentViewPreview() {
    PreviewEnv { AgentView(onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentBubblesPreview() {
    val vm = remember {
        AgentSessionManager().apply {
            messages = listOf(
                AgentMessage(role = AgentRole.user, content = "Bugünkü randevular"),
                AgentMessage(
                    role = AgentRole.assistant,
                    content = "Bugün **6 randevu** var.\n\n| Saat | Hasta |\n|---|---|\n| 09:00 | Ali Yılmaz |\n| 10:30 | Ece Kaya |",
                    toolSteps = listOf(AgentToolStep("sql", "randevular sorgulandı", true)),
                    excelFiles = listOf(AgentExcelArtifact("randevular.xlsx", "/api/files/1", null, 6)),
                ),
                AgentMessage(
                    role = AgentRole.assistant, speakText = "Ali Yılmaz için yeni randevu oluşturulacak.", content = "Ayrıntı",
                    writePreview = AgentWritePreview("Oluştur", "Randevu", "low", 1, false),
                ),
                AgentMessage(role = AgentRole.assistant, writeResult = AgentWriteResult.committed),
            )
        }
    }
    PreviewEnv {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            vm.messages.forEach { MessageBubble(it, vm, {}, {}) }
            Spacer(Modifier.width(1.dp))
        }
    }
}
