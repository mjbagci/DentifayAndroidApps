package com.dentifay.app.core

import com.dentifay.app.credit.CreditLabels
import com.dentifay.app.credit.SmileCreditFormat
import kotlinx.serialization.json.JsonObject

/**
 * Kredi yetmedi — TEK model (MOBIL_IS_EMRI M-E1.7.7). Kaynaklar:
 *   · kredili uçların 402 gövdesi (`backend/lib/kredi-dusum.js`): `{ code|error: "INSUFFICIENT_CREDIT" | "CREDIT_LIMIT_REACHED",
 *     message, requiredCredits, availableCredits, scope, limitCredits, usedCredits, isDemo, canPurchase }`;
 *   · demo hesabın sabit tarifeli uçlarında 403 `DEMO_CREDITS_EXHAUSTED`;
 *   · sohbetin SSE `error` olayı (`INSUFFICIENT_CREDIT` · `CREDIT_LIMIT_REACHED` · `DEMO_CREDITS_EXHAUSTED`, `yarida`).
 * `E_CREDIT_FX_MISSING` kredi modeli DEĞİLDİR (genel hata). Ekrana ham kod ya da JSON basılmaz; gövde
 * sunucunun `message`ı VARSA AYNEN (M0-6), yoksa web `credits.gate` cümleleri (Ek MO.23g).
 */
