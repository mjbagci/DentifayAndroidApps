package com.dentifay.app.core

import com.dentifay.app.auth.AuthManager
import com.dentifay.app.data.DataManager
import com.dentifay.app.ui.YetkiliEylem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** iOS `MobilEkleme7Tests` · `MobilEkleme3Tests` (çekirdek kısımları) · `OnizlemeTests.test6` · `BildirimTests.test1/2`. */
@OptIn(ExperimentalCoroutinesApi::class)
class YetkiVeHataTest {

    @Before fun kur() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun boz() = Dispatchers.resetMain()

    private fun b(s: String) = s.toByteArray()

    /** 1. `kurulum.tamamlandi == false` iken bekleme ekranı; 403 `E_CLINIC_SETUP_INCOMPLETE` yetki uyarısı DEĞİL. */
    @Test
    fun kurulumBeklemeEkrani() = runTest {
        val auth = AuthManager()
        try { kurulumBeklemeAkisi(auth) } finally { auth.dispose() }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.kurulumBeklemeAkisi(auth: AuthManager) {
        var kisi = APIUser(id = "u", email = "d@ornek.test", name = "Deneme", organizationId = "o", role = "DOCTOR")
        auth.currentUser = kisi
        auth.isLoggedIn = true
        assertNull("alan yoksa bugünkü gibi — sekmeler", auth.kurulumBekleme)

        kisi = kisi.copy(kurulum = KurulumDurumu(tamamlandi = false, yapabilir = true, adimSayisi = 6, onayliAdim = 2))
        auth.currentUser = kisi
        assertEquals(true, auth.kurulumBekleme)
        assertEquals("Kliniğinizin kurulumu tamamlanmadı. Kurulumu bilgisayarınızdan Dentifay'a girerek tamamlayın.", Kurulum.metin(true))
        auth.currentUser = kisi.copy(kurulum = KurulumDurumu(tamamlandi = false, yapabilir = false))
        assertEquals(false, auth.kurulumBekleme)
        assertTrue(Kurulum.metin(false).startsWith("Bu kliniğin kurulumu tamamlanmadığı için devam edemezsiniz."))
        auth.currentUser = kisi.copy(kurulum = KurulumDurumu(tamamlandi = true, yapabilir = true, adimSayisi = 6, onayliAdim = 6))
        assertNull(auth.kurulumBekleme)

        val govde = b("""{"error":"Kurulum tamamlanmadı","code":"E_CLINIC_SETUP_INCOMPLETE","kurulumYapabilir":false}""")
        var olay: Boolean? = null
        val dinleyici = launch(start = CoroutineStart.UNDISPATCHED) { olay = Kurulum.tamamlanmadi.first() }
        assertEquals(Yetki.Ret.Kurulum(false), Yetki.ret(govde))
        val metin = Yetki.yasakMetni(govde)
        assertNotEquals(Yetki.EYLEM_YETKISIZ_METNI, metin)
        assertNotEquals(Yetki.KAPSAM_DISI_METNI, metin)
        assertFalse(Yetki.reddiMi(ApiException.ServerError(403, metin)))
        dinleyici.join()
        assertEquals(false, olay)
        // Olay oturumdaki ekranı bekleme ekranına geçirir.
        assertEquals(false, auth.kurulumBekleme)
    }

    /** 2. Yetkisiz eylem çağrılmaz ve uyarır; kodsuz 403 yetki reddidir; iş kodlu 403, 5xx ve ağ hatası değildir. */
    @Test
    fun yetkisizEylemVeKirmiziKartYok() {
        var cagrildi = false
        assertEquals("Bu işlem için yetkiniz yok.", YetkiliEylem.dokun(false) { cagrildi = true })
        assertFalse(cagrildi)
        assertNull(YetkiliEylem.dokun(true) { cagrildi = true })
        assertTrue(cagrildi)
        assertEquals(0.4f, YetkiliEylem.SONUK_OPAKLIK)
        assertEquals(listOf("appt.edit", "appt.checkin"), Yetki.randevuEylemi("ARRIVED"))
        assertEquals(listOf("appt.edit", "appt.noshow"), Yetki.randevuEylemi("NO_SHOW"))
        assertEquals(listOf("appt.edit"), Yetki.randevuEylemi("CANCELLED"))

        val kodsuz = b("""{"error":"Bu işlem için yetkiniz yok","capability":"fin.view","role":"BANKO"}""")
        val metin = hataCumlesi(403, kodsuz) ?: ""
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, metin)
        assertTrue(Yetki.reddiMi(ApiException.ServerError(403, metin)))
        assertTrue(Yetki.reddiMi(object : Exception(), YetkiReddi { override val yetkiReddiMi = true }))
        assertFalse(Yetki.reddiMi(ApiException.ServerError(500, null)))
        assertFalse(Yetki.reddiMi(java.io.IOException("ağ yok")))
    }

