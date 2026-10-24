@file:Suppress("ClassName")

package com.dentifay.app.trial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Prefs
import com.dentifay.app.xray.XrayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Giriş ekranındaki ÜCRETSİZ DENEME (oturumsuz): Röntgen Analizi + Gülüş Simülasyonu.
 *
 * Sunucu: `GET/POST {API}/public/trial/...` (`backend/routes/misafir-deneme.js`). Her özellik
 * için IP başına `limit` (varsayılan 5) hak vardır; hak bitince sunucu 429 `TRIAL_EXHAUSTED`
 * döner ve uygulama "daha fazlası için bizimle iletişime geçin" ekranını açar. Başarısız iş
 * hakkı geri verir (sunucu sayar; istemci yalnız gösterir).
 */
enum class GuestTrialFeature(val rawValue: String) {
    xray("xray"), smile("smile");

    val title: String
        get() = when (this) {
            xray -> "Röntgen Analizi"
            smile -> "Gülüş Simülasyonu"
        }

    companion object {
        fun from(raw: String?): GuestTrialFeature? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
data class GuestTrialQuota(
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val available: Boolean? = null,
)

@Serializable
data class GuestTrialContact(
    val url: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val whatsapp: String? = null,
) {
    companion object {
        val fallback = GuestTrialContact(url = "https://dentifay.com/#cta")
    }
}

@Serializable
data class GuestTrialStatus(
    val limit: Int,
    val xray: GuestTrialQuota,
    val smile: GuestTrialQuota,
    val contact: GuestTrialContact? = null,
)

/** Deneme uçlarının hataları (sunucu sözleşmesi: `{ code, error, msg }`). */
sealed class GuestTrialError : Exception() {
    /** Hak bitti → iletişim ekranı. */
    data class exhausted(val feature: GuestTrialFeature?) : GuestTrialError()
    /** Günlük toplam misafir kapasitesi doldu. */
    data class dailyCap(val msg: String?) : GuestTrialError()
    /** Röntgen servisi meşgul (aynı anda tek misafir analizi). */
    data class busy(val retryAfter: Int?) : GuestTrialError()
    /** Servis kapalı / modeller hazır değil — hak düşmez. */
    data class unavailable(val msg: String?) : GuestTrialError()
    /** Sunucuda deneme ucu yok (henüz yayınlanmadı). */
    data object notDeployed : GuestTrialError()
    data class server(val status: Int, val msg: String?) : GuestTrialError()
    data object network : GuestTrialError()

    val errorDescription: String
        get() = when (this) {
            is exhausted -> "Ücretsiz deneme hakkınız doldu. Daha fazlası için bizimle iletişime geçin."
            is dailyCap -> msg ?: "Bugünkü ücretsiz deneme kapasitesi doldu. Yarın tekrar deneyebilirsiniz."
            is busy -> "Analiz servisi şu an yoğun. ${retryAfter?.let { "$it saniye" } ?: "Biraz"} sonra tekrar deneyin; hakkınız düşülmedi."
            is unavailable -> msg ?: "Servis şu anda kullanılamıyor. Hakkınız düşülmedi."
            notDeployed -> "Ücretsiz deneme sunucuda henüz etkin değil. Lütfen daha sonra tekrar deneyin."
            is server -> msg ?: "Sunucu hatası ($status). Hakkınız düşülmedi; tekrar deneyin."
            network -> "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin."
        }

    override val message: String get() = errorDescription

    val isExhausted: Boolean get() = this is exhausted

    companion object {
        /** Sunucu yanıtını (durum kodu + gövde) hataya çevirir; başarıysa null. */
        fun from(status: Int, json: JsonObject?): GuestTrialError? {
            if (status in 200..299) return null
            val code = json.string("error")
            val msg = json.string("msg") ?: json.string("error")
            return when {
                status == 429 && code == "TRIAL_EXHAUSTED" -> exhausted(GuestTrialFeature.from(json.string("feature")))
                status == 503 && code == "TRIAL_DAILY_CAP" -> dailyCap(json.string("msg"))
                status == 503 && code == "TRIAL_BUSY" -> busy((json?.get("retryAfterSeconds") as? JsonPrimitive)?.intOrNull)
                status == 503 || status == 502 -> unavailable(json.string("msg"))
                status == 429 -> busy(null)
                status == 404 -> notDeployed
                else -> server(status, msg)
            }
        }

        private fun JsonObject?.string(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** `analyzeXray` sonucu (Swift `(result:, quota:)` demeti). */
data class GuestXrayAnalysis(val result: XrayResult, val quota: GuestTrialQuota?)

@Serializable
private data class GuestXrayEnvelope(val result: XrayResult, val trial: GuestTrialQuota? = null)

/**
 * Oturumsuz deneme istemcisi. Çerez GÖNDERMEZ (eski bir oturum çerezi `/api/…`de
 * "oturum değişti" hatası doğurabilirdi) ve önbellek tutmaz.
 */
class GuestTrialClient(val baseURL: String = AppConfig.apiBaseURL + "/public/trial") {

    companion object {
        /** Çerezsiz, önbelleksiz istemci (ephemeral URLSession karşılığı). */
        val session: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)     // röntgen analizi CPU'da 30-60 sn sürebilir
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()

        private suspend fun transport(req: Request, client: OkHttpClient = session): Pair<ByteArray, Int> =
            withContext(Dispatchers.IO) {
                try {
                    val raw = ApiClient.execute(req, client)
                    raw.body to raw.status
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw GuestTrialError.network
                }
            }

        private fun json(data: ByteArray): JsonObject? =
            runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }

    suspend fun status(): GuestTrialStatus {
        val req = Request.Builder().url("$baseURL/status").get().header("Cache-Control", "no-cache").build()
        val quick = session.newBuilder().callTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
        val (data, code) = transport(req, quick)
        GuestTrialError.from(code, json(data))?.let { throw it }
        return try {
            DfyJson.decodeFromString(GuestTrialStatus.serializer(), data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw GuestTrialError.server(code, null)
        }
    }

    /** Röntgeni yükleyip analiz eder (tek istek). Başarıda sonuç + kalan hak. */
    suspend fun analyzeXray(jpeg: ByteArray): GuestXrayAnalysis {
        val body = MultipartBody.Builder("dentifay-${UUID.randomUUID()}")
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "rontgen.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url("$baseURL/xray/analyze").post(body).build()
        val (data, code) = transport(req)
        GuestTrialError.from(code, json(data))?.let { throw it }
        return try {
            val env = DfyJson.decodeFromString(GuestXrayEnvelope.serializer(), data.toString(Charsets.UTF_8))
            GuestXrayAnalysis(env.result, env.trial)
        } catch (e: Exception) {
            throw GuestTrialError.server(code, "Analiz sonucu okunamadı.")
        }
    }
}

/**
 * Kalan hakların ekrandaki tek kaynağı (giriş ekranı kartları, akış başlıkları).
 * Swift `GuestTrialStore.shared` → hem `GuestTrialStore.x` hem `GuestTrialStore.shared.x` çalışır.
 */
object GuestTrialStore {
    val shared: GuestTrialStore get() = this

    var status by mutableStateOf<GuestTrialStatus?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    /**
     * Son yoklamada deneme sunucusu kullanılamıyorsa nedeni (ulaşılamadı / uç yayında değil);
     * null = sunucu yanıt verdi. Önbellekteki hak sayısı ekranda kalır ama işler buna göre yönlenir.
     */
    var unavailableReason by mutableStateOf<GuestTrialError?>(null)
        private set
    /** Son yoklama zamanı (null: henüz yoklanmadı). */
    var checkedAt by mutableStateOf<Instant?>(null)
        private set
    var client = GuestTrialClient()

    /** Anahtar sunucu adresine bağlıdır (geliştirmede farklı sunucuların değeri karışmasın). */
    private val cacheKey: String get() = "GuestTrial.lastStatus.v1|" + AppConfig.apiBaseURL

    /**
     * Sunucu üstlenemediği için cihazda yapılan denemeler. Sunucu o özelliği vermediği
     * sürece kalan hakkı bunlar düşürür (sayaç 5'te takılı kalmasın).
     */
    private var localUsed by mutableStateOf<Map<String, Int>>(emptyMap())
    private val localUsedKey: String get() = "GuestTrial.localUsed.v1|" + AppConfig.apiBaseURL
    private val localUsedSerializer = MapSerializer(String.serializer(), Int.serializer())

    init {
        Prefs.string(cacheKey)?.let { raw ->
            runCatching { DfyJson.decodeFromString(GuestTrialStatus.serializer(), raw) }.getOrNull()?.let { status = it }
        }
        localUsed = Prefs.string(localUsedKey)?.let { raw ->
            runCatching { DfyJson.decodeFromString(localUsedSerializer, raw) }.getOrNull()
        } ?: emptyMap()
    }

    val contact: GuestTrialContact get() = status?.contact ?: GuestTrialContact.fallback
    val limit: Int get() = status?.limit ?: 5

    fun quota(f: GuestTrialFeature): GuestTrialQuota? {
        val q = serverQuota(f)
        val n = localUsed[f.rawValue] ?: 0
        if (isAvailable(f) || n <= 0) return q
        val l = q?.limit ?: limit
        val used = minOf(l, (q?.used ?: 0) + n)
        return GuestTrialQuota(limit = l, used = used, remaining = maxOf(0, l - used), available = q?.available)
    }

    private fun serverQuota(f: GuestTrialFeature): GuestTrialQuota? = when (f) {
        GuestTrialFeature.xray -> status?.xray
        GuestTrialFeature.smile -> status?.smile
    }

    /** Sunucunun üstlenemediği (cihazda yapılan) bir denemeyi sayaca işler. */
    fun recordLocalUse(f: GuestTrialFeature) {
        localUsed = localUsed + (f.rawValue to (localUsed[f.rawValue] ?: 0) + 1)
        Prefs.set(localUsedKey, DfyJson.encodeToString(localUsedSerializer, localUsed))
    }

    /** Kalan hak; bilinmiyorsa null (sunucu yine denetler). */
    fun remaining(f: GuestTrialFeature): Int? = quota(f)?.remaining

    fun isExhausted(f: GuestTrialFeature): Boolean = remaining(f) == 0

    suspend fun refresh() {
        // Adres istek anında çözülür (AppConfig ağ değişiminde host değiştirebilir).
        client = GuestTrialClient()
        isLoading = true
        try {
            val s = client.status()
            status = s
            lastError = null
            unavailableReason = null
            checkedAt = Instant.now()
            Prefs.set(cacheKey, DfyJson.encodeToString(GuestTrialStatus.serializer(), s))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = (e as? GuestTrialError)?.errorDescription ?: e.message
            checkedAt = Instant.now()
            val err = e as? GuestTrialError
            unavailableReason = when (err) {
                GuestTrialError.network, GuestTrialError.notDeployed -> err
                else -> null   // sunucu yanıt verdi (ör. geçici 5xx) — asıl istek söyler
            }
            // Uç yoksa bilinen bir hak durumu da yoktur (eski önbellek yanıltmasın).
            if (err == GuestTrialError.notDeployed) {
                status = null
                Prefs.remove(cacheKey)
            }
        } finally {
            isLoading = false
        }
    }

    /**
     * Özellik sunucuda gerçekten çalışır durumda mı: son yoklama başarılı ve sunucu özelliği
     * kapalı bildirmiyor (gülüşte kie anahtarı, röntgende model servisi).
     */
    fun isAvailable(f: GuestTrialFeature): Boolean =
        checkedAt != null && unavailableReason == null && serverQuota(f)?.available != false

    /**
     * Deneme başlamadan önce: son 30 sn'de başarıyla yoklanmadıysa sunucu yeniden yoklanır
     * (en fazla ~8 sn). Kullanılamıyorsa nedeni döner — istek 1–3 dk zaman aşımı beklemesin.
     */
    suspend fun ensureAvailable(): GuestTrialError? {
        val at = checkedAt
        if (at != null && unavailableReason == null && Duration.between(at, Instant.now()).seconds < 30) return null
        refresh()
        return unavailableReason
    }

    /** İşlem yanıtındaki kalan hakkı ekrana hemen yansıtır. */
    fun apply(quota: GuestTrialQuota?, `for`: GuestTrialFeature) {
        val f = `for`
        val s = status
        if (quota == null || s == null) return
        val merged = GuestTrialQuota(quota.limit, quota.used, quota.remaining, quota.available ?: this.quota(f)?.available)
        status = GuestTrialStatus(
            limit = s.limit,
            xray = if (f == GuestTrialFeature.xray) merged else s.xray,
            smile = if (f == GuestTrialFeature.smile) merged else s.smile,
            contact = s.contact,
        )
    }

    /** Hak bitti yanıtı geldiyse sayacı sıfıra çeker. */
    fun markExhausted(f: GuestTrialFeature) {
        val l = quota(f)?.limit ?: limit
        apply(GuestTrialQuota(limit = l, used = l, remaining = 0, available = quota(f)?.available), f)
    }
}
