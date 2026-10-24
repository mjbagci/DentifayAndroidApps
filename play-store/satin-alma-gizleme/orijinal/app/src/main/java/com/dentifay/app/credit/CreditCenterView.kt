package com.dentifay.app.credit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.NumberFormat
import java.util.Locale

/** Kredi Yönetimi bölümleri (Swift `CreditCenterView.Section`). */
enum class CreditCenterSection {
    overview, ledger, people, topUp;

    val id: String get() = name
    val label: String
        get() = when (this) {
            overview -> "Özet"
            ledger -> "Hareketler"
            people -> "Kişiler"
            topUp -> "Yükle"
        }
}

/**
 * Kredi Yönetimi — web'deki `/krediler` ekranının telefon karşılığı. AYNI uçları okur
 * (`CreditCenter.kt`), hesap sunucudadır; bu ekran yalnız çizer.
 *
 *   Özet       kullanılabilir kredi · dönem kullanımı · yeterlilik · özellik/kişi kırılımı ·
 *              gün-saat ısı haritası · en çok kredi harcanan işlemler
 *   Hareketler işlem defteri (kim · ne · ne zaman · kaç kredi), süzgeçli, sayfalı
 *   Kişiler    bu ayın kişi kullanımı + aylık limitler (+ düşük bakiye uyarı eşiği)
 *   Yükle      kredi paketi (bedel kliniğin Dentifay faturasına) · son yüklemeler · fiyat listesi
 *
 * Görmek `fin.credits.view` ister (varsayılan yalnız klinik sahibi). Limit düzenleme
 * (`fin.credits.limits`) ve satın alma (`fin.credits`) ayrı yetkilerdir; sunucu her yanıtta
 * bunları söyler (`canManageLimits`, `canPurchase`) — ekran kendi kararını uydurmaz.
 */
@Composable
fun CreditCenterView(onDismiss: () -> Unit) {
    val store = remember { CreditCenterStore() }
    CreditCenterContent(store, onDismiss, initialSection = CreditCenterSection.overview, loadOnAppear = true)
}

