package com.dentifay.app.smile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.mlkit.vision.face.Face
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Kamera karesinin parlaklık (Y) düzlemi — `CVPixelBuffer` karşılığı.
 * `bytes` ham (sensör yönünde) `width × height` dizidir; `rotationDegrees` dik
 * görüntüye çevirmek için gereken saat yönü dönüş, `mirrored` ön kamerada
 * önizlemeyle aynı (aynalı) koordinatlar istendiğini belirtir.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val rotationDegrees: Int,
    val mirrored: Boolean,
) {
    /** Dik (upright) görüntünün boyutu. */
    val uprightSize: Size
        get() = if (rotationDegrees % 180 == 0) Size(width.toFloat(), height.toFloat()) else Size(height.toFloat(), width.toFloat())

    /** Dik + (gerekirse) aynalı, sol-üst orijinli normalize noktadaki parlaklık (0–1). */
    fun luma(u: Float, v: Float): Double {
        val uu = if (mirrored) 1f - u else u
        val (ru, rv) = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> v to 1f - uu
            180 -> 1f - uu to 1f - v
            270 -> 1f - v to uu
            else -> uu to v
        }
        val x = (ru * width).toInt().coerceIn(0, width - 1)
        val y = (rv * height).toInt().coerceIn(0, height - 1)
        return (bytes[y * width + x].toInt() and 0xFF) / 255.0
    }
}

/**
 * Çekim rehberi: canlı kameradaki her karede yüzün gülüş simülasyonuna
 * uygun olup olmadığını ölçer. Amaç, maskenin (diş açıklığı) güvenle
 * seçilebileceği bir fotoğraf: önden, yakın, dişler görünür, iyi ışık.
 *
 * Eşikler klinik fotoğraf standartlarından (önden, baş düz, interpupiller
 * hat yatay) ve modelin ihtiyacından (ağız ≥ ~250 px) türetildi.
 */
class SmileCaptureGuide {

    enum class Check {
        face, distance, centered, level, frontal, teeth, light, steady;

        val rawValue: String get() = name
        val title: String
            get() = when (this) {
                face -> "Yüz"
                distance -> "Mesafe"
                centered -> "Ortalı"
                level -> "Düz"
                frontal -> "Önden"
                teeth -> "Dişler"
                light -> "Işık"
                steady -> "Sabit"
            }

        companion object {
            val allCases: List<Check> get() = entries.toList()
        }
    }

    data class State(
        val passed: Set<Check> = emptySet(),
        /** Kullanıcıya gösterilecek tek, en öncelikli yönerge. */
        val hint: String = "Hastanın yüzünü çerçeveye alın",
        /** Algılanan ağız dikdörtgeni — dik (upright) görüntüde, sol-üst orijinli, 0–1. */
        val mouthRect: Rect? = null,
        /** Algılanan yüz dikdörtgeni — aynı koordinatlarda. */
        val faceRect: Rect? = null,
    ) {
        val isReady: Boolean get() = passed.size == Check.entries.size
    }

    companion object {
        // Eşikler
        const val minFaceWidth: Float = 0.42f       // kare genişliğine oran (ICAO: baş genişliği %50–75)
        const val maxFaceWidth: Float = 0.80f
        const val maxRollDegrees: Float = 4f        // 1 mm kesici eğimi fark ediliyor (Kokich)
        const val maxYawRatio: Float = 0.10f        // burun ucu sapması / göz arası mesafe (~5–6°)
        const val minOpeningRatio: Float = 0.09f    // iç dudak açıklığı / ağız genişliği
        const val minTeethContrast: Double = 1.12   // ağız içi / dudak parlaklık oranı
        const val minLuma: Double = 0.30
        const val maxMotion: Float = 0.018f         // ardışık karelerde yüz merkezi kayması
    }

    private var lastCenter: Offset? = null
    private var steadyFrames = 0

