package com.dentifay.app.credit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.SessionEvents
import com.dentifay.app.core.Yetki
import com.dentifay.app.core.YetkiReddi
import com.dentifay.app.core.hataCumlesi
import com.dentifay.app.core.serverCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Kredi Yönetimi — web'in `/krediler` sayfasıyla AYNI dört bölüm ve AYNI uçlar
 * (DentAIWeb `docs/MOBILE_AGENT_CONTRACT.md` §1, §10; Ekleme 16 · 35F ve Ekleme 18 · 37C–37D;
 * sunucu `backend/routes/ai-credits.js` — `kredi-merkezi.js` okuyucusu):
 *
 *   GET  {API}/ai-credits/usage                          cüzdan + aylık döküm (`months[]`)
 *   GET  {API}/ai-credits/analiz?from&to                 özet · seri · özellik · kişi · işlem · ısı haritası · kaç gün yeter
 *   GET  {API}/ai-credits/transactions?cursor&limit[&from&to&userId&feature&tur]   imleçli, süzgeçli hareketler
 *   GET  {API}/ai-credits/transactions/:id/kosullar      satın almada kabul edilen koşullar (`fin.credits`)
 *   GET  {API}/ai-credits/people?ay&cursor&limit         kişiler × ay kullanımı × limitler
 *   PUT  {API}/ai-credits/limits/:userId                 TAM limit seti (`If-Match` ZORUNLU)
 *   GET|PUT {API}/ai-credits/settings                    düşük bakiye uyarı eşiği (`If-Match` ZORUNLU)
 *   GET  {API}/ai-credits/tarife                         kredi ile çalışan işlemler (`KrediTarifesi`)
 *   GET  {API}/edocument/kullanim · POST /kontor/yenile  e-Fatura kartı (salt okur)
 *
 * Görme kapısı `comm.usage` VEYA `fin.credits`; satın alma ve limit yazımı `fin.credits` (Sahip'in yönettiği
 * yetenek — varsayılan yalnız Sahip). Mobil hiçbir toplamı, kalanı, limiti, süreyi ya da ücreti HESAPLAMAZ (M0-4).
 */

// MARK: - Etiketler (sunucu kimlik taşır; metinler web `ns/krediler` ile aynı — Ek MO.21)

object CreditLabels {
    /** Ek MO.21p — özellik. */
    val features: Map<String, String> = mapOf(
        "ai_agent" to "Dentifay Asistan",
        "ai_center" to "Analiz Merkezi",
        "xray" to "Röntgen analizi",
        "messaging" to "İletişim",
        "before_after" to "Gülüş simülasyonu",
        "purchase" to "Kredi paketi",
        "platform_grant" to "Dentifay tanımı",
        "demo_admin" to "Dentifay düzeltmesi",
    )

    /** Ek MO.21p — işlem. */
    val actions: Map<String, String> = mapOf(
        "ai_agent:chat" to "Dentifay Asistan sorusu",
        "ai_center:audience" to "Hedef kitle sorgusu",
        "ai_center:report_export" to "Excel rapor önizlemesi",
        "ai_center:insight" to "Modül yorumu",
        "ai_center:briefing" to "Klinik brifingi",
        "xray:analyze" to "Röntgen analizi",
        "messaging:suggest_message" to "Mesaj taslağı",
        "before_after:orta" to "Gülüş simülasyonu · Orta",
        "before_after:orta_iyi" to "Gülüş simülasyonu · Orta İyi",
        "before_after:iyi" to "Gülüş simülasyonu · İyi",
        "before_after:cok_iyi" to "Gülüş simülasyonu · Çok İyi",
        "before_after:duzeltme" to "Gülüş simülasyonu · otomatik yerleşim düzeltmesi",
        "purchase:credit_pack" to "Kredi paketi",
        // M-E4.1: klinik açılışta kredi almaz — bu satır Dentifay'ın tanımladığı ücretsiz kredidir.
        "platform_grant:opening" to "Dentifay'ın tanımladığı kredi",
        "platform_grant:placeholder_removed" to "Düzeltme",
    )

    /** Ek MO.21o — hareket türü (sunucu `HAREKET_TURU`); tanınmayan "Diğer". */
    val kinds: Map<String, String> = mapOf(
        "kullanim" to "Kullanım", "iade" to "İade", "satin_alma" to "Satın alma",
        "tanim" to "Dentifay tanımı", "duzeltme" to "Düzeltme", "diger" to "Diğer",
    )

    /** Süzgeç seçenekleri — sunucunun `tur` kümesiyle aynı (`diger` dahil). */
    val kindOrder = listOf("kullanim", "iade", "satin_alma", "tanim", "duzeltme", "diger")

    /** Ek MO.21o — kaynak (yalnız kullanımda). */
    val sources: Map<String, String> = mapOf("ucretsiz" to "Ücretsiz kredi", "satin_alinmis" to "Satın alınmış kredi")

