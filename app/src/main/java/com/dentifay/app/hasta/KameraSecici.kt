package com.dentifay.app.hasta

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dentifay.app.theme.sharedCacheFile
import java.io.File
import java.util.UUID

/**
 * Kamera ile tek fotoğraf (medya yükleme — M-E8.4; Swift `UIImagePickerController`). Fotoğraf yalnız yüklemeye gider; cihaz galerisine
 * yazılmaz — uygulamanın önbelleğinde geçici dosyaya çekilir ve pencere kapanınca `temizle` ile silinir. Kamera yoksa `kameraYok`
 * (Swift'teki gibi galeriye düşülür). Kamera izni (manifestte bildirildiği için sistem kamerası da ister) gerekirse sorulur.
 *
 * Dönen işlev kamerayı açar; `sonuc` çekilen fotoğrafın adresini (vazgeçilirse `null`) alır.
 */
@Composable
fun rememberKameraSecici(sonuc: (Uri?) -> Unit, kameraYok: () -> Unit = { sonuc(null) }): () -> Unit {
    val context = LocalContext.current
    val guncelSonuc by rememberUpdatedState(sonuc)
    val guncelKameraYok by rememberUpdatedState(kameraYok)
    var hedef by remember { mutableStateOf<Uri?>(null) }

    val cek = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { tamam ->
        guncelSonuc(if (tamam) hedef else null)
        hedef = null
    }

    fun baslat() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            guncelKameraYok()
            return
        }
        val dosya = sharedCacheFile(context, KameraSecici.ONEK + UUID.randomUUID() + ".jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dosya)
        hedef = uri
        try {
            cek.launch(uri)
        } catch (e: ActivityNotFoundException) {
            hedef = null
            guncelKameraYok()
        }
    }

    val izin = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { verildi ->
        if (verildi) baslat() else guncelSonuc(null)
    }

    return remember(cek, izin) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) baslat()
            else izin.launch(Manifest.permission.CAMERA)
        }
    }
}

object KameraSecici {
    internal const val ONEK = "medya-kamera-"

    /** Çekilen geçici fotoğrafları siler (yükleme penceresi kapanınca). */
    fun temizle(context: Context) {
        val klasor = File(context.cacheDir, "shared")
        klasor.listFiles { f -> f.name.startsWith(ONEK) }?.forEach { runCatching { it.delete() } }
    }
}
