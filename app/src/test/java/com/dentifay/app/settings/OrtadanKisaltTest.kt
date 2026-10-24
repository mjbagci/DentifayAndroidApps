package com.dentifay.app.settings

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ayarlar profil kartındaki e-posta: SwiftUI `.truncationMode(.middle)` karşılığı — sığmıyorsa ORTADAN kısaltılır. */
class OrtadanKisaltTest {

    /** Sahte ölçü: her karakter 10 px. */
    private val olc: (String) -> Int = { it.length * 10 }

    @Test
    fun siganMetinAynen() {
        assertEquals("ayse@klinik.com", ortadanKisalt("ayse@klinik.com", 150, olc))
        assertEquals("ayse@klinik.com", ortadanKisalt("ayse@klinik.com", Constraints.Infinity, olc))
    }

    @Test
    fun sigmayanOrtadanKisalir() {
        val metin = "ayse.demir.uzun.adres@ornek-klinik.com.tr"
        val sonuc = ortadanKisalt(metin, 200, olc)
        assertTrue(sonuc, sonuc.length * 10 <= 200)
        assertEquals("en uzun sığan biçim", 20, sonuc.length)
        assertTrue(sonuc.startsWith("ayse.demi"))
        assertTrue(sonuc.endsWith("com.tr"))
        assertTrue(sonuc.contains("…"))
        // Hiçbir şey sığmıyorsa yalnız üç nokta.
        assertEquals("…", ortadanKisalt(metin, 5, olc))
    }
}
