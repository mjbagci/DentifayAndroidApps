package com.dentifay.app.hasta

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.ApiClient
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.YetkiliButon
import com.dentifay.app.xray.XrayAnalysisView
import com.dentifay.app.xray.XrayAnalysisViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val TAM_SATIR: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/**
 * Web `MediaTab` (DentAIWeb @ afc3170) ekran durumu — Swift `MedyaBolumu`nun `@State` alanları. Sayfa düzeyinde tutulur: pencereler
 * (yükleme, silme onayı, görüntüleyici) tembel ızgaranın dışında çizilir, kaydırmayla kapanmaz.
 */
@Stable
internal class MedyaDurumu {
    /** Kategori GRUBU süzgeci (null = Tümü). */
    var grup by mutableStateOf<String?>(null)
    var acik by mutableStateOf<HastaMedyasi?>(null)
    var yukle by mutableStateOf(false)
    var geriAl by mutableStateOf<HastaMedyasi?>(null)
    var silinecek by mutableStateOf<HastaMedyasi?>(null)
    var hata by mutableStateOf<String?>(null)

    suspend fun sil(model: HastaSayfasiModeli, m: HastaMedyasi) = dene {
        ApiClient.medyaSil(model.hasta.id, m.id)
        geriAl = m
        hata = null
        model.bolumuYukle(HastaSayfasiModeli.Bolum.medya, zorla = true)
    }

    suspend fun geriYukle(model: HastaSayfasiModeli, m: HastaMedyasi) = dene {
        ApiClient.medyaGeriYukle(model.hasta.id, m.id)
        geriAl = null
        model.bolumuYukle(HastaSayfasiModeli.Bolum.medya, zorla = true)
    }

    suspend fun favori(model: HastaSayfasiModeli, m: HastaMedyasi) = dene {
        ApiClient.medyaFavori(model.hasta.id, m.id, !(m.isFavorite ?: false))
        model.bolumuYukle(HastaSayfasiModeli.Bolum.medya, zorla = true)
    }

    private suspend fun dene(isi: suspend () -> Unit) {
        try {
            isi()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hata = HastaSayfasiModeli.hataMetni(e)
        }
    }

    companion object {
        /** Seçili grubun görselleri (grup yoksa hepsi). */
        fun suz(liste: List<HastaMedyasi>, grup: String?): List<HastaMedyasi> {
            if (grup == null) return liste
            val kodlar = MedyaKategorisi.kodlar(grup)
            return liste.filter { it.category in kodlar }
        }
    }
}

/**
 * Medya bölümünün ızgara öğeleri: üst satır (hata · Yükle), "Geri al" bandı, kategori GRUBU süzgeci (Tümü · Röntgenler · Fotoğraflar ·
 * Belgeler), kare küçük resimler (`GridCells.Adaptive` — telefonda 3, tablette 5–7 sütun). Son görsel görününce sonraki sayfa.
 */
internal fun LazyGridScope.medyaBolumu(
    model: HastaSayfasiModeli,
    durum: MedyaDurumu,
    medya: HastaSayfasiModeli.Durum<MedyaYaniti>,
    liste: List<HastaMedyasi>,
    devamYukleniyor: Boolean,
    kapsam: CoroutineScope,
) {
    item(key = "medya-ust", span = TAM_SATIR, contentType = "medya-ust") {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                durum.hata?.let { Text(it, style = sys(12), color = DS.Colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            YetkiliButon(listOf("chart.docs"), eylem = { durum.yukle = true }) { mod ->
                CerceveliDugme("Yükle", "square.and.arrow.up", mod)
            }
        }
    }
    val geriAl = durum.geriAl
    if (geriAl != null) {
        item(key = "medya-geri-al", span = TAM_SATIR, contentType = "medya-bant") {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "\"${geriAl.fileName ?: "Görsel"}\" çöp kutusuna taşındı.", Modifier.weight(1f), style = sys(12),
                    color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Geri al",
                    Modifier
                        .plainClickable { kapsam.launch { durum.geriYukle(model, geriAl) } }
                        .padding(vertical = 4.dp),
                    style = sys(12, FontWeight.Bold), color = DS.Colors.cyan400,
                )
            }
        }
    }
    when (medya) {
        HastaSayfasiModeli.Durum.bos, HastaSayfasiModeli.Durum.yukleniyor ->
            item(key = "medya-yuk", span = TAM_SATIR, contentType = "yukleniyor") { YukleniyorBlogu(Modifier.padding(top = 12.dp)) }
        is HastaSayfasiModeli.Durum.hata ->
            item(key = "medya-hata", span = TAM_SATIR, contentType = "not") { BosNot(medya.mesaj, Modifier.padding(top = 12.dp)) }
        is HastaSayfasiModeli.Durum.hazir -> {
            val y = medya.deger
            item(key = "medya-suzgec", span = TAM_SATIR, contentType = "medya-suzgec") {
                Suzgecler(y, durum.grup, Modifier.padding(top = 12.dp)) { durum.grup = it }
            }
            if (liste.isEmpty()) {
                item(key = "medya-bos", span = TAM_SATIR, contentType = "not") { BosNot("Henüz görsel yok.", Modifier.padding(top = 12.dp)) }
            } else {
                item(key = "medya-bosluk", span = TAM_SATIR, contentType = "bosluk") { Spacer(Modifier.height(12.dp)) }
                val sonId = liste.last().id
                val toplam = y.media.size
                val cop = y.can?.trash == true
                items(liste, key = { "m-" + it.id }, contentType = { "medya" }) { m ->
                    if (m.id == sonId) {
                        // Sonsuz kaydırma: son görsel çizilince (ve her yeni sayfadan sonra hâlâ görünüyorsa) sonraki sayfa.
                        LaunchedEffect(m.id, toplam) { model.medyaDevam() }
                    }
                    MedyaKarti(
                        m = m,
                        copIzni = cop,
                        onAc = { durum.acik = m },
                        onFavori = { kapsam.launch { durum.favori(model, m) } },
                        onSil = { durum.silinecek = m },
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (devamYukleniyor) {
                    item(key = "medya-devam", span = TAM_SATIR, contentType = "yukleniyor") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { IslemIlerleme() }
                    }
                }
            }
        }
    }
}

