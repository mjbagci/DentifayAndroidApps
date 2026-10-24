package com.dentifay.app.xray

import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.Yetki
import com.dentifay.app.core.hataCumlesi
import com.dentifay.app.core.obj
import com.dentifay.app.core.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Röntgen klinik istemcisinin hata kuralları — iOS `MobilEkleme7Tests.test2_YetkisizEylemVeKirmiziKartYok`
 * (`XrayClinicClient.ClinicError.forbidden(Yetki.yasakMetni(kodsuz))` yetki reddi sayılır) ve `MobilEkleme3Tests.test7`
 * (kodsuz 403 "Bu işlem için yetkiniz yok.", iş kodlu 403 sunucunun cümlesi) + istemcinin durum eşlemesi (sözleşme §10:
 * 402 kredi, demoda 403 `DEMO_CREDITS_EXHAUSTED`, kodsuz 403 yetki).
 */
class XrayKlinikIstemcisiTest {

    private fun b(s: String) = s.toByteArray()
    private val yol = "/api/patients/p1/xray"
    private val kodsuz = b("""{"error":"Bu işlem için yetkiniz yok","capability":"ai.xray","role":"BANKO"}""")
    private val kodlu = b("""{"error":"Kendi limitinizi değiştiremezsiniz.","code":"E_CREDIT_LIMIT_SELF"}""")

    private fun hata(status: Int, govde: ByteArray): Throwable =
        runCatching { XrayClinicClient.yanit(status, govde, yol) }.exceptionOrNull() ?: error("hata bekleniyordu ($status)")

    /** Kodsuz 403 → "Bu işlem için yetkiniz yok." ve yetki reddi (parça kırmızı kart çizmez). */
    @Test
    fun kodsuz403YetkiReddiSayilir() {
        val metin = hataCumlesi(403, kodsuz)
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, metin)
        val e = XrayClinicClient.ClinicError.forbidden(Yetki.yasakMetni(kodsuz))
        assertTrue(Yetki.reddiMi(e))
        assertEquals("Bu işlem için yetkiniz yok.", e.message)
    }

    /** İş kodlu 403 yetki reddi SAYILMAZ; sunucunun cümlesi aynen gösterilir. */
    @Test
    fun isKodlu403SunucununCumlesi() {
        val e = XrayClinicClient.ClinicError.forbidden(Yetki.yasakMetni(kodlu))
        assertFalse(Yetki.reddiMi(e))
        assertEquals("Kendi limitinizi değiştiremezsiniz.", e.message)
        // Öteki istemci hataları yetki reddi değildir.
        assertFalse(Yetki.reddiMi(XrayClinicClient.ClinicError.server(null)))
        assertFalse(Yetki.reddiMi(XrayClinicClient.ClinicError.sessionExpired()))
        assertFalse(Yetki.reddiMi(XrayClinicClient.ClinicError.noPatient()))
    }

    @Test
    fun basariliYanitGovdeyiDondurur() {
        val json = XrayClinicClient.yanit(201, b("""{"xray":{"id":"x1"}}"""), yol)
        assertEquals("x1", json.obj("xray")?.str("id"))
        // Boş gövde de başarıdır (boş nesne).
        assertTrue(XrayClinicClient.yanit(200, ByteArray(0), yol).isEmpty())
    }

    @Test
    fun durumEslemesi() {
        // 403 kodsuz → forbidden (yetki reddi).
        val yasak = hata(403, kodsuz)
        assertTrue(yasak is XrayClinicClient.ClinicError.forbidden)
        assertTrue(Yetki.reddiMi(yasak))

        // 403 iş kodlu → forbidden, sunucunun cümlesi; yetki reddi değil.
        val isKodu = hata(403, kodlu)
        assertTrue(isKodu is XrayClinicClient.ClinicError.forbidden)
        assertEquals("Kendi limitinizi değiştiremezsiniz.", isKodu.message)
        assertFalse(Yetki.reddiMi(isKodu))

        // 402 → tek kredi modeli (kredi kartı).
        val kredi = hata(402, b("""{"code":"INSUFFICIENT_CREDIT","requiredCredits":4,"availableCredits":1,"canPurchase":true}"""))
        assertTrue(kredi is CreditRequired)
        assertEquals(4.0, (kredi as CreditRequired).requiredCredits!!, 0.0)

        // Demo hesapta 403 DEMO_CREDITS_EXHAUSTED → demo kredi kartı (yetki uyarısı DEĞİL).
        val demo = hata(403, b("""{"code":"DEMO_CREDITS_EXHAUSTED","message":"Demo krediniz bitti."}"""))
        assertTrue(demo is CreditRequired)
        assertTrue((demo as CreditRequired).isDemo)

        // 401 → oturum sona erdi.
        val oturum = hata(401, b("""{"code":"SESSION_IDLE"}"""))
        assertTrue(oturum is XrayClinicClient.ClinicError.sessionExpired)
        assertEquals("Oturumunuz sona ermiş. Tekrar giriş yapıp deneyin.", oturum.message)
    }

    /** Öteki durumlar: sunucunun cümlesi varsa o; kod biçimli değer ya da ham JSON ekrana basılmaz → varsayılan cümle. */
    @Test
    fun sunucuHatasiCumlesi() {
        val cumleli = hata(500, b("""{"error":"Analiz servisi şu an yanıt vermiyor."}"""))
        assertTrue(cumleli is XrayClinicClient.ClinicError.server)
        assertEquals("Analiz servisi şu an yanıt vermiyor.", cumleli.message)

        val kodBicimli = hata(503, b("""{"error":"XRAY_SERVICE_DOWN"}"""))
        assertEquals("Röntgen analizi tamamlanamadı. Tekrar deneyin.", kodBicimli.message)

        val hamGovde = hata(502, b("<html>Bad gateway</html>"))
        assertEquals("Röntgen analizi tamamlanamadı. Tekrar deneyin.", hamGovde.message)

        assertNotNull(XrayClinicClient.ClinicError.noPatient().message)
        assertEquals("Önce hastayı seçin.", XrayClinicClient.ClinicError.noPatient().message)
    }
}
