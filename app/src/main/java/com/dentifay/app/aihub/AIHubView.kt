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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.R
import com.dentifay.app.agent.AgentView
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.Yetki
import com.dentifay.app.credit.SmileCreditFormat
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.notifications.ZilDugmesi
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
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ProfilDugmesi
import com.dentifay.app.ui.icerikGenisligi
import com.dentifay.app.ui.sutunSayisi
import com.dentifay.app.xray.XrayAnalysisView
import com.dentifay.app.xray.XrayAnalysisViewModel
import kotlin.math.max
import kotlin.math.min

/** Tek sütunda içerik genişliği (Swift `.frame(maxWidth: 640)`). */
private val TEK_SUTUN_EN = 640.dp
/** İki sütunlu kart ızgarasında içerik genişliği (tablet). */
private val IKI_SUTUN_EN = 1040.dp
/** İki sütuna geçmek için kartın en az genişliği. */
private val MIN_KART = 320.dp
private val KART_ARASI = 16.dp

/**
 * Analiz Merkezi — web'in en güncel `AIPage` + `AiModuleCard` düzeni: parıltılı başlık + açıklama, "MODÜLLER" etiketi ve
 * her modül için kutusuz çift tonlu ikon · başlık · rozetler · açıklama · "▶ Çalıştır". Kart arka planında soluk görsel ya da
 * grafik. Kart yalnız oturumun yeteneği varsa çizilir (M-E3.6) — gizli düğme bırakılmaz.
 *
 * Geniş pencerede (tablet) modül kartları iki sütunlu ızgaraya geçer; bir satırdaki kartlar eşit yükseklikte, "Çalıştır"
 * düğmeleri hizalı. Telefonda iOS'taki gibi tek sütun.
 */
