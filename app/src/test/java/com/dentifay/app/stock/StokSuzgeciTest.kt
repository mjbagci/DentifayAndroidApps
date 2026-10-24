package com.dentifay.app.stock

import androidx.compose.ui.unit.dp
import com.dentifay.app.data.StockItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stok ekranının saf kuralları (Swift `StockView.categories` · `filteredItems`) ve tablet sütun kararı. */
class StokSuzgeciTest {

    private fun kalem(ad: String, kategori: String = "", barkod: String = "", sku: String = "", stok: Int = 10, min: Int = 2) =
        StockItem(ad, barkod, sku = sku, category = kategori, currentStock = stok, minimumStock = min, icon = "archivebox.fill")

    private val liste = listOf(
        kalem("Nitril Eldiven", kategori = "Sarf", barkod = "8690001"),
        kalem("İmplant Vida", kategori = "İmplant", sku = "IMP-4", stok = 0),
        kalem("Kompozit A2", kategori = "Dolgu", stok = 3, min = 5),
        kalem("Dezenfektan", kategori = "", stok = 40, min = 5),
        kalem("Çekim Pensi", kategori = "Cerrahi"),
    )

    @Test
    fun kategorilerTurkceSirali() {
        // Boş kategori yok; Türkçe sıralama (Ç, C'den sonra; İ, I'dan sonra).
        assertEquals(listOf("Cerrahi", "Dolgu", "İmplant", "Sarf"), StokSuzgeci.kategoriler(liste))
    }

    @Test
    fun suzgecVeArama() {
        assertEquals(5, StokSuzgeci.suz(liste, StockFilter.all, "").size)
        // Dikkat = tükendi + düşük.
        assertEquals(listOf("İmplant Vida", "Kompozit A2"), StokSuzgeci.suz(liste, StockFilter.attention, "").map { it.name })
        assertEquals(listOf("Kompozit A2"), StokSuzgeci.suz(liste, StockFilter.category("Dolgu"), "").map { it.name })
        // Ad, barkod, SKU ve kategoride büyük-küçük harf duyarsız (Türkçe "İ/i").
        assertEquals(listOf("İmplant Vida"), StokSuzgeci.suz(liste, StockFilter.all, "  implant ").map { it.name })
        assertEquals(listOf("Nitril Eldiven"), StokSuzgeci.suz(liste, StockFilter.all, "8690").map { it.name })
        assertEquals(listOf("İmplant Vida"), StokSuzgeci.suz(liste, StockFilter.all, "imp-4").map { it.name })
        assertEquals(listOf("Çekim Pensi"), StokSuzgeci.suz(liste, StockFilter.all, "cerrah").map { it.name })
        // Süzgeç ve arama birlikte.
        assertEquals(emptyList<String>(), StokSuzgeci.suz(liste, StockFilter.category("Sarf"), "kompozit").map { it.name })
    }

    @Test
    fun sutunSayisi() {
        assertEquals(1, StokSuzgeci.sutunSayisi(390.dp))   // telefon
        assertEquals(1, StokSuzgeci.sutunSayisi(673.dp))   // katlanabilir
        assertEquals(1, StokSuzgeci.sutunSayisi(712.dp))   // tablet dikey (800 − yan ray)
        assertEquals(2, StokSuzgeci.sutunSayisi(1192.dp))  // tablet yatay (1280 − yan ray)
    }
}
