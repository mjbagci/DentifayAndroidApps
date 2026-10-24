package com.dentifay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.Yetki
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys

/** Ayarlar'ı açar (M-E8.1 — "Ayarlar" sekmesi profil simgesine taşındı). null → profil simgesi çizilmez (Ayarlar'ın kendisinde). */
val LocalAyarlariAc = compositionLocalOf<(() -> Unit)?> { null }

/** Üst çubuktaki profil simgesi — Ayarlar'ı açar (Swift `ProfilDugmesi`). */
@Composable
fun ProfilDugmesi(modifier: Modifier = Modifier) {
    val ayarlariAc = LocalAyarlariAc.current ?: return
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, DS.Colors.hairline, CircleShape)
            .pressable(scale = 0.94f) { Haptics.light(); ayarlariAc() },
        contentAlignment = Alignment.Center,
    ) { DfyIcon("person.crop.circle", size = 18.dp, tint = DS.Colors.metal) }
}

/**
 * Yetkisiz EYLEM görünümü — saf kural (M-E7.4). Görünen bir ekranın içindeki eylem GİZLENMEZ: yeteneği yoksa sönük çizilir,
 * dokunuşta işlem YAPILMAZ ve kısa uyarı çıkar.
 */
object YetkiliEylem {
    /** Sönük düğmenin opaklığı (%40). */
    const val SONUK_OPAKLIK = 0.4f

    /** Dokunuş: izinliyse eylemi çağırır ve null döner; değilse eylemi ÇAĞIRMAZ, gösterilecek uyarıyı döner. */
    fun dokun(izinli: Boolean, eylem: () -> Unit): String? {
        if (!izinli) return Yetki.EYLEM_YETKISIZ_METNI
        eylem()
        return null
    }
}

/** Oturumun eylem için gerekli yeteneklerin HEPSİNE sahip olup olmadığı. */
@Composable
fun yetkiliMi(yetenekler: List<String>): Boolean {
    val caps = LocalAuthManager.current.yetenekler
    return yetenekler.all { it in caps }
}

/**
 * Tek sarmalayıcı (Swift `YetkiliButon`): karar `currentUser.capabilities`ten, metin tek sabitte. İzinliyse `etiket`
 * normal tıklanır; değilse %40 sönük çizilir, dokunuş/uzun basış yalnız "Bu işlem için yetkiniz yok." balonunu açar.
 * Sayfa/sekme girişleri BUNU kullanmaz — onlar yeteneği yoksa hiç çizilmez (M-E7.2).
 * `etiket`e verilen `Modifier` tıklamayı taşır; etiket kendi tıklamasını eklememelidir.
 */
@Composable
fun YetkiliButon(
    yetenekler: List<String>,
    eylem: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 0.97f,
    etiket: @Composable (Modifier) -> Unit,
) {
    val izinli = yetkiliMi(yetenekler)
    if (izinli) {
        etiket(modifier.pressable(scale = scale, onClick = eylem))
        return
    }
    var uyari by remember { mutableStateOf<String?>(null) }
    val goster = { Haptics.light(); uyari = YetkiliEylem.dokun(false, eylem) }
    Box {
        etiket(
            modifier
                .alpha(YetkiliEylem.SONUK_OPAKLIK)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = goster, onLongClick = goster,
                )
        )
        uyari?.let { metin ->
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -120),
                onDismissRequest = { uyari = null },
                properties = PopupProperties(focusable = true),
            ) {
                Text(
                    metin,
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DS.Colors.surfaceContainerHighest)
                        .border(1.dp, DS.Colors.hairlineStrong, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface,
                )
            }
        }
    }
}

/** Önizlemede Ayarlar kapısını sağlar. */
@Composable
fun AyarlarKapisi(ac: () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAyarlariAc provides ac) { content() }
}
