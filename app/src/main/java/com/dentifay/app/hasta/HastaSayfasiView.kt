package com.dentifay.app.hasta

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.runtime.CompositionLocalProvider
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIUser
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.horizontalGradient
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.ORNEK_YETENEKLER
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.YetkiliButon
import com.dentifay.app.ui.YetkiliEylem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sayfa içeriğinin en büyük genişliği: şema 100 dp diş boyunda (web md) sığar, tablette ortalanır. */
private val ICERIK_AZAMI: Dp = 840.dp
private val TAM: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
private val KART_KOSE = 14.dp

/**
 * Hastanın sayfası (MOBIL_IS_EMRI M-E8.3) — yalnız Planlama · Yapılacak · Yapılan · Medya; görünüm web `PlanningTab` · `StageItemList` ·
 * `MediaTab` ile aynı (DentAIWeb @ afc3170). Bölüm, yeteneği yoksa çizilmez.
 *
 * Swift `HastaSayfasiView(hasta:)` + gezinme çubuğu: `onDismiss` verilirse üst çubukta geri düğmesi (listeden açıldığında / pencerede);
 * tablette liste–ayrıntı düzeninde verilmez. Diğer paketler (randevu ayrıntısı "Hasta kartı", bildirimler) bunu çağırır.
 */
@Composable
fun HastaSayfasiView(hasta: HastaBasligi, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val model = remember(hasta.id) { HastaSayfasiModeli(hasta) }
    HastaSayfasiIcerigi(model, onDismiss, modifier)
}

/** Kalem işlemleri penceresi isteği (Swift `kalemIslemi` / `islemler` + `KalemKumesi`). */
private class KalemIslemiIstegi(
    val kalemler: List<TedaviKalemi>,
    val planHekimi: String?,
    /** Aşama listesinden açıldı: pencere kapanınca seçim temizlenir. */
    val secimiTemizle: Boolean,
)

/**
 * Sayfanın kendisi — modeli dışarıdan alır (Tedavi Planı sekmesi seçili hastanın modelini liste–ayrıntı düzenleri arasında korur).
 * Modeli alt ağaca (sayfanın açtığı pencerelere) `LocalHastaSayfasiModeli` olarak sağlar.
 */
@Composable
internal fun HastaSayfasiIcerigi(model: HastaSayfasiModeli, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier) {
    HastaSayfasiOrtami(model) { SayfaGovdesi(model, onDismiss, modifier) }
}

