package com.dentifay.app.hasta

import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.HakedisEtkisi
import com.dentifay.app.core.HakedisIptal
import com.dentifay.app.core.HakedisOnayi
import com.dentifay.app.hasta.IslemKurallari.KalemIslemi as K
import com.dentifay.app.hasta.IslemKurallari.PlanIslemi as P
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.coroutineContext

/** iOS `MobilEkleme8Tests` test3 (hakediş etkisi akışı) ve test4 (toplu indirim eşliği) + işlem pasifliği kuralları. */
class IslemKurallariTest {

    private fun kalem(json: String): TedaviKalemi = DfyJson.decodeFromString(json)
    private fun plan(json: String): HastaPlani = DfyJson.decodeFromString(json)
    private val duzenle = setOf(IslemKurallari.DUZENLEME_YETENEGI)

    /** Korumalı çağrının içinde gövdeye eklenecek onay özeti (Swift `HakedisEtkisi.onayOzeti`). */
    private suspend fun onayOzeti(): String? = coroutineContext[HakedisEtkisi.OnayOzeti]?.ozet

    private fun firlat(e: Throwable): Unit = throw e

    /**
     * 3. Hakediş etkisi akışı: etki yok → doğrudan (pencere yok); etki var → pencere, onayda AYNI istek özetle; `DEGISTI` → yeni satırlarla
     * yeniden pencere ve yeni özet; iptal → `HakedisIptal`, hiçbir şey yazılmaz; öteki 409 bu yola girmez; Yapılan'a aktarma onaysız.
     */
    @Test
    fun hakedisEtkisiAkisi() = runTest {
        val ozet1 = "a".repeat(64)
        val ozet2 = "b".repeat(64)
        fun govde(kod: String, ozet: String) =
            """{"error":"x","code":"$kod","etkiVar":true,"satirlar":[{"hekimId":"dtA","ay":"2026-09","fark":-20,"metin":"Dt. A · Eylül 2026: ₺200,00 → ₺180,00 (−₺20,00)"}],"ozet":"$ozet"}"""
                .toByteArray()
        val gerekli = HakedisEtkisi.ayristir(409, govde(HakedisEtkisi.ONAY_KODU, ozet1))
        assertNotNull(gerekli)
        gerekli!!
        assertFalse(gerekli.degisti)
        assertEquals("satır cümlesi sunucudan", "Dt. A · Eylül 2026: ₺200,00 → ₺180,00 (−₺20,00)", gerekli.etki.satirlar?.first()?.metin)
        val degisti = HakedisEtkisi.ayristir(409, govde(HakedisEtkisi.DEGISTI_KODU, ozet2))!!
        assertTrue(degisti.degisti)
        assertNull(HakedisEtkisi.ayristir(409, """{"error":"Yapılan kalem silinemez; önce geri alın.","code":"E_PLAN_EDIT_DONE_DELETE"}""".toByteArray()))
        assertNull(HakedisEtkisi.ayristir(400, govde(HakedisEtkisi.ONAY_KODU, ozet1)))

        val merkez = HakedisOnayi.shared
        // Pencereyi kullanıcı gibi yanıtlayan izleyici.
        val sorulan = mutableListOf<HakedisOnayi.Bekleyen>()
        fun CoroutineScope.yanitla(cevaplar: List<Boolean>): Job = launch {
            val kalan = cevaplar.toMutableList()
            while (kalan.isNotEmpty()) {
                merkez.bekleyen?.let { b ->
                    sorulan += b
                    merkez.yanitla(kalan.removeAt(0))
                }
                delay(5)
            }
        }

        // (a) Etki yok → tek gönderim, pencere yok (Yapılan'a aktarma da böyle: sunucu onay sormaz).
        val gonderilen = mutableListOf<String?>()
        HakedisOnayi.korumali { gonderilen += onayOzeti() }
        assertEquals(listOf<String?>(null), gonderilen)
        assertTrue(sorulan.isEmpty())
        assertEquals(
            IslemKurallari.Durum.acik,
            IslemKurallari.durum(IslemKurallari.KalemIslemi.yapilana, listOf(kalem("""{"id":"k","name":"Dolgu","stage":"TODO"}""")), duzenle),
        )

        // (b) ONAYI_GEREKLI → pencere → onay → özetle; DEGISTI → yeni pencere → yeni özetle başarı.
        gonderilen.clear()
        val izle = yanitla(listOf(true, true))
        HakedisOnayi.korumali {
            gonderilen += onayOzeti()
            if (gonderilen.size == 1) firlat(gerekli)
            if (gonderilen.size == 2) firlat(degisti)
        }
        izle.cancel()
        assertEquals("aynı istek önce özetsiz, sonra her pencerenin özetiyle", listOf(null, ozet1, ozet2), gonderilen)
        assertEquals(listOf(false, true), sorulan.map { it.degisti })
        assertNull("özet yalnız korumalı çağrının içinde geçerli", onayOzeti())

        // (c) İptal → HakedisIptal, ikinci gönderim YOK.
        gonderilen.clear()
        sorulan.clear()
        val iptal = yanitla(listOf(false))
        try {
            HakedisOnayi.korumali {
                gonderilen += onayOzeti()
                firlat(gerekli)
            }
            fail("iptal edilmeliydi")
        } catch (_: HakedisIptal) {
        }
        iptal.cancel()
        assertEquals(listOf<String?>(null), gonderilen)
        assertNull(merkez.bekleyen)
    }

