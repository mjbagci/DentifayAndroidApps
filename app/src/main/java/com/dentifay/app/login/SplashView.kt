package com.dentifay.app.login

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private const val LOGO_WIDTH = 300
/** Tüm scriptli animasyonun kabaca süresi (bar süzülmesi bununla eşleşir): birleşme + bekleme + sheen. */
private const val ANIM_SPAN_MS = 820 + 120 + 1820

/**
 * Dentifay açılış intro'su (programatik):
 *   1) Logonun iki parçası ayrık gelir, ortada BİRLEŞİR (yaylı → temiz oturma).
 *   2) Birleşince: SOL-ÜST → SAĞ-ALT çapraz ışık sheen'i logonun VE "Dentifay" yazısının
 *      üzerinden SENKRON, tek sefer, yumuşak geçer. Arkada spotlight / radyal glow YOK.
 *   3) Alttaki yükleme çubuğu DİNAMİKTİR: min animasyon süresince ~0.9'a süzülür,
 *      1.0'a ancak veri (contentReady) geldiğinde dolar; veri gecikirse 0.9'da bekler.
 *      Her ikisi de hazır olunca RootView eriyerek (blur+scale+fade) kapatır.
 */
@Composable
fun SplashView(contentReady: Boolean, onFinished: () -> Unit) {
    val context = LocalContext.current
    // iOS "Hareketi Azalt" karşılığı: geliştirici seçeneklerinde animasyonlar kapalı.
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val finished by rememberUpdatedState(onFinished)
    val scope = rememberCoroutineScope()

    val offset = remember { Animatable(190f) }        // parça ayrıklığı → 0 birleşti
    val pieceOpacity = remember { Animatable(0f) }
    var logoOpacity by remember { mutableStateOf(0f) }
    val sheen = remember { Animatable(-1.2f) }        // çapraz sheen konumu (-1.2 … 1.2) — logo+yazı ORTAK
    var showTitle by remember { mutableStateOf(false) }
    val barProgress = remember { Animatable(0f) }

    // Kapanış koordinasyonu: animasyon bitişi + veri hazır olunca barı doldur → onFinished.
    var animPhaseDone by remember { mutableStateOf(false) }
    var dataReady by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }

    val titleAlpha by animateFloatAsState(
        if (showTitle) 1f else 0f,
        if (reduceMotion) Motion.linear(0) else Motion.easeOut(300), label = "title",
    )

    /** Animasyon bitti VE veri hazır olduğunda: barı 1.0'a doldur, sonra kapanışı tetikle. */
    fun finishIfReady() {
        if (!animPhaseDone || !dataReady || finishing) return
        finishing = true
        scope.launch { barProgress.animateTo(1f, Motion.easeOut(320)) }
        scope.launch {
            delay(340)
            finished()
        }
    }

    LaunchedEffect(Unit) {
        if (contentReady) dataReady = true

        // Bar min animasyon süresince ~0.9'a süzülür (veriden bağımsız). 1.0'a finishIfReady doldurur.
        launch { barProgress.animateTo(0.9f, Motion.easeInOut(ANIM_SPAN_MS)) }

        // Logo ve parçalar uygulama kaynaklarında derlenmiş durumda (Swift'teki "logo yok" yedeği gereksiz).
        if (reduceMotion) {
            logoOpacity = 1f
            showTitle = true
            animPhaseDone = true
            finishIfReady()
            return@LaunchedEffect
        }

        launch { pieceOpacity.animateTo(1f, Motion.easeOut(200)) }
        // Overshoot'lu yay → parçalar merkezi hafif geçip iç içe girer, sonra geri oturur.
        // offset ± simetrik uygulandığından bu his SOL ve SAĞ parçada eşit olur.
        launch { offset.animateTo(0f, Motion.spring(0.6, 0.7)) }

        delay(820)
        // Parçalar oturdu → sadece başlık + hafif haptik.
        showTitle = true
        Haptics.soft()

        delay(120)
        launch { sheen.animateTo(1.2f, Motion.easeInOut(1820)) }   // logo+yazı senkron

        delay(1820)
        animPhaseDone = true
        finishIfReady()
    }

    LaunchedEffect(contentReady) {
        if (contentReady) {
            dataReady = true
            finishIfReady()
        }
    }

    Box(Modifier.fillMaxSize().background(DS.Colors.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.offset(y = (-6).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            LogoStage(
                offset = { offset.value },
                pieceOpacity = { pieceOpacity.value },
                logoOpacity = logoOpacity,
                sheen = { sheen.value },
                modifier = Modifier.size(LOGO_WIDTH.dp).clearAndSetSemantics { },
            )

            // Başlık + yükleme çubuğu
            Column(
                Modifier.width(240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                MetalNeonTitle("Dentifay", shinePhase = { sheen.value }, modifier = Modifier.graphicsLayer { alpha = titleAlpha })

                Box(
                    Modifier
                        .size(width = 210.dp, height = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.09f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width((210f * max(0f, barProgress.value)).dp)
                            .clip(RoundedCornerShape(50))
                            .background(DS.Gradients.primaryGradient)
                    )
                }
            }
        }
    }
}

// MARK: Logo sahnesi

@Composable
private fun LogoStage(
    offset: () -> Float,
    pieceOpacity: () -> Float,
    logoOpacity: Float,
    sheen: () -> Float,
    modifier: Modifier = Modifier,
) {
    val full = painterResource(R.drawable.dentifay_splash_00)
    Box(modifier) {
        // Birleşen parçalar SON hâldir — ayrı bir tam logoya crossfade YOK.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = pieceOpacity() }) {
            Image(
                painterResource(R.drawable.dentifay_logo_right), null,
                Modifier.matchParentSize().offset { IntOffset((-offset()).dp.roundToPx(), 0) },
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.dentifay_logo_left), null,
                Modifier.matchParentSize().offset { IntOffset(offset().dp.roundToPx(), 0) },
                contentScale = ContentScale.Fit,
            )
        }

        // Parçalar yoksa (yedek) tam logo.
        Image(full, null, Modifier.matchParentSize().graphicsLayer { alpha = logoOpacity }, contentScale = ContentScale.Fit)

        // Çapraz sheen — tam logo şekliyle maskeli.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawFitted(full)
                    drawDiagonalShine(sheen(), 0.38f, BlendMode.SrcIn)
                }
        )
    }
}

