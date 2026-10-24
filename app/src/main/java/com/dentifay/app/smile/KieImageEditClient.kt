package com.dentifay.app.smile

import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.DfyJson
import com.dentifay.app.credit.SmileCreditFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * kie.ai görüntü düzenleme istemcisi.
 *
 * Akış: kırpım JPEG'ini geçici depoya yükle → `createTask` → `recordInfo`
 * ile tamamlanana kadar yokla → sonuç görselini indir.
 *
 * İki yol var (`Endpoint`): geliştirmede doğrudan kie.ai (anahtar cihazda,
 * Secrets.properties), canlıda Dentifay sunucusu üzerinden (`/api/ai/smile/...`;
 * anahtar yalnızca sunucuda, istek oturum çereziyle yetkilendirilir). İstek ve
 * yanıt biçimleri iki yolda aynıdır; sunucu kie.ai yanıtını olduğu gibi döner.
 * Sunucu yolunda her görev kliniğin AI kredisinden düşer (ilk 30 kredi
 * ücretsiz); kredi bitince sunucu 402 `INSUFFICIENT_CREDIT` döner, başarısız
 * görevin kredisi iade edilir, otomatik yerleşim düzeltmesi ücretsizdir.
 */
class KieImageEditClient(val endpoint: Endpoint) {

    constructor(apiKey: String) : this(Endpoint.direct(apiKey))

    enum class Stage { uploading, queued, generating, downloading }

    /** İsteklerin gideceği yer. */
    sealed class Endpoint {
        /** Doğrudan kie.ai — yalnızca geliştirme (anahtar cihazda). */
        data class direct(val apiKey: String) : Endpoint()
        /** Dentifay sunucusu üzerinden — canlı kullanım (anahtar sunucuda). */
        data class proxy(val baseURL: String) : Endpoint()
        /**
         * Giriş ekranındaki ücretsiz deneme (`/api/public/trial/smile`) — oturum yok,
         * IP başına hak; istek biçimi sunucu yoluyla aynı.
         */
        data class guest(val baseURL: String) : Endpoint()
    }

    sealed class KieError : Exception() {
        object missingAPIKey : KieError()
        object unauthorized : KieError()
        object sessionExpired : KieError()
        object forbidden : KieError()
        object notConfiguredOnServer : KieError()
        /** Geliştirme yolu: kie.ai hesabının kendisinde kredi yok. */
        object insufficientCredits : KieError()
        /** Sunucu yolu: kliniğin AI kredisi bu tasarıma yetmiyor → kredi al. */
        data class clinicCreditsExhausted(val required: Double?, val available: Double?) : KieError()
        /**
         * Sunucu yolu: kişinin AYLIK kredi limiti (Kredi Yönetimi) bu tasarıma yetmiyor.
         * Kredi satın almak bunu AÇMAZ — limiti klinik sahibi değiştirir.
         */
        data class personalLimitReached(val limit: Double?, val used: Double?, val required: Double?) : KieError()
        /** Sunucu yolu: yapay zeka sağlayıcısı geçici olarak kullanılamıyor. */
        object providerUnavailable : KieError()
        object hourlyLimit : KieError()
        object rateLimited : KieError()
        data class contentRejected(val detail: String?) : KieError()
        data class generationFailed(val detail: String?) : KieError()
        /** Sunucu yolu: görev başarısız oldu ve kredisi iade edildi. */
        data class refunded(val detail: String?) : KieError()
        /** Ücretsiz deneme: hak bitti → iletişim ekranı. */
        object trialExhausted : KieError()
        /** Ücretsiz deneme: kapasite doldu / servis geçici olarak kapalı (hak düşmez). */
        data class trialUnavailable(val detail: String?) : KieError()
        /** Ücretsiz deneme: görev başarısız oldu, hak iade edildi. */
        data class trialRefunded(val detail: String?) : KieError()
        object timedOut : KieError()
        data class server(val code: Int, val detail: String?) : KieError()
        object badResponse : KieError()

        val errorDescription: String get() = message

