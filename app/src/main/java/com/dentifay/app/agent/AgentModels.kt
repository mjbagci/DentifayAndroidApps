@file:Suppress("ClassName", "EnumEntryName")

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
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// MARK: - Lenient JSON value

/**
 * Ajanın SSE olaylarını ayrıştırmak için hoşgörülü JSON değeri; olay türlerine göre
 * şekil değiştiğinden alanlar savunmacı okunur (her varyant için katı model yok).
 */
@Immutable
sealed class JSONValue {
    data class string(val value: String) : JSONValue()
    data class number(val value: Double) : JSONValue()
    data class bool(val value: Boolean) : JSONValue()
    data class `object`(val value: Map<String, JSONValue>) : JSONValue()
    data class array(val value: List<JSONValue>) : JSONValue()
    data object `null` : JSONValue()

    val stringValue: String?
        get() = when (this) {
            is string -> value
            is number -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
            is bool -> if (value) "true" else "false"
            else -> null
        }

    val doubleValue: Double?
        get() = when (this) {
            is number -> value
            is string -> value.toDoubleOrNull()
            is bool -> if (value) 1.0 else 0.0
            else -> null
        }

    val boolValue: Boolean?
        get() = when (this) {
            is bool -> value
            is number -> value != 0.0
            is string -> value == "true"
            else -> null
        }

    val objectValue: Map<String, JSONValue>? get() = (this as? `object`)?.value
    val arrayValue: List<JSONValue>? get() = (this as? array)?.value

    /** kotlinx `JsonElement` biçimine çevirir — ortak çözücüler (`CreditRequired`) için (Swift `anyValue`). */
    val jsonElement: JsonElement
        get() = when (this) {
            is string -> JsonPrimitive(value)
            is number -> JsonPrimitive(value)
            is bool -> JsonPrimitive(value)
            is `object` -> JsonObject(value.mapValues { it.value.jsonElement })
            is array -> JsonArray(value.map { it.jsonElement })
            `null` -> JsonNull
        }

    companion object {
        /** Swift `Decodable` sırası: null → Bool → Double → String → nesne → dizi. */
        fun from(el: JsonElement): JSONValue = when (el) {
            is JsonNull -> `null`
            is JsonPrimitive -> when {
                el.isString -> string(el.content)
                el.booleanOrNull != null -> bool(el.booleanOrNull!!)
                el.doubleOrNull != null -> number(el.doubleOrNull!!)
                else -> string(el.content)
            }
            is JsonObject -> `object`(el.mapValues { from(it.value) })
            is JsonArray -> array(el.map { from(it) })
        }

        fun parse(text: String): JSONValue? = runCatching { from(DfyJson.parseToJsonElement(text)) }.getOrNull()
    }
}

// MARK: - Agent SSE event

/** POST /api/ai-assistant/chat akışındaki tek bir `data: {...}` SSE çerçevesi. */
class AgentEvent private constructor(val type: String, val raw: Map<String, JSONValue>) {

    fun string(key: String): String? = raw[key]?.stringValue
    fun int(key: String): Int? = raw[key]?.doubleValue?.toInt()
    fun bool(key: String): Boolean? = raw[key]?.boolValue
    fun `object`(key: String): Map<String, JSONValue>? = raw[key]?.objectValue

    companion object {
        /** Swift `AgentEvent(dict:)` — `type` yoksa null. */
        fun from(dict: Map<String, JSONValue>): AgentEvent? {
            val t = dict["type"]?.stringValue ?: return null
            return AgentEvent(t, dict)
        }

        fun parse(jsonLine: String): AgentEvent? {
            val dict = JSONValue.parse(jsonLine)?.objectValue ?: return null
            return from(dict)
        }
    }
}

// MARK: - Chat message model

enum class AgentRole { user, assistant }

@Immutable
data class AgentToolStep(
    val name: String,
    val summary: String,
    val ok: Boolean,
    val id: UUID = UUID.randomUUID(),
)

