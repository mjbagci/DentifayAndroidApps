package com.dentifay.app.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.Yetki
import com.dentifay.app.quickadd.QuickAction
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.horizontalGradient
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.verticalGradient

enum class TabItem {
    home, hastaTedavi, stock, quickAdd, ai, settings;

    val icon: String
        get() = when (this) {
            home -> "calendar"              // web kenar çubuğu: Randevular = Calendar
            hastaTedavi -> "list.clipboard"
            stock -> "shippingbox"
            quickAdd -> "plus"
            ai -> "gauge.with.dots.needle.33percent"   // Analiz Merkezi ikonu
            settings -> "gearshape"
        }

    val label: String
        get() = when (this) {
            home -> "Randevular"; hastaTedavi -> "Tedavi Planı"; stock -> "Stok"
            quickAdd -> "Ekle"; ai -> "Analiz Merkezi"; settings -> "Ayarlar"
        }

    companion object {
        /** Gezinmedeki sıra (M-E8.1): Randevular · Tedavi Planı · Stok · Ekle · Analiz Merkezi. Ayarlar profil simgesinde. */
        val SIRA = listOf(home, hastaTedavi, stock, quickAdd, ai)

        /** Sekme oturumun yeteneklerinde mi? (M-E3.6) Ayarlar herkesindir (kişisel sayfalar — AUTH §15). */
        fun gorunur(caps: Set<String>): Set<TabItem> {
            val s = mutableSetOf(settings)
            if (Yetki.gorur(caps, Yetki.Yuzey.randevular)) s += home
            if (Yetki.gorur(caps, Yetki.Yuzey.hastaTedavi)) s += hastaTedavi
            if (Yetki.gorur(caps, Yetki.Yuzey.stok)) s += stock
            if (QuickAction.entries.any { Yetki.gorur(caps, it.yuzey) }) s += quickAdd
            if (Yetki.analizKartlari.any { Yetki.gorur(caps, it) }) s += ai
            return s
        }
    }
}

/**
 * Alt gezinme — düz koyu yüzey, nötr ikonlar, etkin sekme camgöbeği + üstte ince marka çizgisi (sekmeler arasında kayar).
 * Ortadaki "Ekle" tek gradyanlı öğe. Yeteneği olmayan sekme ÇİZİLMEZ (M-E3.6).
 */
@Composable
fun BottomNavBar(
    selectedTab: TabItem,
    onSelect: (TabItem) -> Unit,
    modifier: Modifier = Modifier,
    gorunur: Set<TabItem> = TabItem.entries.toSet(),
    onQuickAddTap: () -> Unit = {},
) {
    val sira = TabItem.SIRA.filter { it in gorunur }
    Column(modifier.fillMaxWidth().background(hex("0c1019"))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp)) {
            if (sira.isEmpty()) return@BoxWithConstraints
            val slot = maxWidth / sira.size
            val index = sira.indexOf(selectedTab)
            val x by animateDpAsState(slot * index.coerceAtLeast(0) + (slot - 22.dp) / 2, Motion.easeOut(200), label = "tabLine")
            Row(Modifier.fillMaxWidth()) {
                sira.forEach { tab ->
                    if (tab == TabItem.quickAdd) QuickAddButton(Modifier.weight(1f), onQuickAddTap)
                    else TabButton(tab, selectedTab == tab, Modifier.weight(1f)) {
                        if (selectedTab != tab) Haptics.selection()
                        onSelect(tab)
                    }
                }
            }
            if (index >= 0 && selectedTab != TabItem.quickAdd) {
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToPx(), (-8).dp.roundToPx()) }
                        .size(22.dp, 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(horizontalGradient(DS.Colors.cyan400, DS.Colors.purple500))
                )
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** Alt çubuğun dolu yüksekliği (sistem çubuğu hariç): 1 + 8 + 50 + 2. İçerik bu kadar alttan boşluk bırakır. */
val ALT_CUBUK_YUKSEKLIGI = 61.dp

