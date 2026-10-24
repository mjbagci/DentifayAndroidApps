package com.dentifay.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import java.util.UUID

/**
 * Boş sohbet durumunda kompozitin hemen üstünde yatay öneri rayı (44 dp).
 * Dikey 4-kart listesi yerine gelir — başparmağın doğal yayında, ekranın büyük kısmını yemez. İlk mesaj gönderilince
 * kaybolur. Geniş pencerede ray sohbet sütununa hizalanır (`modifier` ile).
 */
object SuggestionRail {
    @Immutable
    data class Item(val icon: String, val tint: Color, val text: String, val id: UUID = UUID.randomUUID())
}

@Composable
fun SuggestionRail(items: List<SuggestionRail.Item>, modifier: Modifier = Modifier, onTap: (SuggestionRail.Item) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(
                Modifier
                    .semantics { role = Role.Button }
                    .pressable { Haptics.light(); onTap(item) }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon(item.icon, size = 12.dp, tint = item.tint)
                Text(item.text, style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 200)
@Composable
private fun SuggestionRailPreview() {
    PreviewEnv {
        Box(Modifier.padding(top = 40.dp)) {
            SuggestionRail(AgentViewDefaults.oneriler(finansGorur = true)) {}
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 200)
@Composable
private fun SuggestionRailTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Box(Modifier.padding(top = 40.dp)) {
            SuggestionRail(AgentViewDefaults.oneriler(finansGorur = false), Modifier.icerikGenisligi(AgentViewDefaults.sohbetSutunu + 32.dp)) {}
        }
    }
}
