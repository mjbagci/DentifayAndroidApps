package com.dentifay.app.credit

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** iOS `MobilEkleme4Tests` (M-E4.4): açılış kredisi metni yok, kredi açıklamaları "ortalama" der. */
class MobilEkleme4KrediTest {

    @Test
    fun test1_AcilisKredisiYokVeOrtalama() {
        // Davranış: ipucu, kademe etiketi, tarife listesi ve hareket satırı.
        assertEquals("Bu işlem ortalama 4 kredi kullanır.", GulusBedeli.ipucu(4.0))
        assertEquals("ortalama 4 kredi", GulusBedeli.kart(4.0))
        val tarife = KrediTarifesi(
            tarife = listOf(KrediTarifesi.Satir("xray", "analyze", 4.0, 4.0, false)),
            durum = emptyMap(),
        )
        assertEquals("Bu işlem ortalama 4 kredi kullanır.", tarife.ipucu("xray:analyze"))
        assertEquals("Ortalama 4 kredi", tarife.listeMetni(tarife.tarife[0]))
        assertEquals("Dentifay'ın tanımladığı kredi", CreditLabels.operation("platform_grant", "opening"))

        // Kaynak: kullanıcı metninde "açılış kredisi" / "açılışta ücretsiz kredi" ve "ortalama"sız "… kredi kullanır" YOK.
        val kok = listOf("src/main/java/com/dentifay/app", "app/src/main/java/com/dentifay/app").map(::File).firstOrNull { it.isDirectory }
        assumeTrue("kaynak dizini bulunamadı (çalışma dizini modül değil)", kok != null)
        val kesin = Regex("\"[^\"]*Bu işlem (?!ortalama)[^\"]*kredi kullanır")
        val tr = Locale.forLanguageTag("tr-TR")
        val bulunan = mutableListOf<String>()
        kok!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}trial${File.separator}") }
            .forEach { f ->
                val metin = f.readText()
                val kucuk = metin.lowercase(tr)
                if (kucuk.contains("açılış kredisi") || kucuk.contains("açılışta ücretsiz kredi")) bulunan += "${f.name}: açılış"
                if (kesin.containsMatchIn(metin)) bulunan += "${f.name}: kesin"
            }
        assertEquals(emptyList<String>(), bulunan)
    }
}
