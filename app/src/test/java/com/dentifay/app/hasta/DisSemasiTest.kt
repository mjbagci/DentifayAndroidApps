package com.dentifay.app.hasta

import com.dentifay.app.hasta.DisSemasi.Gorsel
import com.dentifay.app.hasta.DisSemasi.Ton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `MobilEkleme8Tests.test2_DisSemasiAciklama` — web `buildToothAnnotations` ile aynı girdilerde aynı çıktı (odontogram §7) + konum kuralları. */
class DisSemasiTest {

    /** Örnek 2: üç kalem 36'da — rozetler KANAL, KURON (ilk kalemin adı), katmanlar sırayla, ton Yapıldı (DONE kazanır). */
    @Test
    fun ayniDisteUcKalem() {
        val a = DisSemasi.aciklamalar(
            listOf(
                TedaviKalemi(id = "k", name = "Kanal", stage = "DONE", tooth = "36", visualKey = "rootcanal"),
                TedaviKalemi(id = "z", name = "Zirkonyum Kuron", stage = "TODO", tooth = "36", visualKey = "crown"),
                TedaviKalemi(id = "t", name = "Kuron tekrar", stage = "PLANNED", toothCodes = listOf("36"), visualKey = "crown"),
            )
        )
        val d36 = a.getValue("36")
        assertEquals(listOf("KANAL", "KURON"), d36.rozetler.map { it.gorsel.rozet })
        assertEquals("Zirkonyum Kuron", d36.rozetler.last().baslik)
        assertEquals(listOf(Gorsel.rootcanal, Gorsel.crown), d36.katmanlar)
        assertEquals(3, d36.kalemler.size)
        assertEquals(Ton.yapildi, d36.ton)
    }

    /** Örnek 3: düz grup + çocuklar — grup atlanır, her diş kendi çocuğunun aşamasıyla; iç içe grupta silinmiş çocuğun dişi yok. */
    @Test
    fun gruplar() {
        val g = DisSemasi.aciklamalar(
            listOf(
                TedaviKalemi(id = "g", name = "Köprü", stage = "TODO", toothCodes = listOf("14", "15"), visualKey = "crown"),
                TedaviKalemi(id = "c1", name = "Köprü", stage = "TODO", tooth = "14", visualKey = "crown", parentItemId = "g"),
                TedaviKalemi(id = "c2", name = "Köprü", stage = "DONE", tooth = "15", visualKey = "crown", parentItemId = "g"),
            )
        )
        assertEquals(Ton.yapilacak, g["14"]?.ton)
        assertEquals(Ton.yapildi, g["15"]?.ton)
        assertEquals("grup satırı ikinci kez sayılmaz", 1, g["14"]?.kalemler?.size)

        val ic = DisSemasi.aciklamalar(
            listOf(
                TedaviKalemi(
                    id = "g", name = "Köprü", stage = "TODO", visualKey = "crown",
                    children = listOf(
                        TedaviKalemi(id = "a", name = "", stage = "TODO", tooth = "14"),
                        TedaviKalemi(id = "b", name = "", stage = "TODO", tooth = "15", deletedAt = "x"),
                        TedaviKalemi(id = "c", name = "", stage = "TODO", tooth = "16"),
                    ),
                )
            )
        )
        assertEquals(setOf("14", "16"), ic.keys)
        assertEquals(listOf(Gorsel.crown), ic["16"]?.katmanlar)
    }

    /** Örnek 4–5: yarım çene → 8 diş; alt çene → 16 diş; konumsuz, silinmiş ve tanınmayan görsel çizilmez. */
    @Test
    fun konumsuzVeTaninmayan() {
        assertEquals(
            (1..8).map { "2$it" }.toSet(),
            DisSemasi.aciklamalar(listOf(TedaviKalemi(id = "q", name = "İmplant", stage = "PLANNED", quadrant = 2, visualKey = "implant"))).keys,
        )
        assertEquals(16, DisSemasi.aciklamalar(listOf(TedaviKalemi(id = "j", name = "x", stage = "PLANNED", jawType = "lower"))).size)
        val bes = DisSemasi.aciklamalar(
            listOf(
                TedaviKalemi(id = "n", name = "Diş taşı", stage = "DONE"),
                TedaviKalemi(id = "d", name = "Çekim", stage = "PLANNED", tooth = "55", visualKey = "bridge"),
                TedaviKalemi(id = "x", name = "y", stage = "DONE", tooth = "11", deletedAt = "z"),
            )
        )
        assertEquals(listOf("55"), bes.keys.toList())
        assertEquals(emptyList<DisSemasi.Aciklama.Rozet>(), bes["55"]?.rozetler)
        assertEquals(Ton.planlandi, bes["55"]?.ton)
        assertTrue("süt dişi açıklaması → karma görünüm", DisSemasi.karmaMi(bes))
        assertFalse(DisSemasi.karmaMi(DisSemasi.aciklamalar(listOf(TedaviKalemi(id = "a", name = "a", stage = "TODO", tooth = "36")))))
        assertNull("bilinmeyen aşamada ton yok", DisSemasi.aciklamalar(listOf(TedaviKalemi(id = "a", name = "a", stage = "X", tooth = "36")))["36"]?.ton)
    }