@Composable
private fun CreditCenterContent(
    store: CreditCenterStore,
    onDismiss: () -> Unit,
    initialSection: CreditCenterSection,
    loadOnAppear: Boolean,
) {
    val credits = SmileCreditsStore.shared
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(initialSection) }

    var editing by remember { mutableStateOf<CreditPeople.Person?>(null) }
    var editingThreshold by remember { mutableStateOf(false) }
    var confirmPack by remember { mutableStateOf<SmileCreditPack?>(null) }
    var purchased by remember { mutableStateOf<SmilePurchaseResult?>(null) }
    val alertTitle by remember { mutableStateOf("") }
    var alertText by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // Hareketler: son satır göründüğünde sonraki sayfa (`LazyVStack` + `.onAppear`).
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    var lastRowId by remember { mutableStateOf<String?>(null) }
    var lastRowTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var pagedFor by remember { mutableStateOf<String?>(null) }

    // MARK: - Yükleme

    suspend fun loadSection(s: CreditCenterSection) {
        when (s) {
            CreditCenterSection.overview -> Unit
            CreditCenterSection.ledger -> {
                if (store.pagination == null && store.rowsError == null) store.loadRows(reset = true)
                if (store.people == null) store.loadPeople()   // kişi süzgecinin seçenekleri
            }
            CreditCenterSection.people -> if (store.people == null) store.loadPeople()
            CreditCenterSection.topUp -> store.loadPurchases()
        }
    }

    suspend fun reloadAll() {
        store.loadUsage()
        when (section) {
            CreditCenterSection.overview -> Unit
            CreditCenterSection.ledger -> store.loadRows(reset = true)
            CreditCenterSection.people -> store.loadPeople()
            CreditCenterSection.topUp -> store.loadPurchases()
        }
    }

    fun flash(text: String) {
        Haptics.success()
        toast = text
        scope.launch {
            delay(3000)
            if (toast == text) toast = null
        }
    }

    /** `CreditPurchaseSheet` koşulları onaylatıp satın aldıktan SONRA (hatalar pencerede gösterilir). */
    suspend fun purchaseDone(r: SmilePurchaseResult) {
        purchased = r
        Haptics.success()
        store.loadUsage()
        store.loadPurchases()
    }

    LaunchedEffect(Unit) {
        if (loadOnAppear) {
            store.loadUsage()
            loadSection(section)
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { section }.drop(1).collect { s -> scope.launch { loadSection(s) } } }
    LaunchedEffect(Unit) {
        snapshotFlow { store.range }.drop(1).collect {
            scope.launch {
                store.loadUsage()
                if (store.pagination != null || store.rowsError != null) store.loadRows(reset = true)
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { "${store.filterUser ?: ""}|${store.filterFeature ?: ""}|${store.filterKind ?: ""}" }
            .drop(1).collect { scope.launch { store.loadRows(reset = true) } }
    }
    LaunchedEffect(Unit) { snapshotFlow { store.month }.drop(1).collect { scope.launch { store.loadPeople() } } }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(lastRowId, lastRowTop, viewportBottom) }.collect { (id, top, bottom) ->
            if (section == CreditCenterSection.ledger && id != null && top < bottom && pagedFor != id) {
                pagedFor = id
                scope.launch { store.loadRows(reset = false) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar("Kredi Yönetimi", trailing = { CreditBarButton("Kapat") { onDismiss() } })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    reloadAll()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { viewportBottom = it.boundsInRoot().bottom }
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .padding(bottom = 24.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CcSegmented(
                        options = CreditCenterSection.entries.map { it.label },
                        selected = section.ordinal,
                        onSelect = { section = CreditCenterSection.entries[it] },
                    )

                    if (section == CreditCenterSection.overview || section == CreditCenterSection.ledger) {
                        RangeBar(store)
                    }

                    AnimatedVisibility(toast != null, enter = fadeIn(Motion.default()), exit = fadeOut(Motion.default())) {
                        CreditIconLabel(
                            toast ?: "", "checkmark.circle.fill",
                            size = 13f, weight = FontWeight.SemiBold, color = DS.Colors.tertiaryContainer,
                        )
                    }

                    when (section) {
                        CreditCenterSection.overview -> Overview(store, onTopUp = { section = CreditCenterSection.topUp }) { userId ->
                            store.filterUser = userId
                            section = CreditCenterSection.ledger
                        }
                        CreditCenterSection.ledger -> Ledger(store, onLastRowPositioned = { rowId, top ->
                            lastRowId = rowId
                            lastRowTop = top
                        }, onRetryMore = { scope.launch { store.loadRows(reset = false) } }, onRetry = { scope.launch { store.loadRows(reset = true) } })
                        CreditCenterSection.people -> PeopleSection(
                            store,
                            onEditThreshold = { editingThreshold = true },
                            onEdit = { editing = it },
                            onRetry = { scope.launch { store.loadPeople() } },
                        )
                        CreditCenterSection.topUp -> TopUp(
                            store, credits, purchased,
                            onBuy = { confirmPack = it },
                            onRetry = { scope.launch { store.loadUsage() } },
                        )
                    }
                }
            }
        }
    }

    DfySheet(isPresented = editing != null, onDismiss = { editing = null }, containerColor = DS.Colors.background) { dismiss ->
        editing?.let { person ->
            CcLimitEditorSheet(
                person,
                onSave = { total, features ->
                    store.saveLimits(person.user.id, total, features)
                    flash("${Unvan.dt(person.user.name ?: "Kişi")} için limit kaydedildi.")
                },
                onDismiss = dismiss,
            )
        }
    }
    DfySheet(isPresented = editingThreshold, onDismiss = { editingThreshold = false }, containerColor = DS.Colors.background, heightFraction = 0.5f) { dismiss ->
        CcThresholdSheet(
            current = store.usage?.wallet?.lowBalanceWarnCredits ?: 0.0,
            onSave = { value ->
                store.saveThreshold(value)
                flash("Uyarı eşiği kaydedildi.")
            },
            onDismiss = dismiss,
        )
    }
    // Satın alma: Kredi Satın Alma Koşulları okunup onaylanmadan YAPILMAZ (sunucu 428 verir).
    DfySheet(isPresented = confirmPack != null, onDismiss = { confirmPack = null }, containerColor = DS.Colors.background) { dismiss ->
        confirmPack?.let { pack ->
            CreditPurchaseSheet(pack = pack, onPurchased = { r -> purchaseDone(r) }, onDismiss = dismiss)
        }
    }
    DfyAlert(
        isPresented = alertText != null,
        title = alertTitle,
        message = alertText,
        confirmText = "Tamam",
        onConfirm = { alertText = null },
        onDismiss = { alertText = null },
    )
}

// MARK: - Bölüm seçici (`Picker(.segmented)`)

@Composable
private fun CcSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(hex("767680").copy(alpha = 0.24f))
            .padding(2.dp),
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * selected, Motion.spring(0.3, 0.85), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(7.dp))
                .clip(RoundedCornerShape(7.dp))
                .background(hex("636366")),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, title ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .plainClickable { if (i != selected) onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title, style = sys(13, if (i == selected) FontWeight.SemiBold else FontWeight.Medium), color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

// MARK: - Tarih aralığı

@Composable
private fun RangeBar(store: CreditCenterStore) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CcMenu(CreditRange.entries.map { r -> CcMenuItem(r.label, checked = r == store.range) { store.range = r } }) {
            CcFilterChip("calendar", store.range.label, active = true)
        }
        store.usage?.let { u ->
            Text("${CreditDates.dayLabel(u.from)} – ${CreditDates.dayLabel(u.to)}", style = sys(12), color = DS.Colors.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (store.isLoadingUsage) CreditSpinner(color = DS.Colors.cyan400, size = 16.dp)
    }
}

// MARK: - Özet

@Composable
private fun Overview(store: CreditCenterStore, onTopUp: () -> Unit, onSelectUser: (String) -> Unit) {
    val u = store.usage
    val error = store.usageError
    if (u != null) {
        val order = u.features.map { it.feature }
        if (u.wallet.isLow) {
            CcBanner(
                "exclamationmark.triangle.fill", DS.Colors.orange400,
                "Kullanılabilir kredi (${creditText(u.wallet.availableCredits)}) uyarı eşiğinin (${creditText(u.wallet.lowBalanceWarnCredits)}) altında.",
                "Kredi yükle", onTopUp,
            )
        }
        CcKpiGrid(u)
        if (u.truncated == true) {
            CcBanner("info.circle.fill", DS.Colors.cyan400, "Aralık çok büyük; ilk 200.000 işlem gösteriliyor. Daha kısa bir aralık seçin.")
        }
        CcCard("Kredi kullanımı", if (u.unit == "month") "Ay ay, özelliğe göre (iade düşülmüş)" else "Gün gün, özelliğe göre (iade düşülmüş)") {
            CcUsageChart(u, order)
        }
        CcCard("Özelliğe göre", "Kredinin nereye harcandığı") {
            CcFeatureBreakdown(u.features, order)
        }
        CcCard("Kişiye göre", "Kim ne kadar kullandı — kişiye dokununca hareketleri açılır") {
            CcUserBreakdown(u.users, store.clinicTimeZone, onSelectUser)
        }
        CcCard("Gün ve saate göre kullanım", "Kliniğin saatiyle (${u.tz}); koyu hücre daha çok kredi") {
            CcHeatmapGrid(u.heatmap)
        }
        CcCard("En çok kredi harcanan işlemler", null) {
            CcActionList(u.actions)
        }
        CcFootnote("Kullanım NET gösterilir: başarısız işlemin iadesi aynı kişiye ve aynı güne yazılır. Hareketler bölümünde her işlem tek tek görünür.")
    } else if (error != null) {
        val scope = rememberCoroutineScope()
        CcErrorCard("Kredi kullanımı yüklenemedi", error) { scope.launch { store.loadUsage() } }
    } else {
        CcLoadingBlock()
    }
}

// MARK: - Hareketler

/** Kişi süzgecinin seçenekleri: dönemde kullanan + klinikteki kişiler (tekil, ada göre). */
private fun personOptions(store: CreditCenterStore): List<Pair<String, String>> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<Pair<String, String>>()
    store.usage?.users?.forEach { u ->
        val p = u.user
        if (p != null && seen.add(p.id)) out += p.id to p.displayName
    }
    store.people?.people?.forEach { p ->
        if (seen.add(p.user.id)) out += p.user.id to p.user.displayName
    }
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR"))
    return out.sortedWith { a, b -> collator.compare(a.second, b.second) }
}

