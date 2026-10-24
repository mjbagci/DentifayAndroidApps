package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.core.AppConfig
import com.dentifay.app.smile.SmileSimulationViewModel.Job
import com.dentifay.app.smile.SmileSimulationViewModel.Phase
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.SmileCreditFormat
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.credit.SmileCreditsView
import com.dentifay.app.theme.ActivityHolder
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.PrimaryButton
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.borderBeam
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.trial.GuestContactSheet
import com.dentifay.app.trial.GuestTrialFeature
import com.dentifay.app.trial.GuestTrialStore
import com.dentifay.app.trial.TrialQuotaChip
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min


/**
 * Gülüş Simülasyonu (Önce / Sonra). Analiz Merkezi'ndeki kartından (klinik) ve giriş
 * ekranındaki ücretsiz deneme kartından (`guest = true`) açılır. Mantık: `SmileSimulationViewModel`.
 */
@Composable
fun SmileSimulationView(onDismiss: () -> Unit, guest: Boolean = false) {
    val vm = remember { SmileSimulationViewModel(guest) }
    val trial = GuestTrialStore.shared
    val credits = SmileCreditsStore.shared
    val history = SmileHistoryStore.shared
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showContact by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var zoomedResult by remember { mutableStateOf(true) }
    /** false: önce/sonra yan yana (alt alta), true: kaydırmalı karşılaştırma. */
    var sliderMode by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var maskEditor by remember { mutableStateOf<MaskEditorInput?>(null) }
    var shareJob by remember { mutableStateOf<Job?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    /** Kredi ekranının açılış nedeni. */
    var creditsSheet by remember { mutableStateOf<CreditSheetItem?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val image = withContext(Dispatchers.IO) { SmileSimulationViewModel.decodeImage(context, uri) }
            if (image != null) vm.load(image)
        }
    }

    LaunchedEffect(Unit) {
        history.load()
        if (vm.isGuest) trial.refresh() else credits.refresh()
    }
    // Maskeli model 1–4 dk sürebilir; ekran kilitlenirse uygulama askıya alınır.
    val generating = vm.isGenerating
    LaunchedEffect(generating) { setKeepScreenOn(generating) }
    DisposableEffect(Unit) {
        onDispose {
            vm.dispose()
            setKeepScreenOn(false)
        }
    }

    val actions = SimActions(
        openContact = { showContact = true },
        openCredits = { creditsSheet = CreditSheetItem(it) },
        openCamera = { showCamera = true },
        openGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        openMaskEditor = {
            vm.maskEditorInput()?.let { e -> maskEditor = MaskEditorInput(crop = e.crop, mask = e.mask, auto = e.auto) }
        },
        openCompare = { showCompare = true },
        share = { shareJob = it },
    )

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(vm, onContact = actions.openContact, onCredits = { creditsSheet = CreditSheetItem(null) }, onHistory = { showHistory = true }) {
                vm.teardown(); onDismiss()
            }
            AnimatedContent(
                targetState = vm.phase,
                contentKey = { it::class },
                transitionSpec = { fadeIn(Motion.easeInOut(250)) togetherWith fadeOut(Motion.easeInOut(250)) },
                label = "smilePhase",
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { phase ->
                when (phase) {
                    is Phase.failed -> FailedView(phase.message, vm, actions)
                    Phase.pickPhoto -> PickPhotoView(vm, actions)
                    Phase.analyzing -> StatusView("Ağız ve maske algılanıyor…")
                    Phase.configure -> ConfigureView(vm, showAdvanced, { showAdvanced = !showAdvanced }, actions)
                    Phase.results -> ResultsView(
                        vm, zoomedResult, { zoomedResult = it }, sliderMode, { sliderMode = it }, actions,
                    )
                }
            }
        }
    }

    DfySheet(isPresented = showContact, onDismiss = { showContact = false }) { dismiss ->
        GuestContactSheet(exhausted = if (trial.isExhausted(GuestTrialFeature.smile)) GuestTrialFeature.smile else null, onDismiss = dismiss)
    }
    FullScreenCover(isPresented = showCamera, onDismiss = { showCamera = false }, background = Color.Black) {
        SmileCameraView(onCapture = { vm.load(it) }, onDismiss = { showCamera = false })
    }
    DfySheet(isPresented = showCompare, onDismiss = { showCompare = false }, containerColor = DS.Colors.background) { dismiss ->
        SmileCompareSheet(vm, onDismiss = dismiss)
    }
    FullScreenCover(isPresented = maskEditor != null, onDismiss = { maskEditor = null }) {
        maskEditor?.let { input ->
            SmileMaskEditorView(input, onDone = { vm.applyEditedMask(it) }, onDismiss = { maskEditor = null })
        }
    }
    DfySheet(isPresented = shareJob != null, onDismiss = { shareJob = null }, containerColor = DS.Colors.background) { dismiss ->
        shareJob?.let { job ->
            val content = remember(job.id) { vm.shareContent(job) }
            if (content != null) SmileShareSheet(content = content, onDismiss = dismiss)
        }
    }
    DfySheet(isPresented = showHistory, onDismiss = { showHistory = false }, containerColor = DS.Colors.background) { dismiss ->
        SmileHistoryView(onDismiss = dismiss)
    }
    DfySheet(isPresented = creditsSheet != null, onDismiss = { creditsSheet = null }) { dismiss ->
        creditsSheet?.let { SmileCreditsView(reason = it.reason, onDismiss = dismiss) }
    }
}

