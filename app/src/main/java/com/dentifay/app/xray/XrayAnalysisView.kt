package com.dentifay.app.xray

import com.dentifay.app.xray.XrayAnalysisViewModel.Phase
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.R
import com.dentifay.app.credit.CreditRequiredCard
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.KrediEkrani
import com.dentifay.app.credit.KrediIpucu
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.theme.ActivityHolder
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.LocalTextStyleOverride
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.PrimaryButton
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.SectionLabel
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sharedCacheFile
import com.dentifay.app.theme.shareContent
import com.dentifay.app.theme.sys
import com.dentifay.app.trial.GuestContactSheet
import com.dentifay.app.trial.GuestTrialFeature
import com.dentifay.app.trial.GuestTrialStore
import com.dentifay.app.trial.TrialQuotaChip
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Röntgen Analizi ekranı — web "Röntgen Analizi" stüdyosunun mobil karşılığı.
 * Giriş ekranından (`guest`, ücretsiz deneme) ve Analiz Merkezi'nden (`clinic`) açılır.
 * Sunan taraf tam ekran (`FullScreenCover`) içinde gösterir.
 *
 * `hasta` + `gorsel` verilirse hasta seçili ve görsel yüklü açılır (hastanın Medya'sından "Röntgen Analizi'nde aç" — M-E8.4).
 *
 * Tablet: içerik sütunu 560 / 640 dp ile sınırlı ve ortada; geniş yatay pencerede (≥ 840 dp, ya da kısa yatay bölünmüş
 * pencere) alan seçimi, önizleme ve sonuçlar İKİ BÖLME olur — görüntü solda, liste/denetimler sağda.
 */
@Composable
fun XrayAnalysisView(
    onDismiss: () -> Unit,
    mode: XrayAnalysisViewModel.Mode,
    hasta: XrayAnalysisViewModel.PatientRef? = null,
    gorsel: ByteArray? = null,
) {
    val vm = remember {
        XrayAnalysisViewModel(mode).apply {
            if (hasta != null) patient = hasta
            if (gorsel != null) load(gorsel, XrayAnalysisViewModel.Source.library)
        }
    }
    XrayAnalysisScreen(vm, onDismiss)
}

@Composable
private fun XrayAnalysisScreen(vm: XrayAnalysisViewModel, onDismiss: () -> Unit) {
    val trial = GuestTrialStore.shared
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ikiBolme = ikiBolmeMi(LocalPencere.current)

    var showCamera by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var creditSheet by remember { mutableStateOf<CreditSheetItem?>(null) }
    var compare by remember { mutableStateOf(false) }
    var playLockOn by remember { mutableStateOf(true) }
    var showExisting by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    val isGuest = vm.mode == XrayAnalysisViewModel.Mode.guest

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readBytes(context, uri)?.let { vm.load(it, XrayAnalysisViewModel.Source.library) } }
    }
    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { readBytes(context, uri)?.let { vm.load(it, XrayAnalysisViewModel.Source.file) } }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.dispose()
            keepScreenOn(false)
        }
    }
    LaunchedEffect(Unit) { if (isGuest) trial.refresh() }
    val phase = vm.phase
    LaunchedEffect(phase) {
        keepScreenOn(phase == Phase.analyzing)
        if (phase == Phase.results) { playLockOn = true; compare = false }
        if (phase != Phase.review) showOriginal = false
        // Hak sayısı başka cihaz/sekmede değişmiş olabilir: başlatmadan önce tazele.
        if (isGuest && (phase == Phase.review || phase == Phase.pickSource)) trial.refresh()
    }

    val back: () -> Unit = {
        Haptics.light()
        if (vm.phase == Phase.adjust && vm.isEditingCrop) vm.cancelAdjust()
        else if (vm.phase == Phase.adjust || vm.phase == Phase.review) vm.reset()
        else onDismiss()
    }
    BackHandler(onBack = back)

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // Yatay tutulan cihazda çentik / yan gezinme çubuğu içeriğin üstüne binmesin.
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)),
        ) {
            XrayTopBar(vm, isGuest, onBack = back, onShowContact = { showContact = true })
            AnimatedContent(
                targetState = phase,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(Motion.easeOut(250)) togetherWith fadeOut(Motion.easeOut(250)) },
                contentKey = { it::class },
                label = "phase",
            ) { p ->
                when (p) {
                    Phase.pickSource -> PickSourceContent(
                        onCamera = { Haptics.medium(); showCamera = true },
                        onGallery = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onFile = { fileImporter.launch(arrayOf("image/*")) },
                        onSample = { vm.loadSample(context) },
                    )
                    Phase.preparing -> XrayProgressContent("Röntgen ayrıştırılıyor…", "Film/ekran bulunuyor, kırpılıyor ve netleştiriliyor")
                    Phase.adjust -> AdjustContent(vm, ikiBolme)
                    Phase.review -> ReviewContent(
                        vm, isGuest, showOriginal, ikiBolme,
                        onToggleOriginal = { showOriginal = !showOriginal },
                        onRetake = { Haptics.medium(); showCamera = true },
                        onShowContact = { showContact = true },
                        onEditCrop = { showOriginal = false; vm.backToAdjust() },
                    )
                    Phase.analyzing -> AnalyzingContent(vm)
                    Phase.results -> ResultsContent(
                        vm, isGuest, compare, playLockOn, showExisting, ikiBolme,
                        onCompare = { compare = !compare },
                        onLockOnDone = { playLockOn = false },
                        onToggleExisting = { showExisting = !showExisting },
                        onShowContact = { showContact = true },
                        onShare = {
                            val img = vm.preparedImage
                            if (img != null) {
                                val list = vm.findings.filter { it.kind != XrayToothFinding.Kind.healthy }
                                scope.launch {
                                    val file = withContext(Dispatchers.Default) {
                                        val out = XrayAnnotatedRenderer.render(img, list)
                                        sharedCacheFile(context, "dentifay-rontgen-analizi.png").also { f ->
                                            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                        }
                                    }
                                    shareContent(context, files = listOf(file), mime = "image/png")
                                }
                            }
                        },
                    )
                    is Phase.failed -> FailedContent(
                        vm, p.message,
                        onShowContact = { showContact = true },
                        onBuy = { reason -> creditSheet = CreditSheetItem(reason) },
                    )
                }
            }
        }
    }

    FullScreenCover(isPresented = showCamera, onDismiss = { showCamera = false }, background = Color.Black) {
        XrayCameraView(
            onCapture = { data, hint -> vm.load(data, XrayAnalysisViewModel.Source.camera, hint) },
            onDismiss = { showCamera = false },
        )
    }
    DfySheet(isPresented = showContact, onDismiss = { showContact = false }) { dismiss ->
        GuestContactSheet(exhausted = if (trial.isExhausted(GuestTrialFeature.xray)) GuestTrialFeature.xray else null, onDismiss = dismiss)
    }
    val cs = creditSheet
    DfySheet(isPresented = cs != null, onDismiss = { creditSheet = null }) { dismiss ->
        KrediEkrani(reason = cs?.reason, onDismiss = dismiss)
    }
}

