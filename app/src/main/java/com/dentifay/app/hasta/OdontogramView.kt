package com.dentifay.app.hasta

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.dentifay.app.R
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Diş şeması — web `components/Odontogram.jsx` · `dental/ToothShape.jsx` · `dental/ToothTreatmentOverlay.jsx` ikizi (DentAIWeb @ afc3170;
 * MOBIL_IS_EMRI M-E8.3). Salt okur (Planlama kartı ve aşama listeleri); `secim` verilirse SEÇİLEBİLİR (plan penceresi — web `size="sm"`
 * düzenlenebilir), dokunuş dişi seçer/bırakır. Daimi diş fotoğraflı (32 görsel, `R.drawable.dis_XX`); süt dişi web'in SVG yollarıyla
 * çizilir — web'de olduğu gibi süt dişinde tedavi katmanı YOK, yalnız ton ve rozet. Kullanılabilir genişliğe sığacak biçimde ölçeklenir.
 *
 * Başarım (iOS M-E9.5'in `drawingGroup` düzeltmesinin karşılığı): bütün şema TEK `Canvas`ta, kendi katmanında (`graphicsLayer`) çizilir;
 * geometri ve çizim nesneleri (fırçalar, yollar, yazı yerleşimleri) yalnız girdiler değişince kurulur. Ton/seçim/çekim efektleri
 * renk matrisiyle, bölge yıkamaları dişin saydamlığını maske alan gölgelendiriciyle çizilir — ekran dışı katman (`saveLayer`) YOK,
 * kaydırmada her karede yeniden birleştirme yapılmaz.
 *
 * @param secim seçim kipi (Swift `Binding<Set<String>>?`); `onSecimChange` yeni kümeyi alır.
 * @param karmaGoster seçimde süt dişleri de gösterilsin mi (karma görünüm).
 */
@Composable
fun OdontogramView(
    aciklamalar: Map<String, DisSemasi.Aciklama>,
    modifier: Modifier = Modifier,
    secim: Set<String>? = null,
    onSecimChange: ((Set<String>) -> Unit)? = null,
    karmaGoster: Boolean = false,
) {
    val context = LocalContext.current
    val onizleme = LocalInspectionMode.current
    val gorselHazir = if (onizleme) {
        DisGorselleri.yukleSenkron(context)
        true
    } else {
        DisGorselleri.hazir
    }
    if (!onizleme) LaunchedEffect(Unit) { DisGorselleri.yukle(context) }

    val karmaAciklama = remember(aciklamalar) { DisSemasi.karmaMi(aciklamalar) }
    val karma = karmaGoster || karmaAciklama || (secim?.any { DisSemasi.sutMu(it) } == true)
    val olcer = rememberTextMeasurer(cacheSize = 128)
    val yogunluk = LocalDensity.current
    // Şema bir çizimdir: numara ve rozet yazıları iOS'taki gibi sabit boyda (yazı ölçeğiyle büyüyüp sığmazlık yaratmaz).
    val sabit = remember(yogunluk.density) { Density(yogunluk.density, 1f) }
    val erisilebilirlik = remember(aciklamalar) { erisilebilirlikMetni(aciklamalar) }
    var ipucu by remember { mutableStateOf<String?>(null) }
    val guncelAciklamalar by rememberUpdatedState(aciklamalar)
    val guncelSecim by rememberUpdatedState(secim)
    val guncelDegisim by rememberUpdatedState(onSecimChange)
    val secimModu = secim != null

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val genislik = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else with(yogunluk) { 360.dp.toPx() }
        val g = remember(genislik, karma, sabit) { SemaGeometrisi.hesapla(genislik, sabit, karma, olcer) }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(with(yogunluk) { g.yukseklik.toDp() })
                .graphicsLayer()
                .drawWithCache {
                    val cizim = OdontogramCizimi(g, aciklamalar, secim, gorselHazir, olcer, sabit)
                    onDrawBehind { with(cizim) { ciz() } }
                }
                .pointerInput(g, secimModu) {
                    detectTapGestures(onTap = { p ->
                        val yer = g.bul(p) ?: return@detectTapGestures
                        val s = guncelSecim
                        if (s != null) {
                            Haptics.selection()
                            guncelDegisim?.invoke(if (yer.dis in s) s - yer.dis else s + yer.dis)
                        } else if (guncelAciklamalar[yer.dis] != null) {
                            ipucu = yer.dis
                        }
                    })
                }
                .semantics { contentDescription = erisilebilirlik },
        )
        val acik = ipucu
        if (acik != null) {
            val yer = g.yer(acik)
            if (yer != null) DisIpucu(acik, aciklamalar[acik], yer.hucre, onDismiss = { ipucu = null })
        }
    }
}

private fun erisilebilirlikMetni(a: Map<String, DisSemasi.Aciklama>): String =
    if (a.isEmpty()) "Diş şeması"
    else "Diş şeması: " + a.entries.sortedBy { it.key }.joinToString("; ") { (d, x) ->
        "Diş $d" + if (x.kalemler.isEmpty()) "" else ", ${x.kalemler.size} tedavi"
    }

