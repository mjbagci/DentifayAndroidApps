package com.dentifay.app.xray

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.mutedHex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

// Röntgen analizi sonucu — `xray-service` (engine: hybrid) JSON'unun birebir karşılığı.
// Kutular `[x1, y1, x2, y2]`, ORİJİNAL görsel pikselinde; `image_size` = [genişlik, yükseklik].
// Not: servis EXIF yönünü uygulamaz → uygulama görseli daima dik yönde, JPEG olarak gönderir.

@Serializable
data class XrayResult(
    @SerialName("image_size") val imageSize: List<Double> = emptyList(),
    val teeth: Map<String, XrayTooth> = emptyMap(),
    @SerialName("anatomical_layer") val anatomicalLayer: List<XrayLayerItem>? = null,
    @SerialName("raw_detections") val rawDetections: List<XrayRawDetection>? = null,
    @SerialName("elapsed_ms") val elapsedMs: Double? = null,
    val model: String? = null,
    val mode: String? = null,
) {
    val width: Double get() = imageSize.firstOrNull() ?: 1.0
    val height: Double get() = if (imageSize.size > 1) imageSize[1] else 1.0
}

@Serializable
data class XrayTooth(
    val fdi: Int,
    val box: List<Double>,
    @SerialName("fdi_conf") val fdiConf: Double? = null,
    @SerialName("high_recs") val highRecs: List<XrayRec>? = null,
    @SerialName("mid_recs") val midRecs: List<XrayRec>? = null,
    @SerialName("existing_treatments") val existingTreatments: List<XrayMark>? = null,
    val observations: List<XrayMark>? = null,
    val status: String? = null,
    @SerialName("gap_inferred") val gapInferred: Boolean? = null,
    @SerialName("missing_synthetic") val missingSynthetic: Boolean? = null,
)

/** Tedavi önerisi (sunucunun kural çarpanlı olasılığıyla). */
@Serializable
data class XrayRec(
    @SerialName("class") val cls: String,
    @SerialName("label_full") val labelFull: String? = null,
    val probability: Double,
    val color: String? = null,
    val confidence: String? = null,
    @SerialName("clinical_reason") val clinicalReason: String? = null,
    @SerialName("v2_class_raw") val rawClass: String? = null,
) {
    /** Ekranda gösterilecek ad — sunucunun kısa `label`ı ilk kelimeye kırpılmış olduğu için kullanılmaz. */
    val title: String get() = XrayCatalog.label(cls, labelFull)
}

/** Mevcut restorasyon / gözlem. */
@Serializable
data class XrayMark(
    @SerialName("class") val cls: String,
    @SerialName("label_full") val labelFull: String? = null,
    val probability: Double,
    val color: String? = null,
) {
    val title: String get() = XrayCatalog.label(cls, labelFull)
}

@Serializable
data class XrayLayerItem(
    @SerialName("class") val cls: String,
    @SerialName("label_full") val labelFull: String? = null,
    val probability: Double,
    val color: String? = null,
    val box: List<Double>,
)

@Serializable
data class XrayRawDetection(
    @SerialName("class_raw") val classRaw: String? = null,
    @SerialName("class") val cls: String,
    val probability: Double,
    val color: String? = null,
    val box: List<Double>,
    val kind: String? = null,
)

// MARK: - Katalog (web `XrayStudio.jsx` + `tr.json → clinical.xray.*` ile aynı)

