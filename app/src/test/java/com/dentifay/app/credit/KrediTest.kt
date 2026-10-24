package com.dentifay.app.credit

import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Kurulum
import com.dentifay.app.core.Yetki
import com.dentifay.app.core.hataCumlesi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * iOS `KrediTests` (M-E1.7 + Mobil Ekleme 2 mühürleri) — cüzdan, satın alma (koşul kabulü), hareketler (imleç + süzgeç),
 * görünürlük, limit/eşik yazımı (`If-Match`), kredi yetmedi modeli, kredi ipucu, aylık döküm; ayrıca `MobilEkleme7Tests`in
 * kredi istemcilerine ait yetki reddi kuralları. (`test08` gülüş hata metinleri `smile` paketinindir.)
 */
class KrediTest {

    private val client = CreditCenterClient("https://ornek.test/api")

    private fun b(s: String) = s.toByteArray()

    private fun terms(liste: Int = 50000, odenecek: Int = 45000): SmilePurchaseTerms = DfyJson.decodeFromString(
        SmilePurchaseTerms.serializer(),
        """{"surum":"2026-10-21.1","ozet":"abc123","baslik":"Kredi Satın Alma Koşulları","packId":"pack_500","listeKurus":$liste,"odenecekKurus":$odenecek,"bolumler":[{"baslik":"1","maddeler":["m"]}]}""",
    )

    private fun govde(req: Request): JsonObject {
        val buf = Buffer()
        req.body!!.writeTo(buf)
        return DfyJson.parseToJsonElement(buf.readUtf8()).jsonObject
    }

    private fun sorgu(req: Request): Map<String, String> =
        req.url.queryParameterNames.associateWith { req.url.queryParameter(it) ?: "" }

    /** M-E1.7 kural 1: `priceTL: null`, `listPriceTL`, metin `discountPct`, `purchasedTL`siz ay çözülür; PRICE → Ek MO.21l. */
    @Test
    fun test01_CuzdanSunucuyaUyar() {
        val w = DfyJson.decodeFromString(
            SmileWallet.serializer(),
            """
            {"availableCredits":30,"freeGrantCredits":30,"freeUsedCredits":0,"freeRemainingCredits":30,"balanceCredits":0,
             "tiers":[],"packs":[{"id":"p1","credits":500,"priceTL":null,"listPriceTL":500,"discountPct":"10.00","unitPriceTL":null,"savePct":10}],
             "canPurchase":false,"purchaseBlockedReason":"PRICE","thisMonth":{"period":"2026-10","usedCredits":0,"billed":false},"isDemo":false}
            """.trimIndent(),
        )
        assertNull(w.packs.first().priceTL)
        assertEquals(500.0, w.packs.first().listPriceTL!!, 0.0)
        assertEquals(10.0, w.packs.first().discountPct!!, 0.0)
        assertEquals(10, w.packs.first().savePct)
        assertNull(w.thisMonth?.purchasedTL)
        assertEquals("Paket fiyatı şu an okunamıyor.", w.purchaseBlockedMessage)
    }

    /** M-E1.7 kural 2 (+ M-E2 kural 2): gövde `{packId, kosullar:{surum, ozet}}`, `Idempotency-Key` desene uyar. */
    @Test
    fun test02_SatinAlmaIstegi() {
        val c = SmileCreditsClient("https://ornek.test/api")
        val key = "mobil-pack_500-${UUID.randomUUID().toString().lowercase()}"
        val req = c.satinAlmaIstegi("pack_500", terms(), key)
        assertEquals("POST", req.method)
        val body = govde(req)
        assertEquals(setOf("packId", "kosullar"), body.keys)
        assertEquals("pack_500", body["packId"]!!.jsonPrimitive.content)
        val k = body["kosullar"]!!.jsonObject
        assertEquals("2026-10-21.1", k["surum"]!!.jsonPrimitive.content)
        assertEquals("abc123", k["ozet"]!!.jsonPrimitive.content)
        val anahtar = req.header("Idempotency-Key")
        assertNotNull(anahtar)
        assertTrue(Regex("""^[A-Za-z0-9_\-:.]{8,128}$""").matches(anahtar!!))
        assertEquals(key, anahtar)
    }

