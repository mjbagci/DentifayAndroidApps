package com.dentifay.app.aihub

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.agent.AgentView
import com.dentifay.app.credit.SmileCreditFormat
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.smile.SmileSimulationView
import com.dentifay.app.theme.CiftIkon
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.PageHeader
import com.dentifay.app.theme.SectionLabel
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.treatment.TreatmentPlanView
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.xray.XrayAnalysisView
import com.dentifay.app.xray.XrayAnalysisViewModel
import kotlin.math.max
import kotlin.math.min

/**
 * Analiz Merkezi — web'in en güncel `AIPage` + `AiModuleCard` düzeni: parıltılı başlık + açıklama,
 * "MODÜLLER" etiketi ve her modül için kutusuz çift tonlu ikon · başlık · rozetler · açıklama · "▶ Çalıştır".
 * Kart arka planında soluk görsel ya da grafik.
 */
@Composable
fun AIHubView(onShowNotifications: () -> Unit) {
    var showTreatmentPlan by remember { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    var showSmile by remember { mutableStateOf(false) }
    var showXray by remember { mutableStateOf(false) }
    val credits = SmileCreditsStore.shared

    LaunchedEffect(Unit) { credits.refresh() }

    /** Kalan kredi rozeti (sunucu yolu). */
    val creditBadge: String? = run {
        val w = credits.wallet
        if (!credits.isMetered || w == null) null
        else {
            val n = SmileCreditFormat.credits(w.availableCredits)
            if (w.isOnFreeCredits) "$n ücretsiz kredi" else "$n kredi"
        }
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PageHeader(
                    title = "Analiz Merkezi",
                    subtitle = "Tahmin, risk ve analiz araçları — klinik verinizle çalışır",
                    modifier = Modifier.padding(top = 8.dp).appear(0.0),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, DS.Colors.hairline, CircleShape)
                            .plainClickable(onClick = onShowNotifications),
                        contentAlignment = Alignment.Center,
                    ) {
                        DfyIcon("bell", size = 17.dp, tint = DS.Colors.metal)
                    }
                }

                SectionLabel("Modüller", Modifier.padding(top = 10.dp))

                ModuleCard(
                    icon = "rectangle.split.3x1",
                    tone = mutedHex("2dd4bf"),
                    backdrop = ModuleCardBackdrop.image(R.drawable.card_xray),
                    title = "Röntgenden Tedavi Planı",
                    badges = listOf(ModuleBadge.image, ModuleBadge.action),
                    short = "Hastayı seçin, röntgeni analiz edin; diş diş bulgulardan fiyatlı tedavi planı önerisi hazırlayıp hekim onayıyla hastaya gönderin.",
                    modifier = Modifier.appear(0.04),
                ) { showXray = true }

                ModuleCard(
                    icon = "mouth",
                    tone = mutedHex("f472b6"),
                    backdrop = ModuleCardBackdrop.image(R.drawable.card_smile),
                    title = "Gülüş Simülasyonu",
                    badges = listOf(ModuleBadge.image),
                    extraBadge = creditBadge,
                    short = "Tedavi sonrası gülüşü fotoğraf üzerinde gösterin: lamina, beyazlatma, eksik diş, ortodonti.",
                    modifier = Modifier.appear(0.08),
                ) { showSmile = true }

                ModuleCard(
                    icon = "mic",
                    tone = mutedHex("fbbf24"),
                    backdrop = ModuleCardBackdrop.image(R.drawable.card_plan),
                    title = "Sesli Tedavi Planı",
                    badges = listOf(ModuleBadge.action, ModuleBadge.write),
                    short = "Planı sesle söyleyin (\"26 kanal, 34 implant\"); kalemlere ayrılır ve onayınızla kaydedilir.",
                    modifier = Modifier.appear(0.12),
                ) { showTreatmentPlan = true }

                ModuleCard(
                    icon = "bubble.left.and.text.bubble.right",
                    tone = DS.Colors.brandIcon,
                    backdrop = ModuleCardBackdrop.chart,
                    title = "Dentifay Asistan",
                    badges = listOf(ModuleBadge.analysis, ModuleBadge.action),
                    short = "Soru sorun, raporlar ve grafikler oluşturun.",
                    modifier = Modifier.appear(0.16),
                ) { showAgent = true }
            }
        }
    }

    FullScreenCover(isPresented = showTreatmentPlan, onDismiss = { showTreatmentPlan = false }) {
        TreatmentPlanView(onDismiss = { showTreatmentPlan = false })
    }
    FullScreenCover(isPresented = showAgent, onDismiss = { showAgent = false }) {
        AgentView(onDismiss = { showAgent = false })
    }
    FullScreenCover(isPresented = showSmile, onDismiss = { showSmile = false }) {
        SmileSimulationView(onDismiss = { showSmile = false })
    }
    FullScreenCover(isPresented = showXray, onDismiss = { showXray = false }) {
        XrayAnalysisView(onDismiss = { showXray = false }, mode = XrayAnalysisViewModel.Mode.clinic)
    }
}

