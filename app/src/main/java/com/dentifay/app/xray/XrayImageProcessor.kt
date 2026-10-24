package com.dentifay.app.xray

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Röntgen alanı: görsele göre normalize (0…1, sol-üst orijin) dört köşe. */
data class XrayQuad(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset,
) {
    companion object {
        val full = XrayQuad(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    }

    val points: List<Offset> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    operator fun get(i: Int): Offset = when (i) {
        0 -> topLeft
        1 -> topRight
        2 -> bottomRight
        else -> bottomLeft
    }

    /** Swift `quad[i] = p` karşılığı (değiştirilmiş kopya). */
    fun with(i: Int, p: Offset): XrayQuad = when (i) {
        0 -> copy(topLeft = p)
        1 -> copy(topRight = p)
        2 -> copy(bottomRight = p)
        else -> copy(bottomLeft = p)
    }

    /** Tüm görsel mi (perspektif düzeltmesi gerekmez)? */
    val isFull: Boolean
        get() = points.withIndex().all { (i, p) -> hypot((p.x - full[i].x).toDouble(), (p.y - full[i].y).toDouble()) < 0.004 }

    /** Dışbükey ve yeterince büyük mü (kesişen köşeler perspektif düzeltmesini bozar)? */
    val isValid: Boolean
        get() {
            val p = points
            var sign = 0.0
            for (i in 0 until 4) {
                val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
                val cross = (b.x.toDouble() - a.x) * (c.y.toDouble() - b.y) - (b.y.toDouble() - a.y) * (c.x.toDouble() - b.x)
                if (abs(cross) < 1e-6) return false
                if (sign == 0.0) sign = cross else if ((cross > 0) != (sign > 0)) return false
            }
            return area > 0.02
        }

    val area: Double
        get() {
            val p = points
            var s = 0.0
            for (i in 0 until 4) s += p[i].x.toDouble() * p[(i + 1) % 4].y - p[(i + 1) % 4].x.toDouble() * p[i].y
            return abs(s) / 2
        }

    /**
     * Fotoğraflanmış bir dikdörtgen olabilir mi: karşılıklı kenarlar kabaca eşit, açılar
     * 70°–110° arası (algılayıcı bazen çarpık dörtgen döndürür).
     */
    val isPlausiblyRectangular: Boolean
        get() {
            val p = points
            fun d(a: Offset, b: Offset) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            val top = d(p[0], p[1]); val right = d(p[1], p[2]); val bottom = d(p[2], p[3]); val left = d(p[3], p[0])
            if (minOf(top, right, bottom, left) <= 0.01) return false
            if (max(top, bottom) / min(top, bottom) >= 1.35 || max(left, right) / min(left, right) >= 1.35) return false
            for (i in 0 until 4) {
                val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
                val v1x = (a.x - b.x).toDouble(); val v1y = (a.y - b.y).toDouble()
                val v2x = (c.x - b.x).toDouble(); val v2y = (c.y - b.y).toDouble()
                val cs = (v1x * v2x + v1y * v2y) / max(1e-9, hypot(v1x, v1y) * hypot(v2x, v2y))
                if (abs(cs) > 0.34) return false       // 70°–110° dışı
            }
            return true
        }

    val center: Offset get() = Offset(points.sumOf { it.x.toDouble() }.toFloat() / 4, points.sumOf { it.y.toDouble() }.toFloat() / 4)

    val bounds: Rect
        get() {
            val xs = points.map { it.x }; val ys = points.map { it.y }
            return Rect(xs.min(), ys.min(), xs.max(), ys.max())
        }

    /**
     * Düzleştirilmiş görüntüdeki (u, v ∈ 0…1) noktanın bu dörtgendeki yeri — perspektif
     * düzeltmesinin tersi (birim kareden dörtgene projektif eşleme).
     */
    fun point(u: Double, v: Double): Offset {
        val x0 = topLeft.x.toDouble(); val y0 = topLeft.y.toDouble()
        val x1 = topRight.x.toDouble(); val y1 = topRight.y.toDouble()
        val x2 = bottomRight.x.toDouble(); val y2 = bottomRight.y.toDouble()
        val x3 = bottomLeft.x.toDouble(); val y3 = bottomLeft.y.toDouble()
        val dx1 = x1 - x2; val dx2 = x3 - x2; val sx = x0 - x1 + x2 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val sy = y0 - y1 + y2 - y3
        var g = 0.0; var h = 0.0
        val den = dx1 * dy2 - dx2 * dy1
        if (abs(den) > 1e-12) {
            g = (sx * dy2 - dx2 * sy) / den
            h = (dx1 * sy - sx * dy1) / den
        }
        val a = x1 - x0 + g * x1; val b = x3 - x0 + h * x3
        val d = y1 - y0 + g * y1; val e = y3 - y0 + h * y3
        val w = g * u + h * v + 1
        return Offset(((a * u + b * v + x0) / w).toFloat(), ((d * u + e * v + y0) / w).toFloat())
    }

    /** Düzleştirilmiş uzaydaki normalize dikdörtgenin kaynak görseldeki dörtgeni (x, y, genişlik, yükseklik). */
    fun subQuad(x: Double, y: Double, w: Double, h: Double): XrayQuad = XrayQuad(
        point(x, y), point(x + w, y), point(x + w, y + h), point(x, y + h),
    )

    /** Diğer dörtgenin köşeleri bu dörtgenin içinde mi (kenarlardan %2 pay ile)? */
    fun contains(other: XrayQuad): Boolean {
        val c = center
        val grown = XrayQuad(grow(topLeft, c), grow(topRight, c), grow(bottomRight, c), grow(bottomLeft, c))
        return other.points.all { grown.containsPoint(it) }
    }

    private fun grow(p: Offset, c: Offset) = Offset(c.x + (p.x - c.x) * 1.04f, c.y + (p.y - c.y) * 1.04f)

    private fun containsPoint(p: Offset): Boolean {
        val q = points
        var sign = 0.0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val cross = (b.x.toDouble() - a.x) * (p.y.toDouble() - a.y) - (b.y.toDouble() - a.y) * (p.x.toDouble() - a.x)
            if (abs(cross) < 1e-9) continue
            if (sign == 0.0) sign = cross else if ((cross > 0) != (sign > 0)) return false
        }
        return true
    }
}

/** Canlı kamera karesindeki algılama: röntgen dörtgeni + netlik/parlama ölçüleri. */
data class XrayLiveDetection(
    /** Karede (dik, sol-üst orijin) normalize röntgen alanı — ekran/negatoskop çerçevesi değil, filmin kendisi. */
    val quad: XrayQuad,
    /** Kontrasttan bağımsız netlik ölçüsü (yalnız aynı sahnedeki kareler arasında karşılaştırılır). */
    val sharpness: Double,
    /** Röntgen alanında patlamış (beyaza doymuş) piksel oranı — yansıma / aşırı pozlama. */
    val clipped: Double,
)

/** Analize hazırlanan görüntünün kalite uyarıları (inceleme ekranında gösterilir). */
data class XrayQualityReport(
    val isBlurry: Boolean = false,
    val isSmall: Boolean = false,
    val isOverexposed: Boolean = false,
) {
    val hasIssues: Boolean get() = isBlurry || isSmall || isOverexposed
}