@Composable
private fun SayfaGovdesi(model: HastaSayfasiModeli, onDismiss: (() -> Unit)?, modifier: Modifier) {
    val yetenekler = LocalAuthManager.current.yetenekler
    val kapsam = rememberCoroutineScope()
    val bolumler = remember(yetenekler) { HastaSayfasiModeli.Bolum.entries.filter { it.yetenek in yetenekler } }

    // Swift `onAppear` + `onChange(of: bolum)`: yetkisiz bölüm seçiliyse ilk yetkili bölüme; seçili bölüm (ve açık kartlar) yüklenir.
    LaunchedEffect(model, bolumler) {
        if (model.bolum !in bolumler) bolumler.firstOrNull()?.let { model.bolum = it }
    }
    LaunchedEffect(model, model.bolum, bolumler) {
        if (model.bolum in bolumler) model.bolumuYukle(model.bolum)
        for (id in model.acikPlanlar) model.ayrintiyiYukle(id)
    }
    // Swift `onDisappear`: uçuştaki istekler iptal.
    DisposableEffect(model) { onDispose { model.iptalEt() } }

    // Pencereler sayfa düzeyinde (kartlar tembel ızgarada kayınca kapanmasınlar).
    var yeniPlan by remember { mutableStateOf(false) }
    var eklenecekPlan by remember { mutableStateOf<HastaPlani?>(null) }
    var duzenlenecekPlan by remember { mutableStateOf<HastaPlani?>(null) }
    var islemPlani by remember { mutableStateOf<HastaPlani?>(null) }
    var kalemIslemi by remember { mutableStateOf<KalemIslemiIstegi?>(null) }
    var dogrudanAsama by remember { mutableStateOf<String?>(null) }
    val secim = remember(model, model.bolum) { mutableStateOf<Set<String>>(emptySet()) }
    val medya = remember(model) { MedyaDurumu() }
    val planBellegi = remember(model) { PlanBellegi() }
    val asamaBellegi = remember(model) { AsamaBellegi() }
    val izgara = rememberLazyGridState()
    var yenileniyor by remember { mutableStateOf(false) }

    // Aşama listesi ve seçim çubuğu verisi.
    val asama = model.bolum.asama
    val asamaKalemleri = asama?.let { a -> model.asamaDurumu(a).let { d -> if (d is HastaSayfasiModeli.Durum.hazir) d.deger else null } }
    val secilenler = remember(asamaKalemleri, secim.value) { asamaKalemleri?.filter { it.id in secim.value } ?: emptyList() }
    val secimCubugu = asama != null && secilenler.isNotEmpty()

    // Medya: süzülmüş liste (grup değişince ya da yeni sayfa gelince).
    val medyaDurumu = model.medya
    val medyaListesi = remember(medyaDurumu, medya.grup) {
        if (medyaDurumu is HastaSayfasiModeli.Durum.hazir) MedyaDurumu.suz(medyaDurumu.deger.media, medya.grup) else emptyList()
    }

    // Sabit seçici görünürken bölüm değişirse seçici üstte kalsın (yeni bölüm baştan görünür).
    LaunchedEffect(model.bolum) { if (izgara.firstVisibleItemIndex > 1) izgara.scrollToItem(1) }

    Box(modifier.fillMaxSize()) {
        WebBackdrop()
        Column(Modifier.fillMaxSize()) {
            SayfaUstCubugu(model.hasta.ad, onDismiss)
            val yenileDurumu = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = yenileniyor,
                onRefresh = {
                    kapsam.launch {
                        yenileniyor = true
                        try {
                            model.tazeleVeBekle()
                        } finally {
                            yenileniyor = false
                        }
                    }
                },
                state = yenileDurumu,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = yenileDurumu, isRefreshing = yenileniyor, modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = DS.Colors.surfaceContainerHigh, color = DS.Colors.cyan400,
                    )
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val yatay = max(16.dp, (maxWidth - ICERIK_AZAMI) / 2)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        state = izgara,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = yatay, end = yatay, bottom = if (secimCubugu) 108.dp else 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "baslik", span = TAM, contentType = "baslik") { HastaBasligiSatiri(model.hasta) }
                        if (bolumler.isNotEmpty()) {
                            item(key = "secici", span = TAM, contentType = "secici") {
                                BolumSecici(bolumler, model.bolum, Modifier.padding(top = 12.dp)) { model.bolum = it }
                            }
                            if (model.bolum in bolumler) when (model.bolum) {
                                HastaSayfasiModeli.Bolum.planlama -> planlamaBolumu(
                                    model = model,
                                    bellek = planBellegi,
                                    yeniPlan = { yeniPlan = true },
                                    planaEkle = { eklenecekPlan = it },
                                    planIslemleri = { islemPlani = it },
                                    kalemIslemi = { k, p -> kalemIslemi = KalemIslemiIstegi(listOf(k), p.doctorUserId ?: p.doctor?.id, false) },
                                )
                                HastaSayfasiModeli.Bolum.yapilacak, HastaSayfasiModeli.Bolum.yapilan -> asamaBolumu(
                                    asama = model.bolum.asama ?: "TODO",
                                    durum = model.asamaDurumu(model.bolum.asama ?: "TODO"),
                                    bellek = asamaBellegi,
                                    secim = secim,
                                    ekle = { dogrudanAsama = model.bolum.asama },
                                    islemler = { kalemIslemi = KalemIslemiIstegi(it, null, true) },
                                )
                                HastaSayfasiModeli.Bolum.medya -> medyaBolumu(
                                    model = model, durum = medya, medya = medyaDurumu, liste = medyaListesi,
                                    devamYukleniyor = model.medyaDahaYukleniyor, kapsam = kapsam,
                                )
                            }
                        }
                    }
                    // Swift `pinnedViews: [.sectionHeaders]`: seçici kayıp gidince üstte sabit kopyası.
                    val sabitSecici by remember {
                        derivedStateOf { izgara.firstVisibleItemIndex > 1 || (izgara.firstVisibleItemIndex == 1 && izgara.firstVisibleItemScrollOffset > 0) }
                    }
                    if (sabitSecici && bolumler.isNotEmpty()) {
                        BolumSecici(bolumler, model.bolum, Modifier.align(Alignment.TopCenter).padding(horizontal = yatay)) { model.bolum = it }
                    }
                }
            }
        }

        // Seçim çubuğu (web S5/S6) — listenin sonunda değil, sayfanın altında sabit: uzun listede de görünür.
        AnimatedVisibility(
            visible = secimCubugu,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 2 },
        ) {
            val renk = asamaRengi(asama)
            SecimCubugu(
                renk = renk,
                adet = secilenler.size,
                toplam = secilenler.sumOf { it.totalAmountTRY?.deger ?: 0.0 },
                temizle = { secim.value = emptySet() },
                islemler = { kalemIslemi = KalemIslemiIstegi(secilenler, null, true) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .widthIn(max = 720.dp),
            )
        }
    }

    // MARK: Pencereler (plan penceresi, işlemler, yükleme — Swift `.sheet`ler)

    DfySheet(isPresented = yeniPlan, onDismiss = { yeniPlan = false }) { kapat ->
        TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.yeniPlan, onDismiss = kapat)
    }
    DfySheet(isPresented = eklenecekPlan != null, onDismiss = { eklenecekPlan = null }) { kapat ->
        eklenecekPlan?.let { TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.planaEkle(it), onDismiss = kapat) }
    }
    DfySheet(isPresented = duzenlenecekPlan != null, onDismiss = { duzenlenecekPlan = null }) { kapat ->
        duzenlenecekPlan?.let { TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.duzenle(it), onDismiss = kapat) }
    }
    DfySheet(isPresented = dogrudanAsama != null, onDismiss = { dogrudanAsama = null }) { kapat ->
        dogrudanAsama?.let { TedaviEditoruSheet(kip = TedaviEditoruSheet.Kip.dogrudan(asama = it), onDismiss = kapat) }
    }
    DfySheet(isPresented = islemPlani != null, onDismiss = { islemPlani = null }) { kapat ->
        islemPlani?.let { p ->
            PlanIslemleriSheet(
                plan = p,
                // Swift: işlemler penceresi kapanınca (400 ms) "Planı düzenle" penceresi açılır.
                onDuzenle = {
                    kapsam.launch {
                        delay(400)
                        duzenlenecekPlan = model.ayrintilar[p.id] ?: p
                    }
                },
                onDismiss = kapat,
            )
        }
    }
    DfySheet(
        isPresented = kalemIslemi != null,
        onDismiss = {
            if (kalemIslemi?.secimiTemizle == true) secim.value = emptySet()
            kalemIslemi = null
        },
    ) { kapat ->
        kalemIslemi?.let { istek ->
            KalemIslemleriSheet(kalemler = istek.kalemler, onDismiss = kapat, planHekimi = { istek.planHekimi })
        }
    }
    DfySheet(isPresented = medya.yukle, onDismiss = { medya.yukle = false }) { kapat -> MedyaYuklemeSheet(onDismiss = kapat) }

    val silinecek = medya.silinecek
    IslemOnayi(
        acik = silinecek != null,
        baslik = "Görsel silinsin mi?",
        secenekler = listOf(
            OnaySecenegi("Çöp kutusuna taşı", yikici = true) { if (silinecek != null) kapsam.launch { medya.sil(model, silinecek) } },
        ),
        kapat = { medya.silinecek = null },
    )

    // Tam ekran görüntüleyici (kapak katmanı sayfanın dışında çizer → modeli yeniden sağla). Kapanırken son görsel kalır.
    val sonAcik = remember { mutableStateOf<HastaMedyasi?>(null) }
    LaunchedEffect(medya.acik) { medya.acik?.let { sonAcik.value = it } }
    FullScreenCover(isPresented = medya.acik != null, onDismiss = { medya.acik = null }, background = Color.Black) {
        val m = medya.acik ?: sonAcik.value
        if (m != null) HastaSayfasiOrtami(model) { MedyaGoruntuleyici(m, onDismiss = { medya.acik = null }) }
    }
}

