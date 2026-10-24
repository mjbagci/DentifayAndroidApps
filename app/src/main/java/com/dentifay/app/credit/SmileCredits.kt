package com.dentifay.app.credit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.SessionEvents
import com.dentifay.app.core.str
import com.dentifay.app.smile.KieImageEditClient
import com.dentifay.app.smile.SmileAIModel
import com.dentifay.app.smile.SmileSimulationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * Gülüş simülasyonu kredileri (Dentifay AI kredisi).
 *
 * Her klinik ilk açılışta ücretsiz kredi alır (varsayılan 30; platform
 * yöneticisi panelden değiştirir). Her simülasyon seçilen kalite kademesinin
 * kredisini düşer (Orta 4 · Orta İyi 8 · İyi 12 · Çok İyi 18); otomatik
 * yerleşim düzeltmesi ücretsizdir, başarısız görevin kredisi iade edilir.
 * Kredi bitince klinik sahibi/yöneticisi uygulamadan paket alır; paket
 * tutarı kliniğin o ayki Dentifay faturasına yazılır.
 *
 * Sunucu uçları: `GET {API}/ai/smile/wallet`, `GET {API}/ai-credits/purchase-terms`,
 * `POST {API}/ai-credits/purchase` (kabul edilen koşulların sürümü + parmak iziyle).
 * Geliştirme yolunda (cihazda kie.ai anahtarı varsa) kredi sistemi devre dışıdır.
 */

// MARK: - Biçim

object SmileCreditFormat {
    private val tr = Locale.forLanguageTag("tr-TR")

    /** 4 → "4", 2.5 → "2,5" */
    fun credits(value: Double): String {
        val f = NumberFormat.getNumberInstance(tr)
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 1
        return f.format(value)
    }

    /** 2400 → "₺2.400" (Türkçe para biçimi) */
    fun lira(value: Double): String {
        val f = NumberFormat.getCurrencyInstance(tr)
        f.currency = Currency.getInstance("TRY")
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        return f.format(value)
    }
}

// MARK: - Modeller

@Serializable
data class SmileCreditTier(
    val key: String,
    val label: String,
    val credits: Double,
) {
    val id: String get() = key
}

@Serializable
data class SmileCreditPack(
    val id: String,
    val credits: Double,
    val priceTL: Double,
    val unitPriceTL: Double? = null,
    val savePct: Int? = null,
    val badge: String? = null,
    val popular: Boolean? = null,
    val bestValue: Boolean? = null,
)

@Serializable
data class SmileWallet(
    val availableCredits: Double,
    val freeGrantCredits: Double,
    val freeUsedCredits: Double,
    val freeRemainingCredits: Double,
    val balanceCredits: Double,
    val openingGrantCredits: Double? = null,
    val tiers: List<SmileCreditTier>,
    val fixCredits: Double? = null,
    val packs: List<SmileCreditPack>,
    val canPurchase: Boolean,
    /** `ROLE` (satın alma yetkisi yok) · `DEMO` (demo hesap) · null */
    val purchaseBlockedReason: String? = null,
    val thisMonth: Month? = null,
    val isDemo: Boolean? = null,
    /** Eski sunucuda alan YOK → `null` (limit yok sayılır; sunucu yine denetler). */
    val myLimit: MyLimit? = null,
) {
    @Serializable
    data class Month(
        val period: String,
        val purchasedTL: Double,
        val usedCredits: Double,
        val billed: Boolean,
    )

    /**
     * Kişinin bu ayki kredi limiti (Kredi Yönetimi) — limit yoksa sunucu `null` gönderir.
     * `remainingCredits` toplam ve gülüş simülasyonu limitlerinden KÜÇÜĞÜDÜR.
     */
    @Serializable
    data class MyLimit(
        val period: String,
        val limits: List<Line>,
        val remainingCredits: Double,
    ) {
        @Serializable
        data class Line(
            /** `*` = aylık toplam · `before_after` = gülüş simülasyonu */
            val scope: String,
            val limitCredits: Double,
            val usedCredits: Double,
            val remainingCredits: Double,
        )
    }

    fun price(model: SmileAIModel): Double =
        tiers.firstOrNull { it.key == model.tierKey }?.credits ?: model.creditsPerImage

    /** Hiç satın alma yapılmamış ve ücretsiz kredi sürüyor mu? */
    val isOnFreeCredits: Boolean get() = balanceCredits <= 0 && freeRemainingCredits > 0

    val purchaseBlockedMessage: String?
        get() = when (purchaseBlockedReason) {
            "DEMO" -> "Demo hesapta kredi satın alınamaz. Ek kredi için Dentifay ile iletişime geçin."
            "ROLE" -> "Kredi satın almayı klinik sahibi ya da yöneticisi yapabilir. Kredi paketi için onlara haber verin."
            else -> if (canPurchase) null else "Bu hesapla kredi satın alınamıyor."
        }
}