    /**
     * M-E1.7 kural 3 (+ M-E2 kural 5): süzgeçsiz istekte süzgeç YOK, `page` hiç yok; `cursor` `nextCursor`dan;
     * süzgeçler URL'ye girer; `tur` etiketi, kişisiz "Dentifay", kaynak yalnız varsa.
     */
    @Test
    fun test03_HareketlerVeSuzgecler() {
        val bos = sorgu(client.hareketIstegi(CreditFilter(), cursor = null))
        for (yok in listOf("page", "from", "to", "kind", "tur", "userId", "feature", "cursor")) assertNull(yok, bos[yok])
        assertEquals("30", bos["limit"])
        val sonraki = sorgu(client.hareketIstegi(CreditFilter(), cursor = "imlec-2"))
        assertEquals("imlec-2", sonraki["cursor"])
        val suz = sorgu(client.hareketIstegi(CreditFilter(from = "2026-10-01", to = "2026-10-22", userId = "u1", feature = "xray", tur = "iade"), cursor = "c"))
        assertEquals("2026-10-01", suz["from"])
        assertEquals("2026-10-22", suz["to"])
        assertEquals("u1", suz["userId"])
        assertEquals("xray", suz["feature"])
        assertEquals("iade", suz["tur"])
        assertEquals("c", suz["cursor"])
        assertNull(suz["page"])
        // Yalnız biri verilen aralık gönderilmez.
        assertNull(sorgu(client.hareketIstegi(CreditFilter(from = "2026-10-01"), cursor = null))["from"])

        val sayfa = DfyJson.decodeFromString(
            CreditTransactionsPage.serializer(),
            """
            {"items":[{"id":"1","at":"2026-10-22T08:00:00Z","tur":"kullanim","feature":"ai_agent","action":"chat","credits":-2,"kaynak":"ucretsiz","kisi":{"id":"u1","ad":"Dt. A"}},
                      {"id":"2","at":"2026-10-01T00:00:00Z","tur":"tanim","feature":"platform_grant","action":"opening","credits":30,"kaynak":null,"kisi":null},
                      {"id":"3","at":"2026-10-01T00:00:00Z","tur":"yeni_tur","feature":null,"action":null,"credits":1,"kaynak":null,"kisi":null}],
             "nextCursor":"c2","limit":30}
            """.trimIndent(),
        )
        assertEquals("c2", sayfa.nextCursor)
        assertEquals(listOf("Kullanım", "Dentifay tanımı", "Diğer"), sayfa.items.map { it.turMetni })
        assertEquals("Dentifay", sayfa.items[1].kisiMetni)
        assertEquals("Ücretsiz kredi", sayfa.items[0].kaynakMetni)
        assertNull(sayfa.items[1].kaynakMetni)
        assertEquals("−2", sayfa.items[0].isaretliKredi)
        assertEquals("Dentifay Asistan sorusu", sayfa.items[0].islemMetni)
        // Sözleşme §10 (Ekleme 20 · 39B): tanım satırı "Dentifay'ın tanımladığı kredi".
        assertEquals("Dentifay'ın tanımladığı kredi", sayfa.items[1].islemMetni)
        assertEquals("Diğer", sayfa.items[2].islemMetni)
    }

    /** M-E1.7 kural 4: görünürlük kümesi. */
    @Test
    fun test04_Gorunurluk() {
        assertTrue(KrediErisimi.yonetimGorur(setOf("comm.usage")))
        assertFalse(KrediErisimi.yukleGorur(setOf("comm.usage")))
        assertTrue(KrediErisimi.yonetimGorur(setOf("fin.credits")))
        assertTrue(KrediErisimi.yukleGorur(setOf("fin.credits")))
        assertEquals(KrediErisimi.AyarlarSatiri.krediler, KrediErisimi.ayarlarSatiri(setOf("ai.smilesim")))
        assertEquals(KrediErisimi.AyarlarSatiri.yonetim, KrediErisimi.ayarlarSatiri(setOf("comm.usage", "ai.smilesim")))
        assertNull(KrediErisimi.ayarlarSatiri(emptySet()))
    }