    val departments: Map<String, String> = mapOf(
        "OWNER" to "Sahip", "ADMIN" to "Yönetici", "MANAGER" to "Klinik Müdürü", "BANKO" to "Banko",
        "CAGRI_MERKEZI" to "Çağrı Merkezi Sorumlusu", "HASTA_KOORDINATORU" to "Hasta Koordinatörü",
        "KLINIK_KOORDINATORU" to "Klinik Koordinatörü", "SATIN_ALMA" to "Satın Alma", "MUHASEBE" to "Muhasebe",
        "GOZLEMCI" to "Gözlemci", "ASISTAN" to "Asistan", "HEKIM_GENEL" to "Genel Diş Hekimi",
        "HEKIM_ENDODONTI" to "Endodonti", "HEKIM_ORTODONTI" to "Ortodonti", "HEKIM_PEDODONTI" to "Pedodonti",
        "HEKIM_PERIO" to "Periodontoloji", "HEKIM_PROTEZ" to "Protez",
    )
    val weekdays = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")

    /** Ekleme 20 · 39B — Dentifay'ın tanımladığı (ücretsiz) kredi hareketi. */
    const val tanimMetni = "Dentifay'ın tanımladığı kredi"

    fun feature(key: String?): String = features[key ?: ""] ?: "Diğer"
    fun kind(key: String?): String = kinds[key ?: ""] ?: "Diğer"

    /** İşlem adı: `feature:action` → Ek MO.21p; yoksa özellik adı; tanınmayan "Diğer". */
    fun operation(feature: String?, action: String?): String =
        actions["${feature ?: ""}:${action ?: ""}"] ?: this.feature(feature)
}

// MARK: - Modeller

/** Kişi (sunucu `kisiGorunumu`): ad yoksa Ek MO.21s "Bilinmeyen kişi"; ayrılmış/pasif ek. */
@Serializable
data class CreditPerson(
    val id: String,
    val name: String? = null,
    val department: String? = null,
    val active: Boolean? = null,
    val former: Boolean? = null,
) {
    val displayName: String
        get() {
            val ad = name?.takeIf { it.isNotEmpty() } ?: "Bilinmeyen kişi"
            return if (former == true) "$ad (ayrılmış)" else ad
        }
    val departmentLabel: String? get() = department?.let { CreditLabels.departments[it] }
}

/** `GET /ai-credits/usage` → `{wallet, months[]}`; `wallet` gülüş cüzdanıyla AYNI biçim. */
@Serializable
data class CreditUsage(
    val wallet: SmileWallet? = null,
    val months: List<Month>,
    val isDemo: Boolean? = null,
) {
    /** Aylık döküm satırı. `purchasedTL` YALNIZ tutar görme yetkisinde; yoksa satır ₺ çizmez. */
    @Serializable
    data class Month(
        val period: String,
        val usedCredits: Double,
        val purchasedTL: Double? = null,
        val overageTL: Double? = null,
        val billed: Boolean,
    ) {
        val id: String get() = period

        /** Ek MO.21f/g — durum yalnız ödenecek bir tutar varken (`purchasedTL > 0 || overageTL > 0`); yoksa null. */
        val durumMetni: String?
            get() {
                if ((purchasedTL ?: 0.0) <= 0 && (overageTL ?: 0.0) <= 0) return null
                return if (billed) "Faturalandı" else "Ay sonu Dentifay faturasına yansır"
            }
    }
}

/** `GET /ai-credits/analiz` (sunucu `kredi-merkezi#ozetle` + Ekleme 18 · 37D.1–2 alanları). */
@Serializable
data class CreditAnalysis(
    val tz: String? = null,
    val from: String? = null,
    val to: String? = null,
    val truncated: Boolean? = null,
    /** "Kaç gün yeter" — sunucu (son 30 günün net kullanımıyla); kullanım yoksa `null` → "—". */
    val runwayDays: Int? = null,
    val last30DaysCredits: Double? = null,
    val unit: String,
    val summary: Summary,
    val series: List<Bucket>,
    val features: List<FeatureRow>,
    val actions: List<ActionRow>,
    val heatmap: List<List<Cell>>,
    val users: List<UserRow>,
) {
    @Serializable
    data class Summary(
        val usedCredits: Double,
        val refundedCredits: Double,
        val operations: Int,
        val purchasedCredits: Double,
        val purchases: Int,
        /** Aralıktaki satın almaların ödenen tutarı — bilinemiyorsa ya da yetki yoksa alan YOK (TL çizilmez). */
        val paidTL: Double? = null,
    )

    @Serializable
    data class Bucket(
        val key: String,
        val total: Double,
        val byFeature: Map<String, Double>,
    ) {
        val id: String get() = key
    }

    @Serializable
    data class FeatureRow(
        val feature: String,
        val credits: Double,
        val operations: Int,
        val refunds: Int? = null,
        /** Aralıktaki pay (%) — sunucu hesaplar. */
        val share: Int? = null,
    ) {
        val id: String get() = feature
    }

    @Serializable
    data class ActionRow(
        val feature: String,
        val action: String? = null,
        val credits: Double,
        val operations: Int,
    ) {
        val id: String get() = "$feature:${action ?: ""}"
    }

    @Serializable
    data class UserRow(
        val userId: String? = null,
        val credits: Double,
        val operations: Int,
        val lastAt: String? = null,
        val user: CreditPerson? = null,
    ) {
        val id: String get() = userId ?: "sistem"
    }

    @Serializable
    data class Cell(
        val credits: Double,
        val ops: Int,
    )
}

