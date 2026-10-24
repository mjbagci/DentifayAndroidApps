@file:Suppress("ClassName")

package com.dentifay.app.voice

/** Dört adımlı ilerleme rayı. Ray ASLA geri gitmez (`max(step, yeni)`). */
enum class ProgressStepKind(val rawValue: Int) {
    understanding(0), retrieving(1), computing(2), answering(3);
}

data class ProgressStep(
    var step: ProgressStepKind = ProgressStepKind.understanding,
    var liveText: String = "Sorunuz gönderildi",
    var elapsedSeconds: Double = 0.0,
    var nudged: Boolean = false,
    var commitLocked: Boolean = false,      // stage:'commit' → iptal kilitli
) {
    val index: Int get() = step.rawValue
}

/** AgentSessionManager'ın sesli katmana köprülediği ham ilerleme olayları. */
sealed class VoiceProgress {
    data class queued(val position: Int) : VoiceProgress()
    data class retrieved(val tables: List<String>) : VoiceProgress()
    data class reasoning(val text: String, val stage: String?) : VoiceProgress()
    data class tool(val name: String, val summary: String, val ok: Boolean) : VoiceProgress()
    data class heartbeat(val elapsedMs: Int) : VoiceProgress()
}

object ProgressMapper {

    /** ⚠️ Eşleşmeyen tabloyu HİÇ GÖSTERME — ham tablo adı çirkin + bilgi sızıntısı. */
    private val tableLabels: Map<String, String> = mapOf(
        "appointments" to "randevular",
        "patients" to "hastalar",
        "payments" to "ödemeler",
        "treatments" to "tedaviler",
        "stock_items" to "stok",
        "invoices" to "faturalar",
        "doctors" to "hekimler",
        "branches" to "şubeler",
    )

    /** step = max(step, yeni) — ray ASLA geri gitmez. `s` yerinde güncellenir (Swift `inout`). */
    fun apply(p: VoiceProgress, s: ProgressStep) {
        fun advance(k: ProgressStepKind) { if (k.rawValue > s.step.rawValue) s.step = k }

        when (p) {
            is VoiceProgress.queued -> {
                advance(ProgressStepKind.understanding)
                s.liveText = if (p.position > 0) "Sırada bekliyorsunuz — önünüzde ${p.position} kişi var" else "Bağlanıldı"
            }

            is VoiceProgress.retrieved -> {
                advance(ProgressStepKind.understanding)
                val labels = p.tables.mapNotNull { tableLabels[it] }
                if (labels.isNotEmpty()) s.liveText = "İlgili kayıtlar hazırlanıyor — " + labels.joinToString(", ")
            }

            is VoiceProgress.reasoning -> when (p.stage) {
                "analyze" -> { advance(ProgressStepKind.understanding); s.liveText = p.text }
                "query" -> { advance(ProgressStepKind.retrieving); s.liveText = "Veritabanı sorgulanıyor…" }
                "refine" -> { advance(ProgressStepKind.computing); s.liveText = "Sonuçlar inceleniyor — 2. adım" }
                "write_intent_check" -> { advance(ProgressStepKind.computing); s.liveText = p.text }
                "commit" -> { advance(ProgressStepKind.computing); s.liveText = p.text; s.commitLocked = true }
                else -> when {
                    p.text.contains("Sorgu sürdürülüyor") -> {
                        advance(ProgressStepKind.computing); s.liveText = "Sonuçlar inceleniyor — 2. adım"
                    }
                    p.text.contains("İşlem yürütülüyor") -> {
                        advance(ProgressStepKind.computing); s.liveText = p.text; s.commitLocked = true
                    }
                    else -> { advance(ProgressStepKind.understanding); s.liveText = p.text }
                }
            }

            is VoiceProgress.tool -> {
                advance(ProgressStepKind.retrieving)
                val rows = p.summary.split(" ").firstOrNull { it.isNotEmpty() }
                when {
                    !p.ok -> s.liveText = if (p.name == "sql_write") "İşlem düzeltiliyor…" else "Sorgu düzeltiliyor…"
                    p.name == "excel_export" -> s.liveText = "Excel raporu hazırlandı"
                    p.summary.contains("paralel") -> {
                        val n = p.summary.takeWhile { it.isDigit() }
                        s.liveText = "${n.ifEmpty { "Birden çok" }} sorgu birlikte çalıştırıldı"
                    }
                    rows != null && rows.toIntOrNull() != null -> s.liveText = "$rows kayıt bulundu"   // ⚠️ ms GÖSTERME
                }
            }

            is VoiceProgress.heartbeat -> Unit                 // yalnız izleyici sıfırlar
        }
    }
}
