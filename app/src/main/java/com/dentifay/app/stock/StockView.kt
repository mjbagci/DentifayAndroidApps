package com.dentifay.app.stock

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.data.StockItem
import com.dentifay.app.data.StockStatus
import com.dentifay.app.notifications.ZilDugmesi
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.PageHeader
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.lowerTr
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.openInBrowser
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ProfilDugmesi
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import kotlin.math.min

/** Stok süzgeci (Swift `StockView.StockFilter`). */
sealed class StockFilter {
    data object all : StockFilter()
    data object attention : StockFilter()
    data class category(val name: String) : StockFilter()
}

/** Saf süzgeç ve düzen kuralları (Swift `categories` · `filteredItems`) — test edilebilir. */
internal object StokSuzgeci {
    private val tr: Locale = Locale.forLanguageTag("tr-TR")

    /** İki sütun için gereken en dar alan: 2 × 360 dp kart + 12 dp ara + 2 × 16 dp kenar. */
    val IKI_SUTUN_ESIGI: Dp = 764.dp

    /** Verideki kategoriler (alfabetik — Türkçe sıralama, boşlar hariç). */
    fun kategoriler(items: List<StockItem>, collator: Collator = Collator.getInstance(tr)): List<String> =
        items.map { it.category }.filter { it.isNotEmpty() }.toSet().sortedWith(collator)

    /** Süzgeç + ad / barkod / SKU / kategori araması (büyük-küçük harf duyarsız). */
    fun suz(items: List<StockItem>, filter: StockFilter, arama: String): List<StockItem> {
        val suzulen = when (filter) {
            StockFilter.all -> items
            StockFilter.attention -> items.filter { it.status.needsAttention }
            is StockFilter.category -> items.filter { it.category == filter.name }
        }
        val q = katla(arama.trim())
        if (q.isEmpty()) return suzulen
        return suzulen.filter {
            katla(it.name).contains(q) || katla(it.barcode).contains(q) ||
                katla(it.sku).contains(q) || katla(it.category).contains(q)
        }
    }

    /** Aramada harf katlama: Türkçe küçük harf + "ı" → "i" — "imp" hem "İmplant"ı hem Latin SKU "IMP-4"ü bulur. */
    private fun katla(s: String): String = s.lowerTr().replace('ı', 'i')

    /** Telefonda tek sütun; geniş pencerede (tablet yatay, büyük ekran) iki sütun. */
    fun sutunSayisi(genislik: Dp): Int = if (genislik >= IKI_SUTUN_ESIGI) 2 else 1
}

/**
 * Stok — web "Stok Yönetimi" verisi (`/inventory/items`): özet sayılar, kategori süzgeci, ad / barkod / SKU araması ve barkod
 * tarama. Ürün ekleme ve düzenleme web panelinde (paketin web erişimi yoksa bağlantı gizli — M-E8.6).
 * Liste tembel ızgaradır: telefonda tek sütun (640 dp), geniş pencerede iki sütun (1040 dp, ortalı).
 */