/**
 * Röntgen görselini analize hazırlar: fotoğraftaki filmi/ekranı bulur, çevresindeki ekran
 * arayüzünü ve kenar boşluklarını ayıklayıp yalnız röntgeni kırpar, perspektifi düzeltir,
 * gri tona çevirir ve tonunu dijital panoramik röntgenlere yaklaştırır. Tüm işler arka planda
 * (çağıran `Dispatchers.Default` üzerinde çalıştırır).
 *
 * Neden bu kadarı (ve fazlası değil): sunucudaki modeller (FDI numaralandırma + patoloji,
 * 1024 px) dijital panoramiklerle eğitildi. Doğru kırpma tanınan diş oranını en çok artıran
 * adım; bulanıklık ve örtüşme (moiré) en çok bozan etkiler; CLAHE / sert kontrast germe ise
 * patoloji modelini kötüleştiriyor. Bu yüzden ton ayarı yumuşak, küçültme kademeli.
 *
 * iOS'taki Vision dikdörtgen/belge algılamasının yerine `XrayQuadDetector` (kenar + Hough
 * doğruları, parlak bölge bölütleme) aday dörtgen üretir; seçim mantığı birebir aynıdır.
 */
object XrayImageProcessor {

    sealed class ProcessingError(message: String) : Exception(message) {
        class unreadable : ProcessingError("Görsel okunamadı. Farklı bir dosya deneyin.")
        class encodingFailed : ProcessingError("Görsel hazırlanamadı. Tekrar deneyin.")
    }

    /** İşleme sınırı — 48 MP çekimde film ~5000 px; bellek patlamadan ayrıntı korunur. */
    const val maxWorkingEdge: Double = 4096.0
    /** Sunucuya giden en uzun kenar (model 1024'te çalışır; diş kırpımları için bol pay). */
    const val uploadLongEdge: Double = 2600.0
    /** Algılama için küçültülmüş kopya. */
    private const val detectionEdge: Double = 1024.0
    /** İçerik ayrıştırma (satır/sütun profili) çözünürlüğü. */
    private const val profileEdge: Double = 512.0

    // MARK: - Yükleme

    /** Dosya verisinden (JPEG/PNG/HEIC/WebP) yönü düzeltilmiş, sınırlı boyutta görsel. */
    fun load(data: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ProcessingError.unreadable()
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxWorkingEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: throw ProcessingError.unreadable()
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        val upright = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        // Ara kopyalar hemen bırakılır: 48 MP çekimde her biri ~50 MB (düşük bellekli cihazda tepe noktası yarıya iner).
        if (upright !== decoded) decoded.recycle()
        val scaled = scaledToFit(upright, maxWorkingEdge)
        if (scaled !== upright) upright.recycle()
        val out = opaque(scaled)
        if (out !== scaled) scaled.recycle()
        return out
    }

    /** Bitmap → dik, ARGB_8888, opak, en uzun kenarı sınırlı kopya (Swift `normalized(UIImage)`). */
    fun normalized(image: Bitmap): Bitmap {
        if (image.width <= 0 || image.height <= 0) throw ProcessingError.unreadable()
        val soft = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        return opaque(scaledToFit(soft, maxWorkingEdge))
    }

    /** 90° döndürür (saat yönünde). */
    fun rotatedClockwise(cg: Bitmap): Bitmap? = runCatching {
        Bitmap.createBitmap(cg, 0, 0, cg.width, cg.height, Matrix().apply { setRotate(90f) }, true)
    }.getOrNull()

    /** Ekranda gösterim kopyasının en uzun kenarı (alan seçimi + 3× büyüteç için yeterli; GPU doku sınırının altında). */
    const val displayEdge: Double = 2048.0

    /**
     * Ekranda gösterilecek kopya: en uzun kenarı `maxEdge`e kademeli küçültülmüş, mipmap ipucu açık (küçültülerek çizilirken
     * örtüşme/titreşme olmasın). Arka planda çağrılır; işlemeye her zaman asıl görsel gider.
     */
    fun displayCopy(image: Bitmap, maxEdge: Double = displayEdge): Bitmap {
        val out = scaledToFit(image, maxEdge)
        out.setHasMipMap(true)
        return out
    }

    // MARK: - Ayrıştırma (röntgeni bul)

    /**
     * Görsel bir fotoğraf mı (ekran/negatoskop çekimi), yoksa dijital röntgen dosyası mı?
     * Dijital röntgen tam gridir; telefon fotoğrafında renk tonu ve sensör gürültüsü hep vardır.
     */
    fun isPhotograph(cg: Bitmap): Boolean {
        val r = raster(cg, 320.0) ?: return true
        var colored = 0
        for (i in 0 until r.w * r.h) {
            val c = r.argb[i]
            val red = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (maxOf(red, g, b) - minOf(red, g, b) > 6) colored++
        }
        return colored.toDouble() / max(1, r.w * r.h) > 0.01
    }

    /**
     * Röntgenin görseldeki alanı.
     *
     * Fotoğrafta algılayıcının bulduğu dörtgenler (monitör, görüntüleyici penceresi, röntgenin
     * kendisi, negatoskop paneli, film…) aday olur. Röntgen benzeri adaylar (gri, dokulu,
     * röntgen oranlı) arasında, sınırında doku kesilen (gerçek kenarlı) EN İÇTEKİ seçilir.
     * Seçilen alanın düz kenar şeritleri (film payı, arayüz boşluğu) ayrıca kırpılır. Dijital
     * dosyada yalnız kenar boşlukları kırpılır.
     * @param hint canlı kamerada bulunan alan (çekimle aynı kadraj) — ek aday.
     * @return tam görselden anlamlı biçimde farklıysa alan, değilse null.
     */
    fun locate(cg: Bitmap, photo: Boolean, hint: XrayQuad? = null): XrayQuad? {
        val small = scaledToFit(cg, detectionEdge)
        val quads = mutableListOf<XrayQuad>()
        if (photo) {
            quads += visionCandidates(small, maxCount = 12)
            if (hint != null && hint.isValid) quads += hint
        }
        val pick = select(quads, small, allowFull = true) ?: return null
        return if (pick.quad.isFull) null else pick.quad
    }

    /** Canlı kare (dik): film/ekran adayları + içerik ayrıştırma + netlik. Bulamazsa null. */
    fun detectLive(frame: Bitmap, useSegmentation: Boolean): XrayLiveDetection? {
        val small = scaledToFit(frame, 720.0)
        val quads = visionCandidates(small, maxCount = 8, segmentation = useSegmentation)
        if (quads.isEmpty()) return null
        val pick = select(quads, small, allowFull = false) ?: return null
        val g = pick.raster.gray; val w = pick.raster.w; val h = pick.raster.h
        return XrayLiveDetection(
            quad = pick.quad,
            sharpness = sharpness(g, w, h, pick.content),
            clipped = clippedFraction(g, w, h, pick.content),
        )
    }

    private fun visionCandidates(image: Bitmap, maxCount: Int, segmentation: Boolean = true): List<XrayQuad> {
        val out = XrayQuadDetector.rectangles(image, maxCount).toMutableList()
        if (segmentation) XrayQuadDetector.document(image)?.let { out += it }
        // Aynı dörtgen iki kez gelebilir (dikdörtgen + bölütleme).
        val unique = mutableListOf<XrayQuad>()
        for (q in out) {
            if (!(q.isValid && q.area < 0.985 && q.isPlausiblyRectangular)) continue
            val dup = unique.any { u -> u.points.zip(q.points).all { (a, b) -> hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < 0.02 } }
            if (!dup) unique += q
        }
        return unique
    }

