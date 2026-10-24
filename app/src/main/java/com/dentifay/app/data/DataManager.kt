package com.dentifay.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.dentifay.app.core.APIAppointment
import com.dentifay.app.core.APIInventoryItem
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.HekimSecici
import com.dentifay.app.core.InventoryStats
import com.dentifay.app.core.TakvimHekimleri
import com.dentifay.app.core.Yetki
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.upperTr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** SwiftUI `@Environment(DataManager.self)` karşılığı. */
val LocalDataManager = staticCompositionLocalOf { DataManager() }

class DataManager {
    var appointments by mutableStateOf<List<Appointment>>(emptyList())
    /** Seçili günün hekimleri: önce açık sütunlar, sonra o gün kapalı olanlar (`kapali`). */
    var doctors by mutableStateOf<List<Doctor>>(emptyList())
    var stockItems by mutableStateOf<List<StockItem>>(emptyList())
    var isLoadingAppointments by mutableStateOf(false)
    var isLoadingDoctors by mutableStateOf(false)
    var isLoadingStock by mutableStateOf(false)
    var hasLoadedStock by mutableStateOf(false)
    var stockStats by mutableStateOf<InventoryStats?>(null)
    var stockLoadFailed by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    private val doctorColors = listOf(
        DS.Colors.cyan400, DS.Colors.secondary, DS.Colors.tertiaryContainer,
        DS.Colors.orange400, DS.Colors.error, DS.Colors.purple500,
    )

    private val typeIcons = mapOf(
        "TREATMENT" to "cross.case.fill",
        "CONSULTATION" to "stethoscope",
        "CONTROL" to "heart.text.clipboard.fill",
        "SURGERY" to "scissors",
        "EMERGENCY" to "exclamationmark.triangle.fill",
    )

    private val typeColors = mapOf(
        "TREATMENT" to DS.Colors.cyan400,
        "CONSULTATION" to DS.Colors.orange400,
        "CONTROL" to DS.Colors.secondary,
        "SURGERY" to DS.Colors.tertiaryContainer,
        "EMERGENCY" to DS.Colors.error,
    )

    /** Kart vurgusu DURUMA göre (web takvimi gibi). */
    private val statusColors: Map<String, Color> = mapOf(
        "SCHEDULED" to hex("31bed4"),
        "CONFIRMED" to hex("40bc8f"),
        "ARRIVED" to hex("3db9aa"),
        "IN_TREATMENT" to hex("b885eb"),
        "COMPLETED" to hex("94a3b8"),
        "CANCELLED" to hex("e87586"),
        "NO_SHOW" to hex("e0b034"),
        "RESCHEDULED" to hex("ddb927"),
    )