// MARK: - Üst çubuk · başlık · bölüm seçici

/** Swift `.navigationTitle(hasta.ad)` (satır içi) + geri düğmesi + `toolbarBackground(surfaceContainer)`. */
@Composable
private fun SayfaUstCubugu(baslik: String, onGeri: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(DS.Colors.surfaceContainer.copy(alpha = 0.96f)).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp)) {
            if (onGeri != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .semantics { contentDescription = "Geri" }
                        .plainClickable(onClick = onGeri),
                    contentAlignment = Alignment.Center,
                ) { DfyIcon("chevron.left", size = 17.dp, tint = DS.Colors.onSurface) }
            }
            Text(
                baslik,
                Modifier.align(Alignment.Center).padding(horizontal = 52.dp, vertical = 6.dp),
                style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
    }
}

/** Başlık: baş harfler + ad + "Dosya {no} · {telefon}". */
@Composable
private fun HastaBasligiSatiri(hasta: HastaBasligi) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(DS.Colors.cyan400.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text(basHarfler(hasta.ad), style = sys(16, FontWeight.Bold), color = DS.Colors.cyan400, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(hasta.ad, style = sys(18, FontWeight.Bold), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val alt = listOfNotNull(hasta.dosyaNo?.let { "Dosya $it" }, hasta.telefon).filter { it.isNotEmpty() }.joinToString(" · ")
            if (alt.isNotEmpty()) Text(alt, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Swift `Picker(.segmented)` + `background(DS.Colors.background.opacity(0.92))` (sabitlenince altındaki içeriği örter). */
@Composable
private fun BolumSecici(
    bolumler: List<HastaSayfasiModeli.Bolum>,
    secili: HastaSayfasiModeli.Bolum,
    modifier: Modifier = Modifier,
    sec: (HastaSayfasiModeli.Bolum) -> Unit,
) {
    Box(modifier.fillMaxWidth().background(DS.Colors.background.copy(alpha = 0.92f)).padding(vertical = 8.dp)) {
        IslemSegmenti(
            secenekler = bolumler.map { it.baslik },
            secili = bolumler.indexOf(secili).coerceAtLeast(0),
            sec = { i -> bolumler.getOrNull(i)?.let(sec) },
            modifier = Modifier.semantics { contentDescription = "Bölüm" },
        )
    }
}

// MARK: - Ortak parçalar

/** Durum hapı (web StatusChip / TransitionBadge biçimi). Başka pencereler de kullanır (ödeme planı listesi). */
@Composable
fun DurumHapi(metin: String, renk: Color, modifier: Modifier = Modifier) {
    val sekil = RoundedCornerShape(50)
    Text(
        metin.upperTr(),
        modifier
            .clip(sekil)
            .background(renk.copy(alpha = 0.10f))
            .border(1.dp, renk.copy(alpha = 0.30f), sekil)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        style = sys(10, FontWeight.SemiBold), color = renk, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

object PlanRengi {
    /** Web StatusChip renkleri (koyu tema -300 tonları). */
    fun durum(s: String?): Color = when (s) {
        "PROPOSED" -> hex("67E8F9")
        "APPROVED", "COMPLETED" -> hex("6EE7B7")
        "REJECTED", "CANCELLED" -> hex("FDA4AF")
        "IN_PROGRESS" -> hex("D8B4FE")
        else -> hex("CBD5E1")
    }

    /** Web TransitionBadge. */
    fun gecis(kaynak: String?, asama: String?): Pair<String, Color> {
        if (kaynak == "REVERTED") {
            return when (asama) {
                "PLANNED" -> "Planlamaya Geri Alındı" to hex("FDA4AF")
                "TODO" -> "Yapılacağa Geri Alındı" to hex("FCD34D")
                else -> "Yapılana Geri Alındı" to hex("6EE7B7")
            }
        }
        if (kaynak == null || kaynak == "CREATED_DIRECTLY") return "Direkt Eklendi" to hex("CBD5E1")
        return when (asama) {
            "TODO" -> "Yapılacağa Aktarıldı" to hex("67E8F9")
            "DONE" -> "Yapılana Aktarıldı" to hex("6EE7B7")
            else -> "Planlamaya Eklendi" to hex("CBD5E1")
        }
    }

    fun onam(etiket: String?): Pair<String, Color> = when (etiket) {
        "Var" -> "Onam Var" to hex("6EE7B7")
        "Bekliyor" -> "Onam Bekliyor" to hex("FCD34D")
        else -> "Onam Yok" to DS.Colors.onSurfaceVariant
    }
}

@Composable
fun YukleniyorBlogu(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { IslemIlerleme(boyut = 22.dp) }
}

@Composable
fun BosNot(metin: String, modifier: Modifier = Modifier) {
    Text(
        metin, modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 8.dp),
        style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

/** Satırın ⋮ düğmesi ("Tedavi işlemleri"). */
@Composable
fun IslemDugmesi(eylem: () -> Unit, modifier: Modifier = Modifier, aciklama: String = "Tedavi işlemleri") {
    Box(
        modifier
            .size(width = 30.dp, height = 26.dp)
            .semantics { contentDescription = aciklama }
            .plainClickable(onClick = eylem),
        contentAlignment = Alignment.Center,
    ) { DfyIcon("ellipsis", size = 14.dp, tint = DS.Colors.onSurfaceVariant) }
}

/** Seçili kalem kümesi (sayfa öğesi). */
data class KalemKumesi(val kalemler: List<TedaviKalemi>) {
    val id: String get() = kalemler.joinToString(",") { it.id }
}

/** Swift `Label(metin, dfy: ikon)` — küçük ikon + tek satır metin (sığmazsa kırpılır). */
@Composable
private fun Etiket(metin: String, ikon: String, modifier: Modifier = Modifier, boyut: Float = 11f, renk: Color = DS.Colors.onSurfaceVariant) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DfyIcon(ikon, size = boyut.dp, tint = renk)
        Text(metin, Modifier.weight(1f, fill = false), style = sys(boyut), color = renk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Konum rozeti ("11, 12, 13" · "Üst çene" · "—"). */
@Composable
private fun KonumRozeti(metin: String) {
    Text(
        metin,
        Modifier
            .widthIn(max = 132.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = sys(12, FontWeight.Bold).monoDigits(), color = DS.Colors.onSurface,
    )
}

private fun virgullu(x: Double, basamak: Int): String = String.format(Locale.US, "%.${basamak}f", x).replace('.', ',')

private fun asamaRengi(asama: String?): Color = if (asama == "DONE") hex("6EE7B7") else hex("FCD34D")

// MARK: - Kart parçaları (tembel ızgarada kart birden çok öğeye bölünür; görünüm tek kart)

internal enum class KartParcasi { tek, ust, orta, alt }

private val KART_ZEMINI = hex("141823").copy(alpha = 0.92f)
private val KART_ISIGI = Color.White.copy(alpha = 0.03f)
private val KART_KENARI = Color.White.copy(alpha = 0.08f)

private fun kartSekli(p: KartParcasi): Shape = when (p) {
    KartParcasi.tek -> RoundedCornerShape(KART_KOSE)
    KartParcasi.ust -> RoundedCornerShape(topStart = KART_KOSE, topEnd = KART_KOSE)
    KartParcasi.orta -> RectangleShape
    KartParcasi.alt -> RoundedCornerShape(bottomStart = KART_KOSE, bottomEnd = KART_KOSE)
}

/**
 * Web `.glass-card` (Swift `glassCard(cornerRadius: 14)`) parçası: zemin + kenar; kenar çizgisi yalnız parçanın dış kenarlarında
 * (orta parçada iki yan), böylece öğeler alt alta tek kart görünür.
 */
internal fun Modifier.kartParcasi(p: KartParcasi): Modifier =
    this
        .clip(kartSekli(p))
        .background(KART_ISIGI)
        .background(KART_ZEMINI)
        .drawWithContent {
            drawContent()
            val r = KART_KOSE.toPx()
            val c = 1.dp.toPx()
            val ust = if (p == KartParcasi.tek || p == KartParcasi.ust) 0f else -3f * r
            val alt = if (p == KartParcasi.tek || p == KartParcasi.alt) size.height else size.height + 3f * r
            clipRect(0f, 0f, size.width, size.height) {
                drawRoundRect(
                    KART_KENARI, topLeft = Offset(c / 2f, ust + c / 2f), size = Size(size.width - c, alt - ust - c),
                    cornerRadius = CornerRadius(r), style = Stroke(c),
                )
            }
        }

/** Satırın üst ayırıcısı (Swift `Divider().overlay(DS.Colors.hairline)`). */
private fun Modifier.ustCizgi(var_: Boolean): Modifier =
    if (!var_) this else drawBehind { drawLine(DS.Colors.hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }

// MARK: - Planlama

/** Plan başına türetilmiş veri (kök kalemler, şema açıklamaları, toplamlar) — plan örneği değişmedikçe yeniden hesaplanmaz. */
internal class PlanTuretilmis(
    val kalemler: List<TedaviKalemi>,
    val aciklamalar: Map<String, DisSemasi.Aciklama>,
    val indirim: Double,
    val liste: Double,
)

@Stable
internal class PlanBellegi {
    private val onbellek = HashMap<String, Pair<HastaPlani, PlanTuretilmis>>()

    fun al(plan: HastaPlani): PlanTuretilmis {
        onbellek[plan.id]?.let { (p, t) -> if (p === plan) return t }
        val ogeler = plan.items ?: emptyList()
        val kalemler = ogeler.filter { it.parentItemId == null && it.deletedAt == null }.distinctBy { it.id }
        val indirim = kalemler.sumOf { PlanTutari.satirIndirimi(it) }
        val liste = kalemler.sumOf { (PlanTutari.satirTutari(it) ?: 0.0) + PlanTutari.satirIndirimi(it) }
        val t = PlanTuretilmis(kalemler, DisSemasi.aciklamalar(ogeler), indirim, liste)
        onbellek[plan.id] = plan to t
        return t
    }
}

/** Web `PlanningTab`: "Yeni Plan Oluştur" + silinmemiş planlar alt alta kart. */
private fun LazyGridScope.planlamaBolumu(
    model: HastaSayfasiModeli,
    bellek: PlanBellegi,
    yeniPlan: () -> Unit,
    planaEkle: (HastaPlani) -> Unit,
    planIslemleri: (HastaPlani) -> Unit,
    kalemIslemi: (TedaviKalemi, HastaPlani) -> Unit,
) {
    item(key = "plan-yeni", span = TAM, contentType = "eylem-satiri") {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            YetkiliButon(listOf(IslemKurallari.DUZENLEME_YETENEGI), eylem = yeniPlan) { mod -> CerceveliDugme("Yeni Plan Oluştur", "FilePlus", mod) }
        }
    }
    when (val d = model.planlar) {
        HastaSayfasiModeli.Durum.bos, HastaSayfasiModeli.Durum.yukleniyor ->
            item(key = "plan-yuk", span = TAM, contentType = "yukleniyor") { YukleniyorBlogu(Modifier.padding(top = 12.dp)) }
        is HastaSayfasiModeli.Durum.hata ->
            item(key = "plan-hata", span = TAM, contentType = "not") { BosNot(d.mesaj, Modifier.padding(top = 12.dp)) }
        is HastaSayfasiModeli.Durum.hazir -> {
            if (d.deger.isEmpty()) {
                item(key = "plan-bos", span = TAM, contentType = "not") {
                    BosNot("Henüz tedavi planı yok. Kapsamlı bir teklif için Yeni Plan Oluştur'u kullanın.", Modifier.padding(top = 12.dp))
                }
            } else {
                for (ozet in d.deger) planKarti(model, ozet, bellek, planaEkle, planIslemleri, kalemIslemi)
            }
        }
    }
}

/**
 * Web `PlanCard`: başlık şeridi (ad · durum · onam · Aktif · tarih · hekim · toplam) → ilerleme → (açılınca) diş şeması → kalem
 * satırları → toplamlar. Kart öğelere bölünür: 100 kalemli planda yalnız görünen satırlar kurulur.
 */
private fun LazyGridScope.planKarti(
    model: HastaSayfasiModeli,
    ozet: HastaPlani,
    bellek: PlanBellegi,
    planaEkle: (HastaPlani) -> Unit,
    planIslemleri: (HastaPlani) -> Unit,
    kalemIslemi: (TedaviKalemi, HastaPlani) -> Unit,
) {
    val id = ozet.id
    val acik = id in model.acikPlanlar
    val ayrinti = model.ayrintilar[id]
    val plan = ayrinti ?: ozet
    item(key = "pk-ust-$id", span = TAM, contentType = "plan-basligi") {
        PlanKartiBasligi(
            plan = plan,
            onamEtiketi = model.ayrintilar[id]?.onamEtiketi,
            acik = acik,
            onToggle = { model.kartiDegistir(id) },
            onEkle = { planaEkle(plan) },
            onIslemler = {
                model.ayrintiyiYukle(id)
                planIslemleri(plan)
            },
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(top = 12.dp)
                .kartParcasi(if (acik) KartParcasi.ust else KartParcasi.tek),
        )
    }
    if (!acik) return
    if (ayrinti == null) {
        item(key = "pk-yuk-$id", span = TAM, contentType = "kart-yukleniyor") {
            YukleniyorBlogu(Modifier.animateItem().kartParcasi(KartParcasi.alt))
        }
        return
    }
    val t = bellek.al(ayrinti)
    item(key = "pk-sema-$id", span = TAM, contentType = "sema") {
        Box(Modifier.animateItem().kartParcasi(KartParcasi.orta).padding(horizontal = 8.dp, vertical = 10.dp)) {
            OdontogramView(t.aciklamalar)
        }
    }
    if (t.kalemler.isEmpty()) {
        item(key = "pk-bos-$id", span = TAM, contentType = "kart-not") {
            BosNot("Bu planda kalem yok.", Modifier.animateItem().kartParcasi(KartParcasi.alt).ustCizgi(true))
        }
        return
    }
    items(t.kalemler, key = { "pk-k-$id-${it.id}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "plan-kalemi" }) { k ->
        PlanKalemSatiri(
            kalem = k,
            modifier = Modifier.animateItem().kartParcasi(KartParcasi.orta).ustCizgi(true),
            islemler = { kalemIslemi(k, plan) },
        )
    }
    item(key = "pk-toplam-$id", span = TAM, contentType = "plan-toplam") {
        PlanToplamlari(plan, t, Modifier.animateItem().kartParcasi(KartParcasi.alt))
    }
}

@Composable
private fun PlanKartiBasligi(
    plan: HastaPlani,
    onamEtiketi: String?,
    acik: Boolean,
    onToggle: () -> Unit,
    onEkle: () -> Unit,
    onIslemler: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yetenekler = LocalAuthManager.current.yetenekler
    val kapali = (plan.status ?: "") in setOf("COMPLETED", "CANCELLED", "REJECTED")
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.03f))
                .plainClickable(onClick = onToggle)
                .semantics { contentDescription = (plan.title ?: "Plan") + if (acik) ", açık" else ", kapalı" }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        plan.title?.takeIf { it.isNotEmpty() } ?: "Adsız plan", Modifier.align(Alignment.CenterVertically),
                        style = sys(15, FontWeight.Bold), color = DS.Colors.onSurface,
                    )
                    DurumHapi(DisSemasi.planDurumu(plan.status ?: "DRAFT"), PlanRengi.durum(plan.status), Modifier.align(Alignment.CenterVertically))
                    if (plan.isActive == true && plan.onayBekliyor != true) {
                        DurumHapi("Aktif", hex("A5F3FC"), Modifier.align(Alignment.CenterVertically))
                    }
                }
                DfyIcon(
                    if (acik) "chevron.up" else "chevron.down", Modifier.padding(start = 6.dp, top = 3.dp),
                    size = 12.dp, tint = DS.Colors.outline,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Etiket(Tarih.plan(plan.treatmentDate ?: plan.createdAt), "calendar")
                Etiket(plan.doctor?.name?.let { Unvan.hekimAdi(it) } ?: "Atanmamış", "stethoscope", Modifier.weight(1f))
                Text(Para.tl(plan.plannedTotal?.deger), style = sys(12, FontWeight.Bold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onamEtiketi != null) {
                    val (m, r) = PlanRengi.onam(onamEtiketi)
                    DurumHapi(m, r)
                }
                Spacer(Modifier.weight(1f))
                // Kart eylemleri: "Tedavi Ekle" (kapalı planda yok) ve ⋮ "Plan işlemleri".
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!kapali) {
                        val onayBekliyor = plan.onayBekliyor == true && "chart.plan.approve" !in yetenekler
                        if (onayBekliyor) {
                            TedaviEkleEtiketi(Modifier.alpha(YetkiliEylem.SONUK_OPAKLIK))
                        } else {
                            YetkiliButon(listOf(IslemKurallari.DUZENLEME_YETENEGI), eylem = onEkle) { mod -> TedaviEkleEtiketi(mod) }
                        }
                    }
                    IslemDugmesi(onIslemler, aciklama = "Plan işlemleri")
                }
            }
        }
        PlanIlerlemesi(plan)
    }
}

@Composable
private fun TedaviEkleEtiketi(modifier: Modifier = Modifier) {
    val sekil = RoundedCornerShape(6.dp)
    Row(
        modifier
            .heightIn(min = 26.dp)
            .clip(sekil)
            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.5f), sekil)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DfyIcon("plus", size = 10.dp, tint = DS.Colors.cyan400)
        Text("Tedavi Ekle".upperTr(), style = sys(10, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1)
    }
}

/** "İlerleme" · "%{pct} · {tamamlanan} / {planlanan}" — `pct = round(completed/planned·100)`. */
@Composable
private fun PlanIlerlemesi(plan: HastaPlani) {
    val planlanan = plan.plannedTotal?.deger ?: 0.0
    val tamam = plan.completedTotal?.deger ?: 0.0
    val pct = if (planlanan > 0) (tamam / planlanan * 100).roundToInt() else 0
    val dolgu = when {
        pct >= 100 -> hex("10B981")
        pct > 0 -> DS.Colors.onSurface.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.1f)
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("İlerleme", style = sys(10, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                "%$pct · ${Para.tl(tamam)} / ${Para.tl(planlanan)}", style = sys(10).monoDigits(), color = DS.Colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.05f), cornerRadius = r)
                    val w = size.width * min(100, pct) / 100f
                    if (w > 0f) drawRoundRect(dolgu, size = Size(w, size.height), cornerRadius = r)
                },
        )
    }
}