/** Geniş yatay pencere (tablet yatay) ya da kısa yatay pencere (bölünmüş ekran): görüntü solda, denetimler sağda. */
private fun ikiBolmeMi(p: PencereBilgisi): Boolean = p.yatay && (p.genislik >= 840.dp || (p.genis && p.kisa))

/** Sağ bölmenin genişliği (liste / denetimler). */
private fun bolmeGenisligi(p: PencereBilgisi): Dp = (p.genislik * 0.36f).coerceIn(340.dp, 460.dp)

/** Analiz sürerken ekran kararmasın (iOS `isIdleTimerDisabled`). */
private fun keepScreenOn(on: Boolean) {
    val w = ActivityHolder.current?.window ?: return
    if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

private suspend fun readBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
}

/** `Label(title, dfy: icon)` — düğme içinde. Büyük yazıda / dar düğmede metin kırpılmaz, "…" ile kısalır; ikon hep görünür. */
@Composable
private fun RowScope.XrayButtonLabel(text: String, icon: String) {
    DfyIcon(icon, size = 15.dp, tint = LocalContentColor.current)
    Spacer(Modifier.width(8.dp))
    Text(
        text, Modifier.weight(1f, fill = false),
        color = LocalContentColor.current, style = LocalTextStyleOverride.current,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Alt eylem şeridi: üstte ince çizgi, zemin rengi alt güvenli alana taşar. İçerik en çok `maxWidth`, ortada. */
@Composable
private fun XrayBottomBar(maxWidth: Dp = 560.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DS.Colors.background)
            .drawBehind { drawRect(DS.Colors.hairline, size = Size(size.width, 1.dp.toPx())) }
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .icerikGenisligi(maxWidth)
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun XrayDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(DS.Colors.hairline))
}

/** İki bölme arasındaki dikey ince çizgi. */
@Composable
private fun XrayPaneDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(DS.Colors.hairline))
}

// MARK: - Üst bar