/** Web ipucu: "Diş {kod}" + en çok 6 satır "• {ad} · {aşama}" + "+{n} daha". */
@Composable
private fun DisIpucu(dis: String, aciklama: DisSemasi.Aciklama?, hucre: Rect, onDismiss: () -> Unit) {
    val bosluk = with(LocalDensity.current) { 8.dp.roundToPx() }
    val konum = remember(hucre, bosluk) { IpucuKonumu(hucre, bosluk) }
    val sekil = RoundedCornerShape(14.dp)
    Popup(popupPositionProvider = konum, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .widthIn(min = 190.dp, max = 300.dp)
                .shadow(16.dp, sekil)
                .clip(sekil)
                .background(DS.Colors.surfaceContainerHigh)
                .border(1.dp, DS.Colors.hairlineStrong, sekil)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Diş $dis", style = sys(15, FontWeight.Bold), color = DS.Colors.cyan400)
            val kalemler = aciklama?.kalemler ?: emptyList()
            if (kalemler.isEmpty()) {
                Text("Tedavi geçmişi yok", style = sys(12), color = DS.Colors.onSurfaceVariant)
            }
            kalemler.take(6).forEach { k ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "• ${k.ad}", Modifier.weight(1f, fill = false), style = sys(12), color = DS.Colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (DisSemasi.asamaAdi(k.asama).isNotEmpty()) {
                        Text("· " + asamaKisa(k.asama), style = sys(11, FontWeight.Bold), color = AsamaRengi.renk(k.asama), maxLines = 1)
                    }
                }
            }
            if (kalemler.size > 6) {
                Text("+${kalemler.size - 6} daha", style = sys(10), color = DS.Colors.outline)
            }
        }
    }
}

private fun asamaKisa(s: String?): String = when (s) {
    "DONE" -> "YAPILDI"
    "TODO" -> "YAPILACAK"
    "PLANNED" -> "PLANLANDI"
    else -> ""
}

/** İpucu dişin altında (sığmazsa üstünde), pencere içinde kalacak biçimde. */
private class IpucuKonumu(private val hucre: Rect, private val bosluk: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val ortaX = anchorBounds.left + hucre.center.x
        val enSag = max(bosluk, windowSize.width - popupContentSize.width - bosluk)
        val x = (ortaX - popupContentSize.width / 2f).roundToInt().coerceIn(bosluk, enSag)
        val alt = anchorBounds.top + hucre.bottom.roundToInt() + bosluk
        val ust = anchorBounds.top + hucre.top.roundToInt() - bosluk - popupContentSize.height
        val y = if (alt + popupContentSize.height <= windowSize.height - bosluk) alt else max(bosluk, ust)
        return IntOffset(x, y)
    }
}

/** Ölçüler: web md (h=100) oranları; satır genişliği ekrana sığacak h seçilir (fotoğraf genişliği = h × px/360). Birimler dp. */
object DisOlcu {
    /** Fotoğraf genişlikleri (px, hepsi 360 px yüksek) — web `assets/teeth`. */
    val pxGenislik: Map<String, Float> = mapOf(
        "11" to 126f, "12" to 111f, "13" to 104f, "14" to 116f, "15" to 121f, "16" to 185f, "17" to 197f, "18" to 201f,
        "21" to 131f, "22" to 108f, "23" to 105f, "24" to 119f, "25" to 127f, "26" to 192f, "27" to 183f, "28" to 205f,
        "31" to 99f, "32" to 98f, "33" to 96f, "34" to 124f, "35" to 126f, "36" to 201f, "37" to 210f, "38" to 201f,
        "41" to 92f, "42" to 101f, "43" to 101f, "44" to 123f, "45" to 133f, "46" to 206f, "47" to 194f, "48" to 205f,
    )

    /** Süt dişi taban genişliği (60 birimlik kutuya göre) ve alt çene düzeltmesi (web PRIMARY_WIDTH_PX, WIDTH_PX_LOWER_ADJUST). */
    val sutTaban: Map<Int, Float> = mapOf(1 to 38f, 2 to 34f, 3 to 40f, 4 to 48f, 5 to 54f)
    val altDuzeltme: Map<Int, Float> = mapOf(1 to -8f, 2 to -6f)

    fun bosluk(h: Float): Float = max(2f, 8f * h / 100f)

    fun genislik(dis: String, h: Float): Float {
        pxGenislik[dis]?.let { return yuvarla(h * it / 360f) }
        val poz = DisSemasi.konumNo(dis)
        val taban = (sutTaban[poz] ?: 40f) + (if (DisSemasi.ustMu(dis)) 0f else (altDuzeltme[poz] ?: 0f))
        return yuvarla((taban / 60f) * h)
    }

    /** Daimi üst satırın (en geniş) ekrana sığdığı diş yüksekliği; en çok 100 (web md). */
    fun yukseklik(sigacakGenislik: Float): Float {
        val birimGenislik = DisSemasi.daimiUst.fold(0f) { a, d -> a + (pxGenislik[d] ?: 120f) / 360f }   // h başına
        // w = h·birim + 15·boşluk(h) + 1,5·boşluk(h)  →  boşluk(h) = 0,08h
        val h = sigacakGenislik / (birimGenislik + 16.5f * 0.08f)
        return min(100f, max(30f, floor(h)))
    }

    fun toplamYukseklik(karma: Boolean, sigacakGenislik: Float): Float {
        val h = yukseklik(sigacakGenislik)
        val satir = h + 18f
        return if (karma) (satir + h * 0.8f + 18f) * 2f + 14f else satir * 2f + 14f
    }

    /** Swift `rounded()` (yarıda sıfırdan uzağa — değerler pozitif). */
    fun yuvarla(x: Float): Float = floor(x + 0.5f)
}

/** Aşama renkleri (web `durum-basari/uyari/bilgi`, koyu tema). */
object AsamaRengi {
    fun renk(s: String?): Color = when (s) {
        "DONE" -> Color.hsv(156f, 0.72f, 0.85f)
        "TODO" -> Color(0.99f, 0.83f, 0.30f)
        "PLANNED" -> Color(0.49f, 0.83f, 0.99f)
        else -> DS.Colors.onSurfaceVariant
    }
}

/** Ton renkleri (web TONE_COLOR). */
private object TonRengi {
    fun renk(t: DisSemasi.Ton): Color = when (t) {
        DisSemasi.Ton.yapildi -> Color(52, 211, 153)
        DisSemasi.Ton.yapilacak -> Color(245, 158, 11)
        DisSemasi.Ton.planlandi -> Color(6, 182, 212)
    }
}

