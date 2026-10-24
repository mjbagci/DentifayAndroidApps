package com.dentifay.app.hasta

import com.dentifay.app.core.govde
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Plan penceresinin SAF kuralları (MOBIL_IS_EMRI M-E8.4) — yeni plan · bu plana ekle · doğrudan ekle (aşama listesi) · planı düzenle.
 * Web `NewPlanModal` / `AddItemDirectModal` (DentAIWeb @ afc3170). Fiyat sunucudan: dokunulmamış katalog satırında FİYAT GÖNDERİLMEZ.
 */
object TedaviEditoru {

    // MARK: Tedavi seçici — düz arama (ad · tarife kodu · grup), web katlaması, en çok 30 sonuç

    private val tr = Locale.forLanguageTag("tr-TR")
    private val harita = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u')

    /** Web katlaması: `toLocaleLowerCase('tr-TR')` sonra ç→c ğ→g ı→i ö→o ş→s ü→u (İ/I de i'ye iner). */
    fun katla(s: String): String {
        // Ayrık işaretli harfler (u + ¨) Swift'te tek karakter sayılır; burada önce birleştirilir.
        val kucuk = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(tr)
        return buildString(kucuk.length) { for (c in kucuk) append(harita[c] ?: c) }
    }

    const val AZAMI_SONUC = 30

    /** Swift `(sonuclar: [KatalogKalemi], dahaVar: Bool)`. */
    data class AramaSonucu(val sonuclar: List<KatalogKalemi>, val dahaVar: Boolean)

    /** Her kalemin katlanmış arama metni (ad · tarife kodu · grup) — katalog değişince bir kez kurulur (her tuşta değil). */
    fun aramaDizini(katalog: List<KatalogKalemi>): List<String> =
        katalog.map { k -> katla(listOf(k.name, k.tarifeKodu ?: "", k.group ?: "").joinToString(" ")) }

    /** Her boşlukla ayrılan parça adda, tarife kodunda ya da grup adında geçmeli; en çok 30 + "daha var". */
    fun ara(katalog: List<KatalogKalemi>, sorgu: String, dizin: List<String>? = null): AramaSonucu {
        val parcalar = katla(sorgu).split(" ").filter { it.isNotEmpty() }
        if (parcalar.isEmpty()) return AramaSonucu(emptyList(), false)
        val metinler = dizin?.takeIf { it.size == katalog.size } ?: aramaDizini(katalog)
        val eslesen = katalog.filterIndexed { i, _ -> parcalar.all { metinler[i].contains(it) } }
        return AramaSonucu(eslesen.take(AZAMI_SONUC), eslesen.size > AZAMI_SONUC)
    }

    /** Swift `(ad: String, kalemler: [KatalogKalemi])`. */
    data class Grup(val ad: String, val kalemler: List<KatalogKalemi>)

    /** Arama boşken gruplar (sunucunun grup adlarıyla; "Diğer" en sonda). */
    fun gruplar(katalog: List<KatalogKalemi>): List<Grup> {
        val s = katalog.groupBy { it.group?.takeIf { g -> g.isNotEmpty() } ?: "Diğer" }
        val harmanlayici = Collator.getInstance(tr)
        val adlar = s.keys.sortedWith { a, b ->
            when {
                a == b -> 0
                a == "Diğer" -> 1
                b == "Diğer" -> -1
                else -> harmanlayici.compare(a, b)
            }
        }
        return adlar.map { ad -> Grup(ad, (s[ad] ?: emptyList()).sortedWith { x, y -> harmanlayici.compare(x.name, y.name) }) }
    }

    // MARK: Satırlar