@Composable
private fun XrayTopBar(vm: XrayAnalysisViewModel, isGuest: Boolean, onBack: () -> Unit, onShowContact: () -> Unit) {
    val phase = vm.phase
    val backMode = phase == Phase.adjust || phase == Phase.review
    val genis = LocalPencere.current.genis
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, DS.Colors.hairline, CircleShape)
                .plainClickable(onClick = onBack)
                .semantics { contentDescription = if (backMode) "Geri" else "Kapat" },
            contentAlignment = Alignment.Center,
        ) {
            DfyIcon(if (backMode) "chevron.left" else "xmark", size = 15.dp, tint = DS.Colors.onSurface)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Röntgen Analizi", style = sys(17, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle(vm), style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val p = vm.patient
        if (isGuest) {
            Box(Modifier.plainClickable(onClick = onShowContact)) { TrialQuotaChip(feature = GuestTrialFeature.xray) }
        } else if (p != null) {
            Row(
                Modifier
                    .widthIn(max = if (genis) 260.dp else 150.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, DS.Colors.hairline, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("person", size = 12.dp, tint = DS.Colors.onSurface)
                Text(p.name, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun subtitle(vm: XrayAnalysisViewModel): String = when (vm.phase) {
    Phase.pickSource -> "Diş diş bulgular"
    Phase.preparing -> "Röntgen ayrıştırılıyor"
    Phase.adjust -> if (vm.needsManualCrop) "Röntgen alanını seçin" else "Kırpmayı düzenleyin"
    Phase.review -> "Kontrol edin"
    Phase.analyzing -> "Dişler analiz ediliyor"
    Phase.results -> "${vm.toothCount} diş · ${vm.findingCount} bulgu"
    is Phase.failed -> if (vm.isTrialExhaustedError) "Deneme hakkı doldu" else "Analiz tamamlanamadı"
}

// MARK: 1 · Kaynak

@Composable
private fun PickSourceContent(onCamera: () -> Unit, onGallery: () -> Unit, onFile: () -> Unit, onSample: () -> Unit) {
    val kisa = LocalPencere.current.kisa
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .icerikGenisligi(560.dp)
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Kısa pencerede (yatay bölünmüş ekran) tanıtım görseli küçülür; oranı korunur.
                XraySampleHero(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .then(if (kisa) Modifier.heightIn(max = 150.dp) else Modifier.fillMaxWidth())
                        .appear(0.02),
                )

                Column(Modifier.appear(0.06), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Panoramik röntgeni yükleyin", style = sys(24, FontWeight.Bold, tracking = -0.4), color = Color.White)
                    Text(
                        "Dişler FDI numarasıyla işaretlenir; her bulgu için güven skoru ve tedavi önerisi çıkar. Karar her zaman hekimindir.",
                        style = sys(15).copy(lineHeight = 21.sp), color = DS.Colors.onSurfaceVariant,
                    )
                }

                Column(Modifier.fillMaxWidth().glassCard(16.dp).appear(0.1)) {
                    XrayTipRow("rectangle.split.3x1", "Panoramik (OPG) röntgen en iyi sonucu verir.")
                    XrayDivider()
                    XrayTipRow("sun.max", "Ekrandan çekiyorsanız tam karşıdan, yansımasız çekin.")
                    XrayDivider()
                    XrayTipRow("Sparkles", "Röntgen fotoğraftan otomatik ayrılır, düzleştirilir ve netleştirilir.")
                }
            }
        }

        XrayBottomBar {
            PrimaryButton(onClick = onCamera) { XrayButtonLabel("Kamerayla çek", "camera.viewfinder") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(onClick = onGallery, modifier = Modifier.weight(1f)) { XrayButtonLabel("Galeri", "photo.on.rectangle") }
                SecondaryButton(onClick = onFile, modifier = Modifier.weight(1f)) { XrayButtonLabel("Dosya", "folder") }
            }
            Text(
                buildAnnotatedString {
                    append("Röntgeniniz yok mu? ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Örnek röntgenle deneyin") }
                },
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .plainClickable(onClick = onSample)
                    .padding(vertical = 4.dp),
                style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun XrayTipRow(icon: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { DfyIcon(icon, size = 15.dp, tint = DS.Colors.metal) }
        Text(text, Modifier.weight(1f), style = sys(14), color = DS.Colors.onSurface)
    }
}

@Composable
private fun XrayPrivacyNote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        DfyIcon("lock.shield", size = 14.dp, tint = DS.Colors.success)
        Text(text, Modifier.weight(1f), style = sys(12.5), color = DS.Colors.onSurfaceVariant)
    }
}

// MARK: 2 · Alan seçimi

@Composable
private fun AdjustContent(vm: XrayAnalysisViewModel, ikiBolme: Boolean) {
    val img = vm.originalPreview
    // Sürükleme her karede köşeyi değiştirir; düğme yalnız geçerlilik değişince yeniden kurulur.
    val canConfirm by remember(vm) { derivedStateOf { vm.quad.isValid || vm.quad.isFull } }
    val instruction = if (vm.needsManualCrop) "Röntgen otomatik bulunamadı. Köşeleri röntgenin kenarlarına sürükleyin."
    else "Köşeleri röntgenin kenarlarına sürükleyin; seçilen alan düzleştirilir."
    val instructionColor = if (vm.needsManualCrop) DS.Colors.warning else DS.Colors.onSurfaceVariant

    if (ikiBolme) {
        val panel = bolmeGenisligi(LocalPencere.current)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) {
                if (img != null) XrayRegionEditor(img, { vm.quad }, { vm.quad = it }, Modifier.fillMaxSize())
            }
            XrayPaneDivider()
            Column(Modifier.width(panel).fillMaxHeight()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(instruction, style = sys(13), color = instructionColor)
                    XrayAdjustTools(vm, Arrangement.spacedBy(8.dp))
                }
                XrayBottomBar(maxWidth = panel) {
                    PrimaryButton(onClick = { vm.confirmRegion() }, enabled = canConfirm) { XrayButtonLabel("Bu alanı kullan", "checkmark") }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text(
                instruction,
                Modifier.icerikGenisligi(560.dp).padding(horizontal = 28.dp),
                style = sys(13), color = instructionColor, textAlign = TextAlign.Center,
            )

            if (img != null) {
                XrayRegionEditor(img, { vm.quad }, { vm.quad = it }, Modifier.weight(1f).fillMaxWidth())
            } else {
                Spacer(Modifier.weight(1f))
            }

            XrayAdjustTools(
                vm, Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
            )

            XrayBottomBar {
                PrimaryButton(onClick = { vm.confirmRegion() }, enabled = canConfirm) { XrayButtonLabel("Bu alanı kullan", "checkmark") }
            }
        }
    }
}

/** "Otomatik · Tüm görsel · Döndür" — dar ekranda / büyük yazıda alt satıra kayar. */
@Composable
private fun XrayAdjustTools(vm: XrayAnalysisViewModel, arrangement: Arrangement.Horizontal, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        XrayToolChip("Otomatik", "viewfinder", enabled = vm.detectedQuad != null) { vm.resetToDetected() }
        XrayToolChip("Tüm görsel", "arrow.up.left.and.arrow.down.right", enabled = true) { vm.resetToFull() }
        XrayToolChip("Döndür", "rotate.right", enabled = true) { vm.rotate() }
    }
}

@Composable
private fun XrayToolChip(title: String, icon: String, enabled: Boolean, onClick: () -> Unit) {
    val fg = if (enabled) DS.Colors.onSurface else DS.Colors.outline
    Row(
        Modifier
            .pressable(enabled = enabled, onClick = onClick)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, DS.Colors.hairlineStrong, RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 13.dp, tint = fg)
        Text(title, style = sys(13, FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

// MARK: 3 · Önizleme

@Composable
private fun ReviewContent(
    vm: XrayAnalysisViewModel,
    isGuest: Boolean,
    showOriginal: Boolean,
    ikiBolme: Boolean,
    onToggleOriginal: () -> Unit,
    onRetake: () -> Unit,
    onShowContact: () -> Unit,
    onEditCrop: () -> Unit,
) {
    // Klavye açıkken (hasta arama) alt çubuk gizlenir: klavyenin üstüne binip arama sonuçlarını örtüyordu.
    val klavyeAcik = WindowInsets.isImeVisible
    if (ikiBolme) {
        val panel = bolmeGenisligi(LocalPencere.current)
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .navigationBarsPadding()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                XrayReviewPreview(vm, showOriginal, Modifier)
            }
            XrayPaneDivider()
            // Klavye açılınca (hasta arama) denetimler klavyenin üstünde kalır; eylem çubuğu klavye kapanınca döner.
            Column(Modifier.width(panel).fillMaxHeight().imePadding()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    XrayReviewControls(vm, isGuest, showOriginal, onToggleOriginal, onRetake)
                }
                if (!klavyeAcik) XrayBottomBar(maxWidth = panel) { XrayReviewActions(vm, isGuest, onShowContact, onEditCrop) }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .icerikGenisligi(560.dp)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    XrayReviewPreview(vm, showOriginal, Modifier.fillMaxWidth())
                    XrayReviewControls(vm, isGuest, showOriginal, onToggleOriginal, onRetake)
                }
            }
            if (!klavyeAcik) XrayBottomBar { XrayReviewActions(vm, isGuest, onShowContact, onEditCrop) }
        }
    }
}

