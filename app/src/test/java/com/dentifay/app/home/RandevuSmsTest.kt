package com.dentifay.app.home

import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.DfyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `MobilEkleme3Tests.test6_RandevuSmsKipi` — randevu durumunda hastaya SMS sorusu (M-E3.5). */
class RandevuSmsTest {

    /** `AUTO`da soru yok ve `notifyPatient` gönderilmez; `ASK`ta ön seçimsiz soru, onay seçime dek kapalı, cevap gövdeye birebir. */
    @Test
    fun randevuSmsKipi() {
        val yanit = SmsKipleri(
            modes = listOf(SmsKipleri.Kip("APPOINTMENT_CANCELLED", "AUTO"), SmsKipleri.Kip("APPOINTMENT_NO_SHOW", "ASK")),
            kinds = null,
        )
        val iptal = RandevuSms.kip(durum = "CANCELLED", yanit = yanit, telefonVar = true)
        assertEquals(RandevuSms.Kip.otomatik("APPOINTMENT_CANCELLED"), iptal)
        assertTrue(RandevuSms.onayAcik(iptal, cevap = null))
        assertNull(RandevuSms.notifyPatient(iptal, cevap = null))
        assertEquals("Hastaya randevu iptali SMS'i otomatik gönderilecek.", RandevuSms.otomatikMetni("APPOINTMENT_CANCELLED"))
        val govde = ApiClient.durumGovdesi("CANCELLED", RandevuSms.notifyPatient(iptal, cevap = null))
        assertFalse(govde.toString().contains("notifyPatient"))

        val gelmedi = RandevuSms.kip(durum = "NO_SHOW", yanit = yanit, telefonVar = true)
        assertEquals(RandevuSms.Kip.sor("APPOINTMENT_NO_SHOW"), gelmedi)
        assertFalse("ön seçim YOK — onay kapalı", RandevuSms.onayAcik(gelmedi, cevap = null))
        assertEquals("Hastaya gelmeyen hasta SMS'i gönderilsin mi?", RandevuSms.soruMetni("APPOINTMENT_NO_SHOW"))
        for (cevap in listOf(true, false)) {
            assertTrue(RandevuSms.onayAcik(gelmedi, cevap))
            val g = ApiClient.durumGovdesi("NO_SHOW", RandevuSms.notifyPatient(gelmedi, cevap))
            assertEquals(cevap.toString(), g["notifyPatient"].toString())
        }

        // Uç okunamadı → soru çizilir; otomasyon kapalı / SMS'siz durum / telefon yok → soru yok.
        assertEquals(RandevuSms.Kip.sor("APPOINTMENT_CANCELLED"), RandevuSms.kip("CANCELLED", yanit = null, telefonVar = true))
        assertEquals(RandevuSms.Kip.yok, RandevuSms.kip("CANCELLED", SmsKipleri(modes = emptyList(), kinds = null), telefonVar = true))
        assertEquals(RandevuSms.Kip.yok, RandevuSms.kip("ARRIVED", yanit, telefonVar = true))
        assertEquals(RandevuSms.Kip.yok, RandevuSms.kip("CANCELLED", yanit, telefonVar = false))
        // Eski biçim (yalnız `kinds`) yedek.
        assertEquals(
            RandevuSms.Kip.sor("APPOINTMENT_NO_SHOW"),
            RandevuSms.kip("NO_SHOW", SmsKipleri(modes = null, kinds = listOf("APPOINTMENT_NO_SHOW")), telefonVar = true),
        )
        assertEquals(
            SmsKipleri(modes = null, kinds = listOf("APPOINTMENT_CANCELLED")),
            DfyJson.decodeFromString(SmsKipleri.serializer(), """{"items":[],"kinds":["APPOINTMENT_CANCELLED"]}"""),
        )
    }

    /** Ertelenen randevu iptal türüdür (sözleşme §10); kip harfi küçük gelse de tanınır; bilinmeyen kip soru açmaz. */
    @Test
    fun durumTuruVeKipYazimi() {
        val yanit = DfyJson.decodeFromString(
            SmsKipleri.serializer(),
            """{"modes":[{"kind":"APPOINTMENT_CANCELLED","mode":"ask"},{"kind":"APPOINTMENT_NO_SHOW","mode":"OFF"}]}""",
        )
        assertEquals(RandevuSms.Kip.sor("APPOINTMENT_CANCELLED"), RandevuSms.kip("RESCHEDULED", yanit, telefonVar = true))
        assertEquals(RandevuSms.Kip.yok, RandevuSms.kip("NO_SHOW", yanit, telefonVar = true))
        assertEquals("Devam etmek için SMS seçimini yapın.", RandevuSms.secimsizMetni)
        // Bilinmeyen tür adı: "bilgilendirme".
        assertEquals("Hastaya bilgilendirme SMS'i gönderilsin mi?", RandevuSms.soruMetni("BILINMEYEN"))
    }
}