/** Diş fotoğrafları — tek önbellek; her görsel süreç boyunca bir kez çözülür (M-E8.7). */
object DisGorselleri {
    private val kaynaklar: Map<String, Int> = mapOf(
        "11" to R.drawable.dis_11, "12" to R.drawable.dis_12, "13" to R.drawable.dis_13, "14" to R.drawable.dis_14,
        "15" to R.drawable.dis_15, "16" to R.drawable.dis_16, "17" to R.drawable.dis_17, "18" to R.drawable.dis_18,
        "21" to R.drawable.dis_21, "22" to R.drawable.dis_22, "23" to R.drawable.dis_23, "24" to R.drawable.dis_24,
        "25" to R.drawable.dis_25, "26" to R.drawable.dis_26, "27" to R.drawable.dis_27, "28" to R.drawable.dis_28,
        "31" to R.drawable.dis_31, "32" to R.drawable.dis_32, "33" to R.drawable.dis_33, "34" to R.drawable.dis_34,
        "35" to R.drawable.dis_35, "36" to R.drawable.dis_36, "37" to R.drawable.dis_37, "38" to R.drawable.dis_38,
        "41" to R.drawable.dis_41, "42" to R.drawable.dis_42, "43" to R.drawable.dis_43, "44" to R.drawable.dis_44,
        "45" to R.drawable.dis_45, "46" to R.drawable.dis_46, "47" to R.drawable.dis_47, "48" to R.drawable.dis_48,
    )
    private val onbellek = ConcurrentHashMap<String, ImageBitmap>()
    private val yuklendi = AtomicBoolean(false)
    private val kilit = Any()

    /** Görseller çözüldü mü (çizimler bunu okur; çözülünce şemalar yeniden çizilir). */
    var hazir by mutableStateOf(false)
        private set

    fun gorsel(dis: String): ImageBitmap? = onbellek[dis]

    /** İlk kullanımda arka planda çözer (32 küçük WebP ≈ 270 KB). */
    suspend fun yukle(context: Context) {
        if (!yuklendi.get()) withContext(Dispatchers.IO) { coz(context.applicationContext) }
        if (!hazir) hazir = true
    }

    /** Önizleme ve baskı için eşzamanlı çözüm (durum yazmaz). */
    fun yukleSenkron(context: Context) {
        if (!yuklendi.get()) coz(context)
    }

    private fun coz(context: Context) = synchronized(kilit) {
        if (yuklendi.get()) return@synchronized
        val kaynak = context.resources
        for ((dis, id) in kaynaklar) {
            if (onbellek.containsKey(dis)) continue
            val bmp = runCatching { BitmapFactory.decodeResource(kaynak, id, BitmapFactory.Options().apply { inScaled = false }) }
                .getOrNull() ?: continue
            // Küçültülerek çizilir: mip haritası kenar titremesini önler.
            bmp.setHasMipMap(true)
            bmp.prepareToDraw()
            onbellek[dis] = bmp.asImageBitmap()
        }
        yuklendi.set(true)
    }
}

// MARK: - Geometri

/** Bir dişin yeri (px; şemanın sol üstüne göre). */
internal class DisYeri(
    val dis: String,
    val sut: Boolean,
    val ust: Boolean,
    /** Dişin kendi yüksekliği (dp) — yazı boyları buna göre. */
    val hDp: Float,
    /** Dokunma alanı (numara + çizim). */
    val hucre: Rect,
    /** Çizim kutusu (tam piksele hizalı). */
    val kutu: Rect,
    val etiket: TextLayoutResult,
    val etiketKonum: Offset,
)