object XrayCatalog {
    val labels: Map<String, String> = mapOf(
        "DOLGU" to "Dolgu", "KANAL_TEDAVISI" to "Kanal Tedavisi", "POST_CORE" to "Post & Core",
        "CEKIM" to "Diş Çekimi", "CEKIM_20YAS" to "20 Yaş Çekimi", "IMPLANT" to "İmplant",
        "KURON_KAPLAMA" to "Kuron / Kaplama", "PERIODONTAL" to "Periodontal", "ORTODONTI" to "Ortodontik",
        "EXISTING_KURON" to "Mevcut Kuron", "EXISTING_DOLGU" to "Mevcut Dolgu", "EXISTING_KANAL" to "Mevcut Kanal",
        "EXISTING_POST_CORE" to "Mevcut Post & Core", "EXISTING_IMPLANT" to "Mevcut İmplant",
        "EXISTING_ABUTMENT" to "Mevcut Abutment", "EXISTING_BRACKETS" to "Mevcut Braket",
        "EXISTING_RETAINER" to "Mevcut Pekiştirici", "EXISTING_WIRE" to "Mevcut Tel", "EXISTING_BAND" to "Mevcut Metal Bant",
        "EXISTING_PLATING" to "Mevcut Plak", "EXISTING_GINGIVAL_FORMER" to "Mevcut Gingival Former",
        "EXISTING_TAD" to "Mevcut Mini Vida (TAD)",
        "OBS_MALALIGNED" to "Hatalı Pozisyon", "OBS_SUPRA" to "Supra Erüpsiyon", "OBS_ATTRITION" to "Atrisyon (Aşınma)",
        "ANAT_MANDIBULAR_CANAL" to "Mandibular Kanal", "ANAT_MAXILLARY_SINUS" to "Maksiller Sinüs",
    )

    val colors: Map<String, String> = mapOf(
        "DOLGU" to "3b82f6", "KANAL_TEDAVISI" to "ef4444", "POST_CORE" to "a855f7", "CEKIM" to "dc2626",
        "CEKIM_20YAS" to "f59e0b", "IMPLANT" to "10b981", "KURON_KAPLAMA" to "06b6d4", "PERIODONTAL" to "dc2626",
        "ORTODONTI" to "ec4899",
        "EXISTING_KURON" to "06b6d4", "EXISTING_DOLGU" to "3b82f6", "EXISTING_KANAL" to "10b981",
        "EXISTING_POST_CORE" to "a855f7", "EXISTING_IMPLANT" to "059669", "EXISTING_ABUTMENT" to "0891b2",
        "EXISTING_BRACKETS" to "8b5cf6", "EXISTING_RETAINER" to "7c3aed", "EXISTING_WIRE" to "6366f1",
        "EXISTING_BAND" to "64748b", "EXISTING_PLATING" to "475569", "EXISTING_GINGIVAL_FORMER" to "0e7490",
        "EXISTING_TAD" to "374151",
        "OBS_MALALIGNED" to "eab308", "OBS_SUPRA" to "facc15", "OBS_ATTRITION" to "ca8a04",
        "ANAT_MANDIBULAR_CANAL" to "a78bfa", "ANAT_MAXILLARY_SINUS" to "c4b5fd",
    )

    /** Tedavi önceliği (sunucu öneri planıyla aynı sıra: çekim → periodontal → kanal …). */
    val priority: Map<String, Int> = mapOf(
        "CEKIM" to 1, "CEKIM_20YAS" to 1, "PERIODONTAL" to 2, "KANAL_TEDAVISI" to 3, "POST_CORE" to 4,
        "DOLGU" to 5, "KURON_KAPLAMA" to 6, "IMPLANT" to 7, "ORTODONTI" to 8,
    )

    fun label(cls: String, fallback: String? = null): String = labels[cls] ?: fallback ?: cls

    fun color(cls: String, fallback: String? = null): Color {
        val h = colors[cls] ?: fallback
        if (h != null) return mutedHex(h)
        return hex("94a3b8")
    }

    /** Çeyrek adı (FDI ilk hanesi). */
    fun quadrant(fdi: Int): String = when (fdi / 10) {
        1, 5 -> "Sağ Üst"
        2, 6 -> "Sol Üst"
        3, 7 -> "Sol Alt"
        4, 8 -> "Sağ Alt"
        else -> ""
    }
}

/** Bulgu duyarlılığı (web: Yüksek 0.65 · Orta 0.50 · Düşük 0.35 · Tümü 0.20). */
enum class XraySensitivity(val rawValue: Double) {
    high(0.65), medium(0.50), low(0.35), all(0.20);

