package com.dentifay.app.notifications

import com.dentifay.app.core.APIFlexibleValue
import com.dentifay.app.core.APINotification
import com.dentifay.app.core.DfyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS `BildirimTests` (M-E1.6) kural 3–5 — 19 türün metni web'in çıktısıyla aynı, türe göre başlık/gövde, bilinmeyen tür.
 * (Kural 1–2 — uç biçimi ve sayaç — çekirdekte: `core/YetkiVeHataTest.bildirimUclari`.)
 */
class BildirimMetniTest {

    private fun satir(json: String): APINotification = DfyJson.decodeFromString(APINotification.serializer(), json)

    /**
     * Kural 3: 19 türün her birinde boş olmayan başlık; fikstür tablosu web `bildirim-metni.js` çıktısıyla eşit
     * (`baslikMetni(s,t)` · `govdeMetni(s,t,opts) || t("notifications.types.<tür>.body", params)`).
     */
    @Test
    fun onDokuzTurWebIleAyni() {
        assertEquals(19, BildirimMetni.turler.size)
        val fikstur = listOf(
            Triple("""{"id":"1","type":"appointment.created","params":{"tarih":"2026-10-17","saat":"10:30"},"doctorName":"Dt. A"}""", "Yeni randevu", "17.10.2026 10:30 · Dt. A"),
            Triple("""{"id":"2","type":"appointment.arrived","params":{"saat":"14:00","oda":"Oda 2"}}""", "Hasta geldi", "14:00 randevusu · Oda 2"),
            Triple("""{"id":"3","type":"appointment.inTreatment","params":{}}""", "Hasta odaya alındı", "Tedavide"),
            Triple("""{"id":"4","type":"appointment.completed","params":{},"doctorName":"Dt. B"}""", "Hasta klinikten çıktı", "Dt. B"),
            Triple("""{"id":"5","type":"appointment.cancelled","params":{"durum":"NO_SHOW","tarih":"2026-10-17","saat":"09:00"}}""", "Hasta gelmedi", "17.10.2026 09:00"),
            Triple("""{"id":"6","type":"payment.received","params":{"tutar":1800,"yontem":"CARD","iade":true}}""", "İade yapıldı", "₺1.800,00 · Kredi Kartı"),
            Triple("""{"id":"7","type":"ai.permissionDenied","params":{}}""", "Yetki dışı asistan sorusu", "yetkisi olmayan bir veri sordu"),
            Triple("""{"id":"8","type":"task.assigned","params":{}}""", "Size görev atandı", "Görev sorumlusu olarak siz atandınız."),
            Triple("""{"id":"9","type":"task.unassigned","params":{}}""", "Sorumlusuz yeni görev", "Bu görevin sorumlusu henüz atanmadı."),
            Triple("""{"id":"10","type":"task.due","params":{}}""", "Görevin vakti geldi", "Görevin vadesi doldu."),
            Triple(
                """{"id":"11","type":"survey.lowScore","params":{"puan":1,"soruSayisi":3},"survey":{"question":"Memnun kaldınız mı?","doctorName":"Dt. A"}}""",
                "Düşük anket puanı", "“Memnun kaldınız mı?” sorusuna 1 puan · +2 soru · Dt. A",
            ),
            Triple("""{"id":"12","type":"task.completed","params":{}}""", "Atadığınız görev tamamlandı", "Görev sorumlusu tarafından tamamlandı."),
            Triple(
                """{"id":"13","type":"pricing.approvalPending","params":{"indirim":15},"pricing":{"canApprove":false}}""",
                "Fiyat onayı bekliyor", "%15 indirim talebi · Bu talebi onaylama yetkiniz yok.",
            ),
            Triple("""{"id":"14","type":"pricing.approvalResolved","params":{"sonuc":"REJECTED"}}""", "Fiyat talebiniz reddedildi", "Sonucu pazarlık ekranında görebilirsiniz."),
            Triple("""{"id":"15","type":"xray.planRejected","params":{}}""", "Röntgen tedavi öneriniz reddedildi", "Gerekçeyi plan geçmişinde görebilirsiniz."),
            Triple(
                """{"id":"16","type":"xray.planApprovalPending","params":{"kalem":3},"patientMasked":true}""",
                "Onay bekleyen röntgen planı", "Randevulu hasta için röntgenden hazırlanan plan (3 kalem) onayınızı bekliyor.",
            ),
            Triple(
                """{"id":"17","type":"xray.planApproved","params":{"kalem":2},"patient":{"name":"Deneme Hasta"}}""",
                "Röntgen planı onaylandı", "Deneme Hasta için gönderdiğiniz plan onaylandı.",
            ),
            Triple("""{"id":"18","type":"credits.low","params":{"kalan":42}}""", "Kredi bakiyesi azaldı", "Kalan kredi: 42. Kredi Yönetimi'nden kredi yükleyebilirsiniz."),
            Triple("""{"id":"19","type":"credits.limitReached","params":{"kullanilan":40,"limit":40}}""", "Kredi limiti doldu", "Aylık kredi limiti doldu (40 / 40 kredi)."),
        )
        assertEquals(BildirimMetni.turler.toSet(), fikstur.map { satir(it.first).type }.toSet())
        for ((json, baslik, govde) in fikstur) {
            val n = satir(json)
            assertTrue(n.type, BildirimMetni.baslik(n).isNotEmpty())
            assertEquals(n.type, baslik, BildirimMetni.baslik(n))
            assertEquals(n.type, govde, BildirimMetni.govde(n))
        }
    }