        override val message: String
            get() = when (this) {
                missingAPIKey -> "Gülüş simülasyonu için API anahtarı tanımlı değil (Secrets.plist → KieAPIKey)."
                sessionExpired -> "Oturumunuz sona ermiş. Tekrar giriş yapıp deneyin."
                forbidden -> "Bu hesabın yapay zeka özelliklerini kullanma yetkisi yok (AI_RUN)."
                notConfiguredOnServer -> "Gülüş simülasyonu sunucuda henüz etkin değil. Sistem yöneticinize bildirin."
                unauthorized -> "Yapay zeka servisi API anahtarını reddetti. Anahtarı kontrol edin."
                insufficientCredits -> "Yapay zeka servisinde kredi kalmadı. kie.ai hesabına kredi yükledikten sonra tekrar deneyin."
                is clinicCreditsExhausted -> {
                    val need = required?.let { " Bu tasarım ${SmileCreditFormat.credits(it)} kredi" } ?: ""
                    val have = available?.let { ", kalan krediniz ${SmileCreditFormat.credits(it)}." } ?: (if (need.isEmpty()) "" else ".")
                    "Yapay zeka krediniz yetersiz.$need$have Kredi alarak devam edebilirsiniz."
                }
                is personalLimitReached -> {
                    val durum = (used?.let { SmileCreditFormat.credits(it) } ?: "?") + " / " + (limit?.let { SmileCreditFormat.credits(it) } ?: "?")
                    val need = required?.let { " Bu tasarım ${SmileCreditFormat.credits(it)} kredi." } ?: ""
                    "Bu ay size tanımlanan kredi limitine ulaştınız ($durum kredi).$need Limiti klinik sahibi Kredi Yönetimi'nden değiştirebilir."
                }
                providerUnavailable -> "Yapay zeka servisi şu an yanıt vermiyor. Krediniz ya da deneme hakkınız düşülmedi; biraz sonra tekrar deneyin."
                hourlyLimit -> "Saatlik simülasyon sınırına ulaşıldı. Bir süre sonra tekrar deneyin."
                rateLimited -> "Çok fazla istek gönderildi. Birkaç saniye bekleyip tekrar deneyin."
                is contentRejected -> "Görsel servis tarafından reddedildi." + (detail?.let { " ($it)" } ?: "")
                is generationFailed -> "Simülasyon oluşturulamadı." + (detail?.let { " ($it)" } ?: "") + " Tekrar deneyin."
                is refunded -> "Simülasyon oluşturulamadı" + (detail?.let { " ($it)" } ?: "") + ". Krediniz iade edildi; tekrar deneyin."
                trialExhausted -> "Ücretsiz deneme hakkınız doldu. Daha fazlası için bizimle iletişime geçin."
                is trialUnavailable -> detail ?: "Ücretsiz deneme şu an kullanılamıyor. Hakkınız düşülmedi; biraz sonra tekrar deneyin."
                is trialRefunded -> "Simülasyon oluşturulamadı" + (detail?.let { " ($it)" } ?: "") + ". Hakkınız iade edildi; tekrar deneyin."
                timedOut -> "Yapay zeka zamanında yanıt vermedi. Tekrar deneyin."
                is server -> "Yapay zeka servisi hatası ($code)." + (detail?.let { " $it" } ?: "")
                badResponse -> "Yapay zeka servisinden beklenmeyen yanıt alındı."
            }
    }

    /** Tek bir HTTP alışverişinin kaydı (gönderim kanıtı / hata ayıklama). */
    class AuditEntry(
        val method: String,
        val url: String,
        val requestContentType: String?,
        val requestBody: ByteArray?,
        val status: Int,
        val responseBody: ByteArray,
    )

    /**
     * Tamamlanan görev: sonuç görseli + görev kimliği (sunucu yolunda ücretsiz
     * otomatik düzeltme bu kimliğe bağlanır).
     */
    class Generation(val data: ByteArray, val taskId: String)

    var pollInterval: Duration = 2.seconds
    /**
     * Servis yoğunken görev 7 dakikayı aşabiliyor (27.09.2026 testinde 4 görevden 2'si).
     * Erken bırakılırsa kredisi düşmüş sonuç kaybolur; bu yüzden cömert tutulur.
     */
    var timeout: Double = 900.0