/** Hazırlanan röntgen (ya da orijinal fotoğraf + ayrıştırılan alan) — görüntünün en-boy oranında, verilen alana sığar. */
@Composable
private fun XrayReviewPreview(vm: XrayAnalysisViewModel, showOriginal: Boolean, modifier: Modifier) {
    val shown = if (showOriginal) vm.originalPreview else vm.preparedImage
    val targetAspect = shown?.let { it.width.toFloat() / max(it.height, 1) } ?: 2f
    val aspect by animateFloatAsState(targetAspect, Motion.easeOut(200), label = "aspect")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .aspectRatio(max(0.2f, aspect))
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, DS.Colors.hairlineStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(showOriginal, animationSpec = tween(200, easing = Motion.EaseOut), label = "orig") { orig ->
            val o = vm.originalPreview
            val pimg = vm.preparedImage
            if (orig && o != null) {
                XrayCropPreview(o, vm.quad)
            } else if (pimg != null) {
                val bmp = remember(pimg) { pimg.asImageBitmap() }
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        if (vm.isRebuilding) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        if (vm.isPhoto && vm.isAutoCropped && !showOriginal) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("Sparkles", size = 11.dp, tint = Color.White)
                Text("Otomatik ayrıştırıldı", style = sys(11, FontWeight.SemiBold), color = Color.White, maxLines = 1)
            }
        }
    }
}

/** Önizlemenin denetimleri (orijinal fotoğraf, kalite uyarısı, iyileştirme, hasta, not) — çağıranın sütununa dizilir. */
@Composable
private fun XrayReviewControls(
    vm: XrayAnalysisViewModel,
    isGuest: Boolean,
    showOriginal: Boolean,
    onToggleOriginal: () -> Unit,
    onRetake: () -> Unit,
) {
    val trial = GuestTrialStore.shared
    if (vm.isPhoto) {
        Row { XrayToggleChip("Orijinal fotoğraf", "eye", showOriginal, onToggleOriginal) }
    }

    if (vm.quality.hasIssues && !vm.isRebuilding) XrayQualityCard(vm, onRetake)

    Row(
        Modifier.fillMaxWidth().glassCard(16.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Görüntüyü iyileştir", style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface)
            Text(
                "Parlaklık ve kontrastı dijital röntgen düzeyine getirir — fotoğraflarda önerilir",
                style = sys(12), color = DS.Colors.onSurfaceVariant,
            )
        }
        Switch(
            checked = vm.enhance,
            onCheckedChange = { vm.enhance = it },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = DS.Colors.cyan400, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.16f), uncheckedBorderColor = Color.Transparent,
            ),
        )
    }

    if (vm.mode == XrayAnalysisViewModel.Mode.clinic) XrayPatientPicker(vm)

    if (isGuest) {
        Row(
            Modifier.fillMaxWidth().glassCard(16.dp).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DfyIcon("gift", size = 16.dp, tint = DS.Colors.cyan400)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bu analiz 1 ücretsiz hak kullanır", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                val rem = trial.remaining(GuestTrialFeature.xray)
                Text(
                    if (rem != null) "Kalan: $rem/${trial.quota(GuestTrialFeature.xray)?.limit ?: trial.limit}. Analiz tamamlanamazsa hak düşülmez."
                    else "Analiz tamamlanamazsa hak düşülmez.",
                    style = sys(12.5), color = DS.Colors.onSurfaceVariant,
                )
            }
        }
    } else {
        // Ek MO.25c/d — sayı yazılmaz (kredi sayısı ipucundadır, M-E1.7.8).
        val demo = SmileCreditsStore.shared.wallet?.isDemo == true
        XrayPrivacyNote(
            "Röntgen hastanın dosyasına kaydedilir. " +
                if (demo) "Analiz demo kredinizden düşer." else "Analiz kliniğinizin kredisinden düşer.",
        )
    }
}

/** Önizlemenin alt eylemleri: analiz (+ kredi ipucu) ya da iletişim, kırpmayı düzenle. */
@Composable
private fun XrayReviewActions(vm: XrayAnalysisViewModel, isGuest: Boolean, onShowContact: () -> Unit, onEditCrop: () -> Unit) {
    val trial = GuestTrialStore.shared
    if (isGuest && trial.isExhausted(GuestTrialFeature.xray)) {
        PrimaryButton(onClick = onShowContact) { XrayButtonLabel("Hakkınız doldu · İletişime geçin", "bubble.left.and.text.bubble.right") }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(onClick = { vm.analyze() }, modifier = Modifier.weight(1f), enabled = vm.canAnalyze) {
                XrayButtonLabel("Analizi başlat", "play.fill")
            }
            if (!isGuest) KrediIpucu(islem = "xray:analyze")
        }
    }
    SecondaryButton(onClick = onEditCrop, height = 44.dp) { XrayButtonLabel("Kırpmayı düzenle", "scissors") }
}