    @Test
    fun kalemDisleri() {
        val dis = TedaviKalemi.Dis("21")
        val k = TedaviKalemi(id = "a", name = "a", stage = "TODO", tooth = "11", toothCodes = listOf(" 12 ", ""))
        assertEquals("toothCodes, tooth'tan önce (kırpılmış)", listOf("12"), DisSemasi.kalemDisleri(k))
        assertEquals("teeth[] en önce", listOf("21"), DisSemasi.kalemDisleri(k.copy(teeth = listOf(dis))))
        assertEquals(listOf("11"), DisSemasi.kalemDisleri(k.copy(toothCodes = null)))
        assertEquals(DisSemasi.kadranDisleri(4), DisSemasi.kalemDisleri(TedaviKalemi(id = "b", name = "b", stage = null, quadrant = 4)))
        assertEquals(DisSemasi.ceneDisleri("UPPER"), DisSemasi.kalemDisleri(TedaviKalemi(id = "c", name = "c", stage = null, jawType = " upper ")))
        assertEquals(emptyList<String>(), DisSemasi.kalemDisleri(TedaviKalemi(id = "d", name = "d", stage = null, quadrant = 7)))
        val grup = TedaviKalemi(
            id = "g", name = "g", stage = "TODO",
            children = listOf(
                TedaviKalemi(id = "1", name = "", stage = "TODO", tooth = "14"),
                TedaviKalemi(id = "2", name = "", stage = "DONE", tooth = "15"),
                TedaviKalemi(id = "3", name = "", stage = "TODO", tooth = "14"),
            ),
        )
        assertEquals("sırayla, tekrarsız", listOf("14", "15"), DisSemasi.kalemDisleri(grup))
        assertEquals("yalnız aşama", listOf("15"), DisSemasi.kalemDisleri(grup, yalnizAsama = "DONE"))
    }

    @Test
    fun konumEtiketi() {
        assertEquals("11, 12, 13", DisSemasi.konumEtiketi(TedaviKalemi(id = "a", name = "a", stage = null, toothCodes = listOf("11", "12", "13"))))
        assertEquals("36", DisSemasi.konumEtiketi(TedaviKalemi(id = "a", name = "a", stage = null, tooth = "36")))
        assertEquals("Sol üst yarım çene", DisSemasi.konumEtiketi(TedaviKalemi(id = "a", name = "a", stage = null, quadrant = 2)))
        assertEquals("Alt çene", DisSemasi.konumEtiketi(TedaviKalemi(id = "a", name = "a", stage = null, jawType = "lower")))
        assertEquals("", DisSemasi.konumEtiketi(TedaviKalemi(id = "a", name = "a", stage = null)))
        val grup = TedaviKalemi(
            id = "g", name = "g", stage = null,
            children = listOf(
                TedaviKalemi(id = "1", name = "", stage = null, tooth = "21"),
                TedaviKalemi(id = "2", name = "", stage = null, tooth = "22", deletedAt = "x"),
                TedaviKalemi(id = "3", name = "", stage = null, tooth = "23"),
            ),
        )
        assertEquals("canlı çocukların dişleri", "21, 23", DisSemasi.konumEtiketi(grup))
    }

    @Test
    fun fdiYardimcilari() {
        assertEquals(3, DisSemasi.kadran("36"))
        assertNull(DisSemasi.kadran("x6"))
        assertTrue(DisSemasi.sutMu("55") && DisSemasi.sutMu("85") && !DisSemasi.sutMu("48"))
        assertTrue(DisSemasi.ustMu("11") && DisSemasi.ustMu("65") && !DisSemasi.ustMu("31"))
        assertTrue(DisSemasi.solMu("21") && DisSemasi.solMu("75") && !DisSemasi.solMu("41"))
        assertEquals(6, DisSemasi.konumNo("36"))
        assertEquals(1, DisSemasi.konumNo("123"))
        assertEquals(32, (DisSemasi.daimiUst + DisSemasi.daimiAlt).toSet().size)
        assertEquals(20, (DisSemasi.sutUst + DisSemasi.sutAlt).toSet().size)
    }

    @Test
    fun etiketler() {
        assertEquals("Yapıldı", DisSemasi.asamaAdi("DONE"))
        assertEquals("", DisSemasi.asamaAdi(null))
        assertEquals("Aktif", DisSemasi.planDurumu("IN_PROGRESS"))
        assertEquals("YENI_DURUM", DisSemasi.planDurumu("YENI_DURUM"))
        assertEquals(Gorsel.sealant, Gorsel.coz("sealant"))
        assertNull(Gorsel.coz("bridge"))
        assertEquals(
            "silinmiş plan sayılmaz",
            setOf("36"),
            DisSemasi.planAciklamalari(
                listOf(
                    HastaPlani(id = "p1", items = listOf(TedaviKalemi(id = "a", name = "a", stage = "TODO", tooth = "36"))),
                    HastaPlani(id = "p2", deletedAt = "x", items = listOf(TedaviKalemi(id = "b", name = "b", stage = "TODO", tooth = "11"))),
                )
            ).keys,
        )
    }
}
