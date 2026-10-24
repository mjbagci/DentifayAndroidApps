package com.dentifay.app.hasta

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** Hasta sayfasının başlığı — listeden / arama sonucundan gelen alanlarla (başlık için ağır istek YOK; M-E8.3). */
@Immutable
data class HastaBasligi(
    val id: String,
    val ad: String,
    val dosyaNo: String? = null,
    val telefon: String? = null,
) {
    companion object {
        /** `rememberSaveable` için (seçili hasta süreç yeniden kurulunca da açık kalır). */
        val Saver: Saver<HastaBasligi?, Any> = listSaver<HastaBasligi?, String?>(
            save = { b -> if (b == null) emptyList() else listOf(b.id, b.ad, b.dosyaNo, b.telefon) },
            restore = { l -> if (l.size < 2) null else HastaBasligi(l[0] ?: "", l[1] ?: "", l.getOrNull(2), l.getOrNull(3)) },
        )
    }
}
