package com.dentifay.app.hasta

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIPatientWithAppointments
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.Yetki
import com.dentifay.app.notifications.ZilDugmesi
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.PageHeader
import com.dentifay.app.theme.SectionLabel
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ProfilDugmesi
import com.dentifay.app.ui.YetkiliEylem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Liste–ayrıntı düzeni bu genişlikten itibaren (tablet yatay, büyük katlanabilir). */
private val YAN_YANA_ESIGI = 840.dp
private val LISTE_GENISLIGI = 360.dp
private val LISTE_AZAMI = 760.dp
private val TAM_SATIRI: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
private val SAAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("tr-TR")).withZone(ZoneId.systemDefault())

/**
 * Tedavi Planı sekmesi (MOBIL_IS_EMRI M-E8.2): üstte hasta arama; arama boşken bugünkü randevuları olan hastalar — önce
 * "Benim hastalarım", sonra "Diğer hekimlerin hastaları". Hastaya dokununca hastanın sayfası (Planlama · Yapılacak · Yapılan · Medya).
 *
 * Telefonda (ve 840 dp'den dar pencerede) Swift gibi: sayfa listenin üstüne itilir, geri ile döner. 840 dp ve üstünde liste solda
 * (≈360 dp) kalır, seçili hastanın sayfası sağı doldurur. Seçim ve açık sayfanın durumu döndürmede / pencere boyu değişiminde korunur.
 */
@Composable
fun HastaTedaviListeView(onShowNotifications: () -> Unit) {
    HastaTedaviListeIcerigi(onShowNotifications)
}

/** Seçili hasta: modeli ve sayfası (düzenler arasında taşınır — durum kaybolmaz). */
@Stable
private class SeciliDetay(
    val hasta: HastaBasligi,
    val model: HastaSayfasiModeli,
    val icerik: @Composable (geri: (() -> Unit)?) -> Unit,
)

