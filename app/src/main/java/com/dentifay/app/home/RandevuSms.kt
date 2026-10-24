package com.dentifay.app.home

import com.dentifay.app.core.ApiClient
import kotlinx.serialization.Serializable

/**
 * `GET /api/sms-automations/active-kinds` — Ekleme 19 biçimi `modes: [{kind, mode: 'AUTO'|'ASK'}]`; eski `kinds` yedek
 * (MOBIL_IS_EMRI M-E3.5; DentAIWeb 38I). Şablon/metin/hasta verisi DÖNMEZ.
 */
@Serializable
data class SmsKipleri(
    val modes: List<Kip>? = null,
    val kinds: List<String>? = null,
) {
    @Serializable
    data class Kip(val kind: String, val mode: String)
}

/** Randevu durumu değişirken hastaya SMS sorusu — saf kurallar (test edilebilir). */
object RandevuSms {
    sealed class Kip {
        /** Otomasyon "Otomatik": soru YOK, bilgi satırı Ek MO3.5, gövdede `notifyPatient` YOK (sözleşme: alan yoksa gider). */
        data class otomatik(val tur: String) : Kip()

        /** Otomasyon "Sor" (ya da kip bilinmiyor): ön seçimsiz soru Ek MO3.6; cevap gövdeye birebir (sözleşme: yalnız `true` gönderir). */
        data class sor(val tur: String) : Kip()

        /** Tür yok / otomasyon kapalı / telefon yok: soru yok, `notifyPatient` gönderilmez. */
        data object yok : Kip()
    }

    /**
     * Durum → mesaj türü — sözleşme §10 (Ekleme 19 · 38I): "Ertelendi/İptal → randevu iptal türü · Gelmedi → gelmeyen hasta ·
     * saat/hekim değişikliği → randevu değişti" (mobil saat/hekim değiştirmez). Sunucunun kayıt defteri ve web
     * `DurumOnayDialog#DURUM_TURU` ile AYNI.
     */
    val durumTuru: Map<String, String> = mapOf(
        "CANCELLED" to "APPOINTMENT_CANCELLED",
        "RESCHEDULED" to "APPOINTMENT_CANCELLED",
        "NO_SHOW" to "APPOINTMENT_NO_SHOW",
    )

    /** Türün kullanıcıya görünen adı — web `comms.messageKinds.items.<tür>.label` (cümle içinde küçük harf). */
    val turAdi: Map<String, String> = mapOf(
        "APPOINTMENT_CANCELLED" to "randevu iptali",
        "APPOINTMENT_NO_SHOW" to "gelmeyen hasta",
        // Ödeme planı (web `comms.messageKinds.items.<tür>.label`, M-E9.2).
        "PAYMENT_PLAN_CREATED" to "Ödeme planı bilgilendirmesi",
        "IBAN_INFO" to "IBAN bilgisi",
    )

    /** `yanit == null` → uç okunamadı ya da henüz gelmedi: soru ÇİZİLİR (kip bilinmiyorsa sormak güvenli taraftır). */
    fun kip(durum: String, yanit: SmsKipleri?, telefonVar: Boolean): Kip {
        if (!telefonVar) return Kip.yok
        val tur = durumTuru[durum] ?: return Kip.yok
        if (yanit == null) return Kip.sor(tur)
        yanit.modes?.let { modes ->
            return when (modes.firstOrNull { it.kind == tur }?.mode?.uppercase()) {
                "AUTO" -> Kip.otomatik(tur)
                "ASK" -> Kip.sor(tur)
                else -> Kip.yok
            }
        }
        // Eski biçim (yalnız `kinds`): tür açıksa bugünkü davranış — gönder/gönderme seçimi.
        yanit.kinds?.let { kinds -> return if (tur in kinds) Kip.sor(tur) else Kip.yok }
        return Kip.yok
    }

    /** Ek MO3.5 / MO3.6. */
    fun otomatikMetni(tur: String): String = "Hastaya ${ad(tur)} SMS'i otomatik gönderilecek."
    fun soruMetni(tur: String): String = "Hastaya ${ad(tur)} SMS'i gönderilsin mi?"
    const val secimsizMetni = "Devam etmek için SMS seçimini yapın."
    private fun ad(tur: String): String = turAdi[tur] ?: "bilgilendirme"

    /** Onay düğmesi açık mı? `sor`da seçim yapılmadan KAPALI (ön seçim yok). */
    fun onayAcik(kip: Kip, cevap: Boolean?): Boolean = if (kip is Kip.sor) cevap != null else true

    /** Gövdedeki `notifyPatient`: yalnız `sor`da ve kullanıcının cevabıyla; öteki kiplerde alan YOK (sunucu karar verir). */
    fun notifyPatient(kip: Kip, cevap: Boolean?): Boolean? = if (kip is Kip.sor) cevap else null
}

/** Randevu durumunda SMS kipleri (oturum açmış herkese açık uç). */
suspend fun ApiClient.fetchSmsKinds(): SmsKipleri = request(path = "/sms-automations/active-kinds")