    /** Kural 4: türe ve parametreye göre başlık/gövde. */
    @Test
    fun tureGoreMetin() {
        assertEquals("Hasta gelmedi", BildirimMetni.baslik(satir("""{"id":"a","type":"appointment.cancelled","params":{"durum":"NO_SHOW"}}""")))
        assertEquals("İade yapıldı", BildirimMetni.baslik(satir("""{"id":"b","type":"payment.received","params":{"iade":true}}""")))
        assertTrue(
            BildirimMetni.govde(satir("""{"id":"c","type":"pricing.approvalPending","params":{"indirim":10},"pricing":{"canApprove":false}}"""))
                ?.contains("Bu talebi onaylama yetkiniz yok.") ?: false
        )
        assertEquals(
            "Aylık kredi limiti doldu (40 / 40 kredi).",
            BildirimMetni.govde(satir("""{"id":"d","type":"credits.limitReached","params":{"kullanilan":40,"limit":40}}""")),
        )
        assertEquals(
            "Randevulu hasta için röntgenden hazırlanan plan (3 kalem) onayınızı bekliyor.",
            BildirimMetni.govde(satir("""{"id":"e","type":"xray.planApprovalPending","params":{"kalem":3},"patientMasked":true}""")),
        )
        // Öteki iptal durumları ve fiyat onayı; tanınmayan durum türün genel başlığına düşer.
        assertEquals("Randevu ertelendi", BildirimMetni.baslik(satir("""{"id":"f","type":"appointment.cancelled","params":{"durum":"RESCHEDULED"}}""")))
        assertEquals("Randevu silindi", BildirimMetni.baslik(satir("""{"id":"g","type":"appointment.cancelled","params":{"durum":"DELETED"}}""")))
        assertEquals("Randevu iptal edildi", BildirimMetni.baslik(satir("""{"id":"h","type":"appointment.cancelled","params":{"durum":"BILINMEYEN"}}""")))
        assertEquals("Fiyat talebiniz onaylandı", BildirimMetni.baslik(satir("""{"id":"i","type":"pricing.approvalResolved","params":{"sonuc":"APPROVED"}}""")))
        assertEquals("Ödeme alındı", BildirimMetni.baslik(satir("""{"id":"j","type":"payment.received","params":{"iade":false,"tutar":250,"yontem":"CASH"}}""")))
        assertEquals("₺250,00 · Nakit", BildirimMetni.govde(satir("""{"id":"k","type":"payment.received","params":{"tutar":250,"yontem":"CASH"}}""")))
        // Bilinmeyen ödeme yöntemi AYNEN.
        assertEquals("₺250,00 · HAVALE", BildirimMetni.govde(satir("""{"id":"l","type":"payment.received","params":{"tutar":"250","yontem":"HAVALE"}}""")))
    }

