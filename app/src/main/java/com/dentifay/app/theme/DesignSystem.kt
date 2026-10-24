package com.dentifay.app.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// SwiftUI `Color(hex:)` karşılığı.
fun hex(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return Color.Black
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)   // Swift'teki gibi AARRGGBB
        else -> Color.Black
    }
}

/** Paletin "soluk" kuralı: ton aynı, doygunluk ×0.76, açıklık ×0.96 (Swift `Color(mutedHex:)`). */
fun mutedHex(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return hex(hex)
    val int = h.toLong(16)
    val r = ((int shr 16) and 0xFF) / 255.0
    val g = ((int shr 8) and 0xFF) / 255.0
    val b = (int and 0xFF) / 255.0
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    var hue = 0.0; var sat = 0.0
    var light = (mx + mn) / 2
    val d = mx - mn
    if (d > 0) {
        sat = if (light > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        hue = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        hue /= 6
    }
    sat *= 0.76
    light *= 0.96
    fun channel(p: Double, q: Double, t0: Double): Double {
        var t = t0
        if (t < 0) t += 1
        if (t > 1) t -= 1
        if (t < 1.0 / 6) return p + (q - p) * 6 * t
        if (t < 1.0 / 2) return q
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
        return p
    }
    if (sat <= 0) return Color(light.toFloat(), light.toFloat(), light.toFloat())
    val q = if (light < 0.5) light * (1 + sat) else light + sat - light * sat
    val p = 2 * light - q
    return Color(channel(p, q, hue + 1.0 / 3).toFloat(), channel(p, q, hue).toFloat(), channel(p, q, hue - 1.0 / 3).toFloat())
}

/** SwiftUI `.font(.system(size:weight:))` karşılığı (SF yerine Roboto; font padding kapalı). */
fun sys(size: Number, weight: FontWeight = FontWeight.Normal, tracking: Number = 0): TextStyle = TextStyle(
    fontSize = size.toFloat().sp,
    fontWeight = weight,
    letterSpacing = tracking.toFloat().sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Monospaced rakamlar (SwiftUI `.monospacedDigit()`). */
fun TextStyle.monoDigits(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Türkçe büyük harf (Swift `uppercased(with: tr_TR)`). */
fun String.upperTr(): String = uppercase(Locale.forLanguageTag("tr-TR"))
fun String.lowerTr(): String = lowercase(Locale.forLanguageTag("tr-TR"))

/** Dentifay tasarım sistemi — web panelinin ("Neon Clinical", koyu tema) mobil karşılığı. */
object DS {
    object Colors {
        val brandIcon = mutedHex("a78bfa")

        val background = hex("0f131e")
        val surface = hex("0f131e")
        val surfaceDim = hex("0f131e")
        val surfaceBright = hex("353945")
        val surfaceContainerLowest = hex("0a0e19")
        val surfaceContainerLow = hex("171b27")
        val surfaceContainer = hex("1b1f2b")
        val surfaceContainerHigh = hex("262a36")
        val surfaceContainerHighest = hex("313441")
        val surfaceVariant = hex("313441")

        val onSurface = hex("dfe2f2")
        val onSurfaceVariant = hex("a9b4c2")
        val onBackground = hex("dfe2f2")

        val primary = hex("c3f5ff")
        val onPrimary = hex("002021")
        val primaryContainer = hex("31bed4")
        val onPrimaryContainer = hex("006c71")
        val inversePrimary = hex("00696e")
        val primaryFixed = hex("7fd3e0")
        val primaryFixedDim = hex("2fb3c4")

        val secondary = hex("dfa5f3")
        val onSecondary = hex("490080")
        val secondaryContainer = hex("9a4fd0")
        val onSecondaryContainer = hex("fff6fc")

        val tertiary = hex("a3dfd3")
        val onTertiary = hex("003731")
        val tertiaryContainer = hex("40bc8f")
        val onTertiaryContainer = hex("006559")
        val tertiaryFixedDim = hex("3cddc7")

        val error = hex("ffb4ab")
        val onError = hex("690005")
        val errorContainer = hex("93000a")
        val onErrorContainer = hex("ffdad6")

        val outline = hex("7d8796")
        val outlineVariant = hex("2c3442")

        val inverseSurface = hex("dfe2f2")
        val inverseOnSurface = hex("2c303c")

        val cyan400 = hex("31bed4")
        val purple500 = hex("a15de2")
        val purple400 = hex("c2a6f0")
        val slate500 = hex("64748b")
        val slate400 = hex("94a3b8")
        val slate900 = hex("0f172a")
        val orange400 = hex("e28e48")
        val accentViolet = hex("a15de2")

        val brandMagenta = hex("ae5de0")
        val brandViolet = hex("7a49d8")
        val brandBlue = hex("5789e8")
        val brandCyan = hex("31bed4")

        val metal = hex("a0abb9")
        val metalStrong = hex("dce1e7")

        val success = hex("27b082")
        val warning = hex("d19125")
        val danger = hex("d84e4e")

        val hairline = Color.White.copy(alpha = 0.06f)
        val hairlineStrong = Color.White.copy(alpha = 0.10f)
        val hairlineFocus = Color.White.copy(alpha = 0.16f)

        object Login {
            val bg0 = hex("07070c")
            val bg1 = hex("0b0a13")
            val bg2 = hex("11101c")
            val fg0 = hex("f4f3fa")
            val fg1 = hex("b8b6c8")
            val fg2 = hex("6f6c84")
            val fg3 = hex("4a475d")
            val card = hex("131722").copy(alpha = 0.72f)
        }
    }

    object Gradients {
        /** Birincil eylem — web giriş düğmesinin durakları. */
        val primaryGradient: Brush = relativeLinearGradient(
            listOf(0f to hex("ae5de0"), 0.35f to hex("7a49d8"), 0.70f to hex("5789e8"), 1f to hex("31bed4")),
            start = Offset(0f, 0.45f), end = Offset(1f, 0.55f),
        )

        /** Sayfa kimliği / avatar — topLeading → bottomTrailing. */
        val primaryDiagonal: Brush = relativeLinearGradient(
            listOf(0f to hex("a15de2"), 1f to hex("31bed4")), Offset(0f, 0f), Offset(1f, 1f),
        )

        /** Marka yazısı — leading → trailing. */
        val titleGradient: Brush = relativeLinearGradient(
            listOf(0f to hex("31bed4"), 1f to hex("a15de2")), Offset(0f, 0.5f), Offset(1f, 0.5f),
        )

        val orbGradient: Brush = Brush.radialGradient(
            colors = listOf(hex("31bed4").copy(alpha = 0.20f), hex("a15de2").copy(alpha = 0.10f), Color.Transparent),
            radius = 120f * DensityHolder.density,
        )
    }

    object Radius {
        val card = 18.dp
        val control = 12.dp
        val chip = 8.dp
    }
}

/** Uygulama yoğunluğu (px/dp) — sabit fırçalarda pt→px için. MainActivity'de ayarlanır. */
object DensityHolder { var density: Float = 3f }

/**
 * SwiftUI `UnitPoint` tabanlı doğrusal gradyan: başlangıç/bitiş çizim alanına göre oranlı (0…1).
 * Compose `Brush.linearGradient` mutlak px ister; bu fırça boyuta göre kendini kurar.
 */
fun relativeLinearGradient(stops: List<Pair<Float, Color>>, start: Offset, end: Offset): Brush =
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader = LinearGradientShader(
            from = Offset(start.x * size.width, start.y * size.height),
            to = Offset(end.x * size.width, end.y * size.height),
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
            tileMode = TileMode.Clamp,
        )
    }

/** SwiftUI `RadialGradient(center: UnitPoint, startRadius: 0, endRadius: pt)` karşılığı. */
fun relativeRadialGradient(colors: List<Color>, center: Offset, radius: Dp, stops: List<Float>? = null): Brush =
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader = RadialGradientShader(
            center = Offset(center.x * size.width, center.y * size.height),
            radius = max(1f, radius.value * DensityHolder.density),
            colors = colors,
            colorStops = stops,
            tileMode = TileMode.Clamp,
        )
    }

/** Kısayol: yatay (leading → trailing) gradyan. */
fun horizontalGradient(colors: List<Color>): Brush = relativeLinearGradient(
    colors.mapIndexed { i, c -> (if (colors.size == 1) 0f else i / (colors.size - 1f)) to c }, Offset(0f, 0.5f), Offset(1f, 0.5f),
)
fun horizontalGradient(a: Color, b: Color): Brush = horizontalGradient(listOf(a, b))
fun horizontalGradient(a: Color, b: Color, c: Color): Brush = horizontalGradient(listOf(a, b, c))

/** Kısayol: dikey (top → bottom) gradyan. */
fun verticalGradient(colors: List<Color>): Brush = relativeLinearGradient(
    colors.mapIndexed { i, c -> (if (colors.size == 1) 0f else i / (colors.size - 1f)) to c }, Offset(0.5f, 0f), Offset(0.5f, 1f),
)
fun verticalGradient(a: Color, b: Color): Brush = verticalGradient(listOf(a, b))
fun verticalGradient(a: Color, b: Color, c: Color): Brush = verticalGradient(listOf(a, b, c))

// MARK: - Arka plan

/** Web `body` zemini: düz #0f131e + sol-üstte mor, sağ-altta camgöbeği çok hafif ışık. */
@Composable
fun WebBackdrop(modifier: Modifier = Modifier, intensity: Double = 1.0) {
    Box(
        modifier
            .fillMaxSize()
            .background(DS.Colors.background)
            .background(relativeRadialGradient(listOf(hex("9a4fd0").copy(alpha = (0.07 * intensity).toFloat()), Color.Transparent), Offset(0f, 0f), 520.dp))
            .background(relativeRadialGradient(listOf(hex("31bed4").copy(alpha = (0.05 * intensity).toFloat()), Color.Transparent), Offset(1f, 1f), 560.dp))
    )
}

// MARK: - Kart

/** Web `.glass-card`: %3 beyaz yüzey, %6 kenar, üstte %10'luk ince ışık çizgisi. */
fun Modifier.glassCard(cornerRadius: Dp = 18.dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(Color.White.copy(alpha = 0.03f), shape)
        .background(hex("141823").copy(alpha = 0.92f), shape)
        .border(1.dp, verticalGradient(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.05f)), shape)
}

