package com.dentifay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Pencere bilgisi — telefon / katlanabilir / tablet, dikey / yatay, çoklu pencere. Kök görünüm (`RootView`) gerçek pencere
 * boyutundan sağlar; ekranlar `LocalPencere.current` ile okur.
 *
 * Sınıflar Material pencere boyutu sınıflarıdır: compact < 600 dp ≤ medium < 840 dp ≤ expanded.
 * iOS sürümü iPad'de içeriği 440–640 pt genişlikle sınırlayıp ortalıyor; Android tabletlerde aynı sınır + çok sütunlu ızgaralar
 * + yan gezinme rayı kullanılır.
 */
data class PencereBilgisi(val genislik: Dp, val yukseklik: Dp) {
    enum class Sinif { compact, medium, expanded }

    val sinif: Sinif
        get() = when {
            genislik < 600.dp -> Sinif.compact
            genislik < 840.dp -> Sinif.medium
            else -> Sinif.expanded
        }

    /** Cihaz tablet ölçüsünde mi (kısa kenar ≥ 600 dp) — telefon yatayda tablet SAYILMAZ. */
    val tablet: Boolean get() = min(genislik.value, yukseklik.value) >= 600f

    val yatay: Boolean get() = genislik > yukseklik

    /** Geniş düzen: yan gezinme rayı, iki sütunlu kart ızgaraları, yan yana bölmeler. */
    val genis: Boolean get() = genislik >= 600.dp

    /** Kısa pencere (yatay telefon / bölünmüş ekran): üst boşluklar küçültülür, büyük görseller sınırlanır. */
    val kisa: Boolean get() = yukseklik < 480.dp

    companion object {
        /** Önizleme ve testler için tipik telefon. */
        val TELEFON = PencereBilgisi(390.dp, 844.dp)
        val TABLET_YATAY = PencereBilgisi(1280.dp, 800.dp)
        val TABLET_DIKEY = PencereBilgisi(800.dp, 1280.dp)
    }
}

val LocalPencere = compositionLocalOf { PencereBilgisi.TELEFON }

/** Kökte bir kez: gerçek pencere ölçüsünü `LocalPencere` olarak sağlar. */
@Composable
fun PencereSaglayici(content: @Composable () -> Unit) {
    BoxWithConstraints {
        CompositionLocalProvider(LocalPencere provides PencereBilgisi(maxWidth, maxHeight)) { content() }
    }
}

/**
 * İçerik sütunu: tam genişlik, en çok `max` (Swift `.frame(maxWidth: 640)`), kendi kendini ORTALAR (ebeveynin hizasından
 * bağımsız). Telefonda etkisizdir. Kullanım: `Column(Modifier.icerikGenisligi().padding(horizontal = 20.dp))`.
 */
fun Modifier.icerikGenisligi(max: Dp = 640.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/**
 * Ortalanmış içerik kabı — Swift'teki `.frame(maxWidth: N).frame(maxWidth: .infinity)` kalıbı. Telefonda tam genişlik,
 * tablette `max` genişlikte ortada.
 */
@Composable
fun OrtaliIcerik(modifier: Modifier = Modifier, max: Dp = 640.dp, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = max)) { content() }
    }
}

/** Kaç sütun sığar: en az `minHucre` genişlikte hücreler, aralarında `bosluk`; en çok `enFazla`. */
fun sutunSayisi(genislik: Dp, minHucre: Dp, bosluk: Dp = 12.dp, enFazla: Int = 4): Int {
    val n = ((genislik.value + bosluk.value) / (minHucre.value + bosluk.value)).toInt()
    return n.coerceIn(1, max(1, enFazla))
}

/**
 * Uyarlanır kart ızgarası (tembel olmayan — kısa listeler için; uzun listelerde `LazyVerticalGrid(GridCells.Adaptive(...))`).
 * Telefonda tek sütun, genişledikçe `minHucre`ye göre 2–3 sütun; satırdaki hücreler eşit genişlikte.
 */
@Composable
fun <T> UyarlanirIzgara(
    ogeler: List<T>,
    modifier: Modifier = Modifier,
    minHucre: Dp = 320.dp,
    bosluk: Dp = 12.dp,
    enFazla: Int = 3,
    hucre: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sutun = sutunSayisi(maxWidth, minHucre, bosluk, enFazla)
        Column(verticalArrangement = Arrangement.spacedBy(bosluk)) {
            ogeler.chunked(sutun).forEach { satir ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(bosluk)) {
                    satir.forEach { oge -> Box(Modifier.weight(1f)) { hucre(oge) } }
                    repeat(sutun - satir.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}