/** Şemanın yerleşimi — genişlik / karma / yoğunluk değişmedikçe yeniden hesaplanmaz. */
internal class SemaGeometrisi(
    val genislik: Float,
    val yukseklik: Float,
    val disler: List<DisYeri>,
    val ayracY: Float,
    val ayracX0: Float,
    val ayracX1: Float,
    /** px / dp */
    val dp: Float,
    val enGenisSatir: Float,
) {
    fun bul(p: Offset): DisYeri? = disler.firstOrNull { it.hucre.contains(p) }
    fun yer(dis: String): DisYeri? = disler.firstOrNull { it.dis == dis }

    companion object {
        /** Swift `DisOlcu.yukseklik` ile h seçilir; numaralar dişten geniş kalırsa (çok dar pencere) h küçültülerek sığdırılır. */
        fun hesapla(genislikPx: Float, yogunluk: Density, karma: Boolean, olcer: TextMeasurer): SemaGeometrisi {
            var h = DisOlcu.yukseklik(genislikPx / yogunluk.density)
            while (true) {
                val g = kur(genislikPx, h, yogunluk, karma, olcer)
                if (g.enGenisSatir <= genislikPx + 0.5f || h <= 30f) return g
                h -= 1f
            }
        }

        private class Hucre(
            val dis: String,
            val sut: Boolean,
            val th: Float,
            val tw: Float,
            val etiket: TextLayoutResult,
            val w: Float,
            val h: Float,
        )

        private fun kur(genislik: Float, hDp: Float, yogunluk: Density, karma: Boolean, olcer: TextMeasurer): SemaGeometrisi {
            val dp = yogunluk.density
            val ustSatirlar = if (karma) listOf(DisSemasi.daimiUst, DisSemasi.sutUst) else listOf(DisSemasi.daimiUst)
            val altSatirlar = if (karma) listOf(DisSemasi.sutAlt, DisSemasi.daimiAlt) else listOf(DisSemasi.daimiAlt)
            val bosluk = DisOlcu.bosluk(hDp) * dp
            val aralik = 4f * dp
            val disler = ArrayList<DisYeri>(52)
            var y = 0f
            var enGenis = 0f

            fun satir(isim: List<String>, ust: Boolean) {
                val hucreler = isim.map { dis ->
                    val sut = DisSemasi.sutMu(dis)
                    val th = if (sut) DisOlcu.yuvarla(hDp * 0.8f) else hDp
                    val tw = DisOlcu.genislik(dis, th)
                    val etiket = olcer.measure(dis, etiketStili(th), softWrap = false, maxLines = 1, density = yogunluk)
                    Hucre(
                        dis, sut, th, tw, etiket,
                        w = max(tw * dp, etiket.size.width.toFloat()),
                        h = etiket.size.height + 2f * dp + th * dp,
                    )
                }
                val satirH = hucreler.maxOf { it.h }
                val satirW = hucreler.fold(0f) { a, c -> a + c.w } + (hucreler.size - 1) * bosluk + 1.5f * bosluk
                enGenis = max(enGenis, satirW)
                var x = (genislik - satirW) / 2f
                val orta = hucreler.size / 2
                hucreler.forEachIndexed { i, c ->
                    if (i > 0) x += bosluk
                    if (i == orta) x += 1.5f * bosluk
                    // Üst çenede numara altta (hücre alta yaslı), alt çenede üstte (hücre üste yaslı).
                    val ust0 = y + (if (ust) satirH - c.h else 0f)
                    val kw = (c.tw * dp).roundToInt().toFloat()
                    val kh = (c.th * dp).roundToInt().toFloat()
                    val kx = (x + (c.w - kw) / 2f).roundToInt().toFloat()
                    val ky = (if (ust) ust0 else ust0 + c.etiket.size.height + 2f * dp).roundToInt().toFloat()
                    val ey = if (ust) ky + kh + 2f * dp else ust0
                    disler += DisYeri(
                        dis = c.dis, sut = c.sut, ust = ust, hDp = c.th,
                        hucre = Rect(x, ust0, x + c.w, ust0 + c.h),
                        kutu = Rect(kx, ky, kx + kw, ky + kh),
                        etiket = c.etiket,
                        etiketKonum = Offset(x + (c.w - c.etiket.size.width) / 2f, ey),
                    )
                    x += c.w
                }
                y += satirH
            }

            ustSatirlar.forEachIndexed { i, s -> if (i > 0) y += aralik; satir(s, ust = true) }
            // Ayraç: 1 noktalık kesik çizgi; yatayda 16, dikeyde 2 boşluk (VStack aralığı 4).
            y += aralik
            val ayracY = y + 2.5f * dp
            y += 5f * dp
            altSatirlar.forEach { s -> y += aralik; satir(s, ust = false) }
            return SemaGeometrisi(genislik, y, disler, ayracY, 16f * dp, genislik - 16f * dp, dp, enGenis)
        }

        fun etiketStili(hDp: Float) = sys(max(8f, 11f * hDp / 100f), FontWeight.SemiBold).monoDigits()
    }
}

// MARK: - Çizim

/** Çizimin önceden kurulmuş nesneleri (fırçalar, yollar, yazılar) — `drawWithCache` içinde girdiler değişince bir kez kurulur. */
internal class OdontogramCizimi(
    private val g: SemaGeometrisi,
    aciklamalar: Map<String, DisSemasi.Aciklama>,
    secim: Set<String>?,
    gorselHazir: Boolean,
    olcer: TextMeasurer,
    yogunluk: Density,
) {
    private val parcalar: List<DisCizimi> = g.disler.map { yer ->
        DisCizimi(
            yer = yer,
            aciklama = aciklamalar[yer.dis],
            secili = secim?.contains(yer.dis) == true,
            foto = if (gorselHazir && !yer.sut) DisGorselleri.gorsel(yer.dis) else null,
            olcer = olcer,
            yogunluk = yogunluk,
        )
    }
    private val kesik = PathEffect.dashPathEffect(floatArrayOf(4f * g.dp, 3f * g.dp))

    fun DrawScope.ciz() {
        drawLine(
            DS.Colors.hairlineStrong, Offset(g.ayracX0, g.ayracY), Offset(g.ayracX1, g.ayracY),
            strokeWidth = g.dp, pathEffect = kesik,
        )
        for (p in parcalar) with(p) { ciz() }
    }
}