/** `GET /ai-credits/transactions` → `{items, nextCursor, limit}`. */
@Serializable
data class CreditTransactionsPage(
    val items: List<Row>,
    val nextCursor: String? = null,
) {
    @Serializable
    data class Kisi(
        val id: String? = null,
        val ad: String? = null,
        val ayrildi: Boolean? = null,
    )

    @Serializable
    data class Row(
        val id: String,
        val at: String,
        val tur: String,
        val feature: String? = null,
        val action: String? = null,
        val credits: Double,
        val kaynak: String? = null,
        val kisi: Kisi? = null,
        /** Satın alma satırı: kabul edilen koşul kaydı var mı (Ekleme 18 · 37C). */
        val kosulVar: Boolean? = null,
        /** Satın alma satırı: ödenen tutar — YALNIZ tutar görme yetkisinde ve koşul kaydı varsa (yoksa çizilmez). */
        val tutarTL: Double? = null,
    ) {
        /** Kişisiz kayıt "Dentifay" (Ek O.30-10); ayrılmış üye etiketiyle. */
        val kisiMetni: String
            get() {
                val ad = kisi?.ad?.takeIf { it.isNotEmpty() } ?: return "Dentifay"
                return if (kisi?.ayrildi == true) "$ad (ayrılmış)" else ad
            }
        val kaynakMetni: String? get() = kaynak?.let { CreditLabels.sources[it] }
        val turMetni: String get() = CreditLabels.kind(tur)

        /** Sözleşme §10 (Ekleme 20 · 39B): `tur: "tanim"` satırı "Dentifay'ın tanımladığı kredi" diye yazılır. */
        val islemMetni: String
            get() = if (tur == "tanim") CreditLabels.tanimMetni else CreditLabels.operation(feature, action)

        /** "+N" / "−N" — kredi, TL biçimi DEĞİL. */
        val isaretliKredi: String
            get() {
                if (credits == 0.0) return creditText(0.0)
                return (if (credits > 0) "+" else "−") + creditText(abs(credits))
            }
    }
}

/** Hareket süzgeçleri (Ekleme 18 · 37D.1) — imleçle BİRLİKTE gönderilir; boşsa hiçbiri gitmez. `page` YOK. */
data class CreditFilter(
    val from: String? = null,
    val to: String? = null,
    val userId: String? = null,
    val feature: String? = null,
    val tur: String? = null,
) {
    val bosMu: Boolean get() = from == null && to == null && userId == null && feature == null && tur == null

    fun queryItems(cursor: String?, limit: Int): List<Pair<String, String>> {
        val q = mutableListOf("limit" to limit.toString())
        if (cursor != null) q += "cursor" to cursor
        if (from != null && to != null) {
            q += "from" to from
            q += "to" to to
        }
        userId?.let { q += "userId" to it }
        feature?.let { q += "feature" to it }
        tur?.let { q += "tur" to it }
        return q
    }
}

/** `GET /ai-credits/transactions/:id/kosullar` — kabul edilen metin (Ek MO2.5). */
@Serializable
data class AcceptedTerms(
    val surum: String,
    val ozet: String? = null,
    val kabulEden: Kabul? = null,
    val at: String? = null,
    /** Sözleşme §10: `metin: { baslik, bolumler }` — kabul anındaki metnin kendisi (paket/tutar alanları YOK). */
    val metin: Metin? = null,
) {
    @Serializable
    data class Kabul(val ad: String? = null)

    @Serializable
    data class Metin(
        val baslik: String? = null,
        val bolumler: List<SmilePurchaseTerms.Bolum>,
    )
}

