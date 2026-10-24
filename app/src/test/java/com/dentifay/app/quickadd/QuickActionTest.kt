package com.dentifay.app.quickadd

import com.dentifay.app.core.Yetki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `MobilEkleme3Tests.test7` (hızlı işlem kısmı, M-E3.6) + "Yeni randevu" yerli satırı (M-E8.13). */
class QuickActionTest {

    /** Yeteneği olmayan satır çizilmez; sıra sabit; alan yoksa (boş küme) hiçbir satır yok — fail-closed. */
    @Test
    fun satirlarYetenektenSuzulur() {
        assertEquals(listOf(QuickAction.xray), QuickAction.entries.filter { Yetki.gorur(setOf("ai.xray"), it.yuzey) })
        assertEquals(listOf(QuickAction.xray), QuickAction.satirlar(setOf("ai.xray")))
        assertTrue(QuickAction.satirlar(emptySet()).isEmpty())
        assertEquals(listOf(QuickAction.appointment), QuickAction.satirlar(setOf("appt.view", "appt.create")))
        assertTrue("randevuyu yalnız görebilen ekleyemez", QuickAction.satirlar(setOf("appt.view")).isEmpty())
        val hepsi = Yetki.Yuzey.entries.map { it.yetenek }.toSet()
        assertEquals(QuickAction.entries.toList(), QuickAction.satirlar(hepsi))
    }

    /** Her satırın yüzeyi (sekme ve Analiz Merkezi kartlarıyla AYNI yetenek adları). */
    @Test
    fun yuzeyEslemesi() {
        assertEquals(Yetki.Yuzey.rontgen, QuickAction.xray.yuzey)
        assertEquals(Yetki.Yuzey.gulus, QuickAction.smile.yuzey)
        assertEquals(Yetki.Yuzey.tedaviPlani, QuickAction.treatmentPlan.yuzey)
        assertEquals(Yetki.Yuzey.asistan, QuickAction.assistant.yuzey)
        assertEquals(Yetki.Yuzey.yeniRandevu, QuickAction.appointment.yuzey)
        assertEquals("appt.create", QuickAction.appointment.yuzey.yetenek)
        assertEquals("chart.plan.edit", QuickAction.treatmentPlan.yuzey.yetenek)
    }
}