@Composable
private fun TabButton(tab: TabItem, isActive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val activeF by animateFloatAsState(if (isActive) 1f else 0f, Motion.easeOut(200), label = "tab")
    val iconColor = lerp(DS.Colors.metal.copy(alpha = 0.75f), DS.Colors.cyan400, activeF)
    val textColor = lerp(DS.Colors.metal.copy(alpha = 0.7f), DS.Colors.cyan400, activeF)
    Column(
        modifier.height(50.dp).plainClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { DfyIcon(tab.icon, size = 19.dp, tint = iconColor) }
        Text(
            tab.label,
            style = sys(10.5, if (isActive) FontWeight.SemiBold else FontWeight.Medium),
            color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QuickAddButton(modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier.height(50.dp).pressable(scale = 0.94f) { Haptics.light(); onTap() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(44.dp, 32.dp).clip(RoundedCornerShape(10.dp)).background(DS.Gradients.primaryGradient),
            contentAlignment = Alignment.Center,
        ) { DfyIcon("plus", size = 18.dp, tint = Color.White) }
        Text("Ekle", style = sys(10.5, FontWeight.Medium), color = DS.Colors.metal.copy(alpha = 0.7f))
    }
}

/**
 * Geniş ekran (tablet, katlanabilir açık, yatay büyük ekran) gezinmesi — Android'in yan gezinme rayı, alt çubukla aynı dil:
 * düz koyu yüzey + sağda ince çizgi, etkin sekme camgöbeği + solda dikey marka çizgisi (sekmeler arasında kayar), en üstte
 * gradyanlı "Ekle".
 */
@Composable
fun NavRail(
    selectedTab: TabItem,
    onSelect: (TabItem) -> Unit,
    modifier: Modifier = Modifier,
    gorunur: Set<TabItem> = TabItem.entries.toSet(),
    onQuickAddTap: () -> Unit = {},
) {
    val sekmeler = TabItem.SIRA.filter { it in gorunur && it != TabItem.quickAdd }
    val ogeYuksekligi = 68.dp
    Row(modifier.fillMaxHeight().background(hex("0c1019"))) {
        Column(
            Modifier.width(88.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (TabItem.quickAdd in gorunur) {
                QuickAddButton(Modifier.fillMaxWidth(), onQuickAddTap)
                Spacer(Modifier.height(20.dp))
            }
            Box(Modifier.fillMaxWidth()) {
                val index = sekmeler.indexOf(selectedTab)
                val y by animateDpAsState(ogeYuksekligi * index.coerceAtLeast(0) + (ogeYuksekligi - 26.dp) / 2, Motion.easeOut(200), label = "railLine")
                Column(Modifier.fillMaxWidth()) {
                    sekmeler.forEach { tab ->
                        val aktif = selectedTab == tab
                        val f by animateFloatAsState(if (aktif) 1f else 0f, Motion.easeOut(200), label = "railTab")
                        Column(
                            Modifier.fillMaxWidth().height(ogeYuksekligi).plainClickable {
                                if (!aktif) Haptics.selection()
                                onSelect(tab)
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                        ) {
                            DfyIcon(tab.icon, size = 21.dp, tint = lerp(DS.Colors.metal.copy(alpha = 0.75f), DS.Colors.cyan400, f))
                            Text(
                                tab.label,
                                Modifier.padding(horizontal = 4.dp),
                                style = sys(11, if (aktif) FontWeight.SemiBold else FontWeight.Medium),
                                color = lerp(DS.Colors.metal.copy(alpha = 0.7f), DS.Colors.cyan400, f),
                                maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (index >= 0) {
                    Box(
                        Modifier
                            .offset { IntOffset(0, y.roundToPx()) }
                            .size(3.dp, 26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(verticalGradient(DS.Colors.cyan400, DS.Colors.purple500))
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(DS.Colors.hairline))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390)
@Composable
private fun BottomNavBarPreview() {
    var tab by remember { mutableStateOf(TabItem.home) }
    BottomNavBar(tab, { tab = it })
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 100, heightDp = 700)
@Composable
private fun NavRailPreview() {
    var tab by remember { mutableStateOf(TabItem.hastaTedavi) }
    NavRail(tab, { tab = it })
}