    /** Değerlendirilmiş aday. */
    private class Candidate(
        /** Algılayıcının dörtgeni. */
        val source: XrayQuad,
        /** Düz kenar şeritleri kırpılmış röntgen alanı. */
        val quad: XrayQuad,
        /** Düzleştirilmiş aday (512 px) ve içindeki röntgen alanı (normalize). */
        val raster: Raster,
        val content: Rect,
        /** İçeride / hemen dışındaki halkada dokulu piksel oranı. */
        val inside: Double,
        val ring: Double,
        val chroma: Double,
        val aspect: Double,
    ) {
        /** Röntgene benziyor mu: yeterince büyük, gri, dokulu, röntgen oranlı (panoramik ~2:1, periapikal/sefalometrik ~4:3). */
        val plausible: Boolean get() = quad.area >= 0.05 && chroma <= 0.12 && inside >= 0.04 && aspect in 1.15..3.4
        /** Gerçek bir kenar mı: dışında doku kesiliyor (ekran arayüzü, panel, masa). */
        val bounded: Boolean get() = ring <= inside * 0.4
    }

    /** Kenar halkası (dörtgenin kısa kenarına göre): ilk %2,5 atlanır, %2,5–%7 arası ölçülür. */
    private const val ringMargin = 0.07
    private const val ringGuard = 0.025

    private fun select(quads: List<XrayQuad>, image: Bitmap, allowFull: Boolean): Candidate? {
        val cands = quads.mapNotNull { evaluate(it, image, ring = true) }
        val plausible = cands.filter { it.plausible }
        val bounded = plausible.filter { it.bounded }
        // En içteki: içinde, kendisinin en az %30'u büyüklükte başka gerçek kenarlı aday olmayan.
        val innermost = bounded.filter { outer ->
            bounded.none { inner -> inner.source != outer.source && inner.quad.area >= 0.3 * outer.quad.area && outer.source.contains(inner.quad) }
        }
        innermost.maxByOrNull { it.quad.area }?.let { return it }
        plausible.maxByOrNull { it.quad.area }?.let { return it }
        if (allowFull) {
            val full = evaluate(XrayQuad.full, image, ring = false)
            if (full != null && full.inside >= 0.02) return full
        }
        return null
    }

    private fun evaluate(q: XrayQuad, image: Bitmap, ring: Boolean): Candidate? {
        // Halka iki eksende aynı piksel genişliğinde olsun: düzleştirilmiş en-boy oranı kestirilir.
        val ew = image.width.toDouble(); val eh = image.height.toDouble()
        fun len(a: Offset, b: Offset) = hypot((a.x - b.x) * ew, (a.y - b.y) * eh)
        val qw = (len(q.topLeft, q.topRight) + len(q.bottomLeft, q.bottomRight)) / 2
        val qh = (len(q.topLeft, q.bottomLeft) + len(q.topRight, q.bottomRight)) / 2
        val ratio = qw / max(1.0, qh)
        val mu = if (ring) ringMargin * min(1.0, 1 / max(ratio, 0.01)) else 0.0     // kısa kenara göre
        val mv = if (ring) mu * ratio else 0.0
        val outer = if (ring) q.subQuad(-mu, -mv, 1 + 2 * mu, 1 + 2 * mv) else q
        val r = raster(rectified(image, outer), profileEdge) ?: return null
        val map = TextureMap(r)
        // Raster içinde asıl dörtgen ve kenar tamponu.
        val ku = 1 + 2 * mu; val kv = 1 + 2 * mv
        fun xi(u: Double) = Math.round((u + mu) / ku * r.w).toInt()
        fun yi(v: Double) = Math.round((v + mv) / kv * r.h).toInt()
        val ix0 = xi(0.0); val ix1 = xi(1.0); val iy0 = yi(0.0); val iy1 = yi(1.0)
        if (ix1 - ix0 < 32 || iy1 - iy0 < 32) return null
        val inside = map.fraction(Span(ix0, ix1), Span(iy0, iy1))
        var ringFrac = 0.0
        if (ring) {
            // Kenar çizgisinin kendi yanıtı ve köşe sapması için tampon; her kenar ayrı.
            val gx = Math.round(ringGuard / ringMargin * ix0).toInt()
            val gy = Math.round(ringGuard / ringMargin * iy0).toInt()
            val sides = listOf(
                map.fraction(Span(0, r.w), Span(0, max(0, iy0 - gy))),                     // üst
                map.fraction(Span(0, r.w), Span(min(r.h, iy1 + gy), r.h)),                 // alt
                map.fraction(Span(0, max(0, ix0 - gx)), Span(iy0, iy1)),                     // sol
                map.fraction(Span(min(r.w, ix1 + gx), r.w), Span(iy0, iy1)),                 // sağ
            ).sorted()
            // Arayüz bir-iki kenara yapışık olabilir (araç çubuğu, küçük resimler): en temiz iki
            // kenarın büyüğü ölçü alınır.
            ringFrac = sides[1]
        }
        val band = map.contentBands(Span(ix0, ix1), Span(iy0, iy1)) ?: (Span(ix0, ix1) to Span(iy0, iy1))
        val bx = band.first; val by = band.second
        // Asıl dörtgene göre normalize içerik → kaynak dörtgen.
        val lx = (bx.lo - ix0).toDouble() / (ix1 - ix0); val ly = (by.lo - iy0).toDouble() / (iy1 - iy0)
        val lw = bx.count.toDouble() / (ix1 - ix0); val lh = by.count.toDouble() / (iy1 - iy0)
        val trimmed = if (lw > 0.995 && lh > 0.995) q else q.subQuad(lx, ly, lw, lh)
        if (!(trimmed.isValid || trimmed.isFull)) return null
        val content = Rect(
            bx.lo.toFloat() / r.w, by.lo.toFloat() / r.h,
            bx.hi.toFloat() / r.w, by.hi.toFloat() / r.h,
        )
        return Candidate(
            source = q, quad = trimmed, raster = r, content = content, inside = inside, ring = ringFrac,
            chroma = meanChroma(r, content),
            aspect = bx.count.toDouble() / max(1, by.count),
        )
    }

    /** Yarı açık tamsayı aralığı (Swift `Range<Int>`). */
    private data class Span(val lo: Int, val hi: Int) {
        val count: Int get() = max(0, hi - lo)
        val isEmpty: Boolean get() = hi <= lo
        operator fun contains(i: Int): Boolean = i >= lo && i < hi
    }

    /**
     * Doku haritası: bant geçiren fark (3 px − 7 px kutu ortalaması) eşiği aşan piksel. Sensör
     * gürültüsünü ve yansıma/vinyet gibi yavaş ışık değişimlerini saymaz, diş/kemik ayrıntısını
     * sayar. Görselin dışına taşan (saydam) kısım ve onun 4 px yakını geçersizdir.
     */
    private class TextureMap(r: Raster) {
        val w = r.w
        val h = r.h
        val tex = ByteArray(w * h)
        val valid = ByteArray(w * h)
        /** 3×3 ortalamalı gri (kenar adımı ölçümü için). */
        val smooth: FloatArray

        init {
            val fine = boxBlur(r.gray, w, h, 1)
            smooth = fine
            val coarse = boxBlur(r.gray, w, h, 3)
            val opaque = IntArray(w * h)
            for (i in 0 until w * h) if ((r.argb[i] ushr 24) == 255) opaque[i] = 255
            val solid = boxBlur(opaque, w, h, 4)
            for (i in 0 until w * h) {
                if (solid[i] >= 254.5f) valid[i] = 1
                if (valid[i].toInt() == 1 && abs(fine[i] - coarse[i]) >= textureThreshold) tex[i] = 1
            }
        }