/** `GET /ai-credits/people`. */
@Serializable
data class CreditPeople(
    val month: String,
    /** Limit verilebilen özellikler — SUNUCUDAN (istemcide liste tutulmaz). */
    val features: List<String>,
    val people: List<Person>,
    val nextCursor: String? = null,
    val unattributed: Unattributed? = null,
    val canManageLimits: Boolean,
    val maxLimitCredits: Double? = null,
) {
    @Serializable
    data class Usage(
        val credits: Double,
        val ops: Int,
    )

    @Serializable
    data class Limits(
        val total: Double? = null,
        val features: Map<String, Double>,
    )

    @Serializable
    data class Person(
        val user: CreditPerson,
        val role: String? = null,
        val usedCredits: Double,
        val operations: Int,
        val byFeature: Map<String, Usage>,
        val limits: Limits,
        /** `If-Match` için — limit yazımında AYNEN geri gönderilir. */
        val limitVersion: String,
        val remainingCredits: Double? = null,
    ) {
        val id: String get() = user.id

        /** Limit çubuğunun oranı (sunucunun iki sayısı; limit yoksa çubuk YOK). */
        val limitOrani: Double?
            get() {
                val total = limits.total ?: return null
                return if (total <= 0) 1.0 else min(1.0, max(0.0, usedCredits) / total)
            }
    }

    @Serializable
    data class Unattributed(
        val credits: Double,
        val operations: Int,
    )
}

/** `GET|PUT /ai-credits/settings`. */
@Serializable
data class CreditSettings(
    val lowBalanceWarnCredits: Double,
    val version: String,
    val canEdit: Boolean? = null,
)

/** `GET /edocument/kullanim` — e-Fatura kartı (Ek MO.21r; salt okur). */
@Serializable
data class EDocumentUsage(
    val hesap: Hesap? = null,
    val buAy: BuAy? = null,
    val kontor: Kontor? = null,
    val uyari: String? = null,
) {
    @Serializable
    data class Hesap(val id: String, val ad: String? = null, val ortam: String? = null)

    @Serializable
    data class BuAy(val toplam: Int, val turler: Map<String, Int>)

    @Serializable
    data class Kontor(val kalan: Double? = null, val okumaZamani: String? = null)
}

// MARK: - Görünürlük (yalnız GÖRÜNÜM — asıl kapı sunucuda)

object KrediErisimi {
    /** Kredi Yönetimi: sunucunun görme kapısıyla AYNI küme (`comm.usage` | `fin.credits`). */
    fun yonetimGorur(caps: Set<String>): Boolean = "comm.usage" in caps || "fin.credits" in caps

    /** Kredi Yükle bölümü (web `KrediYonetimiPage` ile aynı): `fin.credits`. */
    fun yukleGorur(caps: Set<String>): Boolean = "fin.credits" in caps

    /**
     * Android: "Kredi Yükle" bölümü bu derlemede çizilir mi — `fin.credits` VE uygulama içi satın alma açık
     * (`AppConfig.inAppPurchase`; mağaza derlemesinde kapalı, krediler web panelinden alınır — Play Ödemeler kuralı).
     */
    fun yukleBolumu(caps: Set<String>): Boolean = yukleGorur(caps) && AppConfig.inAppPurchase

    enum class AyarlarSatiri { yonetim, krediler }

    /** Ayarlar › "Krediler" grubu: Kredi Yönetimi görünürse TEK satır; değilse `ai.smilesim` ile salt okur "Krediler"; yoksa grup yok. */
    fun ayarlarSatiri(caps: Set<String>): AyarlarSatiri? = when {
        yonetimGorur(caps) -> AyarlarSatiri.yonetim
        "ai.smilesim" in caps -> AyarlarSatiri.krediler
        else -> null
    }
}

// MARK: - İstemci

