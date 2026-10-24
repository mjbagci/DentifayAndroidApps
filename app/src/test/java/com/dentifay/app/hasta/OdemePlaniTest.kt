package com.dentifay.app.hasta

import com.dentifay.app.core.DfyJson
import com.dentifay.app.home.RandevuSms
import com.dentifay.app.home.SmsKipleri
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Ödeme planı (web `taksit-takvimi.js` · `PaymentPlanModal` · `paymentPlanPrint.js` ikizi — M-E8.12 / M-E9.2). */
class OdemePlaniTest {

    @Test
    fun taksitTakvimi() {
        assertEquals("ay sonunda kırpılır", "2026-02-28", OdemePlani.ayEkle("2026-01-31", 1, capa = 31))
        assertEquals("artık yıl", "2028-02-29", OdemePlani.ayEkle("2028-01-31", 1, capa = 31))
        assertEquals("çapa günü korunur", "2026-03-31", OdemePlani.ayEkle("2026-01-31", 2, capa = 31))
        assertEquals("yıl devri", "2027-01-15", OdemePlani.ayEkle("2026-12-15", 1, capa = 15))
        assertEquals("geri", "2025-12-15", OdemePlani.ayEkle("2026-01-15", -1, capa = 15))
        assertNull(OdemePlani.ayEkle("2026-13-01", 1, capa = 1))
        assertNull(OdemePlani.ayEkle("bozuk", 1, capa = 1))

        assertEquals("bugünden bir ay sonra aynı gün", "2026-11-24", OdemePlani.varsayilanIlkVade("2026-10-24"))
        assertEquals("2026-02-28", OdemePlani.varsayilanIlkVade("2026-01-30"))
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), OdemePlani.vadeGunleri(4, "2026-01-31"))
        assertNull(OdemePlani.vadeGunleri(0, "2026-01-31"))
        assertNull(OdemePlani.vadeGunleri(241, "2026-01-31"))

        assertEquals("kalan SON taksite", listOf(33.33, 33.33, 33.34), OdemePlani.tutariBol(100.0, 3))
        assertEquals(listOf(1000.0), OdemePlani.tutariBol(1000.0, 0))
        assertEquals(listOf(0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01), OdemePlani.tutariBol(0.12, 12))

        val t = OdemePlani.taksitler(1000.0, 3, "2026-11-30")
        assertEquals(listOf(1, 2, 3), t.map { it.no })
        assertEquals(listOf("2026-11-30", "2026-12-30", "2027-01-30"), t.map { it.vade })
        assertEquals(1000.0, t.sumOf { it.tutar }, 1e-9)
        assertTrue(OdemePlani.taksitler(1000.0, 3, "geçersiz").isEmpty())

        val simdi = Instant.parse("2026-10-23T22:30:00Z")
        assertEquals("kliniğin bugünü", "2026-10-24", OdemePlani.bugun("Europe/Istanbul", simdi))
        assertEquals("2026-10-23", OdemePlani.bugun("UTC", simdi))
    }

    @Test
    fun smsKipi() {
        assertEquals(RandevuSms.Kip.yok, OdemePlani.smsKipi(null))
        assertEquals(
            RandevuSms.Kip.otomatik(tur = "PAYMENT_PLAN_CREATED"),
            OdemePlani.smsKipi(SmsKipleri(modes = listOf(SmsKipleri.Kip(kind = "PAYMENT_PLAN_CREATED", mode = "auto")))),
        )
        assertEquals(
            "PAYMENT_PLAN_CREATED yoksa IBAN_INFO",
            RandevuSms.Kip.sor(tur = "IBAN_INFO"),
            OdemePlani.smsKipi(SmsKipleri(modes = listOf(SmsKipleri.Kip(kind = "IBAN_INFO", mode = "ASK")))),
        )
        assertEquals(RandevuSms.Kip.yok, OdemePlani.smsKipi(SmsKipleri(modes = listOf(SmsKipleri.Kip(kind = "APPOINTMENT_CANCELLED", mode = "AUTO")))))
        assertEquals("eski `kinds` biçimi otomatik sayılır", RandevuSms.Kip.otomatik(tur = "IBAN_INFO"), OdemePlani.smsKipi(SmsKipleri(kinds = listOf("IBAN_INFO"))))
        assertEquals(RandevuSms.Kip.yok, OdemePlani.smsKipi(SmsKipleri(modes = emptyList(), kinds = listOf("PAYMENT_PLAN_CREATED"))))
    }

    private val kayitli: KayitliOdemePlani = DfyJson.decodeFromString(
        """{"id":"op1","totalAmount":"12000.00","notes":"Tedavi Planı: Plan 1 · 3 taksit","status":"ACTIVE","createdAt":"2026-10-12T09:00:00.000Z","teklifBagi":false,
        "installments":[
          {"id":"t1","amount":"4000.00","dueDate":"2026-11-12T00:00:00.000Z","status":"PAID","manualPaidAt":null,"allocatedAmount":"4000.00","remainingAmount":"0"},
          {"id":"t2","amount":"4000.00","dueDate":"2026-12-12T00:00:00.000Z","status":"PARTIAL","manualPaidAt":null,"allocatedAmount":"1500","remainingAmount":"2500.00"},
          {"id":"t3","amount":"4000.00","dueDate":"2027-01-12T00:00:00.000Z","status":"PENDING","manualPaidAt":"2026-10-20T10:00:00Z","manualPaidByAd":"Deneme"},
          {"id":"t4","amount":"999","status":"CANCELLED","allocatedAmount":"50"},
          {"id":"t5","amount":"1000","status":"PENDING","allocatedAmount":"200"}]}""",
    )

    @Test
    fun kayitliPlanOzeti() {
        val oz = OdemePlani.ozet(kayitli)
        assertEquals("iptal atlanır; Σ dağıtılan", 5700.0, oz.odenen, 1e-9)
        assertEquals("açık kalan: kalan alanı, yoksa tutar − dağıtılan; elle işaretli kapalı", 3300.0, oz.kalan, 1e-9)
        assertEquals(1, oz.elle)
        assertEquals("Ödendi", OdemePlani.durumAdi["PAID"])
        assertEquals(
            "Bu ödeme planı ve 5 taksiti silinecek. Taksitlere bağlı ödemeler yerinde kalır; hastanın borcu değişmez.",
            OdemePlani.silmeMetni(kayitli),
        )
        assertTrue(OdemePlani.silmeMetni(kayitli.copy(teklifBagi = true)).endsWith("teklif kalır, plana bağı kopar."))
    }

    @Test
    fun posKomisyonu() {
        val p: PosKomisyonu = DfyJson.decodeFromString("""{"id":"x","label":"Banka A","data":{"single":"1.99","installments":{"3":4.5,"6":"7.25"}}}""")
        assertEquals("Banka A", p.ad)
        assertEquals(1.99, p.oran(1)!!, 0.0)
        assertEquals(4.5, p.oran(3)!!, 0.0)
        assertNull("tanımsız oran 0 sayılmaz", p.oran(12))
        assertEquals("2.5", cG(2.5))
        assertEquals("10", cG(10.0))
        assertEquals("1.75", cG(1.75))
        assertEquals("0.0001", cG(0.0001))
        assertEquals("1.23457e+06", cG(1234567.0))
        assertEquals("1e-05", cG(0.00001))
    }

    @Test
    fun odemePlaniBaskisi() {
        val taksitler = OdemePlani.taksitler(1000.0, 3, "2026-11-30")
        val poslar = listOf<PosKomisyonu>(
            DfyJson.decodeFromString("""{"id":"a","name":"Banka A","data":{"single":2.5,"installments":{"3":6}}}"""),
            DfyJson.decodeFromString("""{"id":"b","name":"Banka <B>","data":{"single":1.5}}"""),
        )
        val kunye = PlanCiktisi.Kunye(
            letterhead = PlanCiktisi.Kunye.Antet(name = "DENEME KLİNİK"),
            odemeBilgisi = PlanCiktisi.Kunye.OdemeBilgisi(iban = "TR609999900000000000001234"),
        )
        val simdi = Instant.parse("2026-10-24T09:00:00Z")
        val h = OdemePlani.baskiHtml("Plan 1", "Ayşe Yılmaz", 1000.0, taksitler, poslar, 3, kunye, PlanCiktisi.Ayar(odemeBilgisi = true), ZoneId.of("UTC"), simdi)
        assertTrue(h.contains("<tr><td>1/3</td><td class=\"r\">₺333,33</td><td>30.11.2026</td></tr>"))
        assertTrue(h.contains("<tr><td>3/3</td><td class=\"r\">₺333,34</td><td>30.01.2027</td></tr>"))
        assertTrue(h.contains("<div class=\"meta\">Ödeme Planı<br>24.10.2026</div>"))
        assertTrue(h.contains("Toplam Tutar: <b class=\"total\">₺1.000,00</b> · 3 taksit"))
        assertTrue(h.contains("DENEME KLİNİK"))
        assertTrue(h.contains("TR60 9999 9000 0000 0000 0012 34"))
        assertTrue(h.contains("<th class=\"r\">Tek Çekim</th><th class=\"r\">3 Taksit</th>"))
        // Tek çekimde en avantajlı B (1,5 %), 3 taksitte yalnız A'da oran var.
        assertTrue(h.contains("<td class=\"bank\">Banka A</td><td class=\"r \">₺1.025,00<br><span class=\"sub\">%2.5</span></td><td class=\"r best\">₺353,33<br><span class=\"sub\">%6</span></td></tr>"))
        assertTrue(h.contains("<td class=\"bank\">Banka &lt;B&gt;</td><td class=\"r best\">₺1.015,00<br><span class=\"sub\">%1.5</span></td><td class=\"r\">—</td></tr>"))
        assertTrue(h.contains("<div class=\"foot\">Bu belge bilgilendirme amaçlıdır · 24.10.2026</div>"))

        val sade = OdemePlani.baskiHtml(null, "", 500.0, OdemePlani.taksitler(500.0, 1, "2026-11-30"), emptyList(), 1, null,
            PlanCiktisi.Ayar(clinicHeader = false, printDate = false), ZoneId.of("UTC"), simdi)
        assertTrue(sade.contains("<div class=\"head\"><div style=\"height:46px\"></div><div class=\"meta\">Ödeme Planı</div></div>"))
        assertTrue(sade.contains("Hasta: <b>—</b>"))
        assertTrue(sade.contains("Tedavi Planı: <b>—</b>"))
        assertFalse("POS yoksa karşılaştırma yok", sade.contains("Banka / POS"))
        assertFalse("IBAN ayarı kapalı", sade.contains("odeme-bilgisi"))
    }
}