@Composable
fun AIHubView(onShowNotifications: () -> Unit) {
    var showTreatmentPlan by remember { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    var showSmile by remember { mutableStateOf(false) }
    var showXray by remember { mutableStateOf(false) }
    val credits = SmileCreditsStore.shared
    val caps = LocalAuthManager.current.yetenekler

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

    fun gorur(y: Yetki.Yuzey): Boolean = Yetki.gorur(caps, y)

    val moduller = buildList {
        if (gorur(Yetki.Yuzey.rontgen)) add(
            Modul(
                id = "xray", icon = "rectangle.split.3x1", tone = mutedHex("2dd4bf"),
                backdrop = ModuleCardBackdrop.image(R.drawable.card_xray),
                title = "Röntgen Analizi",
                badges = listOf(ModuleBadge.image, ModuleBadge.action),
                short = "Hastayı seçin, röntgeni yükleyin; diş numaralandırma ve bulgular hastanın dosyasına kaydedilir. Tedavi planı önerisi web'den hazırlanır.",
                gecikme = 0.04,
            ) { showXray = true }
        )
        if (gorur(Yetki.Yuzey.gulus)) add(
            Modul(
                id = "smile", icon = "mouth", tone = mutedHex("f472b6"),
                backdrop = ModuleCardBackdrop.image(R.drawable.card_smile),
                title = "Gülüş Simülasyonu",
                badges = listOf(ModuleBadge.image),
                extraBadge = creditBadge,
                short = "Tedavi sonrası gülüşü fotoğraf üzerinde gösterin: lamina, beyazlatma, eksik diş, ortodonti.",
                gecikme = 0.08,
            ) { showSmile = true }
        )
        if (gorur(Yetki.Yuzey.tedaviPlani)) add(
            Modul(
                id = "plan", icon = "mic", tone = mutedHex("fbbf24"),
                backdrop = ModuleCardBackdrop.image(R.drawable.card_plan),
                title = "Sesli Tedavi Planı",
                badges = listOf(ModuleBadge.action, ModuleBadge.write),
                short = "Planı yazın ya da söyleyin (“26 kanal, 34 implant”); kalemlere ayrılır, onayınızla kaydedilir.",
                gecikme = 0.12,
            ) { showTreatmentPlan = true }
        )
        if (gorur(Yetki.Yuzey.asistan)) add(
            Modul(
                id = "assistant", icon = "bubble.left.and.text.bubble.right", tone = DS.Colors.brandIcon,
                backdrop = ModuleCardBackdrop.chart,
                title = "Dentifay Asistan",
                badges = listOf(ModuleBadge.analysis, ModuleBadge.action),
                short = "Soru sorun, raporlar ve grafikler oluşturun.",
                gecikme = 0.16,
            ) { showAgent = true }
        )
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // İki sütun yalnız iki kart rahat sığınca; tek sütunda iOS'taki 640 dp sınırı.
            val sutun = sutunSayisi(minOf(maxWidth, IKI_SUTUN_EN) - 32.dp, MIN_KART, KART_ARASI, enFazla = 2)
            val enCok = if (sutun >= 2) IKI_SUTUN_EN else TEK_SUTUN_EN

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .icerikGenisligi(enCok)
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PageHeader(
                        title = "Analiz Merkezi",
                        subtitle = "Tahmin, risk ve analiz araçları — klinik verinizle çalışır",
                        modifier = Modifier.padding(top = 8.dp).appear(0.0),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ZilDugmesi(onShowNotifications)
                            ProfilDugmesi()
                        }
                    }

                    SectionLabel("Modüller", Modifier.padding(top = 10.dp))

                    ModulIzgarasi(moduller, sutun)
                }
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

/** Bir modül kartının içeriği (Swift `ModuleCard(...)` çağrısının argümanları). */
private class Modul(
    val id: String,
    val icon: String,
    val tone: Color,
    val backdrop: ModuleCardBackdrop?,
    val title: String,
    val badges: List<ModuleBadge>,
    val extraBadge: String? = null,
    val short: String,
    /** `.appear(delay:)` — kartın sabit gecikmesi (gizlenen kart ötekileri kaydırmaz). */
    val gecikme: Double,
    val action: () -> Unit,
)

/**
 * Kartlar: tek sütunda alt alta (iOS); iki sütunda satır satır, satırdaki kartlar eşit yükseklikte (`IntrinsicSize.Min`).
 */
@Composable
private fun ModulIzgarasi(moduller: List<Modul>, sutun: Int) {
    if (sutun <= 1) {
        Column(verticalArrangement = Arrangement.spacedBy(KART_ARASI)) {
            moduller.forEach { m -> key(m.id) { ModuleCard(m, Modifier.appear(m.gecikme)) } }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(KART_ARASI)) {
        moduller.chunked(sutun).forEach { satir ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(KART_ARASI),
            ) {
                satir.forEach { m ->
                    key(m.id) { ModuleCard(m, Modifier.weight(1f).fillMaxHeight().appear(m.gecikme)) }
                }
                repeat(sutun - satir.size) { Spacer(Modifier.weight(1f)) }
            }
        }
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

private val BASLIK_STILI = sys(16, FontWeight.SemiBold)
private val KISA_STILI = sys(12).copy(lineHeight = 16.sp)
private val ROZET_STILI = sys(10, FontWeight.SemiBold)

/**
 * Web `AiModuleCard`: cam kart (r 16, iç boşluk 20) · çift tonlu ikon · başlık + rozetler · kısa açıklama · "▶ Çalıştır".
 * Arka planda kartın sağ kısmında sola doğru eriyen soluk görsel + hafif cyan → mor yıkama. Kart yüksekliği dışarıdan
 * sabitlenirse (ızgara satırı) "Çalıştır" en alta oturur.
 */
@Composable
private fun ModuleCard(m: Modul, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .glassCard(16.dp)
            .plainClickable { Haptics.light(); m.action() }
    ) {
        if (m.backdrop != null) BackdropView(m.backdrop, Modifier.matchParentSize())

        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                // Web `CiftIkon size={30}`: kutusuz, modül renginde çift tonlu ikon.
                CiftIkon(m.icon, Modifier.padding(top = 2.dp), color = m.tone, size = 30.dp)

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BaslikVeRozetler(m.title, m.badges, m.extraBadge)
                    Text(m.short, Modifier.padding(top = 2.dp), style = KISA_STILI, color = DS.Colors.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            // Izgarada kart satırın yüksekliğine uzarsa düğme en alta iner (telefonda 0).
            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RunButton(m.title) { Haptics.light(); m.action() }
            }
        }
    }
}

/**
 * Web: başlık ve rozetler aynı satırda; sığmazsa rozetler TÜMÜYLE alt satıra iner (Swift `ViewThatFits`). Karar ölçülen
 * doğal genişliklerden — iç ölçüm (eşit yükseklikli ızgara) de aynı kararı verir.
 */
@Composable
private fun BaslikVeRozetler(title: String, badges: List<ModuleBadge>, extraBadge: String?) {
    Layout(
        content = {
            Text(title, style = BASLIK_STILI, color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            badges.forEach { b -> Badge(b.label, b.colors.first, b.colors.second) }
            if (extraBadge != null) Badge(extraBadge, DS.Colors.metal, DS.Colors.onSurfaceVariant)
        },
    ) { olculer, kisit ->
        val w = kisit.maxWidth
        val bosluk8 = 8.dp.roundToPx()
        val bosluk6 = 6.dp.roundToPx()
        val baslik = olculer.first()
        val rozetler = olculer.drop(1)
        val baslikDogal = baslik.maxIntrinsicWidth(Constraints.Infinity)
        val rozetEnleri = rozetler.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val tekSatir = rozetler.isEmpty() || baslikDogal.toLong() + rozetEnleri.sum() + bosluk8.toLong() * rozetler.size <= w

        if (tekSatir) {
            val baslikP = baslik.measure(Constraints(maxWidth = min(baslikDogal, w)))
            val rozetP = rozetler.map { it.measure(Constraints()) }
            val h = max(baslikP.height, rozetP.maxOfOrNull { it.height } ?: 0)
            val icerikEni = baslikP.width + rozetEnleri.sum() + bosluk8 * rozetler.size
            layout(kisit.constrainWidth(icerikEni), kisit.constrainHeight(h)) {
                baslikP.placeRelative(0, (h - baslikP.height) / 2)
                var x = baslikP.width + bosluk8
                rozetP.forEachIndexed { i, p ->
                    p.placeRelative(x, (h - p.height) / 2)
                    x += rozetEnleri[i] + bosluk8
                }
            }
        } else {
            val baslikP = baslik.measure(Constraints(maxWidth = w))
            val rozetP = rozetler.map { it.measure(Constraints(maxWidth = w)) }
            // Rozetler alt satırda (aralık 6); çok dar pencerede sığmayan bir alta akar.
            val konumlar = ArrayList<Pair<Int, Int>>(rozetP.size)
            var x = 0
            var y = baslikP.height + bosluk6
            var satirH = 0
            var enGenis = baslikP.width
            rozetP.forEachIndexed { i, p ->
                val en = rozetEnleri[i]
                if (x > 0 && x + en > w) {
                    x = 0
                    y += satirH + bosluk6
                    satirH = 0
                }
                konumlar += x to y
                x += en + bosluk6
                satirH = max(satirH, p.height)
                enGenis = max(enGenis, x - bosluk6)
            }
            val h = y + satirH
            layout(kisit.constrainWidth(enGenis), kisit.constrainHeight(h)) {
                baslikP.placeRelative(0, 0)
                rozetP.forEachIndexed { i, p -> p.placeRelative(konumlar[i].first, konumlar[i].second) }
            }
        }
    }
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
        Text(text, style = ROZET_STILI, color = color, maxLines = 1)
    }
}

private val CALISTIR_ZEMINI = relativeLinearGradient(listOf(0f to DS.Colors.purple500, 1f to DS.Colors.cyan400), Offset(0f, 0f), Offset(1f, 1f))
private val CALISTIR_GOLGESI = relativeLinearGradient(listOf(0.32f to Color.Transparent, 1f to hex("0a0e19").copy(alpha = 0.45f)), Offset(0f, 0f), Offset(1f, 1f))

/** Web `btn-primary-gradient`: mor → cyan, sağ altta hafif koyulaşma. */
@Composable
private fun RunButton(title: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .pressable(onClick = onClick)
            .semantics { contentDescription = "$title — Çalıştır" }
            .height(34.dp)
            .clip(shape)
            .background(CALISTIR_ZEMINI)
            .background(CALISTIR_GOLGESI)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("play.fill", size = 12.dp, tint = Color.White)
        Text("Çalıştır", style = sys(13, FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

private val ARKA_MASKE = relativeLinearGradient(
    listOf(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.85f), 0.7f to Color.Black),
    Offset(0f, 0.5f), Offset(1f, 0.5f),
)
private val ARKA_YIKAMA = relativeLinearGradient(
    listOf(0f to DS.Colors.cyan400.copy(alpha = 0.06f), 0.5f to Color.Transparent, 1f to DS.Colors.purple500.copy(alpha = 0.09f)),
    Offset(0f, 1f), Offset(1f, 0f),
)

/**
 * Soluk arka plan: genişliğin sağ %62'si, sola doğru maskeyle karta karışır. Metin mobilde tüm genişliğe yayıldığı için
 * opaklık webdekinden düşük tutulur (okunurluk).
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
                        drawRect(ARKA_MASKE, blendMode = BlendMode.DstIn)
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
        Box(Modifier.fillMaxSize().background(ARKA_YIKAMA))
    }
}

private val GRAFIK_DEGERLERI = listOf(38.0, 52.0, 45.0, 61.0, 56.0, 70.0, 64.0, 77.0, 72.0, 86.0)
private val GRAFIK_EGILIM = GRAFIK_DEGERLERI.indices.map { 36 + it * 5.4 }
private val GRAFIK_CUBUK = mutedHex("38bdf8").copy(alpha = 0.5f)
private val GRAFIK_CIZGI = mutedHex("c4b5fd").copy(alpha = 0.75f)
private val GRAFIK_KILAVUZ = Color.White.copy(alpha = 0.12f)
private val GRAFIK_KILAVUZ_DEGERLERI = listOf(25.0, 50.0, 75.0, 100.0)

/**
 * Dentifay Asistan kartının arka planı: asistanın ürettiği türden soluk bir grafik (aylık çubuklar + eğilim çizgisi).
 * Eksen yazısı yok; yalnız hafif yatay kılavuzlar. Tek `Canvas`, sabit veri.
 */
@Composable
private fun ModuleChartBackdrop(modifier: Modifier) {
    Canvas(modifier.alpha(0.55f)) {
        fun y(v: Double): Float = (size.height * (1 - v / 100.0)).toFloat()
        // Kılavuzlar: 25 · 50 · 75 · 100
        for (g in GRAFIK_KILAVUZ_DEGERLERI) {
            drawLine(GRAFIK_KILAVUZ, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 0.6.dp.toPx())
        }
        val band = size.width / GRAFIK_DEGERLERI.size
        val barW = band * 0.56f
        val r = 2.dp.toPx()
        GRAFIK_DEGERLERI.forEachIndexed { i, v ->
            val cx = band * (i + 0.5f)
            val top = y(v)
            drawRoundRect(GRAFIK_CUBUK, Offset(cx - barW / 2, top), Size(barW, size.height - top), CornerRadius(r, r))
        }
        // Eğilim çizgisi (catmull-rom)
        val pts = GRAFIK_EGILIM.mapIndexed { i, t -> Offset(band * (i + 0.5f), y(t)) }
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
        drawPath(path, GRAFIK_CIZGI, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AIHubViewPreview() {
    PreviewEnv { AIHubView(onShowNotifications = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun AIHubViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { AIHubView(onShowNotifications = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun AIHubViewTabletPortraitPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) { AIHubView(onShowNotifications = {}) }
}