@Serializable
data class SmilePurchaseResult(
    val creditsGranted: Double,
    val priceTL: Double,
    val period: String? = null,
    val availableCredits: Double? = null,
    val replay: Boolean? = null,
)

/**
 * Kredi Satın Alma Koşulları (`GET {API}/ai-credits/purchase-terms?packId=`).
 *
 * Metin sunucuda paketten ve kliniğin tarifesinden TÜRER (tutar sözleşme indirimini içerir).
 * Satın alma isteği kabul edilen `surum` + `ozet`i (parmak izi) taşır; sunucu kabulsüz isteği
 * 428, değişmiş metnin eski kabulünü 409 ile reddeder ve iki yanıtta da güncel metni döner.
 */
@Serializable
data class SmilePurchaseTerms(
    val surum: String,
    val ozet: String,
    val baslik: String,
    val taslak: Boolean? = null,
    val packId: String,
    val listeKurus: Int,
    val odenecekKurus: Int,
    val bolumler: List<Bolum>,
) {
    @Serializable
    data class Bolum(
        val baslik: String,
        val maddeler: List<String>,
    ) {
        val id: String get() = baslik
    }

    /** Faturaya yansıyacak tutar (TL) — kliniğin sözleşmesindeki kredi indirimi dahil. */
    val odenecekTL: Double get() = odenecekKurus.toDouble() / 100
}

/** `GET {API}/ai-credits/purchase-terms` yanıt zarfı (`{ ok, terms }`). */
@Serializable
private data class SmilePurchaseTermsEnvelope(val terms: SmilePurchaseTerms)

// MARK: - İstemci

