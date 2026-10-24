package com.dentifay.app.credit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.SessionEvents
import com.dentifay.app.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
 * Kredi Yönetimi — "kim, nerede, ne kadar, hangi saatte kredi kullandı · kim ne kadar
 * kullanabilir · kredi yükle". Web'deki `/krediler` ekranıyla AYNI uçları okur:
 *
 *   GET  {API}/ai-credits/usage?from&to          özet (kişi · özellik · gün/saat · satın alma)
 *   GET  {API}/ai-credits/transactions?…&page     işlem defteri (sayfalı, süzgeçli)
 *   GET  {API}/ai-credits/people?ay=YYYY-MM       kişiler × bu ayki kullanım × limitler
 *   PUT  {API}/ai-credits/limits/:userId          kişinin aylık limit seti
 *   PUT  {API}/ai-credits/settings                düşük bakiye uyarı eşiği
 *
 * Görmek `fin.credits.view`, limit yazmak `fin.credits.limits`, satın almak `fin.credits`
 * ister (varsayılan yalnız klinik sahibi; Ayarlar'dan açılır). Hesap kuralları sunucuda
 * (`backend/lib/kredi-merkezi.js`): kullanım NET (iade düşülmüş), gün/saat kliniğin saatiyle.
 */

// MARK: - Etiketler (sunucu metin taşımaz; kimlikleri burada Türkçeye çeviririz)

object CreditLabels {
    /** ⚠ Ekran metninde "AI" sözcüğü ve emoji YOK (ürün kuralı). */
    val features: Map<String, String> = mapOf(
        "ai_agent" to "Dentifay Asistan",
        "before_after" to "Gülüş simülasyonu",
        "ai_center" to "Analiz Merkezi",
        "xray" to "Röntgen analizi",
        "messaging" to "İletişim",
        "purchase" to "Kredi yükleme",
        "platform_grant" to "Dentifay tanımı",
        "demo_admin" to "Dentifay düzeltmesi",
    )

    /**
     * Kredili (limit verilebilen) özellikler — sunucu `kredi-merkezi#KULLANIM_OZELLIKLERI`
     * ile AYNI sıra. (Pazarlık ve çağrı merkezi 2026-10-10'da listeden çıktı.)
     */
    val limitFeatures = listOf("ai_agent", "before_after", "ai_center", "xray", "messaging")

    val actions: Map<String, String> = mapOf(
        "before_after:orta" to "Orta kalite", "before_after:orta_iyi" to "Orta İyi kalite",
        "before_after:iyi" to "İyi kalite", "before_after:cok_iyi" to "Çok İyi kalite",
        "before_after:duzeltme" to "Otomatik yerleşim düzeltmesi", "before_after:generate" to "Görsel üretimi",
        "ai_agent:chat" to "Sohbet turu", "ai_agent:voice" to "Sesli tur eki",
        "ai_center:audience" to "Hedef kitle", "ai_center:insight" to "Modül yorumu",
        "ai_center:briefing" to "Klinik brifingi", "ai_center:report_export" to "Excel rapor",
        "xray:analyze" to "Görüntü analizi",
        "messaging:suggest_message" to "Mesaj taslağı",
        "purchase:credit_pack" to "Kredi paketi", "purchase:auto_reload" to "Otomatik yükleme",
        "platform_grant:opening" to "Açılış kredisi", "platform_grant:bulk" to "Toplu tanım",
        "platform_grant:single" to "Tanım", "platform_grant:placeholder_removed" to "Yer tutucu kaldırıldı",
    )
    val kinds: Map<String, String> = mapOf(
        "usage" to "Kullanım", "refund" to "İade", "free_fix" to "Ücretsiz düzeltme",
        "purchase" to "Satın alma", "grant" to "Dentifay tanımı", "adjustment" to "Düzeltme",
    )
    val kindOrder = listOf("usage", "refund", "free_fix", "purchase", "grant", "adjustment")
    val sources: Map<String, String> = mapOf(
        "free" to "Ücretsiz kredi", "balance" to "Satın alınmış kredi",
        "mixed" to "Ücretsiz + satın alınmış", "overage" to "Ay sonu faturası",
    )
    val departments: Map<String, String> = mapOf(
        "OWNER" to "Sahip", "ADMIN" to "Yönetici", "MANAGER" to "Klinik Müdürü", "BANKO" to "Banko",
        "CAGRI_MERKEZI" to "Çağrı Merkezi Sorumlusu", "HASTA_KOORDINATORU" to "Hasta Koordinatörü",
        "KLINIK_KOORDINATORU" to "Klinik Koordinatörü", "SATIN_ALMA" to "Satın Alma", "MUHASEBE" to "Muhasebe",
        "GOZLEMCI" to "Gözlemci", "ASISTAN" to "Asistan", "HEKIM_GENEL" to "Genel Diş Hekimi",
        "HEKIM_ENDODONTI" to "Endodonti", "HEKIM_ORTODONTI" to "Ortodonti", "HEKIM_PEDODONTI" to "Pedodonti",
        "HEKIM_PERIO" to "Periodontoloji", "HEKIM_PROTEZ" to "Protez",
    )
    val weekdays = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")

    /** Fiyat listesi birimi (`priceCatalog[].unit`) → "… başına". */
    val units: Map<String, String> = mapOf(
        "turn" to "tur başına", "query" to "sorgu başına", "run" to "çalıştırma başına",
        "image" to "görüntü başına", "draft" to "taslak başına", "simulation" to "simülasyon başına",
        "operation" to "işlem başına",
    )

    fun unit(key: String?): String = units[key ?: ""] ?: "işlem başına"
    fun feature(key: String?): String = features[key ?: ""] ?: "Diğer"
    fun action(feature: String?, action: String?): String? = actions["${feature ?: ""}:${action ?: ""}"]
    fun operation(feature: String?, action: String?): String =
        listOfNotNull(feature(feature), action(feature, action)).joinToString(" · ")
}

// MARK: - Modeller

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
            val ad = name ?: "Bilinmeyen kişi"
            return if (former == true) "$ad (ayrılmış)" else ad
        }
    val departmentLabel: String? get() = department?.let { CreditLabels.departments[it] }
}

