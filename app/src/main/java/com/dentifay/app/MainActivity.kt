package com.dentifay.app

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder
import com.dentifay.app.theme.DensityHolder
import com.dentifay.app.theme.Haptics
import com.dentifay.app.ui.RootView

class DentifayApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Haptics.init(this)
        DensityHolder.density = resources.displayMetrics.density
    }

    /**
     * Ağ görselleri (hasta medyası, röntgen küçük resimleri) Coil ile — oturum çerezli paylaşılan istemciyle; bellek ve disk
     * önbelleği açık. İkonlar Coil KULLANMAZ (VectorDrawable — `DfyIcon`).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { ApiClient.http }
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()
}

/** `@main struct DentAISoftApp` → tek Activity, tüm arayüz Compose. Uygulama koyu temadadır. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF0C1019.toInt()),
        )
        super.onCreate(savedInstanceState)
        yonuAyarla(resources.configuration)
        ActivityHolder.current = this
        // Yalnız geliştirme: `--es DENTIFAY_API_HOST http://10.0.2.2:3001` (iOS'taki ortam değişkeni karşılığı).
        AppConfig.applyDebugOverride(intent?.getStringExtra("DENTIFAY_API_HOST"))
        setContent { RootView() }
    }

    override fun onResume() {
        super.onResume()
        ActivityHolder.current = this
    }

    /** Döndürme / çoklu pencere / katlama: Activity YENİDEN KURULMAZ (manifest `configChanges`) — açık ekranların durumu korunur. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        yonuAyarla(newConfig)
    }

    /**
     * Telefonda (kısa kenar < 600 dp) dikey; tablet ve açık katlanabilirde serbest döndürme. (Android 16 büyük ekranda yön
     * kilidini zaten yok sayar; eski sürümlerde tabletin dikeye kilitlenmemesi için burada açıkça serbest bırakılır.)
     */
    private fun yonuAyarla(config: Configuration) {
        val istenen = if (config.smallestScreenWidthDp < 600) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (requestedOrientation != istenen) requestedOrientation = istenen
    }
}