    /** M-E1.7 kural 5: limit isteği `If-Match` + `{total, features}`; özellik listesi yanıttan; eşik isteği `If-Match`. */
    @Test
    fun test05_LimitVeEsikYazimi() {
        val r = client.limitIstegi(userId = "u1", total = 40.0, features = mapOf("before_after" to 12.0, "yeni_ozellik" to null), version = "v7")
        assertEquals("PUT", r.method)
        assertEquals("\"v7\"", r.header("If-Match"))
        assertTrue(r.url.encodedPath.endsWith("/ai-credits/limits/u1"))
        val body = govde(r)
        assertEquals(40.0, body["total"]!!.jsonPrimitive.double, 0.0)
        val f = body["features"]!!.jsonObject
        assertEquals(12.0, f["before_after"]!!.jsonPrimitive.double, 0.0)
        assertTrue(f["yeni_ozellik"] is JsonNull)
        // Sınırsız toplam açıkça `null` gider (TAM set).
        assertTrue(govde(client.limitIstegi("u1", null, emptyMap(), "v8"))["total"] is JsonNull)
        // Kimlik yol parçası olarak kodlanır ("/" yolu bozamaz).
        assertTrue(client.limitIstegi("a/b", null, emptyMap(), "v").url.encodedPath.endsWith("/limits/a%2Fb"))

        val p = DfyJson.decodeFromString(
            CreditPeople.serializer(),
            """{"month":"2026-10","features":["ai_agent","yeni_ozellik"],"people":[],"nextCursor":null,"unattributed":null,"canManageLimits":true,"maxLimitCredits":1000000}""",
        )
        assertEquals("özellik listesi sunucudan", listOf("ai_agent", "yeni_ozellik"), p.features)

        val e = client.ayarIstegi(esik = 60.0, version = "e6000")
        assertEquals("PUT", e.method)
        assertEquals("\"e6000\"", e.header("If-Match"))
        assertEquals(60.0, govde(e)["lowBalanceWarnCredits"]!!.jsonPrimitive.double, 0.0)
        assertEquals("E_CREDIT_LIMIT_VERSION", CreditCenterClient.ClientError.server(409, "Sürüm değişti.", "E_CREDIT_LIMIT_VERSION").code)
        assertEquals(409, CreditCenterClient.ClientError.server(409, "Sürüm değişti.", "E_CREDIT_LIMIT_VERSION").status)
        assertEquals("Sürüm değişti.", CreditCenterClient.ClientError.server(409, "Sürüm değişti.", "E_CREDIT_LIMIT_VERSION").message)
    }

    /** M-E1.7 kural 6: `CreditRequired` — demo 403, kodsuz 403, 402, `yarida`, FX eksik. */
    @Test
    fun test06_KrediYetmedi() {
        val demo = CreditRequired.parse(403, b("""{"code":"DEMO_CREDITS_EXHAUSTED","message":"Demo krediniz bitti."}"""))
        assertEquals(true, demo?.isDemo)
        assertNull(CreditRequired.parse(403, b("""{"error":"Yetkiniz yok."}""")))
        assertEquals(CreditRequired.Kind.insufficient, CreditRequired.parse(402, ByteArray(0))?.kind)

        val yarida = CreditRequired.parse(buildJsonObject { put("code", "INSUFFICIENT_CREDIT"); put("message", "X"); put("yarida", true) })
        assertEquals(CreditRequired.YARIDA_METNI, yarida!!.detail)
        val tam = CreditRequired.parse(buildJsonObject { put("code", "INSUFFICIENT_CREDIT"); put("message", "X"); put("yarida", false) })
        assertEquals("X", tam!!.detail)
        assertNull(CreditRequired.parse(buildJsonObject { put("code", "E_CREDIT_FX_MISSING"); put("message", "Kur yok.") }))
    }