// MARK: - Basma geri bildirimi (SwiftUI ButtonStyle karşılıkları)

/**
 * `PressableStyle` / `HapticPressStyle`: basılıyken küçülme + hafif solma, dalgalanma (ripple) yok.
 * `haptic = true` → `HapticPressStyle` (basışta hafif titreşim, yaylı animasyon).
 */
fun Modifier.pressable(
    scale: Float = 0.98f,
    enabled: Boolean = true,
    haptic: Boolean = false,
    pressedAlpha: Float = if (haptic) 0.92f else 0.9f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed && haptic) Haptics.light() }
    val s by animateFloatAsState(
        if (pressed) scale else 1f,
        if (haptic) Motion.spring(0.3, 0.6) else tween(150, easing = Motion.EaseOut), label = "press",
    )
    val a by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(150), label = "pressAlpha")
    this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = a }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** Ripple'sız düz tıklama (SwiftUI `.buttonStyle(.plain)`). */
fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
}

// MARK: - Düğmeler

/** Birincil düğme: marka gradyanı, beyaz yarı kalın metin, 12'lik köşe (`PrimaryButtonStyle`). */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    cornerRadius: Dp = DS.Radius.control,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(150, easing = Motion.EaseOut), label = "s")
    val alpha by animateFloatAsState(if (!enabled) 0.45f else if (pressed) 0.88f else 1f, tween(150), label = "a")
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .then(if (enabled) Modifier.shadow(12.dp, shape, ambientColor = hex("7a49d8").copy(alpha = 0.16f), spotColor = hex("7a49d8").copy(alpha = 0.16f)) else Modifier)
            .clip(shape)
            .background(DS.Gradients.primaryGradient)
            .background(relativeLinearGradient(listOf(0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent), Offset(0.5f, 0f), Offset(0.5f, 0.5f)))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White, LocalTextStyleOverride provides sys(15, FontWeight.SemiBold)) {
            content()
        }
    }
}