    /** Korumalı gövde: özet varsa `hakedisEtkisi` eklenir, yoksa gövde aynen (Swift `HakedisEtkisi.govdeye`). */
    @Test
    fun govdeyeOzetEklenir() = runTest {
        assertNull(HakedisEtkisi.govdeye(null))
        val ozet = "c".repeat(64)
        val gerekli = HakedisEtkisi.ayristir(
            409, """{"code":"E_HAKEDIS_ETKI_ONAYI_GEREKLI","etkiVar":true,"ozet":"$ozet","satirlar":[]}""".toByteArray(),
        )!!
        var gonderilen: String? = null
        val izle = launch {
            while (true) {
                HakedisOnayi.shared.bekleyen?.let { HakedisOnayi.shared.yanitla(true) }
                delay(5)
            }
        }
        var deneme = 0
        HakedisOnayi.korumali {
            deneme += 1
            gonderilen = HakedisEtkisi.govdeye(TedaviEditoru.degisen(TedaviEditoru.Satir(kalemId = "k", ad = "x", fiyat = 1.0), 0.0, true))
                ?.get("hakedisEtkisi")?.toString()
            if (deneme == 1) firlat(gerekli)
        }
        izle.cancel()
        assertEquals("\"$ozet\"", gonderilen)
    }

