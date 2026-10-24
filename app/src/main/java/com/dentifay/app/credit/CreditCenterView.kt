package com.dentifay.app.credit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Kredi Yönetimi bölümleri (Swift `CreditCenterView.Section`) — web `/krediler` ile AYNI ad ve sıra (Ek MO.21a). */
enum class CreditCenterSection {
    overview, ledger, people, topUp;

    val id: String get() = name
    val label: String
        get() = when (this) {
            overview -> "Genel Bakış"
            ledger -> "Hareketler"
            people -> "Kişiler ve Limitler"
            topUp -> "Kredi Yükle"
        }
}

/** Gösterge paneli (Genel Bakış, Kişiler ızgarası) en çok bu genişlikte; tablette ortalanır. */
private val OZET_EN = 1040.dp

/** Okunur liste (Hareketler, Kredi Yükle) genişliği. */
private val LISTE_EN = 840.dp

/** Bölüm seçicisi genişliği. */
private val SECICI_EN = 640.dp

/** Geniş düzen eşiği (içerik genişliği): 4 sütun gösterge, yan yana kartlar, iki sütun kişi. */
private val GENIS_ICERIK = 720.dp

/** Bölüm içeriği: en çok `en` genişlikte ortalı, 16 yatay iç boşluk, üstte `ust` boşluk (Swift `VStack(spacing: 16).padding(16)`). */
private fun Modifier.bolum(en: Dp, ust: Dp = 16.dp): Modifier =
    this.icerikGenisligi(en).padding(horizontal = 16.dp).padding(top = ust)

/** Bölüm içeriğinin kullanılabilir genişliği. */
private fun icGenislik(tam: Dp, en: Dp): Dp = min(tam.value, en.value).dp - 32.dp

/**
 * Kredi Yönetimi — web `/krediler` sayfasıyla AYNI ad ve sırada dört bölüm (Ek MO.21a) ve AYNI uçlar
 * (`CreditCenter.kt`). Hesap sunucudadır; bu ekran yalnız çizer (M0-4).
 *
 *   Genel Bakış        düşük bakiye bandı · dört gösterge · seri · özellik · kişi · ısı haritası · işlemler · aylık döküm ·
 *                      e-Fatura · kredi ile çalışan işlemler · düşük bakiye eşiği (Ekleme 18 · 37D.2 sırası)
 *   Hareketler         imleçli defter; süzgeçler (kişi · özellik · tür · aralık) SUNUCUYA gider
 *   Kişiler ve Limitler ay seçici · kişi satırı (özellik dökümü, limit çubuğu) · limit düzenleme (`If-Match`) · atanmamış kullanım
 *   Kredi Yükle        yalnız `fin.credits`: paketler (koşul kabulüyle satın alma) · son satın almalar · fatura satırı
 *
 * Görünürlük: `comm.usage` | `fin.credits` (sunucunun görme kapısı); limit düzenleme sunucunun `canManageLimits`ı,
 * satın alma cüzdanın `canPurchase`ı — ekran kendi kararını uydurmaz. Android: "Kredi Yükle" ayrıca
 * `AppConfig.inAppPurchase` ister (mağaza derlemesinde krediler web panelinden alınır).
 *
 * Tablet: gösterge paneli 1040 dp'de ortalanır, geniş pencerede göstergeler 4 sütun, kartlar ikişer yan yana, kişiler iki
 * sütun; hareket listesi okunur genişlikte (840 dp). Uzun listeler tembel (satır satır) çizilir.
 */
@Composable
fun CreditCenterView(onDismiss: () -> Unit, baslangic: CreditCenterSection = CreditCenterSection.overview) {
    val store = remember { CreditCenterStore() }
    CreditCenterContent(store, onDismiss, baslangic)
}