/** Alt görünümlerin açtığı sayfalar. */
private class SimActions(
    val openContact: () -> Unit,
    val openCredits: (String?) -> Unit,
    val openCamera: () -> Unit,
    val openGallery: () -> Unit,
    val openMaskEditor: () -> Unit,
    val openCompare: () -> Unit,
    val share: (Job) -> Unit,
)

private fun setKeepScreenOn(on: Boolean) {
    val window = ActivityHolder.current?.window ?: return
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

// MARK: - Üst bar

@Composable
private fun TopBar(vm: SmileSimulationViewModel, onContact: () -> Unit, onCredits: () -> Unit, onHistory: () -> Unit, onClose: () -> Unit) {
    val credits = SmileCreditsStore.shared
    val history = SmileHistoryStore.shared
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Gülüş Simülasyonu", style = sys(17, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Crossfade(subtitle(vm), animationSpec = Motion.easeInOut(250), label = "subtitle") {
                Text(it, style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1)
            }
        }
        val wallet = credits.wallet
        if (vm.isGuest) {
            Box(Modifier.plainClickable(onClick = onContact)) { TrialQuotaChip(GuestTrialFeature.smile, compact = true) }
        } else if (credits.isMetered && wallet != null) {
            val tint = if (wallet.availableCredits > 0) DS.Colors.cyan400 else DS.Colors.orange400
            val text = SmileCreditFormat.credits(wallet.availableCredits)
            Row(
                Modifier
                    .plainClickable(onClick = onCredits)
                    .semantics { contentDescription = "Kalan kredi $text" }
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("creditcard", size = 11.dp, tint = tint)
                Text(text, style = sys(13, FontWeight.SemiBold).monoDigits(), color = tint)
            }
        }
        Box(Modifier.plainClickable(onClick = onHistory).semantics { contentDescription = "Geçmiş" }) {
            CircleIconButton("clock.arrow.circlepath")
            if (history.items.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .defaultMinSize(16.dp, 16.dp)
                        .clip(CircleShape)
                        .background(DS.Colors.cyan400)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${min(history.items.size, 99)}", style = sys(9, FontWeight.Bold), color = DS.Colors.onPrimary)
                }
            }
        }
        Box(Modifier.plainClickable(onClick = onClose).semantics { contentDescription = "Kapat" }) {
            CircleIconButton("xmark")
        }
    }
}

@Composable
private fun CircleIconButton(icon: String) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        DfyIcon(icon, size = 16.dp, tint = DS.Colors.onSurface)
    }
}

private fun subtitle(vm: SmileSimulationViewModel): String = when (vm.phase) {
    is Phase.failed -> "Hata"
    Phase.pickPhoto -> "Önce / Sonra"
    Phase.analyzing -> "Ağız ve maske algılanıyor"
    Phase.configure -> "Tasarımı seçin"
    Phase.results -> if (vm.isGenerating) "Simülasyon hazırlanıyor" else if (vm.jobs.size > 1) "${vm.jobs.size} sonuç" else "Sonuç hazır"
}