/** Bulanık / küçük / parlamalı görüntü uyarısı — yeniden çekim önerir. */
@Composable
private fun XrayQualityCard(vm: XrayAnalysisViewModel, onRetake: () -> Unit) {
    val q = vm.quality
    val text = when {
        q.isBlurry -> "Görüntü bulanık görünüyor. Telefonu sabit tutup yeniden çekmek bulguların doğruluğunu artırır."
        q.isOverexposed -> "Röntgende parlama/yansıma var. Ekran parlaklığını azaltıp ya da açıyı biraz değiştirip yeniden çekin."
        else -> "Röntgen küçük görünüyor. Daha yakından, kadrajı dolduracak şekilde çekin."
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DS.Colors.warning.copy(alpha = 0.08f))
            .border(1.dp, DS.Colors.warning.copy(alpha = 0.28f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            DfyIcon("exclamationmark.triangle", size = 15.dp, tint = DS.Colors.warning)
            Text(text, Modifier.weight(1f), style = sys(13), color = DS.Colors.onSurface)
        }
        if (vm.source == XrayAnalysisViewModel.Source.camera) {
            Row(
                Modifier.plainClickable(onClick = onRetake),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("camera", size = 13.dp, tint = DS.Colors.warning)
                Text("Yeniden çek", style = sys(13, FontWeight.SemiBold), color = DS.Colors.warning)
            }
        }
    }
}

@Composable
private fun XrayPatientPicker(vm: XrayAnalysisViewModel) {
    // Sonuçlar gelince kart görünür alana kayar (klavye açıkken sonuçlar ekranın altında kalıyordu).
    val gorunurYap = remember { BringIntoViewRequester() }
    LaunchedEffect(vm.patientResults) { if (vm.patientResults.isNotEmpty()) gorunurYap.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(gorunurYap).glassCard(16.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("Hasta")
        val p = vm.patient
        if (p != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DfyIcon("person.crop.circle", size = 22.dp, tint = DS.Colors.metal)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.subtitle?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(
                    "Değiştir", Modifier.plainClickable { vm.patient = null },
                    style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1,
                )
            }
        } else {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(1.dp, DS.Colors.hairlineStrong, shape)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("magnifyingglass", size = 17.dp, tint = DS.Colors.metal)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (vm.patientQuery.isEmpty()) {
                        Text(
                            "Hasta ara (ad, telefon)", style = sys(17), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = vm.patientQuery,
                        onValueChange = { vm.patientQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = sys(17).copy(color = DS.Colors.onSurface),
                        cursorBrush = SolidColor(DS.Colors.cyan400),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                    )
                }
                if (vm.isSearchingPatients) CircularProgressIndicator(Modifier.size(16.dp), color = DS.Colors.onSurfaceVariant, strokeWidth = 2.dp)
            }

            for (r in vm.patientResults) {
                key(r.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClickable { vm.selectPatient(r) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            r.name, Modifier.weight(1f),
                            style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        r.subtitle?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1) }
                    }
                    XrayDivider()
                }
            }
        }
    }
}

// MARK: 4 · Analiz

@Composable
private fun AnalyzingContent(vm: XrayAnalysisViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
        // Kısa pencerede metin sığsın diye görüntü yüksekliği sınırlı (oran korunur).
        val imageCap = maxHeight * 0.55f
        Column(
            Modifier.fillMaxSize().icerikGenisligi(640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        ) {
            val img = vm.preparedImage
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .heightIn(max = imageCap)
                    .aspectRatio(img?.let { it.width.toFloat() / max(it.height, 1) } ?: 2f)
                    .clip(shape)
                    .background(Color.Black)
                    .border(1.dp, DS.Colors.hairlineStrong, shape),
            ) {
                if (img != null) {
                    val bmp = remember(img) { img.asImageBitmap() }
                    Image(bmp, null, Modifier.fillMaxSize().alpha(0.85f), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
                }
                XrayScanningOverlay()
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
                    Text("Dişler analiz ediliyor", style = sys(17, FontWeight.SemiBold), color = Color.White, maxLines = 1)
                    AnimatedContent(
                        targetState = vm.elapsed.toInt(),
                        transitionSpec = {
                            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                        },
                        label = "elapsed",
                    ) { s ->
                        Text("· $s sn", style = sys(15, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                    }
                }
                Text(
                    if (vm.elapsed > 70) "Servis yoğun; sonuç biraz daha sürebilir. Bu ekranda kalın."
                    else "Numaralandırma, bulgu tespiti ve tedavi önerileri hazırlanıyor. İlk analiz 30–60 saniye sürebilir.",
                    Modifier.padding(horizontal = 32.dp),
                    style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// MARK: 5 · Sonuçlar

@Composable
private fun ResultsContent(
    vm: XrayAnalysisViewModel,
    isGuest: Boolean,
    compare: Boolean,
    playLockOn: Boolean,
    showExisting: Boolean,
    ikiBolme: Boolean,
    onCompare: () -> Unit,
    onLockOnDone: () -> Unit,
    onToggleExisting: () -> Unit,
    onShowContact: () -> Unit,
    onShare: () -> Unit,
) {
    val sens = vm.sensitivity
    val healthy = vm.showHealthy
    // Swift `.id("\(duyarlılık)-\(sağlıklı)")`: duyarlılık / sağlıklı değişince tuval sıfırdan kurulur. Yakınlaştırma durumu
    // burada tutulur: tembel listede tuval görünümden çıkıp dönünce ve pencere döndürülünce kaybolmaz.
    val canvasState = remember(sens, healthy) { XrayCanvasState() }
    LaunchedEffect(sens, healthy) { delay(3000); onLockOnDone() }

    if (ikiBolme) {
        val panel = bolmeGenisligi(LocalPencere.current)
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // Tuvalde diş seçilince seçili diş kartı (sağ listenin başı) görünsün; listeden seçimde liste yerinde kalır.
        val tuvaldenSec: (String?) -> Unit = { id ->
            vm.selectedToothID = id
            if (id != null) scope.launch { listState.animateScrollToItem(0) }
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            ) {
                XraySummaryRow(vm, Modifier.fillMaxWidth())
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    key(sens.rawValue, healthy) {
                        XrayResultsCanvasBlock(vm, compare, playLockOn, canvasState, Modifier, onSelect = tuvaldenSec)
                    }
                }
                XrayResultChips(vm, compare, onCompare, Modifier.fillMaxWidth())
            }
            XrayPaneDivider()
            Column(Modifier.width(panel).fillMaxHeight()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    xrayResultsList(
                        vm, isGuest, showExisting, onToggleExisting, onShowContact,
                        satir = Modifier.fillMaxWidth().padding(horizontal = 20.dp), ilk = true,
                    )
                }
                XrayBottomBar(maxWidth = panel) { XrayResultActions(vm, onShare) }
            }
        }
    } else {
        val satir = Modifier.icerikGenisligi(640.dp).padding(horizontal = 20.dp)
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "ozet") { XraySummaryRow(vm, satir) }
                if (vm.preparedImage != null && vm.result != null) {
                    item(key = "tuval-${sens.rawValue}-$healthy", contentType = "tuval") {
                        XrayResultsCanvasBlock(vm, compare, playLockOn, canvasState, satir.padding(top = 16.dp).fillMaxWidth())
                    }
                }
                item(key = "cipler") { XrayResultChips(vm, compare, onCompare, satir.padding(top = 16.dp)) }
                xrayResultsList(vm, isGuest, showExisting, onToggleExisting, onShowContact, satir = satir)
            }
            XrayBottomBar(maxWidth = 640.dp) { XrayResultActions(vm, onShare) }
        }
    }
}

