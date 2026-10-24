package com.dentifay.app.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat
import java.util.Locale

// MARK: - Esnek sayı yardımcıları
// Prisma Decimal alanlarını JSON metni ("1500.00") olarak yazar; Int / Double / metin hepsi kabul edilir.

object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        val p = el as? JsonPrimitive ?: return 0
        if (p is JsonNull) return 0
        return (p.doubleOrNull ?: p.content.toDoubleOrNull())?.toInt() ?: 0
    }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object FlexibleIntOrNullSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleIntOrNull", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int? {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return (p.doubleOrNull ?: p.content.toDoubleOrNull())?.toInt()
    }
    override fun serialize(encoder: Encoder, value: Int?) { if (value == null) encoder.encodeNull() else encoder.encodeInt(value) }
}

object FlexibleDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeDouble()
        val p = el as? JsonPrimitive ?: return 0.0
        if (p is JsonNull) return 0.0
        return p.doubleOrNull ?: p.content.toDoubleOrNull() ?: 0.0
    }
    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

// MARK: - Auth

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val user: APIUser)

@Serializable
data class APIUser(
    val id: String,
    val email: String,
    val name: String,
    val organizationId: String,
    val branchId: String? = null,
    val role: String,
    /** Etkin yetenekler ve menü modülleri; eski sunucu göndermezse null → yetki isteyen ekranlar GİZLİ. */
    val capabilities: List<String>? = null,
    val modules: List<String>? = null,
) {
    /** Arayüz kararı içindir (görünürlük); asıl kapı sunucudadır. */
    fun can(capability: String): Boolean = capabilities?.contains(capability) ?: false
}

// MARK: - Appointments

@Serializable
data class AppointmentsResponse(val appointments: List<APIAppointment> = emptyList())

/**
 * Backend eski `appointmentType` / `isUrgent` alanlarından `procedureLabel` / `procedureCatalog`
 * ve `urgency` enum'una geçti; iki biçim de çözülür (Swift'teki özel `init(from:)` karşılığı).
 */
@Serializable
data class APIAppointment(
    val id: String,
    val patientId: String? = null,
    val doctorUserId: String? = null,
    val roomResource: String? = null,
    val startAt: String,
    val endAt: String,
    val durationMinutes: Int? = null,
    @kotlinx.serialization.SerialName("appointmentType") val legacyType: String? = null,
    val status: String? = null,
    val reason: String? = null,
    val notes: String? = null,
    @kotlinx.serialization.SerialName("isUrgent") val legacyUrgent: Boolean? = null,
    val urgency: String? = null,
    val cancellationReason: String? = null,
    val guestFirstName: String? = null,
    val guestLastName: String? = null,
    val guestPhone: String? = null,
    val patient: APIPatientBrief? = null,
    val doctor: APIDoctorBrief? = null,
    @kotlinx.serialization.SerialName("healthAgency") val healthAgencyRaw: JsonElement? = null,
    @kotlinx.serialization.SerialName("contractedInstitution") val contractedInstitutionRaw: JsonElement? = null,
    val procedureLabel: String? = null,
    val procedureCustomLabel: String? = null,
    val procedureCatalog: APIProcedureCatalogBrief? = null,
) {
    val isUrgent: Boolean get() = legacyUrgent ?: (urgency?.let { it != "NONE" } ?: false)
    val appointmentType: String? get() = legacyType ?: procedureCustomLabel ?: procedureLabel ?: procedureCatalog?.name
    val healthAgency: APINamedRef? get() = APINamedRef.from(healthAgencyRaw)
    val contractedInstitution: APINamedRef? get() = APINamedRef.from(contractedInstitutionRaw)
}

/** `{ id, name }` biçimli ilişki (sağlık ajansı, anlaşmalı kurum). */
@Serializable
data class APINamedRef(val id: String, val name: String? = null) {
    companion object {
        fun from(el: JsonElement?): APINamedRef? {
            val o = el as? JsonObject ?: return null
            val id = o.str("id") ?: return null
            return APINamedRef(id, o.str("name"))
        }
    }
}

@Serializable
data class APIProcedureCatalogBrief(val id: String, val name: String? = null)

@Serializable
data class APIPatientBrief(val id: String, val firstName: String, val lastName: String, val phone: String? = null)

@Serializable
data class APIDoctorBrief(val id: String, val name: String? = null)

// MARK: - Doctors

@Serializable
data class DoctorsResponse(val doctors: List<APIDoctorMember> = emptyList())

@Serializable
data class APIDoctorMember(val id: String, val userId: String, val role: String, val user: APIDoctorUser)