@Composable
private fun CreditCenterContent(store: CreditCenterStore, onDismiss: () -> Unit, baslangic: CreditCenterSection) {
    val caps = LocalAuthManager.current.yetenekler
    val credits = SmileCreditsStore.shared
    val tarife = KrediTarifesiStore.shared
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val yukleAcik = KrediErisimi.yukleBolumu(caps)
    val sections = CreditCenterSection.entries.filter { it != CreditCenterSection.topUp || yukleAcik }
    var section by remember {
        mutableStateOf(if (baslangic == CreditCenterSection.topUp && !yukleAcik) CreditCenterSection.overview else baslangic)
    }
    // Yetki değişirse (oturum tazelendi) görünmeyen bölümde kalınmaz.
    LaunchedEffect(yukleAcik) { if (!yukleAcik && section == CreditCenterSection.topUp) section = CreditCenterSection.overview }

    var editing by remember { mutableStateOf<CreditPeople.Person?>(null) }
    var editingThreshold by remember { mutableStateOf(false) }
    var confirmPack by remember { mutableStateOf<SmileCreditPack?>(null) }
    var purchased by remember { mutableStateOf<SmilePurchaseResult?>(null) }
    var kosulSatiri by remember { mutableStateOf<CreditTransactionsPage.Row?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var sonToast by remember { mutableStateOf("") }
    var eBelgeHata by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // MARK: Yükleme

    suspend fun loadSection(s: CreditCenterSection) {
        when (s) {
            CreditCenterSection.overview -> Unit
            CreditCenterSection.ledger -> {
                if (!store.rowsLoaded && store.rowsError == null) store.loadRows(reset = true)
                if (store.people == null) store.loadPeople()   // kişi süzgecinin seçenekleri
            }
            CreditCenterSection.people -> if (store.people == null) store.loadPeople()
            CreditCenterSection.topUp -> store.sonSatinAlmalariYukle()
        }
    }

    suspend fun reload(s: CreditCenterSection) {
        when (s) {
            CreditCenterSection.overview -> store.loadOverview()
            CreditCenterSection.ledger -> store.loadRows(reset = true)
            CreditCenterSection.people -> store.loadPeople()
            CreditCenterSection.topUp -> {
                credits.refresh()
                store.sonSatinAlmalariYukle()
            }
        }
    }

    fun flash(text: String) {
        Haptics.success()
        sonToast = text
        toast = text
        scope.launch {
            delay(3000)
            if (toast == text) toast = null
        }
    }

    /** Satın alma tamamlandıktan SONRA (hatalar pencerede gösterilir) — iki depo da sunucudan tazelenir. */
    suspend fun purchaseDone(r: SmilePurchaseResult) {
        purchased = r
        Haptics.success()
        store.loadOverview()
        store.sonSatinAlmalariYukle()
    }

    LaunchedEffect(Unit) {
        store.loadOverview()
        credits.refresh()
        loadSection(section)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { section }.drop(1).collectLatest { s ->
            listState.scrollToItem(0)
            loadSection(s)
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { store.range }.drop(1).collectLatest { store.loadAnalysis() } }
    LaunchedEffect(Unit) { snapshotFlow { store.filter }.drop(1).collectLatest { store.loadRows(reset = true) } }
    LaunchedEffect(Unit) { snapshotFlow { store.month }.drop(1).collectLatest { store.loadPeople() } }

    // Kişi süzgecinin seçenekleri: aralıkta kullananlar + Kişiler listesi (tekil, ada göre).
    val analysis = store.analysis
    val peopleData = store.people
    val personOptions = remember(analysis, peopleData) { kisiSecenekleri(analysis, peopleData) }
    // Özellik süzgeci: sunucunun kredili özellikleri + satın alma / tanım.
    val featureOptions = remember(peopleData) {
        (peopleData?.features ?: listOf("ai_agent", "ai_center", "xray", "messaging", "before_after")) + listOf("purchase", "platform_grant")
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar("Kredi Yönetimi", trailing = { CreditBarButton("Kapat") { onDismiss() } })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    try {
                        reload(section)
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tamW = maxWidth
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item(key = "bolumler", contentType = "secici") {
                        CcSegmented(
                            options = sections.map { it.label },
                            selected = sections.indexOf(section).coerceAtLeast(0),
                            onSelect = { i -> sections.getOrNull(i)?.let { section = it } },
                            modifier = Modifier.bolum(SECICI_EN),
                        )
                    }
                    if (section == CreditCenterSection.overview) {
                        item(key = "aralik", contentType = "aralik") { RangeBar(store, Modifier.bolum(OZET_EN)) }
                    }
                    item(key = "bildirim", contentType = "bildirim") {
                        Column(Modifier.fillMaxWidth()) {
                            AnimatedVisibility(toast != null, enter = fadeIn(Motion.default()), exit = fadeOut(Motion.default())) {
                                CreditIconLabel(
                                    sonToast, "checkmark.circle.fill", Modifier.bolum(OZET_EN),
                                    size = 13f, weight = FontWeight.SemiBold, color = DS.Colors.tertiaryContainer,
                                )
                            }
                        }
                    }

                    when (section) {
                        CreditCenterSection.overview -> genelBakis(
                            store = store,
                            wallet = store.usage?.wallet ?: credits.wallet,
                            tarife = tarife,
                            tamW = tamW,
                            eBelgeHata = eBelgeHata,
                            yukleEylemi = if (yukleAcik) ({ section = CreditCenterSection.topUp }) else null,
                            onSelectUser = { userId ->
                                store.filter = CreditFilter(userId = userId)
                                section = CreditCenterSection.ledger
                            },
                            onRefreshEDocument = {
                                scope.launch {
                                    try {
                                        store.refreshEDocument()
                                        eBelgeHata = null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: CreditCenterClient.ClientError) {
                                        eBelgeHata = e.message ?: "Kontör yenilenemedi."
                                    } catch (e: Exception) {
                                        eBelgeHata = "Kontör yenilenemedi."
                                    }
                                }
                            },
                            onEditThreshold = { editingThreshold = true },
                            onRetryOverview = { scope.launch { store.loadOverview() } },
                            onRetryAnalysis = { scope.launch { store.loadAnalysis() } },
                        )
                        CreditCenterSection.ledger -> hareketler(
                            store = store,
                            showTerms = KrediErisimi.yukleGorur(caps),
                            personOptions = personOptions,
                            featureOptions = featureOptions,
                            onTerms = { kosulSatiri = it },
                            onRetry = { scope.launch { store.loadRows(reset = true) } },
                            onMore = { scope.launch { store.loadRows(reset = false) } },
                        )
                        CreditCenterSection.people -> kisilerVeLimitler(
                            store = store,
                            tamW = tamW,
                            onEdit = { editing = it },
                            onMore = { scope.launch { store.loadPeople(more = true) } },
                            onRetry = { scope.launch { store.loadPeople() } },
                        )
                        CreditCenterSection.topUp -> krediYukle(
                            store = store,
                            credits = credits,
                            wallet = credits.wallet ?: store.usage?.wallet,
                            purchased = purchased,
                            tamW = tamW,
                            onBuy = { confirmPack = it },
                            onTerms = { kosulSatiri = it },
                            onRetry = { scope.launch { credits.refresh() } },
                        )
                    }

                    item(key = "son", contentType = "son") {
                        Spacer(Modifier.fillMaxWidth().navigationBarsPadding().height(40.dp))
                    }
                }
            }
        }
    }

    DfySheet(isPresented = editing != null, onDismiss = { editing = null }, containerColor = DS.Colors.background) { dismiss ->
        editing?.let { person ->
            CcLimitEditorSheet(
                person = person,
                features = store.people?.features ?: emptyList(),
                maxCredits = store.people?.maxLimitCredits ?: 1_000_000.0,
                onSave = { total, features ->
                    store.saveLimits(person, total, features)
                    flash("Kredi limiti kaydedildi.")
                },
                onDismiss = dismiss,
            )
        }
    }
    DfySheet(
        isPresented = editingThreshold, onDismiss = { editingThreshold = false },
        containerColor = DS.Colors.background, heightFraction = 0.5f,
    ) { dismiss ->
        CcThresholdSheet(
            current = store.settings?.lowBalanceWarnCredits ?: 0.0,
            onSave = { value ->
                store.saveThreshold(value)
                flash("Uyarı eşiği kaydedildi.")
            },
            onDismiss = dismiss,
        )
    }
    // Satın alma: Kredi Satın Alma Koşulları okunup onaylanmadan YAPILMAZ (Ekleme 18 · 37C; sunucu 428/409).
    // Mağaza derlemesinde pencere hiç yok (`AppConfig.inAppPurchase`).
    if (AppConfig.inAppPurchase) {
        DfySheet(isPresented = confirmPack != null, onDismiss = { confirmPack = null }, containerColor = DS.Colors.background) { dismiss ->
            confirmPack?.let { pack ->
                CreditPurchaseSheet(pack = pack, onPurchased = { r -> purchaseDone(r) }, onDismiss = dismiss)
            }
        }
    }
    DfySheet(isPresented = kosulSatiri != null, onDismiss = { kosulSatiri = null }, containerColor = DS.Colors.background) { dismiss ->
        kosulSatiri?.let { row ->
            CcAcceptedTermsSheet(row, load = { store.acceptedTerms(row) }, onDismiss = dismiss)
        }
    }
}