@Composable
internal fun HastaTedaviListeIcerigi(
    onShowNotifications: () -> Unit,
    onizlemeBugun: BugunHastalari.Bolumler? = null,
    onizlemeSecili: HastaBasligi? = null,
    modelUret: (HastaBasligi) -> HastaSayfasiModeli = { HastaSayfasiModeli(it) },
) {
    val auth = LocalAuthManager.current
    val pencere = LocalPencere.current
    val kapsam = rememberCoroutineScope()

    var arama by rememberSaveable { mutableStateOf("") }
    var aramaSonucu by remember { mutableStateOf<List<APIPatientWithAppointments>>(emptyList()) }
    var araniyor by remember { mutableStateOf(false) }
    var bugun by remember { mutableStateOf(onizlemeBugun) }
    var yenileniyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    var misafirUyarisi by remember { mutableStateOf(false) }
    var secili by rememberSaveable(stateSaver = HastaBasligi.Saver) { mutableStateOf(onizlemeSecili) }
    val listeDurumu = rememberLazyGridState()
    val aramaKipi = arama.trim().length >= 2

    val guncelModelUret by rememberUpdatedState(modelUret)
    val detay: SeciliDetay? = remember(secili) {
        secili?.let { h ->
            val m = guncelModelUret(h)
            SeciliDetay(h, m, movableContentOf { geri: (() -> Unit)? -> HastaSayfasiIcerigi(m, geri) })
        }
    }

    suspend fun yenile() {
        try {
            bugun = BugunHastalari.yukle(kullaniciId = auth.currentUser?.id, saatDilimi = auth.currentUser?.timezone)
            hata = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (Yetki.reddiMi(e)) bugun = BugunHastalari.Bolumler(emptyList(), emptyList()) else hata = HastaSayfasiModeli.hataMetni(e)
        }
    }

    LaunchedEffect(Unit) { if (bugun == null) yenile() }

    // 300 ms bekleme, en az 2 harf (M-E8.2); her yazışta önceki arama iptal edilip yeniden başlar.
    LaunchedEffect(arama) {
        val q = arama.trim()
        if (q.length < 2) {
            aramaSonucu = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        araniyor = true
        try {
            aramaSonucu = ApiClient.searchPatients(query = q).distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!Yetki.reddiMi(e)) hata = HastaSayfasiModeli.hataMetni(e)
        } finally {
            araniyor = false
        }
    }

    val yanYana = pencere.genislik >= YAN_YANA_ESIGI
    BackHandler(enabled = !yanYana && secili != null) { secili = null }

    val liste: @Composable (Boolean) -> Unit = { bolme ->
        HastaListesi(
            durum = listeDurumu,
            bolme = bolme,
            arama = arama,
            onArama = { arama = it },
            aramaKipi = aramaKipi,
            araniyor = araniyor,
            aramaSonucu = aramaSonucu,
            bugun = bugun,
            hata = hata,
            yenileniyor = yenileniyor,
            seciliId = if (bolme) secili?.id else null,
            onSec = { secili = it },
            onMisafir = { misafirUyarisi = true },
            onShowNotifications = onShowNotifications,
            onYenile = {
                kapsam.launch {
                    yenileniyor = true
                    try {
                        yenile()
                    } finally {
                        yenileniyor = false
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        if (yanYana) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(LISTE_GENISLIGI).fillMaxHeight()) { liste(true) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(DS.Colors.hairline))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Crossfade(targetState = detay, animationSpec = tween(180), label = "seciliHasta") { d ->
                        if (d != null) d.icerik(null) else SeciliHastaYok()
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = detay,
                contentKey = { it?.hasta?.id },
                transitionSpec = {
                    val itme = targetState != null
                    if (itme) {
                        (slideInHorizontally(tween(300)) { it } togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300), targetAlpha = 0.6f)))
                            .apply { targetContentZIndex = 1f }
                    } else {
                        ((slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(300), initialAlpha = 0.6f)) togetherWith
                            slideOutHorizontally(tween(300)) { it })
                            .apply { targetContentZIndex = -1f }
                    }
                },
                label = "hastaGezinti",
            ) { d ->
                if (d == null) liste(false) else d.icerik { secili = null }
            }
        }
    }

    DfyAlert(
        isPresented = misafirUyarisi,
        title = "Hasta kaydı yok",
        message = "Bu randevunun hasta kaydı yok. Randevu ayrıntısından \"Hasta kaydı oluştur\" ile açabilirsiniz.",
        confirmText = "Tamam",
        onConfirm = { misafirUyarisi = false },
        onDismiss = { misafirUyarisi = false },
    )
}

