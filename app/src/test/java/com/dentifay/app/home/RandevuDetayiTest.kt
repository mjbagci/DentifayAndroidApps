package com.dentifay.app.home

import androidx.compose.ui.graphics.Color
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.HekimSecici
import com.dentifay.app.core.TakvimHekimleri
import com.dentifay.app.data.DataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ana ekran ve randevu ayrıntısının saf kuralları — iOS `MobilEkleme7Tests.test2` (randevu eylem kapısı, M-E7.4) ve
 * `OnizlemeTests.test6` (hekim seçicisi: seçili günün listesi, seçim `apiUserId` ile, kısa ad unvanla).
 */
class RandevuDetayiTest {

    /** Durum eylemi oturumun yeteneklerinin HEPSİNİ ister (sunucunun kapısıyla aynı küme); yoksa sönük, işlem yok. */
    @Test
    fun durumEylemiYetkisi() {
        val yalnizDuzenleme = setOf("appt.view", "appt.edit")
        assertFalse("geldi → appt.checkin de gerekir", eylemIzinli(yalnizDuzenleme, StatusAction.arrive))
        assertFalse("gelmedi → appt.noshow de gerekir", eylemIzinli(yalnizDuzenleme, StatusAction.noShow))
        assertTrue(eylemIzinli(yalnizDuzenleme, StatusAction.cancel))
        assertTrue(eylemIzinli(yalnizDuzenleme, StatusAction.confirm))
        assertTrue(eylemIzinli(yalnizDuzenleme + "appt.checkin", StatusAction.arrive))
        assertTrue(eylemIzinli(yalnizDuzenleme + "appt.noshow", StatusAction.noShow))
        assertFalse("alan yoksa fail-closed", eylemIzinli(emptySet(), StatusAction.complete))
    }

    /** Onay başlıkları: iptal ve gelmedi kendi cümlesi; SMS kipi yüzünden pencere açılan öteki eylemlerde "<eylem>?". */
    @Test
    fun onayBasliklari() {
        assertEquals("Randevu iptal edilsin mi?", StatusAction.cancel.confirmTitle)
        assertEquals("Hasta gelmedi olarak işaretlensin mi?", StatusAction.noShow.confirmTitle)
        assertEquals("Hasta geldi?", StatusAction.arrive.confirmTitle)
        assertTrue(StatusAction.cancel.needsConfirmation && StatusAction.noShow.needsConfirmation)
        assertFalse(StatusAction.complete.needsConfirmation)
        assertEquals(
            listOf("CONFIRMED", "ARRIVED", "IN_TREATMENT", "COMPLETED", "NO_SHOW", "CANCELLED"),
            StatusAction.entries.map { it.target },
        )
    }

    /** Kapalı hekim listenin sonunda, soluk; önceki seçim (o gün kapalı olsa da) korunur; seçim yoksa ilk açık, o da yoksa ilk satır. */
    @Test
    fun hekimSecimi() {
        val yanit = DfyJson.decodeFromString(
            TakvimHekimleri.serializer(),
            """{"doctors":[{"id":"m1","userId":"u1","role":"OWNER","user":{"id":"u1","name":"Uzm. Dt. Ayşe Demir","email":"a@x"}}],
                "closedDoctors":[{"id":"m2","userId":"u2","role":"DOCTOR","user":{"id":"u2","name":"Ali Kaya","email":"b@x"}}]}""",
        )
        val liste = DataManager.doktorlar(yanit, listOf(Color.Cyan, Color.Magenta))
        assertEquals(listOf(false, true), liste.map { it.kapali })
        assertEquals("u2", HekimSecici.korunanSecim("u2", liste))
        assertEquals("u1", HekimSecici.korunanSecim(null, liste))
        val hepsiKapali = liste.map { it.copy(kapali = true) }
        assertEquals("u1", HekimSecici.korunanSecim("yok", hepsiKapali))
        // Seçim yoksa giriş yapan hekimin kendisi — sütunu o gün kapalıysa değil; önceki seçim her zaman önce gelir.
        val ikiAcik = liste.map { it.copy(kapali = false) }
        assertEquals("u2", HekimSecici.korunanSecim(null, ikiAcik, kendiId = "u2"))
        assertEquals("u1", HekimSecici.korunanSecim(null, liste, kendiId = "u2"))
        assertEquals("u1", HekimSecici.korunanSecim("u1", ikiAcik, kendiId = "u2"))
        assertEquals("u1", HekimSecici.korunanSecim(null, ikiAcik, kendiId = "resepsiyon"))

        // Çipteki kısa ad: unvan (bileşik olabilir) + ilk ad — ad hiç düşmez.
        assertEquals(listOf("Uzm. Dt. Ayşe", "Dt. Ali"), liste.map { shortName(it.name) })
        assertEquals("Dt. Ayşe", shortName("Dt. Ayşe Demir"))
        assertEquals("Ayşe", shortName("Ayşe Demir"))
        assertEquals("Dt.", shortName("Dt."))
    }
}