/** Tek diş: numara + çizim (fotoğraf ya da süt dişi) + ton + katman + rozet. */
private class DisCizimi(
    val yer: DisYeri,
    aciklama: DisSemasi.Aciklama?,
    val secili: Boolean,
    val foto: ImageBitmap?,
    olcer: TextMeasurer,
    yogunluk: Density,
) {
    private val dp = yogunluk.density
    private val kutu = yer.kutu
    private val etiketRengi = if (secili) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant
    private val ton = aciklama?.ton
    private val cekim = aciklama?.katmanlar?.contains(DisSemasi.Gorsel.extraction) == true
    private val dstKonum = IntOffset(kutu.left.roundToInt(), kutu.top.roundToInt())
    private val dstBoyut = IntSize(kutu.width.roundToInt(), kutu.height.roundToInt())

    // Fotoğraflı daimi diş: bölge yıkamaları (dişin saydamlığıyla maskeli) ve vurgu çizimleri.
    private val bolgeler: List<Brush> = if (foto != null && aciklama != null) bolgeFircalari(foto, aciklama.katmanlar) else emptyList()
    private val vurgu: Vurgu? = if (foto != null && aciklama != null && aciklama.katmanlar.isNotEmpty()) {
        Vurgu(kutu, yer.ust, aciklama.katmanlar)
    } else null

    // Süt dişi şekli (yol uzayında; çizimde dönüşümle yerleşir).
    private val sekil = if (yer.sut) DisSekilleri.sekil(yer.dis) else null
    private val kokYollari = sekil?.kokler?.map { SVGYolu.path(it) } ?: emptyList()
    private val kuronYolu = sekil?.let { SVGYolu.path(it.kuron) }
    private val okluzalYollar = sekil?.okluzal?.map { SVGYolu.path(it) } ?: emptyList()
    private val sutDolgu = if (secili) SUT_SECILI else sutDolgusu(ton)

    private val rozetler: List<RozetCizimi> = rozetleriKur(aciklama, olcer, yogunluk)

    fun DrawScope.ciz() {
        if (foto != null) fotoCiz(foto) else if (sekil != null) sutCiz()
        for (r in rozetler) with(r) { ciz() }
        drawText(yer.etiket, color = etiketRengi, topLeft = yer.etiketKonum)
    }

    /** Fotoğraflı daimi diş: görsel (çekimde gri/soluk) · ton yıkaması (`color` karışımı, 0,42) · bölge yıkamaları · vurgular. */
    private fun DrawScope.fotoCiz(foto: ImageBitmap) {
        val tabanAlfa = if (cekim) 0.4f else 1f
        if (secili) {
            // Seçili: camgöbeği parıltı (web gölgesi yarıçap 9) — bulanıklaştırma katmanı yerine kademeli büyütülmüş silüetler.
            for ((olcek, alfa) in PARILTI_KATLARI) {
                val w = dstBoyut.width * olcek
                val h = dstBoyut.height * olcek
                drawImage(
                    foto, dstOffset = IntOffset((kutu.center.x - w / 2f).roundToInt(), (kutu.center.y - h / 2f).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = alfa, colorFilter = SECIM_PARILTISI,
                )
            }
        } else {
            drawImage(
                foto, dstOffset = IntOffset(dstKonum.x, dstKonum.y + dp.roundToInt()), dstSize = dstBoyut,
                alpha = 0.25f * tabanAlfa, colorFilter = SIYAH_SILUET,
            )
        }
        drawImage(foto, dstOffset = dstKonum, dstSize = dstBoyut, alpha = tabanAlfa, colorFilter = if (cekim) SOLUK else null)
        if (secili) {
            // Seçili: TONE_COLOR selected rgb(34,211,238), opaklık 0,38 (ton yerine — web'de seçim kazanır).
            drawImage(foto, dstOffset = dstKonum, dstSize = dstBoyut, alpha = 0.38f, colorFilter = SECIM_TONU)
        } else if (ton != null) {
            drawImage(foto, dstOffset = dstKonum, dstSize = dstBoyut, alpha = 0.42f, colorFilter = TON_FILTRELERI.getValue(ton))
        }
        for (b in bolgeler) drawRect(b, topLeft = kutu.topLeft, size = kutu.size)
        vurgu?.let { with(it) { ciz() } }
    }

    /**
     * Süt dişi (51–85) — web `ToothShape` SVG'si: viewBox "-2 -4 64 124", sol taraf yatay, alt çene dikey çevrilir; kanonik çizim
     * sağ üst, kuron altta. Fırçalar yol uzayında tanımlıdır; dönüşüm onları da taşır (Swift'te dönüştürülmüş uç noktalar).
     */
    private fun DrawScope.sutCiz() {
        val kuron = kuronYolu ?: return
        val w = kutu.width
        val h = kutu.height
        val olcek = min(w / 64f, h / 124f)
        val ox = (w - 64f * olcek) / 2f
        val oy = (h - 124f * olcek) / 2f
        withTransform({
            translate(kutu.left, kutu.top)
            scale(if (DisSemasi.solMu(yer.dis)) -1f else 1f, if (DisSemasi.ustMu(yer.dis)) 1f else -1f, pivot = Offset(w / 2f, h / 2f))
            translate(ox + 2f * olcek, oy + 4f * olcek)
            scale(olcek, olcek, pivot = Offset.Zero)
        }) {
            for (kok in kokYollari) {
                drawPath(kok, sutDolgu)
                drawPath(kok, SUT_CIZGI, style = KOK_KALEMI)
                drawPath(kok, SUT_GOLGE)
            }
            drawPath(kuron, sutDolgu)
            drawPath(kuron, SUT_CIZGI, style = KURON_KALEMI)
            drawPath(kuron, SUT_GOLGE)
            for (o in okluzalYollar) drawPath(o, SUT_OKLUZAL, style = OKLUZAL_KALEMI)
            drawPath(kuron, SUT_PARILTI, alpha = 0.85f)
        }
    }

    /** Web `ToothTreatmentOverlay` bölgeleri: kanal (kök) · lamina · kuron (kuron tarafı), dişin saydamlığıyla maskeli. */
    private fun bolgeFircalari(foto: ImageBitmap, katmanlar: List<DisSemasi.Gorsel>): List<Brush> {
        val out = ArrayList<Brush>(3)
        if (DisSemasi.Gorsel.rootcanal in katmanlar) out += maskeliBolge(foto, 0xC12F2F, 0xE8704C, 0.66f, kok = true)
        if (DisSemasi.Gorsel.veneer in katmanlar) out += maskeliBolge(foto, 0xDFF1FF, 0xFFFFFF, 0.5f, kok = false)
        if (DisSemasi.Gorsel.crown in katmanlar) out += maskeliBolge(foto, 0xCF9F2C, 0xF4DD8C, 0.84f, kok = false)
        return out
    }

    private fun maskeliBolge(foto: ImageBitmap, renk: Int, acik: Int, tepe: Float, kok: Boolean): Brush {
        val ustOpak = (!kok && !yer.ust) || (kok && yer.ust)
        val c = renkHex(renk)
        val l = renkHex(acik)
        val renkler = if (ustOpak) {
            intArrayOf(l.copy(alpha = tepe).toArgb(), c.copy(alpha = tepe * 0.95f).toArgb(), c.copy(alpha = 0f).toArgb())
        } else {
            intArrayOf(c.copy(alpha = 0f).toArgb(), c.copy(alpha = tepe * 0.95f).toArgb(), l.copy(alpha = tepe).toArgb())
        }
        val konumlar = if (ustOpak) floatArrayOf(0f, 0.28f, 0.6f) else floatArrayOf(0.4f, 0.72f, 1f)
        val bmp = foto.asAndroidBitmap()
        val maske = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        maske.setLocalMatrix(
            android.graphics.Matrix().apply {
                setScale(dstBoyut.width / bmp.width.toFloat(), dstBoyut.height / bmp.height.toFloat())
                postTranslate(dstKonum.x.toFloat(), dstKonum.y.toFloat())
            }
        )
        val gradyan = android.graphics.LinearGradient(
            0f, kutu.top, 0f, kutu.bottom, renkler, konumlar, android.graphics.Shader.TileMode.CLAMP,
        )
        // SRC_IN: gradyan (kaynak) yalnız dişin (hedef) opak olduğu yerde.
        return ShaderBrush(android.graphics.ComposeShader(maske, gradyan, android.graphics.PorterDuff.Mode.SRC_IN))
    }

    private fun rozetleriKur(aciklama: DisSemasi.Aciklama?, olcer: TextMeasurer, yogunluk: Density): List<RozetCizimi> {
        val r = aciklama?.rozetler?.take(2) ?: return emptyList()
        if (r.isEmpty()) return emptyList()
        val stil = sys(max(6f, 9f * yer.hDp / 100f), FontWeight.Bold)
        val yatayPay = 3f * dp
        val dikeyPay = 1.5f * dp
        val azami = max(1f, kutu.width - 2f * yatayPay)
        val olculer = r.map { rozet ->
            var yazi = olcer.measure(rozet.gorsel.rozet, stil, softWrap = false, maxLines = 1, density = yogunluk)
            var olcek = 1f
            if (yazi.size.width > azami) {
                // `minimumScaleFactor(0.6)`; yine sığmazsa `lineLimit(1)` kısaltması.
                olcek = max(0.6f, azami / yazi.size.width)
                if (yazi.size.width * olcek > azami + 0.5f) {
                    yazi = olcer.measure(
                        rozet.gorsel.rozet, stil, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                        constraints = Constraints(maxWidth = max(1, (azami / olcek).toInt())), density = yogunluk,
                    )
                }
            }
            Triple(rozet, yazi, olcek)
        }
        val boyutlar = olculer.map { (_, yazi, olcek) ->
            Size(yazi.size.width * olcek + 2f * yatayPay, yazi.size.height * olcek + 2f * dikeyPay)
        }
        val aralik = 2f * dp
        val toplam = boyutlar.fold(0f) { a, s -> a + s.height } + aralik * (boyutlar.size - 1)
        var y = kutu.center.y - toplam / 2f
        return olculer.mapIndexed { i, (rozet, yazi, olcek) ->
            val s = boyutlar[i]
            val sol = kutu.center.x - s.width / 2f
            RozetCizimi(
                cerceve = Rect(Offset(sol, y), s), renk = rozet.gorsel.renk, yazi = yazi, olcek = olcek,
                yaziKonum = Offset(sol + yatayPay, y + dikeyPay), kose = 4f * dp, kenar = 0.5f * dp,
            ).also { y += s.height + aralik }
        }
    }
}

/** Rozet: renkli zemin, ince beyaz kenar, beyaz kalın yazı (gerekirse küçültülmüş). */
private class RozetCizimi(
    val cerceve: Rect,
    val renk: Color,
    val yazi: TextLayoutResult,
    val olcek: Float,
    val yaziKonum: Offset,
    kose: Float,
    kenar: Float,
) {
    private val koseYaricapi = CornerRadius(kose)
    private val kenarKalemi = Stroke(kenar)

    fun DrawScope.ciz() {
        drawRoundRect(renk, cerceve.topLeft, cerceve.size, koseYaricapi)
        drawRoundRect(Color.White.copy(alpha = 0.4f), cerceve.topLeft, cerceve.size, koseYaricapi, style = kenarKalemi)
        withTransform({
            translate(yaziKonum.x, yaziKonum.y)
            scale(olcek, olcek, pivot = Offset.Zero)
        }) { drawText(yazi, color = Color.White) }
    }
}

/**
 * Web `ToothTreatmentOverlay` vurguları — 100×150 ızgarası kutuya bağımsız gerilir (web `preserveAspectRatio="none"`); çizim sırası
 * sabit: kanal çizgileri → implant → dolgu → fissür → orto → çekim. Yollar ve fırçalar bir kez kurulur.
 */
private class Vurgu(private val kutu: Rect, ust: Boolean, katmanlar: List<DisSemasi.Gorsel>) {
    private val sx = kutu.width / 100f
    private val sy = kutu.height / 150f
    private fun p(x: Float, y: Float) = Offset(kutu.left + x * sx, kutu.top + y * sy)
    private val crownY = if (ust) 118f else 32f
    private val rootY = if (ust) 34f else 116f

    private val kanal: Path? = if (DisSemasi.Gorsel.rootcanal in katmanlar) Path().apply {
        moveTo(p(41f, if (ust) 6f else 144f)); lineTo(p(38f, crownY))
        moveTo(p(59f, if (ust) 6f else 144f)); lineTo(p(62f, crownY))
    } else null
    private val kanalKalemi = Stroke(2.2f * sx, cap = StrokeCap.Round)

    private val implant: Boolean = DisSemasi.Gorsel.implant in katmanlar
    private val yon = if (ust) -1f else 1f
    private fun ip(x: Float, y: Float) = p(50f + x, rootY + y * yon)
    private val implantBasi = Path().apply { moveTo(ip(-9f, -26f)); lineTo(ip(9f, -26f)); lineTo(ip(6f, -10f)); lineTo(ip(-6f, -10f)); close() }
    private val implantGovdesi = Path().apply { moveTo(ip(-6f, -10f)); lineTo(ip(6f, -10f)); lineTo(ip(0f, 46f)); close() }
    private val metal = Brush.linearGradient(
        0f to hex("7E93A8"), 0.42f to hex("EEF4FB"), 1f to hex("566A7E"), start = ip(-9f, 0f), end = ip(9f, 0f),
    )
    private val metalKenari = Stroke(0.6f * sx)
    private val disler = Path().apply {
        for ((x, y) in listOf(5f to 2f, 4.4f to 10f, 3.8f to 18f, 3.1f to 26f, 2.4f to 34f)) { moveTo(ip(-x, y)); lineTo(ip(x, y)) }
    }
    private val disKalemi = Stroke(1.1f * sx)

    private val dolgu: Boolean = DisSemasi.Gorsel.filling in katmanlar
    private val dolguKutusu = Rect(kutu.left + (50f - 15f) * sx, kutu.top + (crownY - 13f) * sy, kutu.left + 35f * sx + 30f * sx, kutu.top + (crownY - 13f) * sy + 26f * sy)
    private val dolguFircasi = Brush.radialGradient(
        0f to hex("C4F7EE"), 0.52f to hex("2EC6B8"), 1f to hex("0B7A71"),
        center = Offset(dolguKutusu.left + dolguKutusu.width * 0.4f, dolguKutusu.top + dolguKutusu.height * 0.34f),
        radius = max(1f, max(dolguKutusu.width, dolguKutusu.height) * 0.72f),
    )
    private val dolguKalemi = Stroke(1.4f * sx)
    private val dolguIsigi = Rect(kutu.left + (45f - 6f) * sx, kutu.top + (crownY - 4f - 4f) * sy, kutu.left + 39f * sx + 12f * sx, kutu.top + (crownY - 8f) * sy + 8f * sy)

    private val fissur: List<Rect> = if (DisSemasi.Gorsel.sealant in katmanlar) {
        val y = crownY + (if (ust) 9f else -9f)
        listOf(38f, 50f, 62f).map { x -> Rect(kutu.left + (x - 3.1f) * sx, kutu.top + (y - 3.1f) * sy, kutu.left + (x + 3.1f) * sx, kutu.top + (y + 3.1f) * sy) }
    } else emptyList()

    private val orto: Boolean = DisSemasi.Gorsel.ortho in katmanlar
    private val braket = Rect(kutu.left + 43f * sx, kutu.top + (crownY - 7f) * sy, kutu.left + 57f * sx, kutu.top + (crownY + 7f) * sy)
    private val yuva = Rect(kutu.left + 47.4f * sx, kutu.top + (crownY - 7f) * sy, kutu.left + 52.6f * sx, kutu.top + (crownY + 7f) * sy)

    private val cekim: Path? = if (DisSemasi.Gorsel.extraction in katmanlar) Path().apply {
        moveTo(p(27f, 42f)); lineTo(p(73f, 108f)); moveTo(p(73f, 42f)); lineTo(p(27f, 108f))
    } else null
    private val cekimKalemi = Stroke(7f * sx, cap = StrokeCap.Round)

    fun DrawScope.ciz() {
        kanal?.let { drawPath(it, hex("7D1414").copy(alpha = 0.8f), style = kanalKalemi) }
        if (implant) {
            for (parca in listOf(implantBasi, implantGovdesi)) {
                drawPath(parca, metal)
                drawPath(parca, hex("3F5267"), style = metalKenari)
            }
            drawPath(disler, hex("34465A").copy(alpha = 0.5f), style = disKalemi)
        }
        if (dolgu) {
            drawOval(dolguFircasi, dolguKutusu.topLeft, dolguKutusu.size)
            drawOval(hex("0A5E57"), dolguKutusu.topLeft, dolguKutusu.size, style = dolguKalemi)
            drawOval(Color.White.copy(alpha = 0.5f), dolguIsigi.topLeft, dolguIsigi.size)
        }
        for (r in fissur) drawOval(hex("22B3A4").copy(alpha = 0.92f), r.topLeft, r.size)
        if (orto) {
            drawLine(hex("9AA7B6"), p(22f, crownY), p(78f, crownY), strokeWidth = 2f * sx)
            drawRoundRect(hex("CBD5E1"), braket.topLeft, braket.size, CornerRadius(2.5f * sx))
            drawRoundRect(hex("5B6B7D"), braket.topLeft, braket.size, CornerRadius(2.5f * sx), style = Stroke(1.2f * sx))
            drawRect(hex("8A99AB"), yuva.topLeft, yuva.size)
        }
        cekim?.let { drawPath(it, hex("EF4444").copy(alpha = 0.95f), style = cekimKalemi) }
    }
}

private fun Path.moveTo(o: Offset) = moveTo(o.x, o.y)
private fun Path.lineTo(o: Offset) = lineTo(o.x, o.y)

private fun renkHex(rgb: Int): Color = Color(0xFF000000L or rgb.toLong())

/**
 * W3C `color` karışımı (tonun rengi + dişin parlaklığı) renk matrisiyle: B = Cs + (L(dis) − L(Cs)). Opaklıkla (0,42/0,38) dişin
 * üstüne çizilir; maske dişin kendi saydamlığı. API 26'da da çalışır (BlendMode.Color, API 29 altında desteklenmez).
 */
private fun renkKarisimi(c: Color): ColorFilter {
    val ls = 0.3f * c.red + 0.59f * c.green + 0.11f * c.blue
    val or = (c.red - ls) * 255f
    val og = (c.green - ls) * 255f
    val ob = (c.blue - ls) * 255f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.3f, 0.59f, 0.11f, 0f, or,
                0.3f, 0.59f, 0.11f, 0f, og,
                0.3f, 0.59f, 0.11f, 0f, ob,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

private val TON_FILTRELERI: Map<DisSemasi.Ton, ColorFilter> = DisSemasi.Ton.entries.associateWith { renkKarisimi(TonRengi.renk(it)) }
private val SECIM_TONU = renkKarisimi(Color(34, 211, 238))
private val SECIM_PARILTISI = ColorFilter.tint(Color(0f, 0.96f, 1f), BlendMode.SrcIn)
private val PARILTI_KATLARI = listOf(1.14f to 0.14f, 1.09f to 0.22f, 1.045f to 0.32f)
private val SIYAH_SILUET = ColorFilter.tint(Color.Black, BlendMode.SrcIn)
private val SOLUK = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.1f) })

