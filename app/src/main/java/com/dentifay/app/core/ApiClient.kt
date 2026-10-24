package com.dentifay.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Ortak JSON yapılandırması (Swift `JSONDecoder` + esnek alanlar). */
@OptIn(ExperimentalSerializationApi::class)
val DfyJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** `APIClient.APIError` karşılığı. `message` = Swift `errorDescription`. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidUrl : ApiException("Invalid URL")
    class Unauthorized : ApiException("Session expired. Please login again.")
    class ServerError(val code: Int, val serverMessage: String?) : ApiException(serverMessage ?: "Server error ($code)")
    class DecodingError(cause: Throwable) : ApiException("Data error: ${cause.message}", cause)
    class NetworkError(cause: Throwable) : ApiException("Network error: ${cause.message}", cause)
    /** `POST /ai/tts` → 429 `TTS_DAILY_QUOTA`. */
    class SpeechQuotaExceeded : ApiException("Bugünkü seslendirme hakkı doldu. Metin ekranda gösteriliyor.")
}

/** Ham HTTP sonucu (SSE / çok parçalı / ikili uçlar için). */
class RawResponse(val status: Int, val body: ByteArray, val headers: okhttp3.Headers)

object ApiClient {
    val JSON_MEDIA = "application/json".toMediaType()

