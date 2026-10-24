package com.dentifay.app.hasta

import com.dentifay.app.core.ApiException
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.EsnekSayi
import com.dentifay.app.core.Yetki
import com.dentifay.app.hasta.HastaSayfasiModeli.Bolum
import com.dentifay.app.hasta.HastaSayfasiModeli.Durum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hasta sayfasının saf kuralları: hoşgörülü kalem çözümü (Swift `TedaviKalemi.init(from:)`), bölümlerin tembel yüklenmesi ve
 * kodsuz 403'ün boş parça olması (M-E7.5), medya sayfalama birleşimi, aşama gruplaması, şema ölçüleri ve SVG yol ayrıştırması.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HastaSayfasiTest {

    // MARK: Çözme

    @Test
    fun kalemHosgoruluCozulur() {
        val json = """
            {"id":"k1","name":"Zirkonyum Kuron","stage":"PLANNED","price":"6000.00","quantity":2,"discount":600,
             "totalAmountTRY":"11400.5","quadrant":"2","teeth":[{"toothCode":"11"},{"toothCode":"12"}],
             "assignedDoctor":{"id":"d1","name":"Selin Demir"},"catalog":{"visualKey":"crown"},
             "plan":{"id":"p1","title":"Plan 1","status":"IN_PROGRESS"},"kismi":true,"toplamDis":3,"cocukKimlikleri":["a","b"],
             "children":[{"id":"c1","tooth":"11","stage":"PLANNED"},{"id":"c2","tooth":"12","deletedAt":"2026-10-01T00:00:00Z"}],
             "bilinmeyen":{"x":1}}
        """.trimIndent()
        val k = DfyJson.decodeFromString(TedaviKalemiSerializer, json)
        assertEquals(6000.0, k.price?.deger ?: 0.0, 0.0)
        assertEquals(2, k.quantity)
        assertEquals(600.0, k.discount?.deger ?: 0.0, 0.0)
        assertEquals(11400.5, k.totalAmountTRY?.deger ?: 0.0, 0.0)
        assertEquals("quadrant dize olabilir", 2, k.quadrant)
        assertEquals(listOf("11", "12"), k.teeth?.map { it.toothCode })
        assertEquals("Selin Demir", k.assignedDoctor?.name)
        assertEquals("crown", k.catalog?.visualKey)
        assertEquals("Plan 1", k.plan?.title)
        assertEquals(true, k.kismi)
        assertEquals(listOf("a", "b"), k.cocukKimlikleri)
        assertEquals(2, k.children?.size)
        assertEquals("ad yoksa boş", "", k.children?.first()?.name)
        assertEquals(listOf("c1"), k.canliCocuklar.map { it.id })
        assertTrue(k.grupMu)
    }

    @Test
    fun yanlisTurdekiAlanKalemiDusurmez() {
        val json = """
            {"id":"k2","name":5,"price":true,"quantity":"x","quadrant":"iki","toothCodes":["11",12],
             "assignedDoctor":{"id":"d1","name":7},"catalog":"crown","children":[{"id":"c1"},{"tooth":"12"}],"kismi":"evet"}
        """.trimIndent()
        val k = DfyJson.decodeFromString(TedaviKalemiSerializer, json)
        assertEquals("k2", k.id)
        assertEquals("", k.name)
        assertNull(k.price)
        assertNull(k.quantity)
        assertNull(k.quadrant)
        assertNull("dizi bütün ya da hiç", k.toothCodes)
        assertNull(k.assignedDoctor)
        assertNull(k.catalog)
        assertNull("kimliksiz çocuk → dizi düşer", k.children)
        assertNull(k.kismi)
        try {
            DfyJson.decodeFromString(TedaviKalemiSerializer, """{"name":"kimliksiz"}""")
            fail("id zorunlu")
        } catch (e: Exception) {
            // beklenen
        }
    }

    @Test
    fun planListesiVeMedyaCozulur() {
        val planlar = DfyJson.decodeFromString(
            PlanListesiYaniti.serializer(),
            """{"plans":[{"id":"p1","title":"Plan 1","plannedTotal":"45200.00","completedTotal":1800,"doctor":{"id":"d","name":"Selin Demir"},
               "items":[{"id":"k1","name":"Dolgu","tooth":"36","catalog":{"visualKey":"filling"}}],"onamEtiketi":"Bekliyor"}],
               "sonrakiPlanAdi":"Plan 2","pagination":{"page":1}}""",
        )
        assertEquals("Plan 2", planlar.sonrakiPlanAdi)
        val p = planlar.plans.single()
        assertEquals(45200.0, p.plannedTotal?.deger ?: 0.0, 0.0)
        assertEquals(1800.0, p.completedTotal?.deger ?: 0.0, 0.0)
        assertEquals("filling", p.items?.single()?.catalog?.visualKey)

        val medya = DfyJson.decodeFromString(
            MedyaYaniti.serializer(),
            """{"media":[{"id":"m1","category":"XRAY_PANORAMIC","fileUrl":"/uploads/a.jpg","mimeType":"image/jpeg","isFavorite":true},
               {"id":"m2","fileUrl":"/uploads/b.pdf","mimeType":"application/pdf"}],
               "counts":{"XRAY_PANORAMIC":1,"DOCUMENT_OTHER":1},"can":{"trash":true},"pagination":{"nextCursor":"c1"}}""",
        )
        assertEquals("c1", medya.pagination?.nextCursor)
        assertEquals(true, medya.can?.trash)
        assertTrue(medya.media[0].resimMi)
        assertFalse(medya.media[1].resimMi)
        assertEquals("Panoramik", MedyaKategorisi.ad(medya.media[0].category))
        assertEquals("Diğer", MedyaKategorisi.ad(null))
    }

    // MARK: Model

    private class SahteKaynak : HastaSayfasiModeli.Kaynak {
        var planCagrisi = 0
        var ayrintiCagrisi = 0
        var medyaCagrilari = mutableListOf<String?>()
        var asamaHatasi: Exception? = null
        var planlar = PlanListesiYaniti(
            listOf(HastaPlani(id = "p1", title = "Plan 1"), HastaPlani(id = "p2", deletedAt = "2026-10-01")),
            sonrakiPlanAdi = "Plan 3",
        )

        override suspend fun planlar(hastaId: String): PlanListesiYaniti {
            planCagrisi++
            return planlar
        }

        override suspend fun ayrinti(planId: String): HastaPlani {
            ayrintiCagrisi++
            return HastaPlani(id = planId, title = "Plan 1", onamEtiketi = "Var", items = listOf(TedaviKalemi(id = "k", name = "Dolgu", stage = "TODO", tooth = "36")))
        }

        override suspend fun asama(hastaId: String, asama: String): List<TedaviKalemi> {
            asamaHatasi?.let { throw it }
            return listOf(TedaviKalemi(id = "t1", name = "Kanal", stage = asama, tooth = "46"))
        }

        override suspend fun medya(hastaId: String, imlec: String?): MedyaYaniti {
            medyaCagrilari += imlec
            return if (imlec == null) {
                MedyaYaniti(media = listOf(medya("m1"), medya("m2")), counts = mapOf("XRAY_PANORAMIC" to 3), pagination = MedyaYaniti.Sayfa("c1"))
            } else {
                MedyaYaniti(media = listOf(medya("m2"), medya("m3")), pagination = MedyaYaniti.Sayfa(null))
            }
        }

        private fun medya(id: String) = HastaMedyasi(id = id, category = "XRAY_PANORAMIC", fileUrl = "/uploads/$id.jpg", mimeType = "image/jpeg")
    }

    private val hasta = HastaBasligi(id = "h1", ad = "Deneme Hasta")

    /** Modelin görevleri testin zamanlayıcısında (ana iş parçacığı yerine) — `advanceUntilIdle` hepsini çalıştırır. */
    private fun TestScope.model(k: SahteKaynak) =
        HastaSayfasiModeli(hasta, k, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    @Test
    fun planlamaTembelVeOnbellekli() = runTest {
        val k = SahteKaynak()
        val m = model(k)
        assertEquals(Durum.bos, m.planlar)
        m.bolumuYukle(Bolum.planlama)
        advanceUntilIdle()
        val planlar = (m.planlar as Durum.hazir).deger
        assertEquals("silinmiş plan listelenmez", listOf("p1"), planlar.map { it.id })
        assertEquals("Plan 3", m.sonrakiPlanAdi)
        assertEquals("liste özeti ayrıntı yerine geçer", "Plan 1", m.ayrintilar["p1"]?.title)

        m.bolumuYukle(Bolum.planlama)
        advanceUntilIdle()
        assertEquals("hazır bölüm yeniden istenmez", 1, k.planCagrisi)

        m.kartiDegistir("p1")
        advanceUntilIdle()
        assertTrue("p1" in m.acikPlanlar)
        assertEquals("Var", m.ayrintilar["p1"]?.onamEtiketi)
        m.kartiDegistir("p1")
        m.kartiDegistir("p1")
        advanceUntilIdle()
        assertEquals("tam ayrıntı bir kez okunur", 1, k.ayrintiCagrisi)
        assertTrue(m.ayrintiYukleniyor.isEmpty())

        m.tazele()
        advanceUntilIdle()
        assertEquals(2, k.planCagrisi)
        assertEquals("tazelemede açık kartın ayrıntısı yeniden okunur", 2, k.ayrintiCagrisi)
    }

    @Test
    fun yetkiReddiParcayiBosBirakir() = runTest {
        val k = SahteKaynak().apply { asamaHatasi = ApiException.ServerError(403, Yetki.EYLEM_YETKISIZ_METNI) }
        val m = model(k)
        m.bolumuYukle(Bolum.yapilacak)
        advanceUntilIdle()
        assertEquals(Durum.hazir(emptyList<TedaviKalemi>()), m.asamaDurumu("TODO"))

        k.asamaHatasi = ApiException.ServerError(500, "Sunucu hatası oluştu.")
        m.bolumuYukle(Bolum.yapilan)
        advanceUntilIdle()
        assertEquals(Durum.hata("Sunucu hatası oluştu."), m.asamaDurumu("DONE"))

        k.asamaHatasi = null
        m.bolumuYukle(Bolum.yapilan, zorla = true)
        advanceUntilIdle()
        assertEquals(listOf("t1"), (m.asamaDurumu("DONE") as Durum.hazir).deger.map { it.id })
    }

    @Test
    fun medyaSayfalari() = runTest {
        val k = SahteKaynak()
        val m = model(k)
        m.bolumuYukle(Bolum.medya)
        advanceUntilIdle()
        m.medyaDevam()
        advanceUntilIdle()
        val y = (m.medya as Durum.hazir).deger
        assertEquals("tekrar eden görsel bir kez", listOf("m1", "m2", "m3"), y.media.map { it.id })
        assertNull("son sayfa", y.pagination?.nextCursor)
        assertEquals("sayılar ilk sayfanın", 3, y.counts?.get("XRAY_PANORAMIC"))
        assertFalse(m.medyaDahaYukleniyor)
        m.medyaDevam()
        advanceUntilIdle()
        assertEquals("imleç yoksa istek yok", listOf<String?>(null, "c1"), k.medyaCagrilari)
    }

    @Test
    fun medyaBirlestirVeSuzgec() {
        fun g(id: String, kat: String) = HastaMedyasi(id = id, category = kat, fileUrl = "/$id")
        val eski = MedyaYaniti(listOf(g("a", "XRAY_CBCT"), g("b", "PHOTO_SMILE")), counts = mapOf("XRAY_CBCT" to 1), pagination = MedyaYaniti.Sayfa("c"))
        val yeni = MedyaYaniti(listOf(g("b", "PHOTO_SMILE"), g("c", "DOCUMENT_REPORT"), g("c", "DOCUMENT_REPORT")), pagination = MedyaYaniti.Sayfa("d"))
        val b = HastaSayfasiModeli.medyaBirlestir(eski, yeni)
        assertEquals(listOf("a", "b", "c"), b.media.map { it.id })
        assertEquals("d", b.pagination?.nextCursor)
        assertEquals(eski.counts, b.counts)
        assertEquals(listOf("a"), MedyaDurumu.suz(b.media, "Röntgenler").map { it.id })
        assertEquals(listOf("b"), MedyaDurumu.suz(b.media, "Fotoğraflar").map { it.id })
        assertEquals(3, MedyaDurumu.suz(b.media, null).size)
    }

    // MARK: Aşama gruplaması

    @Test
    fun asamaGruplari() {
        fun k(id: String, plan: String?, baslik: String? = "Plan $plan") = TedaviKalemi(id = id, name = id, stage = "TODO")
            .copy(plan = plan?.let { TedaviKalemi.PlanOzeti(it, baslik, "IN_PROGRESS") })
        val g = AsamaBolumu.grupla(listOf(k("1", "p2"), k("2", null), k("3", "p1"), k("4", "p2"), k("5", "p3", baslik = null)))
        assertEquals("ilk görülme sırası", listOf("p2", "_plansiz", "p1", "p3"), g.map { it.anahtar })
        assertEquals(listOf("1", "4"), g[0].kalemler.map { it.id })
        assertEquals("Plansız Kalemler", g[1].baslik)
        assertNull(g[1].durum)
        assertEquals("başlıksız plan da \"Plansız Kalemler\" adını alır (Swift ile aynı)", "Plansız Kalemler", g[3].baslik)
    }

    // MARK: Şema ölçüleri ve yollar

    @Test
    fun disOlculeri() {
        assertEquals(35f, DisOlcu.genislik("11", 100f))
        assertEquals("süt üst 2. azı: taban 54", 72f, DisOlcu.genislik("55", 80f))
        assertEquals("süt alt kesici: 38 − 8", 40f, DisOlcu.genislik("71", 80f))
        assertEquals(100f, DisOlcu.yukseklik(900f))
        assertEquals(43f, DisOlcu.yukseklik(342f))
        assertEquals(30f, DisOlcu.yukseklik(100f))
        assertEquals(8f, DisOlcu.bosluk(100f))
        assertEquals(2f, DisOlcu.bosluk(10f))
        assertEquals(136f, DisOlcu.toplamYukseklik(karma = false, sigacakGenislik = 342f))
        assertEquals("yarıda yukarı (Swift rounded)", 3f, DisOlcu.yuvarla(2.5f))
    }

    @Test
    fun svgYoluAyristirilir() {
        val k = SVGYolu.komutlar("M 20 60 C 17 44 14 22 19 9 Q 22 113 30 114 Z")
        assertEquals(
            listOf(SVGYolu.Komut.M(20f, 60f), SVGYolu.Komut.C(17f, 44f, 14f, 22f, 19f, 9f), SVGYolu.Komut.Q(22f, 113f, 30f, 114f), SVGYolu.Komut.Z),
            k,
        )
        assertEquals(
            "M'den sonra örtük L; eksi sayılar",
            listOf(SVGYolu.Komut.M(1f, 2f), SVGYolu.Komut.L(3f, -4f), SVGYolu.Komut.L(5f, 6f)),
            SVGYolu.komutlar("M 1,2 3 -4 L 5 6"),
        )
        // Bütün web şekilleri ayrışır ve her kök/kuron kapanır.
        for (s in DisSekilleri.ust.values + DisSekilleri.alt.values) {
            for (d in s.kokler + s.kuron) assertEquals(SVGYolu.Komut.Z, SVGYolu.komutlar(d).last())
        }
        assertEquals("süt 1. azı ikinci büyük azı şeklini kullanır", DisSekilleri.ust[7], DisSekilleri.sekil("54"))
        assertEquals(DisSekilleri.alt[6], DisSekilleri.sekil("85"))
        assertNull("6. konumun süt dişi şekli yok (süt dişleri 1–5)", DisSekilleri.sekil("16"))
    }

    @Test
    fun ornekVeriTutarli() {
        val p = OrnekHastaSayfasi.ayrinti("p1")
        val a = DisSemasi.aciklamalar(p.items ?: emptyList())
        assertEquals(setOf("36", "46", "11", "12", "21", "26", "48"), a.keys)
        assertEquals(DisSemasi.Ton.yapilacak, a["48"]?.ton)
        assertEquals(100, OrnekHastaSayfasi.buyukPlan.items?.size)
        assertEquals(200, OrnekHastaSayfasi.medya200.media.map { it.id }.toSet().size)
        assertEquals(EsnekSayi(45200.0), p.plannedTotal)
        val b = OrnekHastaTedavi.bugun
        assertEquals(listOf("p1", "p2"), b.benim.map { it.hastaId })
        assertTrue("iptal edilen sayılmaz", b.diger.none { it.hastaAdi == "İptal Eden" })
    }
}