@Immutable
data class AgentExcelArtifact(
    val filename: String,
    val downloadUrl: String,
    val fileId: String?,
    val rowCount: Int?,
    /** Yeniden açılan sohbette dolar (web `rehydrateMessages` emsali) — süresi dolmuş dosya `POST /regenerate-excel` ile üretilir. */
    val messageId: String? = null,
    val artifactIndex: Int? = null,
    val id: UUID = UUID.randomUUID(),
) {
    companion object {
        /** Canlı akışın `tool_result.artifact`ı ve ayrıntının `artifacts[].data`sı için TEK kurucu. */
        fun from(art: Map<String, JSONValue>): AgentExcelArtifact? {
            val url = art["downloadUrl"]?.stringValue ?: ""
            if (url.isEmpty()) return null
            return AgentExcelArtifact(
                filename = art["filename"]?.stringValue ?: "rapor.xlsx",
                downloadUrl = url,
                fileId = art["fileId"]?.stringValue,
                rowCount = art["rowCount"]?.doubleValue?.toInt(),
            )
        }
    }
}

@Immutable
data class AgentChartSpec(
    val type: String,          // bar | line | pie
    val title: String?,
    val xKey: String?,
    val nameKey: String?,
    val valueKey: String?,
    val valueFormat: String?,  // currency | percent | null
    val series: List<Series>,
    val rows: List<Map<String, JSONValue>>,
    val id: UUID = UUID.randomUUID(),
) {
    /** Swift `(key:, label:)` demeti. */
    data class Series(val key: String, val label: String)

    companion object {
        fun from(obj: Map<String, JSONValue>): AgentChartSpec? {
            // Spec nesnenin kendisi ya da "spec" altında olabilir.
            val spec = obj["spec"]?.objectValue ?: obj
            val type = spec["type"]?.stringValue ?: return null
            val seriesArr = (spec["series"]?.arrayValue ?: emptyList()).mapNotNull { s ->
                val so = s.objectValue ?: return@mapNotNull null
                val k = so["key"]?.stringValue ?: return@mapNotNull null
                Series(k, so["label"]?.stringValue ?: k)
            }
            val rows = (spec["rows"]?.arrayValue ?: emptyList()).mapNotNull { it.objectValue }
            return AgentChartSpec(
                type = type,
                title = spec["title"]?.stringValue,
                xKey = spec["xKey"]?.stringValue,
                nameKey = spec["nameKey"]?.stringValue,
                valueKey = spec["valueKey"]?.stringValue,
                valueFormat = spec["valueFormat"]?.stringValue,
                series = seriesArr,
                rows = rows,
            )
        }
    }
}

// MARK: - Yazma önizlemesi (write_preview)

/**
 * Bir önizleme alanının değeri. `raw` yapısal değerdir (id de olabilir); `label` backend'in çözdüğü İNSAN-OKUR addır.
 * 🔴 EKRANA `text` BASILIR, `raw` DEĞİL: ham cuid gösteren bir onay ekranı onay kapısını KÖRLEŞTİRİR
 * (backend'in `write-labels.js` kuralının ikizi).
 */
@Immutable
data class AgentFieldValue(val raw: JSONValue?, val label: String?) {
    val text: String?
        get() {
            if (!label.isNullOrEmpty()) return label
            val s = raw?.stringValue
            if (s.isNullOrEmpty()) return null
            return if (looksLikeId(s)) "(ilgili kayıt)" else s
        }

    val number: Double? get() = raw?.doubleValue

    companion object {
        /** cuid/uuid biçimi (backend `write-preview-text.ID_TOKEN_RE` ile aynı ölçüt). */
        fun looksLikeId(s: String): Boolean {
            if (s.length >= 10 && s.startsWith("c") && s.drop(1).all { it.isLowerCase() || it.isDigit() } && s.any { it.isDigit() }) {
                return true
            }
            // Swift `split(separator:)` boş parçaları atar.
            val uuid = s.split("-").filter { it.isNotEmpty() }.map { it.length }
            return uuid == listOf(8, 4, 4, 4, 12)
        }
    }
}

/**
 * COMMIT edilecek TEK bir kayıt (backend `write_preview.steps[]`). Alan YOKSA (eski sunucu) liste boş kalır —
 * istemci metin özetine düşer. Konum/hekim/fiyat etiketleri `Onizleme.kt`teki uzantılardadır.
 */