    companion object {
        private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** Gün içi dakikayı "HH:mm" yazar (gece yarısını aşarsa sarar). */
        fun clock(minutesOfDay: Int): String {
            val v = ((minutesOfDay % 1440) + 1440) % 1440
            return String.format(Locale.US, "%02d:%02d", v / 60, v % 60)
        }

        /** Baş harfler — "Dt." / "Prof." gibi unvanlar atlanır ("Dt. Ayşe Demir" → "AD"). */
        fun initials(name: String): String {
            val unvanlar = setOf("dr", "dr.", "dt", "dt.", "prof", "prof.", "doç", "doç.", "uzm", "uzm.")
            return name.split(" ").filter { it.isNotEmpty() && it.lowercase() !in unvanlar }
                .take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").upperTr()
        }

        fun parseIso(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

        /** Saf dönüşüm (test edilebilir): açıklar önce, kapalılar sonra; renk sırayla. */
        fun doktorlar(yanit: TakvimHekimleri, renkler: List<Color>): List<Doctor> =
            HekimSecici.liste(yanit).mapIndexed { index, satir ->
                val color = if (renkler.isEmpty()) DS.Colors.cyan400 else renkler[index % renkler.size]
                val name = Unvan.hekimAdi(satir.uye.user.name)
                Doctor(name, color, initials(name), satir.uye.userId, kapali = satir.kapali)
            }
    }

    // MARK: - Preload

    /** Önce hekimler (ilk randevu isteği tek hekime — hekimse kendisine — daraltılır), sonra stok + randevular paralel. */
    suspend fun preloadHomeData(kendiId: String? = null) {
        fetchDoctors(LocalDate.now())
        coroutineScope {
            val inv = async { fetchInventory() }
            val firstDoctorId = HekimSecici.korunanSecim(null, doctors, kendiId)
            val appts = firstDoctorId?.let { id -> async { fetchAppointments(LocalDate.now(), id) } }
            inv.await(); appts?.await()
        }
    }

    // MARK: - Doctors

    /**
     * Ana ekranın hekim seçicisi: SEÇİLİ günün takvim sütunları (`?date=`). İstemci rol süzgeci YOK — sahibi hekim olan
     * muayenehanede de liste dolar; küme sunucunundur (D12-17).
     */
    suspend fun fetchDoctors(date: LocalDate) {
        isLoadingDoctors = true
        try {
            val yanit = ApiClient.fetchScheduleDoctors(dateFormatter.format(date))
            doctors = doktorlar(yanit, doctorColors)
        } catch (e: CancellationException) {
            isLoadingDoctors = false
            throw e
        } catch (e: Exception) {
            // Kodsuz 403 (yetki reddi) hata değildir: parça boş kalır (M-E7.5).
            if (!Yetki.reddiMi(e)) errorMessage = e.message
        }
        isLoadingDoctors = false
    }

    // MARK: - Appointments

    private var lastAppointmentQuery: Pair<LocalDate, String?>? = null

    /** Son açılan gün / hekim için listeyi yeniden çeker (durum değişikliğinden sonra). */
    suspend fun refreshAppointments() {
        val q = lastAppointmentQuery ?: return
        fetchAppointments(q.first, q.second)
    }

    suspend fun fetchAppointments(date: LocalDate, doctorUserId: String? = null) {
        lastAppointmentQuery = date to doctorUserId
        isLoadingAppointments = true
        try {
            val apiAppts = ApiClient.fetchAppointments(dateFormatter.format(date), dateFormatter.format(date.plusDays(1)), doctorUserId)
            appointments = apiAppts.mapNotNull { convertAppointment(it) }.sortedBy { it.startHour * 60 + it.startMinute }
        } catch (e: CancellationException) {
            isLoadingAppointments = false
            throw e
        } catch (e: Exception) {
            if (!Yetki.reddiMi(e)) errorMessage = e.message
        }
        isLoadingAppointments = false
    }

    private fun convertAppointment(api: APIAppointment): Appointment? {
        val start = parseIso(api.startAt)?.atZone(ZoneId.systemDefault()) ?: return null
        val hour = start.hour
        val minute = start.minute
        val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)

        val duration = api.durationMinutes
            ?: parseIso(api.endAt)?.let { end -> maxOf(30, ((end.epochSecond - start.toEpochSecond()) / 60).toInt()) }
            ?: 60

        val patientName = when {
            api.patient != null -> "${api.patient.firstName} ${api.patient.lastName}"
            api.guestFirstName != null -> "${api.guestFirstName} ${api.guestLastName ?: ""}"
            else -> "İsimsiz hasta"
        }

        val hekim = Unvan.hekimAdi(api.doctor?.name)
        val doctorName = hekim.ifEmpty { "—" }
        val appointmentType = api.appointmentType ?: "TREATMENT"
        val icon = typeIcons[appointmentType] ?: "cross.case.fill"
        val status = (api.status ?: "").uppercase()
        val accentColor = statusColors[status] ?: typeColors[appointmentType] ?: DS.Colors.cyan400

        val statusLabel = when (status) {
            "SCHEDULED" -> "Planlandı"
            "CONFIRMED" -> "Onaylandı"
            "ARRIVED" -> "Geldi"
            "IN_TREATMENT" -> "Tedavide"
            "COMPLETED" -> "Tamamlandı"
            "CANCELLED" -> "İptal"
            "NO_SHOW" -> "Gelmedi"
            "RESCHEDULED" -> "Ertelendi"
            else -> "Planlandı"
        }

        val treatmentLabel = randevuTuruEtiketi(appointmentType)

        return Appointment(
            patientName = patientName,
            treatment = treatmentLabel,
            time = timeStr,
            room = (api.roomResource ?: "").trim(),
            accentColor = accentColor,
            icon = icon,
            note = api.notes ?: api.reason ?: "",
            patientId = api.patient?.id ?: "",
            doctorName = doctorName,
            clinicalNotes = api.notes ?: "",
            agency = (api.healthAgency?.name ?: "").trim(),
            institution = (api.contractedInstitution?.name ?: "").trim(),
            appointmentStatus = statusLabel,
            startHour = hour,
            startMinute = minute,
            durationMinutes = duration,
            apiId = api.id,
            statusCode = (api.status ?: "SCHEDULED").uppercase(),
            phone = (api.patient?.phone ?: api.guestPhone ?: "").trim(),
            reason = (api.reason ?: "").trim(),
            endTime = clock(hour * 60 + minute + duration),
            isGuest = api.patient == null,
        )
    }

    // MARK: - Inventory

    suspend fun fetchInventory() {
        isLoadingStock = true
        try {
            val resp = ApiClient.fetchInventory()
            stockItems = resp.items.map { convertStockItem(it) }
            stockStats = resp.stats
            stockLoadFailed = false
            hasLoadedStock = true
        } catch (e: CancellationException) {
            isLoadingStock = false
            throw e
        } catch (e: Exception) {
            if (Yetki.reddiMi(e)) {
                // Kodsuz 403: kırmızı "yüklenemedi · tekrar dene" kartı yok — boş durum (M-E7.5).
                stockLoadFailed = false
                hasLoadedStock = true
            } else {
                stockLoadFailed = true
                errorMessage = e.message
            }
        }
        isLoadingStock = false
    }

    private fun convertStockItem(api: APIInventoryItem): StockItem {
        val category = api.category?.name?.trim() ?: ""
        return StockItem(
            name = api.name,
            barcode = (api.barcode ?: "").trim(),
            sku = (api.sku ?: "").trim(),
            category = category,
            unit = api.unit ?: "",
            currentStock = api.currentStock,
            minimumStock = api.minLevel,
            icon = stockIconForCategory(api.name),
        )
    }

    private fun stockIconForCategory(itemName: String): String {
        val lower = itemName.lowercase(Locale.forLanguageTag("tr-TR"))
        return when {
            lower.contains("eldiven") || lower.contains("glove") -> "hand.raised.fill"
            lower.contains("enjektör") || lower.contains("syringe") -> "syringe.fill"
            lower.contains("implant") -> "wrench.and.screwdriver.fill"
            lower.contains("maske") || lower.contains("mask") -> "facemask.fill"
            lower.contains("kompozit") || lower.contains("dolgu") -> "paintbrush.fill"
            lower.contains("kanal") -> "cross.vial.fill"
            lower.contains("alginat") || lower.contains("silikon") || lower.contains("ölçü") -> "cross.vial.fill"
            lower.contains("dezenfektan") -> "drop.fill"
            lower.contains("kağıt") || lower.contains("dosya") || lower.contains("kalem") -> "doc.fill"
            else -> "archivebox.fill"
        }
    }
}