/** Kişi süzgecinin seçenekleri: aralıkta kullananlar + Kişiler listesi (tekil, Türkçe ada göre). */
private fun kisiSecenekleri(analysis: CreditAnalysis?, people: CreditPeople?): List<Pair<String, String>> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<Pair<String, String>>()
    analysis?.users?.forEach { u ->
        val p = u.user
        if (p != null && seen.add(p.id)) out += p.id to p.displayName
    }
    people?.people?.forEach { p ->
        if (seen.add(p.user.id)) out += p.user.id to p.user.displayName
    }
    val collator = Collator.getInstance(Locale.forLanguageTag("tr-TR"))
    return out.sortedWith { a, b -> collator.compare(a.second, b.second) }
}

// MARK: - Bölüm seçici (`Picker(.segmented)`)

private val SegmentZemin = hex("767680").copy(alpha = 0.24f)
private val SegmentSecili = hex("636366")

/**
 * iOS segmentli seçici. Etiketler sığmazsa yazı (en az 11) küçülür, o da yetmezse iki satıra iner — hiçbir genişlikte
 * kesilmez ("Kişiler ve Limitler" telefonda). Seçim göstergesi yalnız konumla kayar (yeniden çizim yok).
 */
@Composable
private fun CcSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(SegmentZemin)
            .padding(2.dp)
            .semantics { contentDescription = "Kredi Yönetimi bölümleri" }
            .selectableGroup(),
    ) {
        val n = max(1, options.size)
        val segW = maxWidth / n
        val olcu = remember(options, segW, density) {
            val stil = sys(13, FontWeight.SemiBold)
            val avail = with(density) { (segW - 12.dp).toPx() }
            val widest = options.maxOfOrNull { measurer.measure(it, stil, maxLines = 1, softWrap = false).size.width.toFloat() } ?: 0f
            when {
                widest <= 0f || widest <= avail -> 13f to 1
                13f * avail / widest >= 11f -> (13f * avail / widest) to 1
                else -> 11f to 2
            }
        }
        val x by animateDpAsState(segW * selected, Motion.spring(0.3, 0.85), label = "segment")
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset { IntOffset(x.roundToPx(), 0) }
                    .width(segW)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(SegmentSecili),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, title ->
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 28.dp)
                        .selectable(
                            selected = i == selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { if (i != selected) onSelect(i) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        style = sys(olcu.first, if (i == selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = Color.White, maxLines = olcu.second, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// MARK: - Tarih aralığı (varsayılan bu ay; sunucu en çok bir yıl kabul eder)

@Composable
private fun RangeBar(store: CreditCenterStore, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CcMenu(CreditRange.entries.map { r -> CcMenuItem(r.label, checked = r == store.range) { store.range = r } }) {
            CcFilterChip("calendar", store.range.label, active = true)
        }
        val a = store.analysis
        val f = a?.from
        val t = a?.to
        if (f != null && t != null) {
            Text(
                "${CreditDates.dayLabel(f)} – ${CreditDates.dayLabel(t)}", Modifier.weight(1f, fill = false),
                style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (store.isLoadingOverview) CreditSpinner(color = DS.Colors.cyan400, size = 16.dp)
    }
}

// MARK: - Genel Bakış

/** Genel Bakış parçası: geniş pencerede `yarim` parçalar ikişer yan yana dizilir. */
private class OzetParca(val key: String, val yarim: Boolean, val icerik: @Composable (Modifier) -> Unit)

private fun LazyListScope.genelBakis(
    store: CreditCenterStore,
    wallet: SmileWallet?,
    tarife: KrediTarifesiStore,
    tamW: Dp,
    eBelgeHata: String?,
    yukleEylemi: (() -> Unit)?,
    onSelectUser: (String) -> Unit,
    onRefreshEDocument: () -> Unit,
    onEditThreshold: () -> Unit,
    onRetryOverview: () -> Unit,
    onRetryAnalysis: () -> Unit,
) {
    val icW = icGenislik(tamW, OZET_EN)
    val genis = icW >= GENIS_ICERIK
    val parcalar = mutableListOf<OzetParca>()

    val w = wallet
    val usageError = store.usageError
    if (w != null) {
        if (w.isLow == true) {
            val eylem = if (w.canPurchase) yukleEylemi else null
            parcalar += OzetParca("ozet-dusuk", false) { m ->
                Box(m) {
                    CcBanner(
                        "exclamationmark.triangle.fill", DS.Colors.orange400,
                        "Kullanılabilir kredi (${creditText(w.availableCredits)}) uyarı eşiğinin (${creditText(w.lowBalanceWarnCredits ?: 0.0)}) altında.",
                        eylem?.let { "Kredi yükle" }, eylem,
                    )
                }
            }
        }
        parcalar += OzetParca("ozet-kpi", false) { m -> Box(m) { CcKpiGrid(w, store.analysis, if (genis) 4 else 2) } }
        parcalar += OzetParca("ozet-buay", false) { m ->
            Text("Bu ay kullanılan: ${creditText(w.thisMonth?.usedCredits ?: 0.0)} kredi", m, style = sys(12), color = DS.Colors.onSurfaceVariant)
        }
    } else if (usageError != null) {
        parcalar += OzetParca("ozet-cuzdanHata", false) { m -> Box(m) { CcErrorCard("Kredi bilgileri yüklenemedi.", usageError, onRetryOverview) } }
    } else if (CreditCenterStore.Parca.usage !in store.gizli) {   // kodsuz 403 → parça çizilmez (M-E7.5)
        parcalar += OzetParca("ozet-yukleniyor", false) { m -> Box(m) { CcLoadingBlock() } }
    }

    val a = store.analysis
    val analysisError = store.analysisError
    if (a != null) {
        val order = a.features.map { it.feature }
        parcalar += OzetParca("ozet-grafik", false) { m ->
            CcCard("Kredi kullanımı", if (a.unit == "month") "Ay ay, özelliğe göre" else "Gün gün, özelliğe göre", m) {
                CcUsageChart(a, order, yukseklik = if (genis) 240.dp else 184.dp)
            }
        }
        parcalar += OzetParca("ozet-ozellik", true) { m ->
            CcCard("Özelliğe göre kullanım", null, m) { CcFeatureBreakdown(a.features, order) }
        }
        parcalar += OzetParca("ozet-kisi", true) { m ->
            CcCard("Kişiye göre", "Kim ne kadar kullandı — kişiye tıklayınca hareketleri açılır", m) { CcUserBreakdown(a.users, onSelectUser) }
        }
        parcalar += OzetParca("ozet-isi", false) { m ->
            CcCard("Gün ve saate göre kullanım", "Kliniğin saatiyle (${a.tz ?: "Europe/Istanbul"}); koyu hücre daha çok kredi", m) {
                CcHeatmapGrid(a.heatmap)
            }
        }
        val kesik = if (a.truncated == true) OzetParca("ozet-kesik", false) { m ->
            Box(m) {
                CcBanner("info.circle.fill", DS.Colors.cyan400, "Aralık çok büyük; ilk 200.000 işlem gösteriliyor. Daha kısa bir aralık seçin.")
            }
        } else null
        // Geniş düzende uyarı, yan yana kart çiftini bölmesin diye işlemlerin önüne alınır.
        if (genis && kesik != null) parcalar += kesik
        parcalar += OzetParca("ozet-islemler", true) { m ->
            CcCard("En çok kredi harcanan işlemler", null, m) { CcActionList(a.actions) }
        }
        if (!genis && kesik != null) parcalar += kesik
    } else if (analysisError != null) {
        parcalar += OzetParca("ozet-analizHata", false) { m -> Box(m) { CcErrorCard("Kullanım dağılımı yüklenemedi.", analysisError, onRetryAnalysis) } }
    }

    val months = store.usage?.months
    if (!months.isNullOrEmpty()) {
        parcalar += OzetParca("ozet-aylar", true) { m ->
            CcCard("Geçmiş aylar", "Kullanılan kredi ve satın alınan paketlerin faturaya yansıyan tutarı, ay ay.", m) { CcMonthlyList(months) }
        }
    }

    if (CreditCenterStore.Parca.eDocument !in store.gizli) {
        parcalar += OzetParca("ozet-efatura", true) { m ->
            CcEDocumentCard(store.eDocument, store.eDocumentError, eBelgeHata, onRefreshEDocument, m)
        }
    }

    parcalar += OzetParca("ozet-tarife", true) { m ->
        CcCard("Kredi ile çalışan işlemler", null, m) { CcTariffList(tarife.tarife, tarife.hata) }
    }

    store.settings?.let { s ->
        parcalar += OzetParca("ozet-esik", false) { m -> Box(m) { CcThresholdCard(s.lowBalanceWarnCredits, s.canEdit == true, onEditThreshold) } }
    }
    parcalar += OzetParca("ozet-not", false) { m ->
        CcFootnote("Kullanım NET gösterilir: başarısız işlemin iadesi aynı kişiye ve aynı güne yazılır. Hareketler sekmesinde her işlem tek tek görünür.", m)
    }

    var i = 0
    while (i < parcalar.size) {
        val p = parcalar[i]
        val q = parcalar.getOrNull(i + 1)
        if (genis && p.yarim && q != null && q.yarim) {
            item(key = "${p.key}+${q.key}", contentType = "ozet-cift") {
                Row(Modifier.bolum(OZET_EN).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    p.icerik(Modifier.weight(1f).fillMaxHeight())
                    q.icerik(Modifier.weight(1f).fillMaxHeight())
                }
            }
            i += 2
        } else {
            item(key = p.key, contentType = p.key) { p.icerik(Modifier.bolum(OZET_EN)) }
            i += 1
        }
    }
}

// MARK: - Hareketler (süzgeçler sunucuya — Ekleme 18 · 37D.1)

private fun LazyListScope.hareketler(
    store: CreditCenterStore,
    showTerms: Boolean,
    personOptions: List<Pair<String, String>>,
    featureOptions: List<String>,
    onTerms: (CreditTransactionsPage.Row) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
) {
    item(key = "hareket-suzgecler", contentType = "suzgecler") {
        val filter = store.filter
        Row(
            Modifier.bolum(LISTE_EN).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CcMenu(
                listOf(CcMenuItem("Tüm kişiler") { store.filter = store.filter.copy(userId = null) }) +
                    personOptions.map { (id, name) -> CcMenuItem(name) { store.filter = store.filter.copy(userId = id) } },
            ) {
                CcFilterChip("person", personOptions.firstOrNull { it.first == filter.userId }?.second ?: "Tüm kişiler", active = filter.userId != null)
            }
            CcMenu(
                listOf(CcMenuItem("Tüm özellikler") { store.filter = store.filter.copy(feature = null) }) +
                    featureOptions.map { f -> CcMenuItem(CreditLabels.feature(f)) { store.filter = store.filter.copy(feature = f) } },
            ) {
                CcFilterChip("square.grid.2x2", filter.feature?.let { CreditLabels.feature(it) } ?: "Tüm özellikler", active = filter.feature != null)
            }
            CcMenu(
                listOf(CcMenuItem("Tüm türler") { store.filter = store.filter.copy(tur = null) }) +
                    CreditLabels.kindOrder.map { k -> CcMenuItem(CreditLabels.kind(k)) { store.filter = store.filter.copy(tur = k) } },
            ) {
                CcFilterChip("line.3.horizontal.decrease", filter.tur?.let { CreditLabels.kind(it) } ?: "Tüm türler", active = filter.tur != null)
            }
            CcMenu(
                listOf(CcMenuItem("Tümü") { store.filter = store.filter.copy(from = null, to = null) }) +
                    CreditRange.entries.map { r ->
                        CcMenuItem(r.label) {
                            val b = r.bounds(store.clinicTimeZone)
                            store.filter = store.filter.copy(from = b.from, to = b.to)
                        }
                    },
            ) {
                val f = filter.from
                val t = filter.to
                val etiket = if (f != null && t != null) "${CreditDates.dayLabel(f)} – ${CreditDates.dayLabel(t)}" else "Tarih aralığı"
                CcFilterChip("calendar", etiket, active = f != null)
            }
        }
    }

    val rows = store.rows
    val error = store.rowsError
    when {
        CreditCenterStore.Parca.rows in store.gizli -> Unit   // kodsuz 403 → parça çizilmez (M-E7.5)
        rows.isEmpty() && error != null -> item(key = "hareket-hata", contentType = "hata") {
            Box(Modifier.bolum(LISTE_EN)) { CcErrorCard("Kredi hareketleri yüklenemedi.", error, onRetry) }
        }
        rows.isEmpty() && (store.isLoadingRows || !store.rowsLoaded) -> item(key = "hareket-yukleniyor", contentType = "yukleniyor") {
            Box(Modifier.bolum(LISTE_EN)) { CcLoadingBlock() }
        }
        rows.isEmpty() -> item(key = "hareket-bos", contentType = "bos") {
            Box(Modifier.bolum(LISTE_EN)) { CcEmptyNote("Henüz kredi hareketi yok.") }
        }
        else -> {
            val son = rows.lastIndex
            // Her satır ortak cam kartın kendi dilimini çizer — uzun defter tembel çizilir.
            itemsIndexed(rows, key = { _, r -> "hareketSatiri-${r.id}" }, contentType = { _, _ -> "hareket" }) { i, r ->
                CcLedgerRow(
                    r, showTerms = showTerms, onTerms = { onTerms(r) },
                    modifier = Modifier
                        .bolum(LISTE_EN, ust = if (i == 0) 16.dp else 0.dp)
                        .kartDilimi(ilk = i == 0, son = i == son)
                        .padding(horizontal = 14.dp),
                )
            }
            if (store.isLoadingRows) {
                item(key = "hareket-dahaYukleniyor", contentType = "yukleniyor") {
                    Box(Modifier.bolum(LISTE_EN, ust = 8.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CreditSpinner(color = DS.Colors.cyan400)
                    }
                }
            } else if (store.nextCursor != null) {
                item(key = "hareket-daha", contentType = "daha") { Box(Modifier.bolum(LISTE_EN)) { CcMoreButton(onMore) } }
            }
            if (error != null) {
                item(key = "hareket-dahaHata", contentType = "hataMetni") {
                    Text(error, Modifier.bolum(LISTE_EN), style = sys(12), color = DS.Colors.error)
                }
            }
        }
    }
    item(key = "hareket-not", contentType = "not") {
        CcFootnote(
            "Ücretsiz ve satın alınmış krediden birlikte ödenen işlem tek satırdır. İade, gerçekleştiği anda ayrı satır olarak görünür; Genel Bakış'ta ise düşüldüğü işleme yazılır.",
            Modifier.bolum(LISTE_EN),
        )
    }
}

// MARK: - Kişiler ve Limitler

private fun LazyListScope.kisilerVeLimitler(
    store: CreditCenterStore,
    tamW: Dp,
    onEdit: (CreditPeople.Person) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "kisi-ay", contentType = "ay") {
        val months = remember { CreditDates.lastMonths(12, CreditDates.istanbul) }
        Row(Modifier.bolum(OZET_EN), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CcMenu(months.map { m -> CcMenuItem(CreditDates.monthLabel(m)) { store.month = m } }) {
                CcFilterChip("calendar", "Ay: ${CreditDates.monthLabel(store.month ?: store.people?.month ?: months.firstOrNull() ?: "")}", active = true)
            }
        }
    }
    item(key = "kisi-ayNot", contentType = "not") {
        CcFootnote("Limitler takvim ayıyla sayılır; ay başında sıfırlanır.", Modifier.bolum(OZET_EN))
    }

    val data = store.people
    val error = store.peopleError
    if (data != null) {
        if (data.people.isEmpty() && data.unattributed == null) {
            item(key = "kisi-bos", contentType = "bos") { Box(Modifier.bolum(OZET_EN)) { CcEmptyNote("Bu ay kredi kullanan ya da limiti olan kişi yok.") } }
        }
        val sutun = if (icGenislik(tamW, OZET_EN) >= GENIS_ICERIK) 2 else 1
        if (sutun == 1) {
            data.people.forEach { person ->
                item(key = "kisiKarti-${person.id}", contentType = "kisi") {
                    CcPersonCard(person, canEdit = data.canManageLimits, onEdit = { onEdit(person) }, modifier = Modifier.bolum(OZET_EN))
                }
            }
        } else {
            data.people.chunked(sutun).forEach { satir ->
                item(key = "kisiSatiri-" + satir.joinToString("|") { it.id }, contentType = "kisiSatiri") {
                    EsitSatir(
                        satir, sutun, 16.dp,
                        { person, m -> CcPersonCard(person, data.canManageLimits, { onEdit(person) }, m, esitYukseklik = true) },
                        Modifier.bolum(OZET_EN),
                    )
                }
            }
        }
        data.unattributed?.let { un ->
            item(key = "kisi-atanmamis", contentType = "atanmamis") { Box(Modifier.bolum(OZET_EN)) { CcUnattributedRow(un) } }
        }
        if (data.nextCursor != null) {
            item(key = "kisi-daha", contentType = "daha") { Box(Modifier.bolum(OZET_EN)) { CcMoreButton(onMore) } }
        }
    } else if (error != null) {
        item(key = "kisi-hata", contentType = "hata") { Box(Modifier.bolum(OZET_EN)) { CcErrorCard("Kişi kullanımı yüklenemedi.", error, onRetry) } }
    } else if (CreditCenterStore.Parca.people !in store.gizli) {
        item(key = "kisi-yukleniyor", contentType = "yukleniyor") { Box(Modifier.bolum(OZET_EN)) { CcLoadingBlock() } }
    }
}

// MARK: - Kredi Yükle (yalnız `fin.credits`)

private fun LazyListScope.krediYukle(
    store: CreditCenterStore,
    credits: SmileCreditsStore,
    wallet: SmileWallet?,
    purchased: SmilePurchaseResult?,
    tamW: Dp,
    onBuy: (SmileCreditPack) -> Unit,
    onTerms: (CreditTransactionsPage.Row) -> Unit,
    onRetry: () -> Unit,
) {
    val w = wallet
    val error = credits.errorMessage
    if (w != null) {
        item(key = "yukle-cuzdan", contentType = "cuzdan") { Box(Modifier.bolum(LISTE_EN)) { CcWalletCard(w) } }
        if (purchased != null) {
            item(key = "yukle-eklendi", contentType = "banner") {
                Box(Modifier.animateItem().bolum(LISTE_EN)) {
                    CcBanner("checkmark.seal.fill", DS.Colors.tertiaryContainer, "${creditText(purchased.creditsGranted)} kredi eklendi.")
                }
            }
        }
        val m = w.thisMonth
        val tl = m?.purchasedTL
        if (m != null && tl != null) {
            item(key = "yukle-fatura", contentType = "not") {
                Text(
                    "${CreditDates.monthLabel(m.period)} faturasına yansıyacak: ${SmileCreditFormat.lira(tl)}",
                    Modifier.bolum(LISTE_EN), style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant,
                )
            }
        }
        item(key = "yukle-baslik", contentType = "baslik") { CcSectionHeader("KREDİ PAKETLERİ", Modifier.bolum(LISTE_EN)) }
        w.purchaseBlockedMessage?.let { msg ->
            item(key = "yukle-engel", contentType = "banner") {
                Box(Modifier.bolum(LISTE_EN)) { CcBanner("person.badge.key.fill", DS.Colors.orange400, msg) }
            }
        }
        // Paketler: telefonda tek sütun, genişledikçe 2–3 sütun (en az 300 dp hücre).
        val icW = icGenislik(tamW, LISTE_EN)
        val sutun = ((icW + 12.dp) / (300.dp + 12.dp)).toInt().coerceIn(1, 3)
        w.packs.chunked(sutun).forEach { satir ->
            item(key = "paketSatiri-" + satir.joinToString("|") { it.id }, contentType = "paketSatiri") {
                val buyable: (SmileCreditPack) -> Boolean = { p -> w.canPurchase && p.priceTL != null && !credits.isPurchasing }
                if (sutun == 1) {
                    val p = satir.first()
                    CcPackCard(p, enabled = buyable(p), onBuy = { onBuy(p) }, modifier = Modifier.bolum(LISTE_EN))
                } else {
                    EsitSatir(
                        satir, sutun, 12.dp,
                        { p, mod -> CcPackCard(p, enabled = buyable(p), onBuy = { onBuy(p) }, modifier = mod) },
                        Modifier.bolum(LISTE_EN),
                    )
                }
            }
        }
        item(key = "yukle-not", contentType = "not") {
            CcFootnote("Ödeme uygulamada alınmaz: paket bedeli ay sonunda kliniğinizin Dentifay faturasına yansır.", Modifier.bolum(LISTE_EN))
        }

        if (CreditCenterStore.Parca.purchases !in store.gizli) {
            item(key = "yukle-son", contentType = "sonYuklemeler") {
                CcCard("Son yüklemeler", null, Modifier.bolum(LISTE_EN)) {
                    val purchasesError = store.purchasesError
                    if (store.purchases.isEmpty() && purchasesError != null) {
                        Text(purchasesError, style = sys(12), color = DS.Colors.error)
                    } else if (store.purchases.isEmpty()) {
                        Text("Son bir yılda kredi yüklemesi yok.", style = sys(13), color = DS.Colors.onSurfaceVariant)
                    } else {
                        Column {
                            store.purchases.forEach { r -> CcLedgerRow(r, showTerms = true, onTerms = { onTerms(r) }) }
                        }
                    }
                }
            }
        }
    } else if (error != null) {
        item(key = "yukle-hata", contentType = "hata") { Box(Modifier.bolum(LISTE_EN)) { CcErrorCard("Kredi bilgileri yüklenemedi.", error, onRetry) } }
    } else if (!credits.yetkisiz) {   // kodsuz 403 → parça çizilmez (M-E7.5)
        item(key = "yukle-yukleniyor", contentType = "yukleniyor") { Box(Modifier.bolum(LISTE_EN)) { CcLoadingBlock() } }
    }
}

// MARK: - Önizleme

@Composable
private fun OrnekKrediYonetimi(section: CreditCenterSection) {
    val store = remember {
        SmileCreditsStore.shared.ornekYukle(CreditPreviewData.wallet)
        KrediTarifesiStore.shared.ornekYukle(CreditPreviewData.tarife)
        CreditCenterStore().apply {
            ornekYukle(
                CreditPreviewData.usage, CreditPreviewData.analysis, CreditPreviewData.settings,
                CreditPreviewData.eBelge, CreditPreviewData.rows, CreditPreviewData.people,
            )
        }
    }
    CreditCenterContent(store, onDismiss = {}, baslangic = section)
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterOverviewPreview() {
    PreviewEnv { OrnekKrediYonetimi(CreditCenterSection.overview) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun CreditCenterOverviewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { OrnekKrediYonetimi(CreditCenterSection.overview) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterLedgerPreview() {
    PreviewEnv { OrnekKrediYonetimi(CreditCenterSection.ledger) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun CreditCenterPeopleTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { OrnekKrediYonetimi(CreditCenterSection.people) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CreditCenterTopUpPreview() {
    PreviewEnv { OrnekKrediYonetimi(CreditCenterSection.topUp) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun CreditCenterTopUpTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) { OrnekKrediYonetimi(CreditCenterSection.topUp) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun CreditCenterSmallPhonePreview() {
    PreviewEnv(pencere = PencereBilgisi(360.dp, 640.dp)) { OrnekKrediYonetimi(CreditCenterSection.people) }
}
