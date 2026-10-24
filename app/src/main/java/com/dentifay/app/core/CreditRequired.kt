package com.dentifay.app.core

import com.dentifay.app.credit.CreditLabels
import com.dentifay.app.credit.SmileCreditFormat
import kotlinx.serialization.json.JsonObject

/**
 * Sunucunun "kredi gerekli" yanıtı — HTTP **402**, gövde her uçta AYNI.
 * Sohbet (SSE), sesli mod ve seslendirme bu TEK modelden geçer; ekrana ham kod ya da JSON basılmaz.
 * ⚠ Gülüş simülasyonu uçları (`/api/ai/smile/…`) kendi gövdesini taşır (KieImageEditClient).
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
) : Exception() {

    enum class Kind(val raw: String) {
        insufficient("INSUFFICIENT_CREDIT"),
        limitReached("CREDIT_LIMIT_REACHED"),
    }

    override fun equals(other: Any?): Boolean = other is CreditRequired && other.kind == kind &&
        other.requiredCredits == requiredCredits && other.availableCredits == availableCredits && other.scope == scope &&
        other.limitCredits == limitCredits && other.usedCredits == usedCredits && other.isDemo == isDemo && other.canPurchase == canPurchase

    override fun hashCode(): Int = listOf(kind, requiredCredits, availableCredits, scope, limitCredits, usedCredits, isDemo, canPurchase).hashCode()

    // MARK: - Metin

    /** Kredi ekranına yönlendirme önerilsin mi? Mağaza derlemesinde hiç önerilmez (`AppConfig.inAppPurchase`). */
    val offersPurchase: Boolean get() = AppConfig.inAppPurchase && kind == Kind.insufficient && canPurchase && !isDemo

    val title: String
        get() = when (kind) {
            Kind.limitReached -> "Aylık kredi limitiniz doldu"
            Kind.insufficient -> if (isDemo) "Demo krediniz bitti" else "Kredi yetersiz"
        }

    /** Sayılar + ne yapılabileceği (başlık hariç). */
    val detail: String
        get() = when (kind) {
            Kind.limitReached -> {
                val parts = mutableListOf<String>()
                if (usedCredits != null && limitCredits != null) {
                    val kapsam = scopeLabel?.let { "$it için " } ?: ""
                    parts += "Bu ay $kapsam${credits(usedCredits)} / ${credits(limitCredits)} kredi kullanıldı."
                }
                if (requiredCredits != null && requiredCredits > 0) parts += "Bu işlem ${credits(requiredCredits)} kredi."
                parts += "Limiti değiştirmek için klinik sahibine başvurun."
                parts.joinToString(" ")
            }
            Kind.insufficient -> {
                val parts = mutableListOf<String>()
                if (requiredCredits != null && requiredCredits > 0) {
                    val have = availableCredits?.let { ", kalan krediniz ${credits(it)}" } ?: ""
                    parts += "Bu işlem ${credits(requiredCredits)} kredi$have."
                } else if (availableCredits != null) {
                    parts += "Kalan krediniz ${credits(availableCredits)}."
                }
                parts += when {
                    isDemo -> "Devam etmek için Dentifay satış ekibiyle görüşebilirsiniz."
                    canPurchase -> "Kredi yükleyerek devam edebilirsiniz."
                    AppConfig.inAppPurchase -> "Kredi yüklemek için klinik sahibine başvurun."
                    else -> "Klinik yöneticinize haber verin."
                }
                parts.joinToString(" ")
            }
        }

    /** Tek cümlelik tam metin. */
    override val message: String get() = "$title. $detail"

    /** Sesli modda okunacak kısa metin (sayısız). */
    val spokenText: String
        get() = when (kind) {
            Kind.limitReached -> "Aylık kredi limitiniz doldu. Limiti değiştirmek için klinik sahibine başvurun."
            Kind.insufficient -> when {
                isDemo -> "Demo krediniz bitti."
                canPurchase -> "Krediniz yetersiz. Kredi yükleyerek devam edebilirsiniz."
                AppConfig.inAppPurchase -> "Krediniz yetersiz. Kredi yüklemek için klinik sahibine başvurun."
                else -> "Krediniz yetersiz. Klinik yöneticinize haber verin."
            }
        }

    private val scopeLabel: String?
        get() = scope?.takeIf { it != "total" && it != "*" }?.let { CreditLabels.feature(it) }

    companion object {
        private fun credits(v: Double): String = SmileCreditFormat.credits(v)

        /** HTTP yanıtından: yalnız 402'de değer döner; gövde okunamasa bile "kredi yetersiz". */
        fun parse(status: Int, data: ByteArray): CreditRequired? {
            if (status != 402) return null
            val json = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            return parse(json ?: JsonObject(emptyMap())) ?: CreditRequired(Kind.insufficient)
        }

        /** Ayrıştırılmış gövde (ya da SSE `error` olayı) — kod tanınmazsa null. */
        fun parse(json: JsonObject): CreditRequired? {
            val code = json.str("code") ?: json.str("error")
            var demo = json.bool("isDemo") ?: false
            val kind = when (code) {
                Kind.insufficient.raw -> Kind.insufficient
                Kind.limitReached.raw -> Kind.limitReached
                "DEMO_CREDITS_EXHAUSTED" -> { demo = true; Kind.insufficient }
                else -> return null
            }
            return CreditRequired(
                kind = kind,
                requiredCredits = json.num("requiredCredits"),
                availableCredits = json.num("availableCredits"),
                scope = json.str("scope"),
                limitCredits = json.num("limitCredits"),
                usedCredits = json.num("usedCredits"),
                isDemo = demo,
                // Limit dolduğunda kredi almak limiti AÇMAZ; demoda ve mağaza derlemesinde satın alma yok.
                canPurchase = AppConfig.inAppPurchase && kind == Kind.insufficient && !demo && (json.bool("canPurchase") ?: false),
            )
        }
    }
}