class CreditCenterClient(
    /** `{API}` kökü (ör. https://dentifay.com/api). */
    val base: String,
) {
    /**
     * `message` = Swift `errorDescription`. `server` sunucunun cümlesini ve kodunu taşır (M0-7); kodsuz 403
     * (`status == 403 && code == null`) yetki reddidir — parça çizilmez (M-E7.5, `Yetki.reddiMi`).
     */
    sealed class ClientError(message: String) : Exception(message), YetkiReddi {
        class notLoggedIn : ClientError("Oturumunuz sona ermiş. Tekrar giriş yapın.")

        /** Sunucunun cümlesi + kodu (M0-7). */
        class server(val httpDurumu: Int, val m: String?, val sunucuKodu: String?) :
            ClientError(m ?: "Kredi bilgileri yüklenemedi.")

        class badResponse : ClientError("Kredi bilgileri yüklenemedi.")

        val code: String? get() = (this as? server)?.sunucuKodu
        val status: Int? get() = (this as? server)?.httpDurumu

        override val yetkiReddiMi: Boolean get() = status == 403 && code == null
    }

    companion object {
        private val session: OkHttpClient by lazy {
            ApiClient.http.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
        }
        private val JSON = "application/json".toMediaType()

        /** Tek yol parçasının yüzde kodlaması ("/" dahil kodlanır — kimlik yolu bozamaz). */
        internal fun yolParcasi(s: String): String =
            HttpUrl.Builder().scheme("https").host("x").addPathSegment(s).build().encodedPathSegments.first()
    }

    // MARK: Saf istek kurucular (test edilebilir)

    fun istek(
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
        ifMatch: String? = null,
    ): Request {
        val url = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw ClientError.badResponse()
        query.forEach { (k, v) -> url.addQueryParameter(k, v) }
        val govde = when {
            body != null -> DfyJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON)
            method == "GET" || method == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val b = Request.Builder().url(url.build()).method(method, govde).header("Cache-Control", "no-cache")
        if (body != null) b.header("Content-Type", "application/json")
        if (ifMatch != null) b.header("If-Match", "\"$ifMatch\"")
        return b.build()
    }

    fun hareketIstegi(s: CreditFilter, cursor: String?, limit: Int = 30): Request =
        istek("/ai-credits/transactions", query = s.queryItems(cursor, limit))

    /** Limit yazımı: gövde `{total, features}` (TAM liste; null = sınırsız), başlık `If-Match: "<limitVersion>"`. */
    fun limitIstegi(userId: String, total: Double?, features: Map<String, Double?>, version: String): Request {
        val body = buildJsonObject {
            put("total", total)
            put("features", buildJsonObject { features.forEach { (k, v) -> put(k, v) } })
        }
        return istek("/ai-credits/limits/${yolParcasi(userId)}", method = "PUT", body = body, ifMatch = version)
    }

    fun ayarIstegi(esik: Double, version: String): Request =
        istek("/ai-credits/settings", method = "PUT", body = buildJsonObject { put("lowBalanceWarnCredits", esik) }, ifMatch = version)

    // MARK: Uçlar

    suspend fun usage(): CreditUsage = get(CreditUsage.serializer(), istek("/ai-credits/usage"))

    suspend fun analiz(from: String, to: String): CreditAnalysis =
        get(CreditAnalysis.serializer(), istek("/ai-credits/analiz", query = listOf("from" to from, "to" to to)))

    suspend fun transactions(s: CreditFilter, cursor: String?, limit: Int = 30): CreditTransactionsPage =
        get(CreditTransactionsPage.serializer(), hareketIstegi(s, cursor, limit))

    suspend fun kosullar(transactionId: String): AcceptedTerms =
        get(AcceptedTerms.serializer(), istek("/ai-credits/transactions/${yolParcasi(transactionId)}/kosullar"))

    suspend fun people(month: String?, cursor: String?): CreditPeople {
        val q = mutableListOf("limit" to "30")
        if (month != null) q += "ay" to month
        if (cursor != null) q += "cursor" to cursor
        return get(CreditPeople.serializer(), istek("/ai-credits/people", query = q))
    }

    @Serializable
    data class LimitSonucu(val limitVersion: String, val limits: CreditPeople.Limits)

    suspend fun saveLimits(userId: String, total: Double?, features: Map<String, Double?>, version: String): LimitSonucu =
        get(LimitSonucu.serializer(), limitIstegi(userId, total, features, version))

    suspend fun settings(): CreditSettings = get(CreditSettings.serializer(), istek("/ai-credits/settings"))

    suspend fun saveSettings(esik: Double, version: String): CreditSettings =
        get(CreditSettings.serializer(), ayarIstegi(esik, version))

    suspend fun tarife(): KrediTarifesi = get(KrediTarifesi.serializer(), istek("/ai-credits/tarife"))

    suspend fun eBelge(): EDocumentUsage = get(EDocumentUsage.serializer(), istek("/edocument/kullanim"))

    suspend fun eBelgeYenile(): EDocumentUsage =
        get(EDocumentUsage.serializer(), istek("/edocument/kontor/yenile", method = "POST"))

    private suspend fun <T> get(deserializer: KSerializer<T>, req: Request): T {
        val data = send(req)
        return withContext(Dispatchers.Default) {
            try {
                DfyJson.decodeFromString(deserializer, data.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw ClientError.badResponse()
            }
        }
    }

    private suspend fun send(req: Request): ByteArray {
        val raw = ApiClient.execute(req, session)
        return when (raw.status) {
            in 200..299 -> raw.body
            401 -> {
                SessionEvents.report401(raw.body, req.url.encodedPath)
                throw ClientError.notLoggedIn()
            }
            else -> throw ClientError.server(raw.status, hataCumlesi(raw.status, raw.body), serverCode(raw.body))
        }
    }
}

// MARK: - Biçim

object CreditDates {
    private val tr = Locale.forLanguageTag("tr-TR")

    // Biçimleyiciler bir kez kurulur (değişmez ve iş parçacığı güvenli).
    private val kisaBicim = DateTimeFormatter.ofPattern("d MMM HH:mm", tr)
    private val gunBicim = DateTimeFormatter.ofPattern("d MMM", tr)
    private val ayBicim = DateTimeFormatter.ofPattern("MMM yy", tr)
    private val aylar = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")

    /** Saat dilimiyle `YYYY-MM-DD`. */
    fun ymd(date: Instant, tz: ZoneId): String {
        val d = date.atZone(tz).toLocalDate()
        return String.format(Locale.US, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
    }

    fun parse(s: String): Instant? =
        runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

    /** "10 Eki 14:30" — cihazın saat dilimiyle (Türkiye'de klinikle aynı; bilinen sınır). */
    fun short(s: String, tz: ZoneId = ZoneId.systemDefault()): String {
        val d = parse(s) ?: return s
        return kisaBicim.withZone(tz).format(d)
    }

    /** "2026-10" → "Ekim 2026" */
    fun monthLabel(ym: String): String {
        val parts = ym.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return ym
        return "${aylar[max(0, min(11, parts[1] - 1))]} ${parts[0]}"
    }

    /**
     * Seri kovası anahtarı ("YYYY-MM-DD" ya da "YYYY-MM") → takvim günü. Kova kliniğin günüdür (sunucuda
     * hesaplandı); cihazın saat dilimi günü kaydıramaz.
     */
    fun bucketDate(key: String): LocalDate? {
        val p = key.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size < 2) return null
        return runCatching { LocalDate.of(p[0], p[1], if (p.size > 2) p[2] else 1) }.getOrNull()
    }

    /** Grafik ekseni: gün kovası "10 Eki", ay kovası "Eki 26". */
    fun axisLabel(date: LocalDate, monthly: Boolean): String = (if (monthly) ayBicim else gunBicim).format(date)

    /** "2026-10-10" → "10 Eki" */
    fun dayLabel(ymd: String): String {
        val d = bucketDate(ymd) ?: return ymd
        return axisLabel(d, monthly = false)
    }

    /** Son N ay (yeniden eskiye) — platform ayı (Europe/Istanbul). */
    fun lastMonths(count: Int, tz: ZoneId, now: Instant = Instant.now()): List<String> {
        val today = ymd(now, tz)
        val p = today.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return emptyList()
        var y = p[0]
        var m = p[1]
        val out = mutableListOf<String>()
        repeat(count) {
            out += String.format(Locale.US, "%04d-%02d", y, m)
            m -= 1
            if (m == 0) {
                m = 12
                y -= 1
            }
        }
        return out
    }

    /** Platformun dilimi (sunucu varsayılanı). */
    val istanbul: ZoneId = runCatching { ZoneId.of("Europe/Istanbul") }.getOrNull() ?: ZoneId.systemDefault()
}

/** Hazır aralıklar (bugün DAHİL; varsayılan bu ay — M-E1.7.4 madde 1). */
enum class CreditRange {
    thisMonth, last7, last30, last90;

    val id: String get() = name
    val label: String
        get() = when (this) {
            thisMonth -> "Bu ay"
            last7 -> "Son 7 gün"
            last30 -> "Son 30 gün"
            last90 -> "Son 90 gün"
        }

    data class Bounds(val from: String, val to: String)

    fun bounds(tz: ZoneId, now: Instant = Instant.now()): Bounds {
        val to = CreditDates.ymd(now, tz)
        return when (this) {
            thisMonth -> Bounds(to.take(7) + "-01", to)
            last7 -> Bounds(CreditDates.ymd(now.minusSeconds(6 * 86_400L), tz), to)
            last30 -> Bounds(CreditDates.ymd(now.minusSeconds(29 * 86_400L), tz), to)
            last90 -> Bounds(CreditDates.ymd(now.minusSeconds(89 * 86_400L), tz), to)
        }
    }
}

// MARK: - Durum

/** Tek yükleyici — bölüm başına ayrı istek; bir bölümün hatası ötekileri DÜŞÜRMEZ. */
class CreditCenterStore {
    // Genel Bakış
    var usage by mutableStateOf<CreditUsage?>(null)
        private set
    var usageError by mutableStateOf<String?>(null)
        private set
    var analysis by mutableStateOf<CreditAnalysis?>(null)
        private set
    var analysisError by mutableStateOf<String?>(null)
        private set
    var settings by mutableStateOf<CreditSettings?>(null)
        private set
    var settingsError by mutableStateOf<String?>(null)
        private set
    var eDocument by mutableStateOf<EDocumentUsage?>(null)
        private set
    var eDocumentError by mutableStateOf<String?>(null)
        private set
    var isLoadingOverview by mutableStateOf(false)
        private set

    // Hareketler
    var rows by mutableStateOf<List<CreditTransactionsPage.Row>>(emptyList())
        private set
    var nextCursor by mutableStateOf<String?>(null)
        private set
    var rowsLoaded by mutableStateOf(false)
        private set
    var rowsError by mutableStateOf<String?>(null)
        private set
    var isLoadingRows by mutableStateOf(false)
        private set

    // Kişiler ve Limitler
    var people by mutableStateOf<CreditPeople?>(null)
        private set
    var peopleError by mutableStateOf<String?>(null)
        private set

    // Kredi Yükle — son satın almalar (Ekleme 18 · 37D; `tur=satin_alma`, son bir yıl)
    var purchases by mutableStateOf<List<CreditTransactionsPage.Row>>(emptyList())
        private set
    var purchasesError by mutableStateOf<String?>(null)
        private set

    var range by mutableStateOf(CreditRange.thisMonth)
    var filter by mutableStateOf(CreditFilter())
    var month by mutableStateOf<String?>(null)

    private val client: CreditCenterClient get() = CreditCenterClient(AppConfig.apiBaseURL)

    /** Platformun / kliniğin dilimi — analiz yanıtındaki `tz`; gelene dek İstanbul (sunucu varsayılanı). */
    val clinicTimeZone: ZoneId
        get() = analysis?.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: CreditDates.istanbul

    /** Yalnız önizleme: örnek veri yüklüyken ağ çağrısı yapılmaz. */
    private var ornekVeri = false
    private var rowsGeneration = 0

    /** Ekranın veri parçaları — kodsuz 403 alan parça ÇİZİLMEZ (kırmızı "hata · tekrar dene" kartı yok — M-E7.5). */
    enum class Parca { usage, analysis, settings, eDocument, rows, people, purchases }

    var gizli by mutableStateOf<Set<Parca>>(emptySet())
        private set

    private fun hataMetni(e: Throwable, yedek: String): String =
        (e as? CreditCenterClient.ClientError.server)?.m ?: yedek

    /** Parçanın hatası: yetki reddinde null (parça gizlenir), değilse ekrandaki cümle. */
    private fun parcaHatasi(e: Throwable, p: Parca, yedek: String): String? {
        if (Yetki.reddiMi(e)) {
            gizli = gizli + p
            return null
        }
        gizli = gizli - p
        return hataMetni(e, yedek)
    }

    /** İptal dışındaki hatayı sonuca çevirir (Swift `Result` yardımcısı). */
    private suspend fun <T> sonuc(f: suspend () -> T): Result<T> = try {
        Result.success(f())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun loadOverview() {
        if (ornekVeri) return
        isLoadingOverview = true
        try {
            val b = range.bounds(clinicTimeZone)
            val c = client
            coroutineScope {
                val u = async { sonuc { c.usage() } }
                val a = async { sonuc { c.analiz(b.from, b.to) } }
                val s = async { sonuc { c.settings() } }
                val e = async { sonuc { c.eBelge() } }
                u.await().fold(
                    { usage = it; usageError = null; gizli = gizli - Parca.usage },
                    { usageError = parcaHatasi(it, Parca.usage, "Kredi bilgileri yüklenemedi.") },
                )
                a.await().fold(
                    { analysis = it; analysisError = null; gizli = gizli - Parca.analysis },
                    { analysisError = parcaHatasi(it, Parca.analysis, "Kullanım dağılımı yüklenemedi.") },
                )
                s.await().fold(
                    { settings = it; settingsError = null; gizli = gizli - Parca.settings },
                    { settingsError = parcaHatasi(it, Parca.settings, "Kredi bilgileri yüklenemedi.") },
                )
                e.await().fold(
                    { eDocument = it; eDocumentError = null; gizli = gizli - Parca.eDocument },
                    { eDocumentError = parcaHatasi(it, Parca.eDocument, "e-Fatura kullanımı yüklenemedi.")?.let { "e-Fatura kullanımı yüklenemedi." } },
                )
            }
            KrediTarifesiStore.shared.yukle(zorla = true)
        } finally {
            isLoadingOverview = false
        }
    }

    suspend fun loadAnalysis() {
        if (ornekVeri) return
        val b = range.bounds(clinicTimeZone)
        try {
            analysis = client.analiz(b.from, b.to)
            analysisError = null
            gizli = gizli - Parca.analysis
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analysisError = parcaHatasi(e, Parca.analysis, "Kullanım dağılımı yüklenemedi.")
        }
    }

    /** Hareketler: `reset` ilk sayfa (süzgeç değişince baştan), değilse `nextCursor` ile sonraki sayfa. */
    suspend fun loadRows(reset: Boolean) {
        if (ornekVeri) return
        if (!reset && nextCursor == null) return
        rowsGeneration += 1
        val mine = rowsGeneration
        isLoadingRows = true
        try {
            val r = client.transactions(filter, if (reset) null else nextCursor)
            if (mine != rowsGeneration) return
            // Kimlik tekil tutulur (liste anahtarı) — sunucu aynı satırı iki sayfada verse de ekran bozulmaz.
            rows = (if (reset) r.items else rows + r.items).distinctBy { it.id }
            nextCursor = r.nextCursor
            rowsLoaded = true
            rowsError = null
            gizli = gizli - Parca.rows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine != rowsGeneration) return
            rowsError = parcaHatasi(e, Parca.rows, "Kredi hareketleri yüklenemedi.")
        } finally {
            if (mine == rowsGeneration) isLoadingRows = false
        }
    }

    suspend fun loadPeople(more: Boolean = false) {
        if (ornekVeri) return
        try {
            val r = client.people(month, if (more) people?.nextCursor else null)
            val p = people
            people = if (more && p != null) r.copy(people = (p.people + r.people).distinctBy { it.id }) else r
            peopleError = null
            gizli = gizli - Parca.people
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            peopleError = parcaHatasi(e, Parca.people, "Kişi kullanımı yüklenemedi.")
        }
    }

    suspend fun sonSatinAlmalariYukle() {
        if (ornekVeri) return
        val tz = CreditDates.istanbul
        val now = Instant.now()
        val s = CreditFilter(
            from = CreditDates.ymd(now.minusSeconds(365 * 86_400L), tz),
            to = CreditDates.ymd(now, tz),
            tur = "satin_alma",
        )
        try {
            purchases = client.transactions(s, cursor = null, limit = 10).items.distinctBy { it.id }
            purchasesError = null
            gizli = gizli - Parca.purchases
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            purchasesError = parcaHatasi(e, Parca.purchases, "Kredi hareketleri yüklenemedi.")
        }
    }

    /**
     * Limit yazımı — 409 `E_CREDIT_LIMIT_VERSION`'da liste yeniden okunur ve hata (sunucunun cümlesi) fırlatılır.
     * Sürüm, listedeki GÜNCEL satırdan alınır: 409'dan sonra pencere açık kalır ve yeniden "Kaydet" yeni sürümle gider.
     */
    suspend fun saveLimits(person: CreditPeople.Person, total: Double?, features: Map<String, Double?>) {
        val guncel = people?.people?.firstOrNull { it.id == person.id } ?: person
        try {
            val r = client.saveLimits(guncel.user.id, total, features, guncel.limitVersion)
            val p = people ?: return
            val i = p.people.indexOfFirst { it.id == person.id }
            if (i >= 0) {
                val yeni = p.people.toMutableList()
                yeni[i] = yeni[i].copy(limits = r.limits, limitVersion = r.limitVersion)
                people = p.copy(people = yeni)
            }
        } catch (e: CreditCenterClient.ClientError) {
            if (e.code == "E_CREDIT_LIMIT_VERSION") loadPeople()
            throw e
        }
    }

    suspend fun saveThreshold(credits: Double) {
        val v = settings?.version ?: throw CreditCenterClient.ClientError.badResponse()
        try {
            settings = client.saveSettings(credits, v)
        } catch (e: CreditCenterClient.ClientError) {
            if (e.status == 409) settings = sonuc { client.settings() }.getOrNull()
            throw e
        }
    }

    suspend fun refreshEDocument() {
        eDocument = client.eBelgeYenile()
    }

    suspend fun acceptedTerms(row: CreditTransactionsPage.Row): AcceptedTerms {
        if (ornekVeri) return CreditPreviewData.kabul
        return client.kosullar(row.id)
    }

    /** Yalnız @Preview: örnek verilerle doldurur (ağ çağrısı yapılmaz). */
    internal fun ornekYukle(
        usage: CreditUsage,
        analysis: CreditAnalysis,
        settings: CreditSettings,
        eDocument: EDocumentUsage,
        rows: List<CreditTransactionsPage.Row>,
        people: CreditPeople,
    ) {
        ornekVeri = true
        this.usage = usage
        this.analysis = analysis
        this.settings = settings
        this.eDocument = eDocument
        this.rows = rows
        this.rowsLoaded = true
        this.people = people
        this.purchases = rows.filter { it.tur == "satin_alma" }
    }
}

/** Kredi sayısı metni: "12", "0,5", "1.250". */
fun creditText(v: Double): String = SmileCreditFormat.credits(v)

/** "12,5" / "12.5" → 12.5; boş → `empty`; geçersiz → `invalid` (0–üst sınır, en çok iki ondalık). */
sealed class CreditInput {
    data object empty : CreditInput()
    data class value(val v: Double) : CreditInput()
    data object invalid : CreditInput()

    companion object {
        /** Ondalık sayı (isteğe bağlı üs) — Swift `Double(String)`ın ondalık biçimi; "1d", "0x10" gibi biçimler geçersiz. */
        private val number = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

        fun parse(s: String, max: Double = 1_000_000.0): CreditInput {
            val t = s.trim().replace(",", ".")
            if (t.isEmpty()) return empty
            if (!number.matches(t)) return invalid
            val n = t.toDoubleOrNull() ?: return invalid
            if (!n.isFinite() || n < 0 || n > max) return invalid
            if (abs((n * 100).roundToLong().toDouble() - n * 100) >= 1e-6) return invalid
            return value(n)
        }
    }
}
