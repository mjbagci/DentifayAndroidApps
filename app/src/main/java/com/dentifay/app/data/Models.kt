package com.dentifay.app.data

import androidx.compose.ui.graphics.Color
import com.dentifay.app.theme.DS
import java.util.UUID

data class Doctor(
    val name: String,
    val specialty: String,
    val colorAccent: Color,
    val initials: String,
    val apiUserId: String = "",
    val id: String = UUID.randomUUID().toString(),
)

/** Eşitlik yalnız `id` ile (Swift `==` gibi). */
class Appointment(
    val patientName: String = "",
    val treatment: String = "",
    val time: String = "",
    val room: String = "",
    val accentColor: Color = DS.Colors.cyan400,
    val icon: String = "cross.case.fill",
    val note: String = "",
    val isBreak: Boolean = false,
    val patientId: String = "",
    val doctorName: String = "Dt. Selin",
    val clinicalNotes: String = "",
    val agency: String = "",
    val institution: String = "",
    val appointmentStatus: String = "Planlandı",
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val durationMinutes: Int = 60,
    val apiId: String = "",
    /** Sunucu durum kodu (SCHEDULED, ARRIVED …) — durum geçişleri bununla seçilir. */
    val statusCode: String = "SCHEDULED",
    val phone: String = "",
    val reason: String = "",
    val endTime: String = "",
    val isGuest: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
) {
    val patientInitials: String
        get() = patientName.split(" ").filter { it.isNotEmpty() }.take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("")

    override fun equals(other: Any?): Boolean = other is Appointment && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class CalendarDay(val dayName: String, val dayNumber: Int, val isSelected: Boolean, val id: String = UUID.randomUUID().toString())

/** Stok durumu — web ile aynı eşikler: ≤0 tükendi, ≤min düşük, >2×min yeterli, arası normal. */
enum class StockStatus {
    out, low, normal, ample;

    val label: String
        get() = when (this) {
            out -> "TÜKENDİ"; low -> "DÜŞÜK"; normal -> "NORMAL"; ample -> "YETERLİ"
        }

    val icon: String
        get() = when (this) {
            out -> "xmark.circle.fill"; low -> "exclamationmark.triangle.fill"
            normal -> "archivebox.fill"; ample -> "checkmark.circle.fill"
        }

    val color: Color
        get() = when (this) {
            out -> DS.Colors.danger; low -> DS.Colors.warning; normal -> DS.Colors.slate400; ample -> DS.Colors.success
        }

    val bgColor: Color get() = color.copy(alpha = if (this == normal) 0.08f else 0.12f)

    /** Dikkat isteyen durum (tükendi / düşük). */
    val needsAttention: Boolean get() = this == out || this == low

    companion object {
        fun of(current: Int, minimum: Int): StockStatus = when {
            current <= 0 -> out
            current <= minimum -> low
            current > minimum * 2 -> ample
            else -> normal
        }
    }
}

data class StockItem(
    val name: String,
    val barcode: String,
    val sku: String = "",
    val category: String = "",
    val unit: String = "",
    val currentStock: Int,
    val minimumStock: Int,
    val icon: String,
    val id: String = UUID.randomUUID().toString(),
) {
    val status: StockStatus get() = StockStatus.of(currentStock, minimumStock)
}

data class VoiceNote(
    val procedure: String,
    val toothNumber: String,
    val category: String,
    val icon: String,
    val accentColor: Color,
    val id: String = UUID.randomUUID().toString(),
)

object MockData {
    val doctors: List<Doctor> = listOf(
        Doctor("Dt. Selin", "PROSTHO", DS.Colors.cyan400, "SD"),
        Doctor("Dt. Ahmet", "ORTHO", DS.Colors.cyan400, "AK"),
        Doctor("Dt. Mehmet", "SURGEON", DS.Colors.secondary, "MY"),
        Doctor("Dt. Zeynep", "ENDO", DS.Colors.tertiaryContainer, "ZD"),
        Doctor("Dt. Can", "PERIO", DS.Colors.orange400, "CA"),
    )

    val calendarDays: List<CalendarDay> = listOf(
        CalendarDay("MON", 15, false), CalendarDay("TUE", 16, false), CalendarDay("WED", 17, true),
        CalendarDay("THU", 18, false), CalendarDay("FRI", 19, false), CalendarDay("SAT", 20, false),
    )

    val appointments: List<Appointment> = listOf(
        Appointment(
            patientName = "Selin Yilmaz", treatment = "Dental Implant", time = "09:00", room = "ROOM 4",
            accentColor = DS.Colors.cyan400, icon = "mouth.fill",
            note = "Pre-op görüntüleme tamamlandı. Üst çene sol bölge implant bugün yapılacak. CBCT sonuçlarına göre sinus lift gerekmiyor, kemik yoğunluğu yeterli.",
            patientId = "PT-72301", doctorName = "Dt. Selin",
            clinicalNotes = "Hasta üst çene sol bölge implant planlama için başvurdu. CBCT taraması tamamlandı. Kemik yoğunluğu yeterli, sinus lift gerekmiyor. İmplant yerleşimi için cerrahi kılavuz hazırlandı.",
            institution = "SGK (Sosyal Güvenlik Kurumu)", appointmentStatus = "Tedavide",
            startHour = 9, startMinute = 0, durationMinutes = 90,
        ),
        Appointment(
            patientName = "Menderes Tepecik", treatment = "Protez", time = "10:00", room = "ROOM 2",
            accentColor = DS.Colors.error, icon = "cross.case.fill",
            note = "HAS TALANMASI SEBEBİYLE İPTALİ SAĞLANDI İYİ OLDUĞUNDA KENDİSİ ARAYACAK. Alt çene total protez yenileme planlanmıştı.",
            patientId = "PT-91023", doctorName = "Dt. Ahmet",
            clinicalNotes = "Alt çene total protez yenileme. Hasta talanması sebebiyle iptal edildi. İyileşince kendisi arayacak.",
            institution = "", appointmentStatus = "İptal", startHour = 10, startMinute = 0, durationMinutes = 60,
        ),
        Appointment(
            patientName = "Can Aydin", treatment = "Annual Checkup", time = "11:00", room = "ROOM 3",
            accentColor = DS.Colors.secondary, icon = "heart.text.clipboard.fill",
            note = "Yıllık kontrol, allerji geçmişi mevcut. 26 numaralı dişte hafif çürük tespit edilmişti geçen seansta, dolgu planlanacak.",
            patientId = "PT-84920", doctorName = "Dt. Selin",
            clinicalNotes = "Yıllık kontrol. Genel oral hijyen iyi durumda. 26 numaralı dişte hafif çürük tespit edildi, dolgu planlanacak. Diş taşı temizliği yapıldı.",
            agency = "Agency - MedTour", institution = "Özel Sağlık Sigortası", appointmentStatus = "Planlandı",
            startHour = 11, startMinute = 0, durationMinutes = 60,
        ),
        Appointment(
            patientName = "Zübeyde Kırlı", treatment = "Cerrahi", time = "11:00", room = "ROOM 1",
            accentColor = DS.Colors.tertiaryContainer, icon = "scissors",
            note = "20 yaş diş çekimi planlandı, panoramik röntgen çekildi.",
            patientId = "PT-44821", doctorName = "Dt. Mehmet",
            clinicalNotes = "Alt çene sağ 20 yaş diş çekimi. Panoramik röntgen çekildi, kök yapısı normal.",
            institution = "SGK", appointmentStatus = "Planlandı", startHour = 11, startMinute = 0, durationMinutes = 60,
        ),
        Appointment(isBreak = true, startHour = 12, startMinute = 0, durationMinutes = 60),
        Appointment(
            patientName = "Mert Demir", treatment = "Konsültasyon", time = "13:30", room = "VIP",
            accentColor = DS.Colors.orange400, icon = "stethoscope",
            note = "Estetik gülüş tasarımı ilk konsültasyon. Dijital gülüş analizi yapılacak, hasta veneer seçenekleri hakkında bilgi almak istiyor.",
            patientId = "PT-55410", doctorName = "Dt. Selin",
            clinicalNotes = "İlk konsültasyon. Hasta estetik gülüş tasarımı hakkında bilgi almak istiyor. Dijital gülüş analizi yapılacak.",
            institution = "", appointmentStatus = "Planlandı", startHour = 13, startMinute = 30, durationMinutes = 45,
        ),
        Appointment(
            patientName = "Emel Tokluoglu", treatment = "Kontrol", time = "15:00", room = "ROOM 1",
            accentColor = DS.Colors.cyan400, icon = "cross.case.fill",
            note = "ASAF HOCANIN ESKİ HASTASI. Kontrol randevusu, geçen ay yapılan kanal tedavisi sonrası kontrol.",
            patientId = "PT-33019", doctorName = "Dt. Zeynep",
            clinicalNotes = "Kanal tedavisi kontrol. Geçen ay yapılan tedavi sonrası iyileşme kontrolü.",
            institution = "SGK", appointmentStatus = "Planlandı", startHour = 15, startMinute = 0, durationMinutes = 60,
        ),
        Appointment(
            patientName = "Cahide Gönül", treatment = "Muayene", time = "15:00", room = "ROOM 3",
            accentColor = DS.Colors.tertiaryContainer, icon = "stethoscope",
            note = "İMPLANT İÇİN GÖRÜŞMEK İSTİYOR. MUAYENE-ASAF BEY YÖNLENDİRDİ.",
            patientId = "PT-78442", doctorName = "Dt. Ahmet",
            clinicalNotes = "İmplant konsültasyonu. Asaf Bey tarafından yönlendirildi.",
            institution = "", appointmentStatus = "Planlandı", startHour = 15, startMinute = 0, durationMinutes = 30,
        ),
        Appointment(
            patientName = "Ayse Kara", treatment = "Kanal Tedavisi", time = "16:00", room = "ROOM 1",
            accentColor = DS.Colors.orange400, icon = "cross.case.fill",
            note = "Kanal tedavisi takip seansı. 39 numaralı dişe yapılan kanal tedavisi kontrol edilecek. Geçici dolgu çıkarılıp kalıcı dolgu uygulanacak.",
            patientId = "PT-63287", doctorName = "Dt. Zeynep",
            clinicalNotes = "Kanal tedavisi takip seansı. 39 numaralı dişe yapılan kanal tedavisi kontrol edilecek.",
            institution = "SGK (Sosyal Güvenlik Kurumu)", appointmentStatus = "Planlandı",
            startHour = 16, startMinute = 0, durationMinutes = 60,
        ),
        Appointment(
            patientName = "Gülin Özatik", treatment = "Cerrahi", time = "16:00", room = "ROOM 2",
            accentColor = DS.Colors.secondary, icon = "scissors",
            note = "24 NO EX. Cerrahi diş çekimi planlandı, panoramik röntgen hazır.",
            patientId = "PT-50192", doctorName = "Dt. Mehmet",
            clinicalNotes = "24 numaralı diş cerrahi çekimi. Panoramik röntgen çekildi.",
            institution = "", appointmentStatus = "Planlandı", startHour = 16, startMinute = 0, durationMinutes = 60,
        ),
        Appointment(
            patientName = "Rabia Patır", treatment = "Periodontoloji", time = "17:30", room = "ROOM 3",
            accentColor = DS.Colors.error, icon = "cross.case.fill",
            note = "UZMAN DT+MUAYENE SABAH YAPILAN DOLGU ÜCRETİ ÖDEMESİNİ YAPACAK. Periodontoloji konsültasyonu da planlandı.",
            patientId = "PT-29571", doctorName = "Dt. Can",
            clinicalNotes = "Periodontoloji konsültasyonu. Diş eti tedavisi planlanacak. Sabah yapılan dolgu ücreti ödemesi yapılacak.",
            institution = "SGK", appointmentStatus = "Planlandı", startHour = 17, startMinute = 30, durationMinutes = 30,
        ),
    )

    val stockItems: List<StockItem> = listOf(
        StockItem("Alginate Precision-X", "88402931-A", currentStock = 12, minimumStock = 50, icon = "cross.vial.fill"),
        StockItem("Nitrile Sterile Gloves", "90214455-G", currentStock = 420, minimumStock = 100, icon = "hand.raised.fill"),
        StockItem("Titanium Fixtures v4", "11029384-T", currentStock = 84, minimumStock = 65, icon = "wrench.and.screwdriver.fill"),
        StockItem("Saline 500ml", "55430012-S", currentStock = 156, minimumStock = 40, icon = "drop.fill"),
        StockItem("Syringe 5ml", "66723901-L", currentStock = 42, minimumStock = 40, icon = "syringe.fill"),
        StockItem("Composite Resin A2", "77881234-C", currentStock = 28, minimumStock = 20, icon = "paintbrush.fill"),
    )

    val voiceNotes: List<VoiceNote> = listOf(
        VoiceNote("Implant", "38", "DENTAL PROCEDURE", "mouth.fill", DS.Colors.cyan400),
        VoiceNote("Root Canal", "39", "SURGERY", "cross.case.fill", DS.Colors.secondary),
    )
}
