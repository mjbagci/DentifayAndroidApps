package com.dentifay.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.notifications.ZilDugmesi
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.LocalAyarlariAc
import com.dentifay.app.ui.PreviewEnv
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TARIH_BICIMI: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy EEEE", Locale.forLanguageTag("tr-TR"))
private val AVATAR_GRADYANI = relativeLinearGradient(listOf(0f to DS.Colors.purple500, 1f to DS.Colors.brandBlue), Offset(0f, 0f), Offset(1f, 1f))

/**
 * Üst başlık — web üst çubuğunun mobil karşılığı: avatar (baş harfler; dokununca Ayarlar — M-E8.1) · ad · bugünün tarihi
 * (klinik adı biliniyorsa yanında) ve bildirim düğmesi.
 *
 * @param doctorName oturum kişisinin gösterilecek adı (çağıran `Unvan.oturumAdi` ile verir).
 * @param clinicName klinik adı — BİLİNMİYORSA null/boş verilir ve gösterilmez (sabit marka adı yazılmaz).
 */
@Composable
fun ProfileHeader(
    doctorName: String,
    clinicName: String?,
    onNotificationTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ayarlariAc = LocalAyarlariAc.current
    val initials = remember(doctorName) {
        doctorName.split(" ").filter { it.isNotEmpty() }.take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").upperTr()
    }
    val today = LocalDate.now().format(TARIH_BICIMI)
    val clinicLine = if (clinicName.isNullOrEmpty()) today else "$today · $clinicName"

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Profil simgesi — Ayarlar buradan açılır ("Ayarlar" sekmesi kalktı).
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(AVATAR_GRADYANI)
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .plainClickable(enabled = ayarlariAc != null) {
                    Haptics.light()
                    ayarlariAc?.invoke()
                }
                .semantics {
                    contentDescription = "Ayarlar"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(initials.ifEmpty { "D" }, style = sys(15, FontWeight.Bold), color = Color.White, maxLines = 1)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                doctorName, style = sys(16, FontWeight.SemiBold), color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DfyIcon("clock", size = 10.dp, tint = DS.Colors.onSurfaceVariant)
                Text(
                    clinicLine, style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ZilDugmesi(action = onNotificationTap)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 200)
@Composable
private fun ProfileHeaderPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ProfileHeader(doctorName = "Dt. Ayşe Demir", clinicName = "Örnek Diş Kliniği")
            // Klinik adı yüklenemediyse satırda yalnız tarih (marka adı uydurulmaz).
            ProfileHeader(doctorName = "Deneme Kullanıcı", clinicName = null)
        }
    }
}
