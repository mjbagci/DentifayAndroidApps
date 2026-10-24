package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// CoreImage karşılığı yardımcılar: gri maske (CIImage maskeleri), Gauss bulanıklığı,
// morfolojik min/maks, Bitmap piksel erişimi. Tüm koordinatlar sol-üst orijinli.

/**
 * Gri maske (0–1), kırpım ya da tam fotoğraf koordinatlarında, sol-üst orijin.
 * Swift'teki maske `CIImage`'larının karşılığı. Kenar dışı okumalar kenar değerini
 * verir (`clampedToExtent`).
 */
class MaskImage(val width: Int, val height: Int, val data: FloatArray) {
    init { require(data.size == width * height) }

    operator fun get(x: Int, y: Int): Float = data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun copy(): MaskImage = MaskImage(width, height, data.copyOf())

    /** CIGaussianBlur (yarıçap = σ), kenar sabitlemeli. */
    fun blurred(sigma: Float): MaskImage {
        if (sigma <= 0.3f) return copy()
        val out = data.copyOf()
        gaussianBlurPlane(out, width, height, sigma)
        return MaskImage(width, height, out)
    }

    /** CIMorphologyMinimum (kare pencere). */
    fun eroded(radius: Float): MaskImage = MaskImage(width, height, morphology(data, width, height, radius.roundToInt(), true))

    /** CIMorphologyMaximum (kare pencere). */
    fun dilated(radius: Float): MaskImage = MaskImage(width, height, morphology(data, width, height, radius.roundToInt(), false))

    /** CIMultiplyCompositing. */
    fun multiplied(other: MaskImage): MaskImage {
        val o = other.fitted(width, height)
        return MaskImage(width, height, FloatArray(data.size) { data[it] * o.data[it] })
    }

    /** CIMaximumCompositing. */
    fun maximum(other: MaskImage): MaskImage {
        val o = other.fitted(width, height)
        return MaskImage(width, height, FloatArray(data.size) { max(data[it], o.data[it]) })
    }

    /** `self − other`, 0'da kırpılır (CISubtractBlendMode). */
    fun subtracting(other: MaskImage): MaskImage {
        val o = other.fitted(width, height)
        return MaskImage(width, height, FloatArray(data.size) { max(0f, data[it] - o.data[it]) })
    }

    /** CIColorInvert. */
    fun inverted(): MaskImage = MaskImage(width, height, FloatArray(data.size) { 1f - data[it] })

    /** `v * gain + bias`, 0–1'e kırpılır (CIColorMatrix + CIColorClamp). */
    fun leveled(gain: Float, bias: Float): MaskImage =
        MaskImage(width, height, FloatArray(data.size) { (data[it] * gain + bias).coerceIn(0f, 1f) })

    /** Boyut farklıysa çift doğrusal yeniden örnekleme. */
    fun fitted(w: Int, h: Int): MaskImage = if (w == width && h == height) this else resized(w, h)

    /** Çift doğrusal yeniden örnekleme (piksel merkezleri hizalı). */
    fun resized(w: Int, h: Int): MaskImage {
        val out = FloatArray(w * h)
        val sx = width.toFloat() / w
        val sy = height.toFloat() / h
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = floor(fy).toInt(); val y1 = min(height - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = floor(fx).toInt(); val x1 = min(width - 1, x0 + 1); val tx = fx - x0
                val a = data[y0 * width + x0] * (1 - tx) + data[y0 * width + x1] * tx
                val b = data[y1 * width + x0] * (1 - tx) + data[y1 * width + x1] * tx
                out[y * w + x] = a * (1 - ty) + b * ty
            }
        }
        return MaskImage(w, h, out)
    }

    /** 0–255 tamsayı değerler (CoreImage `render` → RGBA8'in tek kanalı). */
    fun bytes(): IntArray = IntArray(data.size) { (data[it].coerceIn(0f, 1f) * 255f).roundToInt() }

    /** W×H'ye küçültülmüş 0–255 değerler. */
    fun bytes(w: Int, h: Int): IntArray = fitted(w, h).bytes()

    /** Opak gri Bitmap (beyaz = maske). */
    fun toGrayBitmap(): Bitmap {
        val px = IntArray(data.size) {
            val v = (data[it].coerceIn(0f, 1f) * 255f).roundToInt()
            Color.argb(255, v, v, v)
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        fun filled(width: Int, height: Int, value: Float): MaskImage = MaskImage(width, height, FloatArray(width * height) { value })

        /** Gri (ya da renkli) Bitmap'in parlaklığından maske. */
        fun fromBitmap(bmp: Bitmap): MaskImage {
            val src = bmp.softwareCopy()
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            return MaskImage(src.width, src.height, FloatArray(px.size) {
                val c = px[it]
                // Gri maske: R=G=B. Alfa ile çizilmişse alfayı da hesaba kat.
                val a = Color.alpha(c) / 255f
                ((Color.red(c) + Color.green(c) + Color.blue(c)) / 765f) * a
            })
        }
    }
}

