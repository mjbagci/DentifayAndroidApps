package com.dentifay.app.agent

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.core.DfyJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID

// MARK: - Lenient JSON value

/**
 * Ajanın SSE olaylarını ayrıştırmak için hoşgörülü JSON değeri; olay türlerine göre
 * şekil değiştiğinden alanlar savunmacı okunur (her varyant için katı model yok).
 */
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

data class AgentToolStep(
    val name: String,
    val summary: String,
    val ok: Boolean,
    val id: UUID = UUID.randomUUID(),
)

data class AgentExcelArtifact(
    val filename: String,
    val downloadUrl: String,
    val fileId: String?,
    val rowCount: Int?,
    val id: UUID = UUID.randomUUID(),
)

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

data class AgentWritePreview(
    val operation: String?,
    val entity: String?,
    val risk: String?,
    val affectedCount: Int?,
    val requiresDoubleConfirm: Boolean,
)

// MARK: - Disambiguation choice (patient_choices / entity_choices)

/**
 * Belirsizlik seçim kartı. Backend zengin alan gönderiyor (hasta: yaş/tc/telefon/adres,
 * varlık: detail/fiyat) — kullanıcı iki aynı-adı ayırt edebilsin diye adın altında
 * gri ikincil satır gösterilir.
 */
data class AgentChoice(
    val title: String,        // ad
    val subtitle: String?,    // "42 yaş · 0532… · TC 123…" / "500 TL" (gri)
    val sendText: String,     // kimlik yoksa (geriye uyum) modele gönderilecek metin
    /** Backend kayıt kimliği; varsa seçim `pick`/`patientPick` ile id olarak gönderilir. */
    val recordId: String?,
    /** Kaydın tablosu ("patients" / "services" …) — `pick.table` için. */
    val table: String?,
    /** Belirsizlik terimi (olayın üst düzey `term` alanı) — `pick.term` için. */
    val term: String?,
    val id: UUID = UUID.randomUUID(),   // ⚠️ LİSTE kimliği — veritabanı kimliği DEĞİL
) {
    companion object {
        /** SSE `choices` dizisindeki bir öğeyi ayrıştırır. */
        fun parse(item: Map<String, JSONValue>, table: String?, term: String?): AgentChoice? {
            val name = (item["name"]?.stringValue ?: item["label"]?.stringValue)?.trim()
            if (name.isNullOrEmpty()) return null

            val parts = mutableListOf<String>()
            var sendExtra: String? = null

            if (table == "patients") {
                item["age"]?.doubleValue?.let { age -> if (age > 0) parts += "${age.toInt()} yaş" }
                item["phone"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { phone ->
                    parts += phone; sendExtra = "telefon $phone"
                }
                (item["tc"]?.stringValue ?: item["nationalId"]?.stringValue)?.takeIf { it.isNotEmpty() }?.let { tc ->
                    parts += "TC $tc"
                    if (sendExtra == null) sendExtra = "TC $tc"
                }
                item["address"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { addr ->
                    if (parts.size < 3) parts += addr
                }
            } else {
                item["detail"]?.stringValue?.takeIf { it.isNotEmpty() }?.let { detail ->
                    parts += detail; sendExtra = detail
                }
            }

            val subtitle = if (parts.isEmpty()) null else parts.joinToString(" · ")
            val sendText = sendExtra?.let { "$name, $it" } ?: name
            val recordId = item["id"]?.stringValue   // veritabanı kimliği (varsa)
            return AgentChoice(name, subtitle, sendText, recordId, table, term)
        }

        /** Düz string dizisi geldiğinde (eski backend) sade kart. */
        fun plain(text: String): AgentChoice = AgentChoice(text, null, text, null, null, null)
    }
}

enum class AgentWriteResult(val rawValue: String) {
    committed("committed"), cancelled("cancelled"), denied("denied"), error("error"), rejected("rejected");

    val label: String
        get() = when (this) {
            committed -> "İşlem uygulandı"
            cancelled -> "İşlem iptal edildi"
            denied, rejected -> "Yetki reddedildi"
            error -> "İşlem başarısız"
        }

    companion object {
        fun from(raw: String): AgentWriteResult? = entries.firstOrNull { it.rawValue == raw }
    }
}

data class AgentMessage(
    val role: AgentRole,
    val content: String = "",
    val streaming: Boolean = false,
    val thinkingText: String? = null,
    val toolSteps: List<AgentToolStep> = emptyList(),
    val charts: List<AgentChartSpec> = emptyList(),
    val excelFiles: List<AgentExcelArtifact> = emptyList(),
    /**
     * Backend'in `speak` olaylarıyla hazırladığı temiz, kimliksiz doğal metin. Sesli okunan budur
     * (ayrıntılı ekran `content`i değil); yazma önizlemelerinde kısa başlık olarak da kullanılır.
     */
    val speakText: String? = null,
    val writePreview: AgentWritePreview? = null,
    val writeResult: AgentWriteResult? = null,
    val isError: Boolean = false,
    /** Tur kredi yüzünden (402) başlamadıysa — balonda kredi kartı çizilir. */
    val creditRequired: CreditRequired? = null,
    val id: UUID = UUID.randomUUID(),
)

// MARK: - Conversation thread summary

data class AgentConversation(
    val id: String,
    val title: String,
    val updatedAt: String?,
)

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentModelsPreview() {
    val ev = AgentEvent.parse("""{"type":"patient_choices","table":"patients","term":"ayşe","choices":[{"id":"p1","name":"Ayşe Yılmaz","age":42,"phone":"0532 000 00 00"}]}""")
    val choice = ev?.raw?.get("choices")?.arrayValue?.firstOrNull()?.objectValue?.let { AgentChoice.parse(it, ev?.string("table"), ev?.string("term")) }
    PreviewEnv {
        Text("${choice?.title} — ${choice?.subtitle}", Modifier.padding(16.dp), style = sys(15), color = DS.Colors.onSurface)
    }
}