    /** M-E1.7 kural 7: kredi ipucu metinleri. */
    @Test
    fun test07_KrediTarifesi() {
        val t = DfyJson.decodeFromString(
            KrediTarifesi.serializer(),
            """
            {"tarife":[{"feature":"ai_agent","action":"chat","enAz":1,"enCok":2,"kullanimaBagli":true},
                       {"feature":"xray","action":"analyze","enAz":4,"enCok":4,"kullanimaBagli":false},
                       {"feature":"messaging","action":"suggest_message","enAz":1,"enCok":1,"kullanimaBagli":false},
                       {"feature":"ai_center","action":"briefing","enAz":2,"enCok":2,"kullanimaBagli":false}],
             "durum":{"ai_agent":{"durum":"UCRETLI"},"xray":{"durum":"UCRETLI"},"messaging":{"durum":"BASLAYACAK","tarih":"2026-10-21"},
                      "ai_center":{"durum":"UCRETSIZ"}}}
            """.trimIndent(),
        )
        assertNull(t.ipucu("ai_center:briefing"))
        assertEquals("21.10.2026 tarihinden itibaren kredi kullanacak; şu an ücretsiz.", t.ipucu("messaging:suggest_message"))
        assertEquals("Bu işlem ortalama 4 kredi kullanır.", t.ipucu("xray:analyze"))
        assertEquals(
            "Bu işlem ortalama 1–2 kredi kullanır. Kullanılan kredi işlemin kapsamına göre hesaplanır; uzun ya da kapsamlı isteklerde daha fazla olabilir.",
            t.ipucu("ai_agent:chat"),
        )
        assertNull(t.ipucu("bilinmeyen:islem"))
        // Liste sütunu (Ek MO.27e).
        assertEquals("Şu an ücretsiz", t.listeMetni(t.satir("ai_center:briefing")!!))
        assertEquals("Ortalama 1–2 kredi; kullanıma göre değişir", t.listeMetni(t.satir("ai_agent:chat")!!))
    }

    /** M-E1.7 kural 9: aylık döküm durum kuralı. */
    @Test
    fun test09_AyDurumu() {
        assertNull(CreditUsage.Month("2026-10", usedCredits = 3.0, purchasedTL = 0.0, overageTL = 0.0, billed = false).durumMetni)
        assertNull(CreditUsage.Month("2026-10", usedCredits = 3.0, purchasedTL = null, overageTL = null, billed = true).durumMetni)
        assertEquals("Faturalandı", CreditUsage.Month("2026-09", usedCredits = 3.0, purchasedTL = 100.0, overageTL = 0.0, billed = true).durumMetni)
        assertEquals(
            "Ay sonu Dentifay faturasına yansır",
            CreditUsage.Month("2026-10", usedCredits = 3.0, purchasedTL = 100.0, overageTL = 0.0, billed = false).durumMetni,
        )
        assertEquals("Faturalandı", CreditUsage.Month("2026-08", usedCredits = 0.0, purchasedTL = null, overageTL = 20.0, billed = true).durumMetni)
    }

    /** M-E2 kural 1: koşul metni yüklenmeden ve kutu işaretlenmeden satın alma isteği ATILMAZ. */
    @Test
    fun test10_KosulsuzSatinAlmaYok() {
        var d = SatinAlmaDurumu()
        assertFalse(d.gonderilebilir)
        d = d.copy(kabul = true)
        assertFalse("metin yokken kutu tek başına yetmez", d.gonderilebilir)
        d = d.copy(terms = terms(), kabul = false)
        assertFalse(d.gonderilebilir)
        d = d.copy(kabul = true)
        assertTrue(d.gonderilebilir)
    }

    /** M-E2 kural 3: 428/409 gövdesindeki `terms` metni yeniler ve kutuyu temizler. */
    @Test
    fun test11_KosulYenilenir() {
        var d = SatinAlmaDurumu(terms = terms(), kabul = true)
        val yeni = DfyJson.decodeFromString(
            SmilePurchaseTerms.serializer(),
            """{"surum":"2026-10-22.2","ozet":"yeni","baslik":"B","packId":"pack_500","listeKurus":50000,"odenecekKurus":45000,"bolumler":[]}""",
        )
        d = d.kosulGuncellendi(yeni, mesaj = null)
        assertEquals("2026-10-22.2", d.terms?.surum)
        assertFalse(d.kabul)
        assertEquals("Satın alma koşulları güncellendi; yeni metni okuyup yeniden onaylayın.", d.not)
        assertFalse(d.gonderilebilir)
        // Gövdede metin yoksa eldeki metin kalır; sunucunun cümlesi aynen.
        val ayni = SatinAlmaDurumu(terms = terms(), kabul = true).kosulGuncellendi(null, "Sunucunun cümlesi.")
        assertEquals("2026-10-21.1", ayni.terms?.surum)
        assertEquals("Sunucunun cümlesi.", ayni.not)
    }

