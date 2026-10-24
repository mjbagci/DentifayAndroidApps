package com.dentifay.app.smile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** iOS `MobilEkleme3Tests` (test1 · test5) ve `MobilEkleme4Tests` (gülüş metinleri) karşılıkları — saf mantık + kaynak taraması. */
class SmileSimulationViewModelTest {

    /** Kaynak kökü: Gradle birim testleri modül dizininde (`app/`) koşar; IDE'de proje kökünden de bulunur. */
    private fun kaynak(alt: String = ""): File? =
        listOf(File("src/main/java/$alt"), File("app/src/main/java/$alt")).firstOrNull { it.isDirectory }

    /** MobilEkleme3 · 1: bedel bilinmiyorsa (boş `tiers` / listede olmayan kademe) üretim başlatılamaz — sessiz varsayılan YOK. */
    @Test
    fun bedelBilinmiyorsaUretimYok() {
        assertTrue(SmileSimulationViewModel.bedelBilinmiyor(misafir = false, olculu = true, kredi = null))
        assertFalse(SmileSimulationViewModel.bedelBilinmiyor(misafir = false, olculu = true, kredi = 4.0))
        // Misafir denemesi ve geliştirme yolu kredi saymaz — kural yalnız krediyle çalışan yolda.
        assertFalse(SmileSimulationViewModel.bedelBilinmiyor(misafir = true, olculu = true, kredi = null))
        assertFalse(SmileSimulationViewModel.bedelBilinmiyor(misafir = false, olculu = false, kredi = null))
    }

    /** MobilEkleme3 · 5: koda yazılı kredi sayısı (`creditsPerImage`) kaynakta YOK. */
    @Test
    fun kodaYaziliKrediYok() {
        val kok = kaynak()
        assumeTrue("kaynak dizini bulunamadı", kok != null)
        val bulunan = kok!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("creditsPerImage") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), bulunan)
    }

    /** MobilEkleme4 (gülüş): "açılış kredisi" ve "ortalama"sız kesin "Bu işlem … kredi kullanır" cümlesi gülüş ekranlarında YOK. */
    @Test
    fun gulusMetinleriOrtalamaDer() {
        val dizin = kaynak("com/dentifay/app/smile")
        assumeTrue("gülüş kaynakları bulunamadı", dizin != null)
        val kesin = Regex("\"[^\"]*Bu işlem (?!ortalama)[^\"]*kredi kullanır")
        val tr = Locale.forLanguageTag("tr-TR")
        val bulunan = mutableListOf<String>()
        dizin!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val metin = f.readText()
            val kucuk = metin.lowercase(tr)
            if (kucuk.contains("açılış kredisi") || kucuk.contains("açılışta ücretsiz kredi")) bulunan += "${f.name}: açılış"
            if (kesin.containsMatchIn(metin)) bulunan += "${f.name}: kesin"
        }
        assertEquals(emptyList<String>(), bulunan)
    }
}
