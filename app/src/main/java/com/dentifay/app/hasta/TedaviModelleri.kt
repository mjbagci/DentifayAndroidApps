package com.dentifay.app.hasta

import com.dentifay.app.core.EsnekSayi
import com.dentifay.app.core.jsonDegeri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Kişi özeti (`{ id, name }`) — plan hekimi, kalem hekimi. */
@Serializable
data class KisiOzeti(val id: String, val name: String? = null)

/**
 * Tedavi kalemi — `GET /api/treatment-plans/:id` ağacı ve `GET /api/treatment-items` satırı (DentAIWeb @ afc3170).
 *
 * Çözme Swift'teki özel `init(from:)` gibi HOŞGÖRÜLÜDÜR: yalnız `id` zorunludur; beklenmeyen türdeki alan (`try?`) `null` olur,
 * kalem düşmez. `quadrant` sayı ya da sayı metni; tutarlar `EsnekSayi` (Prisma `Decimal` metni ya da sayı).
 *
 * Birincil kurucu bütün alanları ister (çözücü kullanır). Test ve örnekler Swift'in ikinci `init`iyle AYNI imzalı ikincil kurucuyu
 * kullanır: `TedaviKalemi(id = "k", name = "Dolgu", stage = "TODO", tooth = "36", visualKey = "filling", price = 1800.0)`.
 */