// MARK: - 1. Fotoğraf

@Composable
private fun PickPhotoView(vm: SmileSimulationViewModel, actions: SimActions) {
    val trial = GuestTrialStore.shared
    val credits = SmileCreditsStore.shared
    val context = LocalContext.current
    val samples = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.smile_before) to
                BitmapFactory.decodeResource(context.resources, R.drawable.smile_after)
        }.getOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            samples?.let { (before, after) ->
                BeforeAfterSlider(before, after, Modifier.fillMaxWidth().appear(0.0), afterLabel = "SİMÜLASYON")
            }

            Column(Modifier.appear(0.06), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tedaviden önce sonucu gösterin", style = sys(22, FontWeight.Bold), color = DS.Colors.onSurface)
                Text(
                    "Rehberli çekimle hastanın gülüşünü çekin; ağız bölgesi otomatik seçilir. Tasarımı seçin, sonuç yaklaşık 1 dakikada hazırlanır.",
                    style = sys(14).copy(lineHeight = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = DS.Colors.onSurfaceVariant,
                )
            }

            Column(Modifier.appear(0.12), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tip("person.crop.square", "Önden, yüz çerçevede — kamera yönlendirir")
                Tip("mouth", "Hasta gülümsesin, dişler görünsün")
                Tip("sun.max", "Flaşsız, gölgesiz, bol ışık")
                val wallet = credits.wallet
                if (vm.isGuest) {
                    Tip("gift", "${trial.quota(GuestTrialFeature.smile)?.limit ?: trial.limit} ücretsiz deneme hakkı · her simülasyon 1 hak")
                } else if (credits.isMetered && wallet != null) {
                    val orta = SmileCreditFormat.credits(wallet.price(SmileAIModel.nanoBanana))
                    Tip(
                        "creditcard",
                        if (wallet.isOnFreeCredits) "${SmileCreditFormat.credits(wallet.freeRemainingCredits)} ücretsiz krediniz var · Orta kalite $orta kredi"
                        else "Kalan kredi: ${SmileCreditFormat.credits(wallet.availableCredits)} · Orta kalite $orta kredi",
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().smileBarBackground().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(onClick = actions.openGallery, modifier = Modifier.widthIn(max = 140.dp)) {
                DfyIcon("photo.on.rectangle", size = 15.dp, tint = DS.Colors.onSurface)
                Spacer(Modifier.width(8.dp))
                ButtonLabel("Galeri")
            }
            PrimaryButton(onClick = { Haptics.medium(); actions.openCamera() }, modifier = Modifier.weight(1f)) {
                DfyIcon("camera.viewfinder", size = 15.dp, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                ButtonLabel("Rehberli çekim")
            }
        }
    }
}

@Composable
private fun Tip(icon: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { DfyIcon(icon, size = 15.dp, tint = DS.Colors.metal) }
        Text(text, Modifier.weight(1f), style = sys(14), color = DS.Colors.onSurface)
    }
}

// MARK: - 2. Tasarım

@Composable
private fun ConfigureView(vm: SmileSimulationViewModel, showAdvanced: Boolean, toggleAdvanced: () -> Unit, actions: SimActions) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SmilePhotoSection(vm, onRetakeFrontal = { vm.reset(); actions.openCamera() }, modifier = Modifier.appear(0.0))

            SmileStepHeader(
                1, "Tasarımı seçin",
                if (vm.isGuest) "Tek dokunuş · her üretim 1 ücretsiz hak kullanır" else "Tek dokunuş · her üretim seçilen kaliteye göre kredi harcar",
            )
            SmilePresetGrid(vm, Modifier.appear(0.06))

            SmileStepHeader(2, "İnce ayar", "İsteğe bağlı · değiştirirseniz “Özel Tasarım” olur")
            SmileTuningSection(vm, Modifier.appear(0.1))

            SmileAdvancedSection(vm, showAdvanced, toggleAdvanced, onContact = actions.openContact, onEditMask = actions.openMaskEditor)
        }
        ConfigureBottomBar(vm, actions)
    }
}

