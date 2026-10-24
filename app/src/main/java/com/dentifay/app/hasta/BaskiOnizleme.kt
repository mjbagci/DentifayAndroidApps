package com.dentifay.app.hasta

import android.annotation.SuppressLint
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dentifay.app.theme.ActivityHolder
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Baskı önizlemesi — istemcide kurulan HTML (plan / ödeme planı) görünür; "Yazdır" Android yazdırma penceresini açar (yazıcı ya da
 * "PDF olarak kaydet"). Sayfa yüklenmeden "Yazdır" sönük (boş belge basılmasın). Bağlantılar açılmaz, betik kapalı.
 */
@Composable
fun BaskiOnizleme(baslik: String, html: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tutucu = remember { WebTutucu() }
    Column(Modifier.fillMaxSize().background(DS.Colors.surfaceContainer)) {
        PencereBasligi(
            baslik, solMetni = "Kapat", solEylem = onDismiss,
            sagMetni = "Yazdır", sagEtkin = tutucu.yuklendi,
            sagEylem = { tutucu.gorunum?.let { yazdir(context, it, baslik) } },
        )
        if (LocalInspectionMode.current) {
            // Önizlemede WebView çizilmez.
            Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text("Baskı önizlemesi", style = sys(14), color = Color.Gray, textAlign = TextAlign.Center)
            }
        } else {
            AndroidView(
                factory = { ctx -> tutucu.kur(ctx) },
                update = { w ->
                    if (w.tag != html) {
                        w.tag = html
                        tutucu.yuklendi = false
                        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                onRelease = { w ->
                    tutucu.gorunum = null
                    w.stopLoading()
                    w.destroy()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White)
                    .navigationBarsPadding(),
            )
        }
    }
}

/** Önizleme sayfasının WebView'ı — durum yalnız "yüklendi" (Yazdır düğmesi). */
private class WebTutucu {
    var gorunum: WebView? = null
    var yuklendi by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    fun kur(ctx: Context): WebView = WebView(ctx).apply {
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                yuklendi = true
            }

            // Belgedeki bağlantılar (logo vb.) gezinti açmaz.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }
        gorunum = this
    }
}

/** Android yazdırma penceresi (yazıcı · "PDF olarak kaydet"); iş adı belgenin başlığı. A4 varsayılan. */
private fun yazdir(context: Context, w: WebView, baslik: String) {
    val etkinlik = ActivityHolder.current ?: context
    val yazici = etkinlik.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val ad = baslik.ifBlank { "Belge" }
    runCatching {
        yazici.print(ad, w.createPrintDocumentAdapter(ad), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}

/**
 * Önizlemeyi pencerenin TAMAMINDA açar (alt sayfanın üstünde — iOS'taki iç içe `.sheet`in karşılığı; tablette de tam pencere).
 * `html == null` → kapalı.
 */
@Composable
fun BaskiOnizlemePenceresi(baslik: String, html: String?, onDismiss: () -> Unit) {
    if (html == null) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) { BaskiOnizleme(baslik, html, onDismiss) }
    }
}

/** Diş şeması → PNG (baskıya gömülür — Swift `SemaGoruntusu.png`): uygulamanın şema çizimi beyaz zeminde, 340 dp, ×3 (arka planda). */
object SemaGoruntusu {
    suspend fun png(context: Context, kalemler: List<TedaviKalemi>): ByteArray? = withContext(Dispatchers.Default) {
        OdontogramGoruntusu.png(context.applicationContext, DisSemasi.aciklamalar(kalemler))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 700)
@Composable
private fun BaskiOnizlemePreview() {
    PreviewEnv { BaskiOnizleme("Plan 1", "<p>Örnek</p>") {} }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun BaskiOnizlemeTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { BaskiOnizleme("Ödeme Planı", "<p>Örnek</p>") {} }
}
