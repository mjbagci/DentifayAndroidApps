package com.dentifay.app.xray

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Röntgen alanı geometrisi (alan seçimi, perspektif düzeltmesi, aday seçimi) — saf mantık. */
class XrayQuadTest {

    private fun q(vararg p: Float) = XrayQuad(Offset(p[0], p[1]), Offset(p[2], p[3]), Offset(p[4], p[5]), Offset(p[6], p[7]))

    private fun assertNear(beklenen: Offset, gercek: Offset, tol: Float = 1e-4f) {
        assertEquals(beklenen.x, gercek.x, tol)
        assertEquals(beklenen.y, gercek.y, tol)
    }

    @Test
    fun tumGorselVeGecerlilik() {
        assertTrue(XrayQuad.full.isFull)
        assertTrue(XrayQuad.full.isValid)
        assertEquals(1.0, XrayQuad.full.area, 1e-9)

        val ic = q(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        assertFalse(ic.isFull)
        assertTrue(ic.isValid)
        assertEquals(0.64, ic.area, 1e-6)

        // Köşeye 0,004'ten yakın kayma hâlâ "tüm görsel" sayılır (perspektif düzeltmesi gerekmez).
        assertTrue(XrayQuad.full.with(0, Offset(0.002f, 0.002f)).isFull)
    }

    @Test
    fun kesisenVeKucukDortgenGecersiz() {
        // Kelebek (kesişen kenarlar) perspektif düzeltmesini bozar.
        val kelebek = q(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)
        assertFalse(kelebek.isValid)
        // Görselin %2'sinden küçük alan.
        val kucuk = q(0.4f, 0.4f, 0.5f, 0.4f, 0.5f, 0.5f, 0.4f, 0.5f)
        assertEquals(0.01, kucuk.area, 1e-6)
        assertFalse(kucuk.isValid)
        // İçbükey dörtgen.
        val icbukey = q(0.1f, 0.1f, 0.9f, 0.1f, 0.5f, 0.3f, 0.1f, 0.9f)
        assertFalse(icbukey.isValid)
    }

    @Test
    fun kopyaylaKoseDegistirme() {
        val p = Offset(0.7f, 0.8f)
        val d = XrayQuad.full.with(2, p)
        assertEquals(p, d.bottomRight)
        assertEquals(p, d[2])
        assertEquals(XrayQuad.full.topLeft, d[0])
        assertEquals(XrayQuad.full.bottomLeft, d.with(3, XrayQuad.full.bottomLeft)[3])
    }

    /** Birim kareden dörtgene projektif eşleme: köşeler köşelere gider; tüm alt dörtgen dörtgenin kendisidir. */
    @Test
    fun projektifEsleme() {
        val yamuk = q(0.2f, 0.1f, 0.85f, 0.15f, 0.95f, 0.9f, 0.05f, 0.8f)
        assertNear(yamuk.topLeft, yamuk.point(0.0, 0.0))
        assertNear(yamuk.topRight, yamuk.point(1.0, 0.0))
        assertNear(yamuk.bottomRight, yamuk.point(1.0, 1.0))
        assertNear(yamuk.bottomLeft, yamuk.point(0.0, 1.0))
        val ayni = yamuk.subQuad(0.0, 0.0, 1.0, 1.0)
        for (i in 0 until 4) assertNear(yamuk[i], ayni[i])
        // Dikdörtgende eşleme doğrusaldır: merkez merkeze gider.
        val dik = q(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.6f, 0.1f, 0.6f)
        assertNear(Offset(0.5f, 0.4f), dik.point(0.5, 0.5))
        assertNear(dik.center, dik.point(0.5, 0.5))
    }

    @Test
    fun icerme() {
        val ic = q(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)
        assertTrue(XrayQuad.full.contains(ic))
        assertFalse(ic.contains(XrayQuad.full))
        // %2 pay: kenara çok yakın köşe yine içeride sayılır.
        val tasan = q(0.19f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)
        assertTrue(ic.contains(tasan))
    }

    @Test
    fun dikdortgeneBenzerlik() {
        val dikdortgen = q(0.1f, 0.2f, 0.9f, 0.22f, 0.88f, 0.62f, 0.12f, 0.6f)
        assertTrue(dikdortgen.isPlausiblyRectangular)
        // Karşılıklı kenarları çok farklı (yamuk) ya da açıları 70°–110° dışında.
        val yamuk = q(0.4f, 0.1f, 0.6f, 0.1f, 0.95f, 0.9f, 0.05f, 0.9f)
        assertFalse(yamuk.isPlausiblyRectangular)
        val egik = q(0.1f, 0.1f, 0.6f, 0.1f, 0.9f, 0.5f, 0.4f, 0.5f)
        assertFalse(egik.isPlausiblyRectangular)
    }

    @Test
    fun sinirlarVeMerkez() {
        val d = q(0.2f, 0.1f, 0.85f, 0.15f, 0.95f, 0.9f, 0.05f, 0.8f)
        val b = d.bounds
        assertEquals(0.05f, b.left, 1e-6f)
        assertEquals(0.1f, b.top, 1e-6f)
        assertEquals(0.95f, b.right, 1e-6f)
        assertEquals(0.9f, b.bottom, 1e-6f)
        assertNear(Offset((0.2f + 0.85f + 0.95f + 0.05f) / 4, (0.1f + 0.15f + 0.9f + 0.8f) / 4), d.center)
    }
}
