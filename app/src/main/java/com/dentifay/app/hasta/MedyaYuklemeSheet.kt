package com.dentifay.app.hasta

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.ApiClient
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Medya Yükle" — web `MediaTab` UploadDialog: dosya (kamera / galeri), kategori ZORUNLU ("Kategori *"), not (M-E8.4; `chart.docs`).
 * Görseller sırayla JPEG (%85) olarak yüklenir (EXIF yönü uygulanır, konum meta verisi taşınmaz); başarıda Medya tazelenir.
 * Çağıran `DfySheet` içinde açar; hasta `LocalHastaSayfasiModeli`nden.
 */
@Composable
fun MedyaYuklemeSheet(onDismiss: () -> Unit) {
    val model = LocalHastaSayfasiModeli.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kategori by remember { mutableStateOf("") }
    var not by remember { mutableStateOf("") }
    val gorseller = remember { mutableStateListOf<Uri>() }
    var yukleniyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }

    val galeri = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { secilen ->
        for (u in secilen) if (u !in gorseller) gorseller += u
    }
    fun galeriAc() = galeri.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val kamera = rememberKameraSecici(sonuc = { u -> if (u != null) gorseller += u }, kameraYok = { galeriAc() })

    // Çekilen geçici fotoğraflar pencereyle birlikte silinir; yükleme sürerken geri tuşu pencereyi kapatmaz.
    DisposableEffect(Unit) { onDispose { KameraSecici.temizle(context) } }
    BackHandler(enabled = yukleniyor) {}

    fun yukle() {
        if (gorseller.isEmpty() || kategori.isEmpty() || yukleniyor) return
        yukleniyor = true
        hata = null
        scope.launch {
            try {
                val zaman = System.currentTimeMillis() / 1000
                for ((i, u) in gorseller.toList().withIndex()) {
                    val veri = withContext(Dispatchers.IO) { GorselHazirlama.jpeg(context, u) } ?: continue
                    ApiClient.medyaYukle(model.hasta.id, veri, "mobil-$zaman-$i.jpg", "image/jpeg", kategori, not)
                }
                Haptics.success()
                model.bolumuYukle(HastaSayfasiModeli.Bolum.medya, zorla = true)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                hata = HastaSayfasiModeli.hataMetni(e)
            } finally {
                yukleniyor = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        PencereBasligi(
            baslik = "Medya Yükle",
            solMetni = "Vazgeç",
            solEylem = onDismiss,
            sagMetni = if (gorseller.size > 1) "${gorseller.size} dosya yükle" else "Yükle",
            sagEtkin = gorseller.isNotEmpty() && kategori.isNotEmpty() && !yukleniyor,
            sagCalisiyor = yukleniyor,
            sagEylem = { yukle() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            hata?.let { SonucBandi(basari = false, metin = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KaynakDugmesi("Kamera", "camera", Modifier.weight(1f)) { kamera() }
                KaynakDugmesi("Galeri", "photo.on.rectangle", Modifier.weight(1f)) { galeriAc() }
            }
            if (gorseller.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(gorseller, key = { it.toString() }) { u -> SeciliGorsel(u) }
                }
            }
            Text("Kategori *", style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
            KategoriSecici(kategori) { kategori = it }
            NotAlani(not) { not = it }
        }
    }
}

/** Kamera / Galeri düğmesi (Swift `DugmeStili`). */
@Composable
private fun KaynakDugmesi(baslik: String, ikon: String, modifier: Modifier = Modifier, eylem: () -> Unit) {
    val sekil = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = 46.dp)
            .pressable(onClick = eylem)
            .clip(sekil)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, DS.Colors.hairlineStrong, sekil)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(ikon, size = 14.dp, tint = DS.Colors.onSurface)
        Text(baslik, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Seçilen görselin küçük önizlemesi (72 dp; çözme G/Ç iş parçacığında, ekran boyunda). */
@Composable
private fun SeciliGorsel(uri: Uri) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
    val gorsel by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { GorselHazirlama.onizleme(context, uri, px * 2) }
    }
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center,
    ) {
        val g = gorsel
        if (g != null) Image(g, contentDescription = null, modifier = Modifier.size(72.dp), contentScale = ContentScale.Crop)
        else IslemIlerleme(boyut = 16.dp)
    }
}

/** Kategori seçici (Swift `Picker(.menu)` — "Seçiniz" + gruplu kategoriler). */
@Composable
private fun KategoriSecici(secili: String, sec: (String) -> Unit) {
    var acik by remember { mutableStateOf(false) }
    val etiket = if (secili.isEmpty()) "Seçiniz" else MedyaKategorisi.ad(secili)
    Box {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .plainClickable { acik = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(etiket, style = sys(15), color = DS.Colors.cyan400, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DfyIcon("chevron.down", size = 11.dp, tint = DS.Colors.cyan400)
        }
        DropdownMenu(
            expanded = acik,
            onDismissRequest = { acik = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            KategoriSecenegi("Seçiniz", secili.isEmpty()) { acik = false; sec("") }
            MedyaKategorisi.gruplar.forEach { g ->
                Text(
                    g.baslik.uppercase(java.util.Locale.forLanguageTag("tr-TR")),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    style = sys(11, FontWeight.Bold, tracking = 0.8), color = DS.Colors.onSurfaceVariant,
                )
                g.kategoriler.forEach { k ->
                    KategoriSecenegi(k.ad, k.kod == secili) { acik = false; sec(k.kod) }
                }
            }
        }
    }
}

@Composable
private fun KategoriSecenegi(ad: String, secili: Boolean, eylem: () -> Unit) {
    DropdownMenuItem(
        text = { Text(ad, style = sys(15), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = eylem,
        trailingIcon = if (secili) ({ DfyIcon("checkmark", size = 14.dp, tint = DS.Colors.onSurface) }) else null,
    )
}

/** "Not" — çok satırlı (2–4 satır görünür), Swift `KutuStili`. */
@Composable
private fun NotAlani(deger: String, degisti: (String) -> Unit) {
    val sekil = RoundedCornerShape(10.dp)
    BasicTextField(
        value = deger,
        onValueChange = degisti,
        modifier = Modifier
            .fillMaxWidth()
            .clip(sekil)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, DS.Colors.hairlineStrong, sekil)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        textStyle = sys(14).copy(color = DS.Colors.onSurface),
        cursorBrush = SolidColor(DS.Colors.cyan400),
        minLines = 2,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { ic ->
            Box {
                if (deger.isEmpty()) Text("Not", style = sys(14), color = DS.Colors.outline)
                ic()
            }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 640)
@Composable
private fun MedyaYuklemeSheetPreview() {
    PreviewEnv {
        HastaSayfasiOrtami(OrnekHastaSayfasi.model(HastaSayfasiModeli.Bolum.medya)) {
            Box(Modifier.background(DS.Colors.surfaceContainer)) { MedyaYuklemeSheet(onDismiss = {}) }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 640, heightDp = 800)
@Composable
private fun MedyaYuklemeSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) {
        HastaSayfasiOrtami(OrnekHastaSayfasi.model(HastaSayfasiModeli.Bolum.medya)) {
            Box(Modifier.background(DS.Colors.surfaceContainer)) { MedyaYuklemeSheet(onDismiss = {}) }
        }
    }
}