/** İkincil düğme: cam yüzey + ince kenar (`SecondaryButtonStyle`). */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    cornerRadius: Dp = DS.Radius.control,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(Color.White.copy(alpha = if (pressed) 0.07f else 0.035f))
            .border(1.dp, DS.Colors.hairlineStrong, shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides DS.Colors.onSurface, LocalTextStyleOverride provides sys(15, FontWeight.SemiBold)) {
            content()
        }
    }
}

/** Düğme içeriği için varsayılan yazı stili (SwiftUI ButtonStyle'ın `.font(...)` vermesi gibi). */
val LocalTextStyleOverride = androidx.compose.runtime.compositionLocalOf { sys(15, FontWeight.SemiBold) }

/** Düğme etiketi: `PrimaryButton` / `SecondaryButton` içinde stil ve rengi miras alır. */
@Composable
fun ButtonLabel(text: String, modifier: Modifier = Modifier, style: TextStyle? = null) {
    Text(text, modifier, color = LocalContentColor.current, style = style ?: LocalTextStyleOverride.current, maxLines = 1)
}

// MARK: - Sayfa başlığı · bölüm etiketi · etiket çipi

/** Web `PageTitle`: isteğe bağlı kimlik ikonu + parıltılı başlık + soluk açıklama; sağda eylem alanı. */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    subtitle: String? = null,
    titleSize: Float = 26f,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) IconTile(icon = icon, tone = IconTone.Brand, size = IconTileSize.Md)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ShimmerText(title, size = titleSize)
            if (subtitle != null) {
                Text(subtitle, style = sys(14), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/** Web `sig-shimmer-text`: cyan → mor → beyaz → mor → cyan gradyanlı başlık, 16 sn'de bir kayar. */
@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier, size: Float = 30f, weight: FontWeight = FontWeight.Bold) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val colors = listOf(
        DS.Colors.cyan400, DS.Colors.purple500, Color.White, DS.Colors.purple500, DS.Colors.cyan400,
        DS.Colors.purple500, Color.White, DS.Colors.purple500, DS.Colors.cyan400,
    )
    val stops = listOf(0f, 0.14f, 0.25f, 0.36f, 0.5f, 0.64f, 0.75f, 0.86f, 1f)
    val brush = remember(phase) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val w = max(size.width, 1f)
                return LinearGradientShader(Offset(-w * phase, 0f), Offset(-w * phase + 2 * w, 0f), colors, stops, TileMode.Clamp)
            }
        }
    }
    Text(
        text, modifier,
        style = sys(size, weight, tracking = -0.5).copy(brush = brush),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
    )
}