    /** M-E2 kural 4: `taslak` modelde yok (sunucu gönderse de çizilecek alan yok). */
    @Test
    fun test12_TaslakYok() {
        val t = DfyJson.decodeFromString(
            SmilePurchaseTerms.serializer(),
            """{"surum":"1","ozet":"o","baslik":"B","taslak":true,"packId":"p","listeKurus":100,"odenecekKurus":100,"bolumler":[]}""",
        )
        assertTrue(SmilePurchaseTerms::class.java.declaredFields.none { it.name == "taslak" })
        assertFalse(t.indirimVar)
        assertTrue(terms().indirimVar)
        assertEquals(450.0, terms().odenecekTL, 1e-9)
        assertEquals(500.0, terms().listeTL, 1e-9)
    }

    /** M-E2 kural 6: `runwayDays` ve `heatmap` `/analiz` yanıtından çözülür. */
    @Test
    fun test13_AnalizAlanlari() {
        val a = DfyJson.decodeFromString(
            CreditAnalysis.serializer(),
            """
            {"tz":"Europe/Istanbul","from":"2026-10-01","to":"2026-10-22","truncated":false,"runwayDays":34,"last30DaysCredits":46,"unit":"day",
             "summary":{"usedCredits":46,"refundedCredits":4,"operations":31,"purchasedCredits":0,"purchases":0},
             "series":[],"features":[{"feature":"ai_agent","credits":24,"operations":18,"refunds":1,"share":52}],"actions":[],
             "heatmap":[[{"credits":1,"ops":1}]],"users":[]}
            """.trimIndent(),
        )
        assertEquals(34, a.runwayDays)
        assertEquals(46.0, a.last30DaysCredits!!, 0.0)
        assertEquals(1.0, a.heatmap.first().first().credits, 0.0)
        assertEquals(52, a.features.first().share)
        assertNull("tutar yoksa TL çizilmez", a.summary.paidTL)
        val yok = DfyJson.decodeFromString(
            CreditAnalysis.serializer(),
            """{"runwayDays":null,"unit":"day","summary":{"usedCredits":0,"refundedCredits":0,"operations":0,"purchasedCredits":0,"purchases":0},"series":[],"features":[],"actions":[],"heatmap":[],"users":[]}""",
        )
        assertNull(yok.runwayDays)
        assertNull(yok.tz)
    }

    /** M-E2 kural 7: satın alma satırında `tutarTL` yoksa tutar çizilmez; `kosulVar` okunur. */
    @Test
    fun test14_TutarYoksaCizilmez() {
        val r = DfyJson.decodeFromString(
            CreditTransactionsPage.Row.serializer(),
            """{"id":"t4","at":"2026-10-15T10:00:00Z","tur":"satin_alma","feature":"purchase","action":"credit_pack","credits":500,"kaynak":null,"kisi":{"id":"u3","ad":"Dt. B"},"kosulVar":true}""",
        )
        assertNull(r.tutarTL)
        assertEquals(true, r.kosulVar)
        assertEquals("+500", r.isaretliKredi)
    }

    /** Sözleşme §10: kabul edilen koşullar `metin: { baslik, bolumler }` biçimiyle çözülür (paket/tutar alanı beklenmez). */
    @Test
    fun test15_KabulEdilenKosullarSozlesmeBicimi() {
        val t = DfyJson.decodeFromString(
            AcceptedTerms.serializer(),
            """
            {"surum":"2026-10-21.1","ozet":"4b1e9c07d2a8f613","kabulEden":{"ad":"Dt. B"},"at":"2026-10-15T10:00:00Z",
             "metin":{"baslik":"Kredi Satın Alma Koşulları","bolumler":[{"baslik":"1. Taraflar","maddeler":["Deneme madde."]}]}}
            """.trimIndent(),
        )
        assertEquals("2026-10-21.1", t.surum)
        assertEquals("Dt. B", t.kabulEden?.ad)
        assertEquals(listOf("Deneme madde."), t.metin?.bolumler?.first()?.maddeler)
    }