        fun fraction(x: Span, y: Span): Double {
            var t = 0; var n = 0
            for (yy in y.lo until y.hi) {
                val row = yy * w
                for (xx in x.lo until x.hi) {
                    val i = row + xx
                    if (valid[i].toInt() == 1) { n++; t += tex[i] }
                }
            }
            return if (n == 0) 0.0 else t.toDouble() / n
        }

        /**
         * Satır/sütun profilleriyle röntgen içeriği: neredeyse boş kenar şeritleri (film payı,
         * arayüz arka planı, siyah/beyaz boşluk) atılır; en uzun dolu bant kalır. Profil dokulu
         * piksel ORANIDIR — film kenarı gibi tek güçlü çizgi profili ele geçiremez.
         */
        fun contentBands(x: Span, y: Span): Pair<Span, Span>? {
            var rows = y
            // İlk satır profili orta %60'tan: çerçeve/panel kenar çizgileri ve yan paneller her
            // satıra birkaç dokulu piksel ekleyip boş şeritleri dolu gösterebilir.
            var cols = Span(x.lo + x.count / 5, x.hi - x.count / 5)
            var found = false
            for (pass in 0 until 2) {
                val c0 = cols
                val rs = FloatArray(y.count) { k ->
                    val yy = y.lo + k
                    var s = 0; var n = 0
                    for (xx in c0.lo until c0.hi) { val i = yy * w + xx; if (valid[i].toInt() == 1) { n++; s += tex[i] } }
                    if (n == 0) 0f else s.toFloat() / n
                }
                val r = dominantBand(rs) ?: break
                rows = Span(r.lo + y.lo, r.hi + y.lo)
                val r0 = rows
                val cs = FloatArray(x.count) { k ->
                    val xx = x.lo + k
                    var s = 0; var n = 0
                    for (yy in r0.lo until r0.hi) { val i = yy * w + xx; if (valid[i].toInt() == 1) { n++; s += tex[i] } }
                    if (n == 0) 0f else s.toFloat() / n
                }
                val c = dominantBand(cs) ?: break
                cols = Span(c.lo + x.lo, c.hi + x.lo)
                found = true
            }
            if (!found) return null
            // Kırpma kanıtı: bir kenar ancak yeni sınırın hemen dışı boşsa ya da sınır boyunca düz,
            // kesintisiz bir kenar varsa kırpılır. Kanıt yoksa röntgenin sakin kenar bölgesi (çene
            // altı, ramus, 20 yaş dişleri) kesilmesin diye o kenar korunur.
            val sy = max(3, h / 33); val sx = max(3, w / 33)
            if (rows.lo > y.lo &&
                !(isEmpty(cols, Span(max(y.lo, rows.lo - sy), rows.lo)) || hasEdgeRow(rows.lo, cols))
            ) rows = Span(y.lo, rows.hi)
            if (rows.hi < y.hi &&
                !(isEmpty(cols, Span(rows.hi, min(y.hi, rows.hi + sy))) || hasEdgeRow(rows.hi, cols))
            ) rows = Span(rows.lo, y.hi)
            if (cols.lo > x.lo &&
                !(isEmpty(Span(max(x.lo, cols.lo - sx), cols.lo), rows) || hasEdgeCol(cols.lo, rows))
            ) cols = Span(x.lo, cols.hi)
            if (cols.hi < x.hi &&
                !(isEmpty(Span(cols.hi, min(x.hi, cols.hi + sx)), rows) || hasEdgeCol(cols.hi, rows))
            ) cols = Span(cols.lo, x.hi)
            return cols to rows
        }

        private fun isEmpty(x: Span, y: Span): Boolean = !x.isEmpty && !y.isEmpty && fraction(x, y) < 0.006

        /**
         * Sınır satırının ±%2'sinde, satırın en az %75'inde aynı yerde belirgin parlaklık adımı
         * var mı? (Röntgen–zemin sınırı düz ve kesintisizdir; iç yapılar eğri ve kesiklidir.)
         */
        private fun hasEdgeRow(r: Int, cols: Span): Boolean {
            val k = max(3, h / 50)
            for (yy in max(2, r - k)..min(h - 3, r + k)) {
                var strong = 0
                for (x in cols.lo until cols.hi) if (abs(smooth[(yy + 2) * w + x] - smooth[(yy - 2) * w + x]) >= 6) strong++
                if (strong.toDouble() >= 0.75 * max(1, cols.count)) return true
            }
            return false
        }

        private fun hasEdgeCol(c: Int, rows: Span): Boolean {
            val k = max(3, w / 50)
            for (xx in max(2, c - k)..min(w - 3, c + k)) {
                var strong = 0
                for (y in rows.lo until rows.hi) if (abs(smooth[y * w + xx + 2] - smooth[y * w + xx - 2]) >= 6) strong++
                if (strong.toDouble() >= 0.75 * max(1, rows.count)) return true
            }
            return false
        }
    }

    /** Dokulu piksel eşiği (512 px'te, 0–255 düzey). */
    private const val textureThreshold: Float = 2.0f
    /** Satır/sütun/bloğun "dolu" sayılması için dokulu piksel oranı. */
    private const val bandThreshold: Float = 0.02f