/** Web `moduleRegistry.BADGES` — rozet adı ve rengi (zemin %10, yazı 300 tonu, kenar %20). */
enum class ModuleBadge {
    forecast, action, write, image, analysis;

    val label: String
        get() = when (this) {
            forecast -> "Tahmin"
            action -> "Aksiyon"
            write -> "Yazma"
            image -> "Görüntü"
            analysis -> "Analiz"
        }

    /** (500 tonu, 300 tonu) — soluk paletle. */
    val colors: Pair<Color, Color>
        get() = when (this) {
            forecast -> mutedHex("06b6d4") to mutedHex("67e8f9")
            action -> mutedHex("10b981") to mutedHex("6ee7b7")
            write -> mutedHex("f43f5e") to mutedHex("fda4af")
            image -> mutedHex("8b5cf6") to mutedHex("c4b5fd")
            analysis -> mutedHex("6366f1") to mutedHex("a5b4fc")
        }
}

/** Swift `ModuleCard.Backdrop`. */
sealed class ModuleCardBackdrop {
    data class image(@DrawableRes val res: Int) : ModuleCardBackdrop()
    data object chart : ModuleCardBackdrop()
}

/**
 * Web `AiModuleCard`: cam kart (r 16, iç boşluk 20) · çift tonlu ikon · başlık + rozetler · kısa açıklama ·
 * "▶ Çalıştır". Arka planda kartın sağ kısmında sola doğru eriyen soluk görsel + hafif cyan → mor yıkama.
 */
@Composable
private fun ModuleCard(
    icon: String,
    tone: Color,
    title: String,
    badges: List<ModuleBadge>,
    short: String,
    modifier: Modifier = Modifier,
    backdrop: ModuleCardBackdrop? = null,
    extraBadge: String? = null,
    action: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .glassCard(16.dp)
            .plainClickable { Haptics.light(); action() }
    ) {
        if (backdrop != null) BackdropView(backdrop, Modifier.matchParentSize())

        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                // Web `CiftIkon size={30}`: kutusuz, modül renginde çift tonlu ikon.
                CiftIkon(icon, Modifier.padding(top = 2.dp), color = tone, size = 30.dp)

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TitleAndBadges(title, badges, extraBadge)
                    Text(
                        short, Modifier.padding(top = 2.dp),
                        style = sys(12).copy(lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = DS.Colors.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                RunButton { Haptics.light(); action() }
            }
        }
    }
}

/** Web: başlık ve rozetler aynı satırda; sığmazsa rozetler alt satıra iner (`ViewThatFits`). */
@Composable
private fun TitleAndBadges(title: String, badges: List<ModuleBadge>, extraBadge: String?) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val titleW = remember(title) { measurer.measure(title, sys(16, FontWeight.SemiBold)).size.width }
        val labels = badges.map { it.label } + listOfNotNull(extraBadge)
        val badgesW = remember(labels) {
            labels.sumOf { measurer.measure(it, sys(10, FontWeight.SemiBold)).size.width + with(density) { 14.dp.roundToPx() } }
        }
        val spacing = with(density) { 8.dp.roundToPx() }
        val fitsOneLine = titleW + badgesW + spacing * labels.size <= maxPx
        if (fitsOneLine) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TitleText(title)
                BadgeViews(badges, extraBadge)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleText(title)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { BadgeViews(badges, extraBadge) }
            }
        }
    }
}

