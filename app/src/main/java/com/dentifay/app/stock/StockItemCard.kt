package com.dentifay.app.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.data.MockData
import com.dentifay.app.data.StockItem
import com.dentifay.app.data.StockStatus
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.NumberTicker
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.lowerTr
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv

/**
 * Stok kalemi kartı: ikon kutusu · ad + durum rozeti · kategori/barkod · stok ve minimum.
 *
 * @param esitYukseklik çok sütunlu ızgarada (tablet) kategori/barkod satırı boşken de yeri ayrılır — yan yana kartlar
 *   aynı yükseklikte kalır. Telefonda (tek sütun) Swift'teki gibi satır hiç çizilmez.
 */
@Composable
fun StockItemCard(item: StockItem, modifier: Modifier = Modifier, esitYukseklik: Boolean = false) {
    val status = item.status
    val stockValueColor = if (status == StockStatus.normal) DS.Colors.onSurface else status.color

    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().glassCard(16.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val boxShape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(56.dp)
                    .background(DS.Colors.surfaceContainer, boxShape)
                    .border(1.dp, Color.White.copy(alpha = 0.05f), boxShape),
                contentAlignment = Alignment.Center,
            ) {
                DfyIcon(item.icon, size = 22.dp, tint = DS.Colors.onSurfaceVariant)
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name, Modifier.weight(1f), style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(status)
                }

                val meta = metaLine(item)
                if (meta.isNotEmpty() || esitYukseklik) {
                    Text(meta, style = sys(11, FontWeight.Medium), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StockInfo("Stok", item.currentStock, stockValueColor, unitShort(item.unit))
                    StockInfo("Min", item.minimumStock, DS.Colors.onSurface, unitShort(item.unit))
                }
            }
        }
        if (status == StockStatus.out) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(DS.Colors.danger.copy(alpha = 0.04f)))
        }
    }
}

@Composable
private fun StatusBadge(status: StockStatus) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(status.bgColor)
            .border(1.dp, status.color.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DfyIcon(status.icon, size = 10.dp, tint = status.color)
        Text(status.label, style = sys(9, FontWeight.Bold, tracking = 0.5), color = status.color, maxLines = 1)
    }
}

@Composable
private fun StockInfo(label: String, value: Int, color: Color, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label.upperTr(), style = sys(9, FontWeight.Bold, tracking = 1.5), color = DS.Colors.outline)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            NumberTicker(value = value, modifier = Modifier.alignByBaseline(), style = sys(14, FontWeight.Bold), color = color)
            Text(unit, Modifier.alignByBaseline(), style = sys(11), color = color.copy(alpha = 0.7f))
        }
    }
}

/** Kategori · barkod (yoksa SKU). */
private fun metaLine(item: StockItem): String {
    val code = item.barcode.ifEmpty { item.sku }
    return listOf(item.category, code).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Web `stock.units` kısaltmaları. */
private fun unitShort(unit: String): String = when (unit.lowerTr()) {
    "", "adet" -> "ad."
    "gram" -> "g"
    "kilogram" -> "kg"
    "miligram" -> "mg"
    "litre" -> "L"
    "mililitre" -> "ml"
    "libre" -> "lb"
    "metre" -> "m"
    else -> unit
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun StockItemCardPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MockData.stockItems.take(3).forEach { StockItemCard(it) }
            StockItemCard(StockItem("Kompozit A3", "", sku = "KMP-A3", category = "Dolgu", unit = "gram", currentStock = 0, minimumStock = 5, icon = "paintbrush.fill"))
        }
    }
}
