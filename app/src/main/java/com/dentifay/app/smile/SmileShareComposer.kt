package com.dentifay.app.smile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.Rect
import com.dentifay.app.R
import com.dentifay.app.core.Prefs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hastaya gönderilen önce/sonra görseli.
 *
 * Fotoğraf çekildiği gibi, TAM KARE gider (yalnız ağız kırpımı değil): önce ve
 * sonra yan yana, koyu lacivert zemin, şampanya altını ince çizgiler, serif
 * başlık (hasta adı), Dentifay logosu, tarih ve yapay zeka uyarısı. "Detaylı"
 * düzende altına gülüş yakın planı da yan yana eklenir.
 */
data class SmileShareContent(
    /** Tam fotoğraf (çekildiği gibi, yönü düzeltilmiş). */
    val before: Bitmap,
    /** Aynı karede simülasyon sonucu. */
    val after: Bitmap,
    /** Gülüş yakın planı (tam fotoğraf piksel koordinatlarında). */
    val zoomRect: Rect?,
    val designTitle: String,
    val designSummary: String,
    val tierTitle: String,
    val createdAt: Instant,
)

enum class SmileShareLayout {
    full, detailed;

    val rawValue: String get() = name
    val id: String get() = rawValue
    val title: String
        get() = when (this) {
            full -> "Tam fotoğraf"
            detailed -> "Tam + gülüş detayı"
        }

    companion object {
        val allCases: List<SmileShareLayout> get() = entries.toList()
    }
}

object SmileShareComposer {

    // MARK: Palet

    private val bgTop = Color.rgb(10, 14, 23)          // #0A0E17
    private val bgBottom = Color.rgb(18, 24, 38)       // #121826
    private val gold = Color.rgb(214, 185, 140)        // #D6B98C
    private val ink = Color.rgb(244, 241, 234)         // #F4F1EA
    private val dim = Color.rgb(169, 175, 188)         // #A9AFBC
    private val cyan = Color.rgb(0, 229, 255)          // marka

    private val tr: Locale = Locale.forLanguageTag("tr-TR")

    /** Kanvas genişliği (px). Mesaj/e-posta için net, WhatsApp sıkıştırmasına dayanıklı. */
    private const val canvasWidth = 2400f

    private fun withAlpha(c: Int, a: Float): Int = Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    private enum class Weight { regular, semibold }