/** Alt satırlar: indirim varsa "Liste Toplamı" + "İndirim"; "TOPLAM (İNDİRİMLİ)" ya da "TOPLAM" → `plannedTotal`. */
@Composable
private fun PlanToplamlari(plan: HastaPlani, t: PlanTuretilmis, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.02f)).padding(14.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val indirimVar = t.indirim > 0.005
        if (indirimVar) {
            SatirToplam("LİSTE TOPLAMI", Para.tl(t.liste), DS.Colors.onSurfaceVariant)
            SatirToplam("İNDİRİM", "−" + Para.tl(t.indirim), hex("6EE7B7"))
        }
        SatirToplam(if (indirimVar) "TOPLAM (İNDİRİMLİ)" else "TOPLAM", Para.tl(plan.plannedTotal?.deger), DS.Colors.cyan400, kalin = true)
    }
}

@Composable
private fun SatirToplam(etiket: String, deger: String, renk: Color, kalin: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            etiket, Modifier.weight(1f, fill = false), style = sys(if (kalin) 11 else 10, if (kalin) FontWeight.Bold else FontWeight.SemiBold),
            color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        )
        Text(
            deger, Modifier.widthIn(min = 96.dp), style = sys(if (kalin) 15 else 12, FontWeight.Bold).monoDigits(), color = renk,
            maxLines = 1, textAlign = TextAlign.End,
        )
    }
}

