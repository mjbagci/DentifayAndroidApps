package com.dentifay.app.smile

import com.dentifay.app.core.Yetki
import com.dentifay.app.core.govde
import com.dentifay.app.smile.KieImageEditClient.KieError
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS `MobilEkleme3Tests` (gülüş: 409 bedel değişimi, 503 kur yok, iade vaadi yok, kodsuz 403), `KrediTests.test08`
 * (gülüş hata metinleri) ve `MobilEkleme7Tests.test2` (KieError yetki reddi) karşılıkları — saf mantık, ağ yok.
 */
class KieImageEditClientTest {

    /** MobilEkleme3 · 3: 409 `E_CREDIT_PRICE_CHANGED` görev başlatmaz, güncel bedeli taşır; gövde `beklenenKredi`yi yalnız sunucu yolunda taşır. */
    @Test
    fun bedelDegistiVeKurYok() {
        // Sunucunun gövdesi (smile-sim.js @ ae8e20c6): `{ code: 409, msg, error: "E_CREDIT_PRICE_CHANGED", credits }`.
        val hata = KieImageEditClient.sunucuHatasi(
            409, govde("code" to 409, "msg" to "…", "error" to "E_CREDIT_PRICE_CHANGED", "credits" to 15),
        )
        assertTrue("409 tanınmadı", hata is KieError.priceChanged)
        assertEquals(15.0, (hata as KieError.priceChanged).credits!!, 0.0)
        assertEquals(
            "Kredi bedeli güncellendi: 15 kredi. Devam etmek için yeniden dokunun.",
            KieError.priceChanged(15.0).message,
        )
        assertEquals("Kredi bedeli güncellendi. Devam etmek için yeniden dokunun.", KieError.priceChanged(null).message)

        for (kod in listOf("E_CREDIT_FX_MISSING", "E_CREDIT_PRICE_MISSING")) {
            assertEquals("503 $kod", KieError.fxMissing, KieImageEditClient.sunucuHatasi(503, govde("code" to 503, "error" to kod)))
        }
        assertEquals("Kredi bedeli şu an hesaplanamıyor; biraz sonra tekrar deneyin.", KieError.fxMissing.message)
        // 402 AYNEN.
        assertTrue(KieImageEditClient.sunucuHatasi(402, govde("error" to "INSUFFICIENT_CREDIT")) is KieError.clinicCreditsExhausted)

        val giris = govde("prompt" to "p")
        val sunucu = KieImageEditClient.gorevGovdesi(SmileAIModel.nanoBanana, giris, fixOf = null, beklenenKredi = 4.0, sunucuYolu = true)
        assertEquals(4.0, (sunucu["beklenenKredi"] as JsonPrimitive).doubleOrNull!!, 0.0)
        val dogrudan = KieImageEditClient.gorevGovdesi(SmileAIModel.nanoBanana, giris, fixOf = null, beklenenKredi = 4.0, sunucuYolu = false)
        assertNull("sağlayıcıya gitmez", dogrudan["beklenenKredi"])
    }

    /** Otomatik yerleşim düzeltmesi asıl göreve bağlanır (`fixOf`, yalnız sunucu yolu); düzeltme görevinde bedel yok. */
    @Test
    fun duzeltmeGoreviGovdesi() {
        val input = SmileAIModel.qwenMaskFast.input("https://ornek.test/a.jpg", "https://ornek.test/m.png", "p", 7)
        val g = KieImageEditClient.gorevGovdesi(SmileAIModel.qwenMaskFast, input, fixOf = "t1", beklenenKredi = null, sunucuYolu = true)
        assertEquals("qwen2-1/image-to-image", (g["model"] as JsonPrimitive).content)
        assertEquals("t1", (g["fixOf"] as JsonPrimitive).content)
        assertNull(g["beklenenKredi"])
        assertNull(KieImageEditClient.gorevGovdesi(SmileAIModel.qwenMaskFast, input, "t1", 4.0, sunucuYolu = false)["fixOf"])
    }

