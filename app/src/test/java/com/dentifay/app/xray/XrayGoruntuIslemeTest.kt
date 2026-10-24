package com.dentifay.app.xray

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fotoğraf hattının saf (piksel dizisi üzerinde çalışan) parçaları: ekran deseni (moiré) periyodu bulucu, ton dengesi,
 * yüzdelikler, kutu ortalaması. Hat değişirse aynı girdilerle gerilemeyi yakalar.
 */
class XrayGoruntuIslemeTest {

    /** Belirlenimci gürültü (doğrusal eşlik üreteci) — testler her koşuda aynı. */
    private class Lcg(private var s: Long = 12345) {
        fun next(): Int { s = (s * 1103515245 + 12345) and 0x7fffffff; return (s % 1000).toInt() }
    }

    private fun desen(w: Int, h: Int, periyot: Double, genlik: Double, gurultu: Int = 0): IntArray {
        val r = Lcg()
        return IntArray(w * h) { i ->
            val x = i % w
            val v = 128 + genlik * sin(2 * PI * x / periyot) + (if (gurultu > 0) (r.next() % (2 * gurultu + 1)) - gurultu else 0)
            v.roundToInt().coerceIn(0, 255)
        }
    }

    /** Monitör piksel ızgarası / moiré: satır boyunca 2–6,5 px periyotlu keskin tepe bulunur. */
    @Test
    fun ekranDeseniPeriyodu() {
        val w = 512; val h = 256
        val g = desen(w, h, periyot = 3.2, genlik = 30.0, gurultu = 6)
        val p = XrayImageProcessor.dominantPeriod(g, w, h, alongRows = true)
        assertNotNull(p)
        assertEquals(3.2, p!!, 0.05)
        // Desen yalnız satır boyunca: sütun boyunca (yalnız gürültü) tepe yok.
        assertNull(XrayImageProcessor.dominantPeriod(g, w, h, alongRows = false))
    }

    /** Desensiz görüntüde (yumuşak geçiş + sensör gürültüsü) hiçbir şey yapılmaz. */
    @Test
    fun desensizGoruntudePeriyotYok() {
        val w = 512; val h = 256
        val r = Lcg(7)
        val g = IntArray(w * h) { i -> ((i % w) * 200 / w + 20 + r.next() % 9 - 4).coerceIn(0, 255) }
        assertNull(XrayImageProcessor.dominantPeriod(g, w, h, alongRows = true))
        assertNull(XrayImageProcessor.dominantPeriod(g, w, h, alongRows = false))
        // Çok küçük döşeme ölçülmez.
        assertNull(XrayImageProcessor.dominantPeriod(IntArray(100 * 100) { 128 }, 100, 100, alongRows = true))
    }

    /** Dar aralıklı (düz) görüntüye dokunulmaz. */
    @Test
    fun tonDengesiDarAraliktaDegismez() {
        val g = IntArray(1000) { 100 + it % 11 }
        val once = g.copyOf()
        XrayImageProcessor.applyTone(g)
        assertArrayEquals(once, g)
    }

    /** Ekrandan çekimde kalkık siyah / karanlık orta ton: uçlar 0–255'e gerilir, ortanca ≈ 0,38 (dijital panoramik düzeyi). */
    @Test
    fun tonDengesiGerirVeOrtancayiTasir() {
        val g = IntArray(16_000) { 40 + (it % 161) }   // 40…200 düzgün
        XrayImageProcessor.applyTone(g)
        assertEquals(0, g.minOrNull())
        assertEquals(255, g.maxOrNull())
        val ortanca = g.sortedArray()[g.size / 2]
        assertTrue("ortanca $ortanca", abs(ortanca - 97) <= 3)
    }

    @Test
    fun yuzdelikler() {
        val hist = IntArray(256)
        for (v in 0 until 100) hist[v + 50] = 1               // 50…149, 100 piksel
        val (lo, hi) = XrayImageProcessor.percentiles(hist, 100, 0.05, 0.95)
        assertEquals(55, lo)
        assertEquals(145, hi)
        val (orta, _) = XrayImageProcessor.percentiles(hist, 100, 0.5, 0.5)
        assertEquals(100, orta)
    }

    @Test
    fun kutuOrtalamasi() {
        val w = 5; val h = 5
        val g = IntArray(w * h) { if (it == 12) 90 else 0 }    // ortada tek parlak piksel
        val b = XrayImageProcessor.boxBlur(g, w, h, 1)
        assertEquals(10f, b[12], 1e-4f)                        // 3×3 pencerede 90 / 9
        assertEquals(10f, b[6], 1e-4f)
        assertEquals(0f, b[0], 1e-4f)
        // Kenarda pencere kırpılır: köşe pikseli 2×2 ortalaması.
        val k = XrayImageProcessor.boxBlur(IntArray(w * h) { if (it == 0) 40 else 0 }, w, h, 1)
        assertEquals(10f, k[0], 1e-4f)
    }
}