    /** 4. Toplu indirim dağıtımı — web `planIndirimleri` örnekleri. */
    @Test
    fun topluIndirimEsligi() {
        fun s(fiyat: Double, yapilan: Boolean = false, dahil: Boolean = true) = IslemKurallari.Satir(fiyat = fiyat, yapilan = yapilan, dahil = dahil)
        val p = listOf(s(4333.33), s(4333.33), s(4333.34))
        val hedef = IslemKurallari.planIndirimleri(p, IslemKurallari.TopluKip.hedef, 8000.0)
        assertFalse(hedef.gecersiz)
        assertEquals(5000.0, hedef.toplu, 0.0)
        assertEquals(8000.0, hedef.net, 0.0)
        assertEquals(listOf(2666.66, 2666.67, 2666.67), hedef.satirlar.map { it.son })
        assertEquals(listOf(1666.67, 1666.66, 1666.67), hedef.satirlar.map { it.toplamIndirim })
        assertEquals(
            listOf(1666.67, 1666.66, 1666.67),
            IslemKurallari.planIndirimleri(p, IslemKurallari.TopluKip.tl, 5000.0).satirlar.map { it.toplamIndirim },
        )
        val asan = IslemKurallari.planIndirimleri(p, IslemKurallari.TopluKip.hedef, 14000.0)
        assertTrue(asan.gecersiz)
        assertEquals(0.0, asan.toplu, 0.0)
        assertEquals(13000.0, asan.net, 0.0)
        val yuzde = IslemKurallari.planIndirimleri(p, IslemKurallari.TopluKip.yuzde, 10.0)
        assertEquals(listOf(433.33, 433.33, 433.34), yuzde.satirlar.map { it.toplamIndirim })
        assertEquals(11700.0, yuzde.net, 0.0)

        // Yapılan satır pay almaz; hedef Yapılan toplamının altındaysa geçersiz.
        val y = listOf(p[0], p[1], s(4333.34, yapilan = true))
        val yh = IslemKurallari.planIndirimleri(y, IslemKurallari.TopluKip.hedef, 8000.0)
        assertEquals(listOf(2500.0, 2500.0, 0.0), yh.satirlar.map { it.toplamIndirim })
        assertEquals(8000.0, yh.net, 0.0)
        assertEquals(8666.66, yh.acikToplam, 0.0)
        assertTrue(IslemKurallari.planIndirimleri(y, IslemKurallari.TopluKip.hedef, 4000.0).gecersiz)

        // Dahil olmayan satır etkin oranla (1300/13000) kendi tutarına; net'e girmez.
        val d = IslemKurallari.planIndirimleri(p + s(1000.0, dahil = false), IslemKurallari.TopluKip.tl, 1300.0)
        assertEquals(listOf(433.33, 433.33, 433.34, 100.0), d.satirlar.map { it.toplamIndirim })
        assertEquals(11700.0, d.net, 0.0)

        // Sözleşme §14 eşlik tablosu (3 × 1.000).
        val ucBin = listOf(s(1000.0), s(1000.0), s(1000.0))
        assertEquals(listOf(966.66, 966.67, 966.67), IslemKurallari.planIndirimleri(ucBin, IslemKurallari.TopluKip.tl, 100.0).satirlar.map { it.son })
        assertTrue(IslemKurallari.planIndirimleri(ucBin, IslemKurallari.TopluKip.tl, 3500.0).gecersiz)
        assertEquals(
            listOf(900.0, 500.0),
            IslemKurallari.planIndirimleri(listOf(s(1000.0), s(500.0, yapilan = true)), IslemKurallari.TopluKip.hedef, 1400.0).satirlar.map { it.son },
        )
        assertEquals(listOf(34L, 33L, 33L), IslemKurallari.kurusDagit(100, listOf(1, 1, 1)))
    }

    /** Kalem indirimi: yüzde liste tutarına; adet ve kur ondalık çarpılır; kur ≤ 0 → 1; negatif tutar sıfırda kalır. */
    @Test
    fun satirIndirimiVeBrut() {
        assertEquals(200.0, IslemKurallari.brut(IslemKurallari.Satir(fiyat = 100.0, adet = 2)), 0.0)
        assertEquals(100.0, IslemKurallari.brut(IslemKurallari.Satir(fiyat = 100.0, adet = 0)), 0.0)
        assertEquals(3451.0, IslemKurallari.brut(IslemKurallari.Satir(fiyat = 100.0, kur = 34.51)), 0.0)
        assertEquals(100.0, IslemKurallari.brut(IslemKurallari.Satir(fiyat = 100.0, kur = -2.0)), 0.0)
        assertEquals(0.35, IslemKurallari.brut(IslemKurallari.Satir(fiyat = 0.1, adet = 3, kur = 1.1667)), 0.0)
        val y = IslemKurallari.satirIndirimi(IslemKurallari.Satir(fiyat = 1000.0, indirim = 12.5, yuzdeMi = true))
        assertEquals(IslemKurallari.SatirIndirimi(1000.0, 125.0, 875.0), y)
        val asiri = IslemKurallari.satirIndirimi(IslemKurallari.Satir(fiyat = 100.0, indirim = 150.0))
        assertEquals(0.0, asiri.hasta, 0.0)
        assertEquals("yarıda sıfırdan uzağa", 0.13, IslemKurallari.yuvarla(0.125), 0.0)
        assertEquals(-0.13, IslemKurallari.yuvarla(-0.125), 0.0)
        assertTrue(IslemKurallari.kurusDagit(0, listOf(5, 5)).all { it == 0L })
        assertEquals(listOf(0L, 0L), IslemKurallari.kurusDagit(10, listOf(0, 0)))
    }