    /** MobilEkleme3 · 4: başarısız görevde iade vaadi YOK; açılamayan görevde "düşülmedi" geçer. */
    @Test
    fun iadeVaadiYok() {
        val acildi = KieImageEditClient.basarisizGorev(refunded = false, sunucuYolu = true, misafir = false, mesaj = "servis şu an yoğun")
        assertEquals("açılan görev MO3.4 değil", KieError.failedAfterStart, acildi)
        assertEquals("Gülüş simülasyonu tamamlanamadı. Kullanılan kredi iade edilmez.", acildi.message)
        assertFalse(acildi.message.contains("iade edildi"))

        // Sözleşme: `refunded: true` YALNIZ görev açma yanıtında (sağlayıcıya ulaşılamadı / geçici kapalı).
        for ((http, kod) in listOf(502 to "SMILE_SIM_UPSTREAM_ERROR", 503 to "SMILE_SIM_PROVIDER_UNAVAILABLE")) {
            val acilamadi = KieImageEditClient.sunucuHatasi(http, govde("code" to http, "error" to kod, "refunded" to true))
            assertTrue("$http refunded tanınmadı", acilamadi is KieError.refunded)
            assertEquals("Gülüş simülasyonu başlatılamadı; krediniz düşülmedi. Tekrar deneyin.", acilamadi!!.message)
            assertTrue(acilamadi.message.contains("düşülmedi"))
        }

        val digerleri = listOf(
            KieError.generationFailed(null), KieError.timedOut, KieError.server(500, null), KieError.badResponse, KieError.contentRejected(null),
        )
        for (e in digerleri) assertFalse(e.message, e.message.contains("iade edildi"))

        // Misafir yolu bu ekte değişmez; geliştirme (doğrudan) yolunda genel cümle.
        assertTrue(KieImageEditClient.basarisizGorev(refunded = true, sunucuYolu = true, misafir = true, mesaj = null) is KieError.trialRefunded)
        assertTrue(KieImageEditClient.basarisizGorev(refunded = false, sunucuYolu = true, misafir = true, mesaj = null) is KieError.generationFailed)
        assertTrue(KieImageEditClient.basarisizGorev(refunded = false, sunucuYolu = false, misafir = false, mesaj = null) is KieError.generationFailed)
    }

