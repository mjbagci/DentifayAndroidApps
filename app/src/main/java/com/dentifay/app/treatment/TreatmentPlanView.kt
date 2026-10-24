@file:Suppress("ClassName", "EnumEntryName")

package com.dentifay.app.treatment

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.agent.*
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIDoctorMember
import com.dentifay.app.core.FDI
import com.dentifay.app.core.GrupTutari
import com.dentifay.app.core.TL
import com.dentifay.app.core.Unvan
import com.dentifay.app.core.Yetki
import com.dentifay.app.credit.CreditRequiredCard
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.KrediEkrani
import com.dentifay.app.credit.KrediIpucu
import com.dentifay.app.theme.AuroraBackground
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.PrimaryButton
import com.dentifay.app.theme.TagChip
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.treatment.TreatmentPlanViewModel.Phase
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots
import com.dentifay.app.ui.YetkiliButon
import com.dentifay.app.ui.icerikGenisligi
import com.dentifay.app.voice.AIOrb
import kotlinx.coroutines.launch

/** Metin/liste ekranı içerik tavanı (tablet). */
private val ICERIK = 720.dp
/** Geniş yatay pencerede yan yana kayıt düzeninin tavanı. */
private val GENIS_ICERIK = 1040.dp

/**
 * Tedavi Planı modülü. Analiz Merkezi'ndeki "Tedavi Planı" kartından açılır.
 * Planı Dentifay Asistan hazırlar; **Sonuç Onayı** ekranından onaylanınca sisteme yazılır.
 *
 * Tablet: içerik ~720 dp ortalı (hasta seçici, plan kartları, önizleme adımları, cevap alanı); geniş yatay pencerede
 * kayıt ekranı orb panelini yazma alanının yanına koyar (klavye açıkken alan görünür kalır). Her faz kısa pencerede kayar.
 */
@Composable
fun TreatmentPlanView(onDismiss: () -> Unit) {
    val vm = remember { TreatmentPlanViewModel() }
    TreatmentPlanEkrani(vm, onDismiss)
}

/** Ekranın kendisi — model dışarıdan verilir (önizlemeler örnek modelle çizer). */
@Composable
internal fun TreatmentPlanEkrani(vm: TreatmentPlanViewModel, onDismiss: () -> Unit) {
    val auth = LocalAuthManager.current
    val pencere = LocalPencere.current
    val scope = rememberCoroutineScope()
    /** Kredi gerekli (402) → kredi ekranı. */
    var creditSheet by remember { mutableStateOf<CreditSheetItem?>(null) }
    // `…` menüsü akışları
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    /** "Listede yok" penceresinin kartı (menü kimliği + `term`). */
    var menuKarti by remember { mutableStateOf<AgentChoiceSet?>(null) }
    val duzenleme = remember { DuzenlemeDurumu() }

    LaunchedEffect(vm) {
        vm.kullaniciId = auth.currentUser?.id
        vm.saatDilimi = auth.currentUser?.timezone
        vm.onAppear()
    }
    DisposableEffect(vm) { onDispose { vm.dispose() } }

    val phase = vm.phase
    val geriVar = showsBackArrow(phase)
    // Android geri hareketi üst bardaki geri okuyla aynı yolu izler; onay turunda (kapatma pasif) yutulur.
    BackHandler(enabled = geriVar) { geri(vm) }
    BackHandler(enabled = !vm.kapatmaSerbest) { }

    val kapat: () -> Unit = { vm.teardown(); onDismiss() }
    val yanYana = phase == Phase.recording && pencere.genislik >= 840.dp && pencere.yatay
    val icerikMax = if (yanYana) GENIS_ICERIK else ICERIK

    /** `…` menüsü ve hekim düğmeleri: pencereyi hazırlayıp açar (hekim pencereleri listeyi tazeler). */
    val duzenle: (EditTarget) -> Unit = { hedef ->
        when (hedef) {
            is EditTarget.konum -> duzenleme.konumHazirla(hedef.adim)
            is EditTarget.service -> duzenleme.editText = hedef.adim.tedaviAdi
            EditTarget.add -> { duzenleme.editText = ""; duzenleme.konumHazirla(null) }
            is EditTarget.hekim, EditTarget.planHekimi -> scope.launch { vm.hekimleriYukle() }
        }
        duzenleme.editError = null
        editing = hedef
    }

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        AuroraBackground(intensity = 0.12)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(
                title = baslik(phase),
                subtitle = altBaslik(vm),
                geriVar = geriVar,
                kapatmaSerbest = vm.kapatmaSerbest,
                onBack = { geri(vm) },
                onClose = kapat,
                modifier = Modifier.icerikGenisligi(icerikMax),
            )
            // Faz değişimleri Swift'teki gibi animasyonsuz.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (phase) {
                    Phase.pickPatient -> HastaSecici(vm)
                    is Phase.plans -> PlanlarEkrani(vm, phase.not)
                    Phase.recording -> KayitEkrani(vm, yanYana)
                    Phase.thinking -> DusunmeEkrani(vm)
                    Phase.review -> OnizlemeEkrani(vm, duzenle)
                    is Phase.choosing -> SecimEkrani(vm, phase.set, phase.soru) { menuKarti = phase.set }
                    is Phase.asking -> SoruEkrani(vm, phase.soru)
                    Phase.submitting -> DurumEkrani(
                        "Sisteme kaydediliyor…", vm.progressText,
                        note = "İşlem tamamlanınca sonucu bu ekranda görürsünüz.",
                    )
                    Phase.cancelling -> DurumEkrani("İşlem iptal ediliyor…", null, null)
                    is Phase.done -> BittiEkrani(vm, phase.metin, onClose = kapat)
                    is Phase.failed -> HataEkrani(
                        vm, phase.message,
                        onBuy = { reason -> creditSheet = CreditSheetItem(reason) },
                        onClose = kapat,
                    )
                }
            }
        }
    }

    DfySheet(isPresented = editing != null, onDismiss = { editing = null }, containerColor = DS.Colors.background) { dismiss ->
        editing?.let { hedef -> DuzenlemeSayfasi(hedef, vm, duzenleme, onClose = dismiss) }
    }
    DfySheet(isPresented = menuKarti != null, onDismiss = { menuKarti = null }, containerColor = DS.Colors.background) { dismiss ->
        menuKarti?.let { kart -> MenuSecimSheet(set = kart, onSelect = { vm.menudenSec(it, kart) }, onDismiss = dismiss) }
    }
    DfySheet(isPresented = creditSheet != null, onDismiss = { creditSheet = null }) { dismiss ->
        KrediEkrani(reason = creditSheet?.reason, onDismiss = dismiss)
    }
}

// MARK: - Faz yardımcıları

private fun showsBackArrow(phase: Phase): Boolean = when (phase) {
    is Phase.plans, Phase.review, is Phase.choosing, is Phase.asking, Phase.recording -> true
    else -> false
}