    /** İşlem pasifliği (web `islem-pasifligi.js`): yetki · seçim · silinmiş · aynı aşama · Yapılan silinmez · kısmi · parçalama. */
    @Test
    fun kalemIslemiPasifligi() {
        val todo = kalem("""{"id":"t","name":"Dolgu","stage":"TODO","tooth":"36"}""")
        val done = kalem("""{"id":"d","name":"Kanal","stage":"DONE","tooth":"46"}""")
        val silinmis = kalem("""{"id":"s","name":"Kuron","stage":"PLANNED","tooth":"11","deletedAt":"2026-10-01T00:00:00Z"}""")
        val iki = kalem("""{"id":"i","name":"Köprü","stage":"PLANNED","toothCodes":["14","15"]}""")
        val kismi = kalem("""{"id":"k","name":"Köprü","stage":"TODO","kismi":true,"cocukKimlikleri":["a"]}""")
        val grup = kalem("""{"id":"g","name":"Köprü","stage":"PLANNED","children":[{"id":"c1","name":"Köprü","stage":"DONE","tooth":"14"}]}""")
        fun durum(i: IslemKurallari.KalemIslemi, vararg k: TedaviKalemi, caps: Set<String> = duzenle) = IslemKurallari.durum(i, k.toList(), caps)

        assertEquals(IslemKurallari.Durum.pasif(IslemKurallari.YETKI_YOK), durum(K.yapilana, todo, caps = emptySet()))
        assertEquals("Bu işlem için yetkiniz yok.", durum(K.sil, todo, caps = emptySet()).neden)
        assertEquals("Önce bir kalem seçin", durum(K.yapilacaga).neden)
        assertEquals("Seçili kalemler zaten yapılacak tedavilerde", durum(K.yapilacaga, todo).neden)
        assertTrue(durum(K.planlamaya, todo).acikMi)
        assertEquals("Seçili kalemler zaten yapılan tedavilerde", durum(K.yapilana, done).neden)
        assertTrue("Bölüm B: Yapılan'dan geri alma açık", durum(K.yapilacaga, done).acikMi)
        assertTrue("Bölüm B: Yapılan satırda indirim açık", durum(K.indirim, done).acikMi)
        assertEquals(IslemKurallari.YAPILAN_SILINMEZ, durum(K.sil, todo, done).neden)
        assertEquals(IslemKurallari.YAPILAN_SILINMEZ, durum(K.sil, grup).neden)
        assertEquals("Silinmiş kalem — önce geri yükleyin", durum(K.indirim, silinmis).neden)
        assertTrue(durum(K.geriYukle, silinmis).acikMi)
        assertEquals("Önce bir kalem seçin", durum(K.geriYukle, todo).neden)
        assertEquals("Önce bir kalem seçin", durum(K.doviz, todo, done).neden)
        assertTrue("silme çoklu seçimde açık", durum(K.sil, todo, iki).acikMi)
        assertTrue(durum(K.indirim, kismi).neden!!.startsWith("Bu kalemin dişleri farklı aşamalarda"))
        assertTrue("iki dişli düz kalem parçalanır", durum(K.parcala, iki).acikMi)
        assertEquals("tek dişli kalem parçalanmaz", "Önce bir kalem seçin", durum(K.parcala, todo).neden)
        assertEquals("grup satırı parçalanmaz", "Önce bir kalem seçin", durum(K.parcala, grup).neden)
        assertEquals(listOf("i", "a"), IslemKurallari.aktarmaKimlikleri(listOf(iki, kismi)))
    }