/** Diş · öneri · mevcut sayıları — dar ekranda / büyük yazıda alt satıra kayar. */
@Composable
private fun XraySummaryRow(vm: XrayAnalysisViewModel, modifier: Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        XraySummaryPill(vm.toothCount, "diş", DS.Colors.success)
        XraySummaryPill(vm.findingCount, "öneri", DS.Colors.danger)
        XraySummaryPill(vm.existingCount, "mevcut", mutedHex("06b6d4"))
    }
}

@Composable
private fun XrayResultsCanvasBlock(
    vm: XrayAnalysisViewModel,
    compare: Boolean,
    playLockOn: Boolean,
    state: XrayCanvasState,
    modifier: Modifier,
    onSelect: (String?) -> Unit = { vm.selectedToothID = it },
) {
    val img = vm.preparedImage
    val result = vm.result
    if (img == null || result == null) return
    val anatomy = remember(result) { result.anatomy() }
    XrayResultCanvas(
        image = img, findings = vm.visibleFindings,
        selectedID = vm.selectedToothID,
        onSelectedIDChange = onSelect,
        modifier = modifier,
        anatomy = anatomy, showAnatomy = vm.showAnatomy,
        compare = compare, playLockOn = playLockOn,
        state = state,
    )
}

@Composable
private fun XrayResultChips(vm: XrayAnalysisViewModel, compare: Boolean, onCompare: () -> Unit, modifier: Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        XrayToggleChip("Karşılaştır", "rectangle.split.2x1", compare, onCompare)
        XrayToggleChip("Sağlıklı dişler", "circle.dashed", vm.showHealthy) { vm.showHealthy = !vm.showHealthy }
        XrayToggleChip("Anatomi", "square.dashed", vm.showAnatomy) { vm.showAnatomy = !vm.showAnatomy }
    }
}

@Composable
private fun XrayResultActions(vm: XrayAnalysisViewModel, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(onClick = { vm.reset() }, modifier = Modifier.weight(1f)) { XrayButtonLabel("Yeni röntgen", "plus") }
        PrimaryButton(onClick = onShare, modifier = Modifier.weight(1f)) { XrayButtonLabel("Paylaş", "square.and.arrow.up") }
    }
}

/**
 * Sonuç listesi (tembel): seçili diş · duyarlılık · bulgular · mevcut restorasyonlar · açıklama · not · tanıtım.
 * Telefonda tuvalin altında, tablette sağ bölmede. Bloklar arası 16, bölüm içi 10 dp (Swift `VStack` aralıkları).
 */
private fun LazyListScope.xrayResultsList(
    vm: XrayAnalysisViewModel,
    isGuest: Boolean,
    showExisting: Boolean,
    onToggleExisting: () -> Unit,
    onShowContact: () -> Unit,
    satir: Modifier,
    ilk: Boolean = false,
) {
    item(key = "secili", contentType = "secili") {
        XraySelectedTooth(vm, satir, topPadding = if (ilk) 4.dp else 16.dp)
    }

    item(key = "duyarlilik") {
        Column(satir.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Duyarlılık")
            XraySensitivityPicker(vm.sensitivity, { vm.sensitivity = it })
            if (vm.suggestsLowerSensitivity) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(DS.Colors.warning.copy(alpha = 0.08f))
                        .border(1.dp, DS.Colors.warning.copy(alpha = 0.28f), shape)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DfyIcon("exclamationmark.triangle", size = 17.dp, tint = DS.Colors.warning)
                    Text(
                        "Çok az bulgu çıktı. Daha düşük güvenli olasılıkları da görmek ister misiniz?",
                        Modifier.weight(1f), style = sys(12.5), color = DS.Colors.onSurface,
                    )
                    Text(
                        "Düşür",
                        Modifier.plainClickable { vm.sensitivity = XraySensitivity.all; Haptics.selection() },
                        style = sys(13, FontWeight.SemiBold), color = DS.Colors.warning, maxLines = 1,
                    )
                }
            }
        }
    }

    item(key = "bulgular") {
        Column(satir.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Bulgular")
                Spacer(Modifier.weight(1f))
                Text("${vm.findingTeeth.size} dişte öneri", style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1)
            }
            if (vm.findingTeeth.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().glassCard(14.dp).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DfyIcon("checkmark.seal", size = 14.dp, tint = DS.Colors.success)
                    Text("Bu duyarlılıkta tedavi önerisi yok.", Modifier.weight(1f), style = sys(14, FontWeight.Medium), color = DS.Colors.success)
                }
            }
        }
    }
    items(vm.findingTeeth, key = { "b-${it.id}" }, contentType = { "dis" }) { t ->
        Box(
            satir
                .padding(top = 10.dp)
                .pressable(scale = 0.99f) {
                    Haptics.selection()
                    vm.selectedToothID = if (vm.selectedToothID == t.id) null else t.id
                },
        ) {
            XrayToothRow(t, selected = vm.selectedToothID == t.id)
        }
    }

    val existingOnly = vm.existingOnly
    if (existingOnly.isNotEmpty()) {
        item(key = "mevcut") {
            Row(
                satir.padding(top = 16.dp).plainClickable(onClick = onToggleExisting),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel("Mevcut restorasyonlar · ${existingOnly.size} diş", Modifier.weight(1f))
                DfyIcon(if (showExisting) "chevron.up" else "chevron.down", size = 12.dp, tint = DS.Colors.metal)
            }
        }
        if (showExisting) {
            items(existingOnly, key = { "m-${it.id}" }, contentType = { "dis" }) { t ->
                XrayToothRow(t, satir.padding(top = 10.dp).animateItem(), compact = true)
            }
        }
    }

    item(key = "aciklama") {
        XrayLegend(vm.findings, satir.padding(top = 16.dp).glassCard(14.dp).padding(14.dp))
    }

    item(key = "not") {
        Text(
            "Sonuçlar ön değerlendirmedir; kesin teşhis ve tedavi kararı hekime aittir.",
            satir.padding(top = 16.dp),
            style = sys(12), color = DS.Colors.outline,
        )
    }

    if (isGuest) {
        item(key = "tanitim") {
            Column(
                satir.padding(top = 16.dp).glassCard(16.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Kliniğinizde kullanın", style = sys(16, FontWeight.Bold), color = Color.White)
                Text(
                    "Klinik hesabıyla röntgen hasta kartına kaydedilir, bulgulardan fiyatlı tedavi planı taslağı oluşur ve hekim onayına gider.",
                    style = sys(13), color = DS.Colors.onSurfaceVariant,
                )
                SecondaryButton(onClick = onShowContact, height = 44.dp) { XrayButtonLabel("Bizimle iletişime geçin", "arrow.up.right") }
            }
        }
    }
}