@Composable
fun StockView(onShowNotifications: () -> Unit) {
    val dataManager = LocalDataManager.current
    val auth = LocalAuthManager.current
    val pencere = LocalPencere.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val onizleme = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<StockFilter>(StockFilter.all) }
    var showScanner by remember { mutableStateOf(false) }
    var scannerUnavailable by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    // Web paneli (Custom Tab) kapanıp uygulamaya dönülünce liste yenilenir.
    var webPageOpen by remember { mutableStateOf(false) }
    /** Ürün ekleme web'dedir — paketin web erişimi yoksa bağlantı hiç çizilmez (M-E8.6; alan yoksa `true`). */
    val webErisimi = auth.currentUser?.webErisimi ?: true

    LaunchedEffect(Unit) { if (!onizleme) dataManager.fetchInventory() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && webPageOpen) {
                webPageOpen = false
                // Web panelinde ürün eklenmiş / düzenlenmiş olabilir.
                scope.launch { dataManager.fetchInventory() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showScanner = true else scannerUnavailable = true
    }

    fun openScanner() {
        Haptics.light()
        when {
            !BarcodeScannerSheet.isAvailable(context) -> scannerUnavailable = true
            BarcodeScannerSheet.hasPermission(context) -> showScanner = true
            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun openAddProduct() {
        Haptics.light()
        webPageOpen = true
        openInBrowser(context, AppConfig.webBaseURL + "/inventory")
    }

    // MARK: Veri — yalnız girdiler değişince yeniden hesaplanır.
    val allItems = dataManager.stockItems
    val categories = remember(allItems) { StokSuzgeci.kategoriler(allItems) }
    val attentionCount = remember(allItems) { allItems.count { it.status.needsAttention } }
    val filteredItems = remember(allItems, filter, searchText) { StokSuzgeci.suz(allItems, filter, searchText) }

    // Kaydırınca klavye hemen kapanır (`.scrollDismissesKeyboard(.immediately)`).
    val dismissKeyboard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

    // Telefonda alt çubuk için 120 (Swift); geniş pencerede yan ray — altta yalnız sistem çubuğu + boşluk.
    val altBosluk = if (pencere.genis) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp else 120.dp

    val refreshState = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    dataManager.fetchInventory()
                    isRefreshing = false
                }
            },
            state = refreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refreshState, isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                    containerColor = DS.Colors.surfaceContainerHigh, color = DS.Colors.cyan400,
                )
            },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val sutun = StokSuzgeci.sutunSayisi(maxWidth)
                val icerik = if (sutun > 1) 1040.dp else 640.dp
                // Izgara tam genişlikte kalır (kenarlardan da kaydırılır); içerik `icerik` genişliğinde ortalanır.
                val kenar = ((maxWidth - icerik) / 2).coerceAtLeast(0.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(sutun),
                    modifier = Modifier.fillMaxSize().nestedScroll(dismissKeyboard).statusBarsPadding(),
                    contentPadding = PaddingValues(start = kenar, end = kenar, bottom = altBosluk),
                ) {
                    tamSatir("header") {
                        StockHeader(onShowNotifications, Modifier.appear(0.0))
                    }
                    tamSatir("summary") {
                        val stats = dataManager.stockStats
                        SummaryStrip(
                            total = stats?.totalItems ?: allItems.size,
                            low = stats?.lowStock ?: allItems.count { it.status == StockStatus.low },
                            out = stats?.outOfStock ?: allItems.count { it.status == StockStatus.out },
                            redacted = dataManager.isLoadingStock && !dataManager.hasLoadedStock && allItems.isEmpty(),
                            modifier = Modifier.padding(top = 18.dp).appear(0.04),
                        )
                    }
                    tamSatir("search") {
                        SearchAndScanBar(
                            searchText = searchText,
                            onSearchChange = { searchText = it },
                            onScan = { openScanner() },
                            modifier = Modifier.padding(top = 18.dp).appear(0.08),
                        )
                    }
                    if (allItems.isNotEmpty()) {
                        tamSatir("chips") {
                            FilterChips(
                                allCount = allItems.size,
                                attentionCount = attentionCount,
                                categories = categories,
                                filter = filter,
                                onFilter = { filter = it },
                                modifier = Modifier.padding(top = 18.dp).appear(0.12),
                            )
                        }
                    }

                    // MARK: Liste
                    when {
                        dataManager.isLoadingStock && allItems.isEmpty() && !dataManager.hasLoadedStock -> tamSatir("loading") {
                            Box(Modifier.fillMaxWidth().padding(top = 18.dp + 50.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), color = DS.Colors.cyan400, strokeWidth = 2.5.dp)
                            }
                        }
                        allItems.isEmpty() && dataManager.stockLoadFailed -> tamSatir("failed") {
                            StateView(
                                "exclamationmark.triangle", "Stok listesi yüklenemedi",
                                "Bağlantınızı kontrol edip tekrar deneyin.", Modifier.padding(top = 18.dp),
                            ) {
                                SecondaryButton(onClick = { scope.launch { dataManager.fetchInventory() } }, modifier = Modifier.width(180.dp)) {
                                    ButtonLabel("Tekrar dene")
                                }
                            }
                        }
                        allItems.isEmpty() -> tamSatir("empty") {
                            StateView(
                                "shippingbox", "Henüz stok kaydı yok",
                                "Ürünleri web panelindeki Stok Yönetimi'nden ekleyebilirsiniz.", Modifier.padding(top = 18.dp),
                            ) {
                                if (webErisimi) AddProductButton(Modifier.fillMaxWidth()) { openAddProduct() }
                            }
                        }
                        filteredItems.isEmpty() -> tamSatir("noMatch") {
                            StateView(
                                "magnifyingglass", "Eşleşen ürün yok",
                                if (searchText.isEmpty()) "Bu süzgeçte ürün bulunmuyor." else "“$searchText” için sonuç bulunamadı.",
                                Modifier.padding(top = 18.dp),
                            ) {
                                SecondaryButton(onClick = { searchText = ""; filter = StockFilter.all }, modifier = Modifier.width(200.dp)) {
                                    ButtonLabel("Süzgeci temizle")
                                }
                            }
                        }
                        else -> {
                            itemsIndexed(filteredItems, key = { _, item -> item.id }, contentType = { _, _ -> "stok" }) { index, item ->
                                // Dış kenar 16 dp, sütunlar arası 12 dp (Swift `LazyVStack(spacing: 10)` + `.padding(.horizontal, 16)`).
                                val sira = index % sutun
                                StockItemCard(
                                    item,
                                    Modifier
                                        .padding(
                                            start = if (sira == 0) 16.dp else 6.dp,
                                            end = if (sira == sutun - 1) 16.dp else 6.dp,
                                            top = if (index < sutun) 18.dp else 10.dp,
                                        )
                                        .appear(0.14 + min(index, 8) * 0.04),
                                    esitYukseklik = sutun > 1,
                                )
                            }
                            if (webErisimi) {
                                tamSatir("add") {
                                    AddProductButton(Modifier.padding(top = 14.dp).padding(horizontal = 16.dp)) { openAddProduct() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DfySheet(isPresented = showScanner, onDismiss = { showScanner = false }, containerColor = Color.Black, showDragHandle = false) { dismiss ->
        BarcodeScannerSheet(
            onScan = { code ->
                searchText = code
                filter = StockFilter.all
            },
            onDismiss = dismiss,
        )
    }

    DfyAlert(
        isPresented = scannerUnavailable,
        title = "Barkod tarama kullanılamıyor",
        message = "Bu cihazda kamera ile tarama desteklenmiyor ya da kamera izni kapalı. Barkodu arama alanına yazabilirsiniz.",
        confirmText = "Tamam",
        onConfirm = { scannerUnavailable = false },
        onDismiss = { scannerUnavailable = false },
    )
}

/** Izgarada tam satır kaplayan bölüm (başlık, özet, arama, süzgeç, durumlar). */
private fun LazyGridScope.tamSatir(key: String, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key, content = content)
}

// MARK: - Başlık

@Composable
private fun StockHeader(onShowNotifications: () -> Unit, modifier: Modifier = Modifier) {
    PageHeader(
        title = "Stok Yönetimi",
        subtitle = "Malzeme ve ürün takibi, stok hareketleri",
        modifier = modifier.padding(top = 8.dp).padding(horizontal = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZilDugmesi(action = onShowNotifications)
            ProfilDugmesi()
        }
    }
}

// MARK: - Özet (web özet kartları)

@Composable
private fun SummaryStrip(total: Int, low: Int, out: Int, redacted: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().glassCard(14.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryCell(total, "Toplam Ürün", DS.Colors.onSurface, redacted, Modifier.weight(1f))
            Box(Modifier.size(1.dp, 30.dp).background(DS.Colors.hairline))
            SummaryCell(low, "Düşük Stok", if (low > 0) DS.Colors.warning else DS.Colors.onSurface, redacted, Modifier.weight(1f))
            Box(Modifier.size(1.dp, 30.dp).background(DS.Colors.hairline))
            SummaryCell(out, "Tükenen", if (out > 0) DS.Colors.danger else DS.Colors.onSurface, redacted, Modifier.weight(1f))
        }
    }
}

/** `.redacted(reason: .placeholder)` → metin yerine gri yer tutucu. */
private fun Modifier.placeholder(on: Boolean): Modifier =
    if (on) this.clip(RoundedCornerShape(4.dp)).background(DS.Colors.onSurface.copy(alpha = 0.12f)) else this

@Composable
private fun SummaryCell(value: Int, label: String, color: Color, redacted: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "$value", Modifier.placeholder(redacted),
            style = sys(19, FontWeight.Bold).monoDigits(), color = if (redacted) Color.Transparent else color, maxLines = 1,
        )
        Text(
            label, Modifier.placeholder(redacted).padding(horizontal = 4.dp),
            style = sys(12, FontWeight.Medium), color = if (redacted) Color.Transparent else DS.Colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Arama ve tarama

@Composable
private fun SearchAndScanBar(searchText: String, onSearchChange: (String) -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val fieldShape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .weight(1f)
                .height(46.dp)
                .clip(fieldShape)
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, DS.Colors.hairlineStrong, fieldShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DfyIcon("magnifyingglass", tint = DS.Colors.outline)
            BasicTextField(
                value = searchText,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = sys(15).copy(color = DS.Colors.onSurface),
                cursorBrush = SolidColor(DS.Colors.cyan400),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchText.isEmpty()) {
                            Text("Ad, barkod ya da SKU", style = sys(15), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            if (searchText.isNotEmpty()) {
                DfyIcon(
                    "xmark.circle.fill", tint = DS.Colors.outline,
                    modifier = Modifier
                        .semantics { contentDescription = "Aramayı temizle" }
                        .plainClickable { onSearchChange("") },
                )
            }
        }

        Row(
            Modifier
                .height(46.dp)
                .semantics { contentDescription = "Barkod tara" }
                .pressable(onClick = onScan)
                .clip(RoundedCornerShape(12.dp))
                .background(DS.Gradients.primaryGradient)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DfyIcon("barcode.viewfinder", size = 17.dp, tint = Color.White)
            Text("Tara", style = sys(14, FontWeight.SemiBold), color = Color.White, maxLines = 1)
        }
    }
}

// MARK: - Süzgeç

/**
 * Süzgeç çipleri — seçili kapsül çipler arasında kayar (`matchedGeometryEffect`). Kapsül çizim aşamasında çizilir: kayma
 * animasyonu yeniden oluşturma/yerleşim tetiklemez.
 */
@Composable
private fun FilterChips(
    allCount: Int,
    attentionCount: Int,
    categories: List<String>,
    filter: StockFilter,
    onFilter: (StockFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Her çipin satır içindeki konumu/genişliği (px) — yalnız yerleşim değişince yazılır (kaydırmada değil).
    val bounds = remember { mutableStateMapOf<StockFilter, Pair<Float, Float>>() }
    val indicatorX = remember { Animatable(0f) }
    val indicatorW = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val selectedBounds = bounds[filter]
    val selectedTint = if (filter == StockFilter.attention) DS.Colors.warning else DS.Colors.cyan400
    val tint = animateColorAsState(selectedTint, Motion.spring(0.32, 0.8), label = "chipTint")

    LaunchedEffect(selectedBounds) {
        val b = selectedBounds ?: return@LaunchedEffect
        if (!placed) {
            indicatorX.snapTo(b.first)
            indicatorW.snapTo(b.second)
            placed = true
        } else {
            launch { indicatorX.animateTo(b.first, Motion.spring(0.32, 0.8)) }
            launch { indicatorW.animateTo(b.second, Motion.spring(0.32, 0.8)) }
        }
    }

    fun chipTap(value: StockFilter) {
        Haptics.selection()
        val on = filter == value
        onFilter(if (on && value != StockFilter.all) StockFilter.all else value)
    }

    val kapsulVar = selectedBounds != null && placed
    Box(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(
            Modifier.drawBehind {
                if (!kapsulVar) return@drawBehind
                val h = 34.dp.toPx()
                val y = (size.height - h) / 2f
                val w = indicatorW.value
                val x = indicatorX.value
                val c = tint.value
                drawRoundRect(c.copy(alpha = 0.12f), topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(h / 2f))
                val sw = 1.dp.toPx()
                drawRoundRect(
                    c.copy(alpha = 0.45f),
                    topLeft = Offset(x + sw / 2f, y + sw / 2f),
                    size = Size(w - sw, h - sw),
                    cornerRadius = CornerRadius((h - sw) / 2f),
                    style = Stroke(width = sw),
                )
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipEntry("Tümü", allCount, StockFilter.all, filter, DS.Colors.cyan400, bounds, ::chipTap)
            if (attentionCount > 0) ChipEntry("Dikkat", attentionCount, StockFilter.attention, filter, DS.Colors.warning, bounds, ::chipTap)
            if (categories.isNotEmpty()) {
                Box(Modifier.size(1.dp, 22.dp).background(DS.Colors.hairlineStrong))
                categories.forEach { c -> ChipEntry(c, null, StockFilter.category(c), filter, DS.Colors.cyan400, bounds, ::chipTap) }
            }
        }
    }
}

/** Konumunu `bounds`'a yazan süzgeç çipi. */
@Composable
private fun ChipEntry(
    title: String,
    count: Int?,
    value: StockFilter,
    filter: StockFilter,
    tint: Color,
    bounds: MutableMap<StockFilter, Pair<Float, Float>>,
    onTap: (StockFilter) -> Unit,
) {
    key(value) {
        FilterChip(
            title, count, on = filter == value, tint = tint,
            modifier = Modifier.onPlaced { c ->
                bounds[value] = c.positionInParent().x to c.size.width.toFloat()
            },
        ) { onTap(value) }
    }
}

@Composable
private fun FilterChip(title: String, count: Int?, on: Boolean, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val spec = Motion.spring<Color>(0.32, 0.8)
    val fg by animateColorAsState(if (on) tint else DS.Colors.onSurfaceVariant, spec, label = "chipFg")
    val countColor by animateColorAsState(if (on) tint else DS.Colors.outline, spec, label = "chipCount")
    val idleStroke by animateColorAsState(if (on) Color.Transparent else Color.White.copy(alpha = 0.1f), spec, label = "chipStroke")
    val weight = if (on) FontWeight.SemiBold else FontWeight.Medium
    Row(
        modifier
            .height(34.dp)
            .border(1.dp, idleStroke, CircleShape)
            .plainClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = sys(13, weight), color = fg, maxLines = 1)
        if (count != null) Text("$count", style = sys(13, weight).monoDigits(), color = countColor, maxLines = 1)
    }
}

// MARK: - Durum görünümleri

@Composable
private fun StateView(icon: String, title: String, detail: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DfyIcon(icon, size = 28.dp, tint = DS.Colors.metal)
        Text(title, style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
        Text(detail, style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = 6.dp)) { action() }
    }
}

/** Ürün ekleme formu web panelinde (Stok Yönetimi → Yeni Ürün). */
@Composable
private fun AddProductButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.12f),
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .semantics { contentDescription = "Ürün ekle — web panelinde açılır" }
            .plainClickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("plus", size = 14.dp, tint = DS.Colors.onSurfaceVariant)
        Text("Ürün ekle", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, maxLines = 1)
        DfyIcon("arrow.up.right", size = 11.dp, tint = DS.Colors.outline)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun StockViewPreview() {
    PreviewEnv {
        StockView(onShowNotifications = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun StockViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        StockView(onShowNotifications = {})
    }
}
