package com.dentifay.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.horizontalGradient
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys

enum class TabItem {
    home, stock, quickAdd, ai, settings;

    val icon: String
        get() = when (this) {
            home -> "calendar"          // web kenar çubuğu: Randevular = Calendar
            stock -> "shippingbox"
            quickAdd -> "plus"
            ai -> "gauge.with.dots.needle.33percent"   // Analiz Merkezi ikonu
            settings -> "gearshape"
        }

    val label: String
        get() = when (this) {
            home -> "Randevular"; stock -> "Stok"; quickAdd -> "Ekle"; ai -> "Analiz Merkezi"; settings -> "Ayarlar"
        }
}

/**
 * Alt gezinme — düz koyu yüzey, nötr ikonlar, etkin sekme camgöbeği + üstte ince marka çizgisi.
 * Ortadaki "Ekle" tek gradyanlı öğe. Etkin çizgi sekmeler arasında kayar (matchedGeometryEffect).
 */
@Composable
fun BottomNavBar(selectedTab: TabItem, onSelect: (TabItem) -> Unit, modifier: Modifier = Modifier, onQuickAddTap: () -> Unit = {}) {
    val order = listOf(TabItem.home, TabItem.stock, TabItem.quickAdd, TabItem.ai, TabItem.settings)
    Column(modifier.fillMaxWidth().background(hex("0c1019"))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp)) {
            val slot = maxWidth / 5
            // Etkin sekme çizgisi — sekmeler arasında yumuşakça kayar.
            val index = order.indexOf(selectedTab)
            val x by animateDpAsState(slot * index + (slot - 22.dp) / 2, Motion.easeOut(200), label = "tabLine")
            Row(Modifier.fillMaxWidth()) {
                order.forEach { tab ->
                    if (tab == TabItem.quickAdd) QuickAddButton(Modifier.weight(1f), onQuickAddTap)
                    else TabButton(tab, selectedTab == tab, Modifier.weight(1f)) {
                        if (selectedTab != tab) Haptics.selection()
                        onSelect(tab)
                    }
                }
            }
            if (selectedTab != TabItem.quickAdd) {
                Box(
                    Modifier
                        .offset(x = x, y = (-8).dp)
                        .size(22.dp, 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(horizontalGradient(DS.Colors.cyan400, DS.Colors.purple500))
                )
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun TabButton(tab: TabItem, isActive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val activeF by animateFloatAsState(if (isActive) 1f else 0f, Motion.easeOut(200), label = "tab")
    val iconColor = androidx.compose.ui.graphics.lerp(DS.Colors.metal.copy(alpha = 0.75f), DS.Colors.cyan400, activeF)
    val textColor = androidx.compose.ui.graphics.lerp(DS.Colors.metal.copy(alpha = 0.7f), DS.Colors.cyan400, activeF)
    Column(
        modifier.height(50.dp).plainClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { DfyIcon(tab.icon, size = 19.dp, tint = iconColor) }
        Text(
            tab.label,
            style = sys(10.5, if (isActive) FontWeight.SemiBold else FontWeight.Medium),
            color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390)
@Composable
private fun BottomNavBarPreview() {
    var tab by remember { mutableStateOf(TabItem.home) }
    BottomNavBar(tab, { tab = it })
}