// MARK: - Bulanıklık ve morfoloji

/** Yerinde Gauss bulanıklığı. Küçük σ'da tam çekirdek, büyükte 3 kutu (O(n)). */
internal fun gaussianBlurPlane(plane: FloatArray, w: Int, h: Int, sigma: Float) {
    if (sigma <= 0.3f || w <= 0 || h <= 0) return
    if (sigma <= 3f) {
        val r = ceil(sigma * 3).toInt()
        val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); exp(-(d * d) / (2 * sigma * sigma)) }
        val s = k.sum(); for (i in k.indices) k[i] /= s
        val tmp = FloatArray(plane.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (j in -r..r) acc += plane[row + (x + j).coerceIn(0, w - 1)] * k[j + r]
                tmp[row + x] = acc
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f
                for (j in -r..r) acc += tmp[(y + j).coerceIn(0, h - 1) * w + x] * k[j + r]
                plane[y * w + x] = acc
            }
        }
        return
    }
    for (box in boxesForGauss(sigma, 3)) {
        val r = (box - 1) / 2
        boxBlurH(plane, w, h, r)
        boxBlurV(plane, w, h, r)
    }
}

private fun boxesForGauss(sigma: Float, n: Int): IntArray {
    val wIdeal = sqrt((12.0 * sigma * sigma / n) + 1)
    var wl = floor(wIdeal).toInt(); if (wl % 2 == 0) wl--
    val wu = wl + 2
    val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4)
    val m = mIdeal.roundToInt()
    return IntArray(n) { if (it < m) wl else wu }
}

private fun boxBlurH(p: FloatArray, w: Int, h: Int, r: Int) {
    if (r <= 0) return
    val row = FloatArray(w)
    val norm = 1f / (2 * r + 1)
    for (y in 0 until h) {
        val o = y * w
        System.arraycopy(p, o, row, 0, w)
        var acc = 0f
        for (j in -r..r) acc += row[j.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            p[o + x] = acc * norm
            acc += row[(x + r + 1).coerceAtMost(w - 1)] - row[(x - r).coerceAtLeast(0)]
        }
    }
}

private fun boxBlurV(p: FloatArray, w: Int, h: Int, r: Int) {
    if (r <= 0) return
    val col = FloatArray(h)
    val norm = 1f / (2 * r + 1)
    for (x in 0 until w) {
        for (y in 0 until h) col[y] = p[y * w + x]
        var acc = 0f
        for (j in -r..r) acc += col[j.coerceIn(0, h - 1)]
        for (y in 0 until h) {
            p[y * w + x] = acc * norm
            acc += col[(y + r + 1).coerceAtMost(h - 1)] - col[(y - r).coerceAtLeast(0)]
        }
    }
}

/** Kayan pencere min/maks (kenar sabitlemeli), ayrılabilir kare çekirdek. */
private fun morphology(src: FloatArray, w: Int, h: Int, r: Int, isMin: Boolean): FloatArray {
    if (r <= 0) return src.copyOf()
    val tmp = FloatArray(src.size)
    val line = FloatArray(max(w, h))
    val outLine = FloatArray(max(w, h))
    for (y in 0 until h) {
        for (x in 0 until w) line[x] = src[y * w + x]
        slidingExtreme(line, w, r, isMin, outLine)
        for (x in 0 until w) tmp[y * w + x] = outLine[x]
    }
    val out = FloatArray(src.size)
    for (x in 0 until w) {
        for (y in 0 until h) line[y] = tmp[y * w + x]
        slidingExtreme(line, h, r, isMin, outLine)
        for (y in 0 until h) out[y * w + x] = outLine[y]
    }
    return out
}

private fun slidingExtreme(a: FloatArray, n: Int, r: Int, isMin: Boolean, out: FloatArray) {
    // Monoton kuyruk; pencere [i−r, i+r], kenar dışı = kenar değeri.
    val dq = IntArray(n + 2 * r + 1)
    var head = 0; var tail = 0
    fun v(i: Int): Float = a[i.coerceIn(0, n - 1)]
    fun better(x: Float, y: Float) = if (isMin) x <= y else x >= y
    var next = -r
    for (i in 0 until n) {
        while (next <= i + r) {
            val vn = v(next)
            while (tail > head && better(vn, v(dq[tail - 1]))) tail--
            dq[tail++] = next
            next++
        }
        while (dq[head] < i - r) head++
        out[i] = v(dq[head])
    }
}

// MARK: - Bitmap yardımcıları

/** Yazılım (HARDWARE değil) ARGB_8888 Bitmap. */
internal fun Bitmap.softwareCopy(): Bitmap =
    if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

