package com.dentifay.app.hasta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Hasta görselleri için TEK önbellek (MOBIL_IS_EMRI M-E8.7) — uygulamanın Coil yükleyicisi (oturum çerezli paylaşılan istemci):
 * her görsel ekranda gösterileceği boyuta küçültülerek çözülür (tam çözünürlük belleğe açılmaz), aynı istek uçuştaysa ikinci kez
 * indirilmez, bellek önbelleği paylaşılır. Hasta görseli diske YAZILMAZ (iOS `urlCache = nil`): disk önbelleği bu isteklerde kapalı.
 */
object GorselOnbellegi {
    /** Tam ekran görüntüleyicinin üst sınırı (px) — bellek koruması. */
    const val AZAMI_PIKSEL = 3072

    /** `yol` göreli (`/uploads/...`) ya da tam adres. */
    fun adres(yol: String, taban: String = AppConfig.webBaseURL): String? {
        if (yol.isEmpty()) return null
        if (yol.startsWith("http")) return yol
        return taban + (if (yol.startsWith("/")) yol else "/$yol")
    }

    /** Küçültülmüş çözme isteği: uzun kenar `azamiPiksel` (ekran ölçeği dahil). */
    fun istek(context: Context, yol: String, azamiPiksel: Int, olcekleme: Scale = Scale.FILL): ImageRequest =
        ImageRequest.Builder(context)
            .data(adres(yol))
            .size(azamiPiksel.coerceIn(64, AZAMI_PIKSEL))
            .scale(olcekleme)
            .precision(Precision.EXACT)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

    /** Görselin özgün baytları (oturum çerezli; 200 değilse `null`) — "Röntgen Analizi'nde aç". */
    suspend fun veri(yol: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = adres(yol) ?: return@withContext null
        val istek = runCatching { Request.Builder().url(url).get().build() }.getOrNull() ?: return@withContext null
        val istemci = ApiClient.http.newBuilder().callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val yanit = try {
            ApiClient.execute(istek, istemci)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (yanit != null && yanit.status == 200) yanit.body else null
    }
}

/** Önbellekli küçük resim görünümü — görünür olunca yüklenir, kaybolunca istek iptal edilir (Coil). */
@Composable
fun OnbellekliGorsel(
    yol: String,
    azamiNokta: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { azamiNokta.roundToPx() }
    val istek = remember(yol, px, contentScale) {
        GorselOnbellegi.istek(context, yol, px, if (contentScale == ContentScale.Fit) Scale.FIT else Scale.FILL)
    }
    // 0 yükleniyor · 1 hazır · 2 yüklenemedi
    var durum by remember(yol) { mutableIntStateOf(0) }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (durum) {
            0 -> CircularProgressIndicator(Modifier.size(18.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp, trackColor = Color.Transparent)
            2 -> DfyIcon("photo", size = 20.dp, tint = DS.Colors.outline)
        }
        AsyncImage(
            model = istek,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
            onState = { s ->
                durum = when (s) {
                    is AsyncImagePainter.State.Success -> 1
                    is AsyncImagePainter.State.Error -> 2
                    else -> 0
                }
            },
        )
    }
}

/**
 * Yüklenecek görsellerin hazırlanması (G/Ç iş parçacığında çağrılır): EXIF yönü uygulanır, uzun kenar sınırlanır, JPEG (%85) olarak
 * yeniden kodlanır — konum gibi meta veriler taşınmaz. Tam çözünürlüklü görsel yalnız tek tek, yükleme anında belleğe açılır.
 */
internal object GorselHazirlama {
    fun jpeg(context: Context, uri: Uri, azamiKenar: Int = 4096, kalite: Int = 85): ByteArray? = runCatching {
        val bmp = coz(context, uri, azamiKenar) ?: return null
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, kalite, out)
            bmp.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    /** Seçim şeridi için küçük önizleme. */
    fun onizleme(context: Context, uri: Uri, kenarPx: Int): ImageBitmap? =
        runCatching { coz(context, uri, kenarPx)?.asImageBitmap() }.getOrNull()

    private fun coz(context: Context, uri: Uri, azamiKenar: Int): Bitmap? {
        val cr = context.contentResolver
        val sinir = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sinir) } ?: return null
        val enUzun = max(sinir.outWidth, sinir.outHeight)
        if (enUzun <= 0) return null
        var ornek = 1
        while (enUzun / (ornek * 2) >= azamiKenar) ornek *= 2
        val secenek = BitmapFactory.Options().apply {
            inSampleSize = ornek
            val ornekli = enUzun / ornek
            if (ornekli > azamiKenar) {
                // Çözerken tam boya küçült (ikinci tam boy bit eşlem oluşmaz).
                inScaled = true
                inDensity = ornekli
                inTargetDensity = azamiKenar
            }
        }
        val ham = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, secenek) } ?: return null
        val yon = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (yon) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return ham
        }
        val donuk = Bitmap.createBitmap(ham, 0, 0, ham.width, ham.height, m, true)
        if (donuk !== ham) ham.recycle()
        return donuk
    }
}
