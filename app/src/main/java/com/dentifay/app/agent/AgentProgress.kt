@file:Suppress("ClassName", "EnumEntryName")

package com.dentifay.app.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv

/** Dört adımlı ilerleme rayı. Ray ASLA geri gitmez (`max(step, yeni)`). Sıra = `rawValue` (Swift `Comparable`). */
enum class ProgressStepKind(val rawValue: Int) {
    understanding(0), retrieving(1), computing(2), answering(3);
}

/**
 * Rayın anlık durumu (Swift `struct ProgressStep` + `inout`). Swift'teki gibi YERİNDE güncellenir
 * (`ProgressMapper.apply(p, s)`); alanlar Compose anlık durumudur, yerinde değişiklik ekranı tazeler.
 * Swift değer tipi olduğundan kopya gerekirse `copy()`.
 */
@Stable
class ProgressStep(
    step: ProgressStepKind = ProgressStepKind.understanding,
    liveText: String = "Sorunuz gönderildi",
    elapsedSeconds: Double = 0.0,
    nudged: Boolean = false,
) {
    var step by mutableStateOf(step)
    var liveText by mutableStateOf(liveText)
    var elapsedSeconds by mutableDoubleStateOf(elapsedSeconds)
    var nudged by mutableStateOf(nudged)

    val index: Int get() = step.rawValue

    fun copy(
        step: ProgressStepKind = this.step,
        liveText: String = this.liveText,
        elapsedSeconds: Double = this.elapsedSeconds,
        nudged: Boolean = this.nudged,
    ): ProgressStep = ProgressStep(step, liveText, elapsedSeconds, nudged)

    override fun equals(other: Any?): Boolean =
        other is ProgressStep && other.step == step && other.liveText == liveText &&
            other.elapsedSeconds == elapsedSeconds && other.nudged == nudged

    override fun hashCode(): Int = listOf(step, liveText, elapsedSeconds, nudged).hashCode()

    override fun toString(): String = "ProgressStep(step=$step, liveText=$liveText, elapsedSeconds=$elapsedSeconds, nudged=$nudged)"
}

/** AgentSessionManager'ın ilerleme rayına köprülediği ham olaylar (sohbet ve Tedavi Planı). */
sealed class AgentProgress {
    data class queued(val position: Int) : AgentProgress()

    /** `schema_link` / `examples` — yalnız "anlama" adımı; tablo adı TAŞINMAZ (M-E1.5.3). */
    data object retrieved : AgentProgress()

    data class reasoning(val text: String, val stage: String?) : AgentProgress()

    data class tool(val name: String, val summary: String, val ok: Boolean, val rowCount: Int?) : AgentProgress()
}

/**
 * İlerleme rayı YALNIZ sunucunun `thinking.stage`inden ilerler (sözleşme §3: `analiz · sorgu · islem · cevap`);
 * canlı metin sunucunun `text`idir — mobil metin eşlemez, kendi cümlesini kurmaz (M0-6).
 */
object ProgressMapper {

    /** `stage` → adım; tanınmayan/eksik stage adımı İLERLETMEZ. */
    fun adim(stage: String?): ProgressStepKind? = when (stage) {
        "analiz" -> ProgressStepKind.understanding
        "sorgu" -> ProgressStepKind.retrieving
        "islem" -> ProgressStepKind.computing
        "cevap" -> ProgressStepKind.answering
        else -> null
    }

    /**
     * step = max(step, yeni) — ray ASLA geri gitmez. `s` YERİNDE güncellenir (Swift `inout`) ve kolaylık için
     * aynı nesne döner.
     */
    fun apply(p: AgentProgress, s: ProgressStep): ProgressStep {
        fun advance(k: ProgressStepKind) {
            if (k.rawValue > s.step.rawValue) s.step = k
        }

        when (p) {
            is AgentProgress.queued -> {
                advance(ProgressStepKind.understanding)
                s.liveText = if (p.position > 0) "Sırada bekliyorsunuz — önünüzde ${p.position} kişi var" else "Bağlanıldı"
            }

            AgentProgress.retrieved -> advance(ProgressStepKind.understanding)

            is AgentProgress.reasoning -> {
                adim(p.stage)?.let { advance(it) }
                if (p.text.isNotEmpty()) s.liveText = p.text
            }

            is AgentProgress.tool -> {
                // `summary` AYRIŞTIRILMAZ — satır sayısı ayrı alandır (`rowCount`).
                when {
                    !p.ok -> s.liveText = if (p.name == "sql_write") "İşlem düzeltiliyor…" else "Sorgu düzeltiliyor…"   // Ek MO.19a/b
                    p.name == "excel_export" -> s.liveText = "Excel raporu hazırlandı"                                  // Ek MO.19c
                    p.rowCount != null -> s.liveText = "${p.rowCount} kayıt bulundu"                                     // Ek MO.19d
                }
            }
        }
        return s
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun AgentProgressPreview() {
    val s = remember {
        ProgressStep().also {
            ProgressMapper.apply(AgentProgress.reasoning("Kayıtlar okunuyor", "sorgu"), it)
            ProgressMapper.apply(AgentProgress.tool("sql_query", "", true, 12), it)
        }
    }
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Adım ${s.index + 1}/4", style = sys(13), color = DS.Colors.onSurfaceVariant)
            Text(s.liveText, style = sys(15), color = DS.Colors.onSurface)
        }
    }
}