    companion object {
        /**
         * Denetim kancası: ayarlanırsa her istek ve yanıt bildirilir. Uygulamada
         * kullanılmaz (null); test ve kanıt araçları içindir.
         */
        @Volatile var audit: ((AuditEntry) -> Unit)? = null

        // MARK: Oturumlar — önbellek yok, hasta görseli diske düşmez

        private val directSession: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        /** Sunucu yolu: Dentifay oturum çerezi (ApiClient ile aynı depo) gönderilir. */
        private val proxySession: OkHttpClient by lazy {
            ApiClient.http.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Ücretsiz deneme: çerez GÖNDERİLMEZ (eski bir oturum çerezi `/api/…`de "oturum değişti"
         * hatası doğurabilirdi), hiçbir şey önbelleğe yazılmaz.
         */
        private val guestSession: OkHttpClient by lazy { directSession.newBuilder().build() }

        /**
         * Sağlayıcının İngilizce hata metnini hekimin anlayacağı Türkçeye çevirir
         * (yoğunlukta sık gelen "The upstream API service timed out…" gibi).
         * İçerik reddi metni olduğu gibi kalır; `contentRejected` onu ayrıca tanır.
         */
        fun readableFailure(message: String): String {
            val m = message.lowercase()
            if (m.contains("nsfw") || m.contains("sensitive")) return message
            if (m.contains("timed out") || m.contains("timeout")) {
                return "yapay zeka servisi yoğunluk nedeniyle zamanında yanıt vermedi"
            }
            if (m.contains("busy") || m.contains("overload") || m.contains("capacity") || m.contains("try again")) {
                return "yapay zeka servisi şu an yoğun"
            }
            if (m.contains("internal") || m.contains("server error")) {
                return "yapay zeka servisinde geçici bir hata oluştu"
            }
            return message
        }

        /**
         * Telefonda sık görülen geçici hatalar (Wi-Fi ↔ hücresel geçişi, uygulamanın
         * arka plandan dönüşü, servis yoğunluğu). Tekrar denenir.
         */
        internal fun isTransient(error: Throwable): Boolean {
            if (error is CancellationException) return false
            if (error is ApiException.NetworkError) {
                return !(error.cause?.message ?: "").contains("Canceled", ignoreCase = true)
            }
            if (error is IOException) return !(error.message ?: "").contains("Canceled", ignoreCase = true)
            return when (error) {
                is KieError.rateLimited, is KieError.providerUnavailable -> true
                is KieError.server -> error.code >= 500
                else -> false
            }
        }

        /** JSON sayısını (Int/Double) okur. */
        fun number(value: JsonElement?): Double? {
            val p = value as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.doubleOrNull
        }
    }

    // MARK: Adresler (iki yolda aynı istek biçimi)

    private val uploadURL: String
        get() = when (val e = endpoint) {
            is Endpoint.direct -> "https://kieai.redpandaai.co/api/file-stream-upload"
            is Endpoint.proxy -> e.baseURL.trimEnd('/') + "/upload"
            is Endpoint.guest -> e.baseURL.trimEnd('/') + "/upload"
        }

    private val createURL: String
        get() = when (val e = endpoint) {
            is Endpoint.direct -> "https://api.kie.ai/api/v1/jobs/createTask"
            is Endpoint.proxy -> e.baseURL.trimEnd('/') + "/tasks"
            is Endpoint.guest -> e.baseURL.trimEnd('/') + "/tasks"
        }

    private fun recordURL(taskId: String): String = when (val e = endpoint) {
        is Endpoint.direct -> "https://api.kie.ai/api/v1/jobs/recordInfo?taskId=" + java.net.URLEncoder.encode(taskId, "UTF-8")
        is Endpoint.proxy -> e.baseURL.trimEnd('/') + "/tasks/" + taskId
        is Endpoint.guest -> e.baseURL.trimEnd('/') + "/tasks/" + taskId
    }

    private val isUsable: Boolean
        get() = (endpoint as? Endpoint.direct)?.apiKey?.isNotEmpty() ?: true

    private val session: OkHttpClient
        get() = when (endpoint) {
            is Endpoint.proxy -> proxySession
            is Endpoint.guest -> guestSession
            is Endpoint.direct -> directSession
        }

    private val isServerPath: Boolean get() = endpoint !is Endpoint.direct

    /** Tek adımda yükle + düzenle (tek tasarım için kısayol). */
    suspend fun edit(
        jpeg: ByteArray,
        mask: ByteArray? = null,
        model: SmileAIModel = SmileAIModel.default,
        prompt: String,
        seed: Int? = null,
        onStage: (Stage) -> Unit = {},
    ): ByteArray {
        onStage(Stage.uploading)
        val imageURL = upload(jpeg)
        val maskURL = mask?.let { upload(it, fileExtension = "png", mime = "image/png") }
        return generate(model, imageURL, maskURL, prompt, seed, onStage = onStage).data
    }

    /**
     * Yüklenmiş görsel üzerinde bir düzenleme görevi çalıştırır ve sonucu indirir.
     * Aynı görsel birden fazla tasarım için tekrar kullanılabilir.
     * `fixOf`: otomatik yerleşim düzeltmesinde asıl görevin kimliği — sunucu
     * yolunda düzeltme bu görevin kredisine sayılır (ek kredi düşmez).
     */
    suspend fun generate(
        model: SmileAIModel,
        imageURL: String,
        maskURL: String? = null,
        prompt: String,
        seed: Int? = null,
        fixOf: String? = null,
        onStage: (Stage) -> Unit = {},
    ): Generation {
        if (!isUsable) throw KieError.missingAPIKey
        onStage(Stage.queued)
        val taskId = createTask(model, imageURL, maskURL, prompt, seed, fixOf)
        val resultURL = waitForResult(taskId, onStage)
        onStage(Stage.downloading)
        var attempt = 0
        while (true) {
            try {
                val raw = ApiClient.execute(Request.Builder().url(resultURL).get().build(), session)
                audit?.invoke(AuditEntry("GET", resultURL, null, null, raw.status, raw.body))
                if (raw.status != 200 || raw.body.isEmpty()) throw KieError.badResponse
                return Generation(raw.body, taskId)
            } catch (e: Exception) {
                if (!(isTransient(e) && attempt < 3)) throw e
                attempt += 1
                delay(2000)
            }
        }
    }

    // MARK: - Adımlar

    /** Maskeli kırpımı (veya maske PNG'sini) kie.ai geçici depoya yükler. */
    suspend fun upload(data: ByteArray, fileExtension: String = "jpg", mime: String = "image/jpeg"): String {
        if (!isUsable) throw KieError.missingAPIKey
        val boundary = "dentifay-${UUID.randomUUID().toString().uppercase()}"
        // Rastgele ad — dosya adında hasta bilgisi yok.
        val fileName = "${UUID.randomUUID().toString().lowercase()}.$fileExtension"
        val body = MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart("uploadPath", "smile-sim")
            .addFormDataPart("fileName", fileName)
            .addFormDataPart("file", fileName, data.toRequestBody(mime.toMediaType()))
            .build()
        val req = authorized(uploadURL, "POST", body)
        val json = send(req, describe(body))
        val url = (json["data"] as? JsonObject)?.let { (it["downloadUrl"] as? JsonPrimitive)?.contentOrNull }
            ?: throw KieError.badResponse
        return url
    }

    private suspend fun createTask(
        model: SmileAIModel,
        imageURL: String,
        maskURL: String?,
        prompt: String,
        seed: Int?,
        fixOf: String?,
    ): String {
        val input = model.input(imageURL, maskURL, prompt.take(model.maxPromptLength), seed)
        val payload = buildJsonObject {
            put("model", model.apiModel)
            put("input", input)
            // Yalnız sunucu yolu: kie.ai bu alanı tanımaz.
            if (isServerPath && fixOf != null) put("fixOf", fixOf)
        }
        val bytes = DfyJson.encodeToString(JsonObject.serializer(), payload).toByteArray()
        val req = authorized(createURL, "POST", bytes.toRequestBody(ApiClient.JSON_MEDIA))
        val json = send(req, bytes)
        val id = (json["data"] as? JsonObject)?.let { (it["taskId"] as? JsonPrimitive)?.contentOrNull }
            ?: throw KieError.badResponse
        return id
    }

    private suspend fun waitForResult(taskId: String, onStage: (Stage) -> Unit): String {
        val req = authorized(recordURL(taskId), "GET", null)
        val deadline = System.currentTimeMillis() + (timeout * 1000).toLong()
        var reportedGenerating = false
        var failures = 0

        while (true) {
            delay(pollInterval)
            val json: JsonObject
            try {
                json = send(req, null)
                failures = 0
            } catch (e: Exception) {
                if (!isTransient(e)) throw e
                // Ağ kesildi ya da uygulama arka plandaydı: görev sunucuda sürüyor
                // (kredisi düştü), yoklamayı bırakma. Askıdan dönüşteki ilk istek sık
                // düştüğü için süre dolmuş olsa da birkaç kez daha denenir.
                failures += 1
                if (!(System.currentTimeMillis() < deadline || failures <= 3)) throw KieError.timedOut
                continue
            }
            val data = json["data"] as? JsonObject ?: throw KieError.badResponse
            val state = (data["state"] as? JsonPrimitive)?.contentOrNull ?: throw KieError.badResponse

            when (state) {
                "success" -> {
                    val raw = (data["resultJson"] as? JsonPrimitive)?.contentOrNull ?: throw KieError.badResponse
                    val obj = runCatching { DfyJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: throw KieError.badResponse
                    val first = (obj["resultUrls"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: throw KieError.badResponse
                    if (first.isBlank()) throw KieError.badResponse
                    return first
                }
                "fail" -> {
                    val msg = (data["failMsg"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isEmpty()) null else readableFailure(it) }
                    if (msg != null && (msg.contains("nsfw", ignoreCase = true) || msg.contains("sensitive", ignoreCase = true))) {
                        throw KieError.contentRejected(msg)
                    }
                    // Sunucu yolu başarısız görevin kredisini (denemede hakkını) iade eder ve bunu bildirir.
                    if ((json["refunded"] as? JsonPrimitive)?.booleanOrNull == true) {
                        if (endpoint is Endpoint.guest) throw KieError.trialRefunded(msg)
                        throw KieError.refunded(msg)
                    }
                    throw KieError.generationFailed(msg)
                }
                "generating" -> if (!reportedGenerating) { reportedGenerating = true; onStage(Stage.generating) }
                else -> Unit   // waiting / queuing
            }
            // Süre, bir yoklama yapılmadan dolmaz: uygulama askıdan dönünce önce sonuca bakılır.
            if (System.currentTimeMillis() >= deadline) throw KieError.timedOut
        }
    }

    // MARK: - HTTP

    /** Doğrudan yolda anahtar başlığa eklenir; sunucu yolunda yetki oturum çerezindedir. */
    private fun authorized(url: String, method: String, body: RequestBody?): Request {
        val b = Request.Builder().url(url).method(method, body).header("Cache-Control", "no-cache")
        (endpoint as? Endpoint.direct)?.let { b.header("Authorization", "Bearer ${it.apiKey}") }
        return b.build()
    }

    /** Denetim kaydı için gövde baytları (yalnız kanca etkinken). */
    private fun describe(body: RequestBody): ByteArray? {
        if (audit == null) return null
        return runCatching { okio.Buffer().also { body.writeTo(it) }.readByteArray() }.getOrNull()
    }

    /**
     * kie.ai hataları hem HTTP durum kodunda hem de gövdedeki `code` alanında
     * gelebilir (ör. HTTP 200 + `"code": 402`). İkisini de kontrol eder.
     */
    private suspend fun send(req: Request, auditBody: ByteArray?): JsonObject = withContext(Dispatchers.IO) {
        val raw = ApiClient.execute(req, session)
        val http = raw.status
        audit?.invoke(AuditEntry(req.method, req.url.toString(), req.body?.contentType()?.toString(), auditBody, http, raw.body))
        val json = runCatching { DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        fun str(key: String): String? = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Ücretsiz denemenin kendi hataları (hak, kapasite, yapılandırma)
        if (endpoint is Endpoint.guest) {
            val serverError = str("error")
            val serverMsg = str("msg")
            when {
                http == 429 && serverError == "TRIAL_EXHAUSTED" -> throw KieError.trialExhausted
                http == 429 -> throw KieError.rateLimited
                http == 503 && serverError == "SMILE_SIM_NOT_CONFIGURED" -> throw KieError.notConfiguredOnServer
                (http == 503 && serverError == "SMILE_SIM_PROVIDER_UNAVAILABLE") || http == 502 -> throw KieError.providerUnavailable
                http == 503 -> throw KieError.trialUnavailable(serverMsg)
                http == 400 && serverError == "TRIAL_TIER_NOT_ALLOWED" -> throw KieError.server(400, serverMsg)
                http == 404 && serverError != "SMILE_SIM_TASK_NOT_FOUND" ->
                    throw KieError.trialUnavailable("Ücretsiz deneme sunucuda henüz etkin değil. Lütfen daha sonra tekrar deneyin.")
            }
        }
        // Sunucu yolunun kendi hataları (oturum, yetki, kredi, yapılandırma)
        if (endpoint is Endpoint.proxy) {
            val serverError = str("error")
            when {
                http == 401 -> throw KieError.sessionExpired
                http == 403 -> throw KieError.forbidden
                http == 402 && serverError == "CREDIT_LIMIT_REACHED" ->
                    throw KieError.personalLimitReached(
                        limit = number(json?.get("limitCredits")),
                        used = number(json?.get("usedCredits")),
                        required = number(json?.get("requiredCredits")),
                    )
                http == 402 -> throw KieError.clinicCreditsExhausted(
                    required = number(json?.get("requiredCredits")),
                    available = number(json?.get("availableCredits")),
                )
                http == 429 && serverError == "SMILE_SIM_HOURLY_CAP_REACHED" -> throw KieError.hourlyLimit
                http == 502 -> throw KieError.providerUnavailable
                http == 503 && serverError == "SMILE_SIM_PROVIDER_UNAVAILABLE" -> throw KieError.providerUnavailable
                http == 503 -> throw KieError.notConfiguredOnServer
            }
        }
        val codePrim = json?.get("code") as? JsonPrimitive
        val code = (if (codePrim != null && !codePrim.isString) codePrim.intOrNull else null) ?: http
        val msg = str("msg") ?: str("message")

        when (code) {
            200 -> Unit
            401, 403 -> throw KieError.unauthorized
            402 -> throw KieError.insufficientCredits
            429 -> throw KieError.rateLimited
            else -> throw KieError.server(code, msg)
        }
        if (http !in 200..299 || json == null) throw KieError.server(http, msg)
        json
    }
}

/**
 * kie.ai üzerindeki görüntü düzenleme modelleri. Yalnızca 23.09.2026
 * karşılaştırma testinde (8 model, gerçek fotoğraflar) iyi sonuç verenler
 * listelenir; Seedream 4.5 talimata uymadı, Qwen2 Edit renk bozulması yaptı,
 * Ideogram boşluğu doldurmadı, Flux 2 hata verdi. `qwenMask` gerçek maskeli
 * düzenleme yapar (maske dışı piksellere dokunmaz).
 */
@Serializable
enum class SmileAIModel(val rawValue: String) {
    // Ham değer = kie.ai model adı; "@1K" eki aynı modelin ucuz çözünürlük katmanı.
    @SerialName("nano-banana-2") nanoBanana2("nano-banana-2"),
    @SerialName("nano-banana-2@1K") nanoBanana2Fast("nano-banana-2@1K"),
    @SerialName("google/nano-banana-edit") nanoBanana("google/nano-banana-edit"),
    @SerialName("nano-banana-pro") nanoBananaPro("nano-banana-pro"),
    @SerialName("qwen2-1/image-to-image") qwenMask("qwen2-1/image-to-image"),
    @SerialName("qwen2-1/image-to-image@1K") qwenMaskFast("qwen2-1/image-to-image@1K"),
    @SerialName("gpt-image-2-image-to-image") gptImage2("gpt-image-2-image-to-image");

    val id: String get() = rawValue

    /** kie.ai `model` alanı (çözünürlük eki olmadan). */
    val apiModel: String get() = rawValue.split("@")[0]
    val resolution: String get() = if (rawValue.endsWith("@1K")) "1K" else "2K"

    /**
     * Sunucudaki kredi tarifesi anahtarı (yalnız seçilebilir kademeler).
     */
    val tierKey: String?
        get() = when (this) {
            nanoBanana -> "orta"
            nanoBanana2Fast -> "orta_iyi"
            nanoBanana2 -> "iyi"
            nanoBananaPro -> "cok_iyi"
            qwenMask, qwenMaskFast, gptImage2 -> null
        }

    val subtitle: String
        get() = when (this) {
            nanoBanana -> "Doğal renk ve ışık · ~1 dk"
            nanoBanana2Fast -> "Daha net · hızlı (~30 sn)"
            nanoBanana2 -> "Yüksek çözünürlük (2K)"
            nanoBananaPro -> "En detaylı sonuç"
            qwenMaskFast -> "Yalnızca maske içini değiştirir"
            qwenMask -> "Maskeli, 2K"
            gptImage2 -> "Tekdüze sonuç"
        }

    val title: String
        get() = when (this) {
            nanoBanana -> "Orta"
            nanoBanana2Fast -> "Orta İyi"
            nanoBanana2 -> "İyi"
            nanoBananaPro -> "Çok İyi"
            qwenMask -> "Maskeli (2K)"
            qwenMaskFast -> "Yerleşim düzeltme"
            gptImage2 -> "Alternatif"
        }

    /**
     * Görev başına Dentifay AI kredisi (sunucu tarifesiyle aynı; sunucu yolunda
     * asıl ücreti sunucu belirler). Geliştirme yolunda kie.ai kredisine eşittir
     * (kie.ai fiyat listesi 23.09.2026; 1 kie kredisi ≈ 0,005 $).
     */
    val creditsPerImage: Double
        get() = when (this) {
            nanoBananaPro -> 18.0
            nanoBanana2 -> 12.0
            nanoBanana2Fast -> 8.0
            nanoBanana -> 4.0
            qwenMask -> 8.0
            qwenMaskFast -> 4.0
            gptImage2 -> 10.0
        }

    val maxPromptLength: Int
        get() = when (this) {
            nanoBananaPro -> 10000
            nanoBanana2, nanoBanana2Fast, gptImage2 -> 20000
            nanoBanana, qwenMask, qwenMaskFast -> 5000
        }

    val usesMask: Boolean get() = this == qwenMask || this == qwenMaskFast
    val invertsMask: Boolean get() = false

    fun input(imageURL: String, maskURL: String?, prompt: String, seed: Int?): JsonObject = buildJsonObject {
        put("prompt", prompt)
        when (this@SmileAIModel) {
            nanoBananaPro, nanoBanana2, nanoBanana2Fast -> {
                putJsonArray("image_input") { add(imageURL) }
                put("aspect_ratio", "3:2"); put("resolution", resolution); put("output_format", "png")
            }
            nanoBanana -> {
                putJsonArray("image_urls") { add(imageURL) }
                put("aspect_ratio", "3:2"); put("output_format", "png")
            }
            qwenMask, qwenMaskFast -> {
                putJsonArray("image_urls") { add(imageURL) }
                if (maskURL != null) put("mask_url", maskURL)
                put("aspect_ratio", "3:2"); put("resolution", resolution); put("output_format", "png")
                if (seed != null) put("seed", seed)
            }
            gptImage2 -> {
                putJsonArray("input_urls") { add(imageURL) }
                put("aspect_ratio", "3:2"); put("resolution", "2K")
            }
        }
    }

    companion object {
        val allCases: List<SmileAIModel> get() = entries.toList()

        /**
         * 23.09.2026 telefon sonrası testi (ağız dikdörtgeni gönderimi, eksik diş +
         * betel lekesi vakaları): en doğal ışık/renk uyumu ve en düşük maliyet
         * (4 kr, 30–80 sn, yerleşim 0,87–0,89). NB2 daha hızlı ama 2 kat pahalı.
         */
        val default: SmileAIModel = nanoBanana

        /**
         * Hekime sunulan kalite kademeleri (fiyat/kalite sırasıyla). Model adları
         * arayüzde gösterilmez; kademe adları sunucudaki kredi tarifesiyle aynıdır
         * (`backend/lib/ai-credit-rates.js` → `before_after:<tierKey>`).
         */
        val selectable: List<SmileAIModel> = listOf(nanoBanana, nanoBanana2Fast, nanoBanana2, nanoBananaPro)

        /**
         * Otomatik yerleşim düzeltmesinde kullanılan maskeli model (her zaman açık,
         * sunucu yolunda ücretsiz — asıl görevin kredisine sayılır).
         */
        val fixModel: SmileAIModel = qwenMaskFast

        fun of(rawValue: String): SmileAIModel? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
