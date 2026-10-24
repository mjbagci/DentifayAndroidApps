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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.geometry.Offset
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
import com.dentifay.app.ui.PreviewEnv
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
 */
@Composable
fun XrayAnalysisView(onDismiss: () -> Unit, mode: XrayAnalysisViewModel.Mode) {
    val vm = remember { XrayAnalysisViewModel(mode) }
    val trial = GuestTrialStore.shared
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
                    Phase.adjust -> AdjustContent(vm)
                    Phase.review -> ReviewContent(
                        vm, isGuest, showOriginal,
                        onToggleOriginal = { showOriginal = !showOriginal },
                        onRetake = { Haptics.medium(); showCamera = true },
                        onShowContact = { showContact = true },
                        onEditCrop = { showOriginal = false; vm.backToAdjust() },
                    )
                    Phase.analyzing -> AnalyzingContent(vm)
                    Phase.results -> ResultsContent(
                        vm, isGuest, compare, playLockOn, showExisting,
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
        SmileCreditsView(reason = cs?.reason, onDismiss = dismiss)
    }
}

/** Analiz sürerken ekran kararmasın (iOS `isIdleTimerDisabled`). */
private fun keepScreenOn(on: Boolean) {
    val w = ActivityHolder.current?.window ?: return
    if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

private suspend fun readBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
}

/** Swift `.frame(maxWidth: N).frame(maxWidth: .infinity)` — ortalanmış en fazla N genişlik. */
private fun Modifier.maxContentWidth(max: Dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** `Label(title, dfy: icon)` — düğme içinde. */
@Composable
private fun RowScope.XrayButtonLabel(text: String, icon: String) {
    DfyIcon(icon, size = 15.dp, tint = LocalContentColor.current)
    Spacer(Modifier.width(8.dp))
    ButtonLabel(text)
}

/** Alt eylem şeridi: üstte ince çizgi, zemin rengi alt güvenli alana taşar. */
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
                .maxContentWidth(maxWidth)
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

// MARK: - Üst bar

@Composable
private fun XrayTopBar(vm: XrayAnalysisViewModel, isGuest: Boolean, onBack: () -> Unit, onShowContact: () -> Unit) {
    val phase = vm.phase
    val backMode = phase == Phase.adjust || phase == Phase.review
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
            Text("Röntgen Analizi", style = sys(17, FontWeight.Bold), color = Color.White, maxLines = 1)
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
                    .widthIn(max = 150.dp)
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
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .maxContentWidth(560.dp)
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                XraySampleHero(Modifier.appear(0.02))

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
                style = sys(13), color = DS.Colors.onSurfaceVariant,
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
        Text(text, style = sys(12.5), color = DS.Colors.onSurfaceVariant)
    }
}

// MARK: 2 · Alan seçimi

@Composable
private fun AdjustContent(vm: XrayAnalysisViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(
            if (vm.needsManualCrop) "Röntgen otomatik bulunamadı. Köşeleri röntgenin kenarlarına sürükleyin."
            else "Köşeleri röntgenin kenarlarına sürükleyin; seçilen alan düzleştirilir.",
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            style = sys(13), color = if (vm.needsManualCrop) DS.Colors.warning else DS.Colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        val img = vm.originalImage
        if (img != null) {
            XrayRegionEditor(img, vm.quad, { vm.quad = it }, Modifier.weight(1f).fillMaxWidth())
        } else {
            Spacer(Modifier.weight(1f))
        }

        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            XrayToolChip("Otomatik", "viewfinder", enabled = vm.detectedQuad != null) { vm.resetToDetected() }
            XrayToolChip("Tüm görsel", "arrow.up.left.and.arrow.down.right", enabled = true) { vm.resetToFull() }
            XrayToolChip("Döndür", "rotate.right", enabled = true) { vm.rotate() }
        }

        XrayBottomBar {
            PrimaryButton(onClick = { vm.confirmRegion() }, enabled = vm.quad.isValid || vm.quad.isFull) {
                XrayButtonLabel("Bu alanı kullan", "checkmark")
            }
        }
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
    onToggleOriginal: () -> Unit,
    onRetake: () -> Unit,
    onShowContact: () -> Unit,
    onEditCrop: () -> Unit,
) {
    val trial = GuestTrialStore.shared
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .maxContentWidth(560.dp)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val shown = if (showOriginal) vm.originalImage else vm.preparedImage
                val targetAspect = shown?.let { it.width.toFloat() / max(it.height, 1) } ?: 2f
                val aspect by animateFloatAsState(targetAspect, Motion.easeOut(200), label = "aspect")
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(max(0.2f, aspect))
                        .clip(shape)
                        .background(Color.Black)
                        .border(1.dp, DS.Colors.hairlineStrong, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(showOriginal, animationSpec = tween(200, easing = Motion.EaseOut), label = "orig") { orig ->
                        val o = vm.originalImage
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
                            Text("Otomatik ayrıştırıldı", style = sys(11, FontWeight.SemiBold), color = Color.White)
                        }
                    }
                }

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
                    XrayPrivacyNote("Röntgen hastanın dosyasına kaydedilir; analiz kliniğinizin AI kredisinden düşer (başarısız analizde iade edilir).")
                }
            }
        }

        XrayBottomBar {
            if (isGuest && trial.isExhausted(GuestTrialFeature.xray)) {
                PrimaryButton(onClick = onShowContact) { XrayButtonLabel("Hakkınız doldu · İletişime geçin", "bubble.left.and.text.bubble.right") }
            } else {
                PrimaryButton(onClick = { vm.analyze() }, enabled = vm.canAnalyze) { XrayButtonLabel("Analizi başlat", "play.fill") }
            }
            SecondaryButton(onClick = onEditCrop, height = 44.dp) { XrayButtonLabel("Kırpmayı düzenle", "scissors") }
        }
    }
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
    Column(
        Modifier.fillMaxWidth().glassCard(16.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("Hasta")
        val p = vm.patient
        if (p != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DfyIcon("person.crop.circle", size = 22.dp, tint = DS.Colors.metal)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
                    p.subtitle?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant) }
                }
                Text(
                    "Değiştir", Modifier.plainClickable { vm.patient = null },
                    style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400,
                )
            }
        } else {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
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
                        Text("Hasta ara (ad, telefon)", style = sys(17), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1)
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .plainClickable { vm.selectPatient(r) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.name, Modifier.weight(1f), style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface)
                    r.subtitle?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant) }
                }
                XrayDivider()
            }
        }
    }
}