    /** iOS `MobilEkleme3Tests.test7` (çekirdek kısmı): kodsuz 403 → "Bu işlem için yetkiniz yok."; iş kodlu 403 → sunucunun cümlesi. */
    @Test
    fun yasakMetinleri() {
        val kodsuz = b("""{"error":"Bu işlem için yetkiniz yok","capability":"ai.xray","role":"BANKO"}""")
        assertEquals("Bu işlem için yetkiniz yok.", Yetki.yasakMetni(kodsuz))
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, hataCumlesi(403, kodsuz))
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, Yetki.yasakMetni(b("Forbidden")))
        val kodlu = b("""{"error":"Kendi limitinizi değiştiremezsiniz.","code":"E_CREDIT_LIMIT_SELF"}""")
        assertEquals("Kendi limitinizi değiştiremezsiniz.", hataCumlesi(403, kodlu))
    }

    /** `ApiClient.hata`: kodlu 4xx → Kodlu (cümle korunur), kodsuz 403 → yetki reddi, 5xx → ServerError; şifre olayı yayınlanır. */
    @Test
    fun hataEslemesi() = runTest {
        val kodlu409 = ApiClient.hata(409, b("""{"error":"Plan başka bir yerde değişti; yenileyip tekrar deneyin.","code":"E_VERSION_CONFLICT"}"""))
        assertTrue(kodlu409 is ApiException.Kodlu)
        assertEquals(409, kodlu409.status)
        assertEquals("E_VERSION_CONFLICT", kodlu409.kod)
        assertEquals("Plan başka bir yerde değişti; yenileyip tekrar deneyin.", kodlu409.message)

        val kodsuz403 = ApiClient.hata(403, b("""{"error":"Bu işlem için yetkiniz yok"}"""))
        assertTrue(kodsuz403 is ApiException.ServerError)
        assertTrue(Yetki.reddiMi(kodsuz403))

        var sifre = false
        val dinleyici = launch(start = CoroutineStart.UNDISPATCHED) { SifreOlayi.gerekli.first(); sifre = true }
        val sifreHatasi = ApiClient.hata(403, b("""{"error":"Önce şifrenizi değiştirin.","code":"PASSWORD_CHANGE_REQUIRED"}"""))
        dinleyici.join()
        assertTrue(sifre)
        assertEquals("PASSWORD_CHANGE_REQUIRED", sifreHatasi.kod)

        val sunucu = ApiClient.hata(503, b("""{"error":"Sunucu şu an meşgul, biraz sonra deneyin."}"""))
        assertTrue(sunucu is ApiException.ServerError)
        assertEquals("Sunucu şu an meşgul, biraz sonra deneyin.", sunucu.message)
        assertEquals(null, sunucu.kod)
    }

    /** Durum gövdesi: otomatik kipte `notifyPatient` alanı YOK (M-E3.5). */
    @Test
    fun durumGovdesi() {
        assertEquals(JsonObject(mapOf("status" to JsonPrimitive("CANCELLED"))), ApiClient.durumGovdesi("CANCELLED", null))
        assertEquals(
            JsonObject(mapOf("status" to JsonPrimitive("NO_SHOW"), "notifyPatient" to JsonPrimitive(false))),
            ApiClient.durumGovdesi("NO_SHOW", false),
        )
    }

    /** Bildirim uçları: `kanal` yok, `page` + `limit` var; sayaç `{unread}`; eski `{alert, inbox}` çözülmez. */
    @Test
    fun bildirimUclari() {
        val q = ApiClient.bildirimListesiSorgusu(2, 30).toMap()
        assertFalse(q.containsKey("kanal"))
        assertEquals("2", q["page"])
        assertEquals("30", q["limit"])
        assertEquals("/bildirimler/okundu", ApiClient.TUMUNU_OKUNDU_YOLU)
        assertEquals("/bildirimler/n1/okundu", ApiClient.tekOkunduYolu("n1"))
        assertEquals(3, DfyJson.decodeFromString(APINotificationCount.serializer(), """{"unread":3}""").unread)
        assertTrue(runCatching { DfyJson.decodeFromString(APINotificationCount.serializer(), """{"alert":1,"inbox":2}""") }.isFailure)
    }

    /** iOS `OnizlemeTests.test6`: hekim listesi OWNER/ADMIN satırlarını tutar; `closedDoctors` ayrı; seçim `apiUserId` ile korunur. */
    @Test
    fun hekimKumesi() {
        val yanit = DfyJson.decodeFromString(
            TakvimHekimleri.serializer(),
            """{"doctors":[
               {"id":"m1","userId":"u1","role":"OWNER","user":{"id":"u1","name":"Dt. A","email":"a@x"}},
               {"id":"m2","userId":"u2","role":"ADMIN","user":{"id":"u2","name":"Dt. B","email":"b@x"}}],
             "closedDoctors":[{"id":"m3","userId":"u3","role":"DOCTOR","user":{"id":"u3","name":"Dt. C","email":"c@x"}}],
             "day":{"date":"2026-10-21"}}""",
        )
        val liste = DataManager.doktorlar(yanit, listOf(androidx.compose.ui.graphics.Color.Cyan))
        assertEquals(listOf("u1", "u2", "u3"), liste.map { it.apiUserId })
        assertEquals(listOf(false, false, true), liste.map { it.kapali })
        assertEquals("u2", HekimSecici.korunanSecim("u2", liste))
        val yeniGun = liste.reversed()
        assertEquals("u2", HekimSecici.korunanSecim("u2", yeniGun))
        assertEquals("u2", HekimSecici.korunanSecim("yok", yeniGun))
        assertNull(HekimSecici.korunanSecim(null, emptyList()))
    }

    /** Kredi modeli: sunucunun cümlesi AYNEN; yarıda kalan tur Ek MO.23h; demo 403; kodsuz 403 kredi değil. */
    @Test
    fun krediModeli() {
        val k = CreditRequired.parse(402, b("""{"code":"INSUFFICIENT_CREDIT","message":"Krediniz yetersiz.","requiredCredits":4,"availableCredits":1,"canPurchase":true}"""))!!
        assertEquals("Kredi yetersiz", k.title)
        assertEquals("Krediniz yetersiz.", k.detail)
        assertTrue(k.canPurchase)
        val yarida = CreditRequired.parse(JsonObject(mapOf("code" to JsonPrimitive("INSUFFICIENT_CREDIT"), "yarida" to JsonPrimitive(true))))!!
        assertEquals(CreditRequired.YARIDA_METNI, yarida.detail)
        val demo = CreditRequired.parse(403, b("""{"code":"DEMO_CREDITS_EXHAUSTED"}"""))!!
        assertTrue(demo.isDemo)
        assertEquals("Demo krediniz bitti", demo.title)
        assertNull(CreditRequired.parse(403, b("""{"error":"Bu işlem için yetkiniz yok"}""")))
        assertNull(CreditRequired.parse(500, b("{}")))
        val bos = CreditRequired.parse(402, b("bozuk"))!!
        assertEquals("Bu işlem için yeterli krediniz yok. Kredi yüklemek için klinik sahibine başvurun.", bos.detail)
        assertEquals("Kredi yetersiz. Bu işlem için yeterli krediniz yok. Kredi yüklemek için klinik sahibine başvurun.", bos.message)
        assertEquals(bos.detail, bos.kartMetni)
    }

    /** Sunucu cümlesi başlıkla başlıyorsa ("Kredi yetersiz. …") başlık iki kez yazılmaz; kart gövdesi başlığı tekrarlamaz. */
    @Test
    fun krediBasligiTekrarlanmaz() {
        val k = CreditRequired.parse(402, b("""{"code":"INSUFFICIENT_CREDIT","message":"Kredi yetersiz. Kredi Yönetimi'nden kredi yükleyebilirsiniz."}"""))!!
        assertEquals("Kredi yetersiz. Kredi Yönetimi'nden kredi yükleyebilirsiniz.", k.message)
        assertEquals("Kredi Yönetimi'nden kredi yükleyebilirsiniz.", k.kartMetni)
        val yalniz = CreditRequired.parse(402, b("""{"code":"INSUFFICIENT_CREDIT","message":"Kredi yetersiz."}"""))!!
        assertEquals("Kredi yetersiz.", yalniz.message)
        assertEquals("Kredi yetersiz.", yalniz.kartMetni)
    }

    /** `govde(...)`: null değerli anahtar atılır; liste/harita/sayı/mantıksal kodlanır. */
    @Test
    fun govdeKurucu() {
        val g = govde("a" to "x", "b" to null, "c" to 2, "d" to true, "e" to listOf("11", "12"), "f" to mapOf("g" to 1.5, "h" to null))
        assertEquals("""{"a":"x","c":2,"d":true,"e":["11","12"],"f":{"g":1.5}}""", g.toString())
    }
}