    @Test
    fun planIslemiPasifligi() {
        val p = plan("""{"id":"p","status":"IN_PROGRESS","items":[{"id":"a","name":"x","stage":"PLANNED"},{"id":"b","name":"y","stage":"TODO","deletedAt":"z"}]}""")
        val aktarilmis = plan("""{"id":"p","status":"IN_PROGRESS","items":[{"id":"g","name":"x","stage":"PLANNED","children":[{"id":"c","name":"x","stage":"TODO","tooth":"11"}]}]}""")
        val kapali = plan("""{"id":"p","status":"COMPLETED","items":[]}""")
        assertTrue("paylaşım yetkisiz de açık", IslemKurallari.planDurumu(P.paylas, p, emptySet()).acikMi)
        assertEquals(IslemKurallari.YETKI_YOK, IslemKurallari.planDurumu(P.duzenle, p, emptySet()).neden)
        assertEquals("Plan kapalı", IslemKurallari.planDurumu(P.duzenle, kapali, duzenle).neden)
        assertEquals("silinmiş TODO sayılmaz", "Aktarılmış kalem yok — bütün kalemler zaten planlamada", IslemKurallari.planDurumu(P.sifirla, p, duzenle).neden)
        assertTrue(IslemKurallari.planDurumu(P.sifirla, aktarilmis, duzenle).acikMi)
        assertTrue(IslemKurallari.planDurumu(P.sil, kapali, duzenle).acikMi)
    }

    /** Yapılan'a aktar — hekim: birden çok hekimli satır boş; tek seçici ≤ 1 ayrı değer; boş seçimde gövde yok. */
    @Test
    fun yapilanaAktarHekimi() {
        val a = kalem("""{"id":"a","name":"x","stage":"TODO","assignedDoctorId":"dtA"}""")
        val b = kalem("""{"id":"b","name":"y","stage":"TODO"}""")
        val cok = kalem("""{"id":"c","name":"z","stage":"TODO","hekimler":["dtA","dtB"],"assignedDoctorId":"dtA"}""")
        assertEquals("dtA", IslemKurallari.kalemHekimi(a, "dtP"))
        assertEquals("dtP", IslemKurallari.kalemHekimi(b, "dtP"))
        assertEquals("", IslemKurallari.kalemHekimi(cok, "dtP"))

        val tek = IslemKurallari.baslangicSecimi(listOf(a, b)) { "dtA" }
        assertTrue(tek.tek)
        assertEquals(IslemKurallari.AktarGovdesi("dtA", null), IslemKurallari.aktarGovdesi(tek.tek, tek.secimler))
        val ayri = IslemKurallari.baslangicSecimi(listOf(a, b)) { "dtP" }
        assertFalse(ayri.tek)
        assertEquals(mapOf("a" to "dtA", "b" to "dtP"), IslemKurallari.aktarGovdesi(ayri.tek, ayri.secimler)?.hekimler)
        assertNull(IslemKurallari.aktarGovdesi(false, mapOf("a" to "dtA", "c" to "")))
        assertNull(IslemKurallari.aktarGovdesi(true, mapOf("c" to "")))
    }

    @Test
    fun paylasimMetni() {
        val p = plan(
            """{"id":"p","title":"Plan 1","plannedTotal":"7400.00","items":[
                {"id":"a","name":"Dolgu","stage":"PLANNED","tooth":"36","totalAmountTRY":"1800.00"},
                {"id":"b","name":"Köprü","stage":"PLANNED","teeth":[{"toothCode":"14"},{"toothCode":"15"}],"grupToplami":5600},
                {"id":"c","name":"Köprü","stage":"PLANNED","tooth":"14","parentItemId":"b"}]}""",
        )
        assertEquals(
            "*Plan 1*\nAyşe Yılmaz\n\n*Tedaviler:*\n• 36 · Dolgu — ₺1.800,00\n• 14,15 · Köprü — ₺5.600,00\n\n*Planlanan Toplam:* ₺7.400,00",
            IslemKurallari.paylasimMetni(p, "Ayşe Yılmaz"),
        )
        assertEquals("*Tedavi Planı*\nA\n\n\n*Planlanan Toplam:* —", IslemKurallari.paylasimMetni(plan("""{"id":"x","title":""}"""), "A"))
    }
}