enum class IconTileSize(val box: Dp, val icon: Dp) {
    Sm(32.dp, 22.dp), Md(40.dp, 28.dp), Lg(48.dp, 34.dp);
}

sealed interface IconTone {
    data object Brand : IconTone
    data object Metal : IconTone
    data object Muted : IconTone
    data class Accent(val colors: List<Color>) : IconTone
}

/** Web `IconTile`: kutusuz, çift tonlu ikon; kutu yalnız hiza için ayrılan boş alan. */
@Composable
fun IconTile(
    icon: String,
    modifier: Modifier = Modifier,
    tone: IconTone = IconTone.Metal,
    size: IconTileSize = IconTileSize.Md,
    customSize: Dp? = null,
    iconTint: Color? = null,
) {
    val box = customSize ?: size.box
    val iconSize = customSize?.let { it * 0.7f } ?: size.icon
    val color = iconTint ?: when (tone) {
        IconTone.Brand -> DS.Colors.brandIcon
        IconTone.Metal -> mutedHex("cbd5e1")
        IconTone.Muted -> DS.Colors.onSurfaceVariant
        is IconTone.Accent -> tone.colors.firstOrNull() ?: DS.Colors.brandIcon
    }
    Box(modifier.size(box).alpha(if (tone == IconTone.Muted) 0.7f else 1f), contentAlignment = Alignment.Center) {
        DfyIconImage(icon, duotone = true, tint = color, modifier = Modifier.size(iconSize))
    }
}

/** Web `CiftIkon`: kutusuz, çift tonlu ikon — tam `size` boyutunda. */
@Composable
fun CiftIkon(icon: String, modifier: Modifier = Modifier, color: Color = DS.Colors.brandIcon, size: Dp = 24.dp) {
    DfyIconImage(icon, duotone = true, tint = color, modifier = modifier.size(size))
}

/** Eski çağıranlar için: sayfa / kart kimlik ikonu = `IconTile`. */
@Composable
fun BrandIcon(systemName: String, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color? = null) {
    IconTile(systemName, modifier, tone = tint?.let { IconTone.Accent(listOf(it)) } ?: IconTone.Brand, customSize = size)
}

/** Büyük harf bölüm etiketi (web "MODÜLLER"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.upperTr(), modifier, style = sys(10, FontWeight.Bold, tracking = 1.4), color = DS.Colors.onSurfaceVariant)
}

/** Küçük etiket çipi. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, tint: Color = DS.Colors.cyan400) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = sys(11, FontWeight.SemiBold), color = tint,
    )
}

/** Logo işareti + "Dentifay" yazısı + alt satır. */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, markSize: Dp = 40.dp, titleSize: Float = 24f, tagline: String? = "KLİNİK YÖNETİMİ") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(markSize * 0.28f)) {
        Image(painterResource(R.drawable.dentifay_mark), null, Modifier.size(markSize), contentScale = ContentScale.Fit)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Dentifay", style = sys(titleSize, FontWeight.ExtraBold, tracking = -titleSize * 0.035f).copy(brush = DS.Gradients.titleGradient))
            if (tagline != null) {
                Text(tagline, style = sys(max(8f, titleSize * 0.36f), FontWeight.Bold, tracking = titleSize * 0.08f), color = DS.Colors.cyan400.copy(alpha = 0.8f))
            }
        }
    }
}

// MARK: - Giriş animasyonu

/** Tek seferlik giriş: hafif solma + kısa yukarı kayma (`.appear(delay:)`). */
fun Modifier.appear(delay: Double = 0.0): Modifier = composed {
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(350, delayMillis = (delay * 0.6 * 1000).toInt(), easing = Motion.EaseOut), label = "appear",
    )
    LaunchedEffect(Unit) { shown = true }
    val density = LocalDensity.current
    graphicsLayer {
        alpha = progress
        translationY = with(density) { 8.dp.toPx() } * (1 - progress)
    }
}

/** Ekranda yer kaplamayan esnek boşluk (SwiftUI `Spacer(minLength:)` yatay/dikey). */
@Composable
fun RowScope.FlexSpacer(minLength: Dp = 0.dp) = Spacer(Modifier.weight(1f).width(minLength))