    /** Paylaşılan istemci — çerezler `CookieStore`'da (HTTPCookieStorage.shared karşılığı). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieStore)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val baseURL: String get() = AppConfig.apiBaseURL

    // MARK: - Çekirdek istek

    /** OkHttp çağrısını askıya alan biçimde çalıştırır (iptal edilebilir). */
    suspend fun execute(request: Request, client: OkHttpClient = http): RawResponse = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(ApiException.NetworkError(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val bytes = runCatching { r.body?.bytes() ?: ByteArray(0) }.getOrElse {
                        if (cont.isActive) cont.resumeWithException(ApiException.NetworkError(it)); return
                    }
                    cont.resumeWith(Result.success(RawResponse(r.code, bytes, r.headers)))
                }
            }
        })
    }

    fun buildUrl(path: String, query: List<Pair<String, String?>> = emptyList()): String {
        val url = (baseURL + path).toHttpUrlOrNull() ?: throw ApiException.InvalidUrl()
        val b = url.newBuilder()
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    fun requestBuilder(method: String, path: String, body: JsonElement? = null, query: List<Pair<String, String?>> = emptyList()): Request.Builder {
        val reqBody = when {
            body != null -> DfyJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_MEDIA)
            method == "GET" || method == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(JSON_MEDIA)
        }
        return Request.Builder().url(buildUrl(path, query)).method(method, reqBody).header("Content-Type", "application/json")
    }

    /**
     * 401 → oturum olayı + `Unauthorized`; 402 → `CreditRequired`; 2xx değilse `ServerError`
     * (yalnız sunucunun Türkçe cümlesi). Başarılıysa gövdeyi döndürür.
     */
    fun checkStatus(raw: RawResponse, path: String): ByteArray {
        if (raw.status == 401) {
            SessionEvents.report401(raw.body, path)
            throw ApiException.Unauthorized()
        }
        CreditRequired.parse(raw.status, raw.body)?.let { throw it }
        if (raw.status !in 200..299) throw ApiException.ServerError(raw.status, serverSentence(raw.body))
        return raw.body
    }

    /** Genel JSON isteği. `T` = `JsonElement` ise gövde yorumlanmadan döner. */
    suspend fun <T> request(
        deserializer: KSerializer<T>,
        method: String = "GET",
        path: String,
        body: JsonElement? = null,
        query: List<Pair<String, String?>> = emptyList(),
        timeoutSeconds: Long? = null,
    ): T = withContext(Dispatchers.IO) {
        val req = requestBuilder(method, path, body, query).build()
        val client = timeoutSeconds?.let { http.newBuilder().callTimeout(it, TimeUnit.SECONDS).readTimeout(it, TimeUnit.SECONDS).build() } ?: http
        val raw = execute(req, client)
        val data = checkStatus(raw, path)
        try {
            val text = data.toString(Charsets.UTF_8).ifBlank { "{}" }
            DfyJson.decodeFromString(deserializer, text)
        } catch (e: Exception) {
            throw ApiException.DecodingError(e)
        }
    }

    suspend inline fun <reified T> request(
        method: String = "GET",
        path: String,
        body: JsonElement? = null,
        query: List<Pair<String, String?>> = emptyList(),
        timeoutSeconds: Long? = null,
    ): T = request(serializer<T>(), method, path, body, query, timeoutSeconds)

    /** `@Serializable` gövdeyi JsonElement'e çevirir. */
    inline fun <reified B> body(value: B): JsonElement = DfyJson.encodeToJsonElement(serializer<B>(), value)

    // MARK: - Auth

    suspend fun login(email: String, password: String): LoginResponse =
        request(method = "POST", path = "/auth/login", body = body(LoginRequest(email, password)))

    suspend fun logout() {
        request<JsonElement>(method = "POST", path = "/auth/logout")
    }

    suspend fun checkSession(): LoginResponse = request(path = "/auth/session")

    // MARK: - Appointments

    /** Randevu durumu (`PATCH /appointments/:id`); `notifyPatient` hastaya SMS gidip gitmeyeceği. */
    suspend fun updateAppointmentStatus(id: String, status: String, notifyPatient: Boolean) {
        val b = buildJsonObject { put("status", status); put("notifyPatient", notifyPatient) }
        request<JsonElement>(method = "PATCH", path = "/appointments/$id", body = b)
    }

    suspend fun fetchAppointments(startDate: String, endDate: String, doctorUserId: String? = null): List<APIAppointment> {
        val resp: AppointmentsResponse = request(
            path = "/appointments",
            query = listOf("startDate" to startDate, "endDate" to endDate, "doctorUserId" to doctorUserId),
        )
        return resp.appointments
    }

    // MARK: - Doctors / Patients / Inventory

    suspend fun fetchDoctors(): List<APIDoctorMember> = request<DoctorsResponse>(path = "/schedule/doctors").doctors

    suspend fun fetchPatients(): List<APIPatient> = request<PatientsResponse>(path = "/patients").patients

    /** Sunucu sayfa tavanı 200; varsayılan 20 olduğu için açıkça istenir. */
    suspend fun fetchInventory(): InventoryResponse = request(path = "/inventory/items", query = listOf("limit" to "200"))

    suspend fun searchPatients(query: String, page: Int = 1, limit: Int = 20): List<APIPatientWithAppointments> {
        val q = mutableListOf<Pair<String, String?>>("page" to "$page", "limit" to "$limit")
        if (query.isNotEmpty()) q += "search" to query
        return request<PatientSearchResponse>(path = "/patients", query = q).patients
    }

    // MARK: - Bildirimler (`/api/bildirimler`)

    /** Kanal: "INBOX" (bildirimler) ya da "ALERT" (anlık uyarılar). */
    suspend fun fetchNotifications(channel: String, limit: Int = 40): APINotificationList =
        request(path = "/bildirimler", query = listOf("kanal" to channel, "limit" to "$limit"))

    suspend fun fetchNotificationCount(): APINotificationCount = request(path = "/bildirimler/sayac")

    suspend fun markNotificationsRead(channel: String) {
        request<APINotificationReadResult>(method = "POST", path = "/bildirimler/okundu", body = buildJsonObject { put("kanal", channel) })
    }

    // MARK: - TTS

    suspend fun synthesizeSpeech(text: String, language: String = "tr"): ByteArray = withContext(Dispatchers.IO) {
        val req = requestBuilder("POST", "/ai/tts", buildJsonObject { put("text", text); put("language", language) }).build()
        val raw = execute(req, http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build())
        if (raw.status == 401) {
            SessionEvents.report401(raw.body, "/ai/tts")
            throw ApiException.Unauthorized()
        }
        CreditRequired.parse(raw.status, raw.body)?.let { throw it }
        if (raw.status == 429) {
            val code = runCatching { DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)).jsonObject["code"] }.getOrNull()
            if ((code as? JsonPrimitive)?.contentOrNull == "TTS_DAILY_QUOTA") throw ApiException.SpeechQuotaExceeded()
        }
        if (raw.status !in 200..299) throw ApiException.ServerError(raw.status, serverSentence(raw.body))
        raw.body
    }
}

