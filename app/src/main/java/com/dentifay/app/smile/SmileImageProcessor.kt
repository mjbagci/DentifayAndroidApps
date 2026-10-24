package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fotoğraftaki ağız bölgesi. Tüm koordinatlar TAM fotoğrafın piksel
 * koordinatlarındadır (sol-üst orijin).
 */
data class MouthRegion(
    val imageSize: Size,
    /** Yapay zekaya gönderilecek TEK bölge (3:2). Gözleri içermez. */
    val cropRect: Rect,
    /** Dış dudak konturu (bağlam/yedek maske). */
    val lipPolygon: List<Offset>,
    /**
     * İç dudak konturu (diş açıklığı) — birleştirme maskesi bundan üretilir;
     * böylece dudak, ruj ve sakal orijinal fotoğraftan kalır.
     */
    val innerLipPolygon: List<Offset>,
    val mouthBounds: Rect,
    /**
     * Baş dönüklüğü: burnun göz ortasından yatay sapması / göz arası mesafe
     * (işaretli). Rehberli çekimdeki "önden" kontrolüyle aynı ölçü.
     */
    val yaw: Float,
) {
    /** Karşıdan çekilmiş mi? Yandan çekimde model dişleri yamuk çizebiliyor. */
    val isFrontal: Boolean get() = abs(yaw) <= SmileImageProcessor.maxFrontalYaw

    /** Kırpımın tamsayı piksel boyutu. */
    internal val cropW: Int get() = cropRect.width.roundToInt()
    internal val cropH: Int get() = cropRect.height.roundToInt()
}

/**
 * Analiz edilmiş fotoğraf. Baş eğikse (ağız hattı > 2°) `working`, ağız
 * merkezi etrafında düzleştirilmiş kopyadır; kırpım, maske ve birleştirme
 * onun üzerinde yapılır, `finalize` sonucu orijinale geri döndürür.
 */
class SmilePhoto(
    val original: Bitmap,
    val working: Bitmap,
    /** `working` koordinatlarında ağız bölgesi. */
    val region: MouthRegion,
    /** Radyan. `working` = orijinalin ağız merkezi etrafında bu açı kadar düzleştirilmişi. */
    val angle: Float,
    /** Sol-üst orijinli döndürme merkezi. */
    val center: Offset,
) {
    val isDeskewed: Boolean get() = angle != 0f
}

/** Birleştirme sonucu + yerleşim kalitesi (dudak halkası korelasyonu). */
class SmileComposite(val image: Bitmap, val placementScore: Double) {
    val isWellPlaced: Boolean get() = placementScore >= SmileImageProcessor.minPlacementScore
}

/**
 * Maskenin kapsadığı çene: hekim yalnızca üst veya alt dişleri değiştirmek
 * isteyebilir (ör. yalnızca üst çeneye lamina).
 */
enum class MaskScope {
    all, upper, lower;

    val rawValue: String get() = name
    val id: String get() = rawValue
    val title: String
        get() = when (this) {
            all -> "Tümü"
            upper -> "Üst çene"
            lower -> "Alt çene"
        }

    companion object {
        val allCases: List<MaskScope> get() = entries.toList()
    }
}

/** Üst/alt çeneyi ayıran eğrinin üç noktası (kırpım koordinatları, sol-üst). */
data class ArchCurve(val left: Offset, val mid: Offset, val right: Offset)

/**
 * Gülüş simülasyonunun cihaz üzerindeki görüntü işleme çekirdeği (KVKK tasarımı):
 *
 * 1. `detectMouth`   — ML Kit yüz konturları ile ağzı bulur, gözleri dışarıda
 *                      bırakan 3:2 bir kırpım hesaplar.
 * 2. `uploadJPEG`    — SADECE bu kırpımı, metadata'sız (EXIF/GPS yok) JPEG yapar.
 *                      Yüzün geri kalanı cihazdan hiç çıkmaz.
 * 3. `composite`     — Modelden dönen kırpımı orijinale hizalar (model çıktıyı
 *                      ~%10 küçültüp kaydırıyor), yalnızca dudak içini yumuşak
 *                      maskeyle orijinal fotoğrafa geri yerleştirir.
 *
 * Tüm fonksiyonlar bloklayıcıdır — `Dispatchers.Default` üzerinde çağırın.
 */
object SmileImageProcessor {

    /** kie.ai `image_size: "3:2"` ile eşleşir; model 1536×1024 döndürür. */
    const val aspect: Float = 3f / 2f
    const val modelImageSize = "3:2"
    val uploadSize = Size(1536f, 1024f)
    /** Diş açıklığı maskesinin dudak içine doğru genişlemesi (ağız genişliğine oran). */
    const val defaultMaskExpansion: Float = 0.06f
    /**
     * Dudak halkası korelasyonu bunun altındaysa model ağzı/pozu yeniden
     * çizmiş demektir → maskeli modelle yeniden denenir (testlerle belirlendi).
     */
    const val minPlacementScore: Double = 0.2
    /** Bu açıdan eğik ağız hattı gönderilmeden önce düzleştirilir. */
    const val deskewThreshold: Float = (2 * PI / 180).toFloat()
    /**
     * Bunun üstündeki baş dönüklüğünde (yaw) fotoğraf "yandan çekilmiş" sayılır.
     * Canlı rehberdeki sınır 0,10; kayıtlı fotoğrafta landmark gürültüsü için biraz pay.
     */
    const val maxFrontalYaw: Float = 0.12f

    sealed class ProcessingError(message: String) : Exception(message) {
        object analysisFailed : ProcessingError("Fotoğraf analiz edilemedi. Farklı bir fotoğrafla tekrar deneyin.")
        object noFace : ProcessingError("Fotoğrafta ağız bölgesi algılanamadı. Yüzün tamamının göründüğü, önden ve iyi ışıkta çekilmiş bir fotoğraf kullanın.")
        object mouthTooSmall : ProcessingError("Ağız bölgesi çok küçük. Hastaya daha yakından (yüz kadrajı dolduracak şekilde) çekin.")
        object imageEncodingFailed : ProcessingError("Görsel hazırlanamadı.")
        object compositingFailed : ProcessingError("Sonuç fotoğrafla birleştirilemedi.")

        val errorDescription: String get() = message ?: ""
    }

