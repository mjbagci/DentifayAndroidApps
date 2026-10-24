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
    /**
     * Kodlu 4xx (M-E8.0): sunucunun `code`u ve cümlesi KORUNUR. Kendi yolu olmayan her kod (bilinmeyenler dahil) yalnız
     * sunucunun cümlesiyle kısa uyarı olur ve HİÇBİR ŞEY kaydedilmiş sayılmaz; 409 sürüm çakışması gibi kendi yolu olanları çağıran yakalar.
     */
    class Kodlu(val httpDurumu: Int, val sunucuKodu: String, val cumle: String?) : ApiException(cumle ?: "İşlem tamamlanamadı.")
    class DecodingError(cause: Throwable) : ApiException("Data error: ${cause.message}", cause)
    class NetworkError(cause: Throwable) : ApiException("Network error: ${cause.message}", cause)

    /** HTTP durumu (`ServerError` · `Kodlu`). */
    val status: Int?
        get() = when (this) {
            is ServerError -> code
            is Kodlu -> httpDurumu
            else -> null
        }

    /** Sunucunun makine kodu (yalnız `Kodlu`). */
    val kod: String? get() = (this as? Kodlu)?.sunucuKodu
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

    /** Saf istek kurucu (Swift `APIClient.istek`): gövde yoksa GET/HEAD'de gövdesiz; `headers` eklenir. */
    fun requestBuilder(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: List<Pair<String, String?>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): Request.Builder {
        val reqBody = when {
            body != null -> DfyJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_MEDIA)
            method == "GET" || method == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(JSON_MEDIA)
        }
        val b = Request.Builder().url(buildUrl(path, query)).method(method, reqBody).header("Content-Type", "application/json")
        headers.forEach { (ad, deger) -> b.header(ad, deger) }
        return b
    }

    /**
     * Hata gövdesinden atılacak hata (saf — test edilebilir). 4xx'te kod varsa `Kodlu` (cümle korunur); kodsuz 403 yetki reddi
     * `ServerError(403, "Bu işlem için yetkiniz yok.")` (M-E7.5); 5xx ve kodsuz öteki durumlar `ServerError`.
     * Kendi yolları: 401 → `Unauthorized` (çağıran), 402/403 kredi → `CreditRequired` (çağıran), 403 kurulum → bekleme ekranı olayı,
     * 403 `PASSWORD_CHANGE_REQUIRED` → şifre ekranı olayı.
     */
    fun hata(status: Int, data: ByteArray): ApiException {
        val kod = serverCode(data)
        if (status == 403) {
            val cumle = hataCumlesi(403, data)   // kurulum kapısı olayı burada (Yetki.yasakMetni)
            if (kod == SifreOlayi.KOD) SifreOlayi.bildir()
            if (kod != null && Yetki.ret(data) != Yetki.Ret.Yetki) return ApiException.Kodlu(403, kod, cumle)
            return ApiException.ServerError(403, cumle)
        }
        if (status in 400..499 && kod != null) return ApiException.Kodlu(status, kod, serverSentence(data))
        return ApiException.ServerError(status, serverSentence(data))
    }

    /**
     * 401 → oturum olayı + `Unauthorized`; 402 (ve demo 403) → `CreditRequired`; 409 hakediş → `HakedisOnayiGerekli`;
     * kara liste → `KaraListeEngeli`; 2xx değilse `hata(...)`. Başarılıysa gövdeyi döndürür.
     */
    fun checkStatus(raw: RawResponse, path: String): ByteArray {
        if (raw.status == 401) {
            SessionEvents.report401(raw.body, path)
            throw ApiException.Unauthorized()
        }
        // 402 = kredi gerekli (her kredili uçta aynı gövde) → tek model, ham JSON gösterilmez.
        CreditRequired.parse(raw.status, raw.body)?.let { throw it }
        // 409 hakediş etkisi onayı → pencere (M-E8.10); öteki 409'lar aşağıda kodlu.
        HakedisEtkisi.ayristir(raw.status, raw.body)?.let { throw it }
        KaraListeEngeli.ayristir(raw.status, raw.body)?.let { throw it }
        if (raw.status !in 200..299) throw hata(raw.status, raw.body)
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
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val req = requestBuilder(method, path, body, query, headers).build()
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
        headers: Map<String, String> = emptyMap(),
    ): T = request(serializer<T>(), method, path, body, query, timeoutSeconds, headers)

    /** `@Serializable` gövdeyi JsonElement'e çevirir. */
    inline fun <reified B> body(value: B): JsonElement = DfyJson.encodeToJsonElement(serializer<B>(), value)

    // MARK: - Auth

    suspend fun login(email: String, password: String): LoginResponse =
        request(method = "POST", path = "/auth/login", body = body(LoginRequest(email, password)))

    suspend fun logout() {
        request<JsonElement>(method = "POST", path = "/auth/logout")
    }

    suspend fun checkSession(): LoginResponse = request(path = "/auth/session")

    /**
     * Kullanıcının aktif üyelikleri — klinik ADI burada döner. `/auth/session` oturum nesnesinde üyelik listesi
     * (dolayısıyla klinik adı) YOKTUR; ekranda marka adı UYDURMAMAK için bu uç okunur.
     */
    suspend fun fetchOrganizations(): List<APIMembership> = request<OrganizationsResponse>(path = "/auth/organizations").organizations

    // MARK: - Appointments

    /**
     * Randevu durumu (`PATCH /appointments/:id`). `notifyPatient` YALNIZ "Sor" kipindeki türde kullanıcının cevabıyla gider;
     * `null` → alan gövdede YOK (otomatik kipte sunucu kendisi gönderir — M-E3.5).
     */
    suspend fun updateAppointmentStatus(id: String, status: String, notifyPatient: Boolean?) {
        request<JsonElement>(method = "PATCH", path = "/appointments/$id", body = durumGovdesi(status, notifyPatient))
    }

    /** `PATCH /appointments/:id` gövdesi — `notifyPatient` null ise kodlanmaz. */
    fun durumGovdesi(status: String, notifyPatient: Boolean?): JsonObject = buildJsonObject {
        put("status", status)
        if (notifyPatient != null) put("notifyPatient", notifyPatient)
    }

    suspend fun fetchAppointments(
        startDate: String,
        endDate: String,
        doctorUserId: String? = null,
        category: String? = null,
    ): List<APIAppointment> {
        val resp: AppointmentsResponse = request(
            path = "/appointments",
            query = listOf("startDate" to startDate, "endDate" to endDate, "doctorUserId" to doctorUserId, "category" to category),
        )
        return resp.appointments
    }

    // MARK: - Doctors / Patients / Inventory

    /** Seçili günün takvim sütunları (`?date=YYYY-MM-DD` → `{doctors, closedDoctors, day}`). Hekim kümesi sunucunun. */
    suspend fun fetchScheduleDoctors(date: String): TakvimHekimleri =
        request(path = "/schedule/doctors", query = listOf("date" to date))

    /** Yeni plan/kalemin hekimi olabilenler (`?atanabilir=1`) — web plan ekranıyla AYNI küme (26E.2). */
    suspend fun fetchAssignableDoctors(): List<APIDoctorMember> =
        request<AtanabilirHekimler>(path = "/schedule/doctors", query = listOf("atanabilir" to "1")).doctors

    suspend fun fetchPatients(): List<APIPatient> = request<PatientsResponse>(path = "/patients").patients

    /** Sunucu sayfa tavanı 200; varsayılan 20 olduğu için açıkça istenir. */
    suspend fun fetchInventory(): InventoryResponse = request(path = "/inventory/items", query = listOf("limit" to "200"))

    suspend fun searchPatients(query: String, page: Int = 1, limit: Int = 20): List<APIPatientWithAppointments> {
        val q = mutableListOf<Pair<String, String?>>("page" to "$page", "limit" to "$limit")
        if (query.isNotEmpty()) q += "search" to query
        return request<PatientSearchResponse>(path = "/patients", query = q).patients
    }

    // MARK: - Bildirimler (`/api/bildirimler` — sözleşme §5; `kanal` YOK, gönderilirse 400)

    fun bildirimListesiSorgusu(page: Int, limit: Int): List<Pair<String, String?>> = listOf("page" to "$page", "limit" to "$limit")
    const val TUMUNU_OKUNDU_YOLU = "/bildirimler/okundu"
    fun tekOkunduYolu(id: String): String = "/bildirimler/$id/okundu"

    suspend fun fetchNotifications(page: Int = 1, limit: Int = 30): APINotificationList =
        request(path = "/bildirimler", query = bildirimListesiSorgusu(page, limit))

    suspend fun fetchNotificationCount(): APINotificationCount = request(path = "/bildirimler/sayac")

    /** Toplu okundu — GÖVDESİZ (gövde → 400). */
    suspend fun markAllNotificationsRead(): APINotificationReadResult = request(method = "POST", path = TUMUNU_OKUNDU_YOLU)

    /** Tek satır okundu (zaten okunmuşsa da 200; 404 → satır listeden düşer). */
    suspend fun markNotificationRead(id: String): APINotificationReadResult = request(method = "POST", path = tekOkunduYolu(id))
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