@Composable
private fun ConfigureBottomBar(vm: SmileSimulationViewModel, actions: SimActions) {
    val credits = SmileCreditsStore.shared
    Column(
        Modifier.fillMaxWidth().smileBarBackground().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.isGuest) TrialLine(vm, actions) else if (credits.isMetered) CreditLine(vm, actions)
        ConsentRow(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .pressable { vm.reset() }
                    .semantics { contentDescription = "Farklı fotoğraf" }
                    .size(54.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { DfyIcon("camera.rotate", size = 18.dp, tint = DS.Colors.onSurface) }

            val exhausted = vm.trialExhausted
            val overLimit = vm.overPersonalLimit
            val lacks = vm.lacksCredits
            val enabled = vm.canGenerate || exhausted
            val shape = RoundedCornerShape(DS.Radius.control)
            Column(
                Modifier
                    .weight(1f)
                    .pressable(enabled = enabled, haptic = true) {
                        if (exhausted) {
                            actions.openContact()
                        } else if (overLimit) {
                            // Kredi almak kişisel limiti AÇMAZ: kredi ekranı yerine sebebi göster.
                            actions.openCredits(personalLimitText())
                        } else if (lacks) {
                            actions.openCredits(creditShortfallText(vm))
                        } else {
                            vm.generate()
                        }
                    }
                    .alpha(if (enabled) 1f else 0.5f)
                    .height(54.dp)
                    .shadow(12.dp, shape, ambientColor = DS.Colors.brandViolet.copy(alpha = 0.25f), spotColor = DS.Colors.brandViolet.copy(alpha = 0.25f))
                    .clip(shape)
                    .background(DS.Gradients.primaryGradient),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon(
                        if (exhausted) "bubble.left.and.text.bubble.right"
                        else if (overLimit) "gauge.with.dots.needle.100percent"
                        else if (lacks) "creditcard" else "play.fill",
                        size = 16.dp, tint = Color.White,
                    )
                    Text(
                        if (exhausted) "Hakkınız doldu · İletişime geçin"
                        else if (overLimit) "Aylık Limit Doldu"
                        else if (lacks) lacksCreditsLabel else "Simülasyonu oluştur",
                        style = sys(16, FontWeight.SemiBold), color = Color.White, maxLines = 1,
                    )
                }
                Text(
                    if (vm.isGuest) "${vm.designTitle} · 1 ücretsiz hak"
                    else "${vm.designTitle} · ${vm.model.title} · ${SmileCreditFormat.credits(vm.estimatedCredits)} kredi",
                    Modifier.alpha(0.85f).padding(horizontal = 8.dp),
                    style = sys(10, FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Ücretsiz deneme satırı: kalan hak + iletişim. */
@Composable
private fun TrialLine(vm: SmileSimulationViewModel, actions: SimActions) {
    val trial = GuestTrialStore.shared
    val exhausted = vm.trialExhausted
    Row(
        Modifier.fillMaxWidth().plainClickable(onClick = actions.openContact),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("gift", size = 12.dp, tint = if (exhausted) DS.Colors.warning else DS.Colors.cyan400)
        val text = trial.remaining(GuestTrialFeature.smile)?.let { "Ücretsiz deneme · kalan $it/${trial.quota(GuestTrialFeature.smile)?.limit ?: trial.limit} hak" }
            ?: "Ücretsiz deneme · her simülasyon 1 hak"
        Text(
            text, Modifier.weight(1f), style = sys(12, FontWeight.Medium),
            color = if (exhausted) DS.Colors.warning else DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("Daha fazlası", style = sys(12, FontWeight.Bold), color = DS.Colors.cyan400)
        DfyIcon("chevron.right", size = 10.dp, tint = DS.Colors.cyan400)
    }
}

/**
 * Kalan kredi satırı (sunucu yolu): ücretsiz kredi sürüyorsa belirtir; kredi
 * yetmiyorsa "Kredi Al"a yönlendirir.
 */
@Composable
private fun CreditLine(vm: SmileSimulationViewModel, actions: SimActions) {
    val credits = SmileCreditsStore.shared
    val warn = vm.lacksCredits || vm.overPersonalLimit
    Row(
        Modifier.fillMaxWidth().plainClickable { actions.openCredits(null) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("creditcard", size = 12.dp, tint = if (warn) DS.Colors.orange400 else DS.Colors.cyan400)
        val w = credits.wallet
        val text = when {
            w != null && vm.overPersonalLimit -> personalLimitText()
            w != null && vm.lacksCredits -> creditShortfallText(vm)
            w != null && w.isOnFreeCredits ->
                "Ücretsiz kredi: ${SmileCreditFormat.credits(w.freeRemainingCredits)} / ${SmileCreditFormat.credits(w.freeGrantCredits)}"
            w != null -> "Kalan kredi: ${SmileCreditFormat.credits(w.availableCredits)}"
            credits.errorMessage != null -> "Kredi bilgisi alınamadı · dokunun"
            else -> "Kredi bilgisi yükleniyor…"
        }
        Text(
            text, Modifier.weight(1f), style = sys(12, FontWeight.Medium),
            color = if (warn) DS.Colors.orange400 else DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(if (vm.lacksCredits && AppConfig.inAppPurchase) "Kredi Al" else "Krediler", style = sys(12, FontWeight.Bold), color = DS.Colors.cyan400)
        DfyIcon("chevron.right", size = 10.dp, tint = DS.Colors.cyan400)
    }
}

/** Kişisel aylık limit (Kredi Yönetimi) — kalan hak bu kademeye yetmiyor. */
private fun personalLimitText(): String {
    val kalan = SmileCreditsStore.shared.wallet?.myLimit?.let { SmileCreditFormat.credits(it.remainingCredits) } ?: "0"
    return "Aylık kredi limitiniz yetmiyor · kalan $kalan kredi"
}

/** Kredi yetmeyince ana buton: mağaza derlemesinde satın alma yok → yalnız durumu söyler, kredi ekranını açar. */
private val lacksCreditsLabel: String
    get() = if (AppConfig.inAppPurchase) "Kredi Al" else "Kredi yetersiz"

private fun creditShortfallText(vm: SmileSimulationViewModel): String {
    val have = SmileCreditsStore.shared.wallet?.let { SmileCreditFormat.credits(it.availableCredits) } ?: "0"
    return "${vm.model.title} kalitesi ${SmileCreditFormat.credits(vm.estimatedCredits)} kredi · kalan $have"
}

/** KVKK açık rıza: her yeni fotoğrafta yeniden işaretlenir. */
@Composable
private fun ConsentRow(vm: SmileSimulationViewModel) {
    val on = vm.patientConsent
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable { vm.patientConsent = !vm.patientConsent; Haptics.selection() }
            .semantics { selected = on },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Crossfade(on, animationSpec = Motion.easeInOut(200), label = "consent") { checked ->
            DfyIcon(if (checked) "checkmark.square.fill" else "square", size = 20.dp, tint = if (checked) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant)
        }
        Text(
            "Hasta, ağız bölgesi fotoğrafının yapay zeka ile işlenmesine onay verdi",
            Modifier.weight(1f),
            style = sys(12, FontWeight.Medium), color = if (on) DS.Colors.onSurface else DS.Colors.onSurfaceVariant,
        )
    }
}

// MARK: - 3. Sonuçlar

@Composable
private fun ResultsView(
    vm: SmileSimulationViewModel,
    zoomedResult: Boolean,
    onZoomed: (Boolean) -> Unit,
    sliderMode: Boolean,
    onSliderMode: (Boolean) -> Unit,
    actions: SimActions,
) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.jobs.size > 1) JobTabs(vm)

        val job = vm.activeJob ?: return@Column
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val after = job.result
            val before = vm.original
            val state = job.state
            if (after != null && before != null) {
                val zoomRect = vm.zoomRect()
                val pair = remember(before, after, zoomedResult, zoomRect) {
                    if (!zoomedResult) before to after else {
                        val b = before.croppedTo(zoomRect)
                        val a = after.croppedTo(zoomRect)
                        if (b != null && a != null) b to a else before to after
                    }
                }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmileSegmented(
                            options = listOf(true, false), selected = zoomedResult, onSelect = onZoomed,
                            modifier = Modifier.weight(1f), label = { if (it) "Gülüş" else "Tüm yüz" },
                        )
                        SmileSegmented(
                            options = listOf(false, true), selected = sliderMode, onSelect = onSliderMode,
                            modifier = Modifier.width(108.dp), label = { null },
                            icon = { if (it) "slider.horizontal.below.rectangle" else "rectangle.split.2x1" },
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Crossfade("${job.id}-$zoomedResult-$sliderMode", animationSpec = Motion.easeInOut(250), label = "resultView") { _ ->
                            if (sliderMode) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    BeforeAfterSlider(pair.first, pair.second)
                                }
                            } else {
                                SmileComparisonView(pair.first, pair.second, job.title)
                            }
                        }
                    }
                }
            } else if (state is Job.State.failed) {
                JobFailed(vm, job, state.message, actions)
            } else {
                JobProgress(vm, job)
            }
        }

        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon(job.icon, tint = DS.Colors.cyan400)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(job.title, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
                Text(
                    if (job.tierTitle.isEmpty()) job.design.summary else "${job.tierTitle} · ${job.design.summary}",
                    style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            job.finishedIn?.let { t ->
                Text("${t.toInt()} sn", style = sys(11).monoDigits(), color = DS.Colors.outline)
            }
        }

        job.note?.let { note ->
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DfyIcon("checkmark.circle", size = 11.dp, tint = DS.Colors.cyan400)
                Text(note, style = sys(11, FontWeight.Medium), color = DS.Colors.cyan400)
            }
        }

        Text(
            "Yapay zeka simülasyonudur; gerçek tedavi sonucu farklılık gösterebilir.",
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            style = sys(11), color = DS.Colors.outline, textAlign = TextAlign.Center,
        )

        ResultControls(vm, job, actions)
    }
}