    /** Kayıtlı fotoğraf için doğruluk öncelikli yüz algılayıcı (konturlar + işaretler). */
    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build(),
        )
    }

    /** Swift `.rounded()` (pozitif değerler için yarım yukarı). */
    private fun roundHalfUp(v: Float): Float = floor(v + 0.5f)

    // MARK: - 1. Ağız algılama + güvenli kırpım

    fun detectMouth(image: Bitmap): MouthRegion {
        val faces: List<Face> = try {
            Tasks.await(detector.process(InputImage.fromBitmap(image.softwareCopy(), 0)))
        } catch (e: Exception) {
            throw ProcessingError.analysisFailed
        }

        val W = image.width.toFloat()
        val H = image.height.toFloat()
        val size = Size(W, H)
        // Birden fazla yüz varsa en büyüğü (hasta).
        val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() } ?: throw ProcessingError.noFace
        val parts = SmileFaceParts(face)
        val lipPoints = parts.outerLips
        if (lipPoints.size < 6) throw ProcessingError.noFace
        val innerPoints = parts.innerLips

        // Dönüklük (yaw): burun merkezinin göz ortasından yatay sapması / göz arası.
        var yaw = 0f
        val l = parts.leftPupil
        val r = parts.rightPupil
        val n = parts.nose
        if (l != null && r != null && n != null) {
            yaw = (n.x - (l.x + r.x) / 2) / max(hypot2(r.x - l.x, r.y - l.y), 1f)
        }
        val mouth = boundsOf(lipPoints)
        if (mouth.width < 110) throw ProcessingError.mouthTooSmall   // model tuvali 1536 px; altı fazla büyütme

        // Bağlam: ağız genişliğinin ~1.7 katı (model dudak/cilt geçişini görsün).
        var cw = max(mouth.width * 1.7f, mouth.height * 1.9f * aspect)
        var ch = cw / aspect

        // KVKK koruması: kırpımın üst kenarı gözlerin altında kalmalı.
        val eyePoints = parts.leftEyeContour + parts.rightEyeContour
        eyePoints.maxOfOrNull { it.y }?.let { eyeBottom ->
            val maxHalf = mouth.center.y - (eyeBottom + mouth.height * 0.25f)
            if (ch / 2 > maxHalf) {
                ch = max(maxHalf * 2, mouth.height * 1.3f)
                cw = ch * aspect
            }
        }

        // Fotoğrafa sığdır (oran korunur).
        if (cw > W) { cw = W; ch = cw / aspect }
        if (ch > H) { ch = H; cw = ch * aspect }
        cw = floor(cw)
        ch = floor(cw / aspect)
        val x = roundHalfUp(min(max(mouth.center.x - cw / 2, 0f), W - cw))
        val y = roundHalfUp(min(max(mouth.center.y - ch / 2, 0f), H - ch))

        return MouthRegion(
            imageSize = size,
            cropRect = Rect(x, y, x + cw, y + ch),
            lipPolygon = lipPoints,
            innerLipPolygon = innerPoints,
            mouthBounds = mouth,
            yaw = yaw,
        )
    }

    // MARK: - 1b. Eğiklik düzeltme

    /**
     * Ağzı algılar; ağız hattı eğikse fotoğrafı ağız merkezi etrafında
     * düzleştirip yeniden algılar. Modeller eğik ağızları "düzeltip" yeniden
     * çizdiği için bu, yerleşimi belirgin şekilde iyileştirir.
     */
    fun prepare(image: Bitmap): SmilePhoto {
        val r0 = detectMouth(image)
        val l = r0.lipPolygon.minByOrNull { it.x }
        val r = r0.lipPolygon.maxByOrNull { it.x }
        if (l == null || r == null) return SmilePhoto(image, image, r0, 0f, Offset.Zero)
        val angle = atan2(r.y - l.y, r.x - l.x)                   // sol-üst: + = sağ köşe aşağıda
        if (!(abs(angle) > deskewThreshold && abs(angle) < PI.toFloat() / 4)) {
            return SmilePhoto(image, image, r0, 0f, Offset.Zero)
        }
        val center = r0.mouthBounds.center
        val working = rotate(image, angle, center) ?: return SmilePhoto(image, image, r0, 0f, Offset.Zero)
        val r1: MouthRegion = (try { detectMouth(working) } catch (e: ProcessingError) { null })
            ?: return SmilePhoto(image, image, r0, 0f, Offset.Zero)
        return SmilePhoto(image, working, r1, angle, center)
    }

    private fun degrees(rad: Float): Float = (rad * 180.0 / PI).toFloat()

    /**
     * Görüntüyü sol-üst orijinli `center` etrafında, sol-üst koordinatlarda
     * `angle` eğimini sıfırlayacak yönde döndürür (tuval boyutu aynı, kenarlar uzatılır).
     */
    fun rotate(image: Bitmap, angle: Float, center: Offset): Bitmap? = runCatching {
        val src = image.softwareCopy()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // Android'de pozitif derece saat yönü (y aşağı) → eğimi sıfırlamak için −açı.
        shader.setLocalMatrix(Matrix().apply { setRotate(-degrees(angle), center.x, center.y) })
        Canvas(out).drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        out
    }.getOrNull()

    /** `working` → orijinal koordinatları (düzleştirmenin tersi). */
    private fun backTransform(photo: SmilePhoto): Matrix =
        Matrix().apply { setRotate(degrees(photo.angle), photo.center.x, photo.center.y) }

    /**
     * `working` üzerinde yapılmış birleştirmeyi orijinale taşır: düzleştirme
     * geri alınır ve yalnızca ağız bölgesi (yumuşak kenarlı) orijinale yerleşir;
     * fotoğrafın geri kalanı hiç yeniden örneklenmez.
     */
    fun finalize(photo: SmilePhoto, workingComposite: Bitmap, extraMask: MaskImage? = null): Bitmap {
        if (!photo.isDeskewed) return workingComposite
        return runCatching {
            val original = photo.original.softwareCopy()
            val W = original.width; val H = original.height
            val r = photo.region.cropRect
            var local = mouthMask(photo.region, expansion = 0.2f)
            if (extraMask != null) {   // elle genişletilmiş maske 0,2'yi aşabilir → birleşim
                local = local.maximum(extraMask)
            }
            val back = backTransform(photo)
            // Yalnız ağız kutusu işlenir (bellek): kırpımın geri döndürülmüş sınırları
            val pts = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
            back.mapPoints(pts)
            val bx = max(0, floor(minOf(pts[0], pts[2], pts[4], pts[6])).toInt() - 2)
            val by = max(0, floor(minOf(pts[1], pts[3], pts[5], pts[7])).toInt() - 2)
            val bx1 = min(W, kotlin.math.ceil(maxOf(pts[0], pts[2], pts[4], pts[6])).toInt() + 2)
            val by1 = min(H, kotlin.math.ceil(maxOf(pts[1], pts[3], pts[5], pts[7])).toInt() + 2)
            val bw = bx1 - bx; val bh = by1 - by
            if (bw <= 0 || bh <= 0) return workingComposite

            val patch = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(patch).apply {
                translate(-bx.toFloat(), -by.toFloat())
                val shader = BitmapShader(workingComposite.softwareCopy(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setLocalMatrix(back)
                drawRect(bx.toFloat(), by.toFloat(), bx1.toFloat(), by1.toFloat(),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            }
            val maskBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(maskBmp).apply {
                drawColor(Color.BLACK)
                translate(-bx.toFloat(), -by.toFloat())
                concat(back)
                drawBitmap(local.toGrayBitmap(), r.left, r.top, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val o = IntArray(bw * bh); original.getPixels(o, 0, bw, bx, by, bw, bh)
            val p = IntArray(bw * bh); patch.getPixels(p, 0, bw, 0, 0, bw, bh)
            val m = IntArray(bw * bh); maskBmp.getPixels(m, 0, bw, 0, 0, bw, bh)
            for (i in o.indices) o[i] = mix(p[i], o[i], Color.red(m[i]) / 255f)
            val out = original.copy(Bitmap.Config.ARGB_8888, true)
            out.setPixels(o, 0, bw, bx, by, bw, bh)
            patch.recycle(); maskBmp.recycle()
            out
        }.getOrElse { workingComposite }
    }

    /** Maske önizlemesi: orijinal fotoğraf koordinatlarında, beyaz = maske (tam boyut, gri). */
    fun maskPreview(photo: SmilePhoto, expansion: Float): Bitmap? =
        maskPreview(photo, mask = mouthMask(photo.region, expansion))

    /** Kırpım koordinatlı herhangi bir maskeyi orijinal fotoğraf koordinatlarına taşır. */
    fun maskPreview(photo: SmilePhoto, mask: MaskImage): Bitmap? = runCatching {
        val W = photo.original.width; val H = photo.original.height
        val r = photo.region.cropRect
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            if (photo.isDeskewed) concat(backTransform(photo))
            drawBitmap(mask.fitted(photo.region.cropW, photo.region.cropH).toGrayBitmap(), r.left, r.top, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        out
    }.getOrNull()

    // MARK: - 2. Gönderilecek kırpım

    fun crop(image: Bitmap, region: MouthRegion): Bitmap? = runCatching {
        val r = region.cropRect
        val x = r.left.roundToInt().coerceIn(0, image.width - 1)
        val y = r.top.roundToInt().coerceIn(0, image.height - 1)
        val w = min(region.cropW, image.width - x)
        val h = min(region.cropH, image.height - y)
        if (w <= 0 || h <= 0) null else Bitmap.createBitmap(image.softwareCopy(), x, y, w, h)
    }.getOrNull()

    /**
     * KVKK: yalnızca maskeli bölge. Maske dışındaki her piksel siyah olur;
     * yapay zekaya dudakların iç kenarı ve dişlerden başka hiçbir şey gitmez.
     */
    fun maskedOnly(crop: Bitmap, mask: MaskImage, region: MouthRegion): Bitmap {
        val w = crop.width; val h = crop.height
        val px = crop.pixels()
        val m = mask.fitted(w, h).data
        for (i in px.indices) px[i] = mix(px[i], Color.BLACK, m[i])
        return bitmapFromPixels(px, w, h)
    }

    /**
     * Kırpımı modelin çıktı çözünürlüğüne (1536×1024) ölçekleyip metadata'sız
     * JPEG olarak kodlar. Girdi = çıktı boyutu, modelin yeniden ölçekleme
     * kaymasını azaltır ve küçük kırpımlarda detay kaybını önler.
     */
    fun uploadJPEG(crop: Bitmap, quality: Float = 0.92f): ByteArray {
        val resized = resize(crop, uploadSize) ?: throw ProcessingError.imageEncodingFailed
        // Bitmap.compress hiçbir metadata (EXIF/GPS/TIFF) yazmaz.
        val out = ByteArrayOutputStream()
        if (!resized.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), out)) throw ProcessingError.imageEncodingFailed
        return out.toByteArray()
    }

    /**
     * Maskeli (inpainting) modeller için diş açıklığı maskesi: gönderilen
     * görüntüyle aynı boyutta (1536×1024) gri PNG. Varsayılan beyaz = düzenle.
     */
    fun maskPNG(region: MouthRegion, expansion: Float = defaultMaskExpansion + 0.03f, invert: Boolean = false): ByteArray =
        maskPNG(mask = mouthMask(region, expansion), region = region, invert = invert)

    /** Kırpım koordinatlı maskeyi (ör. elle düzenlenmiş) model boyutunda PNG yapar. */
    fun maskPNG(mask: MaskImage, region: MouthRegion, invert: Boolean = false): ByteArray {
        val w = uploadSize.width.toInt(); val h = uploadSize.height.toInt()
        val scaled = mask.fitted(region.cropW, region.cropH).resized(w, h)
        val bmp = (if (invert) scaled.inverted() else scaled).toGrayBitmap()
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw ProcessingError.imageEncodingFailed
        return out.toByteArray()
    }

    // MARK: - 3. Hizala + maskele + birleştir

    fun composite(original: Bitmap, region: MouthRegion, aiCrop: Bitmap, expansion: Float = defaultMaskExpansion): Bitmap =
        compositeScored(original, region, aiCrop, expansion).image

    /**
     * Fotoğraf seviyesinde birleştirme: eğiklik düzeltmesini de geri alır.
     * `maskOverride`: hekimin seçtiği maske (elle düzenleme ve/veya çene seçimi).
     * Verilirse otomatik açıklık yerine bu kullanılır; dudak koruması yine uygulanır.
     * `sentMask`: yalnız-maske gönderimde modele giden maske. Verilirse çıktı
     * o maskeye göre (siyah çerçeveyle) hizalanır ve yalnızca maske içi yerleştirilir.
     */
    fun composite(
        photo: SmilePhoto,
        aiCrop: Bitmap,
        expansion: Float = defaultMaskExpansion,
        maskOverride: MaskImage? = null,
        sentMask: MaskImage? = null,
    ): SmileComposite {
        val c = if (sentMask != null) {
            compositeMaskedOnly(photo.working, photo.region, aiCrop, sentMask)
        } else {
            compositeScored(photo.working, photo.region, aiCrop, expansion, maskOverride)
        }
        return SmileComposite(finalize(photo, c.image, extraMask = sentMask ?: maskOverride), c.placementScore)
    }

    /**
     * Yalnız-maske gönderimin birleştirmesi. Model maske dışını görmedi (siyah):
     * hizalama siyah çerçeveye göre, renk eşleme maskenin dudak kenarı bandına
     * göre yapılır; yerleştirilen alan gönderilen maskeden biraz içeride kalır.
     */
    fun compositeMaskedOnly(original: Bitmap, region: MouthRegion, aiCrop: Bitmap, sentMask: MaskImage): SmileComposite {
        val size = Size(region.cropW.toFloat(), region.cropH.toFloat())
        val mw = region.mouthBounds.width
        val originalCrop = crop(original, region) ?: throw ProcessingError.compositingFailed
        val aiResized = resize(aiCrop, size) ?: throw ProcessingError.compositingFailed
        val base = originalCrop
        val sm = sentMask.fitted(region.cropW, region.cropH)
        val sent = maskedOnly(originalCrop, sm, region)

        // 1) Hizalama: modelin gördüğü siyah çerçeve ile çıktınınki örtüşmeli
        val (alignedRaw, agreement) = alignToFrame(aiResized, aiResized, sent, size)
        // 2) Renk eşleme: maskenin opak kenar bandı (dudak iç kenarı) — değişmemesi gereken yer.
        //    Yumuşak (siyaha geçen) kenar dışarıda kalsın diye iki aşınma arasındaki bant.
        val e1 = sm.eroded(max(1.5f, mw * 0.022f))
        val e2 = sm.eroded(max(1.5f, mw * 0.055f))
        val rim = e1.subtracting(e2)                                  // e1 − e2: kenar bandı
        var aligned = harmonize(alignedRaw, base, rim.inverted(), size)
        if (isGrayscale(originalCrop)) aligned = aligned.desaturated()
        aligned = matchGrain(aligned, base, sm, size)

        // 3) Yerleşecek alan: gönderilen maskenin biraz içi (siyah kenar asla görünmesin)
        //    + dudak/cilt koruması (renk modelleri orijinalden)
        val inner = sm.eroded(max(1.5f, mw * 0.012f)).blurred(max(1.5f, mw * 0.008f))
        val mask = refinedMask(region, base, aligned, coarseOverride = inner, sharedStats = true)

        val patch = blendWithMask(aligned, base, mask)
        val out = placePatch(original, patch, region)
        // Ağız örtüşmesi (IoU) 0,6 → skor 0,2 (eşik); 1 → 1
        return SmileComposite(out, 2 * agreement - 1)
    }

    /**
     * Siyah çerçeve uyumuna göre hizalama: adaylar (kimlik, T, T⁻¹) içinden
     * "siyah / siyah değil" haritası gönderilen görüntüyle en çok örtüşen seçilir.
     * (Vision homografik kaydı yerine benzerlik dönüşümü araması.)
     */
    fun alignToFrame(ai: Bitmap, aiCG: Bitmap, reference: Bitmap, size: Size): Pair<Bitmap, Double> {
        val W = 200
        val H = max(8, (W * size.height / size.width).roundToInt())
        fun blackMap(px: IntArray): BooleanArray = BooleanArray(px.size) { luma(px[it]) < 30 }
        val rm = blackMap(reference.scaledPixels(W, H))
        fun iou(cm: BooleanArray): Double {
            var inter = 0; var union = 0
            for (k in rm.indices) {
                val a = !rm[k]; val b = !cm[k]
                if (a && b) inter++
                if (a || b) union++
            }
            return if (union > 0) inter.toDouble() / union else 0.0
        }
        val candidates = mutableListOf(ai)
        val sim = searchSimilarity(aiCG, size) { w, h ->
            val ref = reference.scaledPixels(w, h).let { px -> BooleanArray(px.size) { luma(px[it]) >= 30 } }
            val cost: (FloatArray) -> Double = { warped ->
                var inter = 0; var union = 0
                for (k in warped.indices) {
                    val a = ref[k]; val b = warped[k] * 255f >= 30f
                    if (a && b) inter++
                    if (a || b) union++
                }
                1.0 - (if (union > 0) inter.toDouble() / union else 0.0)
            }
            cost
        }
        if (sim != null) {
            candidates.add(warp(ai, by = sim.matrix(), size = size))
            candidates.add(warp(ai, by = sim.inverse().matrix(), size = size))
        }
        // Siyah olmayan (ağız) bölgelerinin örtüşme oranı (IoU). Siyah alan çok
        // büyük olduğu için basit piksel uyumu yanıltıcı olur; IoU yalnızca ağza bakar.
        var best: Pair<Bitmap, Double> = candidates[0] to -1.0
        for (c in candidates) {
            val v = iou(blackMap(c.scaledPixels(W, H)))
            if (v > best.second) best = c to v
        }
        return best
    }

    fun compositeScored(
        original: Bitmap,
        region: MouthRegion,
        aiCrop: Bitmap,
        expansion: Float = defaultMaskExpansion,
        maskOverride: MaskImage? = null,
    ): SmileComposite {
        val cropSize = Size(region.cropW.toFloat(), region.cropH.toFloat())
        val originalCrop = crop(original, region) ?: throw ProcessingError.compositingFailed
        val aiResized = resize(aiCrop, cropSize) ?: throw ProcessingError.compositingFailed

        val base = originalCrop
        val coarse = maskOverride?.fitted(region.cropW, region.cropH) ?: mouthMask(region, expansion)

        // Model çıktısı piksel-hizalı değil → benzerlik dönüşümü ile orijinale oturt.
        var aligned = align(aiResized, originalCrop, aiResized, coarse, cropSize)
        // Model pozlamayı/beyaz dengesini kaydırabiliyor → ağız dışındaki
        // bölgeye göre renk eşle (dişlerin görece beyazlığı korunur).
        aligned = harmonize(aligned, base, coarse, cropSize)
        if (isGrayscale(originalCrop)) aligned = aligned.desaturated()
        // Yerleşim kalitesi (renk/gren eşlemeden önce, sadece geometri)
        val score = placementScore(aligned, base, region)
        // Model çıktısı fotoğraftan pürüzsüz → aynı greni ekle.
        aligned = matchGrain(aligned, base, coarse, cropSize)

        // Dudak piksellerini orijinalden koruyan, dişleri köşeye kadar alan maske.
        val mask = refinedMask(region, base, aligned, expansion, coarseOverride = maskOverride?.fitted(region.cropW, region.cropH))

        val patch = blendWithMask(aligned, base, mask)
        // Orijinal fotoğraf + yalnızca ağız yaması. Maske kırpım kenarında 0
        // olduğundan yama sınırı görünmez.
        return SmileComposite(placePatch(original, patch, region), score)
    }

    /** `fg·m + bg·(1−m)` (CIBlendWithMask). */
    private fun blendWithMask(fg: Bitmap, bg: Bitmap, mask: MaskImage): Bitmap {
        val w = bg.width; val h = bg.height
        val f = if (fg.width == w && fg.height == h) fg.pixels() else fg.scaledPixels(w, h)
        val b = bg.pixels()
        val m = mask.fitted(w, h).data
        for (i in b.indices) b[i] = mix(f[i], b[i], m[i])
        return bitmapFromPixels(b, w, h)
    }

    private fun placePatch(original: Bitmap, patch: Bitmap, region: MouthRegion): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true) ?: throw ProcessingError.compositingFailed
        Canvas(out).drawBitmap(patch, region.cropRect.left, region.cropRect.top, null)
        return out
    }

    /**
     * Diş açıklığı (kaba): iç dudak + ağız köşeleri, dudak içine doğru
     * `expansion` kadar genişletilmiş, yumuşak kenarlı. Kırpım koordinatları.
     * Beyaz = AI, siyah = orijinal.
     */
    fun mouthMask(region: MouthRegion, expansion: Float = defaultMaskExpansion): MaskImage {
        val mw = region.mouthBounds.width
        if (region.innerLipPolygon.size < 6) {
            return polygonMask(region.lipPolygon, region, scale = 1.12f, dilate = 0f, feather = mw * 0.03f)
        }
        return polygonMask(openingPolygon(region), region, scale = 1f, dilate = mw * expansion, feather = mw * 0.018f)
    }

    /**
     * İç dudak konturu ağız köşelerine uzanmıyor → yan dişler dışarıda kalıyordu.
     * Dış dudağın köşe noktalarını (biraz içeri çekerek) ekleyip dışbükey zarfı
     * alıyoruz: dikeyde iç dudak, yatayda köşeden köşeye. Tam fotoğraf koordinatları.
     */
    fun openingPolygon(region: MouthRegion): List<Offset> {
        val outer = region.lipPolygon
        val cx = region.mouthBounds.center.x
        val opening = region.innerLipPolygon.toMutableList()
        val l = outer.minByOrNull { it.x }
        val r = outer.maxByOrNull { it.x }
        if (l != null && r != null) {
            opening.add(Offset(l.x + (cx - l.x) * 0.06f, l.y))
            opening.add(Offset(r.x - (r.x - cx) * 0.06f, r.y))
        }
        return convexHull(opening)
    }

    /**
     * Tam-fotoğraf koordinatlı bir çokgeni kırpım boyutunda gri maskeye çizer.
     * `scale`: ağız merkezine göre büyütme, `dilate`: kenar kalınlığı kadar
     * genişletme (px), `feather`: Gauss yumuşatma (px).
     */
    fun polygonMask(
        polygon: List<Offset>,
        region: MouthRegion,
        scale: Float,
        dilate: Float,
        feather: Float,
        erode: Boolean = false,
    ): MaskImage {
        val w = region.cropW; val h = region.cropH
        val origin = region.cropRect.topLeft
        val c = region.mouthBounds.center
        val pts = polygon.map { p ->
            val g = Offset(c.x + (p.x - c.x) * scale, c.y + (p.y - c.y) * scale)
            Offset(g.x - origin.x, g.y - origin.y)
        }
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        if (pts.isNotEmpty()) {
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
            if (dilate > 0) {
                // Kenara `dilate` kalınlığında çizgi: içeri (erode) ya da dışarı (dilate).
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (erode) Color.BLACK else Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = dilate * 2
                    strokeJoin = Paint.Join.ROUND
                })
            }
        }
        var img = MaskImage.fromBitmap(bmp)
        bmp.recycle()
        if (feather > 0.5f) img = img.blurred(feather)
        return img
    }

    // MARK: - Çene seçimi ve etkin maske

    /**
     * Üst/alt çeneyi ayıran eğri: iki ağız köşesinden ve açıklığın orta
     * yüksekliğinden geçen parabol (gülüş eğrisini izler). Kırpım koordinatlarında
     * sol-üst orijinli üç nokta döner.
     */
    fun archCurve(region: MouthRegion): ArchCurve? {
        val o = region.cropRect.topLeft
        val l = region.lipPolygon.minByOrNull { it.x } ?: return null
        val r = region.lipPolygon.maxByOrNull { it.x } ?: return null
        val top = region.innerLipPolygon.minOfOrNull { it.y } ?: return null
        val bottom = region.innerLipPolygon.maxOfOrNull { it.y } ?: return null
        val midY = (top + bottom) / 2
        return ArchCurve(
            Offset(l.x - o.x, l.y - o.y),
            Offset(region.mouthBounds.center.x - o.x, midY - o.y),
            Offset(r.x - o.x, r.y - o.y),
        )
    }

    /** Seçilen çene yarısı = 1, diğeri = 0 (yumuşak geçişli). `.all` için null. */
    fun scopeMask(region: MouthRegion, scope: MaskScope): MaskImage? {
        if (scope == MaskScope.all) return null
        val c = archCurve(region) ?: return null
        val w = region.cropW; val h = region.cropH
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        // Parabol: sol köşe → orta → sağ köşe (ikinci derece Bezier kontrol noktası)
        val ctrl = Offset(c.mid.x, 2 * c.mid.y - (c.left.y + c.right.y) / 2)
        val edgeY = if (scope == MaskScope.upper) 0f else h.toFloat()          // üst için tavan, alt için taban
        val path = Path().apply {
            moveTo(0f, c.left.y)
            lineTo(c.left.x, c.left.y)
            quadTo(ctrl.x, ctrl.y, c.right.x, c.right.y)
            lineTo(w.toFloat(), c.right.y)
            lineTo(w.toFloat(), edgeY)
            lineTo(0f, edgeY)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
        val img = MaskImage.fromBitmap(bmp)
        bmp.recycle()
        return img.blurred(max(2f, region.mouthBounds.width * 0.012f))
    }

    /**
     * Hekimin seçtiği etkin maske (kırpım koordinatları): elle düzenlenmiş maske
     * varsa o (yumuşatılmış), yoksa otomatik açıklık; ardından çene kapsamı.
     */
    fun effectiveMask(region: MouthRegion, expansion: Float, scope: MaskScope, custom: Bitmap?): MaskImage {
        var mask = if (custom != null) {
            MaskImage.fromBitmap(custom).fitted(region.cropW, region.cropH)
                .blurred(max(1.5f, region.mouthBounds.width * 0.008f))
        } else {
            mouthMask(region, expansion)
        }
        scopeMask(region, scope)?.let { mask = mask.multiplied(it) }
        return mask
    }

    /** Maskeyi elle düzenleme için gri bitmap'e çevirir (kırpım boyutu, beyaz = maske). */
    fun maskBitmap(mask: MaskImage, region: MouthRegion): Bitmap? =
        runCatching { mask.fitted(region.cropW, region.cropH).toGrayBitmap() }.getOrNull()

    // MARK: - Hassas maske (dudak koruma)

    private class Stats {
        var ma = 0.0; var mb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var n = 0.0
    }

    /**
     * Kaba açıklığı piksel düzeyinde inceltir: hem orijinalde hem AI'da
     * "dudak rengi" olan pikseller orijinalden kalır (ruj, dudak dokusu), geri
     * kalan her şey (dişler, diş eti, boşluklar, köşe gölgeleri) AI'dan alınır.
     * Dudak renk modeli her iki görüntünün dudak bandından ayrı ayrı öğrenilir.
     */
    fun refinedMask(
        region: MouthRegion,
        original: Bitmap,
        ai: Bitmap,
        expansion: Float = defaultMaskExpansion,
        coarseOverride: MaskImage? = null,
        sharedStats: Boolean = false,
    ): MaskImage {
        val size = Size(region.cropW.toFloat(), region.cropH.toFloat())
        val mw = region.mouthBounds.width
        if (region.innerLipPolygon.size < 6) return mouthMask(region, expansion)

        // Çalışma çözünürlüğü: ağız ~300 px genişlikte.
        val s = min(1f, 300f / max(mw, 1f))
        val W = max(8, (size.width * s).toInt())
        val H = max(8, (size.height * s).toInt())
        val po = original.scaledPixels(W, H)
        val pa = ai.scaledPixels(W, H)
        val manual = coarseOverride != null
        val mCoarse = (coarseOverride ?: mouthMask(region, expansion + 0.03f)).bytes(W, H)   // cömert bölge
        // Kesin diş alanı: otomatikte açıklığın içi, elle/yalnız-maskede verilen
        // maskenin derin içi (kenardan ~%5 ağız genişliği) — burası hep AI'dan gelir.
        val mCore = if (coarseOverride != null)
            coarseOverride.fitted(region.cropW, region.cropH).eroded(max(2f, mw * 0.05f)).bytes(W, H)
        else
            polygonMask(openingPolygon(region), region, scale = 1f, dilate = mw * 0.025f, feather = 0f, erode = true).bytes(W, H)
        // Otomatik maske dış dudağın biraz dışına taşmasın (cilt/sakal noktaları)
        val mLimit = if (manual) null
        else polygonMask(region.lipPolygon, region, scale = 1f, dilate = mw * 0.03f, feather = 0f).bytes(W, H)
        val mOuter = polygonMask(region.lipPolygon, region, scale = 1f, dilate = 0f, feather = 0f).bytes(W, H)
        // Cilt bandı: dış dudağın hemen dışı (perioral cilt)
        val mSkinOuter = polygonMask(region.lipPolygon, region, scale = 1f, dilate = mw * 0.09f, feather = 0f).bytes(W, H)
        val mOpen = polygonMask(openingPolygon(region), region, scale = 1f, dilate = mw * 0.02f, feather = 0f).bytes(W, H)

        // Dudak bandı: dış dudak içinde, açıklığın dışında, ağzın orta %70'i.
        val x0 = (W * ((region.mouthBounds.left + mw * 0.15f - region.cropRect.left) / size.width)).toInt()
        val x1 = (W * ((region.mouthBounds.right - mw * 0.15f - region.cropRect.left) / size.width)).toInt()

        fun chroma(p: IntArray, i: Int): Triple<Double, Double, Double> {
            val c = p[i]
            val r = Color.red(c).toDouble(); val g = Color.green(c).toDouble(); val b = Color.blue(c).toDouble()
            val sum = r + g + b + 1
            return Triple((r - g) / sum, (r + g - 2 * b) / sum, (0.299 * r + 0.587 * g + 0.114 * b) / 255)
        }

        fun bandStats(p: IntArray, inBand: (Int) -> Boolean): Stats {
            val st = Stats()
            val samples = ArrayList<DoubleArray>()
            for (y in 0 until H) for (x in max(0, x0) until min(W, x1)) {
                val i = y * W + x
                if (!inBand(i)) continue
                val (a, b, l) = chroma(p, i)
                if (l <= 0.18) continue          // koyu pikselde renk oranları gürültülü
                samples.add(doubleArrayOf(a, b))
            }
            if (samples.size <= 20) return st
            // Bant dişlerle karışabilir (ör. kaba açıklık dişleri tam kapsamıyorsa):
            // dişler en az kırmızı pikseller → en kırmızı %55'i dudak/cilt modeli olur.
            samples.sortByDescending { it[0] }
            val kept = samples.subList(0, min(samples.size, max(20, samples.size * 55 / 100)))
            st.n = kept.size.toDouble()
            st.ma = kept.sumOf { it[0] } / st.n
            st.mb = kept.sumOf { it[1] } / st.n
            for (v in kept) {
                val a = v[0]; val b = v[1]
                st.saa += (a - st.ma) * (a - st.ma); st.sbb += (b - st.mb) * (b - st.mb); st.sab += (a - st.ma) * (b - st.mb)
            }
            st.saa = st.saa / st.n + 1e-4; st.sbb = st.sbb / st.n + 1e-4; st.sab /= st.n
            // Bir kişinin dudak/cilt rengi dar bir aralıktadır; bant lekeli dişlerle
            // karışırsa model şişer ve beyaz dişleri bile "dudak" sanar → sınırla.
            val capA = 0.0036; val capB = 0.0049                  // std ≤ 0,06 / 0,07
            if (st.saa > capA || st.sbb > capB) {
                val ka = min(1.0, capA / st.saa); val kb = min(1.0, capB / st.sbb)
                st.saa *= ka; st.sbb *= kb; st.sab *= sqrt(ka * kb)
            }
            return st
        }
        fun lipStats(p: IntArray) = bandStats(p) { mOuter[it] > 127 && mOpen[it] < 128 }
        fun skinStats(p: IntArray) = bandStats(p) { mSkinOuter[it] > 127 && mOuter[it] < 128 }
        // Yalnız-maske gönderimde AI'nın dudak/cilt bandı siyah → orijinalin istatistikleri kullanılır
        val so = lipStats(po); val sa = if (sharedStats) so else lipStats(pa)
        val ko = skinStats(po); val ka = if (sharedStats) ko else skinStats(pa)
        fun lipLike(p: IntArray, i: Int, st: Stats): Boolean {
            if (st.n <= 0) return false
            val (a, b, l) = chroma(p, i)
            if (l <= 0.1) return false                 // koyu boşluk/gölge dudak değil
            val da = a - st.ma; val db = b - st.mb
            val det = st.saa * st.sbb - st.sab * st.sab
            if (det <= 0) return false
            val d2 = (st.sbb * da * da - 2 * st.sab * da * db + st.saa * db * db) / det
            return d2 < 5.0
        }

        val out = FloatArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (mCoarse[i] <= 20) continue
            if (mLimit != null && mLimit[i] < 128) continue
            if (mCore[i] > 127) { out[i] = 1f; continue }
            // Hem orijinalde hem sonuçta dudak ya da cilt olan piksel orijinalden kalır
            val keep = (lipLike(po, i, so) && lipLike(pa, i, sa)) || (lipLike(po, i, ko) && lipLike(pa, i, ka))
            out[i] = if (keep) 0f else mCoarse[i] / 255f
        }

        // Gürültüyü temizle + yumuşat, kırpım boyutuna büyüt.
        val small = MaskImage(W, H, out)
            .blurred(2.2f)                       // tek pikselleri ve pürüzü sil
            .leveled(gain = 2.2f, bias = -0.6f)  // eşik benzeri kontrast: kenarı sıkılaştır
            // Morfolojik açma (küçük beyaz adacıkları sil) + kapama (küçük delikleri doldur)
            .eroded(2f).dilated(2f).dilated(2f).eroded(2f)
        return small.resized(region.cropW, region.cropH).blurred(max(1.5f, mw * 0.008f))
    }

    // MARK: - Renk ve doku eşleme

    /**
     * Ağız dışındaki bölgede kanal başına ortalama/std eşleyen renk düzeltmesi.
     * Kazançlar sınırlı tutulur; dişlerin görece beyazlığı değişmez.
     */
    fun harmonize(ai: Bitmap, original: Bitmap, mask: MaskImage, size: Size): Bitmap {
        val so = regionStats(original, mask, size)
        val sa = regionStats(ai, mask, size)
        if (so.count <= 200 || sa.count <= 200) return ai
        val gains = FloatArray(3) { 1f }
        val bias = FloatArray(3)
        for (c in 0 until 3) {
            val g = min(1.25, max(0.8, so.std[c] / max(sa.std[c], 1e-3)))
            gains[c] = g.toFloat()
            bias[c] = (so.mean[c] - g * sa.mean[c]).toFloat()
        }
        return ai.colorMatrixed(gains, bias)
    }

    /**
     * Orijinal fotoğrafın greni (yüksek frekans gürültüsü) AI çıktısından
     * belirgin şekilde fazlaysa, farkı kadar tek renkli gren ekler (sabit tohumlu).
     */
    fun matchGrain(ai: Bitmap, original: Bitmap, mask: MaskImage, size: Size): Bitmap {
        val no = noiseLevel(original, mask, size)
        val na = noiseLevel(ai, mask, size)
        if (no <= na * 1.15) return ai
        // Cilt dokusu da yüksek frekans ölçtüğü için tahmin gerçek grenden
        // büyük çıkar → farkın %55'i, üst sınır 0.02 (dişlerde nokta nokta görünmesin).
        val sigma = min(0.02, 0.55 * sqrt(max(0.0, no * no - na * na)))
        if (sigma <= 0.003) return ai

        val w = size.width.toInt(); val h = size.height.toInt()
        val buf = if (ai.width == w && ai.height == h) ai.pixels() else ai.scaledPixels(w, h)
        var rng: ULong = 0x9E3779B97F4A7C15uL
        fun uniform(): Double {                        // SplitMix64
            rng += 0x9E3779B97F4A7C15uL
            var z = rng
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return ((z xor (z shr 31)) shr 11).toDouble() / (1uL shl 53).toDouble()
        }
        val amp = sigma * 255
        for (i in buf.indices) {
            // Irwin–Hall (3 düzgün) ≈ normal, std = 0.5
            val n = (uniform() + uniform() + uniform() - 1.5) * 2 * amp
            val c = buf[i]
            // Swift `.rounded()`: yarımlar sıfırdan uzağa
            fun ch(v: Int): Int {
                val x = v + n
                val r = if (x >= 0) floor(x + 0.5) else -floor(-x + 0.5)
                return max(0.0, min(255.0, r)).toInt()
            }
            buf[i] = Color.argb(255, ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
        }
        return bitmapFromPixels(buf, w, h)
    }

    private class ChannelStats {
        val mean = DoubleArray(3); val std = DoubleArray(3); var count = 0
    }

    /** Maske≈0 (ağız dışı) ve kenarlardan %8 içerideki piksellerin istatistiği (0–1). */
    private fun regionStats(img: Bitmap, mask: MaskImage, size: Size): ChannelStats {
        val (p, m, W, H) = renderPair(img, mask, size, 160)
        val st = ChannelStats()
        val sum = DoubleArray(3); val sq = DoubleArray(3)
        val mx = W * 8 / 100; val my = H * 8 / 100
        for (y in my until H - my) for (x in mx until W - mx) {
            val i = y * W + x
            if (m[i] >= 6) continue
            val c = p[i]
            val v = doubleArrayOf(Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0)
            for (k in 0 until 3) { sum[k] += v[k]; sq[k] += v[k] * v[k] }
            st.count++
        }
        if (st.count == 0) return st
        for (k in 0 until 3) {
            st.mean[k] = sum[k] / st.count
            st.std[k] = sqrt(max(0.0, sq[k] / st.count - st.mean[k] * st.mean[k]))
        }
        return st
    }

    /**
     * Luma'nın yüksek frekans bileşeninin (görüntü − 1.2σ bulanık) medyan mutlak
     * sapmasından gürültü std tahmini, ağız dışında. Tam çözünürlükte ölçülür.
     */
    fun noiseLevel(img: Bitmap, mask: MaskImage, size: Size): Double {
        val w = img.width; val h = img.height
        val px = img.pixels()
        val hp = FloatArray(w * h)
        val weights = floatArrayOf(0.299f, 0.587f, 0.114f)
        val plane = FloatArray(w * h)
        for (ch in 0 until 3) {
            val shift = 16 - ch * 8
            for (i in px.indices) plane[i] = ((px[i] shr shift) and 0xFF) / 255f
            val blurred = plane.copyOf()
            gaussianBlurPlane(blurred, w, h, 1.2f)
            for (i in hp.indices) hp[i] += weights[ch] * abs(plane[i] - blurred[i])
        }
        val W = max(8, min(640, size.width.toInt()))
        val H = max(8, (W * size.height / size.width).roundToInt())
        val m = mask.bytes(W, H)
        val vals = ArrayList<Double>(W * H / 2)
        val mx = W * 8 / 100; val my = H * 8 / 100
        var y = my
        while (y < H - my) {
            var x = mx
            while (x < W - mx) {
                if (m[y * W + x] < 6) {
                    val sx = ((x + 0.5f) * w / W).toInt().coerceIn(0, w - 1)
                    val sy = ((y + 0.5f) * h / H).toInt().coerceIn(0, h - 1)
                    vals.add(hp[sy * w + sx].toDouble())
                }
                x += 2
            }
            y += 2
        }
        if (vals.size <= 100) return 0.0
        vals.sort()
        return vals[vals.size / 2] * 1.4826      // |N(0,σ)| medyanı ≈ 0.6745σ
    }

    private data class Rendered(val p: IntArray, val m: IntArray, val w: Int, val h: Int)

    private fun renderPair(img: Bitmap, mask: MaskImage, size: Size, width: Int): Rendered {
        val W = max(8, width)
        val H = max(8, (W * size.height / size.width).roundToInt())
        return Rendered(img.scaledPixels(W, H), mask.bytes(W, H), W, H)
    }

    /**
     * Orijinal kırpım gri tonlu mu (siyah-beyaz fotoğraf)? Model her zaman
     * renkli üretir; öyleyse sonucu da griye çeviririz.
     */
    fun isGrayscale(image: Bitmap): Boolean {
        val sw = 64; val sh = 43
        val buf = image.scaledPixels(sw, sh)
        var chroma = 0
        for (c in buf) {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            chroma += maxOf(r, g, b) - minOf(r, g, b)
        }
        return chroma.toDouble() / (sw * sh) < 6   // 0–255 ölçeğinde ortalama renk sapması
    }

    // MARK: - Hizalama

    /** Ölçek (merkez etrafında) + öteleme (kırpım pikseli). */
    internal class Similarity(val s: Float, val tx: Float, val ty: Float, val cx: Float, val cy: Float) {
        fun matrix(): Matrix = Matrix().apply { setScale(s, s, cx, cy); postTranslate(tx, ty) }
        fun inverse(): Similarity = Similarity(1f / s, -tx / s, -ty / s, cx, cy)
    }

    /**
     * Vision `VNHomographicImageRegistrationRequest` karşılığı: AI çıktısını
     * referansa oturtan ölçek+ötelemeyi kaba→ince ızgara aramasıyla bulur
     * (model çıktıyı ~%10 küçültüp kaydırıyor). `costAt(w, h)` o çözünürlükte
     * bükülmüş parlaklık dizisini puanlayan fonksiyonu döndürür (düşük = iyi).
     */
    private fun searchSimilarity(
        ai: Bitmap,
        size: Size,
        costAt: (Int, Int) -> ((FloatArray) -> Double),
    ): Similarity? = runCatching {
        fun lumaPlane(w: Int, h: Int): FloatArray = ai.scaledPixels(w, h).let { px -> FloatArray(px.size) { (luma(px[it]) / 255).toFloat() } }
        fun warpPlane(src: FloatArray, w: Int, h: Int, s: Float, tx: Float, ty: Float): FloatArray {
            val out = FloatArray(w * h)
            val cx = w / 2f; val cy = h / 2f
            for (y in 0 until h) {
                val sy = (y + 0.5f - cy - ty) / s + cy - 0.5f
                if (sy < 0 || sy > h - 1) continue           // dışarısı siyah
                val y0 = sy.toInt(); val y1 = min(h - 1, y0 + 1); val fy = sy - y0
                for (x in 0 until w) {
                    val sx = (x + 0.5f - cx - tx) / s + cx - 0.5f
                    if (sx < 0 || sx > w - 1) continue
                    val x0 = sx.toInt(); val x1 = min(w - 1, x0 + 1); val fx = sx - x0
                    val a = src[y0 * w + x0] * (1 - fx) + src[y0 * w + x1] * fx
                    val b = src[y1 * w + x0] * (1 - fx) + src[y1 * w + x1] * fx
                    out[y * w + x] = a * (1 - fy) + b * fy
                }
            }
            return out
        }
        // Kaba: 96 px genişlik
        val wc = 96; val hc = max(8, (wc * size.height / size.width).roundToInt())
        val srcC = lumaPlane(wc, hc)
        val costC = costAt(wc, hc)
        var bs = 1f; var btx = 0f; var bty = 0f
        var best = costC(srcC)
        val identityCost = best
        var s = 0.85f
        while (s <= 1.2001f) {
            var tx = -0.1f * wc
            while (tx <= 0.1f * wc + 0.001f) {
                var ty = -0.1f * hc
                while (ty <= 0.1f * hc + 0.001f) {
                    val c = costC(warpPlane(srcC, wc, hc, s, tx, ty))
                    if (c < best) { best = c; bs = s; btx = tx; bty = ty }
                    ty += 1.5f
                }
                tx += 1.5f
            }
            s += 0.025f
        }
        // İnce: 192 px genişlik, en iyinin çevresi
        val wf = 192; val hf = max(8, (wf * size.height / size.width).roundToInt())
        val k = wf.toFloat() / wc
        val srcF = lumaPlane(wf, hf)
        val costF = costAt(wf, hf)
        var fs = bs; var ftx = btx * k; var fty = bty * k
        var fbest = costF(warpPlane(srcF, wf, hf, fs, ftx, fty))
        val cs = fs; val ctx = ftx; val cty = fty
        for (ds in -4..4) for (dx in -3..3) for (dy in -3..3) {
            val ss = cs + ds * 0.005f; val tx = ctx + dx; val ty = cty + dy
            val c = costF(warpPlane(srcF, wf, hf, ss, tx, ty))
            if (c < fbest) { fbest = c; fs = ss; ftx = tx; fty = ty }
        }
        if (best >= identityCost && fs == 1f && ftx == 0f && fty == 0f) return@runCatching null
        Similarity(fs, ftx * size.width / wf, fty * size.height / hf, size.width / 2f, size.height / 2f)
    }.getOrNull()

    /**
     * AI çıktısını orijinal kırpıma hizalar. Dönüşüm konvansiyonuna güvenmek
     * yerine adayları (T, T⁻¹, kimlik) ağız DIŞINDAKİ piksel farkıyla puanlar ve
     * en iyisini seçer — kayıt başarısız olursa hizalanmamış çıktıya düşer.
     */
    fun align(ai: Bitmap, reference: Bitmap, aiCG: Bitmap, mask: MaskImage, size: Size): Bitmap {
        val sim = searchSimilarity(aiCG, size) { w, h ->
            val ref = reference.scaledPixels(w, h).let { px -> FloatArray(px.size) { (luma(px[it]) / 255).toFloat() } }
            val m = mask.bytes(w, h)
            val mx = w * 6 / 100; val my = h * 6 / 100
            val cost: (FloatArray) -> Double = { warped ->
                var total = 0.0; var count = 0
                for (y in my until h - my) for (x in mx until w - mx) {
                    val i = y * w + x
                    if (m[i] >= 8) continue
                    total += abs(warped[i] - ref[i]); count++
                }
                if (count > 0) total / count else Double.MAX_VALUE
            }
            cost
        } ?: return ai
        val candidates = listOf(ai, warp(ai, by = sim.matrix(), size = size), warp(ai, by = sim.inverse().matrix(), size = size))
        return candidates.minByOrNull { outsideMouthDifference(it, reference, mask, size) } ?: ai
    }

    /** Dönüşümü (ölçek/öteleme ya da perspektif `Matrix`) uygular; boş kalan alan siyah. */
    fun warp(image: Bitmap, by: Matrix, size: Size): Bitmap {
        val w = size.width.roundToInt(); val h = size.height.roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(image.softwareCopy(), by, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /**
     * Ağız dışındaki (maske≈0) bölgede ortalama mutlak renk farkı. Küçük
     * çözünürlükte hesaplanır; sadece adayları karşılaştırmak için.
     */
    fun outsideMouthDifference(a: Bitmap, b: Bitmap, mask: MaskImage, size: Size): Double {
        val sw = 192; val sh = max(8, (sw * size.height / size.width).roundToInt())
        val pa = a.scaledPixels(sw, sh); val pb = b.scaledPixels(sw, sh); val pm = mask.bytes(sw, sh)
        // Kenarlardan %6 içeride ölç (warp'ın boş bıraktığı şeritleri cezalandırmadan).
        val mx = sw * 6 / 100; val my = sh * 6 / 100
        var total = 0.0; var count = 0.0
        for (y in my until sh - my) for (x in mx until sw - mx) {
            val i = y * sw + x
            if (pm[i] >= 8) continue
            val ca = pa[i]; val cb = pb[i]
            total += (abs(Color.red(ca) - Color.red(cb)) + abs(Color.green(ca) - Color.green(cb)) + abs(Color.blue(ca) - Color.blue(cb))).toDouble()
            count += 1
        }
        return if (count > 0) total / count else Double.POSITIVE_INFINITY
    }

    /**
     * Yerleşim kalitesi: dudak halkasında (açıklığın hemen dışı, dudaklar ve
     * ağız köşeleri) yumuşatılmış parlaklığın normalize çapraz korelasyonu
     * (−1…1). Renk/doku farkından etkilenmez; model ağzı kapatıp/yeniden
     * çizip dudak konturunu değiştirdiyse düşer. Eşik: `minPlacementScore`.
     */
    fun placementScore(ai: Bitmap, original: Bitmap, region: MouthRegion): Double {
        val size = Size(region.cropW.toFloat(), region.cropH.toFloat())
        val mw = region.mouthBounds.width
        val outer = polygonMask(openingPolygon(region), region, scale = 1f, dilate = mw * 0.16f, feather = 0f)
        val inner = polygonMask(openingPolygon(region), region, scale = 1f, dilate = mw * 0.02f, feather = 0f)
        val W = 240
        val H = max(8, (W * size.height / size.width).roundToInt())
        val sigma = mw * 0.012f * W / size.width
        fun smoothLuma(img: Bitmap): FloatArray {
            val px = img.scaledPixels(W, H)
            val l = FloatArray(px.size) { luma(px[it]).toFloat() }
            gaussianBlurPlane(l, W, H, sigma)
            return l
        }
        val pa = smoothLuma(ai); val pb = smoothLuma(original)
        val po = outer.bytes(W, H); val pi = inner.bytes(W, H)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until W * H) {
            if (po[i] <= 127 || pi[i] >= 128) continue        // halka: dış içinde, iç dışında
            xs.add(pa[i].toDouble()); ys.add(pb[i].toDouble())
        }
        if (xs.size <= 100) return 0.0
        val n = xs.size.toDouble()
        val mxv = xs.sum() / n; val myv = ys.sum() / n
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (k in xs.indices) {
            val dx = xs[k] - mxv; val dy = ys[k] - myv
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        return sxy / max(sqrt(sxx * syy), 1e-6)
    }

    // MARK: - Yardımcılar

    fun resize(image: Bitmap, size: Size): Bitmap? = runCatching {
        val w = size.width.roundToInt(); val h = size.height.roundToInt()
        val src = image.softwareCopy()
        if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
    }.getOrNull()

    /** Monotone chain dışbükey zarf. */
    fun convexHull(points: List<Offset>): List<Offset> {
        val p = points.sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })
        if (p.size <= 2) return p
        fun cross(o: Offset, a: Offset, b: Offset): Float = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<Offset>(); val upper = mutableListOf<Offset>()
        for (pt in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0) lower.removeAt(lower.size - 1)
            lower.add(pt)
        }
        for (pt in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0) upper.removeAt(upper.size - 1)
            upper.add(pt)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** `a·t + b·(1−t)` piksel karışımı (opak sonuç). */
    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return b or (0xFF shl 24)
        if (t >= 1f) return a or (0xFF shl 24)
        val u = 1f - t
        val r = (Color.red(a) * t + Color.red(b) * u + 0.5f).toInt()
        val g = (Color.green(a) * t + Color.green(b) * u + 0.5f).toInt()
        val bl = (Color.blue(a) * t + Color.blue(b) * u + 0.5f).toInt()
        return Color.argb(255, r, g, bl)
    }
}
