package com.dentifay.app.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.Kurulum
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.launch

/**
 * Kurulumu bitmemiş klinik — tam ekran bekleme (MOBIL_IS_EMRI M-E5.4; DentAIWeb 40G · Ek W.5). Sekme, ana sayfa, bildirim
 * yoklaması, asistan ve veri isteği YOK; yalnız "Yeniden dene" (oturumu yeniden okur) ve "Çıkış yap".
 * Kısa pencerede (yatay / bölünmüş ekran) içerik kaydırılır; tablette sütun en çok 720 dp, ortada.
 */
@Composable
fun KurulumBeklemeView(yapabilir: Boolean) {
    val auth = LocalAuthManager.current
    var deneniyor by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val enAzYukseklik = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .heightIn(min = enAzYukseklik)
                        .icerikGenisligi(720.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Swift VStack(spacing: 22) { Spacer · ikon · metinler · Spacer · düğmeler } — aralık her öğe arasında.
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(22.dp))
                    IconTile(icon = "gearshape", tone = IconTone.Metal, customSize = 64.dp)
                    Spacer(Modifier.height(22.dp))
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Kurulum tamamlanmadı", style = sys(22, FontWeight.Bold), color = DS.Colors.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            Kurulum.metin(yapabilir), style = sys(15), color = DS.Colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        auth.organizationName?.let { ad ->
                            Text(ad, style = sys(13, FontWeight.Medium), color = DS.Colors.outline, textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(22.dp))
                    Column(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val sekil = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .pressable(enabled = !deneniyor) {
                                    // Ekrandan bağımsız (Swift `Task`): kurulum bitmişse ekran kökten kalkar, klinik adı yine okunur.
                                    girisKapsami.launch {
                                        deneniyor = true
                                        auth.checkSession()
                                        deneniyor = false
                                    }
                                }
                                .clip(sekil)
                                .background(DS.Gradients.primaryGradient),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (deneniyor) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                DfyIcon("arrow.clockwise", size = 16.dp, tint = Color.White)
                            }
                            Text("Yeniden dene", style = sys(16, FontWeight.SemiBold), color = Color.White, maxLines = 1)
                        }

                        SecondaryButton(onClick = { girisKapsami.launch { auth.logout() } }) {
                            ButtonLabel("Çıkış yap")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun KurulumBeklemePreview() {
    PreviewEnv { KurulumBeklemeView(yapabilir = true) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun KurulumBeklemeTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { KurulumBeklemeView(yapabilir = false) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 400)
@Composable
private fun KurulumBeklemeKisaPreview() {
    PreviewEnv(pencere = PencereBilgisi(800.dp, 400.dp)) { KurulumBeklemeView(yapabilir = false) }
}