    /** Kutu ortalaması (integral görüntü; kenarlarda pencere kırpılır). */
    internal fun boxBlur(g: IntArray, w: Int, h: Int, radius: Int): FloatArray {
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += g[y * w + x]
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
                val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                out[y * w + x] = s.toFloat() / ((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    /** Dokulu satırların en uzun kesintisiz bandı (küçük boşluklar kapatılır). */
    private fun dominantBand(s: FloatArray): Span? {
        val n = s.size
        if (n == 0) return null
        val sorted = s.sortedArray()
        val ref = sorted[min(n - 1, n * 9 / 10)]
        if (ref < 0.06f) return null                // hiç doku yok
        // Mutlak, düşük eşik: yalnız neredeyse boş şeritler atılır.
        val on = BooleanArray(n) { s[it] >= bandThreshold }
        val gap = max(1, n / 256)
        var i = 0
        while (i < n) {
            if (!on[i]) {
                var j = i
                while (j < n && !on[j]) j++
                if (i > 0 && j < n && j - i <= gap) for (k in i until j) on[k] = true
                i = j
            } else i++
        }
        var best = Span(0, 0)
        var start = -1
        for (k in 0..n) {
            if (k < n && on[k]) {
                if (start < 0) start = k
            } else if (start >= 0) {
                if (k - start > best.count) best = Span(start, k)
                start = -1
            }
        }
        return if (best.count >= n / 5) best else null
    }

    private fun meanChroma(r: Raster, rect: Rect): Double {
        val x0 = (rect.left * r.w).toInt(); val x1 = max(x0 + 1, (rect.right * r.w).toInt())
        val y0 = (rect.top * r.h).toInt(); val y1 = max(y0 + 1, (rect.bottom * r.h).toInt())
        var sum = 0L; var n = 0
        var y = y0
        while (y < min(y1, r.h)) {
            var x = x0
            while (x < min(x1, r.w)) {
                val c = r.argb[y * r.w + x]
                val red = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                sum += maxOf(red, g, b) - minOf(red, g, b)
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 1.0 else sum.toDouble() / n / 255
    }

    /** Ortalama gradyan / kontrast aralığı — odak ve titreme ölçüsü. */
    fun sharpness(g: IntArray, w: Int, h: Int, rect: Rect): Double {
        val x0 = max(1, (rect.left * w).toInt()); val x1 = min(w - 1, (rect.right * w).toInt())
        val y0 = max(1, (rect.top * h).toInt()); val y1 = min(h - 1, (rect.bottom * h).toInt())
        if (x1 <= x0 + 4 || y1 <= y0 + 4) return 0.0
        var sum = 0L; var n = 0
        val hist = IntArray(256)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val i = y * w + x
                sum += abs(g[i + 1] - g[i - 1]) + abs(g[i + w] - g[i - w])
                hist[g[i]]++
                n++
            }
        }
        val (lo, hi) = percentiles(hist, n, 0.05, 0.95)
        return sum.toDouble() / n / max(16, hi - lo)
    }

    private fun clippedFraction(g: IntArray, w: Int, h: Int, rect: Rect): Double {
        val x0 = (rect.left * w).toInt(); val x1 = (rect.right * w).toInt()
        val y0 = (rect.top * h).toInt(); val y1 = (rect.bottom * h).toInt()
        var c = 0; var n = 0
        for (y in y0 until max(y0, min(h, y1))) for (x in x0 until max(x0, min(w, x1))) {
            if (g[y * w + x] >= 250) c++
            n++
        }
        return if (n == 0) 0.0 else c.toDouble() / n
    }

    // MARK: - Hazırlama

    /**
     * Seçilen alanı düzleştirir (perspektif), gri tona çevirir, yükleme boyutuna indirir.
     * `enhance` açıksa ton dengelenir (yumuşak siyah/beyaz noktası + orta tonu dijital
     * panoramik düzeyine getiren gama). Ekran deseni (moiré) önceden `removeScreenPattern` ile temizlenir.
     */
    fun prepare(cg: Bitmap, quad: XrayQuad, enhance: Boolean): Bitmap {
        val flat = rectified(cg, quad)
        val image = scaledToFit(flat, uploadLongEdge)
        if (flat !== cg && flat !== image) flat.recycle()
        if (image.width <= 16 || image.height <= 16) throw ProcessingError.encodingFailed()
        val w = image.width; val h = image.height
        val px = IntArray(w * h)
        image.getPixels(px, 0, w, 0, 0, w, h)
        if (image !== cg) image.recycle()
        // Doygunluk 0 (Core Image `colorControls` luma ağırlıkları).
        val gray = IntArray(w * h) { i ->
            val c = px[i]
            val v = 0.2125 * ((c shr 16) and 0xFF) + 0.7154 * ((c shr 8) and 0xFF) + 0.0721 * (c and 0xFF)
            v.roundToInt().coerceIn(0, 255)
        }
        if (enhance) applyTone(gray)
        return grayImage(gray, w, h)
    }

    // MARK: - Moiré / piksel ızgarası

    /**
     * Ekran fotoğrafındaki ince tekrar eden deseni (monitör piksel ızgarası, sensörde oluşan
     * moiré) temizler. Model bu desende dişleri bulamıyor; dijital röntgende ve desensiz
     * fotoğrafta hiçbir şey yapmaz. Desenin periyodu perspektif yüzünden görüntü boyunca
     * değiştiği için ~512 px'lik döşemelerde ayrı ayrı bulunur ve o döşemeye kendi periyoduyla
     * uygulanır. Ayrıştırmadan ÖNCE çalışır: desen, doku ölçüsünü de bozar.
     */
    fun removeScreenPattern(cg: Bitmap): Bitmap {
        val W = cg.width; val H = cg.height
        val nx = max(1, (W / 512.0).roundToInt()); val ny = max(1, (H / 512.0).roundToInt())
        val tw = W.toDouble() / nx; val th = H.toDouble() / ny
        var out: Bitmap? = null
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                val x0 = max(0, floor(i * tw).toInt()); val x1 = min(W, ceil((i + 1) * tw).toInt())
                val y0 = max(0, floor(j * th).toInt()); val y1 = min(H, ceil((j + 1) * th).toInt())
                val tw0 = x1 - x0; val th0 = y1 - y0
                if (tw0 <= 0 || th0 <= 0) continue
                val tilePx = IntArray(tw0 * th0)
                cg.getPixels(tilePx, 0, tw0, x0, y0, tw0, th0)
                val gray = IntArray(tw0 * th0) { k -> luma(tilePx[k]) }
                val px = dominantPeriod(gray, tw0, th0, alongRows = true)
                val py = dominantPeriod(gray, tw0, th0, alongRows = false)
                if (px == null && py == null) continue
                val dst = out ?: cg.copy(Bitmap.Config.ARGB_8888, true).also { out = it }
                suppressPattern(cg, dst, x0, y0, x1, y1, px, py)
            }
        }
        return out ?: cg
    }

    /** Satır (ya da sütun) boyunca ortalama güç tayfında 2–6,5 px periyotlu keskin tepe. */
    fun dominantPeriod(g: IntArray, w: Int, h: Int, alongRows: Boolean): Double? {
        val len = if (alongRows) w else h
        val lines = if (alongRows) h else w
        var log2n = 0
        while ((1 shl (log2n + 1)) <= min(len, 1024)) log2n++
        if (log2n < 8 || lines < 16) return null
        val n = 1 shl log2n; val half = n / 2; val off = (len - n) / 2
        val window = DoubleArray(n) { 0.5 * (1 - cos(2 * PI * it / n)) }
        val power = DoubleArray(half)
        val re = DoubleArray(n); val im = DoubleArray(n)
        var l = 0
        val step = max(1, lines / 256)
        while (l < lines) {
            var mean = 0.0
            for (i in 0 until n) {
                val v = (if (alongRows) g[l * w + off + i] else g[(off + i) * w + l]).toDouble()
                re[i] = v; mean += v
            }
            mean /= n
            for (i in 0 until n) { re[i] = (re[i] - mean) * window[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in 1 until half) power[k] += re[k] * re[k] + im[k] * im[k]
            l += step
        }
        var bestPeriod: Double? = null
        var bestRatio = 0.0
        val kLo = ceil(n / 6.5).toInt(); val kHi = min(half - 1, (n * 0.48).toInt())
        if (kLo >= kHi) return null
        val nb = ArrayList<Double>(64)
        for (k in kLo..kHi) {
            val a = (k * 0.75).toInt(); val b = min(half - 1, (k * 1.25).toInt())
            nb.clear()
            for (jj in a..b) if (abs(jj - k) > 2) nb += power[jj]
            if (nb.size < 4) continue
            nb.sort()
            val ratio = power[k] / max(nb[nb.size / 2], 1e-9)
            if (ratio >= 12 && ratio > bestRatio) { bestPeriod = n.toDouble() / k; bestRatio = ratio }
        }
        return bestPeriod
    }

    /** Yerinde, yinelemeli radix-2 karmaşık FFT (n ikinin kuvveti). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val ang = -2 * PI / size
            val wr = cos(ang); val wi = sin(ang)
            var start = 0
            while (start < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until size / 2) {
                    val a = start + k; val b = a + size / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                start += size
            }
            size = size shl 1
        }
    }

    /**
     * Periyot genişliğinde (kesirli) kutu filtresi: desenin temel frekansını ve tüm
     * harmoniklerini tam sıfırlar; ekranın zaten gösteremediği ayrıntı dışında röntgene dokunmaz.
     * Kaynak `src`'den okunur (kenarda kenar pikseli tekrarlanır), döşeme `dst`'ye yazılır.
     */
    private fun suppressPattern(src: Bitmap, dst: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, px: Double?, py: Double?) {
        val W = src.width; val H = src.height
        val ex0 = max(0, x0 - 4); val ex1 = min(W, x1 + 4)
        val ey0 = max(0, y0 - 4); val ey1 = min(H, y1 + 4)
        val ew = ex1 - ex0; val eh = ey1 - ey0
        val pix = IntArray(ew * eh)
        src.getPixels(pix, 0, ew, ex0, ey0, ew, eh)
        val tw = x1 - x0
        // Yatay geçiş: döşeme sütunları × genişletilmiş satırlar.
        val hr = FloatArray(tw * eh); val hg = FloatArray(tw * eh); val hb = FloatArray(tw * eh)
        val kx = px?.let { boxWeights(it) }
        for (yy in 0 until eh) {
            for (xx in 0 until tw) {
                val x = x0 + xx
                var r = 0.0; var g = 0.0; var b = 0.0
                if (kx != null) {
                    for (t in 0 until 9) {
                        val sx = (x + t - 4).coerceIn(ex0, ex1 - 1) - ex0
                        val c = pix[yy * ew + sx]
                        r += kx[t] * ((c shr 16) and 0xFF); g += kx[t] * ((c shr 8) and 0xFF); b += kx[t] * (c and 0xFF)
                    }
                } else {
                    val c = pix[yy * ew + (x - ex0)]
                    r = ((c shr 16) and 0xFF).toDouble(); g = ((c shr 8) and 0xFF).toDouble(); b = (c and 0xFF).toDouble()
                }
                val o = yy * tw + xx
                hr[o] = r.toFloat(); hg[o] = g.toFloat(); hb[o] = b.toFloat()
            }
        }
        // Dikey geçiş: döşeme satırları.
        val th = y1 - y0
        val outPx = IntArray(tw * th)
        val ky = py?.let { boxWeights(it) }
        for (yy in 0 until th) {
            val y = y0 + yy
            for (xx in 0 until tw) {
                var r = 0.0; var g = 0.0; var b = 0.0
                if (ky != null) {
                    for (t in 0 until 9) {
                        val sy = (y + t - 4).coerceIn(ey0, ey1 - 1) - ey0
                        val o = sy * tw + xx
                        r += ky[t] * hr[o]; g += ky[t] * hg[o]; b += ky[t] * hb[o]
                    }
                } else {
                    val o = (y - ey0) * tw + xx
                    r = hr[o].toDouble(); g = hg[o].toDouble(); b = hb[o].toDouble()
                }
                outPx[yy * tw + xx] = AColor.argb(
                    255, r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255),
                )
            }
        }
        dst.setPixels(outPx, 0, tw, x0, y0, tw, th)
    }

    private fun boxWeights(p: Double): DoubleArray {
        val k = DoubleArray(9)
        for (j in -4..4) {
            val a = max(j - 0.5, -p / 2); val b = min(j + 0.5, p / 2)
            k[j + 4] = max(0.0, b - a)
        }
        val sum = k.sum()
        return DoubleArray(9) { k[it] / sum }
    }

    /**
     * Ton dengesi: p0.1–p99.9 aralığına yumuşak germe, ardından ortanca ≈ 0.38 (dijital
     * panoramik ortalaması) olacak gama. Ekrandan çekimde kalkık siyahı ve karanlık görüntüyü
     * düzeltir; dijital röntgene yakın bir görüntüde neredeyse hiçbir şeyi değiştirmez.
     */
    fun applyTone(g: IntArray) {
        val hist = IntArray(256)
        for (v in g) hist[v]++
        val (lo, hi) = percentiles(hist, g.size, 0.001, 0.999)
        if (hi - lo < 24) return
        val span = (hi - lo).toDouble()
        val stretched = IntArray(256)
        for (v in 0 until 256) stretched[Math.round(((v - lo) / span).coerceIn(0.0, 1.0) * 255).toInt()] += hist[v]
        val (mid, _) = percentiles(stretched, g.size, 0.5, 0.5)
        val m = min(0.9, max(0.05, mid / 255.0))
        val gamma = min(1.7, max(0.6, ln(0.38) / ln(m)))
        val lut = IntArray(256) { v -> Math.round(((v - lo) / span).coerceIn(0.0, 1.0).pow(gamma) * 255).toInt() }
        for (i in g.indices) g[i] = lut[g[i]]
    }

    /** Kalite uyarıları (hazırlanmış görüntüde). */
    fun assessQuality(cg: Bitmap): XrayQualityReport {
        val isSmall = max(cg.width, cg.height) < 1200
        val r = raster(cg, 1024.0) ?: return XrayQualityReport(isSmall = isSmall)
        val all = Rect(0.02f, 0.02f, 0.98f, 0.98f)
        return XrayQualityReport(
            isBlurry = sharpness(r.gray, r.w, r.h, all) < blurThreshold,
            isSmall = isSmall,
            isOverexposed = clippedFraction(r.gray, r.w, r.h, all) > 0.06,
        )
    }

    /** 1024 px'teki netlik eşiği — dijital panoramikler ≥0,074; bulanık çekim bunun altına düşer. */
    const val blurThreshold = 0.045

    /** Sunucuya giden JPEG (metadata'sız, en uzun kenar sınırlı). */
    fun uploadJPEG(cg: Bitmap): ByteArray {
        var image = cg
        if (max(cg.width, cg.height) > uploadLongEdge + 1) {
            val r = raster(cg, uploadLongEdge) ?: throw ProcessingError.encodingFailed()
            image = grayImage(r.gray, r.w, r.h)
        }
        val out = ByteArrayOutputStream()
        if (!image.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw ProcessingError.encodingFailed()
        return out.toByteArray()
    }

    // MARK: - Yardımcılar

    /** ARGB tampon (görsel dışı saydam) + gri kanal; satır 0 görselin üstüdür. */
    internal class Raster(val w: Int, val h: Int, val argb: IntArray, val gray: IntArray)

    private fun luma(c: Int): Int = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /**
     * Dörtgeni düzleştirir. Çıktı boyutu dörtgenin ortalama kenar uzunlukları (kaynak piksel) —
     * iOS'taki CIPerspectiveCorrection en-boy hatasının düzeltilmiş hâli.
     */
    private fun rectified(image: Bitmap, quad: XrayQuad): Bitmap {
        if (quad.isFull) return image
        val ew = image.width.toDouble(); val eh = image.height.toDouble()
        fun dist(a: Offset, b: Offset) = hypot((a.x - b.x) * ew, (a.y - b.y) * eh)
        val w = (dist(quad.topLeft, quad.topRight) + dist(quad.bottomLeft, quad.bottomRight)) / 2
        val h = (dist(quad.topLeft, quad.bottomLeft) + dist(quad.topRight, quad.bottomRight)) / 2
        val ow = max(1, w.roundToInt()); val oh = max(1, h.roundToInt())
        val src = floatArrayOf(
            (quad.topLeft.x * ew).toFloat(), (quad.topLeft.y * eh).toFloat(),
            (quad.topRight.x * ew).toFloat(), (quad.topRight.y * eh).toFloat(),
            (quad.bottomRight.x * ew).toFloat(), (quad.bottomRight.y * eh).toFloat(),
            (quad.bottomLeft.x * ew).toFloat(), (quad.bottomLeft.y * eh).toFloat(),
        )
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return image
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return out
    }

    /**
     * En uzun kenarı sınırlar. Büyük küçültmelerde yarıya inen kademelerle (örtüşmesiz,
     * Lanczos'a yakın kalite), son adım çift doğrusal.
     */
    internal fun scaledToFit(image: Bitmap, longEdge: Double): Bitmap {
        val long = max(image.width, image.height).toDouble()
        if (long <= longEdge || !longEdge.isFinite()) return image
        val s = longEdge / long
        val tw = max(1, (image.width * s).roundToInt()); val th = max(1, (image.height * s).roundToInt())
        var cur = image
        while (cur.width / 2 >= tw * 1.0 && cur.height / 2 >= th * 1.0 && cur.width / 2 > tw) {
            val next = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            if (cur !== image) cur.recycle()
            cur = next
        }
        if (cur.width == tw && cur.height == th) return cur
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== image && cur !== out) cur.recycle()
        return out
    }

    /** Opak ARGB_8888 kopya (saydam alan siyah — iOS `opaque` renderer gibi). */
    private fun opaque(image: Bitmap): Bitmap {
        if (image.config == Bitmap.Config.ARGB_8888 && !image.hasAlpha()) return image
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(AColor.BLACK)
        c.drawBitmap(image, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        out.setHasAlpha(false)
        return out
    }

    /** ARGB tampon (görsel dışı saydam) + gri kanal. */
    internal fun raster(image: Bitmap, longEdge: Double): Raster? {
        val scaled = scaledToFit(image, longEdge)
        val w = scaled.width; val h = scaled.height
        if (w <= 0 || h <= 0) return null
        val soft = if (scaled.config == Bitmap.Config.HARDWARE) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
        val px = IntArray(w * h)
        soft.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { luma(px[it]) }
        return Raster(w, h, px, gray)
    }

    private fun grayImage(g: IntArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { val v = g[it]; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    internal fun percentiles(hist: IntArray, total: Int, a: Double, b: Double): Pair<Int, Int> {
        val ta = (total * a).toInt(); val tb = (total * b).toInt()
        var acc = 0; var pa = 0; var pb = 255; var foundA = false
        for (v in hist.indices) {
            acc += hist[v]
            if (!foundA && acc > ta) { pa = v; foundA = true }
            if (acc > tb || (b >= 1 && acc >= total)) { pb = v; break }
        }
        return pa to pb
    }
}

/**
 * Vision `VNDetectRectanglesRequest` + `VNDetectDocumentSegmentationRequest` yerine aday
 * dörtgen üreticisi (ek kütüphane olmadan):
 * - `rectangles`: Sobel kenarları → yön kısıtlı Hough doğruları → yatay/dikey doğru çiftlerinden
 *   dörtgenler; dört kenarın kenar pikselleriyle örtüşme oranı güven puanıdır.
 * - `document`: Otsu eşiğiyle parlak bölge (negatoskop paneli, film, ekran) → dışbükey zarf →
 *   dört köşeye indirgenmiş dörtgen.
 * Sonuçlar normalize (0…1, sol-üst orijin). Seçim ve doğrulama `XrayImageProcessor`'dadır.
 */
internal object XrayQuadDetector {
    private const val workEdge = 400.0
    /** Vision `minimumSize` (kısa boyuta oranla). */
    private const val minSize = 0.15
    /** Vision `minimumAspectRatio` (kısa / uzun). */
    private const val minAspect = 0.2
    private const val peaksPerGroup = 12

    private class Line(val theta: Int, val rho: Double, val votes: Int) {
        val c = cos(theta * PI / 180)
        val s = sin(theta * PI / 180)
    }

    fun rectangles(image: Bitmap, maxCount: Int): List<XrayQuad> {
        val r = XrayImageProcessor.raster(image, workEdge) ?: return emptyList()
        val w = r.w; val h = r.h
        if (w < 32 || h < 32) return emptyList()
        // Hafif yumuşatma (iki kez 3×3 kutu ≈ Gauss).
        val g1 = XrayImageProcessor.boxBlur(r.gray, w, h, 1)
        val g = boxBlurF(g1, w, h, 1)
        // Sobel
        val mag = FloatArray(w * h)
        val ang = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
            val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
            mag[i] = sqrt(gx * gx + gy * gy)
            ang[i] = atan2(gy, gx)
        }
        // Eşik: yüzdelik 85 (en az 16).
        val sortedMag = mag.copyOf().also { it.sort() }
        val thr = max(16f, sortedMag[(sortedMag.size * 0.85).toInt().coerceIn(0, sortedMag.size - 1)])
        // İnceltme (gradyan yönünde yerel en büyük).
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m < thr) continue
            var a = Math.toDegrees(ang[i].toDouble())
            if (a < 0) a += 180.0
            val (n1, n2) = when {
                a < 22.5 || a >= 157.5 -> (i - 1) to (i + 1)
                a < 67.5 -> (i - w - 1) to (i + w + 1)
                a < 112.5 -> (i - w) to (i + w)
                else -> (i - w + 1) to (i + w - 1)
            }
            if (m >= mag[n1] && m >= mag[n2]) edge[i] = true
        }
        // Kenar desteği için 2 px genişletilmiş harita.
        val near = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!edge[y * w + x]) continue
            for (dy in -2..2) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -2..2) {
                    val xx = x + dx
                    if (xx in 0 until w) near[yy * w + xx] = true
                }
            }
        }
        // Hough (her kenar pikseli yalnız kendi gradyan yönünün ±6°'sine oy verir).
        val diag = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val rhoBins = 2 * diag + 1
        val acc = IntArray(180 * rhoBins)
        val cs = DoubleArray(180) { cos(it * PI / 180) }
        val sn = DoubleArray(180) { sin(it * PI / 180) }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!edge[i]) continue
            var a = Math.toDegrees(ang[i].toDouble())
            a = ((a % 180) + 180) % 180
            val t0 = a.roundToInt()
            for (d in -6..6) {
                val t = ((t0 + d) % 180 + 180) % 180
                val rho = (x * cs[t] + y * sn[t]).roundToInt() + diag
                acc[t * rhoBins + rho]++
            }
        }
        val minDim = min(w, h)
        val minVotes = max(20, (0.12 * minDim).toInt())
        val peaks = ArrayList<Line>()
        for (t in 0 until 180) for (rb in 0 until rhoBins) {
            val v = acc[t * rhoBins + rb]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -3..3) {
                val tt = t + dt
                if (tt < 0 || tt >= 180) continue
                for (dr in -5..5) {
                    val rr = rb + dr
                    if (rr < 0 || rr >= rhoBins || (dt == 0 && dr == 0)) continue
                    val o = acc[tt * rhoBins + rr]
                    if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break@loop }
                }
            }
            if (isMax) peaks += Line(t, (rb - diag).toDouble(), v)
        }
        peaks.sortByDescending { it.votes }
        val kept = ArrayList<Line>()
        for (p in peaks) {
            val dup = kept.any { k ->
                val dt = abs(k.theta - p.theta)
                (dt <= 4 && abs(k.rho - p.rho) <= 8) || (dt >= 176 && abs(k.rho + p.rho) <= 8)
            }
            if (!dup) kept += p
        }
        val horizontal = kept.filter { it.theta in 50..130 }.take(peaksPerGroup)
        val vertical = kept.filter { it.theta <= 40 || it.theta >= 140 }.take(peaksPerGroup)
        if (horizontal.size < 2 || vertical.size < 2) return emptyList()

        val cx = w / 2.0; val cy = h / 2.0
        fun yAt(l: Line, x: Double) = (l.rho - x * l.c) / l.s
        fun xAt(l: Line, y: Double) = (l.rho - y * l.s) / l.c
        fun intersect(a: Line, b: Line): Offset? {
            val det = a.c * b.s - a.s * b.c
            if (abs(det) < 1e-6) return null
            val x = (a.rho * b.s - a.s * b.rho) / det
            val y = (a.c * b.rho - a.rho * b.c) / det
            return Offset(x.toFloat(), y.toFloat())
        }
        fun support(a: Offset, b: Offset): Double {
            val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            val n = max(12, (len / 3).toInt())
            var hit = 0; var total = 0
            for (k in 0..n) {
                val t = k.toDouble() / n
                val x = (a.x + (b.x - a.x) * t).roundToInt(); val y = (a.y + (b.y - a.y) * t).roundToInt()
                total++
                if (x in 0 until w && y in 0 until h && near[y * w + x]) hit++
            }
            return hit.toDouble() / max(1, total)
        }
        val tolX = 0.02 * w; val tolY = 0.02 * h
        fun inside(p: Offset) = p.x >= -tolX && p.x <= w + tolX && p.y >= -tolY && p.y <= h + tolY
        fun norm(p: Offset) = Offset((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))

        val scored = ArrayList<Pair<XrayQuad, Double>>()
        for (i in horizontal.indices) for (j in i + 1 until horizontal.size) {
            var top = horizontal[i]; var bottom = horizontal[j]
            if (yAt(top, cx) > yAt(bottom, cx)) { val t = top; top = bottom; bottom = t }
            if (yAt(bottom, cx) - yAt(top, cx) < minSize * minDim) continue
            for (k in vertical.indices) for (l in k + 1 until vertical.size) {
                var left = vertical[k]; var right = vertical[l]
                if (xAt(left, cy) > xAt(right, cy)) { val t = left; left = right; right = t }
                if (xAt(right, cy) - xAt(left, cy) < minSize * minDim) continue
                val tl = intersect(top, left) ?: continue
                val tr = intersect(top, right) ?: continue
                val br = intersect(bottom, right) ?: continue
                val bl = intersect(bottom, left) ?: continue
                if (!inside(tl) || !inside(tr) || !inside(br) || !inside(bl)) continue
                val sTop = support(tl, tr); val sRight = support(tr, br)
                val sBottom = support(br, bl); val sLeft = support(bl, tl)
                if (minOf(sTop, sRight, sBottom, sLeft) < 0.45) continue
                val q = XrayQuad(norm(tl), norm(tr), norm(br), norm(bl))
                val wq = (hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble()) + hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())) / 2
                val hq = (hypot((bl.x - tl.x).toDouble(), (bl.y - tl.y).toDouble()) + hypot((br.x - tr.x).toDouble(), (br.y - tr.y).toDouble())) / 2
                if (min(wq, hq) / max(1.0, max(wq, hq)) < minAspect) continue
                scored += q to (sTop + sRight + sBottom + sLeft) / 4
            }
        }
        scored.sortByDescending { it.second }
        val out = ArrayList<XrayQuad>()
        for ((q, _) in scored) {
            val dup = out.any { u -> u.points.zip(q.points).all { (a, b) -> hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < 0.02 } }
            if (!dup) out += q
            if (out.size >= maxCount) break
        }
        return out
    }

    /** Parlak bölge bölütleme (belge bölütlemenin karşılığı). Bulamazsa null. */
    fun document(image: Bitmap): XrayQuad? {
        val r = XrayImageProcessor.raster(image, 256.0) ?: return null
        val w = r.w; val h = r.h
        if (w < 32 || h < 32) return null
        val g = XrayImageProcessor.boxBlur(r.gray, w, h, 2)
        // Otsu eşiği
        val hist = IntArray(256)
        for (v in g) hist[v.roundToInt().coerceIn(0, 255)]++
        val total = w * h
        var sumAll = 0.0
        for (v in 0 until 256) sumAll += v.toDouble() * hist[v]
        var wB = 0; var sumB = 0.0; var bestVar = -1.0; var thr = 128
        for (v in 0 until 256) {
            wB += hist[v]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += v.toDouble() * hist[v]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > bestVar) { bestVar = between; thr = v }
        }
        val mask = BooleanArray(total) { g[it] > thr }
        // En büyük parlak bileşen (4-komşuluk).
        val label = IntArray(total) { -1 }
        var bestLabel = -1; var bestArea = 0; var next = 0
        val stack = IntArray(total)
        for (start in 0 until total) {
            if (!mask[start] || label[start] >= 0) continue
            var sp = 0; stack[sp++] = start; label[start] = next
            var area = 0
            while (sp > 0) {
                val p = stack[--sp]; area++
                val x = p % w; val y = p / w
                if (x > 0 && mask[p - 1] && label[p - 1] < 0) { label[p - 1] = next; stack[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && label[p + 1] < 0) { label[p + 1] = next; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - w] && label[p - w] < 0) { label[p - w] = next; stack[sp++] = p - w }
                if (y < h - 1 && mask[p + w] && label[p + w] < 0) { label[p + w] = next; stack[sp++] = p + w }
            }
            if (area > bestArea) { bestArea = area; bestLabel = next }
            next++
        }
        if (bestLabel < 0) return null
        val frac = bestArea.toDouble() / total
        if (frac < minSize * minSize || frac > 0.985) return null
        // Satır uçlarından dışbükey zarf.
        val pts = ArrayList<Offset>()
        for (y in 0 until h) {
            var lo = -1; var hi = -1
            for (x in 0 until w) if (label[y * w + x] == bestLabel) { if (lo < 0) lo = x; hi = x }
            if (lo >= 0) { pts += Offset(lo.toFloat(), y.toFloat()); pts += Offset(hi + 1f, y.toFloat()) }
        }
        val hull = convexHull(pts)
        if (hull.size < 4) return null
        val quad = reduceToFour(hull)
        val qArea = polygonArea(quad)
        if (qArea <= 0) return null
        // Doluluk oranı = güven (Vision `confidence > 0.3` karşılığı).
        val fill = bestArea / qArea
        if (fill < 0.85 || fill > 1.15) return null
        val ordered = orderCorners(quad)
        return XrayQuad(
            Offset(ordered[0].x / w, ordered[0].y / h), Offset(ordered[1].x / w, ordered[1].y / h),
            Offset(ordered[2].x / w, ordered[2].y / h), Offset(ordered[3].x / w, ordered[3].y / h),
        )
    }

    private fun boxBlurF(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val integ = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
                val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        return out
    }

    /** Monoton zincir dışbükey zarf. */
    private fun convexHull(points: List<Offset>): List<Offset> {
        val p = points.distinct().sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })
        if (p.size < 3) return p
        fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x).toDouble() * (b.y - o.y) - (a.y - o.y).toDouble() * (b.x - o.x)
        val lower = ArrayList<Offset>()
        for (pt in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0) lower.removeAt(lower.size - 1)
            lower += pt
        }
        val upper = ArrayList<Offset>()
        for (pt in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0) upper.removeAt(upper.size - 1)
            upper += pt
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Zarfı, her adımda en az alan kaybettiren köşeyi atarak dört köşeye indirger. */
    private fun reduceToFour(hull: List<Offset>): List<Offset> {
        val pts = hull.toMutableList()
        while (pts.size > 4) {
            var bestI = 0; var bestLoss = Double.MAX_VALUE
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
                val loss = abs((b.x - a.x).toDouble() * (c.y - a.y) - (b.y - a.y).toDouble() * (c.x - a.x)) / 2
                if (loss < bestLoss) { bestLoss = loss; bestI = i }
            }
            pts.removeAt(bestI)
        }
        return pts
    }

    private fun polygonArea(p: List<Offset>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2
    }

    /** Köşeleri sol-üst, sağ-üst, sağ-alt, sol-alt sırasına dizer (y aşağı). */
    private fun orderCorners(p: List<Offset>): List<Offset> {
        val cx = p.sumOf { it.x.toDouble() } / p.size; val cy = p.sumOf { it.y.toDouble() } / p.size
        val sorted = p.sortedBy { atan2(it.y - cy, it.x - cx) }
        val startIdx = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(startIdx + it) % 4] }
    }
}