    /** Pencerede eklenecek / düzenlenecek satır (Swift `var` alanları → `copy`). */
    data class Satir(
        val id: String = UUID.randomUUID().toString(),
        /** Düzenlemede var olan kalemin kimliği (yeni satırda `null`). */
        val kalemId: String? = null,
        val katalogId: String? = null,
        val ad: String,
        val grup: String? = null,
        val disler: List<String> = emptyList(),
        val yarimCene: Int? = null,
        val cene: String? = null,
        /** Katalog satırında menü fiyatı (gösterim); manuel satırda kullanıcının fiyatı. */
        val fiyat: Double,
        val para: String = "TRY",
        val kur: String? = null,
        val adet: Int = 1,
        val indirim: Double = 0.0,
        val yuzdeMi: Boolean = false,
        val hekimId: String? = null,
        val manuel: Boolean = false,
        val asama: String = "PLANNED",
        /** Düzenlemede değişti mi (yalnız değişen satır `degisen`e girer). */
        val degisti: Boolean = false,
    ) {
        val toplu: Boolean get() = disler.size >= 2
        val yapilan: Boolean get() = asama == "DONE"

        val konumEtiketi: String
            get() {
                if (disler.isNotEmpty()) return disler.joinToString(", ")
                val q = yarimCene
                if (q != null && q in 1..4) return DisSemasi.kadranAdlari[q - 1]
                if (cene == "UPPER") return "Üst çene"
                if (cene == "LOWER") return "Alt çene"
                return ""
            }

        val indirimSatiri: IslemKurallari.Satir
            get() = IslemKurallari.Satir(
                fiyat = fiyat, adet = adet, kur = kur?.toDoubleOrNull(), indirim = indirim, yuzdeMi = yuzdeMi, yapilan = yapilan,
            )
    }

    /** Seçim kipi: 2+ diş için web `SaveModeDialog` — "TEKER TEKER KAYDET" (diş başına satır) · "TOPLU KAYDET" (tek satır, `toothCodes`). */
    enum class DisKipi { teker, toplu }

    /**
     * Bir tedavi seçimi → satırlar (web kuralı): konumsuz → 1 satır; her çene/yarım çene ayrı satır; 1 diş → 1 satır;
     * 2+ diş → kipe göre diş başına ya da tek toplu satır.
     */
    fun satirlar(
        katalog: KatalogKalemi?,
        ad: String,
        fiyat: Double,
        disler: List<String>,
        ceneler: List<String>,
        yarimCeneler: List<Int>,
        kip: DisKipi,
        hekimId: String?,
        para: String = "TRY",
        kur: String? = null,
    ): List<Satir> {
        fun yeni() = Satir(
            katalogId = katalog?.id, ad = ad, grup = katalog?.group, fiyat = fiyat, para = para, kur = kur, hekimId = hekimId,
            manuel = katalog == null,
        )
        val out = mutableListOf<Satir>()
        if (disler.size == 1) out += yeni().copy(disler = disler)
        if (disler.size >= 2) {
            if (kip == DisKipi.toplu) out += yeni().copy(disler = disler)
            else out += disler.map { d -> yeni().copy(disler = listOf(d)) }
        }
        out += ceneler.map { c -> yeni().copy(cene = c) }
        out += yarimCeneler.map { q -> yeni().copy(yarimCene = q) }
        if (out.isEmpty()) out += yeni()
        return out
    }

    /** Var olan kalemden düzenleme satırı. */
    fun satir(k: TedaviKalemi): Satir {
        val disler = DisSemasi.kalemDisleri(k)
        val adet = if (k.grupMu) 1 else max(1, k.quantity ?: 1)
        val birim = if (k.grupMu) {
            ((PlanTutari.satirTutari(k) ?: 0.0) + PlanTutari.satirIndirimi(k)) / max(1, disler.size).toDouble()
        } else {
            k.price?.deger ?: 0.0
        }
        return Satir(
            kalemId = k.id, katalogId = k.catalogServiceId, ad = k.name, grup = null, disler = disler,
            yarimCene = if (disler.isEmpty()) k.quadrant else null, cene = if (disler.isEmpty()) k.jawType?.uppercase() else null,
            fiyat = birim, para = k.currency ?: "TRY", kur = k.exchangeRate?.deger?.toString(), adet = adet,
            indirim = PlanTutari.satirIndirimi(k), hekimId = k.assignedDoctorId ?: k.assignedDoctor?.id, asama = k.stage ?: "PLANNED",
        )
    }