    val id: Double get() = rawValue
    val title: String
        get() = when (this) {
            high -> "Yüksek"
            medium -> "Orta"
            low -> "Düşük"
            all -> "Tümü"
        }
}

/** Ekranda çizilen tek diş — sunucu sonucundan, seçili duyarlılıkla türetilir. */
data class XrayToothFinding(
    val id: String,
    val fdi: Int,
    /** Görsele göre normalize dikdörtgen (0…1). */
    val rect: Rect,
    val recs: List<XrayRec>,
    val existing: List<XrayMark>,
    val observations: List<XrayMark>,
    val isSynthetic: Boolean,
) {
    enum class Kind { finding, existing, healthy, missing }

    val top: XrayRec? get() = recs.firstOrNull()
    val kind: Kind
        get() = when {
            recs.isNotEmpty() -> Kind.finding
            isSynthetic -> Kind.missing
            existing.isNotEmpty() -> Kind.existing
            else -> Kind.healthy
        }
    val isHighConfidence: Boolean get() = (top?.probability ?: 0.0) >= 0.65

    val color: Color
        get() {
            top?.let { return XrayCatalog.color(it.cls, it.color) }
            existing.firstOrNull()?.let { return XrayCatalog.color(it.cls, it.color) }
            return hex("94a3b8")
        }

    val quadrant: String get() = XrayCatalog.quadrant(fdi)

    /** Görseldeki kısa etiket: "Kanal 78%" / "Mevcut Kuron". */
    val badgeText: String?
        get() {
            top?.let { return "${XrayCatalog.label(it.cls, it.labelFull)} ${(it.probability * 100).roundToInt()}%" }
            if (isSynthetic) return "Eksik"
            return existing.firstOrNull()?.title
        }
}

/**
 * Duyarlılık eşiğine göre diş listesi (FDI sırasıyla). Öneriler sunucunun `high_recs` +
 * `mid_recs` alanlarından gelir (web'in `all_probs`tan yeniden kurma hatası tekrarlanmaz).
 */
fun XrayResult.findings(minProbability: Double): List<XrayToothFinding> {
    val w = max(width, 1.0)
    val h = max(height, 1.0)
    return teeth.mapNotNull { (key, t) ->
        if (t.box.size != 4) return@mapNotNull null
        val rect = Rect(
            left = (t.box[0] / w).toFloat(), top = (t.box[1] / h).toFloat(),
            right = ((t.box[0] + max(0.0, t.box[2] - t.box[0])) / w).toFloat(),
            bottom = ((t.box[1] + max(0.0, t.box[3] - t.box[1])) / h).toFloat(),
        )
        val seen = HashSet<String>()
        val recs = ((t.highRecs ?: emptyList()) + (t.midRecs ?: emptyList()))
            .filter { it.probability >= minProbability }
            .sortedByDescending { it.probability }
            .filter { seen.add(it.cls) }
        XrayToothFinding(
            id = key, fdi = t.fdi, rect = rect, recs = recs,
            existing = (t.existingTreatments ?: emptyList()).sortedByDescending { it.probability },
            observations = t.observations ?: emptyList(),
            isSynthetic = t.gapInferred == true || t.missingSynthetic == true || key.startsWith("gap_") || key.startsWith("miss_"),
        )
    }.sortedWith(compareBy<XrayToothFinding> { it.fdi }.thenBy { it.id })
}

/** Anatomik yapılar (normalize dikdörtgen). */
fun XrayResult.anatomy(): List<Pair<XrayLayerItem, Rect>> {
    val w = max(width, 1.0)
    val h = max(height, 1.0)
    return (anatomicalLayer ?: emptyList()).mapNotNull { a ->
        if (a.box.size != 4) return@mapNotNull null
        a to Rect(
            (a.box[0] / w).toFloat(), (a.box[1] / h).toFloat(),
            (a.box[2] / w).toFloat(), (a.box[3] / h).toFloat(),
        )
    }
}
