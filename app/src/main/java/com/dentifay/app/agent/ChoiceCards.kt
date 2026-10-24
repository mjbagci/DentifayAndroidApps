package com.dentifay.app.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv

/**
 * Belirsizlik seçim kartları — `patient_choices` / `entity_choices`.
 *
 * TEK BİLEŞEN, İKİ EKRAN (sohbet · Tedavi Planı): kartın gövdesi iki yerde kopyalansaydı ikisi ayrışırdı.
 * ⚠️ Kart YAPISAL bir olaydan doğar (backend `choices[]`), serbest metinden DEĞİL. Dokunuş `pick` ile id taşır → model
 *    aynı soruyu tekrar sormaz.
 * ⚠️ Ham kimlik EKRANA BASILMAZ; `AgentChoice.recordId` yalnız yükte taşınır.
 */
@Composable
fun ChoiceCards(
    title: String,
    choices: List<AgentChoice>,
    /** Kuyruğa alınmış seçim (akış sürerken tıklandı) — vurgulanır, diğerleri söner. */
    pending: AgentChoice? = null,
    modifier: Modifier = Modifier,
    onSelect: (AgentChoice) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (pending == null) title else "SEÇİLDİ — yanıt bitince gönderilecek",
            style = sys(11, FontWeight.Bold, tracking = 1),
            color = if (pending == null) DS.Colors.onSurfaceVariant else DS.Colors.cyan400,
        )
        choices.forEach { opt ->
            key(opt.id) {
                val secilebilir = opt.pick != null
                val opaklik = when {
                    !secilebilir -> 0.45f
                    pending == null || pending == opt -> 1f
                    else -> 0.35f
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(opaklik)
                        .semantics(mergeDescendants = true) {
                            contentDescription = opt.subtitle?.let { "${opt.title}, $it" } ?: opt.title
                            role = Role.Button
                        }
                        .pressable(enabled = pending == null && secilebilir) { onSelect(opt) }
                        .glassCard(14.dp)
                        // HIG/Material: dokunma hedefi ≥ 44 dp.
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(opt.title, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
                        opt.subtitle?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant) }
                        if (!secilebilir) {
                            Text(AgentChoiceSet.kimliksizMetni, style = sys(11), color = DS.Colors.outline)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    DfyIcon(
                        if (pending == opt) "checkmark.circle.fill" else "arrow.up.left.circle.fill",
                        size = 17.dp, tint = DS.Colors.cyan400,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 500)
@Composable
private fun ChoiceCardsPreview() {
    val secimler = listOf(
        AgentChoice("Ayşe Yılmaz", "42 yaş · 0532 000 00 00", "p1", "patients", "ayşe"),
        AgentChoice("Ayşe Yılmaz", "35 yaş · TC 123…", "p2", "patients", "ayşe"),
        AgentChoice("Ayşe Kaya", null, null, "patients", "ayşe"),
    )
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ChoiceCards("SEÇENEKLER", secimler, onSelect = {})
            ChoiceCards("SEÇENEKLER", secimler, pending = secimler[1], onSelect = {})
        }
    }
}