/** Liste: başlık (zil · profil), arama kutusu, arama sonuçları ya da bugünün iki bölümü. Geniş pencerede kartlar 2 sütun. */
@Composable
private fun HastaListesi(
    durum: LazyGridState,
    bolme: Boolean,
    arama: String,
    onArama: (String) -> Unit,
    aramaKipi: Boolean,
    araniyor: Boolean,
    aramaSonucu: List<APIPatientWithAppointments>,
    bugun: BugunHastalari.Bolumler?,
    hata: String?,
    yenileniyor: Boolean,
    seciliId: String?,
    onSec: (HastaBasligi) -> Unit,
    onMisafir: () -> Unit,
    onShowNotifications: () -> Unit,
    onYenile: () -> Unit,
) {
    val yenileDurumu = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = yenileniyor,
        onRefresh = onYenile,
        state = yenileDurumu,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = yenileDurumu, isRefreshing = yenileniyor,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = DS.Colors.surfaceContainerHigh, color = DS.Colors.cyan400,
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val yatay = if (bolme) 16.dp else max(16.dp, (maxWidth - LISTE_AZAMI) / 2)
            LazyVerticalGrid(
                columns = if (bolme) GridCells.Fixed(1) else GridCells.Adaptive(300.dp),
                state = durum,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = yatay, end = yatay, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "baslik", span = TAM_SATIRI, contentType = "baslik") {
                    PageHeader(title = "Tedavi Planı", modifier = Modifier.padding(top = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZilDugmesi(onShowNotifications)
                            ProfilDugmesi()
                        }
                    }
                }
                item(key = "arama", span = TAM_SATIRI, contentType = "arama") { AramaKutusu(arama, onArama) }

                if (aramaKipi) {
                    when {
                        araniyor && aramaSonucu.isEmpty() -> item(key = "arama-yuk", span = TAM_SATIRI, contentType = "yukleniyor") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { IslemIlerleme(boyut = 22.dp) }
                        }
                        aramaSonucu.isEmpty() -> item(key = "arama-bos", span = TAM_SATIRI, contentType = "not") {
                            ListeNotu("Bu aramayla eşleşen hasta yok.")
                        }
                        else -> items(aramaSonucu, key = { "a-" + it.id }, contentType = { "hasta" }) { p ->
                            val baslik = remember(p) { HastaBasligi(id = p.id, ad = "${p.firstName} ${p.lastName}", dosyaNo = p.fileNumber, telefon = p.phone) }
                            val ust = remember(p) { listOfNotNull(p.fileNumber?.let { "Dosya $it" }, p.phone).joinToString(" · ") }
                            HastaKartSatiri(baslik.ad, ust = ust, alt = null, misafir = false, secili = p.id == seciliId) { onSec(baslik) }
                        }
                    }
                } else if (bugun != null) {
                    if (bugun.bos) {
                        item(key = "bugun-bos", span = TAM_SATIRI, contentType = "not") {
                            ListeNotu("Bugün randevulu hasta yok. Hastayı aramayla bulabilirsiniz.")
                        }
                    } else {
                        if (bugun.benim.isNotEmpty()) {
                            item(key = "benim", span = TAM_SATIRI, contentType = "bolum") {
                                SectionLabel("Benim hastalarım", Modifier.padding(top = 6.dp))
                            }
                            items(bugun.benim, key = { "b-" + it.id }, contentType = { "hasta" }) { k ->
                                KartSatiri(k, seciliId, onSec, onMisafir)
                            }
                        }
                        if (bugun.diger.isNotEmpty()) {
                            item(key = "diger", span = TAM_SATIRI, contentType = "bolum") {
                                SectionLabel("Diğer hekimlerin hastaları", Modifier.padding(top = 6.dp))
                            }
                            items(bugun.diger, key = { "d-" + it.id }, contentType = { "hasta" }) { k ->
                                KartSatiri(k, seciliId, onSec, onMisafir)
                            }
                        }
                    }
                } else if (hata != null) {
                    item(key = "hata", span = TAM_SATIRI, contentType = "not") { ListeNotu(hata) }
                } else {
                    item(key = "yuk", span = TAM_SATIRI, contentType = "yukleniyor") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { IslemIlerleme(boyut = 22.dp) }
                    }
                }
            }
        }
    }
}

/** Bugünün kartı: kayıtlı hasta sayfayı açar; misafir randevu sönük + "Hasta kaydı yok" (dokununca kısa uyarı). */
@Composable
private fun KartSatiri(k: BugunHastalari.Kart, seciliId: String?, onSec: (HastaBasligi) -> Unit, onMisafir: () -> Unit) {
    val alt = remember(k) { listOfNotNull(SAAT.format(k.saat), k.hekimAdi, k.islem).filter { it.isNotEmpty() }.joinToString(" · ") }
    val hastaId = k.hastaId
    if (hastaId != null) {
        HastaKartSatiri(k.hastaAdi, ust = null, alt = alt, misafir = false, secili = hastaId == seciliId) {
            onSec(HastaBasligi(id = hastaId, ad = k.hastaAdi, telefon = k.telefon))
        }
    } else {
        HastaKartSatiri(k.hastaAdi, ust = "Hasta kaydı yok", alt = alt, misafir = true, secili = false) {
            Haptics.light()
            onMisafir()
        }
    }
}