@Serializable
data class APIDoctorUser(val id: String, val name: String? = null, val email: String)

// MARK: - Patients

@Serializable
data class PatientsResponse(val patients: List<APIPatient> = emptyList())

@Serializable
data class APIPatient(
    val id: String,
    val firstName: String,
    val lastName: String,
    val phone: String? = null,
    val email: String? = null,
    val birthDate: String? = null,
    val gender: String? = null,
    val acquisitionChannel: String? = null,
    val referralSource: String? = null,
    val primaryDoctor: APIPrimaryDoctor? = null,
    val financeSummary: APIFinanceSummary? = null,
)

@Serializable
data class APIPrimaryDoctor(val id: String, val role: String, val user: APIDoctorNameOnly)

@Serializable
data class APIDoctorNameOnly(val id: String, val name: String? = null)

@Serializable
data class APIFinanceSummary(
    @Serializable(with = FlexibleDoubleSerializer::class) val totalBilled: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val totalPaid: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val remaining: Double = 0.0,
)

// MARK: - Inventory

@Serializable
data class InventoryResponse(val items: List<APIInventoryItem> = emptyList(), val stats: InventoryStats? = null)

/** Web Stok Yönetimi özet kartlarıyla aynı sayılar. */
@Serializable
data class InventoryStats(val totalItems: Int? = null, val lowStock: Int? = null, val outOfStock: Int? = null)

@Serializable
data class APIInventoryItem(
    val id: String,
    val categoryId: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val name: String,
    val description: String? = null,
    val unit: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val vatRate: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class) val minLevel: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class) val currentStock: Int = 0,
    @Serializable(with = FlexibleIntOrNullSerializer::class) val cost: Int? = null,
    val category: APICategory? = null,
)

@Serializable
data class APICategory(val id: String, val name: String, val isActive: Boolean = true)

// MARK: - Patient Search

@Serializable
data class PatientSearchResponse(val patients: List<APIPatientWithAppointments> = emptyList(), val pagination: APIPagination? = null)

@Serializable
data class APIPatientWithAppointments(
    val id: String,
    val firstName: String,
    val lastName: String,
    val phone: String? = null,
    val email: String? = null,
    val appointments: List<APIPatientAppointmentBrief>? = null,
    val primaryDoctor: APIPrimaryDoctor? = null,
)

@Serializable
data class APIPatientAppointmentBrief(val startAt: String, val status: String? = null)

@Serializable
data class APIPagination(val page: Int, val limit: Int, val total: Int, val pages: Int)

// MARK: - Bildirimler

@Serializable
data class APINotificationList(val items: List<APINotification> = emptyList(), val unread: Int? = null)

@Serializable
data class APINotificationCount(val alert: Int = 0, val inbox: Int = 0)

@Serializable
data class APINotificationReadResult(val ok: Boolean? = null)

/** Sunucu satırı METİN taşımaz, veri taşır; başlık/gövde istemcide türden kurulur. */
@Serializable
data class APINotification(
    val id: String,
    val channel: String? = null,
    val type: String,
    val count: Int? = null,
    val params: Map<String, JsonElement>? = null,
    val patient: Patient? = null,
    val patientMasked: Boolean? = null,
    val actorName: String? = null,
    val task: TaskRef? = null,
    val survey: Survey? = null,
    val readAt: String? = null,
    val lastEventAt: String? = null,
    val createdAt: String? = null,
) {
    @Serializable data class Patient(val name: String? = null, val archived: Boolean? = null)
    @Serializable data class TaskRef(val title: String? = null)
    @Serializable data class Survey(val question: String? = null, val doctorName: String? = null)

    val isUnread: Boolean get() = readAt == null

    /** Swift `params: [String: APIFlexibleValue]` karşılığı. */
    fun param(key: String): APIFlexibleValue? = params?.get(key)?.let { APIFlexibleValue.from(it) }
}

/** Sayı ya da metin olabilen JSON değeri (bildirim parametreleri). */
sealed class APIFlexibleValue {
    data class Number(val value: Double) : APIFlexibleValue()
    data class Text(val value: String) : APIFlexibleValue()
    data object Other : APIFlexibleValue()

    val display: String?
        get() = when (this) {
            is Number -> NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).apply { maximumFractionDigits = 2 }.format(value)
            is Text -> value
            Other -> null
        }

    companion object {
        fun from(el: JsonElement): APIFlexibleValue {
            val p = el as? JsonPrimitive ?: return Other
            if (p is JsonNull) return Other
            if (!p.isString) p.doubleOrNull?.let { return Number(it) }
            return if (p.isString) Text(p.content) else Other
        }
    }
}