@Composable
private fun Ledger(
    store: CreditCenterStore,
    onLastRowPositioned: (String, Float) -> Unit,
    onRetryMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val options = personOptions(store)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CcMenu(listOf(CcMenuItem("Tüm kişiler") { store.filterUser = null }) + options.map { p -> CcMenuItem(p.second) { store.filterUser = p.first } }) {
            CcFilterChip("person", options.firstOrNull { it.first == store.filterUser }?.second ?: "Tüm kişiler", active = store.filterUser != null)
        }
        CcMenu(listOf(CcMenuItem("Tüm özellikler") { store.filterFeature = null }) + CreditLabels.limitFeatures.map { f -> CcMenuItem(CreditLabels.feature(f)) { store.filterFeature = f } }) {
            CcFilterChip("square.grid.2x2", store.filterFeature?.let { CreditLabels.feature(it) } ?: "Tüm özellikler", active = store.filterFeature != null)
        }
        CcMenu(listOf(CcMenuItem("Tüm türler") { store.filterKind = null }) + CreditLabels.kindOrder.map { k -> CcMenuItem(CreditLabels.kinds[k] ?: k) { store.filterKind = k } }) {
            CcFilterChip("line.3.horizontal.decrease", store.filterKind?.let { CreditLabels.kinds[it] } ?: "Tüm türler", active = store.filterKind != null)
        }
    }

    store.pagination?.let { p ->
        Text("${NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).format(p.total)} işlem", style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
    }

    val rows = store.rows
    val error = store.rowsError
    if (rows.isEmpty() && error != null) {
        CcErrorCard("Kredi hareketleri yüklenemedi", error, onRetry)
    } else if (rows.isEmpty() && (store.isLoadingRows || store.pagination == null)) {
        CcLoadingBlock()
    } else if (rows.isEmpty()) {
        CcEmptyNote("Bu süzgeçle hareket yok.")
    } else {
        val tz = store.clinicTimeZone
        val lastId = rows.last().id
        Column(Modifier.fillMaxWidth().glassCard(18.dp).padding(horizontal = 14.dp)) {
            rows.forEach { row ->
                val isLast = row.id == lastId
                CcLedgerRow(
                    row, tz,
                    if (isLast) Modifier.onGloballyPositioned { onLastRowPositioned(row.id, it.positionInRoot().y) } else Modifier,
                )
                if (!isLast) CreditDivider()
            }
        }
        if (store.isLoadingRows) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { CreditSpinner(color = DS.Colors.cyan400) }
        } else if (error != null) {
            CreditIconLabel("$error Tekrar dene", "arrow.clockwise", Modifier.plainClickable(onClick = onRetryMore), size = 12f, color = CreditSystemBlue)
        }
    }
    CcFootnote("Ücretsiz ve satın alınmış krediden birlikte ödenen işlem tek satırdır. İade, gerçekleştiği anda ayrı satır olarak görünür; Özet'te ise düşüldüğü işleme yazılır.")
}