@Immutable
data class AgentWriteStep(
    val index: Int,
    val entity: String,          // "treatment_plans" | "treatment_items" | …
    val operation: String,       // "insert" | "update"
    val title: String,
    val linkedToStep: Int?,
    val affectedCount: Int?,
    val fields: Map<String, AgentFieldValue>,
    /** Kalemin COMMIT'te yazılacak TL tutarı (sunucu; kur yoksa `null` — mobil toplamaz, çarpmaz). */
    val tutarTL: Double? = null,
    /** Çok dişli kalemin dişleri (sunucu). */
    val toothCodes: List<String> = emptyList(),
    val id: UUID = UUID.randomUUID(),
) {
    // Kolaylık erişimleri (tedavi kalemi kartı bunları çizer).
    val tooth: String? get() = fields["tooth"]?.text
    val quantity: Int get() = fields["quantity"]?.number?.toInt() ?: 1
    val name: String? get() = fields["name"]?.text ?: fields["title"]?.text
    val catalogLabel: String? get() = fields["catalogServiceId"]?.label

    companion object {
        fun parse(item: Map<String, JSONValue>): AgentWriteStep? {
            val entity = item["entity"]?.stringValue ?: return null
            val fields = LinkedHashMap<String, AgentFieldValue>()
            for ((key, value) in item["fields"]?.objectValue ?: emptyMap()) {
                val o = value.objectValue ?: continue
                fields[key] = AgentFieldValue(raw = o["raw"], label = o["label"]?.stringValue)
            }
            return AgentWriteStep(
                index = item["index"]?.doubleValue?.toInt() ?: 0,
                entity = entity,
                operation = item["operation"]?.stringValue ?: "insert",
                title = item["title"]?.stringValue ?: "",
                linkedToStep = item["linkedToStep"]?.doubleValue?.toInt(),
                affectedCount = item["affectedCount"]?.doubleValue?.toInt(),
                fields = fields,
                tutarTL = item["tutarTL"]?.doubleValue,
                toothCodes = (item["toothCodes"]?.arrayValue ?: emptyList()).mapNotNull { it.stringValue },
            )
        }
    }
}

@Immutable
data class AgentWritePreview(
    val operation: String?,
    val entity: String?,
    val risk: String?,
    val affectedCount: Int?,
    val requiresDoubleConfirm: Boolean,
    /** Yapısal kalem listesi (backend v14+). Eski sunucuda BOŞ. */
    val steps: List<AgentWriteStep> = emptyList(),
    /**
     * Önizlemenin tek cümlelik Türkçe özeti (`write_preview.ozet`, ≤ 280; fiyat kaymasında null).
     * Tedavi Planı bunu sesli okur (M-E1.1.4); sohbet onay kartının üst cümlesidir.
     */
    val ozet: String? = null,
    /** Tedavi kalemi eklemesinde COMMIT'in yazacağı TL toplamı (sunucu; bir adımda kur yoksa `null`). */
    val toplamTL: Double? = null,
    /** Çok klinikli kişide önizlemenin kliniği (`write_preview.klinik.ad`) — Ek MO.7f. */
    val klinikAdi: String? = null,
    /** Türkçe etiketler (27C.1) — onay başlığı bunlardan; ham `operation`/`entity` gösterilmez. */
    val entityLabel: String? = null,
    val operationLabel: String? = null,
    /** Güncelleme değişiklikleri (≤ 20) + gösterilmeyenlerin sayısı. */
    val changes: List<Degisiklik> = emptyList(),
    val changesKalan: Int? = null,
    /** Deterministik onay kimliği ve geçerlilik sonu (27C.2). */
    val onizlemeId: String? = null,
    val gecerlilikSonu: Instant? = null,
) {
    /**
     * Onay kapısı vaadi: COMMIT edilecek kayıt sayısı ile GÖSTERİLEN sayı ayrışıyorsa arayüz bunu AÇIKÇA yazar
     * (sessiz kırpma YASAK).
     */
    val hiddenStepCount: Int
        get() {
            val n = affectedCount ?: return 0
            if (steps.isEmpty() || n <= steps.size) return 0
            return n - steps.size
        }

    companion object {
        /**
         * `write_preview` olayı ve konuşma ayrıntısının `pendingWrite`ı AYNI biçimdedir (sözleşme §4) — tek ayrıştırıcı
         * (M-E1.1.5).
         */
        fun parse(raw: Map<String, JSONValue>): AgentWritePreview {
            val steps = (raw["steps"]?.arrayValue ?: emptyList()).mapNotNull { s -> s.objectValue?.let { AgentWriteStep.parse(it) } }
            val ozet = raw["ozet"]?.stringValue?.trim()
            return AgentWritePreview(
                operation = raw["operation"]?.stringValue,
                entity = raw["entity"]?.stringValue,
                risk = raw["risk"]?.stringValue,
                affectedCount = raw["affectedCount"]?.doubleValue?.toInt(),
                requiresDoubleConfirm = raw["requiresDoubleConfirm"]?.boolValue ?: false,
                steps = steps,
                ozet = ozet?.takeIf { it.isNotEmpty() },
                toplamTL = raw["toplamTL"]?.doubleValue,
                klinikAdi = raw["klinik"]?.objectValue?.get("ad")?.stringValue?.takeIf { it.isNotEmpty() },
                entityLabel = raw["entityLabel"]?.stringValue,
                operationLabel = raw["operationLabel"]?.stringValue,
                changes = (raw["changes"]?.arrayValue ?: emptyList()).mapNotNull { o -> o.objectValue?.let { Degisiklik.parse(it) } },
                changesKalan = raw["changesKalan"]?.doubleValue?.toInt(),
                onizlemeId = raw["onizlemeId"]?.stringValue,
                gecerlilikSonu = raw["gecerlilikSonu"]?.stringValue?.let { isoTarih(it) },
            )
        }
    }
}