internal fun Bitmap.pixels(): IntArray {
    val src = softwareCopy()
    val px = IntArray(src.width * src.height)
    src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
    return px
}

/** Siyah zemin üzerine W×H'ye ölçeklenmiş pikseller (CoreImage `render` karşılığı). */
internal fun Bitmap.scaledPixels(w: Int, h: Int): IntArray {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.BLACK)
    c.drawBitmap(softwareCopy(), null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    out.recycle()
    return px
}

internal fun bitmapFromPixels(px: IntArray, w: Int, h: Int): Bitmap {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    b.setPixels(px, 0, w, 0, 0, w, h)
    return b
}

/** CIColorMatrix (köşegen kazanç + 0–1 sapma) + CIColorClamp. */
internal fun Bitmap.colorMatrixed(gains: FloatArray, bias: FloatArray): Bitmap {
    val cm = ColorMatrix(floatArrayOf(
        gains[0], 0f, 0f, 0f, bias[0] * 255f,
        0f, gains[1], 0f, 0f, bias[1] * 255f,
        0f, 0f, gains[2], 0f, bias[2] * 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    return filtered(cm)
}

/** CIColorControls saturation 0. */
internal fun Bitmap.desaturated(): Bitmap = filtered(ColorMatrix().apply { setSaturation(0f) })

private fun Bitmap.filtered(cm: ColorMatrix): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.BLACK)
    c.drawBitmap(softwareCopy(), 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    return out
}

internal fun luma(c: Int): Double = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

// MARK: - Geometri yardımcıları

internal fun Rect.insetBy(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right - dx, bottom - dy)

/** CGRect.integral: kenarlar dışa doğru tamsayıya yuvarlanır. */
internal fun Rect.integral(): Rect = Rect(floor(left), floor(top), ceil(right), ceil(bottom))

internal fun Rect.toAndroid(): android.graphics.Rect =
    android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

internal fun boundsOf(points: List<Offset>): Rect {
    val xs = points.map { it.x }; val ys = points.map { it.y }
    return Rect(xs.min(), ys.min(), xs.max(), ys.max())
}

internal fun centroidOf(points: List<Offset>): Offset? {
    if (points.isEmpty()) return null
    val n = points.size.toFloat()
    return Offset(points.sumOf { it.x.toDouble() }.toFloat() / n, points.sumOf { it.y.toDouble() }.toFloat() / n)
}

/**
 * Kontur noktalarını merkez etrafında açıya göre sıralar — ML Kit dudak konturları
 * (üst/alt yarılar ayrı gelir) tek bir kapalı halkaya dönüşür.
 */
internal fun orderedRing(points: List<Offset>): List<Offset> {
    val c = centroidOf(points) ?: return points
    return points.sortedBy { atan2((it.y - c.y).toDouble(), (it.x - c.x).toDouble()) }
}

/**
 * ML Kit yüzünden simülasyonun kullandığı noktalar (görüntü pikseli, sol-üst orijin).
 * Vision'daki `outerLips / innerLips / leftPupil / leftEye / nose` karşılıkları.
 */
internal class SmileFaceParts(private val face: Face) {
    private fun contour(type: Int): List<Offset> =
        face.getContour(type)?.points?.map { p: PointF -> Offset(p.x, p.y) } ?: emptyList()

    private fun landmark(type: Int): Offset? = face.getLandmark(type)?.position?.let { Offset(it.x, it.y) }

    val outerLips: List<Offset> = orderedRing(contour(FaceContour.UPPER_LIP_TOP) + contour(FaceContour.LOWER_LIP_BOTTOM))
    val innerLips: List<Offset> = orderedRing(contour(FaceContour.UPPER_LIP_BOTTOM) + contour(FaceContour.LOWER_LIP_TOP))
    val leftEyeContour: List<Offset> = contour(FaceContour.LEFT_EYE)
    val rightEyeContour: List<Offset> = contour(FaceContour.RIGHT_EYE)
    /** Göz bebeği (ML Kit göz işareti = göz merkezi) ya da göz konturunun ağırlık merkezi. */
    val leftPupil: Offset? = landmark(FaceLandmark.LEFT_EYE) ?: centroidOf(leftEyeContour)
    val rightPupil: Offset? = landmark(FaceLandmark.RIGHT_EYE) ?: centroidOf(rightEyeContour)
    /** Burun alt konturu (kanatlar + uç) merkezi — simetrik burun konturunun merkezi. */
    val nose: Offset? = centroidOf(contour(FaceContour.NOSE_BOTTOM)) ?: landmark(FaceLandmark.NOSE_BASE)
    val box: Rect = face.boundingBox.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
}

internal fun hypot2(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
internal fun absf(v: Float): Float = abs(v)