/** `Image(...).resizable().scaledToFit()` çizimi (maske için). */
private fun DrawScope.drawFitted(p: Painter) {
    val intr = p.intrinsicSize
    if (intr.width <= 0f || intr.height <= 0f) return
    val s = min(size.width / intr.width, size.height / intr.height)
    val w = intr.width * s
    val h = intr.height * s
    translate((size.width - w) / 2, (size.height - h) / 2) {
        with(p) { draw(Size(w, h)) }
    }
}

/**
 * Sol-üst → sağ-alt çapraz, yumuşak ışık bandı (butonlardaki shine efekti).
 * `phase` -1.2 (görünmez, sol-üst) → 1.2 (görünmez, sağ-alt). Maske `blendMode` ile uygulanır.
 * Swift: d×0.30 genişlik, d×2.2 yükseklik, 45° döndürülmüş, (phase·d·1.3, phase·d·1.3) kaydırılmış.
 */
private fun DrawScope.drawDiagonalShine(phase: Float, intensity: Float, blendMode: BlendMode) {
    val d = max(size.width, size.height)
    val w = d * 0.30f
    val h = d * 2.2f
    val cx = w / 2 + phase * d * 1.3f
    val cy = h / 2 + phase * d * 1.3f
    rotate(45f, pivot = Offset(cx, cy)) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = intensity), Color.Transparent),
                startY = cy - h / 2, endY = cy + h / 2,
            ),
            topLeft = Offset(cx - w / 2, cy - h / 2),
            size = Size(w, h),
            blendMode = blendMode,
        )
    }
}

private val metalGradient = relativeLinearGradient(
    listOf(
        0.00f to mutedHex("8B5CF6"),
        0.28f to mutedHex("B9A5FF"),
        0.50f to mutedHex("DFFBFF"),   // metalik streak (açık cyan)
        0.72f to mutedHex("6FE6F2"),
        1.00f to mutedHex("22D3EE"),
    ),
    Offset(0f, 0.5f), Offset(1f, 0.5f),
)

private val glassDepth = relativeLinearGradient(
    listOf(0f to Color.White.copy(alpha = 0.12f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f)),
    Offset(0.5f, 0f), Offset(0.5f, 1f),
)

/**
 * "Dentifay" — YATAY metalik/camsı neon gradyan (kalın). Ağır glow YOK.
 * Parlama: logoyla SENKRON çapraz sheen — yazı şekliyle maskeli, yüksek yoğunluk.
 */
@Composable
private fun MetalNeonTitle(text: String, shinePhase: () -> Float, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(glassDepth, blendMode = BlendMode.SrcAtop)                 // camsı derinlik (statik)
                drawDiagonalShine(shinePhase(), 0.85f, BlendMode.SrcAtop)           // logoyla senkron sheen
            },
        style = sys(42, FontWeight.Bold).copy(brush = metalGradient),
        maxLines = 1,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SplashViewPreview() {
    PreviewEnv(loggedIn = false) {
        SplashView(contentReady = true, onFinished = {})
    }
}