    // MARK: İstek gövdeleri (Swift `Govde.nesne` → `govde`: `null` alan GİTMEZ, `JsonNull` açık `null` gider)

    /** Konum alanları (web: tek diş `tooth`, 2+ diş `toothCodes`+`tooth:null`, çene/yarım çene). */
    private fun konum(s: Satir): List<Pair<String, Any?>> = listOf(
        "tooth" to (if (s.disler.size == 1) s.disler[0] else JsonNull),
        "toothCodes" to (if (s.disler.size >= 2) s.disler else null),
        "quadrant" to (s.yarimCene ?: JsonNull),
        "jawType" to (s.cene ?: JsonNull),
    )

    /** Para alanları: TRY → `{currency:"TRY"}`; döviz → `{currency, exchangeRate: gösterilen kur}`. */
    private fun para(s: Satir): List<Pair<String, Any?>> =
        if (s.para == "TRY") listOf("currency" to "TRY") else listOf("currency" to s.para, "exchangeRate" to s.kur)

    private fun birlestir(vararg parcalar: List<Pair<String, Any?>>): JsonObject {
        val a = LinkedHashMap<String, Any?>()
        for (p in parcalar) for ((k, v) in p) a[k] = v
        return govde(*a.toList().toTypedArray())
    }

    /** `POST /treatment-plans/items` (sıkı şema). `indirim` = satır indirimi + toplu payı (`toplamIndirim`). */
    fun planKalemi(s: Satir, planId: String, toplamIndirim: Double): JsonObject = birlestir(
        listOf(
            "treatmentPlanId" to planId, "name" to s.ad, "catalogServiceId" to s.katalogId,
            "assignedDoctorId" to s.hekimId, "quantity" to (if (s.toplu) 1 else s.adet), "discount" to toplamIndirim,
            "stage" to "PLANNED", "price" to (if (s.manuel) s.fiyat else null),
        ),
        konum(s), para(s),
    )

    /** `POST /treatment-items` — doğrudan kalem (aşama listesi). */
    fun dogrudanKalem(s: Satir, hastaId: String, asama: String, tarih: String, toplamIndirim: Double): JsonObject = birlestir(
        listOf(
            "patientId" to hastaId, "stage" to asama, "name" to s.ad, "catalogServiceId" to (s.katalogId ?: JsonNull),
            "assignedDoctorId" to (s.hekimId ?: JsonNull), "quantity" to (if (s.toplu) 1 else s.adet),
            "discount" to toplamIndirim, "treatmentDate" to tarih, "price" to (if (s.manuel) s.fiyat else null),
        ),
        konum(s), para(s),
    )

    /** `PUT /:id/kalemler` `eklenen` satırı (plan/aşama alanları yok). */
    fun eklenen(s: Satir, toplamIndirim: Double): JsonObject = birlestir(
        listOf(
            "name" to s.ad, "catalogServiceId" to s.katalogId, "assignedDoctorId" to s.hekimId,
            "quantity" to (if (s.toplu) 1 else s.adet), "discount" to toplamIndirim, "price" to (if (s.manuel) s.fiyat else null),
        ),
        konum(s), para(s),
    )

    /** `degisen` satırı — yalnız düzenlenebilir alanlar (adet, indirim, hekim). Grup satırında adet/indirim gönderilmez (sunucu reddeder). */
    fun degisen(s: Satir, toplamIndirim: Double, grup: Boolean): JsonObject {
        val a = mutableListOf<Pair<String, Any?>>("id" to (s.kalemId ?: ""), "assignedDoctorId" to s.hekimId)
        if (!grup) {
            a += "quantity" to s.adet
            a += "discount" to toplamIndirim
        }
        return birlestir(a)
    }
}