/** Web plan tablosunun satırı mobilde iki satıra yerleşir: (Tarih · Doktor · Konum) / (Tedavi · Adet/Tutar · Ödenmesi Gereken · Durum). */
@Composable
fun PlanKalemSatiri(kalem: TedaviKalemi, modifier: Modifier = Modifier, islemler: (() -> Unit)? = null) {
    val toplam = remember(kalem) { PlanTutari.satirTutari(kalem) }
    val indirim = remember(kalem) { PlanTutari.satirIndirimi(kalem) }
    val konum = remember(kalem) { DisSemasi.konumEtiketi(kalem).ifEmpty { "—" } }
    val tarih = remember(kalem) { Tarih.plan(kalem.treatmentDate ?: kalem.createdAt) }
    val hekim = remember(kalem) { kalem.assignedDoctor?.name?.let { Unvan.hekimAdi(it) } ?: "—" }
    val adet = max(1, kalem.quantity ?: 1)
    val (gecis, gecisRengi) = PlanRengi.gecis(kalem.transitionSource, kalem.stage)
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ⋮ önce ölçülür (dar pencerede / büyük yazıda kaybolmaz); sığmayan hekim adı ve rozet kırpılır.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tarih, style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                Text(
                    hekim, Modifier.weight(1f), style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DurumHapi(gecis, gecisRengi, Modifier.widthIn(max = 180.dp))
            }
            if (islemler != null) IslemDugmesi(islemler)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KonumRozeti(konum)
            Text(kalem.name, Modifier.weight(1f), style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("×$adet", style = sys(10), color = DS.Colors.onSurfaceVariant)
                if (indirim > 0.005 && toplam != null) {
                    Text(
                        Para.tl(toplam + indirim), style = sys(10).copy(textDecoration = TextDecoration.LineThrough),
                        color = DS.Colors.outline, maxLines = 1,
                    )
                }
                Text(Para.tl(toplam), style = sys(14, FontWeight.Bold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1)
                if (indirim > 0.005 && toplam != null && toplam + indirim > 0) {
                    Text("%${virgullu(indirim / (toplam + indirim) * 100, 1)} ind.", style = sys(9), color = hex("34D399"), maxLines = 1)
                }
                val c = kalem.currency
                val kur = kalem.exchangeRate?.deger
                if (c != null && c != "TRY" && kur != null) {
                    Text("$c · ${virgullu(kur, 4)}", style = sys(10), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

// MARK: - Yapılacak / Yapılan

/** Web `StageItemList`in saf kuralı: plana göre gruplar (ilk görülme sırası; plansız "Plansız Kalemler"). */
object AsamaBolumu {
    data class Grup(val anahtar: String, val baslik: String, val durum: String?, val kalemler: List<TedaviKalemi>)

    fun grupla(k: List<TedaviKalemi>): List<Grup> {
        val sira = mutableListOf<String>()
        val harita = HashMap<String, Triple<String, String?, MutableList<TedaviKalemi>>>()
        for (kalem in k) {
            val anahtar = kalem.plan?.id ?: "_plansiz"
            if (anahtar !in harita) {
                sira += anahtar
                harita[anahtar] = Triple(kalem.plan?.title ?: "Plansız Kalemler", kalem.plan?.status, mutableListOf())
            }
            harita.getValue(anahtar).third += kalem
        }
        return sira.map { a -> harita.getValue(a).let { (b, d, l) -> Grup(a, b, d, l.toList()) } }
    }
}

/** Grubun türetilmiş verisi (şema, ilk iki hekim, toplam) — liste örneği değişmedikçe yeniden hesaplanmaz. */
internal class AsamaGrubu(
    val grup: AsamaBolumu.Grup,
    val aciklamalar: Map<String, DisSemasi.Aciklama>,
    val hekimler: List<String>,
    val toplam: Double,
)

internal class AsamaTuretilmis(val gruplar: List<AsamaGrubu>, val toplam: Double)

@Stable
internal class AsamaBellegi {
    private val onbellek = HashMap<String, Pair<List<TedaviKalemi>, AsamaTuretilmis>>()

    fun al(asama: String, kalemler: List<TedaviKalemi>): AsamaTuretilmis {
        onbellek[asama]?.let { (l, t) -> if (l === kalemler) return t }
        val gruplar = AsamaBolumu.grupla(kalemler.distinctBy { it.id }).map { g ->
            AsamaGrubu(
                grup = g,
                aciklamalar = DisSemasi.aciklamalar(g.kalemler),
                hekimler = g.kalemler.mapNotNull { k -> k.assignedDoctor?.name?.let { Unvan.hekimAdi(it) } }.distinct().take(2),
                toplam = g.kalemler.sumOf { it.totalAmountTRY?.deger ?: 0.0 },
            )
        }
        val t = AsamaTuretilmis(gruplar, kalemler.sumOf { it.totalAmountTRY?.deger ?: 0.0 })
        onbellek[asama] = kalemler to t
        return t
    }
}

/** Web `StageItemList`: "Tedavi Ekle" · özet · plan başına kart (başlık, diş şeması, satırlar). */
private fun LazyGridScope.asamaBolumu(
    asama: String,
    durum: HastaSayfasiModeli.Durum<List<TedaviKalemi>>,
    bellek: AsamaBellegi,
    secim: MutableState<Set<String>>,
    ekle: () -> Unit,
    islemler: (List<TedaviKalemi>) -> Unit,
) {
    val renk = asamaRengi(asama)
    // Web S1 — "Tedavi Ekle" (doğrudan kalem; Yapılan seçilirse doğrudan Yapılan kalemi).
    item(key = "asama-ekle-$asama", span = TAM, contentType = "eylem-satiri") {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            YetkiliButon(listOf(IslemKurallari.DUZENLEME_YETENEGI), eylem = ekle) { mod -> CerceveliDugme("Tedavi Ekle", "plus", mod) }
        }
    }
    when (durum) {
        HastaSayfasiModeli.Durum.bos, HastaSayfasiModeli.Durum.yukleniyor ->
            item(key = "asama-yuk-$asama", span = TAM, contentType = "yukleniyor") { YukleniyorBlogu(Modifier.padding(top = 12.dp)) }
        is HastaSayfasiModeli.Durum.hata ->
            item(key = "asama-hata-$asama", span = TAM, contentType = "not") { BosNot(durum.mesaj, Modifier.padding(top = 12.dp)) }
        is HastaSayfasiModeli.Durum.hazir -> {
            val kalemler = durum.deger
            if (kalemler.isEmpty()) {
                item(key = "asama-bos-$asama", span = TAM, contentType = "not") {
                    BosNot(
                        if (asama == "DONE") "Henüz yapılan tedavi yok. Yapılacaktan kalemleri buraya aktarabilirsiniz."
                        else "Henüz yapılacak tedavi yok. Planlamadan kalemleri buraya aktarabilirsiniz.",
                        Modifier.padding(top = 12.dp),
                    )
                }
                return
            }
            val t = bellek.al(asama, kalemler)
            item(key = "asama-ozet-$asama", span = TAM, contentType = "asama-ozet") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${t.gruplar.size} plan · ${kalemler.size} kalem · Toplam", Modifier.weight(1f, fill = false), style = sys(12),
                        color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(Para.tl(t.toplam), style = sys(13, FontWeight.Bold).monoDigits(), color = renk, maxLines = 1)
                }
            }
            for (g in t.gruplar) grupKarti(asama, g, renk, secim, islemler)
        }
    }
}

private fun LazyGridScope.grupKarti(
    asama: String,
    g: AsamaGrubu,
    renk: Color,
    secim: MutableState<Set<String>>,
    islemler: (List<TedaviKalemi>) -> Unit,
) {
    val anahtar = "$asama-${g.grup.anahtar}"
    item(key = "ag-ust-$anahtar", span = TAM, contentType = "asama-basligi") {
        Column(
            Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(top = 12.dp)
                .kartParcasi(KartParcasi.ust)
                .background(horizontalGradient(renk.copy(alpha = 0.10f), Color.Transparent))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(g.grup.baslik, Modifier.align(Alignment.CenterVertically), style = sys(14, FontWeight.Bold), color = DS.Colors.onSurface)
                    g.grup.durum?.let {
                        DurumHapi(DisSemasi.planDurumu(it), DS.Colors.onSurfaceVariant, Modifier.align(Alignment.CenterVertically))
                    }
                }
                Text(
                    Para.tl(g.toplam), Modifier.padding(start = 8.dp), style = sys(13, FontWeight.Bold).monoDigits(), color = renk, maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.hekimler.isNotEmpty()) Etiket(g.hekimler.joinToString(", "), "stethoscope", Modifier.weight(1f, fill = false))
                Text("${g.grup.kalemler.size} kalem", style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
    if (g.aciklamalar.isNotEmpty()) {
        item(key = "ag-sema-$anahtar", span = TAM, contentType = "sema") {
            Box(Modifier.animateItem().kartParcasi(KartParcasi.orta).padding(8.dp)) { OdontogramView(g.aciklamalar) }
        }
    }
    val ilkId = g.grup.kalemler.first().id
    val sonId = g.grup.kalemler.last().id
    items(g.grup.kalemler, key = { "ag-k-$anahtar-${it.id}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "asama-kalemi" }) { k ->
        val secili = k.id in secim.value
        AsamaKalemSatiri(
            kalem = k,
            renk = renk,
            secili = secili,
            secimDegistir = { secim.value = if (k.id in secim.value) secim.value - k.id else secim.value + k.id },
            islemler = { islemler(listOf(k)) },
            modifier = Modifier
                .animateItem()
                .kartParcasi(if (k.id == sonId) KartParcasi.alt else KartParcasi.orta)
                .ustCizgi(k.id != ilkId),
        )
    }
}

/** Aşama satırı: Tarih · Hekim ("Birden çok hekim") · Konum (+ kısmi "2/3 diş") · Tedavi · Tutar · Kaynak. */
@Composable
fun AsamaKalemSatiri(
    kalem: TedaviKalemi,
    renk: Color,
    modifier: Modifier = Modifier,
    secili: Boolean = false,
    secimDegistir: () -> Unit = {},
    islemler: (() -> Unit)? = null,
) {
    val konum = remember(kalem) { DisSemasi.konumEtiketi(kalem).ifEmpty { "—" } }
    val (gecis, gecisRengi) = PlanRengi.gecis(kalem.transitionSource, kalem.stage)
    val tarih = remember(kalem) { Tarih.asama(kalem.treatmentDate ?: kalem.completedAt ?: kalem.createdAt) }
    val hekim = remember(kalem) {
        if ((kalem.hekimler?.size ?: 0) > 1) "Birden çok hekim" else (kalem.assignedDoctor?.name?.let { Unvan.hekimAdi(it) } ?: "—")
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(if (secili) DS.Colors.cyan400.copy(alpha = 0.04f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Seçim kutusu ve ⋮ önce ölçülür (dar pencerede / büyük yazıda kaybolmaz); sığmayan hekim adı ve rozet kırpılır.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .semantics { contentDescription = if (secili) "Seçili" else "Seç" }
                    .plainClickable(onClick = secimDegistir),
                contentAlignment = Alignment.Center,
            ) {
                DfyIcon(if (secili) "checkmark.square.fill" else "Square", size = 15.dp, tint = if (secili) DS.Colors.cyan400 else DS.Colors.outline)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Etiket(tarih, "calendar")
                Etiket(hekim, "stethoscope", Modifier.weight(1f))
                DurumHapi(gecis, gecisRengi, Modifier.widthIn(max = 180.dp))
            }
            if (islemler != null) IslemDugmesi(islemler)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KonumRozeti(konum)
                val toplamDis = kalem.toplamDis
                if (kalem.kismi == true && toplamDis != null) {
                    Text("${kalem.cocukKimlikleri?.size ?: 0}/$toplamDis diş", style = sys(10), color = DS.Colors.onSurfaceVariant)
                }
            }
            Text(kalem.name, Modifier.weight(1f), style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(Para.tl(kalem.totalAmountTRY?.deger), style = sys(14, FontWeight.Bold).monoDigits(), color = renk, maxLines = 1)
                val c = kalem.currency
                val kur = kalem.exchangeRate?.deger
                if (c != null && c != "TRY" && kur != null) {
                    Text("$c · kur ${virgullu(kur, 4)}", style = sys(10), color = DS.Colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Web S5/S6 — "{n} kalem seçildi · Toplam" + Seçimi Temizle / İşlemler. */
@Composable
private fun SecimCubugu(renk: Color, adet: Int, toplam: Double, temizle: () -> Unit, islemler: () -> Unit, modifier: Modifier = Modifier) {
    val sekil = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(sekil)
            .background(DS.Colors.surfaceContainer)
            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.3f), sekil)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(renk, CircleShape))
        Text(
            "$adet kalem seçildi ·  Toplam ${Para.tl(toplam)}", Modifier.weight(1f), style = sys(12, FontWeight.SemiBold),
            color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Seçimi Temizle", Modifier.plainClickable(onClick = temizle).padding(vertical = 6.dp), style = sys(12),
            color = DS.Colors.onSurfaceVariant, maxLines = 1,
        )
        Text(
            "İşlemler", Modifier.plainClickable(onClick = islemler).padding(vertical = 6.dp), style = sys(12, FontWeight.Bold),
            color = DS.Colors.cyan400, maxLines = 1,
        )
    }
}

// MARK: - Önizleme

/** Önizleme oturumu: örnek yeteneklere Medya ("chart.docs.view", "chart.docs") ve onay eklenir — dört bölüm görünür. */
@Composable
internal fun OnizlemeYetkileri(content: @Composable () -> Unit) {
    val auth = remember {
        AuthManager().apply {
            currentUser = APIUser(
                id = "demo", email = "selin@ornek.test", name = "Selin Demir", organizationId = "o1", role = "DOCTOR",
                capabilities = ORNEK_YETENEKLER + listOf("chart.docs.view", "chart.docs", "chart.plan.approve"),
            )
            isLoggedIn = true
        }
    }
    CompositionLocalProvider(LocalAuthManager provides auth, content = content)
}

@Composable
private fun SayfaOnizlemesi(bolum: HastaSayfasiModeli.Bolum, buyuk: Boolean = false) {
    val model = remember { OrnekHastaSayfasi.model(bolum, buyuk) }
    OnizlemeYetkileri { HastaSayfasiIcerigi(model, onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 1400)
@Composable
private fun HastaSayfasiPlanlamaPreview() {
    PreviewEnv { SayfaOnizlemesi(HastaSayfasiModeli.Bolum.planlama) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 1100)
@Composable
private fun HastaSayfasiYapilacakPreview() {
    PreviewEnv { SayfaOnizlemesi(HastaSayfasiModeli.Bolum.yapilacak) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun HastaSayfasiYapilanBuyukYaziPreview() {
    PreviewEnv { SayfaOnizlemesi(HastaSayfasiModeli.Bolum.yapilan) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun HastaSayfasiMedyaPreview() {
    PreviewEnv { SayfaOnizlemesi(HastaSayfasiModeli.Bolum.medya) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun HastaSayfasiBuyukPlanPreview() {
    PreviewEnv { SayfaOnizlemesi(HastaSayfasiModeli.Bolum.planlama, buyuk = true) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun HastaSayfasiTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { SayfaOnizlemesi(HastaSayfasiModeli.Bolum.planlama) }
}