    /** MobilEkleme3 · 7 + MobilEkleme7 · 2: kodsuz 403 → "Bu işlem için yetkiniz yok.", `Yetki.reddiMi` tanır; kurulum kapısı sayılmaz. */
    @Test
    fun kodsuz403YetkiReddi() {
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, KieError.forbidden.message)
        assertTrue(Yetki.reddiMi(KieError.forbidden))
        assertEquals(
            KieError.forbidden,
            KieImageEditClient.sunucuHatasi(403, govde("error" to "Bu işlem için yetkiniz yok", "capability" to "ai.smilesim", "role" to "BANKO")),
        )
        // Önce kurulum kapısı: yetki reddi DEĞİL (bekleme ekranı açılır, ham kod görünmez).
        val kurulum = KieImageEditClient.sunucuHatasi(403, govde("error" to "Kurulum tamamlanmadı", "code" to "E_CLINIC_SETUP_INCOMPLETE"))
        assertEquals(KieError.server(403, null), kurulum)
        assertFalse(Yetki.reddiMi(kurulum!!))
        assertFalse(Yetki.reddiMi(KieError.server(500, null)))
        assertFalse(Yetki.reddiMi(KieError.sessionExpired))
    }

    /** KrediTests · 08: gülüş hata metinlerinde sağlayıcı adı, ham kod, anahtar/dosya adı ve İngilizce `msg` YOK. */
    @Test
    fun hataMetinlerindeSaglayiciVeHamKodYok() {
        val hepsi = listOf(
            KieError.missingAPIKey, KieError.unauthorized, KieError.sessionExpired, KieError.forbidden, KieError.notConfiguredOnServer,
            KieError.insufficientCredits, KieError.clinicCreditsExhausted(4.0, 1.0), KieError.personalLimitReached(40.0, 40.0, 4.0, null),
            KieError.providerUnavailable, KieError.hourlyLimit, KieError.rateLimited, KieError.contentRejected("nsfw content"),
            KieError.generationFailed(null), KieError.refunded(null), KieError.timedOut, KieError.server(400, "Model not allowed"),
            KieError.badResponse, KieError.failedAfterStart, KieError.priceChanged(4.0), KieError.fxMissing,
        )
        for (e in hepsi) {
            val m = e.message
            for (yasak in listOf("kie", "AI_RUN", "Yapay zeka servis", "KieAPIKey", "Secrets.plist", "Model not allowed")) {
                assertFalse("$e: $m", m.contains(yasak, ignoreCase = true))
            }
        }
        assertNull(KieImageEditClient.readableFailure("Something unexpected happened"))
        assertEquals(
            "Sunucunun cümlesi.",
            KieError.personalLimitReached(40.0, 40.0, 4.0, "Sunucunun cümlesi.").message,
        )
        assertEquals(
            "Bu ay 40 kredilik limitinizin 40 kredisini kullandınız.",
            KieError.personalLimitReached(40.0, 40.0, 4.0, null).message,
        )
        assertEquals("Aylık kredi limitiniz doldu.", KieError.personalLimitReached(null, null, null, "").message)
    }

    /** Sunucu yolunun öteki kodları (Swift `sunucuHatasi` tablosu). */
    @Test
    fun sunucuHatalari() {
        assertEquals(KieError.sessionExpired, KieImageEditClient.sunucuHatasi(401, null))
        assertEquals(
            KieError.personalLimitReached(40.0, 38.0, 4.0, "Aylık limitiniz doldu."),
            KieImageEditClient.sunucuHatasi(
                402,
                govde(
                    "error" to "CREDIT_LIMIT_REACHED", "limitCredits" to 40, "usedCredits" to 38, "requiredCredits" to 4,
                    "message" to "Aylık limitiniz doldu.",
                ),
            ),
        )
        assertEquals(
            KieError.clinicCreditsExhausted(4.0, 1.5),
            KieImageEditClient.sunucuHatasi(402, govde("error" to "INSUFFICIENT_CREDIT", "requiredCredits" to 4, "availableCredits" to 1.5)),
        )
        assertEquals("Krediniz yetersiz. Bu tasarım 4 kredi, kalan krediniz 1,5.", KieError.clinicCreditsExhausted(4.0, 1.5).message)
        assertEquals("Krediniz yetersiz.", KieError.clinicCreditsExhausted(null, null).message)
        assertEquals(KieError.hourlyLimit, KieImageEditClient.sunucuHatasi(429, govde("error" to "SMILE_SIM_HOURLY_CAP_REACHED")))
        assertNull("başka 429 genel çözüme düşer", KieImageEditClient.sunucuHatasi(429, govde("error" to "RATE_LIMITED")))
        assertEquals(KieError.providerUnavailable, KieImageEditClient.sunucuHatasi(502, null))
        assertEquals(KieError.providerUnavailable, KieImageEditClient.sunucuHatasi(503, govde("error" to "SMILE_SIM_PROVIDER_UNAVAILABLE")))
        assertEquals(KieError.notConfiguredOnServer, KieImageEditClient.sunucuHatasi(503, govde("error" to "SMILE_SIM_NOT_CONFIGURED")))
        assertNull("başarılı yanıtta iade alanı hata değildir", KieImageEditClient.sunucuHatasi(200, govde("code" to 200, "refunded" to true)))
        assertNull("bedel değişimi dışındaki 409", KieImageEditClient.sunucuHatasi(409, govde("error" to "E_OTHER")))
    }

    /** Sağlayıcının İngilizce metni Türkçe kısa açıklamaya çevrilir; içerik reddi ham metinsiz. */
    @Test
    fun saglayiciMetniCevrilir() {
        assertEquals("servis yoğunluk nedeniyle zamanında yanıt vermedi", KieImageEditClient.readableFailure("The upstream API service timed out"))
        assertEquals("servis şu an yoğun", KieImageEditClient.readableFailure("Server busy, try again later"))
        assertEquals("serviste geçici bir hata oluştu", KieImageEditClient.readableFailure("Internal error"))
        assertTrue(KieImageEditClient.icerikReddiMi("NSFW content detected"))
        assertTrue(KieImageEditClient.icerikReddiMi("flagged as sensitive"))
        assertFalse(KieImageEditClient.icerikReddiMi("timeout"))
        assertEquals(
            "Görsel, içerik kuralları nedeniyle işlenemedi; başka bir fotoğrafla deneyin.",
            KieError.contentRejected("nsfw").message,
        )
        assertEquals(
            "Gülüş simülasyonu tamamlanamadı (servis şu an yoğun). Tekrar deneyin.",
            KieError.generationFailed("servis şu an yoğun").message,
        )
        assertEquals("Gülüş simülasyonu tamamlanamadı (hata 500). Tekrar deneyin.", KieError.server(500, "Internal Server Error").message)
    }

    /** Kademe anahtarları sunucu tarifesiyle aynı (`before_after:<tierKey>`); koda yazılı kredi sayısı yok. */
    @Test
    fun kademeAnahtarlari() {
        assertEquals(listOf("orta", "orta_iyi", "iyi", "cok_iyi"), SmileAIModel.selectable.map { it.tierKey })
        assertNull(SmileAIModel.fixModel.tierKey)
        assertEquals(SmileAIModel.nanoBanana, SmileAIModel.default)
        assertEquals("nano-banana-2", SmileAIModel.nanoBanana2Fast.apiModel)
        assertEquals("1K", SmileAIModel.nanoBanana2Fast.resolution)
    }
}