// Süt dişi fırçaları (yol uzayı: viewBox -2 -4 64 124).
private val SUT_CIZGI = Color(56, 48, 36).copy(alpha = 0.7f)
private val SUT_OKLUZAL = Color(56, 42, 22).copy(alpha = 0.45f)
private val KOK_KALEMI = Stroke(1.1f, join = StrokeJoin.Round)
private val KURON_KALEMI = Stroke(1.2f, join = StrokeJoin.Round)
private val OKLUZAL_KALEMI = Stroke(1f, cap = StrokeCap.Round)
private val SUT_GOLGE = Brush.linearGradient(
    0f to Color.Transparent, 0.68f to Color.Transparent, 1f to Color(80, 60, 30).copy(alpha = 0.22f),
    start = Offset(-2f, 0f), end = Offset(62f, 0f),
)
private val SUT_PARILTI = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.6f), 0.5f to Color.White.copy(alpha = 0.12f), 1f to Color.White.copy(alpha = 0f),
    start = Offset(0f, 0f), end = Offset(0f, 124f),
)

private fun sutFircasi(vararg duraklar: Pair<Float, Color>): Brush =
    Brush.linearGradient(*duraklar, start = Offset(0f, 0f), end = Offset(0f, 124f))

private val SUT_SECILI = sutFircasi(0f to hex("ECFEFF"), 0.5f to hex("A5F3FC"), 1f to hex("22D3EE"))
private val SUT_DOLGULARI: Map<DisSemasi.Ton?, Brush> = mapOf(
    DisSemasi.Ton.yapildi to sutFircasi(0f to hex("D1FAE5"), 1f to hex("34D399")),
    DisSemasi.Ton.yapilacak to sutFircasi(0f to hex("FEF3C7"), 1f to hex("F59E0B")),
    DisSemasi.Ton.planlandi to sutFircasi(0f to hex("CFFAFE"), 1f to hex("06B6D4")),
    null to sutFircasi(0f to hex("FFFAF0"), 0.4f to hex("F4EAD2"), 0.78f to hex("E2D4B0"), 1f to hex("C8B888")),
)

