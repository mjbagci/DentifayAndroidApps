package com.dentifay.app.ui

import com.dentifay.app.ui.TabItem.ai
import com.dentifay.app.ui.TabItem.hastaTedavi
import com.dentifay.app.ui.TabItem.home
import com.dentifay.app.ui.TabItem.quickAdd
import com.dentifay.app.ui.TabItem.settings
import com.dentifay.app.ui.TabItem.stock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Alt çubuk: "Ekle" hep ortada, yanlarda eşit yuva; dört sekmesi olmayan rolde boşluk Ayarlar ile dolar. */
class AltCubukTest {

    @Test
    fun tumYetkiler() {
        assertEquals(listOf(home, hastaTedavi, quickAdd, stock, ai), TabItem.altCubuk(TabItem.entries.toSet()))
    }

    @Test
    fun hekimStokGormez_AyarlarDoldurur() {
        assertEquals(
            listOf(home, hastaTedavi, quickAdd, ai, settings),
            TabItem.altCubuk(setOf(home, hastaTedavi, quickAdd, ai, settings)),
        )
    }

    @Test
    fun azSekme_EkleYineOrtada() {
        val c = TabItem.altCubuk(setOf(home, quickAdd, settings))
        assertEquals(listOf(home, quickAdd, settings), c)
        val tek = TabItem.altCubuk(setOf(quickAdd, settings))
        assertEquals(listOf(settings, quickAdd, null), tek)
        assertEquals(tek.size / 2, tek.indexOf(quickAdd))
    }

    @Test
    fun ekleYoksaOrtaDugmeYok() {
        assertEquals(listOf(home, stock, settings), TabItem.altCubuk(setOf(home, stock, settings)))
    }
}
