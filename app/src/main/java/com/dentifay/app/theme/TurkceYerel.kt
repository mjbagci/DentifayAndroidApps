package com.dentifay.app.theme

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** Uygulamanın sabit dili (Swift ekranları `tr_TR` ile çizilir). */
val TR_YEREL: Locale = Locale.forLanguageTag("tr-TR")

/**
 * Material takvimi gün adlarını ve haftanın ilk gününü cihaz dilinden alır; İngilizce bir telefonda "S M T W…" ve Pazar
 * başlangıcı çıkıyordu. İçerik Türkçe yapılandırmayla çizilir: "P S Ç P C C P", hafta Pazartesi başlar.
 * Uygulamanın kaynak dili değişmez (yalnız bu Compose alt ağacının takvim hesabı) — dil paketi indirmek gerekmez.
 */
@SuppressLint("AppBundleLocaleChanges")
@Composable
fun TurkceYerel(content: @Composable () -> Unit) {
    val yapilandirma = LocalConfiguration.current
    val tr = remember(yapilandirma) { Configuration(yapilandirma).apply { setLocale(TR_YEREL) } }
    CompositionLocalProvider(LocalConfiguration provides tr, content = content)
}
