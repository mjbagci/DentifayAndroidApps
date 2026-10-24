package com.dentifay.app.smile

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.R
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val trLocale: Locale = Locale.forLanguageTag("tr-TR")

/** `createdAt.formatted(.dateTime.day().month(...).year().hour().minute())` (tr_TR). */
internal fun smileDateText(date: Instant, wideMonth: Boolean): String =
    DateTimeFormatter.ofPattern(if (wideMonth) "d MMMM yyyy HH:mm" else "d MMM yyyy HH:mm", trLocale)
        .withZone(ZoneId.systemDefault())
        .format(date)

/**
 * Kaydedilen gülüş simülasyonları (Geçmiş). Hasta onayıyla kaydedilen her
 * simülasyonun tam kare önce/sonra fotoğrafları cihazda saklanır; buradan
 * yeniden karşılaştırılır, hastaya tekrar gönderilir ya da silinir.
 */
@Composable
fun SmileHistoryView(onDismiss: () -> Unit) {
    val store = SmileHistoryStore.shared
    // NavigationStack yolu: seçili kayıt = ayrıntı ekranı.
    var selected by remember { mutableStateOf<SmileHistoryItem?>(null) }
    LaunchedEffect(Unit) { store.load() }
    BackHandler(enabled = selected != null) { selected = null }

    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally(Motion.easeInOut(300)) { it } togetherWith slideOutHorizontally(Motion.easeInOut(300)) { -it / 3 }
            } else {
                slideInHorizontally(Motion.easeInOut(300)) { -it / 3 } togetherWith slideOutHorizontally(Motion.easeInOut(300)) { it }
            }
        },
        label = "historyNav",
        modifier = Modifier.fillMaxSize().background(DS.Colors.background),
    ) { item ->
        if (item != null) {
            SmileHistoryDetailView(item = item, onBack = { selected = null })
        } else {
            Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
                SmileInlineNavBar("Geçmiş", onTrailing = onDismiss)
                if (store.items.isEmpty()) {
                    HistoryEmptyState()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(store.items, key = { it.id.toString() }) { item ->
                            HistoryTile(item, Modifier.plainClickable { selected = item })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        DfyIcon("clock.arrow.circlepath", size = 40.dp, tint = DS.Colors.cyan400.copy(alpha = 0.7f))
        Text("Henüz kayıtlı simülasyon yok", style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
        Text(
            "Sonucu hastaya gönderirken Kayıt bölümündeki saklama onayı kutusunu işaretleyip “Geçmişe kaydet”e basın; simülasyon burada listelenir.",
            Modifier.padding(horizontal = 32.dp),
            style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistoryTile(item: SmileHistoryItem, modifier: Modifier = Modifier) {
    val store = SmileHistoryStore.shared
    val thumb = remember(item.id) { store.thumbnail(item) }
    Column(modifier.glassCard(18.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(14.dp))) {
            if (thumb != null) {
                Image(remember(thumb) { thumb.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(DS.Colors.surfaceContainer))
            }
            if (item.savedToPatientFile) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(6.dp)
                        .semantics { contentDescription = "Hasta dosyasında" },
                ) { DfyIcon("folder.fill.badge.person.crop", size = 11.dp, tint = Color.White) }
            }
        }
        Text(item.displayName, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1)
        Text("${item.designTitle} · ${item.tierTitle}", style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1)
        Text(smileDateText(item.createdAt, wideMonth = false), style = sys(10), color = DS.Colors.outline)
    }
}

/**
 * Kayıtlı simülasyonun ayrıntısı: kaydırmalı / yan yana karşılaştırma,
 * hastaya yeniden gönderme, silme.
 */
@Composable
fun SmileHistoryDetailView(item: SmileHistoryItem, onBack: () -> Unit) {
    var images by remember { mutableStateOf<Pair<Bitmap, Bitmap>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var sliderMode by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        val loaded = SmileHistoryStore.shared.images(item)
        if (loaded != null) {
            val (b, a) = loaded
            images = b to a
        } else loadFailed = true
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        SmileInlineNavBar(item.displayName, leading = { SmileBackButton("Geçmiş", onBack) }, trailingText = null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val imgs = images
            if (imgs != null) {
                val pair = remember(imgs, zoomed) {
                    if (!zoomed) imgs else {
                        val b = imgs.first.croppedTo(item.zoomRect)
                        val a = imgs.second.croppedTo(item.zoomRect)
                        if (b != null && a != null) b to a else imgs
                    }
                }
                SmileSegmented(
                    options = if (item.zoomRect != null) listOf(false, true) else listOf(false),
                    selected = zoomed, onSelect = { zoomed = it }, modifier = Modifier.fillMaxWidth(),
                    label = { if (it) "Gülüş" else "Tam fotoğraf" }, animationMs = 200,
                )

                Crossfade(sliderMode, animationSpec = Motion.easeInOut(200), label = "historyMode") { slider ->
                    if (slider) {
                        // `.id(zoomed)`: görünüm değişince ipucu yeniden oynar.
                        androidx.compose.runtime.key(zoomed) {
                            BeforeAfterSlider(pair.first, pair.second, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HistoryCompareTile(pair.first, "ÖNCE", Modifier.weight(1f))
                            HistoryCompareTile(pair.second, "SONRA", Modifier.weight(1f))
                        }
                    }
                }

                SmileSegmented(
                    options = listOf(true, false), selected = sliderMode, onSelect = { sliderMode = it },
                    modifier = Modifier.fillMaxWidth(), animationMs = 200,
                    label = { if (it) "Kaydır" else "Yan yana" },
                    icon = { if (it) "slider.horizontal.below.rectangle" else "rectangle.split.2x1" },
                )
            } else if (loadFailed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DfyIcon("exclamationmark.triangle.fill", size = 13.dp, tint = DS.Colors.orange400)
                    Text(
                        "Fotoğraflar açılamadı (cihaz kilitliyken okunamaz ya da silinmiş olabilir).",
                        style = sys(13), color = DS.Colors.orange400,
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) { SmileSpinner() }
            }

            Column(Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryInfoRow("person.fill", item.displayName)
                HistoryInfoRow("wand.and.stars", "${item.designTitle} · ${item.tierTitle}")
                if (item.designSummary.isNotEmpty()) HistoryInfoRow("slider.horizontal.3", item.designSummary)
                HistoryInfoRow("calendar", smileDateText(item.createdAt, wideMonth = true))
                HistoryInfoRow(
                    if (item.savedToPatientFile) "folder.fill.badge.person.crop" else "iphone",
                    if (item.savedToPatientFile) "Hastanın Dentifay dosyasında da kayıtlı" else "Yalnız bu cihazda kayıtlı",
                )
            }

            Text("Yapay zeka simülasyonudur; gerçek tedavi sonucu farklılık gösterebilir.", style = sys(11), color = DS.Colors.outline)

            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(enabled = images != null) { showShare = true }
                    .alpha(if (images != null) 1f else 0.45f)
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(DS.Gradients.primaryGradient),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("paperplane.fill", size = 15.dp, tint = Color.White)
                Text("Hastaya Gönder", style = sys(15, FontWeight.Bold), color = Color.White)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable { confirmDelete = true }
                    .height(44.dp)
                    .border(1.dp, DS.Colors.error.copy(alpha = 0.4f), CircleShape),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("trash", size = 14.dp, tint = DS.Colors.error)
                Text("Sil", style = sys(14, FontWeight.SemiBold), color = DS.Colors.error)
            }
        }
    }

    DfySheet(isPresented = showShare && images != null, onDismiss = { showShare = false }) { dismiss ->
        images?.let { (b, a) ->
            SmileShareSheet(
                content = SmileShareContent(
                    before = b, after = a, zoomRect = item.zoomRect,
                    designTitle = item.designTitle, designSummary = item.designSummary,
                    tierTitle = item.tierTitle, createdAt = item.createdAt,
                ),
                historyItem = SmileHistoryStore.shared.items.firstOrNull { it.id == item.id } ?: item,
                onDismiss = dismiss,
            )
        }
    }

    DfyAlert(
        isPresented = confirmDelete,
        title = "Bu simülasyon silinsin mi?",
        message = if (item.savedToPatientFile) "Hastanın Dentifay dosyasındaki kopya silinmez." else "Fotoğraflar bu cihazdan kalıcı olarak silinir.",
        confirmText = "Cihazdan sil",
        onConfirm = {
            confirmDelete = false
            SmileHistoryStore.shared.delete(item)
            onBack()
        },
        dismissText = "Vazgeç",
        onDismiss = { confirmDelete = false },
        destructive = true,
    )
}

@Composable
private fun HistoryCompareTile(image: Bitmap, label: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp))) {
        Image(
            remember(image) { image.asImageBitmap() }, null,
            Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
        )
        Text(
            label,
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = sys(10, FontWeight.Bold, tracking = 1), color = Color.White,
        )
    }
}

@Composable
private fun HistoryInfoRow(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            DfyIcon(icon, size = 13.dp, tint = DS.Colors.cyan400)
        }
        Text(text, style = sys(13), color = DS.Colors.onSurface)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileHistoryViewPreview() {
    PreviewEnv { SmileHistoryView(onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileHistoryEmptyPreview() {
    PreviewEnv {
        Column(Modifier.fillMaxSize()) {
            SmileInlineNavBar("Geçmiş")
            HistoryEmptyState()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 400)
@Composable
private fun HistoryCompareTilePreview() {
    PreviewEnv {
        val b = rememberSampleBitmap(R.drawable.smile_before)
        val a = rememberSampleBitmap(R.drawable.smile_after)
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryCompareTile(b, "ÖNCE", Modifier.weight(1f))
            HistoryCompareTile(a, "SONRA", Modifier.weight(1f))
        }
    }
}