    /**
     * `face` dik görüntüde (ML Kit `InputImage` dönüşüyle) yapılmış algılama sonucu olmalı.
     * `frameSize`: dik görüntünün piksel boyutu; `mirrored`: ön kamerada x aynalanır
     * (rehber dikdörtgenleri önizlemeyle aynı yönde olsun).
     */
    fun evaluate(
        face: Face?,
        pixelBuffer: LumaFrame?,
        frameSize: Size = pixelBuffer?.uprightSize ?: Size(1f, 1f),
        mirrored: Boolean = pixelBuffer?.mirrored ?: false,
    ): State {
        val passed = mutableSetOf<Check>()
        var hint = State().hint
        if (face == null) {
            lastCenter = null; steadyFrames = 0
            return State()
        }
        val parts = SmileFaceParts(face)
        passed.add(Check.face)
        val fw = max(frameSize.width, 1f); val fh = max(frameSize.height, 1f)
        // Piksel → normalize (0–1), sol-üst orijin; ön kamerada aynalı
        fun norm(p: Offset): Offset = Offset(if (mirrored) 1f - p.x / fw else p.x / fw, p.y / fh)
        fun normAll(list: List<Offset>): List<Offset> = list.map { norm(it) }

        val b = parts.box
        val faceRect = if (mirrored)
            Rect(1f - b.right / fw, b.top / fh, 1f - b.left / fw, b.bottom / fh)
        else
            Rect(b.left / fw, b.top / fh, b.right / fw, b.bottom / fh)
        var mouthRect: Rect? = null

        val outer = normAll(parts.outerLips)
        val inner = normAll(parts.innerLips)
        val leftEye = parts.leftPupil?.let { norm(it) }
        val rightEye = parts.rightPupil?.let { norm(it) }
        val nose = parts.nose?.let { norm(it) }

        // Mesafe
        if (faceRect.width < minFaceWidth) {
            hint = "Biraz yaklaşın"
        } else if (faceRect.width > maxFaceWidth) {
            hint = "Biraz uzaklaşın — yüzün tamamı görünsün"
        } else {
            passed.add(Check.distance)
        }

        // Ortalama
        val c = faceRect.center
        if (abs(c.x - 0.5f) < 0.16f && abs(c.y - 0.46f) < 0.2f) passed.add(Check.centered)
        else if (passed.contains(Check.distance)) hint = "Yüzü çerçevenin ortasına alın"

        // Baş eğimi (roll) — göz çizgisi yatay olmalı
        if (leftEye != null && rightEye != null) {
            val l = leftEye; val r = rightEye
            val angle = abs(atan2(r.y - l.y, r.x - l.x) * 180f / PI.toFloat())
            val roll = min(angle, abs(180f - angle))
            if (roll <= maxRollDegrees) passed.add(Check.level)
            else if (passed.containsAll(listOf(Check.distance, Check.centered))) hint = "Başı düz tutun"

            // Dönüklük (yaw) — burun ucu gözlerin ortasında olmalı
            if (nose != null) {
                val eyeMid = Offset((l.x + r.x) / 2, (l.y + r.y) / 2)
                val interEye = max(hypot2(r.x - l.x, r.y - l.y), 0.001f)
                if (abs(nose.x - eyeMid.x) / interEye <= maxYawRatio) passed.add(Check.frontal)
                else if (passed.containsAll(listOf(Check.distance, Check.centered, Check.level))) hint = "Kameraya tam önden bakın"
            }
        }

        // Dişler görünür mü — iç dudak açıklığı + ağız içi dudaktan parlak
        if (outer.size >= 6 && inner.size >= 4) {
            val ob = boundsOf(outer); val ib = boundsOf(inner)
            mouthRect = ob
            val opening = ib.height / max(ob.width, 0.001f)
            var contrastOK = true
            if (pixelBuffer != null) {
                // Ortalama değil en parlak %20: eksik diş boşlukları kontrolü bozmasın
                val innerLuma = meanLuma(pixelBuffer, ib.insetBy(ib.width * 0.12f, ib.height * 0.2f), fw, fh, percentile = 0.8)
                val lipBand = Rect(
                    ob.center.x - ob.width * 0.2f, ob.top,
                    ob.center.x + ob.width * 0.2f, ob.top + max(0.002f, ib.top - ob.top),
                )
                val lipLuma = meanLuma(pixelBuffer, lipBand, fw, fh)
                if (innerLuma != null && lipLuma != null) contrastOK = innerLuma > lipLuma * minTeethContrast
            }
            if (opening >= minOpeningRatio && contrastOK) passed.add(Check.teeth)
            else if (passed.containsAll(listOf(Check.distance, Check.centered, Check.level, Check.frontal))) hint = "Gülümseyin — dişler görünsün"
        }

        // Işık (yüz bölgesi ortalama parlaklığı)
        val faceLuma = pixelBuffer?.let { meanLuma(it, faceRect.insetBy(faceRect.width * 0.2f, faceRect.height * 0.2f), fw, fh) }
        if (faceLuma != null) {
            if (faceLuma >= minLuma) passed.add(Check.light)
            else if (passed.size >= 5) hint = "Işık yetersiz — daha aydınlık bir yere geçin"
        } else {
            passed.add(Check.light)
        }

        // Sabitlik
        val last = lastCenter
        if (last != null && hypot2(c.x - last.x, c.y - last.y) < maxMotion) steadyFrames += 1 else steadyFrames = 0
        lastCenter = c
        if (steadyFrames >= 4) passed.add(Check.steady)
        else if (passed.size == Check.entries.size - 1) hint = "Sabit tutun…"

        val st = State(passed = passed, hint = hint, mouthRect = mouthRect, faceRect = faceRect)
        return if (st.isReady) st.copy(hint = "Harika — çekiliyor") else st
    }

    // MARK: - Yardımcılar

    /**
     * Dik yönlü, sol-üst orijinli normalize dikdörtgenin ortalama parlaklığı (0–1).
     * Seyrek örnekleme — karede ~0.1 ms.
     */
    private fun meanLuma(pb: LumaFrame, r: Rect, fw: Float, fh: Float, percentile: Double? = null): Double? {
        val w = fw.toInt(); val h = fh.toInt()
        val x0 = max(0, (r.left * w).toInt()); val x1 = min(w - 1, (r.right * w).toInt())
        val y0 = max(0, (r.top * h).toInt()); val y1 = min(h - 1, (r.bottom * h).toInt())
        if (x1 <= x0 || y1 <= y0) return null
        val step = max(1, min(x1 - x0, y1 - y0) / 12)
        val values = ArrayList<Double>()
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                values.add(pb.luma((x + 0.5f) / w, (y + 0.5f) / h))
                x += step
            }
            y += step
        }
        if (values.isEmpty()) return null
        if (percentile != null) {
            values.sort()
            return values[min(values.size - 1, (values.size * percentile).toInt())]
        }
        return values.sum() / values.size
    }
}
