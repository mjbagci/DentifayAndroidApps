package com.dentifay.app.hasta

import com.dentifay.app.core.DfyJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** iOS `MobilEkleme9Tests` test1 (baskı HTML'i — web `yazdirma-ayarlari.test.js` örnek planı) + antet/IBAN/biçim ayrıntıları. */
class PlanCiktisiTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private fun plan(json: String): HastaPlani = DfyJson.decodeFromString(json)

    /** Web testinin planı: Kaplama USD (kur 1), TL toplamı 0 → yedek formül. */
    private val ornekPlan = plan(
        """{"id":"p","title":"Örnek Plan","status":"IN_PROGRESS","isActive":true,"notes":"PLAN NOTU BURADA",
        "doctor":{"id":"d","name":"Mehmet Demir"},"version":1,"onayBekliyor":false,"kapali":false,"sistemPlani":false,"kalemSayisi":3,
        "items":[
          {"id":"a","name":"İmplant","stage":"PLANNED","tooth":"16","price":18000,"quantity":1,"discount":0,"totalAmountTRY":18000,"currency":"TRY","treatmentDate":"2026-10-12"},
          {"id":"b","name":"Kaplama","stage":"TODO","tooth":"26","price":100,"quantity":2,"discount":50,"exchangeRate":1,"totalAmountTRY":0,"currency":"USD","treatmentDate":"2026-10-26"},
          {"id":"c","name":"Temizlik","stage":"DONE","price":900,"quantity":1,"discount":0,"totalAmountTRY":900,"currency":"TRY","treatmentDate":"2026-09-28"}
        ]}""",
    )
    private val kunye = PlanCiktisi.Kunye(
        letterhead = PlanCiktisi.Kunye.Antet(name = "GÜLÜŞ KLİNİK"),
        odemeBilgisi = PlanCiktisi.Kunye.OdemeBilgisi(iban = "TR609999900000000000001234", aliciAdi = "Gülüş Klinik Ltd.", bankaAdi = "Örnek Banka"),
    )
    private val hasta = HastaBasligi(id = "h", ad = "Ayşe Yılmaz", dosyaNo = "1024", telefon = "05550000000")

    @Test
    fun baskiHtml() {
        val h = PlanCiktisi.html(ornekPlan, hasta, kunye, PlanCiktisi.Ayar(), utc, semaPNG = png)
        assertTrue("antet", h.contains("GÜLÜŞ KLİNİK"))
        assertTrue("hekim adı kartla aynı: Dt. unvanıyla", h.contains("Hekim: <b>Dt. Mehmet Demir</b>"))
        assertTrue("diş şeması (dişli kalem var)", h.contains("Tedavi İçeriği") && h.contains("data:image/png;base64,"))
        assertFalse("fxEquivalent varsayılan kapalı", h.contains("Döviz karşılığı"))
        assertFalse("patientContact varsayılan kapalı", h.contains("05550000000"))
        assertFalse("odemeBilgisi varsayılan kapalı", h.contains("TR60 9999"))
        assertTrue(
            "web'in beklenen satırı (tutar yedek formülle 100×2×1−50)",
            h.contains("<td class=\"r\">2</td><td>26</td><td>Kaplama</td><td>26 Ekim 2026</td><td class=\"r\">2</td><td class=\"r\">₺50,00</td><td class=\"r\">₺150,00</td>"),
        )
        assertTrue(h.contains("Planlama") && h.contains("Yapılacak") && h.contains("Yapılan"))
        assertTrue("liste · indirim · genel toplam", h.contains("₺19.100,00") && h.contains("− ₺50,00") && h.contains("₺19.050,00"))

        val acik = PlanCiktisi.html(
            ornekPlan, hasta, kunye, PlanCiktisi.Ayar(patientContact = true, fxEquivalent = true, odemeBilgisi = true), utc, semaPNG = png,
        )
        assertTrue("döviz sütunu web biçimiyle", acik.contains("Döviz karşılığı") && acik.contains("100 USD × 1"))
        assertTrue(acik.contains("05550000000"))
        assertTrue("IBAN dörtlü gruplar", acik.contains("TR60 9999 9000 0000 0000 0012 34"))

        val kapali = PlanCiktisi.html(ornekPlan, hasta, kunye, PlanCiktisi.Ayar(clinicHeader = false, toothChart = false), utc, semaPNG = png)
        assertFalse(kapali.contains("GÜLÜŞ KLİNİK"))
        assertTrue(kapali.contains("height:46px"))
        assertFalse(kapali.contains("Tedavi İçeriği"))
        // Dişsiz planda şema basılmaz.
        assertFalse(PlanCiktisi.semaVarMi(plan("""{"id":"x","items":[{"id":"c","name":"Temizlik","stage":"DONE"}]}""")))
    }

    @Test
    fun ayarVarsayilanlariVeToplamKipleri() {
        val v = PlanCiktisi.Ayar()
        assertTrue(v.antet && v.hekim && v.tarih && v.tedaviTarihi && v.fiyat && v.indirim && v.sema)
        assertFalse(v.iletisim || v.birim || v.doviz || v.not || v.iban)
        assertEquals("grandOnly", v.toplamKipi)
        assertEquals("tr", v.dil)
        assertEquals("No · Diş · Tedavi · Adet + Tarih · İndirim · Tutar", 7, PlanCiktisi.sutunSayisi(v))
        val fiyatsiz = PlanCiktisi.Ayar(priceInfo = false, unitPrice = true, discountRate = true, fxEquivalent = true, totalsMode = "subtotalsAndGrand")
        assertFalse("priceInfo:false → fiyata bağlı her şey kapalı", fiyatsiz.birim || fiyatsiz.indirim || fiyatsiz.doviz)
        assertEquals("none", fiyatsiz.toplamKipi)
        assertEquals(5, PlanCiktisi.sutunSayisi(fiyatsiz))
        assertEquals("grandOnly", PlanCiktisi.Ayar(totalsMode = "bilinmeyen").toplamKipi)
        assertEquals("en", PlanCiktisi.Ayar(planLanguage = "en").dil)

        val ara = PlanCiktisi.html(ornekPlan, hasta, null, PlanCiktisi.Ayar(totalsMode = "subtotalsAndGrand", planNote = true), utc)
        assertTrue(ara.contains("<tr class=\"sub\"><td colspan=\"6\" class=\"r\">Ara toplam</td><td class=\"r\">₺18.000,00</td></tr>"))
        assertTrue("not ayara göre", ara.contains("<p class=\"note-body\">PLAN NOTU BURADA</p>"))
        assertFalse("PNG yoksa şema bölümü yok", ara.contains("Tedavi İçeriği"))
        assertFalse("künye yoksa antet yok", ara.contains("class=\"letterhead"))
        val yok = PlanCiktisi.html(ornekPlan, hasta, null, PlanCiktisi.Ayar(totalsMode = "none"), utc)
        assertFalse(yok.contains("<tfoot>"))
        assertFalse(yok.contains(">Tutar<"))
    }

    @Test
    fun ingilizceBelge() {
        val simdi = Instant.parse("2026-10-24T21:30:00Z")
        val en = PlanCiktisi.html(ornekPlan, hasta, kunye, PlanCiktisi.Ayar(planLanguage = "en"), ZoneId.of("Europe/Istanbul"), simdi)
        assertTrue(en.contains("<html lang=\"en\">"))
        assertTrue(en.contains("<title>Treatment Plan</title>"))
        assertTrue("klinik saat dilimiyle bugün", en.contains("25 October 2026"))
        assertTrue(en.contains("26 October 2026"))
        assertTrue(en.contains("Grand total"))
        val tr = PlanCiktisi.html(ornekPlan, hasta, kunye, PlanCiktisi.Ayar(), ZoneId.of("Europe/Istanbul"), simdi)
        assertTrue(tr.contains("<div class=\"foot\">Bu belge bilgilendirme amaçlıdır. · 25 Ekim 2026</div>"))
    }

    @Test
    fun antetVeBicimler() {
        val tam = PlanCiktisi.Kunye(
            letterhead = PlanCiktisi.Kunye.Antet(
                name = "A & B <Klinik>", tradeName = "A Sağlık Ltd.", taxNo = "123", taxOffice = "Kadıköy", address = "Adres 1",
                phone = "+90 555 000 00 00", email = "bilgi@klinik.invalid", website = "klinik.test", logoDataUrl = "data:image/png;base64,AAA",
            ),
        )
        val a = PlanCiktisi.antetHtml(tam, "tr")
        assertTrue(a.contains("A &amp; B &lt;Klinik&gt;"))
        assertTrue(a.contains("<div>Tel: +90 555 000 00 00</div>"))
        assertFalse("`.invalid` e-posta basılmaz", a.contains("invalid"))
        assertTrue(a.contains("<div>Vergi Dairesi: Kadıköy · VKN/TCKN: 123</div>"))
        assertTrue(a.contains("<img src=\"data:image/png;base64,AAA\""))
        assertEquals("", PlanCiktisi.antetHtml(PlanCiktisi.Kunye(letterhead = PlanCiktisi.Kunye.Antet(name = "")), "tr"))
        assertEquals("", PlanCiktisi.antetHtml(null, "tr"))

        assertEquals("+90 555 123 45 67", PlanCiktisi.telefonGoster("+905551234567"))
        assertEquals("0555 123 45 67", PlanCiktisi.telefonGoster("0555 123 45 67"))
        assertEquals("TR12 3456 7", PlanCiktisi.ibanGrupla("tr12 34567"))
        assertEquals("&lt;a href=&quot;x&quot;&gt;&#39;&amp;", PlanCiktisi.esc("<a href=\"x\">'&"))
        assertEquals("—", PlanCiktisi.tarih(null, "tr", utc))
        assertEquals("—", PlanCiktisi.tarih("2026-13-40", "tr", utc))
        assertEquals("salt tarih gün kaymaz", "01 Ocak 2026", PlanCiktisi.tarih("2026-01-01", "tr", ZoneId.of("America/New_York")))
        assertEquals("an klinik saat diliminde", "02 Ocak 2026", PlanCiktisi.tarih("2026-01-01T22:00:00.000Z", "tr", ZoneId.of("Europe/Istanbul")))
        assertEquals("₺1.234,50", PlanCiktisi.para(1234.5, "tr"))
    }

    @Test
    fun dovizVeYaprak() {
        val p = plan(
            """{"id":"p","items":[
              {"id":"g","name":"Köprü","stage":"PLANNED","children":[
                {"id":"c1","name":"Köprü","stage":"PLANNED","tooth":"14","price":1234.5,"currency":"eur","exchangeRate":36.125},
                {"id":"c2","name":"Köprü","stage":"PLANNED","tooth":"15","deletedAt":"x"}]},
              {"id":"c1","name":"Köprü","stage":"PLANNED","tooth":"14","parentItemId":"g"},
              {"id":"s","name":"Sil","stage":"PLANNED","deletedAt":"x"}]}""",
        )
        val y = PlanCiktisi.yapraklar(p)
        assertEquals("grup yerine canlı yapraklar, silinmişler yok", listOf("c1"), y.map { it.id })
        assertEquals("1.234,5 EUR × 36.125", PlanCiktisi.dovizMetni(y[0], "tr"))
        assertEquals("1,234.5 EUR × 36.125", PlanCiktisi.dovizMetni(y[0], "en"))
        assertTrue(PlanCiktisi.semaVarMi(p))
        assertEquals("44596.31", "%.2f".format(java.util.Locale.ROOT, PlanCiktisi.satirTL(y[0])))
    }
}