    /**
     * iOS `MobilEkleme7Tests` (kredi istemcileri): kodsuz 403 yetki reddidir → parça çizilmez; kurulum kapısı ve iş kodlu 403
     * yetki reddi SAYILMAZ.
     */
    @Test
    fun yetkiReddiKrediIstemcileri() {
        val kodsuz = b("""{"error":"Bu işlem için yetkiniz yok","capability":"fin.view","role":"BANKO"}""")
        val metin = hataCumlesi(403, kodsuz) ?: ""
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, metin)
        assertTrue(Yetki.reddiMi(CreditCenterClient.ClientError.server(403, metin, null)))
        assertTrue(Yetki.reddiMi(SmileCreditsClient.ClientError.forbidden(Yetki.yasakMetni(kodsuz))))
        assertFalse(Yetki.reddiMi(CreditCenterClient.ClientError.server(403, "Kendi limitinizi değiştiremezsiniz.", "E_CREDIT_LIMIT_SELF")))
        assertFalse(Yetki.reddiMi(CreditCenterClient.ClientError.server(403, "Kurulum", Kurulum.KOD)))
        assertFalse(Yetki.reddiMi(CreditCenterClient.ClientError.server(500, null, null)))
        assertFalse(Yetki.reddiMi(CreditCenterClient.ClientError.notLoggedIn()))
        assertFalse(Yetki.reddiMi(SmileCreditsClient.ClientError.forbidden("Kendi limitinizi değiştiremezsiniz.")))
        assertFalse(Yetki.reddiMi(SmileCreditsClient.ClientError.server(403, null)))
        // Cümlesiz `forbidden` da Ek MO3.7 metnini gösterir.
        assertEquals(Yetki.EYLEM_YETKISIZ_METNI, SmileCreditsClient.ClientError.forbidden(null).message)
    }

    /** Girdi kuralı: 0–üst sınır, en çok iki ondalık; virgül ondalık ayırıcı. */
    @Test
    fun krediGirdisi() {
        assertEquals(CreditInput.empty, CreditInput.parse("  "))
        assertEquals(CreditInput.value(12.5), CreditInput.parse("12,5"))
        assertEquals(CreditInput.value(12.25), CreditInput.parse("12.25"))
        assertEquals(CreditInput.invalid, CreditInput.parse("12,345"))
        assertEquals(CreditInput.invalid, CreditInput.parse("-1"))
        assertEquals(CreditInput.invalid, CreditInput.parse("1000001"))
        assertEquals(CreditInput.invalid, CreditInput.parse("50", max = 40.0))
        assertEquals(CreditInput.invalid, CreditInput.parse("abc"))
        assertEquals(CreditInput.value(0.0), CreditInput.parse("0"))
    }

    /** Sayı ve tarih biçimleri: kredi Türkçe ondalık, ay adı, aralık etiketi, işaretli kredi. */
    @Test
    fun bicimler() {
        assertEquals("2,5", creditText(2.5))
        assertEquals("1.250", creditText(1250.0))
        assertEquals("Ekim 2026", CreditDates.monthLabel("2026-10"))
        assertEquals("tanınmayan biçim aynen", "2026-1-1", CreditDates.monthLabel("2026-1-1"))
        assertEquals(listOf("2026-10", "2026-09", "2026-08"), CreditDates.lastMonths(3, CreditDates.istanbul, java.time.Instant.parse("2026-10-24T09:00:00Z")))
        assertEquals(listOf("2026-01", "2025-12"), CreditDates.lastMonths(2, CreditDates.istanbul, java.time.Instant.parse("2026-01-05T09:00:00Z")))
        val b = CreditRange.thisMonth.bounds(CreditDates.istanbul, java.time.Instant.parse("2026-10-24T09:00:00Z"))
        assertEquals("2026-10-01", b.from)
        assertEquals("2026-10-24", b.to)
        assertEquals("21.10.2026", KrediTarifesi.tarih("2026-10-21T00:00:00Z"))
        assertEquals("", KrediTarifesi.tarih(null))
        assertEquals(CreditRange.thisMonth, CreditRange.entries.first())
    }
}
