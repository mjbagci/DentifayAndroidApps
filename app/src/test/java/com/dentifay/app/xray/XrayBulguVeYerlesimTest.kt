package com.dentifay.app.xray

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sonuç ayrıştırma (duyarlılık eşiği, öncelik sırası) ve kamera rehber çerçevesinin pencereye sığması — saf mantık. */
class XrayBulguVeYerlesimTest {

    private val sonuc = XrayResult(
        imageSize = listOf(1000.0, 500.0),
        teeth = mapOf(
            "t46" to XrayTooth(
                fdi = 46, box = listOf(280.0, 215.0, 353.0, 318.0),
                highRecs = listOf(XrayRec(cls = "KANAL_TEDAVISI", probability = 0.92)),
                midRecs = listOf(XrayRec(cls = "KANAL_TEDAVISI", probability = 0.40), XrayRec(cls = "DOLGU", probability = 0.45)),
            ),
            "t21" to XrayTooth(
                fdi = 21, box = listOf(494.0, 100.0, 542.0, 210.0),
                midRecs = listOf(XrayRec(cls = "DOLGU", probability = 0.58)),
                existingTreatments = listOf(XrayMark(cls = "EXISTING_KURON", probability = 0.8)),
            ),
            "t38" to XrayTooth(fdi = 38, box = listOf(755.0, 190.0, 827.0, 273.0), highRecs = listOf(XrayRec(cls = "CEKIM_20YAS", probability = 0.95))),
            "t11" to XrayTooth(fdi = 11, box = listOf(445.0, 100.0, 492.0, 210.0), existingTreatments = listOf(XrayMark(cls = "EXISTING_DOLGU", probability = 0.9))),
            "t31" to XrayTooth(fdi = 31, box = listOf(480.0, 290.0, 520.0, 380.0)),
            "gap_36" to XrayTooth(fdi = 36, box = listOf(200.0, 215.0, 270.0, 318.0)),
            "bozuk" to XrayTooth(fdi = 99, box = listOf(1.0, 2.0)),
        ),
    )

    @Test
    fun duyarlilikEsigiVeSiralama() {
        val dusuk = sonuc.findings(XraySensitivity.low.rawValue)      // ≥ 0,35
        // Kutusu bozuk diş atlanır; FDI sırası.
        assertEquals(listOf(11, 21, 31, 36, 38, 46), dusuk.map { it.fdi })
        val d46 = dusuk.first { it.fdi == 46 }
        // Aynı sınıfın en olası önerisi kalır (high + mid birleşir), olasılığa göre azalan.
        assertEquals(listOf("KANAL_TEDAVISI", "DOLGU"), d46.recs.map { it.cls })
        assertEquals(0.92, d46.recs.first().probability, 1e-9)
        // Normalize dikdörtgen.
        assertEquals(0.28f, d46.rect.left, 1e-6f)
        assertEquals(0.43f, d46.rect.top, 1e-6f)
        assertEquals(0.353f, d46.rect.right, 1e-6f)
        assertEquals(0.636f, d46.rect.bottom, 1e-6f)

        val yuksek = sonuc.findings(XraySensitivity.high.rawValue)    // ≥ 0,65
        assertTrue(yuksek.first { it.fdi == 21 }.recs.isEmpty())
        assertEquals(listOf("KANAL_TEDAVISI"), yuksek.first { it.fdi == 46 }.recs.map { it.cls })
    }

    @Test
    fun disTurleriVeEtiket() {
        val f = sonuc.findings(XraySensitivity.low.rawValue).associateBy { it.fdi }
        assertEquals(XrayToothFinding.Kind.finding, f.getValue(46).kind)
        assertEquals(XrayToothFinding.Kind.existing, f.getValue(11).kind)
        assertEquals(XrayToothFinding.Kind.healthy, f.getValue(31).kind)
        // `gap_` anahtarı sentetik (eksik diş alanı) — diş sayısına girmez.
        assertTrue(f.getValue(36).isSynthetic)
        assertEquals(XrayToothFinding.Kind.missing, f.getValue(36).kind)
        assertEquals("Eksik", f.getValue(36).badgeText)
        assertEquals("Kanal Tedavisi 92%", f.getValue(46).badgeText)
        assertEquals("Mevcut Dolgu", f.getValue(11).badgeText)
        assertNull(f.getValue(31).badgeText)
        assertTrue(f.getValue(38).isHighConfidence)
        assertFalse(f.getValue(21).isHighConfidence)
        assertEquals("Sol Alt", f.getValue(38).quadrant)
        assertEquals("Sağ Üst", f.getValue(11).quadrant)
    }

    /** "Bulgular" listesi: tedavi önceliği (çekim → periodontal → kanal → … → dolgu), eşitse FDI. */
    @Test
    fun tedaviOnceligi() {
        val sirali = sonuc.findings(XraySensitivity.low.rawValue).byTreatmentPriority()
        assertEquals(listOf(38, 46, 21), sirali.map { it.fdi })
        assertTrue(sirali.all { it.recs.isNotEmpty() })
    }

    @Test
    fun anatomiKutulari() {
        val r = XrayResult(
            imageSize = listOf(2000.0, 1000.0),
            anatomicalLayer = listOf(
                XrayLayerItem(cls = "ANAT_MAXILLARY_SINUS", probability = 0.8, box = listOf(200.0, 100.0, 600.0, 300.0)),
                XrayLayerItem(cls = "ANAT_MANDIBULAR_CANAL", probability = 0.7, box = listOf(1.0)),
            ),
        )
        val a = r.anatomy()
        assertEquals(1, a.size)
        assertEquals(0.1f, a[0].second.left, 1e-6f)
        assertEquals(0.3f, a[0].second.bottom, 1e-6f)
    }

    /** Dik telefon: iOS ile aynı çerçeve (genişliğin %90'ı, merkez yüksekliğin %40'ında). */
    @Test
    fun rehberCercevesiTelefon() {
        val ekran = Size(1170f, 2532f)               // 390×844 dp @3x
        val r = rehberCercevesi(ekran, ustBosluk = 228f, altBosluk = 684f, sagBosluk = 0f)
        assertEquals(1053f, r.width, 0.01f)
        assertEquals(526.5f, r.height, 0.01f)
        assertEquals(58.5f, r.left, 0.01f)
        assertEquals(2532f * 0.40f, r.center.y, 0.01f)
    }

    /** Tablet yatay (sağda deklanşör): boş alana sığar, 2:1 kalır, üst çubuk / alt panel / deklanşörle çakışmaz. */
    @Test
    fun rehberCercevesiTabletYatay() {
        val ekran = Size(2560f, 1600f)               // 1280×800 dp @2x
        val ust = 152f; val alt = 280f; val sag = 268f
        val r = rehberCercevesi(ekran, ust, alt, sag)
        assertEquals(2f, r.width / r.height, 1e-3f)
        assertTrue(r.top >= ust)
        assertTrue(r.bottom <= ekran.height - alt)
        assertTrue(r.right <= ekran.width - sag)
        assertTrue(r.left >= 0f)
        assertEquals((ekran.width - sag) / 2, r.center.x, 0.5f)
    }

    /** Kısa pencere (bölünmüş ekran): iOS çerçevesi sığmıyorsa boş alana küçülür. */
    @Test
    fun rehberCercevesiKisaPencere() {
        val ekran = Size(1170f, 1200f)
        val ust = 228f; val alt = 684f
        val r = rehberCercevesi(ekran, ust, alt, 0f)
        assertTrue(r.top >= ust)
        assertTrue(r.bottom <= ekran.height - alt)
        assertEquals(2f, r.width / r.height, 1e-3f)
    }
}