class CreditRequired(
    val kind: Kind,
    val requiredCredits: Double? = null,
    val availableCredits: Double? = null,
    /** `total` = aylık toplam · diğerleri özellik kimliği (`ai_agent` …). */
    val scope: String? = null,
    val limitCredits: Double? = null,
    val usedCredits: Double? = null,
    val isDemo: Boolean = false,
    val canPurchase: Boolean = false,
    /** Sunucunun Türkçe cümlesi (varsa AYNEN gösterilir). */
    val serverMessage: String? = null,
    /** Tur ortasında kredi bitti (sohbet ve Tedavi Planı): o tura ait yarım çıktı ÇİZİLMEZ. */
    val yarida: Boolean = false,
) : Exception() {

    enum class Kind(val raw: String) {
        insufficient("INSUFFICIENT_CREDIT"),
        limitReached("CREDIT_LIMIT_REACHED"),
    }

    private fun alanlar() = listOf(kind, requiredCredits, availableCredits, scope, limitCredits, usedCredits, isDemo, canPurchase, serverMessage, yarida)
    override fun equals(other: Any?): Boolean = other is CreditRequired && other.alanlar() == alanlar()
    override fun hashCode(): Int = alanlar().hashCode()

    // MARK: - Metin (web `credits.gate.*` — Ek MO.23g/h)

    val title: String
        get() = when {
            isDemo -> "Demo krediniz bitti"
            kind == Kind.limitReached -> "Aylık kredi limitiniz doldu"
            else -> "Kredi yetersiz"
        }

    /** Gövde: yarıda kalan istekte Ek MO.23h; değilse sunucunun cümlesi AYNEN; o da yoksa sayılı cümleler + ipucu. */
    val detail: String
        get() {
            if (yarida) return YARIDA_METNI
            serverMessage?.let { return it }
            if (isDemo) return "Demo krediniz bitti."
            return when (kind) {
                Kind.limitReached -> {
                    val parts = mutableListOf<String>()
                    if (usedCredits != null && limitCredits != null) {
                        val f = scopeLabel
                        parts += if (f != null) "$f için bu ayki ${credits(limitCredits)} kredilik limitinizin ${credits(usedCredits)} kredisini kullandınız."
                        else "Bu ay ${credits(limitCredits)} kredilik limitinizin ${credits(usedCredits)} kredisini kullandınız."
                    }
                    parts += "Limitinizi kredi yükleme yetkisi olan kişi Kredi Yönetimi'nden değiştirebilir."
                    parts.joinToString(" ")
                }
                Kind.insufficient -> {
                    val parts = mutableListOf<String>()
                    parts += if (requiredCredits != null && requiredCredits > 0 && availableCredits != null) {
                        "Bu işlem ${credits(requiredCredits)} kredi gerektiriyor; kullanılabilir krediniz ${credits(availableCredits)}."
                    } else "Bu işlem için yeterli krediniz yok."
                    if (!canPurchase) parts += "Kredi yüklemek için klinik sahibine başvurun."
                    parts.joinToString(" ")
                }
            }
        }

    /** Sunucu cümlesi başlıkla başlıyor mu ("Kredi yetersiz. Kredi Yönetimi'nden…")? */
    private val basliklaBaslar: Boolean get() = detail.startsWith(title, ignoreCase = true)

    /** Tek cümlelik tam metin (sohbet balonu, kredi ekranı gerekçesi); başlık sunucu cümlesinde varsa iki kez yazılmaz. */
    override val message: String get() = if (basliklaBaslar) detail else "$title. $detail"

    /** Kart gövdesi: başlık kartın üstünde zaten yazılı — cümle başlıkla başlıyorsa o kısım atılır. */
    val kartMetni: String
        get() = if (basliklaBaslar) detail.substring(title.length).trimStart('.', ' ', ':', '—', '-').ifEmpty { detail } else detail

    /** Özellik limiti ise özelliğin adı; aylık toplam limitte null. */
    private val scopeLabel: String?
        get() = scope?.takeIf { it != "total" && it != "*" }?.let { CreditLabels.feature(it) }

    companion object {
        /** Ek MO.23h (web `credits.gate.partial`). */
        const val YARIDA_METNI = "Krediniz bu isteği tamamlamaya yetmedi; istek yarıda kaldı. Kullanılan kısım kredinizden düşüldü. Kredi Yönetimi'nden kredi yükleyebilirsiniz."

        private fun credits(v: Double): String = SmileCreditFormat.credits(v)

        /** HTTP yanıtından: 402 → kredi modeli (gövde okunamasa bile); 403 + `DEMO_CREDITS_EXHAUSTED` → demo; kodsuz 403 null. */
        fun parse(status: Int, data: ByteArray): CreditRequired? {
            val json = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            return when (status) {
                402 -> parse(json ?: JsonObject(emptyMap())) ?: CreditRequired(Kind.insufficient)
                403 -> {
                    val code = json?.str("code") ?: json?.str("error")
                    if (code == "DEMO_CREDITS_EXHAUSTED") parse(json!!) else null
                }
                else -> null
            }
        }

        /** Ayrıştırılmış gövde ya da SSE `error` olayı — kod tanınmazsa null. */
        fun parse(json: JsonObject): CreditRequired? {
            val code = json.str("code") ?: json.str("error")
            var demo = json.bool("isDemo") ?: false
            val kind = when (code) {
                Kind.insufficient.raw -> Kind.insufficient
                Kind.limitReached.raw -> Kind.limitReached
                "DEMO_CREDITS_EXHAUSTED" -> { demo = true; Kind.insufficient }
                else -> return null
            }
            val mesaj = json.str("message")?.trim()
            return CreditRequired(
                kind = kind,
                requiredCredits = json.num("requiredCredits"),
                availableCredits = json.num("availableCredits"),
                scope = json.str("scope"),
                limitCredits = json.num("limitCredits"),
                usedCredits = json.num("usedCredits"),
                isDemo = demo,
                canPurchase = kind == Kind.insufficient && !demo && (json.bool("canPurchase") ?: false),
                serverMessage = mesaj?.takeIf { it.isNotEmpty() },
                yarida = json.bool("yarida") ?: false,
            )
        }
    }
}

/** Ek MO.23f — `E_CREDIT_FX_MISSING` sunucu cümlesi yoksa. */
const val KREDI_KUR_YOK_METNI = "Kredi şu an hesaplanamıyor; biraz sonra tekrar deneyin."

/** Sunucu hata gövdesinin makine kodu (`code`; yoksa kod biçimli `error`) — ekrana BASILMAZ (M0-7). */
fun serverCode(data: ByteArray): String? {
    val json = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
    json.str("code")?.takeIf { it.isNotEmpty() }?.let { return it }
    json.str("error")?.takeIf { it.isNotEmpty() && !it.contains(" ") }?.let { return it }
    return null
}

/**
 * Hata gövdesinin ekrandaki cümlesi, durumu bilerek: 403'te kodsuz yetenek reddi Ek MO3.7, iş kodlu 403 sunucunun cümlesi
 * (M-E3.6 · `Yetki.yasakMetni`); öteki durumlarda `serverSentence`.
 */
fun hataCumlesi(status: Int, data: ByteArray): String? = if (status == 403) Yetki.yasakMetni(data) else serverSentence(data)
