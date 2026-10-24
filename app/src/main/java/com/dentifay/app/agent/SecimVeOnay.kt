@file:Suppress("ClassName", "EnumEntryName", "ConstPropertyName")

package com.dentifay.app.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.TL
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

// MARK: - Seçim kartları, "Listede yok" ve Türkçe onay (MOBIL_IS_EMRI M-E1.4)
//
// Kart yapısal bir olaydan doğar (`patient_choices` / `entity_choices`); dokunuş `pick` ile id taşır.
// Kimliği olmayan öğe DOKUNULAMAZ — hastanın telefonu ya da TC'si mesaja yazılamaz.

/** `entity_choices` / `patient_choices` olayının tamamı (sözleşme §5) — mesaja bağlanır. */
@Immutable
data class AgentChoiceSet(
    val table: String?,
    val term: String?,
    val priceListId: String?,
    val elleSecim: Boolean,
    val choices: List<AgentChoice>,
) {
    /** Pencere kimliği — veritabanı kimliği DEĞİL. */
    val id: String get() = "${table ?: ""}|${term ?: ""}|${priceListId ?: ""}"

    /** 0 adaylı kart da `elleSecim` ise çizilir (26D.7). */
    val cizilir: Boolean get() = choices.isNotEmpty() || elleSecim

    /** "Listede yok, kendim seçeceğim" (Ek O.26D-4) yalnız tedavi menüsü kartında. */
    val listedeYokVar: Boolean get() = table == "service_catalog" && elleSecim && priceListId != null

    companion object {
        fun parse(raw: Map<String, JSONValue>): AgentChoiceSet {
            val table = raw["table"]?.stringValue
            val term = raw["term"]?.stringValue
            val choices = (raw["choices"]?.arrayValue ?: emptyList()).mapNotNull { item ->
                item.objectValue?.let { AgentChoice.parse(it, table, term) }
            }
            return AgentChoiceSet(
                table = table,
                term = term,
                priceListId = raw["priceListId"]?.stringValue,
                elleSecim = raw["elleSecim"]?.boolValue ?: false,
                choices = choices,
            )
        }

        /** Ek MO.10b — 0 adaylı kartın metni. */
        const val bosKartMetni = "Bu tedavi menüde bulunamadı. Menüden seçebilir ya da isteğinizi yeniden yazabilirsiniz."
        /** Ek MO.10a — sunucunun sorusu yoksa. */
        const val soruYedegi = "Birini seçin"
        /** Ek MO.10c — kimliksiz öğe. */
        const val kimliksizMetni = "Bu seçenek seçilemiyor; isteğinizi yeniden yazın."
        /** Ek O.26D-4 */
        const val listedeYokMetni = "Listede yok, kendim seçeceğim"
    }
}

// MARK: - "Tedavi menüsünden seçin" (`GET /api/price-lists/:id/secim?q=`)

@Immutable
@Serializable
data class MenuSecimYaniti(
    val items: List<Oge> = emptyList(),
    val truncated: Boolean? = null,
) {
    @Immutable
    @Serializable
    data class Oge(
        val id: String,
        val name: String,
        val grup: String? = null,
        val price: Double? = null,
        val currency: String? = null,
        val priceReason: String? = null,
    ) {
        /** Satırın fiyat metni — TRY → `tr_TR` TL; döviz → "200,00 USD"; `priceReason` varsa Ek O.26D-5 fiyatsız satırı. */
        val fiyatMetni: String
            get() {
                if (priceReason != null) return fiyatsizMetni
                val p = price ?: return fiyatsizMetni
                val cur = (currency ?: "TRY").uppercase()
                if (cur == "TRY") return TL.bicim(p)
                val f = ondalik.get() ?: return "$p $cur"
                return "${f.format(p)} $cur"
            }
    }

    companion object {
        // Ek O.26D-5
        const val baslik = "Tedavi menüsünden seçin"
        const val aramaIpucu = "Tedavi ara"
        const val bosMetni = "Aramaya uyan tedavi yok."
        const val hataMetni = "Tedavi menüsü yüklenemedi."
        const val kirpmaMetni = "İlk 200 tedavi gösteriliyor; aramayı daraltın."
        const val fiyatsizMetni = "Fiyat şu an hesaplanamıyor"

        /** `tr_TR`, iki ondalık — biçimleyici bir kez kurulur (iş parçacığı başına). */
        private val ondalik: ThreadLocal<NumberFormat> = ThreadLocal.withInitial {
            NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR")).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
        }

        /** Menüden seçim — kart dokunuşuyla AYNI yol: `pick{table:"service_catalog", term:<olayın term'i>, id, name}`. */
        fun pick(oge: Oge, kart: AgentChoiceSet): AgentClient.PickPayload {
            val term = kart.term?.takeIf { it.isNotEmpty() } ?: oge.name
            return AgentClient.PickPayload(table = "service_catalog", term = term, id = oge.id, name = oge.name)
        }
    }
}

/**
 * "Listede yok" — varsayılan tedavi menüsünün düz listesi (sözleşme §5; arama ve TR katlama SUNUCUDA, ≤ 200).
 * Swift `APIClient.fetchMenuSecim(priceListId:q:)`.
 */
suspend fun ApiClient.fetchMenuSecim(priceListId: String, q: String): MenuSecimYaniti {
    val t = q.trim()
    return request(path = "/price-lists/$priceListId/secim", query = if (t.isEmpty()) emptyList() else listOf("q" to t))
}

// MARK: - Türkçe onay penceresi (sohbet kartı ve Tedavi Planı önizlemesi — tek kurucu)