// MARK: 4 · Analiz

@Composable
private fun AnalyzingContent(vm: XrayAnalysisViewModel) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.maxContentWidth(640.dp).weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        ) {
            val img = vm.preparedImage
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .aspectRatio(img?.let { it.width.toFloat() / max(it.height, 1) } ?: 2f)
                    .clip(shape)
                    .background(Color.Black)
                    .border(1.dp, DS.Colors.hairlineStrong, shape),
            ) {
                if (img != null) {
                    val bmp = remember(img) { img.asImageBitmap() }
                    Image(bmp, null, Modifier.fillMaxSize().alpha(0.85f), contentScale = ContentScale.Fit)
                }
                XrayScanningOverlay()
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
                    Text("Dişler analiz ediliyor", style = sys(17, FontWeight.SemiBold), color = Color.White)
                    AnimatedContent(
                        targetState = vm.elapsed.toInt(),
                        transitionSpec = {
                            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                        },
                        label = "elapsed",
                    ) { s ->
                        Text("· $s sn", style = sys(15, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
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
    onCompare: () -> Unit,
    onLockOnDone: () -> Unit,
    onToggleExisting: () -> Unit,
    onShowContact: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .maxContentWidth(640.dp)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XraySummaryPill(vm.toothCount, "diş", DS.Colors.success)
                    XraySummaryPill(vm.findingCount, "öneri", DS.Colors.danger)
                    XraySummaryPill(vm.existingCount, "mevcut", mutedHex("06b6d4"))
                }

                val img = vm.preparedImage
                val result = vm.result
                if (img != null && result != null) {
                    key(vm.sensitivity.rawValue, vm.showHealthy) {
                        XrayResultCanvas(
                            image = img, findings = vm.visibleFindings,
                            selectedID = vm.selectedToothID,
                            onSelectedIDChange = { vm.selectedToothID = it },
                            anatomy = result.anatomy(), showAnatomy = vm.showAnatomy,
                            compare = compare, playLockOn = playLockOn,
                        )
                        LaunchedEffect(Unit) { delay(3000); onLockOnDone() }
                    }
                }

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XrayToggleChip("Karşılaştır", "rectangle.split.2x1", compare, onCompare)
                    XrayToggleChip("Sağlıklı dişler", "circle.dashed", vm.showHealthy) { vm.showHealthy = !vm.showHealthy }
                    XrayToggleChip("Anatomi", "square.dashed", vm.showAnatomy) { vm.showAnatomy = !vm.showAnatomy }
                }

                val selected = vm.selectedTooth
                var lastSelected by remember { mutableStateOf(selected) }
                if (selected != null) lastSelected = selected
                AnimatedVisibility(
                    visible = selected != null,
                    enter = fadeIn(Motion.easeOut(200)) + slideInVertically(Motion.easeOut(200)) { -it / 3 } + expandVertically(Motion.easeOut(200)),
                    exit = fadeOut(Motion.easeOut(200)) + slideOutVertically(Motion.easeOut(200)) { -it / 3 } + shrinkVertically(Motion.easeOut(200)),
                ) {
                    lastSelected?.let { XrayToothRow(it, selected = true) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                style = sys(13, FontWeight.SemiBold), color = DS.Colors.warning,
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Bulgular")
                        Spacer(Modifier.weight(1f))
                        Text("${vm.findingTeeth.size} dişte öneri", style = sys(12), color = DS.Colors.onSurfaceVariant)
                    }
                    if (vm.findingTeeth.isEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().glassCard(14.dp).padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DfyIcon("checkmark.seal", size = 14.dp, tint = DS.Colors.success)
                            Text("Bu duyarlılıkta tedavi önerisi yok.", style = sys(14, FontWeight.Medium), color = DS.Colors.success)
                        }
                    }
                    for (t in vm.findingTeeth) {
                        Box(
                            Modifier.pressable(scale = 0.99f) {
                                Haptics.selection()
                                vm.selectedToothID = if (vm.selectedToothID == t.id) null else t.id
                            },
                        ) {
                            XrayToothRow(t, selected = vm.selectedToothID == t.id)
                        }
                    }
                }

                val existingOnly = vm.findings.filter { it.recs.isEmpty() && it.existing.isNotEmpty() }
                if (existingOnly.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().plainClickable(onClick = onToggleExisting), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Mevcut restorasyonlar · ${existingOnly.size} diş")
                            Spacer(Modifier.weight(1f))
                            DfyIcon(if (showExisting) "chevron.up" else "chevron.down", size = 12.dp, tint = DS.Colors.metal)
                        }
                        AnimatedVisibility(
                            visible = showExisting,
                            enter = fadeIn(Motion.easeOut(200)) + expandVertically(Motion.easeOut(200)),
                            exit = fadeOut(Motion.easeOut(200)) + shrinkVertically(Motion.easeOut(200)),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (t in existingOnly) XrayToothRow(t, compact = true)
                            }
                        }
                    }
                }

                XrayLegend(vm.findings, Modifier.fillMaxWidth().glassCard(14.dp).padding(14.dp))

                Text(
                    "Sonuçlar ön değerlendirmedir; kesin teşhis ve tedavi kararı hekime aittir.",
                    style = sys(12), color = DS.Colors.outline,
                )

                if (isGuest) {
                    Column(
                        Modifier.fillMaxWidth().glassCard(16.dp).padding(16.dp),
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

        XrayBottomBar(maxWidth = 640.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(onClick = { vm.reset() }, modifier = Modifier.weight(1f)) { XrayButtonLabel("Yeni röntgen", "plus") }
                PrimaryButton(onClick = onShare, modifier = Modifier.weight(1f)) { XrayButtonLabel("Paylaş", "square.and.arrow.up") }
            }
        }
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
    Column(
        Modifier.fillMaxSize().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.weight(1f))
        DfyIcon(if (exhausted) "lock" else "exclamationmark.triangle", size = 34.dp, tint = if (exhausted) DS.Colors.cyan400 else DS.Colors.warning)
        Text(
            message, Modifier.padding(horizontal = 32.dp),
            style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface, textAlign = TextAlign.Center,
        )
        val credit = vm.creditRequired
        if (credit != null) {
            CreditRequiredCard(
                credit = credit, modifier = Modifier.padding(horizontal = 20.dp),
                onBuy = { onBuy(credit.message) }, onRetry = { vm.retry() },
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.maxContentWidth(560.dp).padding(horizontal = 20.dp).padding(bottom = 8.dp),
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

@Composable
private fun XrayProgressContent(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
        Text(title, style = sys(16, FontWeight.SemiBold), color = Color.White)
        Text(detail, Modifier.padding(horizontal = 24.dp), style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// MARK: - Tanıtım görseli

private class XraySampleMark(val fdi: Int, val x: Float, val y: Float, val w: Float, val h: Float, val color: Color, val text: String)

private val sampleMarks = listOf(
    XraySampleMark(46, 0.280f, 0.430f, 0.073f, 0.206f, mutedHex("ef4444"), "Kanal %92"),
    XraySampleMark(21, 0.494f, 0.203f, 0.048f, 0.220f, mutedHex("3b82f6"), "Dolgu %94"),
    XraySampleMark(38, 0.755f, 0.380f, 0.072f, 0.167f, mutedHex("f59e0b"), "20 Yaş %95"),
)

/** Örnek panoramik + web tanıtım demosundaki üç bulgu (46 · 21 · 38). */
@Composable
fun XraySampleHero(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
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
            style = sys(9, FontWeight.Bold, tracking = 1), color = Color.White.copy(alpha = 0.85f),
        )
    }
}

// MARK: - Paylaşım

/** Paylaşım görseli: röntgen + bulgu kutuları + alt bilgi şeridi. */
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

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun XrayAnalysisViewPreview() {
    PreviewEnv {
        XrayAnalysisView(onDismiss = {}, mode = XrayAnalysisViewModel.Mode.guest)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun XraySampleHeroPreview() {
    PreviewEnv {
        Box(Modifier.padding(20.dp)) { XraySampleHero() }
    }
}