@Serializable
data class CreditUsage(
    val tz: String,
    val from: String,
    val to: String,
    val truncated: Boolean? = null,
    val wallet: Wallet,
    val runwayDays: Int? = null,
    val last30DaysCredits: Double,
    val unit: String,
    val summary: Summary,
    val series: List<Bucket>,
    val features: List<FeatureRow>,
    val actions: List<ActionRow>,
    val heatmap: List<List<Cell>>,
    val users: List<UserRow>,
    val packs: List<SmileCreditPack>,
    val priceCatalog: List<PriceRow>,
    val canPurchase: Boolean,
    val purchaseBlockedReason: String? = null,
    val canManageLimits: Boolean,
) {
    @Serializable
    data class Wallet(
        val availableCredits: Double,
        val freeRemainingCredits: Double,
        val freeGrantCredits: Double,
        val balanceCredits: Double,
        val lowBalanceWarnCredits: Double,
        val isLow: Boolean,
        val isDemo: Boolean? = null,
        val thisMonth: SmileWallet.Month? = null,
    )

    @Serializable
    data class Summary(
        val usedCredits: Double,
        val chargedCredits: Double,
        val refundedCredits: Double,
        val freeUsedCredits: Double,
        val operations: Int,
        val refunds: Int,
        val freeFixes: Int,
        val activeUsers: Int,
        val purchasedCredits: Double,
        val purchases: Int,
        val paidTL: Double,
        val grantedCredits: Double,
        val adjustedCredits: Double,
        val avgDailyCredits: Double,
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
        val refunds: Int,
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
        val refunds: Int,
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

    /**
     * `{feature, action, unit, extra, credits, minCredits, maxCredits}` — eski sunucuda
     * `extra`/`minCredits`/`maxCredits` yok → `credits`e düşülür.
     */
    @Serializable
    data class PriceRow(
        val feature: String,
        val action: String,
        val credits: Double,
        val unit: String,
        /** Başka bir işlemin üstüne eklenen ücret (sesli tur +1). */
        val extra: Boolean? = null,
        val minCredits: Double? = null,
        val maxCredits: Double? = null,
    ) {
        val id: String get() = "$feature:$action"

        /** "Ücretsiz" · "2 kredi" · "1–2 kredi" (aralıklı) · "+1 kredi" (ek ücret). */
        val priceText: String
            get() {
                val lo = minCredits ?: credits
                val hi = max(maxCredits ?: credits, lo)
                if (hi <= 0) return "Ücretsiz"
                val amount = if (lo == hi) creditText(lo) else "${creditText(lo)}–${creditText(hi)}"
                return (if (extra == true) "+" else "") + amount + " kredi"
            }
        val isFree: Boolean get() = max(maxCredits ?: credits, minCredits ?: credits) <= 0
    }
}

@Serializable
data class CreditTransactionsPage(
    val pagination: Pagination,
    val rows: List<Row>,
) {
    @Serializable
    data class Pagination(
        val page: Int,
        val limit: Int,
        val total: Int,
        val pages: Int,
    )

    @Serializable
    data class Row(
        val id: String,
        val at: String,
        val kind: String,
        val feature: String? = null,
        val action: String? = null,
        val credits: Double,
        val source: String? = null,
        val parts: Int,
        val priceTL: Double? = null,
        val byPlatform: Boolean,
        val user: CreditPerson? = null,
    ) {
        val actorName: String
            get() {
                if (user != null) return user.displayName
                return if (byPlatform || kind == "grant") "Dentifay" else "Sistem"
            }
    }
}

@Serializable
data class CreditPeople(
    val month: String,
    val people: List<Person>,
    val unattributed: Unattributed? = null,
    val canManageLimits: Boolean,
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
        val remainingCredits: Double? = null,
    ) {
        val id: String get() = user.id
    }

    @Serializable
    data class Unattributed(
        val credits: Double,
        val operations: Int,
    )
}