@Serializable(with = TedaviKalemiSerializer::class)
data class TedaviKalemi(
    val id: String,
    val treatmentPlanId: String?,
    val name: String,
    val code: String?,
    val tooth: String?,
    val teeth: List<Dis>?,
    val toothCodes: List<String>?,
    val quadrant: Int?,
    val jawType: String?,
    val stage: String?,
    val price: EsnekSayi?,
    val quantity: Int?,
    val discount: EsnekSayi?,
    val totalAmountTRY: EsnekSayi?,
    val currency: String?,
    val exchangeRate: EsnekSayi?,
    val treatmentDate: String?,
    val completedAt: String?,
    val createdAt: String?,
    val deletedAt: String?,
    val parentItemId: String?,
    val assignedDoctorId: String?,
    val assignedDoctor: KisiOzeti?,
    val catalogServiceId: String?,
    val catalog: Katalog?,
    val children: List<TedaviKalemi>?,
    val grupToplami: EsnekSayi?,
    val grupIndirimi: EsnekSayi?,
    val notes: String?,
    /** `CREATED_DIRECTLY` · `FROM_PLANNED` · `FROM_TODO` · `REVERTED` — durum rozeti (web `TransitionBadge`). */
    val transitionSource: String?,
    // `gruplu=1` aşama satırı (plan-yaniti#asamaGruplari)
    val cocukKimlikleri: List<String>?,
    val toplamDis: Int?,
    val kismi: Boolean?,
    val hekimler: List<String>?,
    val plan: PlanOzeti?,
) {
    data class Dis(val toothCode: String)
    data class Katalog(val id: String?, val name: String?, val visualKey: String?)
    data class PlanOzeti(val id: String, val title: String?, val status: String?)

    /** Test ve örnek için (Swift'in ikinci `init`i — aynı etiketler, tutarlar `Double`). */
    constructor(
        id: String,
        name: String,
        stage: String?,
        tooth: String? = null,
        toothCodes: List<String>? = null,
        quadrant: Int? = null,
        jawType: String? = null,
        visualKey: String? = null,
        parentItemId: String? = null,
        deletedAt: String? = null,
        children: List<TedaviKalemi>? = null,
        price: Double? = null,
        quantity: Int? = 1,
        discount: Double? = null,
        totalAmountTRY: Double? = null,
        doktor: KisiOzeti? = null,
        treatmentDate: String? = null,
        planId: String? = null,
    ) : this(
        id = id, treatmentPlanId = planId, name = name, code = null, tooth = tooth, teeth = null, toothCodes = toothCodes,
        quadrant = quadrant, jawType = jawType, stage = stage, price = price?.let(::EsnekSayi), quantity = quantity,
        discount = discount?.let(::EsnekSayi), totalAmountTRY = totalAmountTRY?.let(::EsnekSayi), currency = "TRY",
        exchangeRate = null, treatmentDate = treatmentDate, completedAt = null, createdAt = null, deletedAt = deletedAt,
        parentItemId = parentItemId, assignedDoctorId = doktor?.id, assignedDoctor = doktor, catalogServiceId = null,
        catalog = visualKey?.let { Katalog(id = null, name = null, visualKey = it) }, children = children, grupToplami = null,
        grupIndirimi = null, notes = null, transitionSource = null, cocukKimlikleri = null, toplamDis = null, kismi = null,
        hekimler = null, plan = null,
    )

    /** Canlı (silinmemiş) çocuklar. */
    val canliCocuklar: List<TedaviKalemi> get() = (children ?: emptyList()).filter { it.deletedAt == null }
    val grupMu: Boolean get() = canliCocuklar.isNotEmpty()

    companion object {
        /** JSON nesnesinden (Swift `init(from:)`); `id` metin değilse `null`. */
        fun coz(o: JsonObject): TedaviKalemi? {
            val id = o.metin("id") ?: return null
            return TedaviKalemi(
                id = id,
                treatmentPlanId = o.metin("treatmentPlanId"),
                name = o.metin("name") ?: "",
                code = o.metin("code"),
                tooth = o.metin("tooth"),
                teeth = o.disler("teeth"),
                toothCodes = o.metinler("toothCodes"),
                // `quadrant` Int ya da dize olabilir.
                quadrant = o.tam("quadrant") ?: o.metin("quadrant")?.toIntOrNull(),
                jawType = o.metin("jawType"),
                stage = o.metin("stage"),
                price = o.esnek("price"),
                quantity = o.tam("quantity"),
                discount = o.esnek("discount"),
                totalAmountTRY = o.esnek("totalAmountTRY"),
                currency = o.metin("currency"),
                exchangeRate = o.esnek("exchangeRate"),
                treatmentDate = o.metin("treatmentDate"),
                completedAt = o.metin("completedAt"),
                createdAt = o.metin("createdAt"),
                deletedAt = o.metin("deletedAt"),
                parentItemId = o.metin("parentItemId"),
                assignedDoctorId = o.metin("assignedDoctorId"),
                assignedDoctor = kisiCoz(o["assignedDoctor"]),
                catalogServiceId = o.metin("catalogServiceId"),
                catalog = katalogCoz(o["catalog"]),
                children = (o["children"] as? JsonArray)?.let { a -> a.map { c -> (c as? JsonObject)?.let { coz(it) } ?: return@let null } },
                grupToplami = o.esnek("grupToplami"),
                grupIndirimi = o.esnek("grupIndirimi"),
                notes = o.metin("notes"),
                transitionSource = o.metin("transitionSource"),
                cocukKimlikleri = o.metinler("cocukKimlikleri"),
                toplamDis = o.tam("toplamDis"),
                kismi = o.mantik("kismi"),
                hekimler = o.metinler("hekimler"),
                plan = planOzetiCoz(o["plan"]),
            )
        }

        /** Sunucu biçiminde JSON (yalnız dolu alanlar). */
        fun kodla(k: TedaviKalemi): JsonObject {
            val a = LinkedHashMap<String, JsonElement>()
            fun koy(ad: String, v: Any?) { if (v != null) a[ad] = jsonDegeri(v) }
            koy("id", k.id); koy("treatmentPlanId", k.treatmentPlanId); koy("name", k.name); koy("code", k.code)
            koy("tooth", k.tooth); koy("teeth", k.teeth?.map { mapOf("toothCode" to it.toothCode) }); koy("toothCodes", k.toothCodes)
            koy("quadrant", k.quadrant); koy("jawType", k.jawType); koy("stage", k.stage); koy("price", k.price)
            koy("quantity", k.quantity); koy("discount", k.discount); koy("totalAmountTRY", k.totalAmountTRY)
            koy("currency", k.currency); koy("exchangeRate", k.exchangeRate); koy("treatmentDate", k.treatmentDate)
            koy("completedAt", k.completedAt); koy("createdAt", k.createdAt); koy("deletedAt", k.deletedAt)
            koy("parentItemId", k.parentItemId); koy("assignedDoctorId", k.assignedDoctorId)
            koy("assignedDoctor", k.assignedDoctor?.let { mapOf("id" to it.id, "name" to it.name) })
            koy("catalogServiceId", k.catalogServiceId)
            koy("catalog", k.catalog?.let { mapOf("id" to it.id, "name" to it.name, "visualKey" to it.visualKey) })
            koy("children", k.children?.map { kodla(it) }); koy("grupToplami", k.grupToplami); koy("grupIndirimi", k.grupIndirimi)
            koy("notes", k.notes); koy("transitionSource", k.transitionSource); koy("cocukKimlikleri", k.cocukKimlikleri)
            koy("toplamDis", k.toplamDis); koy("kismi", k.kismi); koy("hekimler", k.hekimler)
            koy("plan", k.plan?.let { mapOf("id" to it.id, "title" to it.title, "status" to it.status) })
            return JsonObject(a)
        }
    }
}