@Composable
private fun JobTabs(vm: SmileSimulationViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val activeId = vm.activeJob?.id
        vm.jobs.forEach { job ->
            val active = job.id == activeId
            val fg = if (active) DS.Colors.onSurface else DS.Colors.onSurfaceVariant
            Row(
                Modifier
                    .plainClickable { vm.activeJobID = job.id; Haptics.selection() }
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(if (active) DS.Colors.cyan400.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                    .border(if (active) 1.5.dp else 1.dp, if (active) DS.Colors.cyan400.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.1f), CircleShape)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val st = job.state
                when {
                    st == Job.State.done -> DfyIcon("checkmark.circle.fill", tint = DS.Colors.tertiaryContainer)
                    st is Job.State.failed -> DfyIcon("exclamationmark.circle.fill", tint = DS.Colors.orange400)
                    else -> SmileSpinner(size = 12.dp)
                }
                Text(job.title, style = sys(13, FontWeight.SemiBold), color = fg)
            }
        }
    }
}

@Composable
private fun JobProgress(vm: SmileSimulationViewModel, job: Job) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        vm.uploadPreview?.let { sent ->
            val shape = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 360.dp)
                    .aspectRatio(sent.width.toFloat() / max(sent.height, 1).toFloat())
                    .clip(shape)
                    .borderBeam(22.dp)
            ) {
                Image(remember(sent) { sent.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                SmileScanOverlay(Modifier.fillMaxSize())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SmileSpinner()
            Text(job.state.label, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
        }
        @Suppress("UNUSED_VARIABLE") val tick = vm.tick   // her saniye yeniden çiz
        val elapsed = Duration.between(job.startedAt, Instant.now()).seconds.coerceAtLeast(0)
        Text("$elapsed sn · genelde 30–90 sn", style = sys(12).monoDigits(), color = DS.Colors.outline)
        AnimatedVisibility(elapsed > 120, enter = fadeIn(), exit = fadeOut()) {
            // Servis yoğunluğunda görev sürer; ekrandan çıkmak ya da tekrar denemek yeni kredi harcatır.
            Text(
                if (vm.isGuest) "Servis şu an yoğun, sonuç biraz daha sürebilir. Bu ekranda bekleyin; hak bir kez düşer."
                else "Servis şu an yoğun, sonuç biraz daha sürebilir. Bu ekranda bekleyin; kredi bir kez düşer.",
                Modifier.padding(horizontal = 32.dp),
                style = sys(12), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun JobFailed(vm: SmileSimulationViewModel, job: Job, message: String, actions: SimActions) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DfyIcon("exclamationmark.triangle.fill", size = 38.dp, tint = DS.Colors.orange400)
        Text(message, Modifier.padding(horizontal = 32.dp), style = sys(14), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
        if (job.needsContact) {
            GradientPill("Bizimle iletişime geçin", "bubble.left.and.text.bubble.right", RoundedCornerShape(DS.Radius.control)) { actions.openContact() }
        } else if (job.needsCredits) {
            GradientPill(lacksCreditsLabel, "creditcard", CircleShape) { actions.openCredits(message) }
            Row(
                Modifier
                    .pressable { vm.regenerate(job.id) }
                    .height(40.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("arrow.clockwise", size = 13.dp, tint = DS.Colors.onSurface)
                Text("Kredi aldıktan sonra tekrar dene", style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
            }
        } else {
            GradientPill("Tekrar dene", "arrow.clockwise", CircleShape) { vm.regenerate(job.id) }
        }
    }
}

@Composable
private fun GradientPill(title: String, icon: String, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick = onClick)
            .height(46.dp)
            .clip(shape)
            .background(DS.Gradients.primaryGradient)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 15.dp, tint = Color.White)
        Text(title, style = sys(15, FontWeight.Bold), color = Color.White)
    }
}

@Composable
private fun ResultControls(vm: SmileSimulationViewModel, job: Job, actions: SimActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .pressable { vm.backToConfigure() }
                .height(52.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DfyIcon("slider.horizontal.3", size = 14.dp, tint = DS.Colors.onSurface)
            Text("Tasarım", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1)
        }

        val regenEnabled = !(job.state.isRunning || vm.isGenerating)
        Box(
            Modifier
                .pressable(enabled = regenEnabled) { vm.regenerate(job.id) }
                .alpha(if (regenEnabled) 1f else 0.45f)
                .semantics { contentDescription = "Bu tasarımı yeniden üret" }
                .size(52.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { DfyIcon("arrow.clockwise", size = 17.dp, tint = DS.Colors.onSurface) }

        if (vm.jobs.count { it.result != null } > 1) {
            Box(
                Modifier
                    .pressable(onClick = actions.openCompare)
                    .semantics { contentDescription = "Tasarımları karşılaştır" }
                    .size(52.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { DfyIcon("square.grid.2x2", size = 17.dp, tint = DS.Colors.onSurface) }
        }

        if (job.result != null) {
            Row(
                Modifier
                    .weight(1f)
                    .pressable { actions.share(job) }
                    .height(52.dp)
                    .shadow(12.dp, CircleShape, ambientColor = DS.Colors.cyan400.copy(alpha = 0.3f), spotColor = DS.Colors.cyan400.copy(alpha = 0.3f))
                    .clip(CircleShape)
                    .background(DS.Gradients.primaryGradient),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("paperplane.fill", size = 15.dp, tint = Color.White)
                Text("Hastaya Gönder", style = sys(15, FontWeight.Bold), color = Color.White, maxLines = 1)
            }
        } else {
            Spacer(Modifier.weight(1f).height(52.dp))
        }
    }
}

// MARK: - Durum ekranları

@Composable
private fun StatusView(title: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        ThinkingDots(dotSize = 10.dp)
        Text(title, style = sys(18, FontWeight.SemiBold), color = DS.Colors.onSurface)
    }
}

@Composable
private fun FailedView(message: String, vm: SmileSimulationViewModel, actions: SimActions) {
    Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        DfyIcon("exclamationmark.triangle.fill", size = 44.dp, tint = DS.Colors.orange400)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            Text(message, Modifier.padding(horizontal = 32.dp), style = sys(15), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.weight(1f).pressable { vm.reset() }.height(52.dp).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("Farklı fotoğraf", style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface) }
            Box(
                Modifier.weight(1f).pressable { vm.reset(); actions.openCamera() }.height(52.dp).clip(CircleShape).background(DS.Gradients.primaryGradient),
                contentAlignment = Alignment.Center,
            ) { Text("Rehberli çekim", style = sys(16, FontWeight.Bold), color = Color.White) }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileSimulationViewPreview() {
    PreviewEnv { SmileSimulationView(onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileSimulationGuestPreview() {
    PreviewEnv(loggedIn = false) { SmileSimulationView(onDismiss = {}, guest = true) }
}
