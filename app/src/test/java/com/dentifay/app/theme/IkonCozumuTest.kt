package com.dentifay.app.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iOS'ta eşlemesi olmayan ikon adı SF Symbol olarak çizilir; Android'de karşılıksız ad gri bir NOKTA olur (kredi ipucu,
 * onay kutusu, silgi bu yüzden nokta görünüyordu). Kaynakta `DfyIcon(…)` / `DfyIconImage(…)` / `IconTile(…)` ile ya da
 * `icon =` / `ikon =` ile verilen her sabit ad web setine, ek eşlemeye ya da Material karşılığına çözülmeli.
 */
class IkonCozumuTest {

    private val kaynak = File("src/main/java")

    private val desenler = listOf(
        Regex("""\b(?:DfyIcon|DfyIconImage|IconTile)\(\s*(?:(?:name|icon)\s*=\s*)?(?:if \([^)]*\)\s*)?"([^"$]+)"(?:\s*else\s*"([^"$]+)")?"""),
        Regex("""\b(?:icon|ikon)\s*=\s*(?:\{\s*)?(?:if \([^)]*\)\s*)?"([^"$]+)"(?:\s*else\s*"([^"$]+)")?"""),
    )

    private fun cozulur(ad: String): Boolean =
        DfyIconAssets.res(ad) != null || DfyIconAssets.fallback(ad) != Icons.Filled.Circle || ad == "circle.fill"

    @Test
    fun kaynaktakiHerIkonAdiCozulur() {
        assertTrue("kaynak klasörü bulunamadı: ${kaynak.absolutePath}", kaynak.isDirectory)
        val adlar = mutableMapOf<String, MutableSet<String>>()
        kaynak.walkTopDown().filter { it.extension == "kt" && !it.name.startsWith("DfyIcon") }.forEach { f ->
            val metin = f.readText()
            desenler.forEach { d ->
                d.findAll(metin).forEach { m ->
                    m.groupValues.drop(1).filter { it.isNotEmpty() }.forEach { adlar.getOrPut(it) { mutableSetOf() } += f.name }
                }
            }
        }
        assertTrue("hiç ikon adı bulunamadı — desenler bozuk", adlar.size > 50)
        val cozulmeyen = adlar.filterKeys { !cozulur(it) }
        assertTrue("çözülmeyen ikon adları (gri nokta çizilir): $cozulmeyen", cozulmeyen.isEmpty())
    }

    @Test
    fun ekEslemelerCizilir() {
        for (ad in listOf("info.circle", "bell.badge", "square", "triangle", "equal", "eraser.fill", "banknote", "pencil")) {
            assertTrue(ad, DfyIconAssets.res(ad) != null)
        }
    }
}