/** `TedaviKalemi`nin JSON çözücüsü — hoşgörülü alan çözümü `TedaviKalemi.coz`ta. */
object TedaviKalemiSerializer : KSerializer<TedaviKalemi> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.dentifay.app.hasta.TedaviKalemi")

    override fun deserialize(decoder: Decoder): TedaviKalemi {
        val jd = decoder as? JsonDecoder ?: throw SerializationException("TedaviKalemi yalnız JSON'dan çözülür")
        val o = jd.decodeJsonElement() as? JsonObject ?: throw SerializationException("TedaviKalemi nesne değil")
        return TedaviKalemi.coz(o) ?: throw SerializationException("TedaviKalemi: id yok")
    }

    override fun serialize(encoder: Encoder, value: TedaviKalemi) {
        val je = encoder as? JsonEncoder ?: throw SerializationException("TedaviKalemi yalnız JSON'a yazılır")
        je.encodeJsonElement(TedaviKalemi.kodla(value))
    }
}

// MARK: - Hoşgörülü alan okuyucular (Swift `try? c.decodeIfPresent(T.self, …)`)

/** Metin alanı: yok / `null` / başka tür → `null`. */
private fun JsonObject.metin(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** Tam sayı alanı (metin DEĞİL): `2` ve `2.0` → 2; başka tür → `null`. */
private fun JsonObject.tam(k: String): Int? {
    val p = this[k] as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString || p.booleanOrNull != null) return null
    p.intOrNull?.let { return it }
    val d = p.doubleOrNull ?: return null
    return if (d % 1.0 == 0.0 && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
}

/** Mantıksal alan (yalnız `true`/`false`). */
private fun JsonObject.mantik(k: String): Boolean? =
    (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

/** `EsnekSayi`: sayı ya da sayı metni ("1800.00"); mantıksal ve başka tür → `null`. */
private fun JsonObject.esnek(k: String): EsnekSayi? {
    val p = this[k] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val d = if (p.isString) p.content.toDoubleOrNull() else if (p.booleanOrNull != null) null else p.doubleOrNull
    return d?.let(::EsnekSayi)
}

/** Metin dizisi: bir öğe bile metin değilse dizi `null` (Swift `[String]` çözümü bütün ya da hiç). */
private fun JsonObject.metinler(k: String): List<String>? {
    val a = this[k] as? JsonArray ?: return null
    return a.map { el -> (el as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: return null }
}

private fun JsonObject.disler(k: String): List<TedaviKalemi.Dis>? {
    val a = this[k] as? JsonArray ?: return null
    return a.map { el -> TedaviKalemi.Dis((el as? JsonObject)?.metin("toothCode") ?: return null) }
}

/** Swift'in sentezlenmiş `Decodable`ı: `String?` alanında başka tür → bütün nesne düşer. */
private fun JsonObject.katiMetinMi(k: String): Boolean {
    val v = this[k] ?: return true
    return v is JsonNull || (v is JsonPrimitive && v.isString)
}

private fun kisiCoz(el: JsonElement?): KisiOzeti? {
    val o = el as? JsonObject ?: return null
    val id = o.metin("id") ?: return null
    if (!o.katiMetinMi("name")) return null
    return KisiOzeti(id, o.metin("name"))
}

private fun katalogCoz(el: JsonElement?): TedaviKalemi.Katalog? {
    val o = el as? JsonObject ?: return null
    if (!o.katiMetinMi("id") || !o.katiMetinMi("name") || !o.katiMetinMi("visualKey")) return null
    return TedaviKalemi.Katalog(o.metin("id"), o.metin("name"), o.metin("visualKey"))
}

private fun planOzetiCoz(el: JsonElement?): TedaviKalemi.PlanOzeti? {
    val o = el as? JsonObject ?: return null
    val id = o.metin("id") ?: return null
    if (!o.katiMetinMi("title") || !o.katiMetinMi("status")) return null
    return TedaviKalemi.PlanOzeti(id, o.metin("title"), o.metin("status"))
}

/** Tedavi planı — liste (özet) ve ayrıntı (`items` ağacı) aynı modeli kullanır. */
@Serializable
data class HastaPlani(
    val id: String,
    val title: String? = null,
    val status: String? = null,
    val isActive: Boolean? = null,
    val totalPrice: EsnekSayi? = null,
    val plannedTotal: EsnekSayi? = null,
    val completedTotal: EsnekSayi? = null,
    val treatmentDate: String? = null,
    val createdAt: String? = null,
    val notes: String? = null,
    val doctorUserId: String? = null,
    val doctor: KisiOzeti? = null,
    val items: List<TedaviKalemi>? = null,
    val version: Int? = null,
    val onayBekliyor: Boolean? = null,
    val kapali: Boolean? = null,
    val sistemPlani: Boolean? = null,
    val kalemSayisi: Int? = null,
    val onamEtiketi: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class PlanListesiYaniti(val plans: List<HastaPlani> = emptyList(), val sonrakiPlanAdi: String? = null)

@Serializable
data class PlanYaniti(val plan: HastaPlani)

@Serializable
data class KalemListesiYaniti(val items: List<TedaviKalemi> = emptyList())

/** Hasta görseli — `GET /api/patients/:id/media`. */
@Serializable
data class HastaMedyasi(
    val id: String,
    val category: String? = null,
    val fileName: String? = null,
    val fileUrl: String = "",
    val thumbnailUrl: String? = null,
    val mimeType: String? = null,
    val capturedAt: String? = null,
    val createdAt: String? = null,
    val note: String? = null,
    val deletedAt: String? = null,
    val isFavorite: Boolean? = null,
) {
    val resimMi: Boolean get() = (mimeType ?: "").startsWith("image/")
}

/** `GET /api/patients/:id/media` yanıtı — sayfalı (`pagination.nextCursor`; `null` son sayfa). */
@Serializable
data class MedyaYaniti(
    val media: List<HastaMedyasi> = emptyList(),
    val counts: Map<String, Int>? = null,
    val can: Yetki? = null,
    val pagination: Sayfa? = null,
) {
    @Serializable
    data class Yetki(val trash: Boolean? = null, val purge: Boolean? = null)

    @Serializable
    data class Sayfa(val nextCursor: String? = null)
}

/** Medya kategorileri (sunucuda sabit küme — MediaCategory; web MediaTab grupları ve etiketleri). */
object MedyaKategorisi {
    data class Kategori(val kod: String, val ad: String)
    data class Grup(val baslik: String, val kategoriler: List<Kategori>)

    val gruplar: List<Grup> = listOf(
        Grup(
            "Röntgenler",
            listOf(
                Kategori("XRAY_PANORAMIC", "Panoramik"), Kategori("XRAY_PERIAPICAL", "Periapikal"),
                Kategori("XRAY_BITEWING", "Bite-wing"), Kategori("XRAY_CEPHALOMETRIC", "Sefalometrik"), Kategori("XRAY_CBCT", "CBCT"),
            ),
        ),
        Grup(
            "Fotoğraflar",
            listOf(
                Kategori("PHOTO_INTRAORAL", "Ağız İçi"), Kategori("PHOTO_FACE", "Yüz/Profil"), Kategori("PHOTO_SMILE", "Gülüş"),
                Kategori("PHOTO_BEFORE_AFTER", "Before/After"),
            ),
        ),
        Grup(
            "Belgeler",
            listOf(
                Kategori("DOCUMENT_CONSENT", "Onam Formu"), Kategori("DOCUMENT_PRESCRIPTION", "Reçete"),
                Kategori("DOCUMENT_REPORT", "Rapor"), Kategori("DOCUMENT_OTHER", "Diğer"),
            ),
        ),
    )

    private val adlar: Map<String, String> = gruplar.flatMap { it.kategoriler }.associate { it.kod to it.ad }

    fun ad(kod: String?): String = kod?.let { adlar[it] } ?: "Diğer"

    /** Grubun kategori kodları (süzgeç). */
    fun kodlar(grupBasligi: String): Set<String> =
        gruplar.firstOrNull { it.baslik == grupBasligi }?.kategoriler?.mapTo(HashSet()) { it.kod } ?: emptySet()
}