class SmileCreditsClient(
    /** `{API}` kökü (ör. https://dentifay.com/api) — `AppConfig`ten okunur. */
    val base: String,
) {
    sealed class ClientError(message: String) : Exception(message) {
        class notLoggedIn : ClientError("Kredileri görmek için Dentifay hesabınızla giriş yapın.")
        class notAvailableOnServer : ClientError("Kredi sistemi sunucuda henüz etkin değil. Sistem yöneticinize bildirin.")
        class forbidden(val m: String?) : ClientError(m ?: "Bu işlem için yetkiniz yok.")
        class server(val code: Int, val m: String?) : ClientError(m ?: "Sunucu hatası ($code). Tekrar deneyin.")
        class badResponse : ClientError("Sunucudan beklenmeyen yanıt alındı.")
        /** Kabul edilen koşullar güncel değil ya da hiç gönderilmedi (428/409); güncel metin ekte. */
        class termsOutdated(val terms: SmilePurchaseTerms?, val m: String?) :
            ClientError(m ?: "Satın alma koşulları güncellendi; yeni metni okuyup yeniden onaylayın.")
    }

    companion object {
        /** APIClient ile aynı çerez deposu (oturum). */
        private val session: OkHttpClient by lazy {
            ApiClient.http.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
        }
        private val JSON = "application/json".toMediaType()
    }

    suspend fun wallet(): SmileWallet {
        val url = (base + "/ai/smile/wallet").toHttpUrlOrNull() ?: throw ClientError.badResponse()
        val data = send(Request.Builder().url(url).get().header("Cache-Control", "no-cache").build())
        return try {
            DfyJson.decodeFromString(SmileWallet.serializer(), data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ClientError.badResponse()
        }
    }

    /** Paketin satın alma koşulları (metin + sürüm + parmak izi). Satın alma ile AYNI yetki. */
    suspend fun purchaseTerms(packId: String): SmilePurchaseTerms {
        val url = (base + "/ai-credits/purchase-terms").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("packId", packId)?.build() ?: throw ClientError.badResponse()
        val data = send(Request.Builder().url(url).get().header("Cache-Control", "no-cache").build())
        return try {
            DfyJson.decodeFromString(SmilePurchaseTermsEnvelope.serializer(), data.toString(Charsets.UTF_8)).terms
        } catch (e: Exception) {
            throw ClientError.badResponse()
        }
    }

    /**
     * Paketi satın alır. `idempotencyKey` aynı kaldıkça (ağ kopup yeniden
     * denense bile) sunucu tek satın alma yazar. Kabul edilen koşulların sürümü ve
     * parmak izi (`kosullar`) ZORUNLUDUR — sunucu onsuz satın almaz (428).
     */
    suspend fun purchase(packId: String, terms: SmilePurchaseTerms, idempotencyKey: String): SmilePurchaseResult {
        val url = (base + "/ai-credits/purchase").toHttpUrlOrNull() ?: throw ClientError.badResponse()
        val body = buildJsonObject {
            put("packId", packId)
            putJsonObject("kosullar") {
                put("surum", terms.surum)
                put("ozet", terms.ozet)
            }
        }
        val req = Request.Builder().url(url)
            .post(DfyJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .build()
        val data = send(req)
        return try {
            DfyJson.decodeFromString(SmilePurchaseResult.serializer(), data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ClientError.badResponse()
        }
    }

    /** Önce/sonra görselini hastanın dosyasına ("Önce/Sonra" kategorisi) yükler. */
    suspend fun uploadToPatientFile(patientId: String, jpeg: ByteArray, fileName: String, note: String) {
        val safeId = android.net.Uri.encode(patientId, "/")
        val url = (base + "/patients/$safeId/media").toHttpUrlOrNull() ?: throw ClientError.badResponse()
        val boundary = "dentifay-${UUID.randomUUID().toString().uppercase()}"
        val capturedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        val multipart = MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart("category", "PHOTO_BEFORE_AFTER")
            .addFormDataPart("note", note)
            .addFormDataPart("tags", "gulus-simulasyonu,yapay-zeka")
            .addFormDataPart("capturedAt", capturedAt)
            .addFormDataPart("files", "$fileName.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val data = send(Request.Builder().url(url).post(multipart).build(), notFound = ClientError.server(404, "Hasta bulunamadı."))
        // Sunucu tanımadığı dosyayı sessizce atlamaz, `rejected` listesinde döner.
        val json = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val media = json?.get("media") as? JsonArray
        if (media != null && media.isEmpty()) {
            throw ClientError.server(400, "Görsel hasta dosyasına eklenemedi.")
        }
    }

    private suspend fun send(req: Request, notFound: ClientError = ClientError.notAvailableOnServer()): ByteArray {
        val raw = ApiClient.execute(req, session)
        val json = runCatching { DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val message = json?.str("error")?.takeIf { it.contains(" ") } ?: json?.str("message") ?: json?.str("msg")
        return when (raw.status) {
            in 200..299 -> raw.body
            401 -> {
                SessionEvents.report401(raw.body, req.url.encodedPath)
                throw ClientError.notLoggedIn()
            }
            403 -> throw ClientError.forbidden(message)
            404 -> throw notFound
            409, 428 -> {
                // Satın alma koşulları: kabul yok (428) ya da metin değişmiş (409) — güncel metin gövdede.
                val code = json?.str("code")
                if (code != null && code.startsWith("E_PURCHASE_TERMS")) {
                    val terms = (json?.get("terms") as? JsonObject)?.let {
                        runCatching { DfyJson.decodeFromJsonElement(SmilePurchaseTerms.serializer(), it) }.getOrNull()
                    }
                    throw ClientError.termsOutdated(terms, message)
                }
                throw ClientError.server(raw.status, message)
            }
            else -> throw ClientError.server(raw.status, message)
        }
    }
}

/**
 * Kullanıcıya gösterilecek hata metni (Swift `error.localizedDescription`).
 * Ağ hataları iOS'un Türkçe URLError metinleriyle aynı anlamda gösterilir.
 */
internal fun creditErrorText(e: Throwable): String = when (e) {
    is ApiException.NetworkError -> when (e.cause) {
        is SocketTimeoutException -> "İstek zaman aşımına uğradı."
        is UnknownHostException -> "İnternet bağlantısı çevrimdışı görünüyor."
        is ConnectException -> "Sunucuya bağlanılamadı."
        else -> "Ağ bağlantısı kesildi."
    }
    else -> e.message ?: "Bilinmeyen bir hata oluştu."
}

// MARK: - Durum

/** Uygulama genelinde tek kredi durumu (Gülüş Simülasyonu, Ayarlar, AI Hub). */
class SmileCreditsStore private constructor() {
    companion object {
        val shared = SmileCreditsStore()
    }

    var wallet by mutableStateOf<SmileWallet?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isPurchasing by mutableStateOf(false)
        private set

    private val scope = MainScope()
    private val client: SmileCreditsClient get() = SmileCreditsClient(AppConfig.apiBaseURL)

    /**
     * Gülüş simülasyonu krediyle mi çalışıyor? Geliştirme yolunda (cihazda kie.ai
     * anahtarı) simülasyon için kredi düşülmez. ⚠ Diğer yapay zekâ özellikleri
     * (asistan, sesli mod …) her derlemede sunucuda krediyle çalışır — bu yüzden
     * cüzdan `isMetered`tan bağımsız okunur (`refresh`).
     */
    val isMetered: Boolean
        get() = SmileSimulationConfig.endpoint is KieImageEditClient.Endpoint.proxy

    /** Bilinmiyorsa null (sunucu yine de denetler). */
    fun canAfford(credits: Double): Boolean? {
        val w = wallet
        if (!isMetered || w == null) return null
        return w.availableCredits + 1e-9 >= credits
    }

    /** Kişinin aylık kredi limiti bu işe yetmiyor mu? (Kredi satın almak bunu AÇMAZ.) */
    fun exceedsPersonalLimit(credits: Double): Boolean {
        val limit = wallet?.myLimit
        if (!isMetered || limit == null) return false
        return limit.remainingCredits + 1e-9 < credits
    }

    fun price(model: SmileAIModel): Double = wallet?.price(model) ?: model.creditsPerImage

    /**
     * Uçuştaki yenileme sayısı — satın alma sonrası yenileme, açık bir yenileme
     * yüzünden atlanmasın (en son yanıt kazanır).
     */
    private var inFlight = 0
    private var generation = 0

    /**
     * Bir özellik kredi harcadı ya da 402 aldı → cüzdanı gösteren ekran varsa (cüzdan
     * yüklüyse) arka planda tazele. Hiçbir ekran göstermiyorsa ağ çağrısı yapılmaz.
     */
    fun noteBalanceChanged() {
        if (wallet == null) return
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        inFlight += 1
        generation += 1
        val mine = generation
        isLoading = true
        try {
            val w = client.wallet()
            if (mine != generation) return
            wallet = w
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine != generation) return
            // Oturum kapandıysa (başka hesaba geçiş) eski kliniğin kredisi gösterilmesin.
            if (e is SmileCreditsClient.ClientError.notLoggedIn) wallet = null
            errorMessage = creditErrorText(e)
        } finally {
            inFlight -= 1
            isLoading = inFlight > 0
        }
    }

    /**
     * Paket satın alır; tutar kliniğin bu ayki Dentifay faturasına yazılır. `terms`, kullanıcının
     * okuyup onayladığı Kredi Satın Alma Koşulları'dır (`CreditPurchaseSheet`).
     */
    suspend fun purchase(pack: SmileCreditPack, terms: SmilePurchaseTerms): SmilePurchaseResult {
        isPurchasing = true
        try {
            // Aynı dokunuş için tek anahtar: ağ hatasında yeniden deneme çift satın alma yazmaz.
            val key = "mobil-${pack.id}-${UUID.randomUUID().toString().lowercase()}"
            val client = this.client
            var attempt = 0
            while (true) {
                try {
                    val result = client.purchase(pack.id, terms, key)
                    refresh()
                    return result
                } catch (e: ApiException.NetworkError) {
                    if (attempt >= 2) throw e
                    attempt += 1
                    delay(1500)
                }
            }
        } finally {
            isPurchasing = false
        }
    }

    /** Paketin satın alma koşulları (pencerede gösterilir, onaylanınca `purchase(pack, terms)`e geçer). */
    suspend fun purchaseTerms(pack: SmileCreditPack): SmilePurchaseTerms = client.purchaseTerms(pack.id)

    fun reset() {
        wallet = null
        errorMessage = null
    }

    /** Yalnız @Preview: örnek cüzdanla doldurur. */
    internal fun previewFill(w: SmileWallet?) {
        wallet = w
    }
}
