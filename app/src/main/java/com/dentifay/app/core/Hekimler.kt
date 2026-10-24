package com.dentifay.app.core

import com.dentifay.app.data.Doctor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// MARK: - Hekim listeleri

/** `GET /api/schedule/doctors?atanabilir=1` (Tedavi Planı) — web plan ekranıyla AYNI küme. */
@Serializable
data class AtanabilirHekimler(val doctors: List<APIDoctorMember> = emptyList())

/** `GET /api/schedule/doctors?date=YYYY-MM-DD` — seçili günün takvim sütunları (`{doctors, closedDoctors, day}`). */
@Serializable
data class TakvimHekimleri(val doctors: List<APIDoctorMember> = emptyList(), val closedDoctors: List<APIDoctorMember>? = null)

/** Ana ekran hekim seçicisinin saf kuralları (D12-17): istemci rol süzgeci YOK; liste değişince seçim `apiUserId` ile korunur. */
object HekimSecici {
    data class Satir(val uye: APIDoctorMember, val kapali: Boolean)

    /** Önce açık hekimler, sonra o gün sütunu kapalı olanlar. */
    fun liste(yanit: TakvimHekimleri): List<Satir> =
        yanit.doctors.map { Satir(it, false) } + (yanit.closedDoctors ?: emptyList()).map { Satir(it, true) }

    /**
     * Önceki seçim listede varsa o; yoksa giriş yapan hekimin kendisi (listede ve o gün sütunu açıksa — hekim uygulamayı açınca
     * başkasının boş gününü değil kendi programını görür); o da yoksa ilk açık hekim (o da yoksa ilk satır).
     */
    fun korunanSecim(oncekiId: String?, liste: List<Doctor>, kendiId: String? = null): String? {
        if (oncekiId != null && liste.any { it.apiUserId == oncekiId }) return oncekiId
        if (kendiId != null && liste.any { it.apiUserId == kendiId && !it.kapali }) return kendiId
        return (liste.firstOrNull { !it.kapali } ?: liste.firstOrNull())?.apiUserId
    }
}

// MARK: - FDI

/**
 * FDI diş numarası — sunucunun `lib/fdi.js` kümesinin AYNISI (52 değer): kalıcı (çeyrek 1–4 × diş 1–8) + süt (çeyrek 5–8 × diş 1–5).
 * Denetim yalnız erken söylemek içindir; karar sunucunun.
 */
object FDI {
    fun gecerliMi(raw: String): Boolean {
        val t = raw.trim()
        if (t.length != 2 || !t.all { it.isDigit() }) return false
        val n = t.toInt()
        val ceyrek = n / 10
        val dis = n % 10
        if (ceyrek in 1..4) return dis in 1..8
        if (ceyrek in 5..8) return dis in 1..5
        return false
    }

    /** "36, 37" / "36 37" → ["36", "37"] */
    fun ayir(girdi: String): List<String> = girdi.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Ek MO.8 — geçersiz diş metni. */
    fun hataMetni(girdi: String): String =
        "«$girdi» geçerli bir diş numarası değil. Kalıcı: 11–18, 21–28, 31–38, 41–48 · süt: 51–55, 61–65, 71–75, 81–85."
}

// MARK: - Tutar biçimi

/** TL sayısını `tr_TR` biçimine çevirir — yalnız sunucunun verdiği TL sayısı için (toplama YOK). Swift `TL.bicim`. */
object TL {
    private val tr = Locale.forLanguageTag("tr-TR")

    fun bicim(value: Double): String {
        val f = NumberFormat.getCurrencyInstance(tr)
        f.currency = Currency.getInstance("TRY")
        return runCatching { f.format(value) }.getOrElse { "$value ₺" }
    }
}

/**
 * Çok dişli (grup) kalemin tutar metni (M-E5.1): sunucunun `grupToplami`, yoksa çocuk kalemlerin sunucu tutarlarının toplamı,
 * ikisi de yoksa "—". "₺0" ASLA (eksik tutar sıfır gibi görünmesin). Tek dişli kalem bugünkü gibi.
 */
object GrupTutari {
    fun metin(grupToplami: Double?, cocuklar: List<Double?> = emptyList()): String {
        if (grupToplami != null && grupToplami > 0) return TL.bicim(grupToplami)
        val bilinen = cocuklar.filterNotNull()
        val toplam = bilinen.sum()
        if (bilinen.isNotEmpty() && toplam > 0) return TL.bicim(toplam)
        return "—"
    }
}

// MARK: - Esnek sayı

/** Sayı ya da sayı metni ("1500.00") olabilen JSON değeri — Swift `EsnekSayi`. */
@Serializable(with = EsnekSayiSerializer::class)
data class EsnekSayi(val deger: Double)

object EsnekSayiSerializer : KSerializer<EsnekSayi> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EsnekSayi", PrimitiveKind.DOUBLE)
    override fun deserialize(decoder: Decoder): EsnekSayi {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return EsnekSayi(decoder.decodeDouble())
        val p = el as? JsonPrimitive ?: throw kotlinx.serialization.SerializationException("sayı değil")
        val d = (if (p is JsonNull) null else p.doubleOrNull ?: p.content.toDoubleOrNull())
            ?: throw kotlinx.serialization.SerializationException("sayı değil")
        return EsnekSayi(d)
    }
    override fun serialize(encoder: Encoder, value: EsnekSayi) = encoder.encodeDouble(value.deger)
}

// MARK: - JSON gövdesi

/**
 * İstek gövdesi kurucusu — Swift `Govde.nesne([...])` karşılığı. Değeri null olan anahtar ATILIR (açık `null` için `JsonNull` verin).
 * Desteklenen değerler: String, Number, Boolean, JsonElement, List<*>, Map<String, *>.
 */
fun govde(vararg alanlar: Pair<String, Any?>): JsonObject =
    JsonObject(alanlar.mapNotNull { (k, v) -> v?.let { k to jsonDegeri(it) } }.toMap())

fun jsonDegeri(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is EsnekSayi -> JsonPrimitive(v.deger)
    is List<*> -> JsonArray(v.map { jsonDegeri(it) })
    is Map<*, *> -> JsonObject(v.entries.mapNotNull { (k, d) -> d?.let { k.toString() to jsonDegeri(it) } }.toMap())
    else -> throw IllegalArgumentException("Desteklenmeyen gövde değeri: ${v::class.simpleName}")
}