// MARK: - Kişiler

@Composable
private fun PeopleSection(
    store: CreditCenterStore,
    onEditThreshold: () -> Unit,
    onEdit: (CreditPeople.Person) -> Unit,
    onRetry: () -> Unit,
) {
    val months = CreditDates.lastMonths(12, store.clinicTimeZone)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CcMenu(months.map { m -> CcMenuItem(CreditDates.monthLabel(m)) { store.month = m } }) {
            CcFilterChip("calendar", CreditDates.monthLabel(store.month ?: store.people?.month ?: months.firstOrNull() ?: ""), active = true)
        }
    }
    CcFootnote("Limitler kliniğin takvim ayıyla sayılır; ay başında sıfırlanır.")

    val data = store.people
    val error = store.peopleError
    if (data != null) {
        val u = store.usage
        if (data.canManageLimits && u != null) {
            CcThresholdCard(u.wallet.lowBalanceWarnCredits, onEditThreshold)
        }
        if (data.people.isEmpty()) {
            CcEmptyNote("Bu ay kredi kullanan ya da limiti olan kişi yok.")
        }
        data.people.forEach { person ->
            CcPersonCard(person, data.canManageLimits) { onEdit(person) }
        }
        data.unattributed?.let { un ->
            Row(
                Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DfyIcon("questionmark.circle", tint = DS.Colors.slate400)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Kişiye bağlanamayan kullanım", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                    Text("Eski ya da sistemin yaptığı işlemler — kişi limitine sayılmaz.", style = sys(12), color = DS.Colors.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${creditText(un.credits)} kredi", style = sys(14, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
                    Text("${un.operations} işlem", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }
            }
        }
    } else if (error != null) {
        CcErrorCard("Kişi kullanımı yüklenemedi", error, onRetry)
    } else {
        CcLoadingBlock()
    }

    CcFootnote("Bu ekranı varsayılan olarak yalnız klinik sahibi görür. Bir departmana açmak için web'de Ayarlar › Yetki Ayarları'nda, tek bir kişiye açmak için Ayarlar › Kullanıcı Ayarları'nda “Kredi Yönetimi'ni görüntüleme” yetkisini verin; limit düzenleme ve kredi satın alma ayrı yetkilerdir.")
}