/** Grup süzgeci: "Tümü" (bütün görseller) + her grup (sunucu sayılarının toplamı). */
@Composable
private fun Suzgecler(y: MedyaYaniti, grup: String?, modifier: Modifier = Modifier, sec: (String?) -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cip(null, "Tümü", y.media.size, grup, sec)
        MedyaKategorisi.gruplar.forEach { g ->
            Cip(g.baslik, g.baslik, g.kategoriler.sumOf { y.counts?.get(it.kod) ?: 0 }, grup, sec)
        }
    }
}

@Composable
private fun Cip(deger: String?, ad: String, sayi: Int, secili: String?, sec: (String?) -> Unit) {
    val bu = secili == deger
    val sekil = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(sekil)
            .background(if (bu) DS.Colors.cyan400.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (bu) DS.Colors.cyan400.copy(alpha = 0.4f) else DS.Colors.hairline, sekil)
            .plainClickable { sec(deger) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val renk = if (bu) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant
        Text(ad, style = sys(12, FontWeight.SemiBold), color = renk, maxLines = 1)
        Text("$sayi", style = sys(10), color = renk.copy(alpha = 0.7f), maxLines = 1)
    }
}

/** Kare küçük resim + kategori (favori yıldızı) + tarih; uzun basınca Favori / Sil (Swift `contextMenu`). */
@Composable
private fun MedyaKarti(
    m: HastaMedyasi,
    copIzni: Boolean,
    onAc: () -> Unit,
    onFavori: () -> Unit,
    onSil: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val tarih = remember(m.capturedAt, m.createdAt) { Tarih.medya(m.capturedAt ?: m.createdAt) }
    val kategori = remember(m.category) { MedyaKategorisi.ad(m.category) }
    Box(modifier) {
        Column(
            Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAc,
                onLongClick = {
                    Haptics.medium()
                    menu = true
                },
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                if (m.resimMi) {
                    OnbellekliGorsel(m.thumbnailUrl ?: m.fileUrl, azamiNokta = 140.dp, modifier = Modifier.fillMaxSize())
                } else {
                    DfyIcon("doc.text", size = 26.dp, tint = DS.Colors.outline)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kategori, Modifier.weight(1f, fill = false), style = sys(11, FontWeight.SemiBold), color = DS.Colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (m.isFavorite == true) DfyIcon("star.fill", size = 9.dp, tint = hex("F59E0B"))
            }
            Text(tarih, style = sys(10), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            DropdownMenuItem(
                text = { Text(if (m.isFavorite == true) "Favorilerden çıkar" else "Favorilere ekle", style = sys(15), color = DS.Colors.onSurface) },
                leadingIcon = { DfyIcon("Star", size = 15.dp, tint = DS.Colors.onSurface) },
                onClick = { menu = false; onFavori() },
            )
            if (copIzni) {
                DropdownMenuItem(
                    text = { Text("Sil", style = sys(15), color = DS.Colors.danger) },
                    leadingIcon = { DfyIcon("trash", size = 15.dp, tint = DS.Colors.danger) },
                    onClick = { menu = false; onSil() },
                )
            }
        }
    }
}

/** Kapsül çerçeveli küçük eylem düğmesi ("Yükle", "Yeni Plan Oluştur", "Tedavi Ekle"). */
@Composable
internal fun CerceveliDugme(metin: String, ikon: String, modifier: Modifier = Modifier) {
    val sekil = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(sekil)
            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.4f), sekil)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        DfyIcon(ikon, size = 12.dp, tint = DS.Colors.cyan400)
        Text(metin, style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400, maxLines = 1)
    }
}

