package com.dentifay.app.credit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.FlexibleIntOrNullSerializer
import com.dentifay.app.core.SessionEvents
import com.dentifay.app.core.Yetki
import com.dentifay.app.core.YetkiReddi
import com.dentifay.app.core.str
import com.dentifay.app.smile.KieImageEditClient
import com.dentifay.app.smile.SmileAIModel
import com.dentifay.app.smile.SmileSimulationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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
 * Kliniğin kredi cüzdanı (tek kredi sistemi — Ekleme 16) ve kredi paketi satın alma.
 *
 * Uçlar: `GET {API}/ai/smile/wallet` (cüzdan özeti; `/ai-credits/usage`ın `wallet`ı AYNI biçim) ·
 * `GET {API}/ai-credits/purchase-terms?packId=` · `POST {API}/ai-credits/purchase` — satın almada koşul kabulü
 * ZORUNLU (`kosullar {surum, ozet}` + `Idempotency-Key`; DentAIWeb Ekleme 18 · 37C). Satın almayı varsayılanda
 * Sahip yapar (`fin.credits`; Sahip Yönetici ve Klinik Müdürü'ne açabilir) — karar sunucunun (`canPurchase`).
 * Geliştirme derlemesinde (cihazda geliştirme anahtarı varsa) yalnız gülüş simülasyonu kredisiz çalışır.
 */

// MARK: - Biçim

/**
 * Gülüş kademesinin bedel metinleri — kart, "harcanacak" satırı ve kredi ipucu AYNI sayıdan (M-E3.1). Kredi açıklayan
 * her metin "ortalama" der (M-E4.2 — kesin "{n} kredi kullanır" YOK); sayı yalnız sunucunun `tiers`ından, null → "—".
 */
object GulusBedeli {
    fun kart(kredi: Double?): String = kredi?.let { "ortalama ${SmileCreditFormat.credits(it)} kredi" } ?: "—"
    fun harcanacak(kredi: Double?): String? = kredi?.let { "ortalama ${SmileCreditFormat.credits(it)} kredi harcanacak" }

    /** "Bu işlem ortalama {n} kredi kullanır." — sayı `tiers`tan. */
    fun ipucu(kredi: Double?): String? = kredi?.let { "Bu işlem ortalama ${SmileCreditFormat.credits(it)} kredi kullanır." }
}

object SmileCreditFormat {
    private val tr = Locale.forLanguageTag("tr-TR")

    // Biçimleyiciler pahalıdır ve iş parçacığı güvenli değildir: iş parçacığı başına bir kez kurulur (listelerde her
    // satır için yeniden kurulmaz).
    private val sayi = ThreadLocal.withInitial {
        NumberFormat.getNumberInstance(tr).apply { minimumFractionDigits = 0; maximumFractionDigits = 1 }
    }
    private val para = ThreadLocal.withInitial {
        NumberFormat.getCurrencyInstance(tr).apply {
            currency = Currency.getInstance("TRY")
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
    }

    /** 4 → "4", 2.5 → "2,5" */
    fun credits(value: Double): String = sayi.get()!!.format(value)

    /** 2400 → Türkçe para biçimi ("₺2.400") */
    fun lira(value: Double): String = para.get()!!.format(value)
}

/** Sayı ya da sayı metni ("10.00") olabilen isteğe bağlı ondalık; okunamazsa null (Swift `try? decode` yedeği). */
@OptIn(ExperimentalSerializationApi::class)
internal object EsnekOndalikSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EsnekOndalik", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): Double? {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeDouble()
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
    }
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

// MARK: - Modeller

@Serializable
data class SmileCreditTier(
    val key: String,
    val label: String,
    /** Sözleşme §10 (Ekleme 19 · 38D): güne bağlı; kur ya da fiyat yoksa `null` → kart "—", kademe seçilemez. */
    val credits: Double? = null,
) {
    val id: String get() = key
}

/**
 * Kredi paketi. Fiyat okunamazsa sunucu `priceTL: null` gönderir (satın alma kapanır — Ek MO.21l).
 * `unitPriceTL` ve `savePct` sunucudan; mobil hesaplamaz.
 */
@Serializable
data class SmileCreditPack(
    val id: String,
    val credits: Double,
    val priceTL: Double? = null,
    val listPriceTL: Double? = null,
    /** Sunucu indirimi metin olarak gönderebilir ("10.00"). */
    @Serializable(with = EsnekOndalikSerializer::class) val discountPct: Double? = null,
    val unitPriceTL: Double? = null,
    @Serializable(with = FlexibleIntOrNullSerializer::class) val savePct: Int? = null,
    val badge: String? = null,
    val popular: Boolean? = null,
    val bestValue: Boolean? = null,
) {
    /** Liste fiyatı indirimli fiyattan büyükse üstü çizili gösterilir (Ek MO.21h). */
    val indirimVar: Boolean get() = (listPriceTL ?: 0.0) > (priceTL ?: Double.POSITIVE_INFINITY)
}

@Serializable
data class SmileWallet(
    val availableCredits: Double,
    val freeGrantCredits: Double,
    val freeUsedCredits: Double,
    val freeRemainingCredits: Double,
    val balanceCredits: Double,
    val tiers: List<SmileCreditTier>,
    val fixCredits: Double? = null,
    val packs: List<SmileCreditPack>,
    val canPurchase: Boolean,
    /** `DEMO` · `ROLE` · `PRICE` · null */
    val purchaseBlockedReason: String? = null,
    val thisMonth: Month? = null,
    val isDemo: Boolean? = null,
    val lowBalanceWarnCredits: Double? = null,
    val isLow: Boolean? = null,
) {
    /**
     * Bu ay. `purchasedTL` YALNIZ tutar görme yetkisinde (`comm.usage`/`fin.credits`) — yoksa alan YOK
     * ve "Bu ay faturaya" kutusu ÇİZİLMEZ (hekim ₺ görmez).
     */
    @Serializable
    data class Month(
        val period: String,
        val purchasedTL: Double? = null,
        val overageTL: Double? = null,
        val usedCredits: Double,
        val billed: Boolean,
    )

    /**
     * Kademenin kredisi YALNIZ sunucunun `tiers`ından (M-E3.1); listede yoksa null → kart "—", kademe seçilemez.
     * Sessiz varsayılan sayı YOK — bedel güne (kura) bağlıdır ve mobil hesaplamaz. (Swift `price(of:)`.)
     */
    fun price(model: SmileAIModel): Double? {
        val key = model.tierKey ?: return null
        return tiers.firstOrNull { it.key == key }?.credits
    }

    /** 409 `E_CREDIT_PRICE_CHANGED` gövdesindeki güncel bedel kartta hemen görünsün (sunucunun sayısı; ardından `/wallet`). */
    fun bedelGuncellendi(key: String, credits: Double): SmileWallet =
        copy(tiers = tiers.map { if (it.key == key) it.copy(credits = credits) else it })

    /** Hiç satın alma yapılmamış ve ücretsiz kredi sürüyor mu? */
    val isOnFreeCredits: Boolean get() = balanceCredits <= 0 && freeRemainingCredits > 0

    /** Ek MO.21j–m. Mobil satın alma yetkisini kendisi KARARLAŞTIRMAZ: yalnız `canPurchase` + sunucunun nedeni. */
    val purchaseBlockedMessage: String?
        get() = when (purchaseBlockedReason) {
            "DEMO" -> "Demo hesapta kredi satın alınamaz. Ek kredi için Dentifay ile iletişime geçin."
            "ROLE" -> "Ek kredi satın alma yetkisi varsayılan olarak kliniğinizin Sahibindedir; Sahip bu yetkiyi Yönetici ve Klinik Müdürü'ne açabilir. Kredi paketi için Sahibe haber verin."
            "PRICE" -> "Paket fiyatı şu an okunamıyor."
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
 * (`taslak` alanı modelde YOK — sunucu gönderse de çizilecek alan yok.)
 */
@Serializable
data class SmilePurchaseTerms(
    val surum: String,
    val ozet: String,
    val baslik: String,
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
    val listeTL: Double get() = listeKurus.toDouble() / 100

    /** Liste fiyatı üstü çizili yalnız indirim varken. */
    val indirimVar: Boolean get() = listeKurus > odenecekKurus
}

/** `GET {API}/ai-credits/purchase-terms` yanıt zarfı (`{ ok, terms }`). */
@Serializable
private data class SmilePurchaseTermsEnvelope(val terms: SmilePurchaseTerms)

// MARK: - İstemci

class SmileCreditsClient(
    /** `{API}` kökü (ör. https://dentifay.com/api) — `AppConfig`ten okunur. */
    val base: String,
) {
    /** `message` = Swift `errorDescription`. Kodsuz 403 (`forbidden` + Ek MO3.7 cümlesi) yetki reddidir (M-E7.5). */
    sealed class ClientError(message: String) : Exception(message), YetkiReddi {
        override val yetkiReddiMi: Boolean get() = false

        class notLoggedIn : ClientError("Kredileri görmek için Dentifay hesabınızla giriş yapın.")
        class notAvailableOnServer : ClientError("Kredi bilgisi şu an alınamıyor; biraz sonra tekrar deneyin.")
        class forbidden(val m: String?) : ClientError(m ?: Yetki.EYLEM_YETKISIZ_METNI) {
            override val yetkiReddiMi: Boolean get() = m == Yetki.EYLEM_YETKISIZ_METNI
        }
        class server(val code: Int, val m: String?) : ClientError(m ?: "Sunucu hatası ($code). Tekrar deneyin.")
        class badResponse : ClientError("Sunucudan beklenmeyen yanıt alındı.")

        /** Kabul edilen koşullar güncel değil ya da hiç gönderilmedi (428/409); güncel metin ekte (Ek MO2.3). */
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
        return coz(SmileWallet.serializer(), data)
    }

    /** Paketin satın alma koşulları (metin + sürüm + parmak izi). Satın alma ile AYNI yetki. */
    suspend fun purchaseTerms(packId: String): SmilePurchaseTerms {
        val url = (base + "/ai-credits/purchase-terms").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("packId", packId)?.build() ?: throw ClientError.badResponse()
        val data = send(Request.Builder().url(url).get().header("Cache-Control", "no-cache").build())
        return coz(SmilePurchaseTermsEnvelope.serializer(), data).terms
    }

    /**
     * Paketi satın alır. `idempotencyKey` aynı kaldıkça (ağ kopup yeniden denense bile) sunucu tek satın alma yazar.
     * Kabul edilen koşulların sürümü ve parmak izi (`kosullar`) ZORUNLUDUR — sunucu onsuz satın almaz (428).
     * Saf istek kurucu (test edilebilir): gövde `{packId, kosullar: {surum, ozet}}`, `Idempotency-Key` başlığı.
     */
    fun satinAlmaIstegi(packId: String, terms: SmilePurchaseTerms, idempotencyKey: String): Request {
        val url = (base + "/ai-credits/purchase").toHttpUrlOrNull() ?: throw ClientError.badResponse()
        val body = buildJsonObject {
            put("packId", packId)
            putJsonObject("kosullar") {
                put("surum", terms.surum)
                put("ozet", terms.ozet)
            }
        }
        return Request.Builder().url(url)
            .post(DfyJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .build()
    }

    suspend fun purchase(packId: String, terms: SmilePurchaseTerms, idempotencyKey: String): SmilePurchaseResult {
        val data = send(satinAlmaIstegi(packId, terms, idempotencyKey))
        return coz(SmilePurchaseResult.serializer(), data)
    }

    /** Önce/sonra görselini hastanın dosyasına ("Önce/Sonra" kategorisi) yükler. */
    suspend fun uploadToPatientFile(patientId: String, jpeg: ByteArray, fileName: String, note: String) {
        // Yol parçası kodlanarak eklenir ("/" dahil) — hasta kimliği yolu bozamaz.
        val url = (base + "/patients").toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment(patientId)?.addPathSegment("media")?.build() ?: throw ClientError.badResponse()
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
        val data = send(Request.Builder().url(url).post(multipart).build()) { ClientError.server(404, "Hasta bulunamadı.") }
        // Sunucu tanımadığı dosyayı sessizce atlamaz, `rejected` listesinde döner.
        val json = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val media = json?.get("media") as? JsonArray
        if (media != null && media.isEmpty()) {
            throw ClientError.server(400, "Görsel hasta dosyasına eklenemedi.")
        }
    }

    /** Gövdeyi ana iş parçacığı dışında çözer; biçim tutmazsa `badResponse`. */
    private suspend fun <T> coz(deserializer: KSerializer<T>, data: ByteArray): T = withContext(Dispatchers.Default) {
        try {
            DfyJson.decodeFromString(deserializer, data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ClientError.badResponse()
        }
    }

    private suspend fun send(req: Request, notFound: () -> ClientError = { ClientError.notAvailableOnServer() }): ByteArray {
        val raw = ApiClient.execute(req, session)
        val json = runCatching { DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val message = json?.str("error")?.takeIf { it.contains(" ") } ?: json?.str("message") ?: json?.str("msg")
        return when (raw.status) {
            in 200..299 -> raw.body
            401 -> {
                SessionEvents.report401(raw.body, req.url.encodedPath)
                throw ClientError.notLoggedIn()
            }
            // Kodsuz 403 → Ek MO3.7 cümlesi (yetki reddi, parça çizilmez); iş kodlu 403 → sunucunun cümlesi.
            403 -> throw ClientError.forbidden(Yetki.yasakMetni(raw.body))
            404 -> throw notFound()
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

/** Uygulama genelinde tek kredi durumu (Gülüş Simülasyonu, Ayarlar, Analiz Merkezi). */
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

    /** Cüzdan ucu kodsuz 403 döndü — kredi parçaları çizilmez (M-E7.5). */
    var yetkisiz by mutableStateOf(false)
        private set

    private val scope = MainScope()
    private val client: SmileCreditsClient get() = SmileCreditsClient(AppConfig.apiBaseURL)

    /** Yalnız önizleme: örnek cüzdan yüklüyken ağ çağrısı yapılmaz. */
    private var ornekVeri = false

    /**
     * Gülüş simülasyonu krediyle mi çalışıyor? Yalnız geliştirme derlemesinde (cihazda geliştirme anahtarı)
     * simülasyon için kredi düşülmez; yayın derlemesinde daima true. Normal klinikte bütün kredili işlemler sunucuda
     * ücretlendirilir — cüzdan `isMetered`tan bağımsız okunur (`refresh`).
     */
    val isMetered: Boolean
        get() = SmileSimulationConfig.endpoint is KieImageEditClient.Endpoint.proxy

    /** Bilinmiyorsa null (sunucu yine de denetler). */
    fun canAfford(credits: Double): Boolean? {
        val w = wallet
        if (!isMetered || w == null) return null
        return w.availableCredits + 1e-9 >= credits
    }

    /** Kademenin kredisi yalnız sunucunun `tiers`ından; bilinmiyorsa null (Swift `price(of:)`). */
    fun price(model: SmileAIModel): Double? = wallet?.price(model)

    /** 409 sonrası: sunucunun verdiği güncel bedel karta yazılır, ardından cüzdan tazelenir. */
    fun bedelGuncellendi(model: SmileAIModel, credits: Double?) {
        val key = model.tierKey
        val w = wallet
        if (credits != null && key != null && w != null) wallet = w.bedelGuncellendi(key, credits)
        scope.launch { refresh() }
    }

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
        if (ornekVeri) return
        inFlight += 1
        generation += 1
        val mine = generation
        isLoading = true
        try {
            val w = client.wallet()
            if (mine != generation) return
            wallet = w
            errorMessage = null
            yetkisiz = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine != generation) return
            // Oturum kapandıysa (başka hesaba geçiş) eski kliniğin kredisi gösterilmesin.
            if (e is SmileCreditsClient.ClientError.notLoggedIn) wallet = null
            // Kodsuz 403: kredi parçası ÇİZİLMEZ, kırmızı kart yok (M-E7.5).
            yetkisiz = Yetki.reddiMi(e)
            errorMessage = if (yetkisiz) null else creditErrorText(e)
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

    /** Yalnız @Preview: örnek cüzdanla doldurur (ağ çağrısı yapılmaz). */
    internal fun ornekYukle(w: SmileWallet) {
        ornekVeri = true
        wallet = w
    }
}

// MARK: - Satın alma penceresinin saf durumu (Ekleme 18 · 37C; M-E2.1)

/**
 * "Satın al" yalnız koşul metni yüklüyken VE onay kutusu işaretliyken açılır; 428/409'da gövdedeki güncel metin
 * gösterilir, kutu TEMİZLENİR, pencere açık kalır (sunucunun cümlesi; yoksa Ek MO2.3). Değişmez değer — her adım kopya döner.
 */
data class SatinAlmaDurumu(
    val terms: SmilePurchaseTerms? = null,
    val kabul: Boolean = false,
    val not: String? = null,
) {
    val gonderilebilir: Boolean get() = terms != null && kabul

    fun kosulGuncellendi(yeni: SmilePurchaseTerms?, mesaj: String?): SatinAlmaDurumu = copy(
        terms = yeni ?: terms,
        kabul = false,
        not = mesaj ?: "Satın alma koşulları güncellendi; yeni metni okuyup yeniden onaylayın.",
    )
}
