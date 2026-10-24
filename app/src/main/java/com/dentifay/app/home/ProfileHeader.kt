package com.dentifay.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.PingDot
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Üst başlık — web üst çubuğunun mobil karşılığı: avatar (baş harfler) · ad · klinik ·
 * bugünün tarihi ve bildirim düğmesi.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ProfileHeader(
    doctorName: String,
    clinicName: String,
    showNotificationBadge: Boolean,
    onNotificationTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val initials = doctorName.split(" ").filter { it.isNotEmpty() }.take(2)
        .mapNotNull { it.firstOrNull()?.toString() }.joinToString("").upperTr()
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy EEEE", Locale.forLanguageTag("tr-TR")))

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(relativeLinearGradient(listOf(0f to DS.Colors.purple500, 1f to DS.Colors.brandBlue), Offset(0f, 0f), Offset(1f, 1f)))
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials.ifEmpty { "D" }, style = sys(15, FontWeight.Bold), color = Color.White)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                Unvan.dt(doctorName), style = sys(16, FontWeight.SemiBold), color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DfyIcon("clock", size = 10.dp, tint = DS.Colors.onSurfaceVariant)
                Text(today, style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Box(
            Modifier
                .size(40.dp)
                .semantics { contentDescription = "Bildirimler" }
                .plainClickable { Haptics.light(); onNotificationTap() },
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.04f), CircleShape)
                    .border(1.dp, DS.Colors.hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DfyIcon("bell", size = 17.dp, tint = DS.Colors.metal)
            }
            if (showNotificationBadge) {
                PingDot(
                    color = DS.Colors.purple500, size = 8.dp,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun ProfileHeaderPreview() {
    PreviewEnv {
        ProfileHeader(
            doctorName = "Dt. Ayşe Demir", clinicName = "Dentifay", showNotificationBadge = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