/**
 * Tam ekran görüntüleyici — yakınlaştırma (0,5…3), yakınken kaydırma, çift dokunuşla 1×/2×; görsel ekran boyunda çözülür. Röntgen
 * kategorisinde "Röntgen Analizi'nde aç" (`ai.xray`) — görsel ve hasta hazır açılır. Ölçek ve kayma yalnız çizim katmanında okunur.
 */
@Composable
fun MedyaGoruntuleyici(medya: HastaMedyasi, onDismiss: () -> Unit) {
    val model = LocalHastaSayfasiModeli.current
    val kapsam = rememberCoroutineScope()
    var rontgen by remember { mutableStateOf<ByteArray?>(null) }
    var indiriliyor by remember { mutableStateOf(false) }
    var olcek by remember { mutableFloatStateOf(1f) }
    var kayma by remember { mutableStateOf(Offset.Zero) }
    val tarih = remember(medya.id) { Tarih.medya(medya.capturedAt ?: medya.createdAt) }

    fun sifirla(hedefOlcek: Float) {
        val bas = olcek
        val basKayma = kayma
        kapsam.launch { animate(bas, hedefOlcek) { v, _ -> olcek = v } }
        if (hedefOlcek <= 1f && basKayma != Offset.Zero) {
            kapsam.launch { animate(Offset.VectorConverter, basKayma, Offset.Zero) { v, _ -> kayma = v } }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (medya.resimMi) {
            OnbellekliGorsel(
                yol = medya.fileUrl,
                azamiNokta = 1600.dp,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val olay = awaitPointerEvent()
                                val zoom = olay.calculateZoom()
                                val kaydir = olay.calculatePan()
                                var degisti = false
                                if (zoom != 1f) {
                                    olcek = (olcek * zoom).coerceIn(0.5f, 3f)
                                    degisti = true
                                }
                                if (olcek > 1f && kaydir != Offset.Zero) {
                                    kayma += kaydir
                                    degisti = true
                                }
                                if (degisti) olay.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (olay.changes.any { it.pressed })
                            if (olcek <= 1f && kayma != Offset.Zero) sifirla(olcek)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { sifirla(if (olcek > 1f) 1f else 2f) })
                    }
                    .graphicsLayer {
                        scaleX = olcek
                        scaleY = olcek
                        translationX = kayma.x
                        translationY = kayma.y
                    },
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DfyIcon("doc.text", size = 40.dp, tint = DS.Colors.outline)
                Text(medya.fileName ?: "Belge", style = sys(15), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
            }
        }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .semantics { contentDescription = "Kapat" }
                    .plainClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { DfyIcon("xmark", size = 16.dp, tint = Color.White) }
            Text(
                "${MedyaKategorisi.ad(medya.category)} · $tarih", style = sys(12), color = Color.White.copy(alpha = 0.8f),
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            )
            if ((medya.category ?: "").startsWith("XRAY_") && medya.resimMi) {
                YetkiliButon(listOf("ai.xray"), eylem = {
                    if (!indiriliyor) {
                        indiriliyor = true
                        kapsam.launch {
                            rontgen = GorselOnbellegi.veri(medya.fileUrl)
                            indiriliyor = false
                        }
                    }
                }) { mod ->
                    Row(
                        mod
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (indiriliyor) IslemIlerleme(renk = Color.White, boyut = 12.dp)
                        else DfyIcon("rectangle.split.3x1", size = 12.dp, tint = Color.White)
                        Text("Röntgen Analizi'nde aç", style = sys(12, FontWeight.SemiBold), color = Color.White, maxLines = 1)
                    }
                }
            }
        }
    }

    // Röntgen Analizi: hasta seçili ve görsel yüklü açılır (Swift `XrayAnalysisView(… hasta:, gorsel:)`).
    FullScreenCover(isPresented = rontgen != null, onDismiss = { rontgen = null }) {
        val veri = rontgen
        if (veri != null) {
            XrayAnalysisView(
                onDismiss = { rontgen = null },
                mode = XrayAnalysisViewModel.Mode.clinic,
                hasta = XrayAnalysisViewModel.PatientRef(id = model.hasta.id, name = model.hasta.ad, subtitle = model.hasta.dosyaNo),
                gorsel = veri,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 390, heightDp = 844)
@Composable
private fun MedyaGoruntuleyiciPreview() {
    PreviewEnv {
        HastaSayfasiOrtami(OrnekHastaSayfasi.model(HastaSayfasiModeli.Bolum.medya)) {
            MedyaGoruntuleyici(OrnekHastaSayfasi.medya.media.last(), onDismiss = {})
        }
    }
}