/** Hasta kartı satırı (liste ve arama); liste–ayrıntı düzeninde seçili kart vurgulu. */
@Composable
private fun HastaKartSatiri(ad: String, ust: String?, alt: String?, misafir: Boolean, secili: Boolean, onClick: () -> Unit) {
    val sekil = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (misafir) YetkiliEylem.SONUK_OPAKLIK else 1f)
            .pressable(scale = 0.985f, onClick = onClick)
            .glassCard(14.dp)
            .then(if (secili) Modifier.border(1.dp, DS.Colors.cyan400.copy(alpha = 0.55f), sekil).background(DS.Colors.cyan400.copy(alpha = 0.05f)) else Modifier)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).background(DS.Colors.cyan400.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text(basHarfler(ad), style = sys(14, FontWeight.Bold), color = DS.Colors.cyan400, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ad, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!ust.isNullOrEmpty()) {
                Text(
                    ust, style = sys(12), color = if (misafir) DS.Colors.warning else DS.Colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!alt.isNullOrEmpty()) {
                Text(alt, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!misafir) DfyIcon("chevron.right", size = 12.dp, tint = DS.Colors.outline)
    }
}

/** "Hasta ara (ad, telefon, dosya no)" — otomatik düzeltme ve büyük harf yok; arama tuşu klavyeyi kapatır. */
@Composable
private fun AramaKutusu(arama: String, onArama: (String) -> Unit) {
    val odak = LocalFocusManager.current
    val sekil = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(Color.White.copy(alpha = 0.03f), sekil)
            .border(1.dp, DS.Colors.hairlineStrong, sekil)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DfyIcon("magnifyingglass", tint = DS.Colors.outline)
        BasicTextField(
            value = arama,
            onValueChange = onArama,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = sys(15).copy(color = DS.Colors.onSurface),
            cursorBrush = SolidColor(DS.Colors.cyan400),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { odak.clearFocus() }),
            decorationBox = { ic ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (arama.isEmpty()) {
                        Text("Hasta ara (ad, telefon, dosya no)", style = sys(15), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ic()
                }
            },
        )
        if (arama.isNotEmpty()) {
            DfyIcon(
                "xmark.circle.fill", tint = DS.Colors.outline,
                modifier = Modifier
                    .semantics { contentDescription = "Aramayı temizle" }
                    .plainClickable { onArama("") },
            )
        }
    }
}

@Composable
private fun ListeNotu(metin: String) {
    Text(
        metin, Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 8.dp),
        style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

/** Tablette sağ bölme boşken (hasta seçilmedi). */
@Composable
private fun SeciliHastaYok() {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconTile("list.clipboard", tone = IconTone.Muted)
            Text("Bir hasta seçin", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
            Text(
                "Hastanın Planlama · Yapılacak · Yapılan · Medya sayfası burada açılır.", style = sys(13),
                color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Ad soyadın baş harfleri (en çok iki, Türkçe büyük harf). */
internal fun basHarfler(ad: String): String =
    ad.split(" ").filter { it.isNotEmpty() }.take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").upperTr()

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun HastaTedaviListePreview() {
    PreviewEnv { HastaTedaviListeIcerigi({}, onizlemeBugun = OrnekHastaTedavi.bugun) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun HastaTedaviListeSeciliPreview() {
    PreviewEnv {
        OnizlemeYetkileri {
            HastaTedaviListeIcerigi(
                {}, onizlemeBugun = OrnekHastaTedavi.bugun, onizlemeSecili = OrnekHastaSayfasi.hasta,
                modelUret = { OrnekHastaSayfasi.model() },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun HastaTedaviListeTabletDikeyPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) { HastaTedaviListeIcerigi({}, onizlemeBugun = OrnekHastaTedavi.bugun) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun HastaTedaviListeTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OnizlemeYetkileri {
            HastaTedaviListeIcerigi(
                {}, onizlemeBugun = OrnekHastaTedavi.bugun, onizlemeSecili = OrnekHastaSayfasi.hasta,
                modelUret = { OrnekHastaSayfasi.model() },
            )
        }
    }
}
