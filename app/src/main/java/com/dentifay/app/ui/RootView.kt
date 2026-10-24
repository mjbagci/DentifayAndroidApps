package com.dentifay.app.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.data.DataManager
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.login.KurulumBeklemeView
import com.dentifay.app.login.LoginView
import com.dentifay.app.login.SifreBelirlemeView
import com.dentifay.app.login.SplashView
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Uygulama geneli Material teması — renkler DS paletinden (yalnız koyu tema). */
@Composable
fun DentifayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DS.Colors.cyan400,
            onPrimary = DS.Colors.onPrimary,
            secondary = DS.Colors.purple500,
            background = DS.Colors.background,
            onBackground = DS.Colors.onBackground,
            surface = DS.Colors.surface,
            onSurface = DS.Colors.onSurface,
            surfaceVariant = DS.Colors.surfaceVariant,
            onSurfaceVariant = DS.Colors.onSurfaceVariant,
            surfaceContainer = DS.Colors.surfaceContainer,
            surfaceContainerHigh = DS.Colors.surfaceContainerHigh,
            surfaceContainerHighest = DS.Colors.surfaceContainerHighest,
            surfaceContainerLow = DS.Colors.surfaceContainerLow,
            error = DS.Colors.error,
            outline = DS.Colors.outline,
            outlineVariant = DS.Colors.outlineVariant,
        ),
        content = content,
    )
}

/**
 * `RootView`: splash → (oturum varsa) dolu ana ekran, yoksa giriş.
 * Splash kapanışı: blur 18 + ölçek 1.1 + solma (easeIn 0.64 sn); içerik alttan yaylı kayar (spring 0.72 / bounce 0.1).
 */
@Composable
fun RootView() {
    val authManager = remember { AuthManager() }
    val dataManager = remember { DataManager() }
    var showSplash by remember { mutableStateOf(true) }
    var splashAnimDone by remember { mutableStateOf(false) }
    var backgroundTaskDone by remember { mutableStateOf(false) }
    var loginScreenReady by remember { mutableStateOf(false) }
    var contentOpacity by remember { mutableFloatStateOf(0f) }
    val splashProgress = remember { Animatable(0f) }   // 0 → 1: blur 0→18, scale 1→1.1, opacity 1→0
    val contentOffset = remember { Animatable(50f) }
    val scope = rememberCoroutineScope()

    fun dismissSplashIfReady() {
        if (!splashAnimDone || !backgroundTaskDone) return
        contentOpacity = 1f
        scope.launch { splashProgress.animateTo(1f, Motion.easeIn(640)) }
        scope.launch { contentOffset.animateTo(0f, Motion.springDuration(0.72, 0.1)) }
        scope.launch {
            delay(720)
            showSplash = false
            // Splash tamamen kapandı → biyometrik otomatik istem artık tetiklenebilir.
            loginScreenReady = true
        }
    }

    LaunchedEffect(Unit) {
        // Ağ değişmiş olabilir → ulaşılabilir backend'i seç (ilk istekten ÖNCE).
        AppConfig.resolveBaseURL()
        // Oturum açık bırakıldıysa doğrudan dolu ana ekrana geç.
        if (AuthManager.rememberSession) {
            AuthManager.restoreCookies()
            authManager.checkSession()
            if (authManager.isLoggedIn && authManager.kurulumBekleme == null) dataManager.preloadHomeData(authManager.currentUser?.id)
        }
        backgroundTaskDone = true
        dismissSplashIfReady()
    }

    DentifayTheme {
        CompositionLocalProvider(LocalAuthManager provides authManager, LocalDataManager provides dataManager) {
          PencereSaglayici {
          com.dentifay.app.theme.CoverHost {
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentOpacity
                            translationY = with(density) { contentOffset.value.dp.toPx() }
                        }
                ) {
                    val kurulumBekleme = authManager.kurulumBekleme
                    when {
                        // Şifre değişikliği zorunlu (AUTH §4): sunucu öteki istekleri 403 ile keser — yalnız şifre ekranı (M-E8.6).
                        authManager.isLoggedIn && authManager.sifreBelirle -> SifreBelirlemeView(zorunlu = true)
                        // Kurulumu bitmemiş klinik: sekmeler, ana sayfa, bildirim yoklaması, asistan ve veri isteği KURULMAZ (M-E5.4).
                        authManager.isLoggedIn && kurulumBekleme != null -> KurulumBeklemeView(yapabilir = kurulumBekleme)
                        authManager.isLoggedIn -> ContentView()
                        else -> LoginView(screenReady = loginScreenReady)
                    }
                }

                if (showSplash) {
                    val p = splashProgress.value
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(1f)
                            .blur((18 * p).dp)
                            .graphicsLayer {
                                val s = 1f + 0.1f * p
                                scaleX = s; scaleY = s
                                alpha = 1f - p
                            }
                    ) {
                        SplashView(contentReady = backgroundTaskDone, onFinished = {
                            splashAnimDone = true
                            dismissSplashIfReady()
                        })
                    }
                }
            }
          }
          }
        }
    }
}