// MARK: - Yükle

@Composable
private fun TopUp(
    store: CreditCenterStore,
    credits: SmileCreditsStore,
    purchased: SmilePurchaseResult?,
    onBuy: (SmileCreditPack) -> Unit,
    onRetry: () -> Unit,
) {
    val u = store.usage
    val error = store.usageError
    if (u != null) {
        CcWalletCard(u.wallet)
        AnimatedVisibility(purchased != null, enter = fadeIn(Motion.spring(0.4, 0.85))) {
            purchased?.let { p ->
                CcBanner(
                    "checkmark.seal.fill", DS.Colors.tertiaryContainer,
                    "${creditText(p.creditsGranted)} kredi eklendi. ${SmileCreditFormat.lira(p.priceTL)} bu ayın Dentifay faturanıza yansıyacak.",
                )
            }
        }
        CcSectionHeader("KREDİ PAKETLERİ")
        if (!u.canPurchase) {
            CcBanner(
                "person.badge.key.fill", DS.Colors.orange400,
                if (u.purchaseBlockedReason == "DEMO") "Demo hesapta kredi satın alınamaz; ek kredi için Dentifay ile iletişime geçin."
                else "Kredi satın alma yetkiniz yok; klinik sahibine başvurun.",
            )
        }
        u.packs.forEach { pack ->
            CcPackCard(pack, enabled = u.canPurchase && !credits.isPurchasing) { onBuy(pack) }
        }
        CcFootnote("Ödeme uygulamada alınmaz: paket bedeli ay sonunda kliniğinizin Dentifay faturasına yansır. Başarısız işlemin kredisi otomatik iade edilir.")
        if (u.wallet.isDemo == true) {
            CcFootnote("Demo hesapta diğer özellikler sabit tarifeyle düşer; her düşüm Hareketler bölümünde görünür.")
        }

        CcCard("Son yüklemeler", monthSubtitle(u.wallet.thisMonth)) {
            val purchasesError = store.purchasesError
            if (store.purchases.isEmpty() && purchasesError != null) {
                Text(purchasesError, style = sys(12), color = DS.Colors.error)
            } else if (store.purchases.isEmpty()) {
                Text("Son bir yılda kredi yüklemesi yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    store.purchases.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${creditText(r.credits)} kredi", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                                Text(
                                    listOfNotNull(CreditDates.short(r.at, store.clinicTimeZone), r.user?.displayName).joinToString(" · "),
                                    style = sys(11), color = DS.Colors.onSurfaceVariant,
                                )
                            }
                            Text(r.priceTL?.let { SmileCreditFormat.lira(it) } ?: "—", style = sys(14, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface)
                        }
                    }
                }
            }
        }

        CcCard("Fiyat listesi", "Her işlemin kaç kredi düştüğü") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                u.priceCatalog.forEach { p ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(CreditLabels.feature(p.feature), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                            Text(
                                listOfNotNull(CreditLabels.action(p.feature, p.action), CreditLabels.unit(p.unit)).joinToString(" · "),
                                style = sys(11), color = DS.Colors.onSurfaceVariant,
                            )
                        }
                        // "Ücretsiz" · "2 kredi" · "1–2 kredi" (aralıklı) · "+1 kredi" (ek ücret)
                        Text(
                            p.priceText,
                            Modifier.padding(top = 1.dp),
                            style = sys(13, FontWeight.SemiBold).monoDigits(),
                            color = if (p.isFree) DS.Colors.tertiaryContainer else DS.Colors.cyan400,
                        )
                    }
                }
            }
        }
    } else if (error != null) {
        CcErrorCard("Kredi bilgisi yüklenemedi", error, onRetry)
    } else {
        CcLoadingBlock()
    }
}