private fun sutDolgusu(ton: DisSemasi.Ton?): Brush = SUT_DOLGULARI.getValue(ton)

/**
 * Diş şeması → PNG (baskıya gömülür — Swift `SemaGoruntusu.png`): uygulamanın şema çizimi beyaz zeminde, `genislikDp` genişlik,
 * `kenarDp` kenar boşluğu, `olcek` piksel yoğunluğu (Swift `ImageRenderer.scale = 3`). Ana iş parçacığını kısa süre (birkaç ms) tutar.
 */
object OdontogramGoruntusu {
    fun png(
        context: Context,
        aciklamalar: Map<String, DisSemasi.Aciklama>,
        genislikDp: Float = 340f,
        kenarDp: Float = 8f,
        olcek: Float = 3f,
    ): ByteArray? = runCatching {
        DisGorselleri.yukleSenkron(context)
        val yogunluk = Density(olcek, 1f)
        val olcer = TextMeasurer(createFontFamilyResolver(context), yogunluk, LayoutDirection.Ltr)
        val g = SemaGeometrisi.hesapla(genislikDp * olcek, yogunluk, DisSemasi.karmaMi(aciklamalar), olcer)
        val kenar = kenarDp * olcek
        val w = (g.genislik + 2 * kenar).roundToInt()
        val h = (g.yukseklik + 2 * kenar).roundToInt()
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val tuval = androidx.compose.ui.graphics.Canvas(bitmap)
        val cizim = OdontogramCizimi(g, aciklamalar, null, true, olcer, yogunluk)
        CanvasDrawScope().draw(yogunluk, LayoutDirection.Ltr, tuval, Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White)
            translate(kenar, kenar) { with(cizim) { ciz() } }
        }
        ByteArrayOutputStream().use { out ->
            bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()
}

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 420)
@Composable
private fun OdontogramPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OdontogramView(DisSemasi.aciklamalar(OrnekHastaSayfasi.kalemler1))
            OdontogramView(DisSemasi.aciklamalar(OrnekHastaSayfasi.ayrinti("p2").items ?: emptyList()))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun OdontogramSecimPreview() {
    PreviewEnv {
        var secim by remember { mutableStateOf(setOf("11", "21", "36", "55")) }
        OdontogramView(emptyMap(), Modifier.padding(16.dp), secim = secim, onSecimChange = { secim = it }, karmaGoster = true)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun OdontogramTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        OdontogramView(DisSemasi.aciklamalar(OrnekHastaSayfasi.kalemler1), Modifier.padding(24.dp))
    }
}
