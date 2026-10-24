package com.dentifay.app.hasta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.HakedisEtkisi
import com.dentifay.app.core.HakedisOnayi
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv

/**
 * "Hakediş değişecek" — web `HakedisEtkisiPenceresi` (DentAIWeb @ 33c26896; M-E8.10). Satırlar sunucunun `metin`/`ekMetin`i, genel uyarı
 * `uyari` (yetkisiz kişide yalnız bu cümle). "İptal" hiçbir şey yazmaz; "Onayla ve kaydet" aynı isteği özetle yeniden gönderir.
 * Kart en çok 440 dp (tablette ortada); satır listesi en çok 320 dp yükseklikte kayar.
 */
@Composable
fun HakedisEtkisiPenceresi(bekleyen: HakedisOnayi.Bekleyen, yanit: (Boolean) -> Unit) {
    Column(
        Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DS.Colors.surfaceContainer)
            .border(1.dp, DS.Colors.hairlineStrong, RoundedCornerShape(18.dp))
            // Kartın içine dokunmak pencereyi kapatmaz.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon("exclamationmark.triangle", size = 17.dp, tint = hex("F59E0B"))
            Text("Hakediş değişecek", style = sys(17, FontWeight.Bold), color = DS.Colors.onSurface)
        }
        if (bekleyen.degisti) {
            Text(HakedisEtkisi.DEGISTI_METNI, style = sys(13), color = DS.Colors.onSurfaceVariant)
        }
        Column(
            Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in bekleyen.etki.satirlar ?: emptyList()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        s.metin, style = sys(13, FontWeight.SemiBold).monoDigits(),
                        color = if ((s.fark?.deger ?: 0.0) < 0) hex("FB7185") else hex("34D399"),
                    )
                    s.ekMetin?.let { Text(it, style = sys(11), color = DS.Colors.onSurfaceVariant) }
                }
            }
            bekleyen.etki.uyari?.let { Text(it, style = sys(12), color = DS.Colors.onSurfaceVariant) }
            val ek = bekleyen.ek
            if (!ek.isNullOrEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(hex("F59E0B").copy(alpha = 0.10f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Yapılan ya da yapılacak kalemler değişiyor", style = sys(12, FontWeight.Bold), color = hex("FCD34D"))
                    ek.forEach { Text("• $it", style = sys(12), color = hex("FCD34D")) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(onClick = { yanit(false) }, modifier = Modifier.weight(1f), height = 46.dp) { ButtonLabel("İptal") }
            BirincilDugme("Onayla ve kaydet", etkin = true, calisiyor = false, eylem = { yanit(true) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Korumalı işlemi başlatan pencerede onayı gösterir (Swift `.hakedisOnayi()` — `HakedisOnayi.shared.bekleyen` doluyken). Pencere
 * ayrı bir Dialog katmanında açılır: alt sayfanın üstünde, klavyeden bağımsız, tablette ortada. Dışarı dokunmak ya da geri tuşu "İptal".
 */
@Composable
fun HakedisOnayiKaplamasi(content: @Composable () -> Unit) {
    content()
    val merkez = HakedisOnayi.shared
    val b = merkez.bekleyen ?: return
    Dialog(onDismissRequest = { merkez.yanitla(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { merkez.yanitla(false) }
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            HakedisEtkisiPenceresi(b) { merkez.yanitla(it) }
        }
    }
}

// MARK: - Önizleme (kurmaca cümleler — gerçek kişi/tutar değil)

internal object OrnekHakedis {
    /** Swift `OrnekHastaSayfasi.hakedis(yetkili:)`. */
    fun bekleyen(yetkili: Boolean): HakedisOnayi.Bekleyen {
        val ozet = "a".repeat(64)
        val json = if (yetkili) {
            """{"etkiVar":true,"ozet":"$ozet","tutarGorunur":true,"satirlar":[{"hekimId":"dtA","ay":"2026-09","fark":-650,"metin":"Dt. A · Eylül 2026: ₺2.600,00 → ₺1.950,00 (−₺650,00)","ekMetin":"Eylül 2026 henüz kapatılmadı; değişiklik o ayın hakedişine yazılır. O ay için ödeme yaptıysanız önce ayı kapatın."}]}"""
        } else {
            """{"etkiVar":true,"ozet":"$ozet","tutarGorunur":false,"satirlar":[],"uyari":"Bu değişiklik hekim hakedişini etkiler. Tutarları hakediş yetkilisi görür."}"""
        }
        val etki = runCatching { DfyJson.decodeFromString(HakedisEtkisi.serializer(), json) }.getOrNull()
            ?: HakedisEtkisi(etkiVar = true, satirlar = emptyList())
        return HakedisOnayi.Bekleyen(etki, degisti = false, ek = if (yetkili) listOf("Kanal Tedavisi (Yapıldı)") else null)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 620)
@Composable
private fun HakedisEtkisiPenceresiPreview() {
    PreviewEnv {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(20.dp), contentAlignment = Alignment.Center) {
            HakedisEtkisiPenceresi(OrnekHakedis.bekleyen(yetkili = true)) {}
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun HakedisEtkisiPenceresiTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(20.dp), contentAlignment = Alignment.Center) {
            HakedisEtkisiPenceresi(OrnekHakedis.bekleyen(yetkili = false)) {}
        }
    }
}