    /** Kural 5: bilinmeyen tür görünmez; `credits.low` ve `xray.planApproved` görünür. */
    @Test
    fun bilinmeyenTur() {
        assertFalse(BildirimMetni.gorunur(satir("""{"id":"x","type":"voice.call"}""")))
        assertTrue(BildirimMetni.gorunur(satir("""{"id":"y","type":"credits.low"}""")))
        assertTrue(BildirimMetni.gorunur(satir("""{"id":"z","type":"xray.planApproved"}""")))
        assertEquals("", BildirimMetni.baslik(satir("""{"id":"x","type":"voice.call"}""")))
        assertNull(BildirimMetni.govde(satir("""{"id":"x","type":"voice.call"}""")))
    }

    /** `konuMetni`: maskeli hasta önce; arşivli hasta eki; görev başlığı; aktör (unvan EKLENMEZ — hekim değil, kişi adı aynen). */
    @Test
    fun konuMetni() {
        assertEquals(BildirimMetni.MASKELI_HASTA, BildirimMetni.konu(satir("""{"id":"1","type":"appointment.arrived","patientMasked":true,"patient":{"name":"Ayşe"}}""")))
        assertEquals("Ayşe Yılmaz (Arşivlenmiş hasta)", BildirimMetni.konu(satir("""{"id":"2","type":"appointment.arrived","patient":{"name":"Ayşe Yılmaz","archived":true}}""")))
        assertEquals("Ölçü gönder", BildirimMetni.konu(satir("""{"id":"3","type":"task.due","task":{"title":"Ölçü gönder"}}""")))
        assertEquals("Banko Kişi", BildirimMetni.konu(satir("""{"id":"4","type":"ai.permissionDenied","actorName":"Banko Kişi"}""")))
        assertNull(BildirimMetni.konu(satir("""{"id":"5","type":"credits.low","patient":{"name":""}}""")))
    }

    /** Yardımcılar: tarih biçimi, şablon doldurma (eksik anahtar boş; değerdeki `{{…}}` yeniden açılmaz), "N kez", alt metin. */
    @Test
    fun yardimcilar() {
        assertEquals("17.10.2026", BildirimMetni.tarihBicimi("2026-10-17"))
        assertEquals("17.10.2026", BildirimMetni.tarihBicimi("2026-10-17T09:00:00.000Z"))
        assertEquals("dün", BildirimMetni.tarihBicimi("dün"))
        assertEquals("a  b", BildirimMetni.doldur("a {{yok}} b", emptyMap()))
        assertEquals("x {{y}} z", BildirimMetni.doldur("x {{v}} z", mapOf("v" to APIFlexibleValue.Text("{{y}}"), "y" to APIFlexibleValue.Text("!"))))
        assertEquals("%15 indirim", BildirimMetni.doldur("%{{i}} indirim", mapOf("i" to APIFlexibleValue.Number(15.0))))
        assertNull(BildirimMetni.sayiMetni(satir("""{"id":"1","type":"task.due","count":1}""")))
        assertEquals("3 kez", BildirimMetni.sayiMetni(satir("""{"id":"1","type":"task.due","count":3}""")))
        assertEquals("Görevin vadesi doldu. · 3 kez", BildirimMetni.altMetin(satir("""{"id":"1","type":"task.due","count":3}""")))
        // Gövdesi boş kalan tür (parametresiz randevu) alt metin çizmez.
        assertNull(BildirimMetni.altMetin(satir("""{"id":"2","type":"appointment.created","params":{}}""")))
    }
}