/** Geri ok: plan listesinden hasta seçimine, kayıttan plan listesine; önizleme/soru ekranından bekleyen yazmayı
 *  iptal ederek kayda (M-E1.2.7). */
private fun geri(vm: TreatmentPlanViewModel) {
    when (vm.phase) {
        is Phase.plans -> vm.hastaSecimineDon()
        Phase.recording -> vm.planlaraDon()
        else -> vm.backToRecording()
    }
}

private fun baslik(phase: Phase): String = if (phase == Phase.review) "Sonuç Onayı" else "Tedavi Planı"

private fun altBaslik(vm: TreatmentPlanViewModel): String = when (vm.phase) {
    Phase.pickPatient -> "Hasta seçin"
    is Phase.plans, Phase.recording, Phase.thinking, Phase.cancelling -> vm.selectedPatient?.name ?: ""
    Phase.review, Phase.submitting -> vm.selectedPatient?.name ?: "Gözden geçir"
    is Phase.choosing -> "Seçim gerekiyor"
    is Phase.asking -> "Asistan soruyor"
    is Phase.done -> "Tamamlandı"
    is Phase.failed -> "Hata"
}

// MARK: - Üst bar

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    geriVar: Boolean,
    kapatmaSerbest: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(start = if (geriVar) 6.dp else 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (geriVar) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .plainClickable(onClick = onBack)
                    .semantics { contentDescription = "Geri"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { DfyIcon("chevron.left", size = 17.dp, tint = DS.Colors.onSurface) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = sys(11, FontWeight.Medium), color = DS.Colors.cyan400, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Onay turunda kapatma pasif — kapatmak akışı keser ve sunucu turu düşerdi (M-E1.2.8).
        Box(
            Modifier
                .size(44.dp)
                .alpha(if (kapatmaSerbest) 1f else 0.35f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .plainClickable(enabled = kapatmaSerbest, onClick = onClose)
                .semantics { contentDescription = "Kapat"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { DfyIcon("xmark", size = 16.dp, tint = DS.Colors.onSurface) }
    }
}

// MARK: - Hasta seçici

private data class HastaBolumu(val baslik: String, val hastalar: List<TreatmentPlanViewModel.PatientRef>, val bos: String)

@Composable
private fun HastaSecici(vm: TreatmentPlanViewModel) {
    Column(Modifier.fillMaxSize()) {
        AramaCubugu(vm, Modifier.icerikGenisligi(ICERIK).padding(start = 16.dp, end = 16.dp, top = 8.dp))
        if (vm.loadingLists) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { ThinkingDots(dotSize = 9.dp) }
        } else {
            val aramada = vm.searchQuery.isNotBlank()
            val bolumler = remember(aramada, vm.searchResults, vm.isSearching, vm.todayPatients, vm.otherTodayPatients) {
                when {
                    aramada -> listOf(HastaBolumu("ARAMA SONUÇLARI", vm.searchResults, if (vm.isSearching) "Aranıyor…" else "Sonuç yok"))
                    vm.todayPatients.isEmpty() && vm.otherTodayPatients.isEmpty() ->
                        listOf(HastaBolumu("BUGÜNKÜ RANDEVULAR", emptyList(), "Bugün randevu yok"))
                    else -> listOfNotNull(
                        vm.todayPatients.takeIf { it.isNotEmpty() }?.let { HastaBolumu("BENİM HASTALARIM", it, "") },
                        vm.otherTodayPatients.takeIf { it.isNotEmpty() }?.let { HastaBolumu("DİĞER HEKİMLERİN HASTALARI", it, "") },
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            ) {
                bolumler.forEachIndexed { i, b ->
                    item(key = "baslik-${b.baslik}", contentType = "baslik") {
                        Kap(Modifier.padding(top = if (i == 0) 0.dp else 20.dp)) { BolumEtiketi(b.baslik) }
                    }
                    if (b.hastalar.isEmpty()) {
                        item(key = "bos-${b.baslik}", contentType = "bos") {
                            Kap(Modifier.padding(top = 8.dp)) {
                                Text(b.bos, Modifier.padding(vertical = 8.dp), style = sys(14), color = DS.Colors.outline)
                            }
                        }
                    } else {
                        items(b.hastalar, key = { "${b.baslik}-${it.id}" }, contentType = { "hasta" }) { p ->
                            Kap(Modifier.padding(top = 8.dp), yatay = 16.dp) { HastaSatiri(p) { vm.selectPatient(p) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AramaCubugu(vm: TreatmentPlanViewModel, modifier: Modifier) {
    val focus = LocalFocusManager.current
    var odak by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (odak) DS.Colors.cyan400.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("magnifyingglass", size = 15.dp, tint = DS.Colors.onSurfaceVariant)
        BasicTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it; vm.searchChanged() },
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).onFocusChanged { odak = it.isFocused },
            textStyle = sys(16).copy(color = DS.Colors.onSurface),
            cursorBrush = SolidColor(DS.Colors.cyan400),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            // Arama yazdıkça sunucuda yürür; klavyenin "Ara" tuşu yalnız klavyeyi kapatır (sonuçlar görünsün).
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (vm.searchQuery.isEmpty()) {
                        Text("Hasta ara (ad, soyad)…", style = sys(16), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        if (vm.searchQuery.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .plainClickable { vm.searchQuery = ""; vm.searchChanged() }
                    .semantics { contentDescription = "Aramayı temizle"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { DfyIcon("xmark.circle.fill", size = 17.dp, tint = DS.Colors.outline) }
        }
    }
}

@Composable
private fun HastaSatiri(p: TreatmentPlanViewModel.PatientRef, onClick: () -> Unit) {
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
            Text(initials(p.name), style = sys(15, FontWeight.Bold), color = DS.Colors.cyan400, maxLines = 1)
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

// MARK: - Plan kartları (M-E1.2.2)

@Composable
private fun PlanlarEkrani(vm: TreatmentPlanViewModel, not: String?) {
    val scope = rememberCoroutineScope()
    var yenileniyor by remember { mutableStateOf(false) }
    val yenileme = rememberPullToRefreshState()
    val liste = vm.planListesi
    PullToRefreshBox(
        isRefreshing = yenileniyor,
        onRefresh = {
            yenileniyor = true
            scope.launch {
                vm.planlariYukle()
                yenileniyor = false
            }
        },
        modifier = Modifier.fillMaxSize(),
        state = yenileme,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = yenileme, isRefreshing = yenileniyor, modifier = Modifier.align(Alignment.TopCenter),
                containerColor = DS.Colors.surfaceContainerHigh, color = DS.Colors.cyan400,
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)) {
            if (not != null) {
                item(key = "not", contentType = "not") {
                    Kap {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(DS.Colors.orange400.copy(alpha = 0.10f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            DfyIcon("info.circle.fill", size = 13.dp, tint = DS.Colors.orange400, modifier = Modifier.padding(top = 2.dp))
                            Text(not, Modifier.weight(1f), style = sys(13, FontWeight.Medium), color = DS.Colors.orange400)
                        }
                    }
                }
            }
            item(key = "yeni", contentType = "yeni") {
                Kap(Modifier.padding(top = if (not != null) 14.dp else 0.dp)) {
                    PrimaryButton(onClick = { vm.yeniPlan() }) {
                        DfyIcon("plus", size = 15.dp, tint = Color.White)
                        Spacer(Modifier.width(7.dp))
                        ButtonLabel("Yeni Plan Oluştur")
                    }
                }
            }
            item(key = "baslik", contentType = "baslik") {
                Kap(Modifier.padding(top = 20.dp)) { BolumEtiketi("TEDAVİ PLANLARI") }
            }
            val hata = vm.planHatasi
            when {
                vm.planlarYukleniyor && liste == null -> item(key = "yukleniyor", contentType = "durum") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { ThinkingDots(dotSize = 8.dp) }
                }
                hata != null -> item(key = "hata", contentType = "durum") {
                    Kap(Modifier.padding(top = 14.dp)) {
                        Column(
                            Modifier.fillMaxWidth().glassCard(14.dp).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(hata, style = sys(14), color = DS.Colors.onSurface)
                            Text(
                                "Tekrar dene",
                                Modifier
                                    .plainClickable { scope.launch { vm.planlariYukle() } }
                                    .semantics { role = Role.Button },
                                style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400,
                            )
                        }
                    }
                }
                liste != null -> {
                    if (liste.plans.isEmpty()) {
                        item(key = "bos", contentType = "durum") {
                            Kap(Modifier.padding(top = 14.dp)) {
                                Text("Bu hastanın henüz tedavi planı yok.", Modifier.padding(vertical = 8.dp), style = sys(14), color = DS.Colors.outline)
                            }
                        }
                    } else {
                        items(liste.siraliPlanlar, key = { "plan-${it.id}" }, contentType = { "plan" }) { plan ->
                            Kap(Modifier.padding(top = 14.dp)) { PlanKartiGorunumu(plan) { vm.plandaDevam(plan) } }
                        }
                        liste.kirpmaMetni?.let { kirpma ->
                            item(key = "kirpma", contentType = "durum") {
                                Kap(Modifier.padding(top = 14.dp)) { Text(kirpma, style = sys(12), color = DS.Colors.outline) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanKartiGorunumu(plan: PlanKarti, onClick: () -> Unit) {
    val dokunulabilir = plan.dokunulabilir
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (dokunulabilir) 1f else 0.5f)
            .pressable(enabled = dokunulabilir, onClick = onClick)
            .glassCard(16.dp)
            .padding(14.dp)
            .semantics(mergeDescendants = true) { if (dokunulabilir) role = Role.Button },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                plan.baslik, Modifier.weight(1f),
                style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (plan.sistemPlani != true) {
                Spacer(Modifier.width(6.dp))
                TagChip(plan.durumEtiketi, tint = if (dokunulabilir) DS.Colors.cyan400 else DS.Colors.outline)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(plan.hekimMetni, Modifier.weight(1f, fill = false), style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            plan.kalemSayisi?.let { n ->
                Text("·", style = sys(12), color = DS.Colors.onSurfaceVariant)
                Text("$n kalem", style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1)
            }
        }
        plan.ilkKalemSatirlari.forEach { satir ->
            Text(satir, style = sys(13), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (plan.sistemPlani == true) {
            Text("Plansız eklenen tedaviler · buraya plan olarak ekleme yapılmaz.", style = sys(11), color = DS.Colors.outline)
        } else if (!dokunulabilir) {
            Text("Bu plana yeni tedavi eklenemez.", style = sys(11), color = DS.Colors.outline)
        }
    }
}

// MARK: - Kayıt — yazarak ya da dikteyle (M-E1.2.6)

@Composable
private fun KayitEkrani(vm: TreatmentPlanViewModel, yanYana: Boolean) {
    val focus = LocalFocusManager.current
    val gonder: () -> Unit = { focus.clearFocus(); vm.finishAndSend() }
    if (yanYana) {
        // Geniş yatay pencere: orb paneli solda, yazma alanı ve düğmeler sağda (klavye açıkken de görünür).
        Row(
            Modifier.fillMaxSize().icerikGenisligi(GENIS_ICERIK).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            DoldurVeyaKaydir(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                KayitOrbu(vm, 140.dp)
                DikteDurumu(vm, Modifier.padding(top = 14.dp))
            }
            DoldurVeyaKaydir(Modifier.weight(1.15f).fillMaxHeight(), horizontalAlignment = Alignment.Start) {
                KayitBasligi(vm.kayitBasligi, Modifier.padding(top = 6.dp))
                Spacer(Modifier.weight(1f).heightIn(min = 14.dp))
                KayitAlani(vm)
                KayitDugmeleri(vm, gonder, Modifier.padding(top = 14.dp, bottom = 24.dp))
            }
        }
    } else {
        DoldurVeyaKaydir(Modifier.fillMaxSize().icerikGenisligi(ICERIK)) {
            KayitBasligi(vm.kayitBasligi, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            Spacer(Modifier.weight(1f))
            KayitOrbu(vm, 140.dp)
            DikteDurumu(vm, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
            Spacer(Modifier.weight(1f).heightIn(min = 14.dp))
            KayitAlani(vm, Modifier.padding(horizontal = 20.dp))
            KayitDugmeleri(vm, gonder, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun KayitBasligi(metin: String, modifier: Modifier = Modifier) {
    Text(
        metin, modifier.fillMaxWidth(),
        style = sys(13, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** Orb — ses seviyesi saniyede onlarca kez değiştiği için okuma yalnız bu küçük kapsamda yapılır. */
@Composable
private fun KayitOrbu(vm: TreatmentPlanViewModel, size: Dp) {
    val dinliyor = vm.isListening
    val seviye = if (dinliyor) vm.speech.audioLevel.toFloat() else 0f
    AIOrb(
        size = size,
        isAnimating = dinliyor,
        audioLevel = seviye,
        // Kırpılmaz: seviye halkası konuşurken orbun dışına genişler.
        modifier = Modifier
            .plainClickable { vm.toggleDictation() }
            .semantics { contentDescription = if (dinliyor) "Dikteyi durdur" else "Dikte et"; role = Role.Button },
    )
}

@Composable
private fun DikteDurumu(vm: TreatmentPlanViewModel, modifier: Modifier = Modifier) {
    val dinliyor = vm.isListening
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(0.9f).clip(CircleShape).background(if (dinliyor) DS.Colors.error else DS.Colors.outline))
        Text(
            if (dinliyor) "Dinliyorum — tedavileri söyleyin" else "Mikrofona basıp söyleyin ya da yazın",
            Modifier.weight(1f, fill = false),
            style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

private const val KAYIT_IPUCU =
    "Tedavileri yazın ya da mikrofona basıp söyleyin. Örn: “26 kanal tedavisi, 34 implant, 11 ve 21 zirkonyum kaplama”"

@Composable
private fun KayitAlani(vm: TreatmentPlanViewModel, modifier: Modifier = Modifier) {
    var odak by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        value = vm.kayitMetni,
        onValueChange = { yeni ->
            // Yazılan metin dikteyle ezilmesin: kullanıcı yazınca dikte durur.
            if (vm.isListening) vm.kullaniciYaziyor()
            vm.kayitMetni = yeni
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { odak = it.isFocused; if (it.isFocused) vm.kullaniciYaziyor() }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (odak) DS.Colors.cyan400.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f), shape)
            .padding(14.dp),
        textStyle = sys(17).copy(color = DS.Colors.onSurface),
        cursorBrush = SolidColor(DS.Colors.cyan400),
        minLines = 3,
        maxLines = 7,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box {
                if (vm.kayitMetni.isEmpty()) {
                    Text(KAYIT_IPUCU, style = sys(17), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f))
                }
                inner()
            }
        },
    )
}

@Composable
private fun KayitDugmeleri(vm: TreatmentPlanViewModel, gonder: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlineCapsule("Sıfırla", "arrow.counterclockwise", DS.Colors.error, DS.Colors.error.copy(alpha = 0.4f), height = 52.dp) {
            vm.restartRecording()
        }
        GradientCapsule(
            "Çözümle", "waveform.and.magnifyingglass", height = 52.dp,
            modifier = Modifier.weight(1f), enabled = vm.gonderilebilir, onClick = gonder,
        )
        KrediIpucu(islem = "ai_agent:chat")
    }
}

// MARK: - Düşünme (asistan çalışıyor) — ilerleme rayı

@Composable
private fun DusunmeEkrani(vm: TreatmentPlanViewModel) {
    DoldurVeyaKaydir(Modifier.fillMaxSize().icerikGenisligi(ICERIK), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            AIOrb(size = 150.dp, isAnimating = true, audioLevel = 0f)
            ThinkingDots(dotSize = 8.dp)
        }
        IlerlemeRayi(vm.progressStepIndex)
        AnimatedContent(vm.progressText, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "ilerleme") { t ->
            Text(
                t, Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface, textAlign = TextAlign.Center,
            )
        }
        GecenSure(vm)
        Spacer(Modifier.weight(1f))
        OutlineCapsule(
            "İptal et", "xmark", DS.Colors.error, DS.Colors.error.copy(alpha = 0.4f), height = 50.dp, hPadding = 22.dp,
            modifier = Modifier.padding(bottom = 24.dp),
        ) { vm.cancelThinking() }
    }
}

/** Geçen süre her saniye değişir — okuma bu küçük kapsamda kalır. */
@Composable
private fun GecenSure(vm: TreatmentPlanViewModel) {
    val sn = vm.elapsedSeconds
    if (sn >= 8) {
        Text("$sn sn · Bu tür işlemler ~30 sn sürebilir", style = sys(12).monoDigits(), color = DS.Colors.outline, textAlign = TextAlign.Center)
    }
}

/** Dört adımlı ilerleme rayı (anlama → getirme → hesaplama → yanıtlama). Ray ASLA geri gitmez — kural `ProgressMapper`ın içindedir. */
@Composable
private fun IlerlemeRayi(index: Int) {
    Row(
        Modifier.semantics { contentDescription = "İlerleme adımı ${index + 1} / 4" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(4) { i ->
            val dolu by animateFloatAsState(if (i <= index) 1f else 0f, Motion.easeOut<Float>(250), label = "ray$i")
            Box(
                Modifier
                    .size(width = 34.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .drawBehind { drawRect(DS.Gradients.primaryGradient, alpha = dolu) }
            )
        }
    }
}

// MARK: - Seçim kartı (M-E1.4.1) — sunucunun sorusu · kartlar · "Listede yok" · cevap alanı

@Composable
private fun SecimEkrani(vm: TreatmentPlanViewModel, set: AgentChoiceSet, soru: String, onListedeYok: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(Modifier.icerikGenisligi(ICERIK).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                SecimKarti(set = set, soru = soru, onSelect = { vm.chooseOption(it) }, onListedeYok = onListedeYok)
            }
        }
        CevapAlani(vm)
    }
}

// MARK: - Asistanın SORUSU — turuncu "Hata" DEĞİL

@Composable
private fun SoruEkrani(vm: TreatmentPlanViewModel, soru: String) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.icerikGenisligi(ICERIK).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon("bubble.left.and.text.bubble.right", size = 18.dp, tint = DS.Colors.cyan400)
                    Text("Dentifay Asistan soruyor", style = sys(13, FontWeight.Bold, tracking = 0.6), color = DS.Colors.cyan400)
                }
                Text(
                    soru,
                    Modifier.fillMaxWidth().glassCard(18.dp).padding(16.dp),
                    style = sys(17).satirArasi(4f), color = DS.Colors.onSurface,
                )
            }
        }
        CevapAlani(vm)
    }
}

/** Soru ve seçim ekranlarının ORTAK cevap alanı — yazılan/dikte edilen cevap AYNEN gider. */
@Composable
private fun CevapAlani(vm: TreatmentPlanViewModel) {
    val focus = LocalFocusManager.current
    val dinliyor = vm.isListening
    AltCubuk {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, if (dinliyor) DS.Colors.error.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pressable { vm.toggleAnswerDictation() }
                        .semantics { contentDescription = if (dinliyor) "Dikteyi durdur" else "Dikteyle cevapla"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    DfyIcon(if (dinliyor) "mic.fill" else "mic", size = 18.dp, tint = if (dinliyor) DS.Colors.error else DS.Colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = vm.answerDraft,
                    onValueChange = { yeni ->
                        // Yazılan cevap dikteyle ezilmesin.
                        if (vm.isListening) vm.toggleAnswerDictation()
                        vm.answerDraft = yeni
                    },
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textStyle = sys(16).copy(color = DS.Colors.onSurface),
                    cursorBrush = SolidColor(DS.Colors.cyan400),
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.submitAnswer() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (vm.answerDraft.isEmpty()) {
                                Text("Cevabınızı yazın ya da mikrofona basın…", style = sys(16), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            inner()
                        }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Sunucunun sabit cümlesiyle biten tur (ör. kapanmış plan) soru gibi görünür — çıkış yolu daima var.
                OutlineCapsule("Planlara dön", null, DS.Colors.onSurface, Color.White.copy(alpha = 0.18f), height = 52.dp, hPadding = 16.dp) {
                    focus.clearFocus(); vm.planlaraDon()
                }
                GradientCapsule("Gönder", "arrow.up", height = 52.dp, modifier = Modifier.weight(1f), enabled = vm.cevapGonderilebilir) {
                    focus.clearFocus(); vm.submitAnswer()
                }
            }
        }
    }
}

// MARK: - Sonuç Onayı (M-E1.3 · yapısal önizleme)

@Composable
private fun OnizlemeEkrani(vm: TreatmentPlanViewModel, duzenle: (EditTarget) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val kalemler = vm.items
        val toplam = vm.toplamTL
        val gizli = vm.hiddenStepCount
        val gonderilen = vm.spokenTranscript
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            item(key = "baslik", contentType = "baslik") { Kap { OnizlemeBasligi(vm, duzenle) } }
            item(key = "ozet", contentType = "ozet") { Kap(Modifier.padding(top = 18.dp)) { OzetKarti(vm) } }
            item(key = "kalemler", contentType = "kalemler") {
                Kap(Modifier.padding(top = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BolumEtiketi("TEDAVİ KALEMLERİ", Modifier.weight(1f))
                        Text("${kalemler.size} kalem", style = sys(11, FontWeight.SemiBold), color = DS.Colors.cyan400)
                    }
                }
            }
            if (kalemler.isEmpty()) {
                // Onay kapısı ne onayladığını GÖSTERMEK zorundadır (Ek MO.7h).
                item(key = "kalem-yok", contentType = "durum") {
                    Kap(Modifier.padding(top = 10.dp)) {
                        Text(
                            "Kalem listesi gelmedi; ayrıntı için özeti okuyun.",
                            Modifier.fillMaxWidth().glassCard(14.dp).padding(14.dp),
                            style = sys(13), color = DS.Colors.orange400,
                        )
                    }
                }
            } else {
                items(kalemler, key = { "kalem-${it.id}" }, contentType = { "kalem" }) { kalem ->
                    Kap(Modifier.padding(top = 10.dp)) {
                        KalemKarti(kalem, duzenle, onSil = { vm.removeItem(kalem) })
                    }
                }
            }
            // 🔴 SESSİZ KIRPMA YASAK (Ek MO.7g).
            if (gizli > 0) {
                item(key = "gizli", contentType = "durum") {
                    Kap(Modifier.padding(top = 12.dp)) {
                        Text(
                            "$gizli kayıt bu listede gösterilmedi; onaylamadan önce özeti okuyun.",
                            style = sys(12, FontWeight.Medium), color = DS.Colors.orange400,
                        )
                    }
                }
            }
            item(key = "kalem-ekle", contentType = "kalem-ekle") {
                Kap(Modifier.padding(top = 10.dp)) { KalemEkleDugmesi { duzenle(EditTarget.add) } }
            }
            if (toplam != null) {
                item(key = "toplam", contentType = "toplam") { Kap(Modifier.padding(top = 18.dp)) { ToplamKarti(toplam) } }
            }
            if (gonderilen.isNotEmpty()) {
                item(key = "metin", contentType = "metin") { Kap(Modifier.padding(top = 18.dp)) { GonderilenMetinKarti(gonderilen) } }
            }
        }
        OnizlemeDugmeleri(vm)
    }
}

/** Başlık Ek MO.7a · hasta · plan adı + planın hekimi (yeni plan) ya da hedef planın adı + hekimi · Klinik (Ek MO.7f). */
@Composable
private fun OnizlemeBasligi(vm: TreatmentPlanViewModel, duzenle: (EditTarget) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Önizleme hazır", style = sys(20, FontWeight.Bold), color = DS.Colors.onSurface)
        vm.selectedPatient?.name?.let { Text(it, style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400) }
        val hedef = vm.hedefPlan
        val plan = vm.planStep
        if (hedef != null) {
            Text("${hedef.baslik} · ${hedef.hekimMetni}", style = sys(13), color = DS.Colors.onSurfaceVariant)
        } else if (plan != null) {
            if (plan.title.isNotEmpty()) {
                Text(plan.title, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
            }
            val hekim = plan.planHekimiMetni ?: "Hekim atanmadı"
            Row(
                Modifier
                    .plainClickable { duzenle(EditTarget.planHekimi) }
                    .semantics(mergeDescendants = true) { contentDescription = "Planın hekimi: $hekim. Değiştir"; role = Role.Button }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Planın hekimi: $hekim", Modifier.weight(1f, fill = false),
                    style = sys(13), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                DfyIcon("chevron.right", size = 10.dp, tint = DS.Colors.onSurfaceVariant)
            }
        }
        vm.writePreview?.let { pv ->
            val bilgi = remember(pv) { OnayBilgisi.kur(pv) }
            Text(bilgi.baslik, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
            bilgi.satirlar.forEach { Text(it, style = sys(12), color = DS.Colors.onSurface) }
            bilgi.kalanSatiri?.let { Text(it, style = sys(12), color = DS.Colors.outline) }
            bilgi.klinik?.let { Text(it, style = sys(12), color = DS.Colors.outline) }
        }
    }
}

@Composable
private fun OzetKarti(vm: TreatmentPlanViewModel) {
    Column(Modifier.fillMaxWidth().glassCard(18.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BolumEtiketi("ÖZET", Modifier.weight(1f))
            val ozetVar = vm.writePreview?.ozet != null
            Row(
                Modifier
                    .alpha(if (ozetVar) 1f else 0.4f)
                    .pressable(enabled = ozetVar) { vm.speakSummary() }
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Özeti sesli dinle"; role = Role.Button },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("speaker.wave.2", size = 12.dp, tint = DS.Colors.cyan400)
                Text("Dinle", style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1)
            }
        }
        Text(
            vm.planSummary.ifEmpty { TreatmentPlanViewModel.ozetYedegi },
            style = sys(15).satirArasi(3f), color = DS.Colors.onSurface,
        )
    }
}

/** Kalem kartı: konum (sunucunun etiketi; yoksa Ek MO.7d) · tedavi · adet · hekim (dokununca değişir) · fiyat etiketi + TL tutarı.
 *  Dar pencerede ad kısaltılır; tutar KISALTILMAZ (gerekirse iki satıra iner). */
@Composable
private fun KalemKarti(item: AgentWriteStep, duzenle: (EditTarget) -> Unit, onSil: () -> Unit) {
    val tutar = remember(item) { tutarSatiri(item) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .glassCard(16.dp)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        val tutarTavani = maxWidth * 0.42f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val konum = item.konumMetni
                Text(
                    konum ?: "Konum yok",
                    style = sys(12, FontWeight.Bold), color = if (konum == null) DS.Colors.outline else DS.Colors.cyan400,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(item.tedaviAdi, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.quantity > 1) {
                    Text("${item.quantity} adet", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
                val hekim = item.hekimMetni ?: "Hekim atanmadı"
                Row(
                    Modifier
                        .plainClickable { duzenle(EditTarget.hekim(item)) }
                        .semantics(mergeDescendants = true) { contentDescription = "Kalemin hekimi: $hekim. Değiştir"; role = Role.Button }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DfyIcon("person", size = 10.dp, tint = DS.Colors.onSurfaceVariant)
                    Text(hekim, Modifier.weight(1f, fill = false), style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DfyIcon("chevron.right", size = 9.dp, tint = DS.Colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.widthIn(max = tutarTavani), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item.fiyatEtiketi?.let { Text(it, style = sys(14, FontWeight.Bold), color = DS.Colors.onSurface, textAlign = TextAlign.End) }
                tutar?.let { Text(it, style = sys(11).monoDigits(), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.End) }
            }
            KalemMenusu(item, duzenle, onSil)
        }
    }
}

/** Çok dişli kalem: tutar `GrupTutari` kuralıyla — eksikse "—", ₺0 ASLA (M-E5.1). Satır TL tutarı sunucudan (kur yoksa
 *  satır yok); fiyat zaten TL ise ikinci kez yazılmaz. */
private fun tutarSatiri(item: AgentWriteStep): String? {
    if (item.toothCodes.size > 1) return GrupTutari.metin(item.tutarTL)
    val tl = item.tutarTL ?: return null
    val fiyatTL = item.fiyatEtiketi?.endsWith("TL") ?: false
    return if (!fiyatTL || item.quantity > 1) TL.bicim(tl) else null
}

@Composable
private fun KalemMenusu(item: AgentWriteStep, duzenle: (EditTarget) -> Unit, onSil: () -> Unit) {
    var acik by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(width = 36.dp, height = 44.dp)
                .plainClickable { acik = true }
                .semantics { contentDescription = "Kalem işlemleri"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { DfyIcon("ellipsis", size = 16.dp, tint = DS.Colors.onSurfaceVariant) }
        DropdownMenu(
            expanded = acik,
            onDismissRequest = { acik = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            MenuSatiri("Konumu değiştir", "DisIkon") { acik = false; duzenle(EditTarget.konum(item)) }
            MenuSatiri("Tedaviyi değiştir", "pencil.line") { acik = false; duzenle(EditTarget.service(item)) }
            MenuSatiri("Hekimi değiştir", "person") { acik = false; duzenle(EditTarget.hekim(item)) }
            MenuSatiri("Kalemi sil", "trash", DS.Colors.danger) { acik = false; onSil() }
        }
    }
}

@Composable
private fun MenuSatiri(metin: String, ikon: String, renk: Color = DS.Colors.onSurface, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(metin, style = sys(15), color = renk) },
        leadingIcon = { DfyIcon(ikon, size = 17.dp, tint = renk) },
        onClick = onClick,
    )
}

@Composable
private fun KalemEkleDugmesi(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .heightIn(min = 44.dp)
            .drawBehind {
                val r = 14.dp.toPx()
                drawRoundRect(
                    color = DS.Colors.cyan400.copy(alpha = 0.3f),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("plus", size = 14.dp, tint = DS.Colors.cyan400)
        Text("Kalem ekle", style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400)
    }
}

@Composable
private fun ToplamKarti(toplam: Double) {
    val metin = remember(toplam) { TL.bicim(toplam) }
    Row(
        Modifier.fillMaxWidth().glassCard(16.dp).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Toplam", Modifier.weight(1f), style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        Text(metin, style = sys(20, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface, textAlign = TextAlign.End)
    }
}

@Composable
private fun GonderilenMetinKarti(metin: String) {
    Column(Modifier.fillMaxWidth().glassCard(16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BolumEtiketi("GÖNDERİLEN METİN")
        Text(metin, style = sys(14).copy(fontStyle = FontStyle.Italic).satirArasi(3f), color = DS.Colors.onSurfaceVariant)
    }
}

@Composable
private fun OnizlemeDugmeleri(vm: TreatmentPlanViewModel) {
    AltCubuk {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineCapsule(
                "Tekrar Kaydet", "arrow.counterclockwise", DS.Colors.onSurface, Color.White.copy(alpha = 0.18f),
                height = 54.dp, hPadding = 16.dp,
            ) { vm.retryDictation() }
            // Plan yazımı `chart.plan.edit` ister — yetkisizse düğme sönük, dokununca uyarı (M-E7.4).
            val suresiDoldu = vm.onizlemeSuresiDoldu()
            YetkiliButon(
                yetenekler = listOf(Yetki.Yuzey.tedaviPlani.yetenek),
                eylem = { vm.confirm() },
                modifier = Modifier.weight(1f),
            ) { mod ->
                GradientCapsuleIcerik(
                    if (suresiDoldu) OnayIstegi.suresiDolduDugmesi else "Onayla", "checkmark", height = 54.dp, modifier = mod,
                )
            }
        }
    }
}

// MARK: - Düzeltme sayfaları (`…` menüsü · Ek MO.8)

private sealed class EditTarget {
    data class konum(val adim: AgentWriteStep) : EditTarget()
    data class service(val adim: AgentWriteStep) : EditTarget()
    data class hekim(val adim: AgentWriteStep) : EditTarget()
    data object planHekimi : EditTarget()
    data object add : EditTarget()
}

private enum class KonumTuru(val rawValue: String) {
    dis("Diş"), ust("Üst çene"), alt("Alt çene"), bolge("Yarım çene"), yok("Konumsuz")
}

/** Düzeltme pencerelerinin alan durumu (Swift'teki `@State` alanları). */
@Stable
private class DuzenlemeDurumu {
    var editText by mutableStateOf("")
    var editError by mutableStateOf<String?>(null)
    // Konum seçici durumu (Ek MO.8)
    var konumTuru by mutableStateOf(KonumTuru.dis)
    var konumDisler by mutableStateOf("")
    var konumBolge by mutableIntStateOf(1)

    fun konumHazirla(adim: AgentWriteStep?) {
        konumTuru = if (adim == null) KonumTuru.yok else KonumTuru.dis
        konumDisler = adim?.let { if (it.toothCodes.isEmpty()) it.tooth ?: "" else it.toothCodes.joinToString(", ") } ?: ""
        konumBolge = 1
    }

    val konumSecimi: KonumSecimi
        get() = when (konumTuru) {
            KonumTuru.dis -> KonumSecimi.dis(FDI.ayir(konumDisler))
            KonumTuru.ust -> KonumSecimi.ustCene
            KonumTuru.alt -> KonumSecimi.altCene
            KonumTuru.bolge -> KonumSecimi.bolge(konumBolge)
            KonumTuru.yok -> KonumSecimi.konumsuz
        }
}

private fun editTitle(hedef: EditTarget): String = when (hedef) {
    is EditTarget.konum -> "Konumu değiştir"
    is EditTarget.service -> "Tedaviyi değiştir"
    is EditTarget.hekim, EditTarget.planHekimi -> "Hekimi değiştir"
    EditTarget.add -> "Kalem ekle"
}

@Composable
private fun ColumnScope.DuzenlemeSayfasi(
    hedef: EditTarget,
    vm: TreatmentPlanViewModel,
    durum: DuzenlemeDurumu,
    onClose: () -> Unit,
) {
    // Satır içi başlık + sağda "Kapat" (Swift `NavigationStack` · `.inline`).
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 44.dp)) {
        Text(
            editTitle(hedef), Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
            style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            "Kapat",
            Modifier
                .align(Alignment.CenterEnd)
                .plainClickable(onClick = onClose)
                .padding(vertical = 10.dp)
                .semantics { role = Role.Button },
            style = sys(17), color = DS.Colors.cyan400,
        )
    }
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (hedef) {
            is EditTarget.konum -> {
                AltBaslik(hedef.adim.tedaviAdi)
                KonumSecici(durum)
            }
            is EditTarget.service -> {
                AltBaslik("Yeni tedavi adı")
                DuzenlemeAlani(durum.editText, { durum.editText = it }, "Ör. Zirkonyum Kaplama")
                Text("Tedavi menüsü eşlemesini sistem yapar; adını yazmanız yeterli.", style = sys(12), color = DS.Colors.outline)
            }
            is EditTarget.hekim -> {
                AltBaslik("Hekim seçin")
                HekimListesi(vm) { h -> vm.kalemHekiminiDegistir(hedef.adim, h); onClose() }
            }
            EditTarget.planHekimi -> {
                AltBaslik("Hekim seçin")
                HekimListesi(vm) { h -> vm.planHekiminiDegistir(h); onClose() }
            }
            EditTarget.add -> {
                AltBaslik("Yeni kalem")
                DuzenlemeAlani(durum.editText, { durum.editText = it }, "Tedavi adı")
                KonumSecici(durum)
            }
        }

        durum.editError?.let { Text(it, style = sys(13), color = DS.Colors.error) }

        if (hedef !is EditTarget.hekim && hedef != EditTarget.planHekimi) {
            GradientCapsule("Uygula", null, height = 52.dp, modifier = Modifier.fillMaxWidth()) {
                val hata = when (hedef) {
                    is EditTarget.konum -> vm.konumuDegistir(hedef.adim, durum.konumSecimi)
                    is EditTarget.service -> vm.changeService(hedef.adim, durum.editText)
                    EditTarget.add -> vm.addItem(durum.editText, if (durum.konumTuru == KonumTuru.yok) null else durum.konumSecimi)
                    else -> null
                }
                if (hata != null) durum.editError = hata else onClose()
            }
        }
    }
}

@Composable
private fun AltBaslik(metin: String) {
    Text(metin, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
}

@Composable
private fun DuzenlemeAlani(
    deger: String,
    degisti: (String) -> Unit,
    ipucu: String,
    stil: TextStyle = sys(17),
    klavye: KeyboardType = KeyboardType.Text,
) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = deger,
        onValueChange = degisti,
        modifier = Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.06f)).padding(14.dp),
        textStyle = stil.copy(color = DS.Colors.onSurface),
        cursorBrush = SolidColor(DS.Colors.cyan400),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = if (klavye == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = klavye,
        ),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (deger.isEmpty()) {
                    Text(ipucu, style = stil, color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}

@Composable
private fun KonumSecici(durum: DuzenlemeDurumu) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        KonumTuruMenusu(durum)
        when (durum.konumTuru) {
            KonumTuru.dis -> {
                DuzenlemeAlani(
                    durum.konumDisler, { durum.konumDisler = it }, "Ör. 36 ya da 11, 21",
                    stil = sys(18, FontWeight.SemiBold), klavye = KeyboardType.Number,
                )
                Text("Kalıcı: 11–18, 21–28, 31–38, 41–48 · süt: 51–55, 61–65, 71–75, 81–85", style = sys(12), color = DS.Colors.outline)
            }
            KonumTuru.bolge -> YarimCeneSecici(durum.konumBolge) { durum.konumBolge = it }
            else -> Unit
        }
    }
}

/** `Picker(.menu)` karşılığı: seçili tür + açılır liste. */
@Composable
private fun KonumTuruMenusu(durum: DuzenlemeDurumu) {
    var acik by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .plainClickable { acik = true }
                .padding(vertical = 6.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Konum: ${durum.konumTuru.rawValue}"; role = Role.Button },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(durum.konumTuru.rawValue, style = sys(16, FontWeight.Medium), color = DS.Colors.cyan400)
            DfyIcon("chevron.down", size = 12.dp, tint = DS.Colors.cyan400)
        }
        DropdownMenu(
            expanded = acik,
            onDismissRequest = { acik = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            KonumTuru.entries.forEach { tur ->
                DropdownMenuItem(
                    text = { Text(tur.rawValue, style = sys(16), color = DS.Colors.onSurface) },
                    onClick = { durum.konumTuru = tur; acik = false },
                    trailingIcon = if (tur == durum.konumTuru) ({ DfyIcon("checkmark", size = 15.dp, tint = DS.Colors.onSurface) }) else null,
                )
            }
        }
    }
}

/** `Picker(.segmented)` karşılığı (yarım çene 1–4): numara üstte, ad altta — dar pencerede de taşmaz. */
@Composable
private fun YarimCeneSecici(secili: Int, onSec: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.08f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        (1..4).forEach { n ->
            val on = n == secili
            val renk = if (on) Color.White else DS.Colors.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) SEGMENT_RENGI else Color.Transparent)
                    .plainClickable { onSec(n) }
                    .semantics(mergeDescendants = true) { selected = on; role = Role.Tab }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("$n", style = sys(13, FontWeight.SemiBold), color = renk, maxLines = 1)
                Text(KonumSecimi.bolgeAdi(n), style = sys(11, if (on) FontWeight.SemiBold else FontWeight.Medium), color = renk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val SEGMENT_RENGI = Color(0xFF636366).copy(alpha = 0.55f)

@Composable
private fun HekimListesi(vm: TreatmentPlanViewModel, sec: (APIDoctorMember) -> Unit) {
    val scope = rememberCoroutineScope()
    val hata = vm.hekimHatasi
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            vm.hekimlerYukleniyor && vm.hekimler.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { ThinkingDots(dotSize = 7.dp) }
            hata != null -> {
                Text(hata, style = sys(14), color = DS.Colors.error)
                Text(
                    "Tekrar dene",
                    Modifier.plainClickable { scope.launch { vm.hekimleriYukle() } }.semantics { role = Role.Button },
                    style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400,
                )
            }
            else -> vm.hekimler.forEach { h ->
                Text(
                    Unvan.hekimAdi(h.user.name),
                    Modifier
                        .fillMaxWidth()
                        .pressable { sec(h) }
                        .glassCard(14.dp)
                        .padding(14.dp)
                        .semantics { role = Role.Button },
                    style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface,
                )
            }
        }
    }
}

// MARK: - Durum ekranları

@Composable
private fun DurumEkrani(title: String, detail: String?, note: String?) {
    DoldurVeyaKaydir(Modifier.fillMaxSize().icerikGenisligi(ICERIK), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        ThinkingDots(dotSize = 10.dp)
        Text(title, Modifier.padding(horizontal = 20.dp), style = sys(18, FontWeight.SemiBold), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
        if (!detail.isNullOrEmpty()) {
            Text(detail, Modifier.padding(horizontal = 40.dp), style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        if (note != null) {
            Text(
                note, Modifier.padding(start = 32.dp, end = 32.dp, bottom = 24.dp),
                style = sys(12), color = DS.Colors.outline, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BittiEkrani(vm: TreatmentPlanViewModel, metin: String, onClose: () -> Unit) {
    val kisa = LocalPencere.current.kisa
    DoldurVeyaKaydir(Modifier.fillMaxSize().icerikGenisligi(ICERIK), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.weight(1f))
        DfyIcon("checkmark.seal.fill", size = 56.dp, tint = DS.Colors.tertiaryContainer)
        if (metin.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(max = if (kisa) 160.dp else 260.dp).verticalScroll(rememberScrollState())) {
                MarkdownMessageView(text = metin, modifier = Modifier.padding(horizontal = 28.dp), textColor = DS.Colors.onSurface)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineCapsule(
                "Kapat", null, DS.Colors.onSurface, Color.White.copy(alpha = 0.18f), height = 54.dp,
                modifier = Modifier.weight(1f), fill = true, onClick = onClose,
            )
            GradientCapsule("Planlara dön", null, height = 54.dp, modifier = Modifier.weight(1f)) { vm.planlaraDon() }
        }
    }
}

@Composable
private fun HataEkrani(vm: TreatmentPlanViewModel, message: String, onBuy: (String) -> Unit, onClose: () -> Unit) {
    val kisa = LocalPencere.current.kisa
    DoldurVeyaKaydir(Modifier.fillMaxSize().icerikGenisligi(ICERIK), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.weight(1f))
        val credit = vm.creditRequired
        if (credit != null) {
            // 402: ham hata yerine kredi kartı (Kredi Al → paketler; satın alamıyorsa ne yapılacağı).
            CreditRequiredCard(credit = credit, modifier = Modifier.padding(horizontal = 20.dp), onBuy = { onBuy(credit.message) })
        } else {
            DfyIcon("exclamationmark.triangle.fill", size = 44.dp, tint = DS.Colors.orange400)
            Box(Modifier.fillMaxWidth().heightIn(max = if (kisa) 140.dp else 220.dp).verticalScroll(rememberScrollState())) {
                Text(
                    message, Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    style = sys(15), color = DS.Colors.onSurface, textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineCapsule(
                "Kapat", null, DS.Colors.onSurface, Color.White.copy(alpha = 0.18f), height = 52.dp,
                modifier = Modifier.weight(1f), fill = true, onClick = onClose,
            )
            GradientCapsule("Tekrar dene", null, height = 52.dp, modifier = Modifier.weight(1f)) { vm.hataSonrasiTekrar() }
        }
    }
}

// MARK: - Yerleşim yardımcıları

/**
 * Uzun pencerede `Spacer(weight)`larla dikeyde dağılır (Swift `Spacer()`); kısa pencerede (yatay tablet, bölünmüş ekran,
 * açık klavye) içerik kayar. Ağırlıklar en az pencere yüksekliğine göre dağıtılır.
 */
@Composable
private fun DoldurVeyaKaydir(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val yukseklik = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = yukseklik),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** Liste satırı kabı: tablette ~720 dp ortalı sütun, yatay iç boşluk. */
@Composable
private fun Kap(modifier: Modifier = Modifier, yatay: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(Modifier.icerikGenisligi(ICERIK).then(modifier).padding(horizontal = yatay)) { content() }
}

/** Alt kontrol şeridi (Swift `.ultraThinMaterial`): zemin tam genişlik, içerik ortalı sütunda. */
@Composable
private fun AltCubuk(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(DS.Colors.surfaceContainerLow.copy(alpha = 0.86f))) {
        Box(Modifier.icerikGenisligi(ICERIK).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) { content() }
    }
}

@Composable
private fun BolumEtiketi(metin: String, modifier: Modifier = Modifier) {
    Text(metin, modifier, style = sys(11, FontWeight.Bold, tracking = 1), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Swift `.lineSpacing(n)` — satır yüksekliğine `n` eklenir. */
private fun TextStyle.satirArasi(ekstra: Float): TextStyle = copy(lineHeight = (fontSize.value * 1.2f + ekstra).sp)

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
            .heightIn(min = height)
            .clip(CircleShape)
            .border(1.dp, stroke, CircleShape)
            .padding(horizontal = hPadding)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) DfyIcon(icon, size = textStyle.fontSize.value.dp, tint = color)
        Text(text, style = textStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Marka gradyanlı kapsül düğme (ikon + kalın metin); pasifken yarı saydam. */
@Composable
private fun GradientCapsule(
    text: String,
    icon: String?,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GradientCapsuleIcerik(
        text, icon, height,
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .pressable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
    )
}

/** Gradyan kapsülün görünümü — tıklama `modifier`dadır (`YetkiliButon` kendi tıklamasını verir). */
@Composable
private fun GradientCapsuleIcerik(text: String, icon: String?, height: Dp, modifier: Modifier) {
    Row(
        modifier
            .heightIn(min = height)
            .clip(CircleShape)
            .background(DS.Gradients.primaryGradient)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) DfyIcon(icon, size = 16.dp, tint = Color.White)
        Text(
            text, style = sys(16, FontWeight.Bold), color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Önizlemeler

@Preview(name = "Plan kartları", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanPlanlarPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-planlar") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Hasta seçimi", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 360, heightDp = 640)
@Composable
private fun TreatmentPlanHastaPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-hasta") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Kayıt", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanKayitPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-kayit") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Önizleme (telefon)", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
private fun TreatmentPlanOnizlemePreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-onizleme") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Seçim kartı", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanSecimPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-secim") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Soru", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanSoruPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-soru") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Bitti", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanBittiPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-bitti") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Hata", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun TreatmentPlanHataPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-hata") }
    PreviewEnv { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Tablet yatay · önizleme", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun TreatmentPlanTabletOnizlemePreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-onizleme") }
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Tablet yatay · kayıt (yan yana)", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun TreatmentPlanTabletKayitPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-kayit") }
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Tablet dikey · plan kartları", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun TreatmentPlanTabletDikeyPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-planlar") }
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) { TreatmentPlanEkrani(vm, onDismiss = {}) }
}

@Preview(name = "Düzeltme · konum", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 520)
@Composable
private fun TreatmentPlanKonumSayfasiPreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-onizleme") }
    val durum = remember { DuzenlemeDurumu().apply { konumHazirla(vm.items.firstOrNull()) } }
    PreviewEnv {
        Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
            vm.items.firstOrNull()?.let { DuzenlemeSayfasi(EditTarget.konum(it), vm, durum, onClose = {}) }
        }
    }
}

@Preview(name = "Düzeltme · yarım çene", showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 360, heightDp = 420, fontScale = 1.3f)
@Composable
private fun TreatmentPlanBolgePreview() {
    val vm = remember { TreatmentPlanViewModel.ornek("tp-onizleme") }
    val durum = remember { DuzenlemeDurumu().apply { konumTuru = KonumTuru.bolge; konumBolge = 2 } }
    PreviewEnv {
        Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
            DuzenlemeSayfasi(EditTarget.add, vm, durum, onClose = {})
        }
    }
}