@Immutable
data class Degisiklik(
    val kayit: String?,
    val alanAdi: String?,
    val eski: String?,
    val yeni: String?,
) {
    /** Ek MO.11c — "{kayit} · {alanAdi}: {eski} → {yeni}"; `kayit` yoksa önek yok, `eski` yoksa "{alanAdi}: {yeni}". */
    val satir: String
        get() {
            val onek = if (!kayit.isNullOrEmpty()) "$kayit · " else ""
            val alan = alanAdi ?: ""
            val y = yeni ?: ""
            if (!eski.isNullOrEmpty()) return "$onek$alan: $eski → $y"
            return "$onek$alan: $y"
        }

    companion object {
        fun parse(raw: Map<String, JSONValue>): Degisiklik = Degisiklik(
            kayit = raw["kayit"]?.stringValue,
            alanAdi = raw["alanAdi"]?.stringValue ?: raw["alan"]?.stringValue,
            eski = raw["eski"]?.stringValue,
            yeni = raw["yeni"]?.stringValue,
        )
    }
}

@Immutable
data class OnayBilgisi(
    val baslik: String,
    val ozet: String?,
    val satirlar: List<String>,
    val kalanSatiri: String?,
    val klinik: String?,
) {
    companion object {
        /** Ham `operation` / `entity` HİÇBİR dalda gösterilmez; etiket yoksa Ek MO.11a. */
        fun kur(p: AgentWritePreview): OnayBilgisi {
            val parcalar = mutableListOf<String>()
            p.operationLabel?.takeIf { it.isNotEmpty() }?.let { parcalar += it }
            parcalar += p.entityLabel?.takeIf { it.isNotEmpty() } ?: "Kayıt"
            p.affectedCount?.let { parcalar += "$it kayıt" }
            val kalan = p.changesKalan?.takeIf { it > 0 }?.let { "$it değişiklik daha" }
            return OnayBilgisi(
                baslik = parcalar.joinToString(" · "),
                ozet = p.ozet,
                satirlar = p.changes.map { it.satir },
                kalanSatiri = kalan,
                klinik = p.klinikAdi?.let { "Klinik: $it" },
            )
        }
    }
}

/** Deterministik onay (27C.2): bugünkü metin AYNEN + gövdede `onay {islem, onizlemeId}`. */
object OnayIstegi {
    enum class Islem(val rawValue: String) { onayla("onayla"), vazgec("vazgec") }

    /** Swift'in `(metin:, onay:)` demeti. */
    data class Istek(val metin: String, val onay: AgentClient.OnayPayload?)

    /** Ek O.27C-4 düğme metni — süresi geçmiş önizlemede onay gönderilmez. */
    const val suresiDolduDugmesi = "Süresi doldu — isteği yeniden gönderin"

    /** Önizlemenin geçerlilik süresi geçti mi (cihaz saatiyle; karar sunucunundur). */
    fun suresiDoldu(p: AgentWritePreview?, simdi: Instant = Instant.now()): Boolean {
        val son = p?.gecerlilikSonu ?: return false
        return !son.isAfter(simdi)
    }

    /**
     * İstek: metin + (varsa) `onay` alanı. Süresi dolmuş önizlemede ONAY isteği ÜRETİLMEZ (`null`).
     * `onizlemeId` ya da `conversationId` yoksa `onay` alanı gönderilmez (sunucu `conversationId`siz `onay`ı 400 yapar).
     */
    fun kur(
        islem: Islem,
        preview: AgentWritePreview?,
        cift: Boolean,
        conversationId: String?,
        simdi: Instant = Instant.now(),
    ): Istek? {
        if (islem == Islem.onayla && suresiDoldu(preview, simdi)) return null
        val metin = when (islem) {
            Islem.onayla -> if (cift) "Evet, kalıcı olarak onaylıyorum." else "Onayla"
            Islem.vazgec -> "Vazgeç"
        }
        val id = preview?.onizlemeId
        if (id.isNullOrEmpty() || conversationId == null) return Istek(metin, null)
        return Istek(metin, AgentClient.OnayPayload(islem = islem.rawValue, onizlemeId = id))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 400)
@Composable
private fun SecimVeOnayPreview() {
    val p = AgentWritePreview(
        operation = "update", entity = "treatment_items", risk = "low", affectedCount = 1, requiresDoubleConfirm = false,
        ozet = "Deneme Hasta için 36 numaralı dişteki Kompozit Dolgu yapılanlara aktarılacak. Onaylıyor musunuz?",
        klinikAdi = "Deneme Klinik", entityLabel = "Tedavi kalemi", operationLabel = "Yapılana aktarma",
        changes = listOf(
            Degisiklik("Kompozit Dolgu (36)", "Aşama", "Planlandı", "Yapıldı"),
            Degisiklik("Kompozit Dolgu (36)", "Hekim", null, "Dt. A"),
        ),
        changesKalan = 2,
    )
    val b = OnayBilgisi.kur(p)
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(b.baslik, style = sys(14), color = DS.Colors.onSurface)
            b.satirlar.forEach { Text(it, style = sys(13), color = DS.Colors.onSurfaceVariant) }
            b.kalanSatiri?.let { Text(it, style = sys(12), color = DS.Colors.outline) }
            b.klinik?.let { Text(it, style = sys(12), color = DS.Colors.outline) }
            Text(MenuSecimYaniti.Oge("s5", "Ozon Tedavisi", price = 200.0, currency = "USD").fiyatMetni, style = sys(13), color = DS.Colors.onSurface)
        }
    }
}
