package com.dentifay.app.login

import com.dentifay.app.core.SifreKurali
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-E8.6 — şifre ekranının saf kuralları (iOS `SifreBelirlemeView.mevcutGerekli` · `SifreKurali`). */
class SifreKurallariTest {

    /** "Mevcut şifre" yalnız geçici şifreyle açılmış ZORUNLU oturumda istenmez; Ayarlar'dan değiştirmede hep istenir. */
    @Test
    fun mevcutSifreKurali() {
        assertFalse(mevcutSifreGerekli(zorunlu = true, geciciSifreyle = true))
        assertTrue(mevcutSifreGerekli(zorunlu = true, geciciSifreyle = false))
        assertTrue("alan yoksa istenir", mevcutSifreGerekli(zorunlu = true, geciciSifreyle = null))
        assertTrue(mevcutSifreGerekli(zorunlu = false, geciciSifreyle = true))
        assertTrue(mevcutSifreGerekli(zorunlu = false, geciciSifreyle = null))
    }

    /** İstemcide tek kural: iki alan aynı olmalı (uzunluk/ortak şifre kuralları sunucunun cümlesiyle). */
    @Test
    fun tekrarUyusmali() {
        assertNull(SifreKurali.denetle("Yeni-Sifre1", "Yeni-Sifre1"))
        assertEquals("Şifreler aynı değil.", SifreKurali.denetle("Yeni-Sifre1", "Yeni-Sifre2"))
    }
}