// MARK: - Disambiguation choice (patient_choices / entity_choices)

/**
 * Belirsizlik seçim kartı. Backend zengin alan gönderiyor (hasta: yaş/tc/telefon/adres, varlık: `detail` — biçimi
 * sunucunun, ör. "1.800,00 TL") — kullanıcı iki aynı-adı ayırt edebilsin diye adın altında gri ikincil satır gösterilir.
 * Seçim YALNIZ `pick` ile gider (metin yolu YOK).
 */
@Immutable
data class AgentChoice(
    val title: String,        // ad
    val subtitle: String?,    // "42 yaş · 0532… · TC 123…" / sunucunun `detail`i (gri)
    /** Backend kayıt kimliği (item["id"]). YOKSA kart dokunulamaz (Ek MO.10c). */
    val recordId: String?,
    /** Kaydın tablosu — `pick.table` için. */
    val table: String?,
    /** Belirsizlik terimi (olayın üst düzey `term` alanı) — `pick.term` için. */
    val term: String?,
    val id: UUID = UUID.randomUUID(),   // ⚠️ LİSTE kimliği — veritabanı kimliği DEĞİL
) {
    /** Kartın `pick`i; kimlik ya da tablo yoksa `null` (dokunulamaz — telefon/TC mesaja YAZILMAZ). */
    val pick: AgentClient.PickPayload?
        get() {
            val kimlik = recordId?.takeIf { it.isNotEmpty() } ?: return null
            val tablo = table?.takeIf { it.isNotEmpty() } ?: return null
            val t = term?.takeIf { it.isNotEmpty() } ?: title
            return AgentClient.PickPayload(table = tablo, term = t, id = kimlik, name = title)
        }

    companion object {
        fun parse(item: Map<String, JSONValue>, table: String?, term: String?): AgentChoice? {
            val name = (item["name"]?.stringValue ?: item["label"]?.stringValue)?.trim()
            if (name.isNullOrEmpty()) return null

            val parts = mutableListOf<String>()
            if (table == "patients") {
                item["age"]?.doubleValue?.let { age -> if (age > 0) parts += "${age.toInt()} yaş" }
                item["phone"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { parts += it }
                item["tc"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { parts += "TC $it" }
                item["address"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { if (parts.size < 3) parts += it }
            } else {
                item["detail"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { parts += it }   // sunucunun biçimi AYNEN
            }
            return AgentChoice(
                title = name,
                subtitle = if (parts.isEmpty()) null else parts.joinToString(" · "),
                recordId = item["id"]?.stringValue,
                table = table,
                term = term,
            )
        }
    }
}

/**
 * Yazma sonucu etiketleri — Ek MO.17. `rejected` onay anındaki yeniden doğrulama reddidir (FK, menü, kur …), YETKİ DEĞİL.
 * Tanınmayan durum `null` (etiket çizilmez); cevap metni sunucunundur, `observation` OKUNMAZ.
 */
enum class AgentWriteResult(val rawValue: String) {
    committed("committed"), cancelled("cancelled"), denied("denied"), error("error"), rejected("rejected");

    val label: String
        get() = when (this) {
            committed -> "İşlem uygulandı"
            cancelled -> "İşlem iptal edildi"
            denied -> "Yetki reddedildi"
            rejected -> "İşlem doğrulanamadı; tekrar deneyin."
            error -> "İşlem başarısız"
        }

    companion object {
        /** Swift `AgentWriteResult(rawValue:)`. */
        fun from(raw: String): AgentWriteResult? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Immutable
data class AgentMessage(
    val role: AgentRole,
    val content: String = "",
    val streaming: Boolean = false,
    val thinkingText: String? = null,
    val toolSteps: List<AgentToolStep> = emptyList(),
    val charts: List<AgentChartSpec> = emptyList(),
    val excelFiles: List<AgentExcelArtifact> = emptyList(),
    val writePreview: AgentWritePreview? = null,
    val writeResult: AgentWriteResult? = null,
    val isError: Boolean = false,
    /**
     * Belirsizlik seçim kartı (olayın tamamı) — MESAJA bağlıdır, global state'e DEĞİL: aksi halde eski turun kartları
     * yeni turun altında asılı kalırdı.
     */
    val choiceSet: AgentChoiceSet? = null,
    /** `soft_fail` olayının kodu (varsa) — tur "yapamadım" ile bitti. */
    val softFailCode: String? = null,
    /** Tur kredi yüzünden (402) başlamadıysa — balonda kredi kartı çizilir. */
    val creditRequired: CreditRequired? = null,
    /** Akış AÇILMADAN dönen hatanın sunucu kodu (ör. `E_TARGET_PLAN_INVALID`) — ekrana basılmaz. */
    val errorCode: String? = null,
    /** Sunucudaki mesaj kimliği (yeniden açılan sohbette) — Excel yeniden üretimi için. */
    val serverId: String? = null,
    /** Silinmiş sohbet (O.27C-7): "Yeniden gönder" bu metni YENİ sohbette gönderir. */
    val yenidenGonderilecek: String? = null,
    val id: UUID = UUID.randomUUID(),
) {
    val choices: List<AgentChoice> get() = choiceSet?.choices ?: emptyList()
}

// MARK: - Conversation thread summary

@Immutable
data class AgentConversation(
    val id: String,
    val title: String,
    val lastMessageAt: Instant?,
    val createdAt: Instant?,
)

/** Sohbet geçmişi tarihi — Ek MO.18a: bugün "HH:mm", dün "Dün", bu yıl "17 Eki", önceki yıllar "17.10.2025". */
object SohbetTarihi {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val saat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", tr)
    private val gunAy: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", tr)
    private val tam: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", tr)

    /** `takvim` = Swift `Calendar`ın saat dilimi (java.time takvimi ISO'dur). */
    fun metin(d: Instant, simdi: Instant = Instant.now(), takvim: ZoneId = ZoneId.systemDefault()): String {
        val gun = d.atZone(takvim)
        val bugun: LocalDate = simdi.atZone(takvim).toLocalDate()
        if (gun.toLocalDate() == bugun) return saat.format(gun)
        if (gun.toLocalDate() == bugun.minusDays(1)) return "Dün"
        if (gun.year == bugun.year) return gunAy.format(gun)
        return tam.format(gun)
    }

    fun cozumle(s: String?): Instant? = s?.let { isoTarih(it) }
}

/** ISO 8601 (kesirli saniyeli ya da değil; `Z` ya da ±hh:mm) — Swift `ISO8601DateFormatter.withInternetDateTime`. */
internal fun isoTarih(s: String): Instant? =
    runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentModelsPreview() {
    val ev = AgentEvent.parse("""{"type":"patient_choices","table":"patients","term":"ayşe","choices":[{"id":"p1","name":"Ayşe Yılmaz","age":42,"phone":"0532 000 00 00"}]}""")
    val set = ev?.let { AgentChoiceSet.parse(it.raw) }
    val onizleme = AgentWritePreview.parse(
        JSONValue.parse("""{"operationLabel":"Ekleme","entityLabel":"Tedavi kalemi","affectedCount":2,"ozet":"Deneme Hasta için 2 tedavi eklenecek."}""")
            ?.objectValue ?: emptyMap(),
    )
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            set?.choices?.forEach { Text("${it.title} — ${it.subtitle}", style = sys(15), color = DS.Colors.onSurface) }
            Text(OnayBilgisi.kur(onizleme).baslik, style = sys(15), color = DS.Colors.onSurface)
            Text(AgentWriteResult.rejected.label, style = sys(13), color = DS.Colors.onSurfaceVariant)
        }
    }
}