    /** UIFont karşılığı: boyut + ağırlık (+ serif). */
    private fun font(size: Float, weight: Weight, serif: Boolean = false, color: Int, kern: Float = 0f): TextPaint {
        val family = if (serif) Typeface.SERIF else Typeface.DEFAULT
        val tf = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, if (weight == Weight.semibold) 600 else 400, false)
        } else {
            Typeface.create(family, if (weight == Weight.semibold) Typeface.BOLD else Typeface.NORMAL)
        }
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = size
            this.color = color
            letterSpacing = if (size > 0) kern / size else 0f
        }
    }

    private fun TextPaint.lineHeight(): Float = fontMetrics.let { it.descent - it.ascent + it.leading }

    /** Metni sol-üst noktasından çizer (UIKit `draw(at:)`). */
    private fun Canvas.drawTextAt(text: String, x: Float, y: Float, paint: TextPaint) {
        drawText(text, x, y - paint.fontMetrics.ascent, paint)
    }

    fun render(c: SmileShareContent, layout: SmileShareLayout, patientName: String?): Bitmap? = runCatching {
        val bCG = c.before.softwareCopy()
        val aCG = c.after.softwareCopy()
        val W = canvasWidth
        val u = W / 100
        val m = 5.5f * u
        val gap = 2.2f * u
        val pw = (W - 2 * m - gap) / 2
        val photoAspect = bCG.height.toFloat() / max(bCG.width, 1)
        val ph = (pw * photoAspect).roundToInt().toFloat()

        // Yakın plan (yalnız "Detaylı")
        var zoom: Triple<Bitmap, Bitmap, Float>? = null
        val zr = c.zoomRect?.integral()
        if (layout == SmileShareLayout.detailed && zr != null && zr.width > 10 && zr.height > 10) {
            val zb = cropOrNull(bCG, zr)
            val za = cropOrNull(aCG, zr)
            if (zb != null && za != null) zoom = Triple(zb, za, (pw * zr.height / zr.width).roundToInt().toFloat())
        }

        val name = patientName?.trim() ?: ""
        val title = if (name.isEmpty()) "Yeni Gülüşünüz" else name
        val design = c.designTitle.uppercase(tr)
        val date = DateTimeFormatter.ofPattern("d MMMM yyyy", tr).withZone(ZoneId.systemDefault()).format(c.createdAt).uppercase(tr)

        // Yazı tipleri
        val serif = font(4.6f * u, Weight.semibold, serif = true, color = ink, kern = 0.05f * u)
        val brandFont = font(2.3f * u, Weight.semibold, color = ink, kern = 0.55f * u)
        val smallCapsGold = font(1.35f * u, Weight.semibold, color = gold, kern = 0.45f * u)
        val labelFontSize = 1.55f * u
        val noteFont = font(1.25f * u, Weight.regular, color = dim)
        val labelLineHeight = font(labelFontSize, Weight.semibold, color = dim).lineHeight()
        val smallCapsLineHeight = smallCapsGold.lineHeight()

        // Dikey yerleşim
        val logoSize = 7 * u
        val headerBottom = m + logoSize
        val titleTop = headerBottom + 4.2f * u
        val titleHeight = serif.lineHeight()
        val ornamentY = titleTop + titleHeight + 1.8f * u
        val photosTop = ornamentY + 3.6f * u
        val labelsTop = photosTop + ph + 1.8f * u
        var contentBottom = labelsTop + labelLineHeight + 0.4f * u
        var zoomTop = 0f
        zoom?.let {
            zoomTop = contentBottom + 5.2f * u
            contentBottom = zoomTop + it.third
        }
        val footerLineY = contentBottom + 4.2f * u
        val footerTextTop = footerLineY + 2 * u
        val noteWidth = W - 2 * m - 22 * u
        val noteText = "Bu görsel yapay zeka ile hazırlanmış bir tedavi simülasyonudur; gerçek tedavi sonucu farklılık gösterebilir."
        val noteLayout = staticLayout(noteText, noteFont, noteWidth.toInt())
        val noteHeight = ceil(noteLayout.height.toFloat())
        val H = (footerTextTop + max(noteHeight, noteFont.lineHeight()) + 0.8f * m).roundToInt()

        val out = Bitmap.createBitmap(W.toInt(), H, Bitmap.Config.ARGB_8888)
        val g = Canvas(out)

        // Zemin: dikey degrade + iki yumuşak ışık
        g.drawRect(0f, 0f, W, H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), bgTop, bgBottom, Shader.TileMode.CLAMP)
        })
        glow(g, photosTop - 6 * u, W * 0.5f, W * 0.55f, withAlpha(cyan, 0.07f), W, H.toFloat())
        glow(g, H * 0.92f, W * 0.92f, W * 0.45f, withAlpha(gold, 0.07f), W, H.toFloat())

        // Başlık satırı: logo + marka (sol), tarih (sağ)
        val logoRect = RectF(m, m, m + logoSize, m + logoSize)
        drawLogo(logoRect, logoSize * 0.24f, g)
        val brandX = logoRect.right + 2 * u
        g.drawTextAt("DENTIFAY", brandX, m + logoSize * 0.5f - brandFont.lineHeight() + 0.2f * u, brandFont)
        g.drawTextAt("GÜLÜŞ TASARIMI", brandX, m + logoSize * 0.5f + 0.5f * u, smallCapsGold)
        val datePaint = font(1.35f * u, Weight.semibold, color = dim, kern = 0.3f * u)
        g.drawTextAt(date, W - m - datePaint.measureText(date), m + logoSize * 0.5f - smallCapsLineHeight / 2, datePaint)

        // Başlık (hasta adı)
        val maxTitle = W - 2 * m
        val titleStr = TextUtils.ellipsize(title, serif, maxTitle, TextUtils.TruncateAt.END).toString()
        val tw = min(serif.measureText(titleStr), maxTitle)
        g.drawTextAt(titleStr, (W - tw) / 2, titleTop, serif)

        // Süs çizgisi: ─── DOĞAL ESTETİK · SİMÜLASYON ───
        val ornPaint = font(1.35f * u, Weight.semibold, color = gold, kern = 0.5f * u)
        val ornMax = W - 2 * m - 24 * u
        val orn = TextUtils.ellipsize("$design  ·  SİMÜLASYON", ornPaint, ornMax, TextUtils.TruncateAt.END).toString()
        val ow = min(ornPaint.measureText(orn), ornMax)
        val oy = ornamentY
        g.drawTextAt(orn, (W - ow) / 2, oy - smallCapsLineHeight / 2, ornPaint)
        val lineLen = 9 * u
        hairline(g, (W - ow) / 2 - 2 * u - lineLen, oy, (W - ow) / 2 - 2 * u, oy, withAlpha(gold, 0.55f), max(2f, 0.12f * u))
        hairline(g, (W + ow) / 2 + 2 * u, oy, (W + ow) / 2 + 2 * u + lineLen, oy, withAlpha(gold, 0.55f), max(2f, 0.12f * u))

        // Önce | Sonra — tam kare
        val left = RectF(m, photosTop, m + pw, photosTop + ph)
        val right = RectF(m + pw + gap, photosTop, m + pw + gap + pw, photosTop + ph)
        drawPhoto(bCG, left, 1.6f * u, g, u, accent = null)
        drawPhoto(aCG, right, 1.6f * u, g, u, accent = gold)
        drawLabel("ÖNCE", left, labelsTop, labelFontSize, dim, u, g)
        drawLabel("SONRA", right, labelsTop, labelFontSize, gold, u, g)

        // Gülüş detayı
        zoom?.let { z ->
            val hy = zoomTop - 2.6f * u
            val headPaint = font(1.35f * u, Weight.semibold, color = gold, kern = 0.5f * u)
            val head = "GÜLÜŞ DETAYI"
            val hw = headPaint.measureText(head)
            g.drawTextAt(head, (W - hw) / 2, hy - smallCapsLineHeight / 2, headPaint)
            hairline(g, m, hy, (W - hw) / 2 - 2 * u, hy, withAlpha(gold, 0.3f), max(2f, 0.1f * u))
            hairline(g, (W + hw) / 2 + 2 * u, hy, W - m, hy, withAlpha(gold, 0.3f), max(2f, 0.1f * u))
            drawPhoto(z.first, RectF(m, zoomTop, m + pw, zoomTop + z.third), 1.4f * u, g, u, accent = null)
            drawPhoto(z.second, RectF(m + pw + gap, zoomTop, m + pw + gap + pw, zoomTop + z.third), 1.4f * u, g, u, accent = gold)
        }

        // Alt bant: ince çizgi + uyarı (sol) + marka (sağ)
        hairline(g, m, footerLineY, W - m, footerLineY, withAlpha(gold, 0.32f), max(2f, 0.1f * u))
        g.save()
        g.translate(m, footerTextTop)
        noteLayout.draw(g)
        g.restore()
        val sitePaint = font(1.35f * u, Weight.semibold, color = gold, kern = 0.2f * u)
        val site = "dentifay.com"
        g.drawTextAt(site, W - m - sitePaint.measureText(site), footerTextTop, sitePaint)
        out
    }.getOrNull()

    // MARK: - Çizim yardımcıları

    private fun cropOrNull(src: Bitmap, r: Rect): Bitmap? = runCatching {
        val x = max(0, r.left.toInt()); val y = max(0, r.top.toInt())
        val w = min(src.width - x, r.width.toInt()); val h = min(src.height - y, r.height.toInt())
        if (w <= 0 || h <= 0) null else Bitmap.createBitmap(src, x, y, w, h)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        val spacing = paint.textSize * 0.25f
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(spacing, 1f)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, spacing, false)
        }
    }

    private fun hairline(g: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        g.drawLine(x0, y0, x1, y1, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        })
    }

    private fun glow(g: Canvas, cy: Float, cx: Float, radius: Float, color: Int, W: Float, H: Float) {
        g.drawRect(0f, 0f, W, H, Paint().apply {
            shader = RadialGradient(cx, cy, radius, color, withAlpha(color, 0f), Shader.TileMode.CLAMP)
        })
    }

    /** Yuvarlatılmış köşeli fotoğraf: yumuşak gölge + ince çerçeve (sonuçta altın). */
    private fun drawPhoto(image: Bitmap, rect: RectF, corner: Float, g: Canvas, u: Float, accent: Int?) {
        val path = Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) }
        g.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(3.2f * u, 0f, 0.9f * u, Color.argb((0.55f * 255).roundToInt(), 0, 0, 0))
        })

        g.save()
        g.clipPath(path)
        g.drawBitmap(image, null, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        g.restore()

        g.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent?.let { withAlpha(it, 0.55f) } ?: withAlpha(Color.WHITE, 0.12f)
            strokeWidth = max(2f, (if (accent == null) 0.08f else 0.12f) * u)
        })
    }

    private fun drawLabel(text: String, under: RectF, top: Float, size: Float, color: Int, u: Float, g: Canvas) {
        val p = font(size, Weight.semibold, color = color, kern = 0.6f * u)
        val w = p.measureText(text)
        g.drawTextAt(text, under.centerX() - w / 2, top, p)
    }

    /** Uygulama logosu (D + diş), uygulama simgesi gibi yuvarlatılmış kare içinde. */
    private fun drawLogo(rect: RectF, corner: Float, g: Canvas) {
        val path = Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) }
        g.save()
        g.clipPath(path)
        val logo = Prefs.appContext?.let { runCatching { BitmapFactory.decodeResource(it.resources, R.drawable.dentifay_splash_00) }.getOrNull() }
        if (logo != null) {
            val side = min(logo.width, logo.height).toFloat()
            val inset = side * 0.16f
            val crop = android.graphics.Rect(inset.toInt(), inset.toInt(), ceil(side - inset).toInt(), ceil(side - inset).toInt())
            g.drawBitmap(logo, crop, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            g.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(cyan, 0.2f) })
        }
        g.restore()
        g.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = withAlpha(Color.WHITE, 0.14f)
            strokeWidth = max(2f, rect.width() * 0.012f)
        })
    }
}
