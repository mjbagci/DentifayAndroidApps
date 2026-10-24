package com.dentifay.app.hasta

import java.time.LocalDate
import java.time.ZoneId

/** Kurmaca örnek veri (gerçek hasta/personel YOK — M0-8): Tedavi Planı listesi önizlemeleri. */
object OrnekHastaTedavi {
    val bugun: BugunHastalari.Bolumler
        get() {
            val gun = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            fun saat(s: Long, d: Long = 0) = gun.plusHours(s).plusMinutes(d).toInstant()
            val r = listOf(
                BugunHastalari.Randevu(
                    "r1", saat(9), "SCHEDULED", "PATIENT_VISIT", "p1", "Deneme Hasta", "0555 000 00 00", "demo", "Selin Demir", "Kontrol",
                ),
                BugunHastalari.Randevu(
                    "r2", saat(10, 30), "CONFIRMED", "PATIENT_VISIT", "p2", "Örnek Kişi", null, "demo", "Selin Demir", "Dolgu",
                ),
                BugunHastalari.Randevu(
                    "r3", saat(11), "SCHEDULED", "PATIENT_VISIT", "p3", "Deneme İkinci", null, "d2", "Burak Kaya", "Kanal tedavisi",
                ),
                BugunHastalari.Randevu(
                    "r4", saat(14), "SCHEDULED", "PATIENT_VISIT", null, "Misafir Deneme", null, "d2", "Burak Kaya", "Muayene",
                ),
                BugunHastalari.Randevu(
                    "r5", saat(15), "CANCELLED", "PATIENT_VISIT", "p4", "İptal Eden", null, "demo", "Selin Demir", "Kontrol",
                ),
            )
            return BugunHastalari.bolumle(r, kullaniciId = "demo")
        }
}