/**
 * Sunucu gövdesinden kullanıcıya gösterilebilecek Türkçe cümle (`message` ya da cümle biçimli `error`).
 * Kod gibi görünen değer ve ham JSON DÖNMEZ.
 */
fun serverSentence(data: ByteArray): String? {
    val json = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
    for (key in listOf("message", "error", "msg")) {
        val s = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        if (s.contains(" ")) {
            val t = s.trim()
            if (t.isNotEmpty()) return t
        }
    }
    return null
}

/** JsonObject yardımcıları (Swift `json["x"] as? String` kalıbı). */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { p ->
    when {
        p is JsonNull -> null
        p.isString -> p.content.toDoubleOrNull()
        p.content == "true" || p.content == "false" -> null   // gerçek mantıksal değer sayı sayılmaz
        else -> p.doubleOrNull
    }
}
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { p ->
    if (p is JsonNull) null else p.booleanOrNull ?: p.doubleOrNull?.let { it != 0.0 }
}
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/**
 * Oturum sonlandırma olayı — herhangi bir uçta `401` gelince yayınlanır; `AuthManager` dinler.
 */
object SessionEvents {
    private val _ended = MutableSharedFlow<String?>(extraBufferCapacity = 8)
    val ended: SharedFlow<String?> = _ended.asSharedFlow()

    @Volatile private var lastCode: String? = null

    /** Son 401'in kodu (bir kez okunur). */
    @Synchronized
    fun takeLastCode(): String? = lastCode.also { lastCode = null }

    /** Giriş isteğinin kendi 401'i (yanlış şifre) bildirilmez. */
    fun report401(data: ByteArray, path: String) {
        if (path.startsWith("/auth/login")) return
        val code = runCatching { (DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject)?.str("code") }.getOrNull()
        synchronized(this) { lastCode = code }
        _ended.tryEmit(code)
    }

    fun message(code: String?): String? = when (code) {
        "SESSION_REPLACED" -> "Hesabınıza başka bir cihazdan giriş yapıldı; bu cihazdaki oturum kapatıldı."
        "SESSION_REVOKED" -> "Oturumunuz yönetici tarafından sonlandırıldı."
        "SESSION_IDLE" -> "12 saattir işlem yapılmadığı için oturum kapatıldı."
        "PASSWORD_CHANGED" -> "Şifreniz başka bir cihazda değiştirildi; yeni şifrenizle giriş yapın."
        "DEMO_EXPIRED" -> "Demo süreniz doldu."
        "ORGANIZATION_SUSPENDED" -> "Kliniğinizin Dentifay hesabı askıya alındı."
        "ORGANIZATION_ARCHIVED" -> "Kliniğinizin Dentifay hesabı kapatıldı."
        else -> null
    }
}

/** Hekim unvanı her yerde "Dt."; "Dr." hiçbir ekranda görünmez. */
object Unvan {
    private val dotted = Regex("""(?<![\p{L}\p{N}])[Dd][Rr]\.(?=\s|$)""")
    private val leading = Regex("""^[Dd][Rr](?=\s)""")
    private val doubled = Regex("""Dt\.\s+Dt\.""")

    fun dt(text: String): String {
        if (!text.contains("dr", ignoreCase = true)) return text
        var out = dotted.replace(text, "Dt.")
        out = leading.replace(out, "Dt.")
        out = doubled.replace(out, "Dt.")
        return out
    }
}