/** Seçili diş kartı (tuvalde ya da listede seçilince açılır). */
@Composable
private fun XraySelectedTooth(vm: XrayAnalysisViewModel, satir: Modifier, topPadding: Dp) {
    val selected = vm.selectedTooth
    // Kapanış animasyonu süresince son seçili diş çizilmeye devam etsin (durum değil — yeniden kurulum tetiklemez).
    val son = remember { arrayOfNulls<XrayToothFinding>(1) }
    if (selected != null) son[0] = selected
    AnimatedVisibility(
        visible = selected != null,
        modifier = satir,
        enter = fadeIn(Motion.easeOut(200)) + slideInVertically(Motion.easeOut(200)) { -it / 3 } + expandVertically(Motion.easeOut(200)),
        exit = fadeOut(Motion.easeOut(200)) + slideOutVertically(Motion.easeOut(200)) { -it / 3 } + shrinkVertically(Motion.easeOut(200)),
    ) {
        son[0]?.let { XrayToothRow(it, Modifier.padding(top = topPadding), selected = true) }
    }
}

@Composable
private fun XrayToggleChip(title: String, icon: String, on: Boolean, action: () -> Unit) {
    val fg by animateColorAsState(if (on) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant, Motion.easeOut(200), label = "chipFg")
    val bg by animateColorAsState(if (on) DS.Colors.cyan400.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f), Motion.easeOut(200), label = "chipBg")
    val border by animateColorAsState(if (on) DS.Colors.cyan400.copy(alpha = 0.4f) else DS.Colors.hairlineStrong, Motion.easeOut(200), label = "chipBorder")
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .plainClickable { Haptics.selection(); action() }
            .semantics { selected = on }   // Swift `.accessibilityAddTraits(on ? .isSelected : [])`
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(icon, size = 13.dp, tint = fg)
        Text(title, style = sys(13, FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

// MARK: 6 · Hata

@Composable
private fun FailedContent(vm: XrayAnalysisViewModel, message: String, onShowContact: () -> Unit, onBuy: (String) -> Unit) {
    val exhausted = vm.isTrialExhaustedError
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        // Kısa pencerede (kredi kartı + düğmeler) kaydırılır; uzunsa ortalı kalır.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.weight(1f))
            DfyIcon(if (exhausted) "lock" else "exclamationmark.triangle", size = 34.dp, tint = if (exhausted) DS.Colors.cyan400 else DS.Colors.warning)
            val credit = vm.creditRequired
            if (credit != null) {
                // Kredi kartı başlığı ve gerekçeyi zaten söyler; aynı cümle üstte bir daha yazılmaz, "Tekrar dene" alttaki
                // ana düğmede (kartta ikincisi çizilmez).
                CreditRequiredCard(
                    credit = credit, modifier = Modifier.icerikGenisligi(560.dp).padding(horizontal = 20.dp),
                    onBuy = { onBuy(credit.message) },
                )
            } else {
                Text(
                    message, Modifier.icerikGenisligi(560.dp).padding(horizontal = 32.dp),
                    style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.icerikGenisligi(560.dp).padding(horizontal = 20.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (exhausted) {
                    PrimaryButton(onClick = onShowContact) { XrayButtonLabel("Bizimle iletişime geçin", "bubble.left.and.text.bubble.right") }
                } else {
                    PrimaryButton(onClick = { vm.retry() }) { XrayButtonLabel("Tekrar dene", "arrow.clockwise") }
                }
                SecondaryButton(onClick = { vm.reset() }, height = 44.dp) { ButtonLabel("Farklı röntgen seç") }
            }
        }
    }
}

@Composable
private fun XrayProgressContent(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
        Text(title, style = sys(16, FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center)
        Text(
            detail, Modifier.icerikGenisligi(560.dp).padding(horizontal = 24.dp),
            style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Tanıtım görseli

private class XraySampleMark(val fdi: Int, val x: Float, val y: Float, val w: Float, val h: Float, val color: Color, val text: String)

private val sampleMarks = listOf(
    XraySampleMark(46, 0.280f, 0.430f, 0.073f, 0.206f, mutedHex("ef4444"), "Kanal %92"),
    XraySampleMark(21, 0.494f, 0.203f, 0.048f, 0.220f, mutedHex("3b82f6"), "Dolgu %94"),
    XraySampleMark(38, 0.755f, 0.380f, 0.072f, 0.167f, mutedHex("f59e0b"), "20 Yaş %95"),
)

/**
 * Örnek panoramik + web tanıtım demosundaki üç bulgu (46 · 21 · 38). Görselin oranında, verilen kısıtlara sığar
 * (tam genişlik için `Modifier.fillMaxWidth()` verin; kısa pencerede `heightIn(max = …)` ile küçülür).
 */
@Composable
fun XraySampleHero(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    BoxWithConstraints(
        modifier
            .aspectRatio(1582f / 814f)
            .clip(shape)
            .border(1.dp, DS.Colors.hairlineStrong, shape)
            .semantics { contentDescription = "Örnek panoramik röntgen üzerinde işaretlenmiş üç bulgu" },
    ) {
        val sw = maxWidth; val sh = maxHeight
        Image(painterResource(R.drawable.xray_sample), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        for (m in sampleMarks) {
            val x = sw * m.x; val y = sh * m.y; val w = sw * m.w; val h = sh * m.h
            Box(
                Modifier
                    .offset(x, y)
                    .size(w, h)
                    .drawBehind { xrayRoundRect(m.color, 1.5.dp, 3.dp, fill = m.color.copy(alpha = 0.08f)) },
            )
            Text(
                "${m.fdi}",
                Modifier
                    .offset(x - 3.dp, y - 7.dp)
                    .background(m.color, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                style = sys(8.5, FontWeight.Bold), color = Color.White, maxLines = 1, softWrap = false,
            )
            Text(
                m.text,
                Modifier
                    .offset(x - 2.dp, y + h + 2.dp)
                    .background(m.color.copy(alpha = 0.92f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                style = sys(7.5, FontWeight.SemiBold), color = Color.White, maxLines = 1, softWrap = false,
            )
        }
        Text(
            "ÖRNEK GÖRÜNÜM",
            Modifier
                .padding(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            style = sys(9, FontWeight.Bold, tracking = 1), color = Color.White.copy(alpha = 0.85f), maxLines = 1,
        )
    }
}

// MARK: - Paylaşım

/** Paylaşım görseli: röntgen + bulgu kutuları + alt bilgi şeridi. Arka planda çağrılır. */
object XrayAnnotatedRenderer {
    fun render(image: Bitmap, findings: List<XrayToothFinding>): Bitmap {
        val maxW = 2000f
        val scale = min(1f, maxW / max(image.width, 1))
        val w = image.width * scale; val h = image.height * scale
        val footer = max(44f, w * 0.035f)
        val out = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), (h + footer).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = ACanvas(out)
        c.drawColor(DS.Colors.background.toArgb())
        c.drawBitmap(image, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val lw = max(2f, w / 700)
        val semibold = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = semibold
            textSize = max(12f, w / 95)
            color = android.graphics.Color.WHITE
        }
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        for (f in findings) {
            val r = RectF(f.rect.left * w, f.rect.top * h, f.rect.right * w, f.rect.bottom * h)
            val color = f.color.toArgb()
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = lw
                this.color = color
                if (f.kind == XrayToothFinding.Kind.finding && !f.isHighConfidence) pathEffect = DashPathEffect(floatArrayOf(lw * 3, lw * 2), 0f)
            }
            c.drawRoundRect(r, lw * 2, lw * 2, stroke)
            val label = if (f.kind == XrayToothFinding.Kind.finding) "${f.fdi} · ${f.badgeText ?: ""}" else "${f.fdi}"
            val tw = textPaint.measureText(label)
            val tagTop = max(0f, r.top - textH - 4)
            val tag = RectF(r.left, tagTop, r.left + tw + 8, tagTop + textH + 2)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                alpha = (0.92f * 255).toInt()
            }
            c.drawRoundRect(tag, 3f, 3f, fill)
            c.drawText(label, tag.left + 4, tag.top + 1 - fm.ascent, textPaint)
        }
        val caption = "Dentifay · Röntgen Analizi — ön değerlendirmedir, kesin karar hekime aittir."
        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT
            textSize = footer * 0.36f
            color = android.graphics.Color.rgb(204, 204, 204)
        }
        c.drawText(caption, footer * 0.4f, h + footer * 0.28f - capPaint.fontMetrics.ascent, capPaint)
        return out
    }
}

// MARK: - Önizleme

/** Sonuç ekranı örneği (ağsız): örnek röntgen yerine koyu zemin + `XrayPreviewData`. */
@Composable
private fun XraySonucOrnek() {
    val vm = remember {
        XrayAnalysisViewModel(XrayAnalysisViewModel.Mode.clinic).apply {
            onizlemeSonucu(
                XrayPreviewData.image(), XrayPreviewData.result,
                XrayAnalysisViewModel.PatientRef(id = "p1", name = "Ayşe Yılmaz", subtitle = "0532 000 00 00"),
            )
        }
    }
    XrayAnalysisScreen(vm, onDismiss = {})
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun XrayAnalysisViewPreview() {
    PreviewEnv {
        XrayAnalysisView(onDismiss = {}, mode = XrayAnalysisViewModel.Mode.guest)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun XrayAnalysisViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        XrayAnalysisView(onDismiss = {}, mode = XrayAnalysisViewModel.Mode.clinic)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun XrayResultsPreview() {
    PreviewEnv { XraySonucOrnek() }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun XrayResultsTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { XraySonucOrnek() }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun XrayResultsTabletPortraitPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) { XraySonucOrnek() }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun XraySampleHeroPreview() {
    PreviewEnv {
        Box(Modifier.padding(20.dp)) { XraySampleHero(Modifier.fillMaxWidth()) }
    }
}
