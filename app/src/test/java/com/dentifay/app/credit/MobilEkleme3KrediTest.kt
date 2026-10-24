package com.dentifay.app.credit

import com.dentifay.app.core.DfyJson
import com.dentifay.app.smile.SmileAIModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * iOS `MobilEkleme3Tests` (M-E3.4 mühürleri) — kredi paketine ait kısımlar: gülüş bedeli YALNIZ sunucunun `tiers`ından
 * (sessiz varsayılan yok), kart · "harcanacak" · ipucu AYNI sayı, 409 sonrası güncel bedel, kodda kredi sayısı yok.
 * (`SmileSimulationViewModel.bedelBilinmiyor` ve `KieImageEditClient` hata eşlemesi `smile` paketinin testlerindedir.)
 */
class MobilEkleme3KrediTest {

    /** Örnek cüzdan — `tiers` istenen listeyle değiştirilir. */
    private fun cuzdan(tiers: String): SmileWallet = DfyJson.decodeFromString(
        SmileWallet.serializer(),
        """
        {"availableCredits":52,"freeGrantCredits":30,"freeUsedCredits":18,"freeRemainingCredits":12,"balanceCredits":40,
         "tiers":$tiers,"packs":[],"canPurchase":true}
        """.trimIndent(),
    )

    /** 1. `tiers` boşken (ya da kademe listede yokken) sayı gösterilmez ve kademe seçilemez — sessiz varsayılan YOK. */
    @Test
    fun test1_TiersBosKademeSecilemez() {
        val bos = cuzdan("[]")
        for (m in SmileAIModel.selectable) assertNull("$m", bos.price(m))
        assertEquals("—", GulusBedeli.kart(bos.price(SmileAIModel.nanoBanana)))
        assertNull(GulusBedeli.harcanacak(null))
        assertNull(GulusBedeli.ipucu(null))

        val eksik = cuzdan("""[{"key":"orta","label":"Orta","credits":5}]""")
        assertEquals(5.0, eksik.price(SmileAIModel.nanoBanana)!!, 0.0)
        assertNull("listede olmayan kademe", eksik.price(SmileAIModel.nanoBananaPro))
        assertNull("kademesi olmayan model", eksik.price(SmileAIModel.qwenMaskFast))

        // Sözleşme §10 (38D): kur/fiyat yoksa `tiers[].credits: null` — cüzdan yine çözülür, kademe "—".
        val kurYok = cuzdan("""[{"key":"orta","label":"Orta","credits":null},{"key":"iyi","label":"İyi","credits":12}]""")
        assertNull(kurYok.price(SmileAIModel.nanoBanana))
        assertEquals("—", GulusBedeli.kart(kurYok.price(SmileAIModel.nanoBanana)))
        assertEquals(12.0, kurYok.price(SmileAIModel.nanoBanana2)!!, 0.0)
    }

    /** 2. Kart, "harcanacak" yazısı ve kredi ipucu AYNI değeri (`tiers[].credits`) gösterir. */
    @Test
    fun test2_KartHarcanacakIpucuAyni() {
        val w = cuzdan("""[{"key":"iyi","label":"İyi","credits":13.5}]""")
        val kredi = w.price(SmileAIModel.nanoBanana2)
        assertEquals("ortalama 13,5 kredi", GulusBedeli.kart(kredi))
        assertEquals("ortalama 13,5 kredi harcanacak", GulusBedeli.harcanacak(kredi))
        assertEquals("Bu işlem ortalama 13,5 kredi kullanır.", GulusBedeli.ipucu(kredi))
        // Tarife durumu yalnız ücretsiz/başlayacak bilgisini verir; sayı kartla aynı kalır (tarifede başka sayı olsa da).
        val tarife = KrediTarifesi(
            tarife = listOf(KrediTarifesi.Satir("before_after", "iyi", 99.0, 99.0, false)),
            durum = mapOf("before_after" to KrediTarifesi.Durum("UCRETLI", null)),
        )
        assertEquals("Bu işlem ortalama 13,5 kredi kullanır.", tarife.ipucu("before_after:iyi", kredi))
        val ucretsiz = KrediTarifesi(tarife = emptyList(), durum = mapOf("before_after" to KrediTarifesi.Durum("UCRETSIZ", null)))
        assertNull(ucretsiz.ipucu("before_after:iyi", kredi))
        val baslayacak = KrediTarifesi(tarife = emptyList(), durum = mapOf("before_after" to KrediTarifesi.Durum("BASLAYACAK", "2026-11-25")))
        assertEquals("25.11.2026 tarihinden itibaren kredi kullanacak; şu an ücretsiz.", baslayacak.ipucu("before_after:iyi", kredi))
    }

    /** 3. 409 `E_CREDIT_PRICE_CHANGED`: kart sunucunun güncel bedeliyle güncellenir (yalnız o kademe). */
    @Test
    fun test3_BedelDegistiKartGuncellenir() {
        val w = cuzdan("""[{"key":"orta","label":"Orta","credits":4},{"key":"iyi","label":"İyi","credits":12}]""")
        val yeni = w.bedelGuncellendi("orta", 15.0)
        assertEquals(15.0, yeni.price(SmileAIModel.nanoBanana)!!, 0.0)
        assertEquals(12.0, yeni.price(SmileAIModel.nanoBanana2)!!, 0.0)
        assertEquals("ilk cüzdan değişmez", 4.0, w.price(SmileAIModel.nanoBanana)!!, 0.0)
    }

    /** 5. `creditsPerImage` kod tabanında YOK (koda yazılı kredi sayısı). */
    @Test
    fun test5_KodaYaziliKrediYok() {
        val kok = listOf("src/main/java/com/dentifay/app", "app/src/main/java/com/dentifay/app").map(::File).firstOrNull { it.isDirectory }
        assumeTrue("kaynak dizini bulunamadı (çalışma dizini modül değil)", kok != null)
        val bulunan = kok!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("creditsPerImage") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), bulunan)
    }
}