@Composable
private fun TitleText(title: String) {
    Text(title, style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun BadgeViews(badges: List<ModuleBadge>, extraBadge: String?) {
    badges.forEach { b -> Badge(b.label, b.colors.first, b.colors.second) }
    if (extraBadge != null) Badge(extraBadge, DS.Colors.metal, DS.Colors.onSurfaceVariant)
}

@Composable
private fun Badge(text: String, base: Color, color: Color) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(20.dp)
            .clip(shape)
            .background(base.copy(alpha = 0.10f))
            .border(1.dp, base.copy(alpha = 0.20f), shape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = sys(10, FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** Web `btn-primary-gradient`: mor → cyan, sağ altta hafif koyulaşma. */
@Composable
private fun RunButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .pressable(onClick = onClick)
            .height(34.dp)
            .clip(shape)
            .background(relativeLinearGradient(listOf(0f to DS.Colors.purple500, 1f to DS.Colors.cyan400), Offset(0f, 0f), Offset(1f, 1f)))
            .background(relativeLinearGradient(listOf(0.32f to Color.Transparent, 1f to hex("0a0e19").copy(alpha = 0.45f)), Offset(0f, 0f), Offset(1f, 1f)))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("play.fill", size = 12.dp, tint = Color.White)
        Text("Çalıştır", style = sys(13, FontWeight.SemiBold), color = Color.White)
    }
}

/**
 * Soluk arka plan: genişliğin sağ %62'si, sola doğru maskeyle karta karışır. Metin mobilde tüm genişliğe
 * yayıldığı için opaklık webdekinden düşük tutulur (okunurluk).
 */
@Composable
private fun BackdropView(backdrop: ModuleCardBackdrop, modifier: Modifier) {
    Box(modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.62f)
                    .clipToBounds()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Maske: solda saydam → %45'te 0.85 → %70'te tam.
                        drawRect(
                            relativeLinearGradient(
                                listOf(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.85f), 0.7f to Color.Black),
                                Offset(0f, 0.5f), Offset(1f, 0.5f),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
            ) {
                when (backdrop) {
                    is ModuleCardBackdrop.image -> Image(
                        painterResource(backdrop.res), null,
                        Modifier.fillMaxSize().alpha(0.34f),
                        contentScale = ContentScale.Crop,
                    )
                    ModuleCardBackdrop.chart -> ModuleChartBackdrop(Modifier.fillMaxSize().padding(top = 18.dp, bottom = 18.dp, end = 14.dp))
                }
            }
        }
        // Hafif cyan → mor yıkama (altsol → üstsağ).
        Box(
            Modifier.fillMaxSize().background(
                relativeLinearGradient(
                    listOf(0f to DS.Colors.cyan400.copy(alpha = 0.06f), 0.5f to Color.Transparent, 1f to DS.Colors.purple500.copy(alpha = 0.09f)),
                    Offset(0f, 1f), Offset(1f, 0f),
                )
            )
        )
    }
}

/**
 * Dentifay Asistan kartının arka planı: asistanın ürettiği türden soluk bir grafik (aylık çubuklar +
 * eğilim çizgisi). Eksen yazısı yok; yalnız hafif yatay kılavuzlar.
 */
@Composable
private fun ModuleChartBackdrop(modifier: Modifier) {
    val values = listOf(38.0, 52.0, 45.0, 61.0, 56.0, 70.0, 64.0, 77.0, 72.0, 86.0)
    val trends = values.indices.map { 36 + it * 5.4 }
    val barColor = mutedHex("38bdf8").copy(alpha = 0.5f)
    val lineColor = mutedHex("c4b5fd").copy(alpha = 0.75f)
    Canvas(modifier.alpha(0.55f)) {
        fun y(v: Double): Float = (size.height * (1 - v / 100.0)).toFloat()
        // Kılavuzlar: 25 · 50 · 75 · 100
        listOf(25.0, 50.0, 75.0, 100.0).forEach { g ->
            drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 0.6.dp.toPx())
        }
        val band = size.width / values.size
        val barW = band * 0.56f
        val r = 2.dp.toPx()
        values.forEachIndexed { i, v ->
            val cx = band * (i + 0.5f)
            val top = y(v)
            drawRoundRect(barColor, Offset(cx - barW / 2, top), Size(barW, size.height - top), CornerRadius(r, r))
        }
        // Eğilim çizgisi (catmull-rom)
        val pts = trends.mapIndexed { i, t -> Offset(band * (i + 0.5f), y(t)) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[max(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(pts.size - 1, i + 2)]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y,
                )
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AIHubViewPreview() {
    PreviewEnv { AIHubView(onShowNotifications = {}) }
}