// MARK: - İstemci

class CreditCenterClient(
    /** `{API}` kökü (ör. https://dentifay.com/api). */
    val base: String,
) {
    sealed class ClientError(message: String) : Exception(message) {
        class notLoggedIn : ClientError("Oturumunuz sona ermiş. Tekrar giriş yapın.")
        class forbidden(val m: String?) :
            ClientError(m ?: "Bu ekran için yetkiniz yok. Klinik sahibinden “Kredi Yönetimi'ni görüntüleme” yetkisini isteyin.")
        class server(val code: Int, val m: String?) : ClientError(m ?: "Sunucu hatası ($code). Tekrar deneyin.")
        class badResponse : ClientError("Sunucudan beklenmeyen yanıt alındı.")
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
    }

    suspend fun usage(from: String, to: String): CreditUsage =
        get(CreditUsage.serializer(), "/ai-credits/usage", listOf("from" to from, "to" to to))

    suspend fun transactions(
        from: String, to: String, userId: String?, feature: String?, kind: String?, page: Int, limit: Int = 25,
    ): CreditTransactionsPage {
        val q = mutableListOf("from" to from, "to" to to, "page" to page.toString(), "limit" to limit.toString())
        if (userId != null) q += "userId" to userId
        if (feature != null) q += "feature" to feature
        if (kind != null) q += "kind" to kind
        return get(CreditTransactionsPage.serializer(), "/ai-credits/transactions", q)
    }

    suspend fun people(month: String?): CreditPeople =
        get(CreditPeople.serializer(), "/ai-credits/people", month?.let { listOf("ay" to it) } ?: emptyList())

    /** Kişinin limit setini TAM yazar (PUT anlamı): `total` null = sınırsız; olmayan özellik = sınırsız. */
    suspend fun saveLimits(userId: String, total: Double?, features: Map<String, Double>) {
        val id = android.net.Uri.encode(userId, "/")
        val body = buildJsonObject {
            put("total", total)
            putJsonObject("features") { features.forEach { (k, v) -> put(k, v) } }
        }
        send("/ai-credits/limits/$id", "PUT", body)
    }

    suspend fun saveLowBalanceThreshold(credits: Double) {
        send("/ai-credits/settings", "PUT", buildJsonObject { put("lowBalanceWarnCredits", credits) })
    }

    private suspend fun <T> get(deserializer: kotlinx.serialization.KSerializer<T>, path: String, query: List<Pair<String, String>>): T {
        val data = send(path, "GET", null, query)
        return try {
            DfyJson.decodeFromString(deserializer, data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ClientError.badResponse()
        }
    }

    private suspend fun send(path: String, method: String, body: JsonElement?, query: List<Pair<String, String>> = emptyList()): ByteArray {
        val builder = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw ClientError.badResponse()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val url = builder.build()
        val reqBody = body?.let { DfyJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        val rb = Request.Builder().url(url).method(method, reqBody).header("Cache-Control", "no-cache")
        if (body != null) rb.header("Content-Type", "application/json")
        val req = rb.build()
        val raw = ApiClient.execute(req, session)
        val json = runCatching { DfyJson.parseToJsonElement(raw.body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val message = json?.str("error")?.takeIf { it.contains(" ") } ?: json?.str("message")
        return when (raw.status) {
            in 200..299 -> raw.body
            401 -> {
                SessionEvents.report401(raw.body, req.url.encodedPath)
                throw ClientError.notLoggedIn()
            }
            403 -> throw ClientError.forbidden(message)
            else -> throw ClientError.server(raw.status, message)
        }
    }
}

// MARK: - Biçim

object CreditDates {
    private val tr = Locale.forLanguageTag("tr-TR")

    /** Kliniğin saat dilimiyle `YYYY-MM-DD`. */
    fun ymd(date: Instant, tz: ZoneId): String {
        val d = date.atZone(tz).toLocalDate()
        return String.format(Locale.US, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
    }

    /** Sunucunun ISO anı (`toISOString()` → milisaniyeli). */
    fun parse(s: String): Instant? =
        runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

    /** "10 Eki 14:30" — kliniğin saatiyle. */
    fun short(s: String, tz: ZoneId): String {
        val d = parse(s) ?: return s
        return DateTimeFormatter.ofPattern("d MMM HH:mm", tr).withZone(tz).format(d)
    }

    /** "Ekim 2026" */
    fun monthLabel(ym: String): String {
        val parts = ym.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return ym
        val aylar = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")
        return "${aylar[max(0, min(11, parts[1] - 1))]} ${parts[0]}"
    }

    /**
     * Grafik takvimi: seri kovaları kliniğin GÜNÜDÜR (sunucuda hesaplandı); takvim günü
     * olarak çizilir — cihazın saat dilimi günü kaydıramaz.
     * Seri kovası anahtarı ("YYYY-MM-DD" ya da "YYYY-MM") → gün.
     */
    fun bucketDate(key: String): LocalDate? {
        val p = key.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size < 2) return null
        return runCatching { LocalDate.of(p[0], p[1], if (p.size > 2) p[2] else 1) }.getOrNull()
    }

    /** Grafik ekseni: gün kovası "10 Eki", ay kovası "Eki 26". */
    fun axisLabel(date: LocalDate, monthly: Boolean): String =
        DateTimeFormatter.ofPattern(if (monthly) "MMM yy" else "d MMM", tr).format(date)

    /** "2026-10-10" → "10 Eki" (aralık şeridi). */
    fun dayLabel(ymd: String): String {
        val d = bucketDate(ymd) ?: return ymd
        return axisLabel(d, monthly = false)
    }

    /** Son N ay (yeniden eskiye) — kliniğin bugününden. */
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
            if (m == 0) { m = 12; y -= 1 }
        }
        return out
    }
}

// MARK: - Durum

/** Hazır aralıklar — web'deki `DateRangePicker` ön ayarlarıyla aynı anlam (bugün DAHİL). */
enum class CreditRange {
    last7, last30, last90, thisMonth;

    val id: String get() = name
    val label: String
        get() = when (this) {
            last7 -> "Son 7 gün"
            last30 -> "Son 30 gün"
            last90 -> "Son 90 gün"
            thisMonth -> "Bu ay"
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

class CreditCenterStore {
    var usage by mutableStateOf<CreditUsage?>(null)
        private set
    var usageError by mutableStateOf<String?>(null)
        private set
    var isLoadingUsage by mutableStateOf(false)
        private set

    var rows by mutableStateOf<List<CreditTransactionsPage.Row>>(emptyList())
        private set
    var pagination by mutableStateOf<CreditTransactionsPage.Pagination?>(null)
        private set
    var rowsError by mutableStateOf<String?>(null)
        private set
    var isLoadingRows by mutableStateOf(false)
        private set

    var people by mutableStateOf<CreditPeople?>(null)
        private set
    var peopleError by mutableStateOf<String?>(null)
        private set

    /** Son bir yılın kredi yüklemeleri (Yükle bölümü) — defterin AYNI ucundan, tür süzgeciyle. */
    var purchases by mutableStateOf<List<CreditTransactionsPage.Row>>(emptyList())
        private set
    var purchasesError by mutableStateOf<String?>(null)
        private set

    var range by mutableStateOf(CreditRange.last30)
    var filterUser by mutableStateOf<String?>(null)
    var filterFeature by mutableStateOf<String?>(null)
    var filterKind by mutableStateOf<String?>(null)
    var month by mutableStateOf<String?>(null)

    private val client: CreditCenterClient get() = CreditCenterClient(AppConfig.apiBaseURL)

    /** Kliniğin dilimi sunucudan gelir (`usage.tz`); gelene dek İstanbul (sunucu varsayılanı). */
    val clinicTimeZone: ZoneId
        get() = usage?.let { runCatching { ZoneId.of(it.tz) }.getOrNull() }
            ?: runCatching { ZoneId.of("Europe/Istanbul") }.getOrNull() ?: ZoneId.systemDefault()

    private var usageGeneration = 0
    private var rowsGeneration = 0

    suspend fun loadUsage() {
        usageGeneration += 1
        val mine = usageGeneration
        isLoadingUsage = true
        try {
            val b = range.bounds(clinicTimeZone)
            val u = client.usage(b.from, b.to)
            if (mine != usageGeneration) return
            usage = u
            usageError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine != usageGeneration) return
            usageError = creditErrorText(e)
        } finally {
            if (mine == usageGeneration) isLoadingUsage = false
        }
    }

    /** İlk sayfa (`reset`) ya da sonraki sayfa. */
    suspend fun loadRows(reset: Boolean) {
        val p = pagination
        if (!reset && p != null && p.page >= p.pages) return
        rowsGeneration += 1
        val mine = rowsGeneration
        isLoadingRows = true
        try {
            val b = range.bounds(clinicTimeZone)
            val page = if (reset) 1 else (pagination?.page ?: 0) + 1
            val r = client.transactions(b.from, b.to, filterUser, filterFeature, filterKind, page)
            if (mine != rowsGeneration) return
            rows = if (reset) r.rows else rows + r.rows
            pagination = r.pagination
            rowsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine != rowsGeneration) return
            rowsError = creditErrorText(e)
        } finally {
            if (mine == rowsGeneration) isLoadingRows = false
        }
    }

    suspend fun loadPeople() {
        try {
            people = client.people(month)
            peopleError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            peopleError = creditErrorText(e)
        }
    }

    suspend fun loadPurchases() {
        val tz = clinicTimeZone
        val now = Instant.now()
        val to = CreditDates.ymd(now, tz)
        val from = CreditDates.ymd(now.minusSeconds(364 * 86_400L), tz)
        try {
            purchases = client.transactions(from, to, null, null, "purchase", page = 1, limit = 10).rows
            purchasesError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            purchasesError = creditErrorText(e)
        }
    }

    suspend fun saveLimits(userId: String, total: Double?, features: Map<String, Double>) {
        client.saveLimits(userId, total, features)
        loadPeople()
    }

    suspend fun saveThreshold(credits: Double) {
        client.saveLowBalanceThreshold(credits)
        loadUsage()
    }

    /** Yalnız @Preview: örnek verilerle doldurur. */
    internal fun previewFill(usage: CreditUsage?, people: CreditPeople?, rows: List<CreditTransactionsPage.Row>) {
        this.usage = usage
        this.people = people
        this.rows = rows
        this.purchases = rows.filter { it.kind == "purchase" }
        if (rows.isNotEmpty()) pagination = CreditTransactionsPage.Pagination(1, 25, rows.size, 1)
    }
}

/** Kredi sayısı metni: "12", "0,5", "1.250". */
fun creditText(v: Double): String = SmileCreditFormat.credits(v)

/** "12,5" / "12.5" → 12.5; boş → empty; geçersiz → invalid. */
sealed class CreditInput {
    data object empty : CreditInput()
    data class value(val v: Double) : CreditInput()
    data object invalid : CreditInput()

    companion object {
        private val number = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

        fun parse(s: String): CreditInput {
            val t = s.trim().replace(",", ".")
            if (t.isEmpty()) return empty
            if (!number.matches(t)) return invalid
            val n = t.toDoubleOrNull() ?: return invalid
            if (!n.isFinite() || n < 0 || n > 1_000_000) return invalid
            if (abs((n * 100).roundToLong().toDouble() - n * 100) >= 1e-6) return invalid
            return value(n)
        }
    }
}
