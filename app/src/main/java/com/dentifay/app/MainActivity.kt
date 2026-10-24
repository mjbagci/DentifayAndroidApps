package com.dentifay.app

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
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

    /** SVG ikonlar (assets/dfy, assets/dfycift) Coil + SvgDecoder ile çizilir. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(false)
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
        ActivityHolder.current = this
        // Yalnız geliştirme: `--es DENTIFAY_API_HOST http://10.0.2.2:3001` (iOS'taki ortam değişkeni karşılığı).
        AppConfig.applyDebugOverride(intent?.getStringExtra("DENTIFAY_API_HOST"))
        setContent { RootView() }
    }

    override fun onResume() {
        super.onResume()
        ActivityHolder.current = this
    }
}