private fun monthSubtitle(m: SmileWallet.Month?): String? {
    if (m == null) return null
    var s = "${CreditDates.monthLabel(m.period)} faturasına yansıyacak: ${SmileCreditFormat.lira(m.purchasedTL)}"
    if (m.purchasedTL > 0) s += if (m.billed) " · faturalandı" else " · ay sonu faturasına"
    return s
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterOverviewPreview() {
    PreviewEnv {
        val store = remember {
            CreditCenterStore().apply { previewFill(CreditPreviewData.usage, CreditPreviewData.people, CreditPreviewData.rows) }
        }
        CreditCenterContent(store, onDismiss = {}, initialSection = CreditCenterSection.overview, loadOnAppear = false)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterLedgerPreview() {
    PreviewEnv {
        val store = remember {
            CreditCenterStore().apply { previewFill(CreditPreviewData.usage, CreditPreviewData.people, CreditPreviewData.rows) }
        }
        CreditCenterContent(store, onDismiss = {}, initialSection = CreditCenterSection.ledger, loadOnAppear = false)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterPeoplePreview() {
    PreviewEnv {
        val store = remember {
            CreditCenterStore().apply { previewFill(CreditPreviewData.usage, CreditPreviewData.people, CreditPreviewData.rows) }
        }
        CreditCenterContent(store, onDismiss = {}, initialSection = CreditCenterSection.people, loadOnAppear = false)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterTopUpPreview() {
    PreviewEnv {
        val store = remember {
            CreditCenterStore().apply { previewFill(CreditPreviewData.usage, CreditPreviewData.people, CreditPreviewData.rows) }
        }
        CreditCenterContent(store, onDismiss = {}, initialSection = CreditCenterSection.topUp, loadOnAppear = false)
    }
}
