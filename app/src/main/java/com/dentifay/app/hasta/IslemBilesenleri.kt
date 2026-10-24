package com.dentifay.app.hasta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.TurkceYerel
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.YetkiliEylem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Tedavi işlemleri pencerelerinin ortak form parçaları — SwiftUI sistem denetimlerinin (Picker .segmented / .menu, DatePicker,
 * Stepper, confirmationDialog, ProgressView) bu ekranlardaki görünümüyle Compose karşılıkları. Hepsi yazı ölçeği 1,3'te taşmaz:
 * sabit yükseklik yerine en az yükseklik, metinler tek satırda kırpılır.
 */

/** Araç çubuğu düğmesi rengi: uygulamanın marka camgöbeği (iOS sistem mavisi yerine; kredi ekranlarıyla aynı). */
internal val ARAC_MAVISI get() = DS.Colors.cyan400

/**
 * `NavigationStack` + `.navigationTitle(…)` (satır içi) + araç çubuğu: solda iptal, ortada başlık, sağda onay. Dar pencerede başlık
 * iki satıra iner, ancak o da yetmezse kırpılır (düğmeler hiç kırpılmaz); yazı ölçeğiyle yükseklik büyür.
 */
@Composable
internal fun PencereBasligi(
    baslik: String,
    solMetni: String? = null,
    solEylem: () -> Unit = {},
    sagMetni: String? = null,
    sagEtkin: Boolean = true,
    sagCalisiyor: Boolean = false,
    sagEylem: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Layout(
            content = {
                Box { if (solMetni != null) AracDugmesi(solMetni, kalin = false, etkin = true, eylem = solEylem) }
                // Telefonda "Yeni Tedavi Planı Hazırla" + "PLAN OLARAK KAYDET" yan yana sığmıyor, başlık "Yeni Tedavi Plan…" oluyordu.
                Text(
                    baslik, style = sys(17, FontWeight.SemiBold).copy(lineHeight = 20.sp), color = DS.Colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Box {
                    when {
                        sagCalisiyor -> IslemIlerleme(renk = ARAC_MAVISI)
                        sagMetni != null -> AracDugmesi(sagMetni, kalin = true, etkin = sagEtkin, eylem = sagEylem)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) { olculer, c ->
            val serbest = c.copy(minWidth = 0, minHeight = 0)
            val sol = olculer[0].measure(serbest)
            val sag = olculer[2].measure(serbest)
            val bosluk = 10.dp.roundToPx()
            val w = c.maxWidth
            val yan = maxOf(sol.width, sag.width)
            // Önce iOS gibi tam ortada; yer kalmıyorsa düğmelerin arasındaki boşlukta.
            val ortali = w - 2 * (yan + bosluk)
            val ortadaMi = ortali >= w / 3
            val baslikEn = (if (ortadaMi) ortali else w - sol.width - sag.width - 2 * bosluk).coerceAtLeast(0)
            val orta = olculer[1].measure(serbest.copy(maxWidth = baslikEn))
            val h = maxOf(c.minHeight, sol.height, sag.height, orta.height)
            layout(w, h) {
                sol.placeRelative(0, (h - sol.height) / 2)
                val bx = if (ortadaMi) (w - orta.width) / 2 else sol.width + bosluk + (baslikEn - orta.width) / 2
                orta.placeRelative(bx, (h - orta.height) / 2)
                sag.placeRelative(w - sag.width, (h - sag.height) / 2)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
    }
}

@Composable
private fun AracDugmesi(metin: String, kalin: Boolean, etkin: Boolean, eylem: () -> Unit) {
    Text(
        metin,
        Modifier
            .alpha(if (etkin) 1f else 0.35f)
            .plainClickable(enabled = etkin, onClick = eylem)
            .padding(vertical = 8.dp),
        style = sys(16, if (kalin) FontWeight.SemiBold else FontWeight.Normal), color = ARAC_MAVISI, maxLines = 1, softWrap = false,
    )
}

private val SEGMENT_ZEMIN = hex("767680").copy(alpha = 0.24f)
private val SEGMENT_SECILI = hex("636366")

/** `Picker(…).pickerStyle(.segmented)` — eşit genişlikte bölümler. */
@Composable
internal fun IslemSegmenti(
    secenekler: List<String>,
    secili: Int,
    sec: (Int) -> Unit,
    modifier: Modifier = Modifier,
    etkin: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(SEGMENT_ZEMIN)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        secenekler.forEachIndexed { i, s ->
            val bu = i == secili
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (bu) SEGMENT_SECILI else Color.Transparent)
                    .plainClickable(enabled = etkin) {
                        if (!bu) {
                            Haptics.selection()
                            sec(i)
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s, style = sys(13, if (bu) FontWeight.SemiBold else FontWeight.Medium), color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** `Picker(…).pickerStyle(.menu)` — seçili etiket + ok; dokununca liste. `secenekler`: değer → etiket. */
@Composable
internal fun IslemMenusu(
    secenekler: List<Pair<String, String>>,
    secili: String,
    sec: (String) -> Unit,
    modifier: Modifier = Modifier,
    renk: Color = DS.Colors.cyan400,
    yazi: TextStyle = sys(15),
) {
    var acik by remember { mutableStateOf(false) }
    val etiket = secenekler.firstOrNull { it.first == secili }?.second ?: secenekler.firstOrNull()?.second ?: ""
    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .plainClickable { acik = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(etiket, Modifier.weight(1f, fill = false), style = yazi, color = renk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DfyIcon("chevron.down", size = 11.dp, tint = renk)
        }
        DropdownMenu(
            expanded = acik,
            onDismissRequest = { acik = false },
            containerColor = DS.Colors.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            secenekler.forEach { (deger, ad) ->
                DropdownMenuItem(
                    text = { Text(ad, style = sys(15), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        acik = false
                        if (deger != secili) sec(deger)
                    },
                    trailingIcon = if (deger == secili) ({ DfyIcon("checkmark", size = 14.dp, tint = DS.Colors.onSurface) }) else null,
                )
            }
        }
    }
}

private val TARIH_DUGMESI: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("tr-TR"))

/** `DatePicker(baslik, selection:, displayedComponents: .date)` (kompakt): başlık + tarih düğmesi → takvim penceresi. Takvim günü UTC'dedir. */
@Composable
internal fun IslemTarihi(
    baslik: String?,
    tarih: LocalDate,
    sec: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    yazi: TextStyle = sys(15),
) {
    var acik by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (baslik != null) {
            Text(baslik, Modifier.weight(1f), style = yazi, color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            TARIH_DUGMESI.format(tarih),
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .plainClickable { acik = true }
                .padding(horizontal = 11.dp, vertical = 7.dp),
            style = yazi, color = DS.Colors.cyan400, maxLines = 1,
        )
    }
    if (acik) {
        val durum = rememberDatePickerState(initialSelectedDateMillis = tarih.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { acik = false },
            confirmButton = {
                TextButton(onClick = {
                    durum.selectedDateMillis?.let { sec(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    acik = false
                }) { Text("Tamam", style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400) }
            },
            dismissButton = { TextButton(onClick = { acik = false }) { Text("Vazgeç", style = sys(15), color = DS.Colors.onSurfaceVariant) } },
            colors = DatePickerDefaults.colors(containerColor = DS.Colors.surfaceContainerHigh),
        ) {
            // Başlık/özet yok: Material metinleri cihaz dilinde ("Select date") çıkıyordu; takvim Türkçe çizilir.
            TurkceYerel {
                DatePicker(
                    state = durum, title = null, headline = null, showModeToggle = false,
                    colors = DatePickerDefaults.colors(containerColor = DS.Colors.surfaceContainerHigh),
                )
            }
        }
    }
}

/** `Stepper(etiket, value:, in:)` — etiket + − / + kapsülü; sınırda sönük. */
@Composable
internal fun IslemAdimi(
    etiket: String,
    deger: Int,
    aralik: IntRange,
    degisti: (Int) -> Unit,
    modifier: Modifier = Modifier,
    yazi: TextStyle = sys(14),
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(etiket, style = yazi, color = DS.Colors.onSurface, maxLines = 1)
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdimDugmesi("minus", deger > aralik.first) { degisti(deger - 1) }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 7.dp).background(DS.Colors.hairlineStrong))
            AdimDugmesi("plus", deger < aralik.last) { degisti(deger + 1) }
        }
    }
}

@Composable
private fun AdimDugmesi(ikon: String, etkin: Boolean, eylem: () -> Unit) {
    Box(
        Modifier
            .size(width = 44.dp, height = 32.dp)
            .alpha(if (etkin) 1f else 0.35f)
            .plainClickable(enabled = etkin) {
                Haptics.selection()
                eylem()
            },
        contentAlignment = Alignment.Center,
    ) { DfyIcon(ikon, size = 13.dp, tint = DS.Colors.onSurface) }
}

/** `ProgressView()` — küçük dönen gösterge. */
@Composable
internal fun IslemIlerleme(modifier: Modifier = Modifier, renk: Color = DS.Colors.cyan400, boyut: Dp = 18.dp) {
    CircularProgressIndicator(modifier.size(boyut), color = renk, strokeWidth = 2.dp, trackColor = Color.Transparent)
}

/** Bölüm/alan etiketi (Swift `alanBasligi` — Türkçe büyük harf, 10 pt kalın, 0,8 aralık). */
@Composable
internal fun AlanBasligi(metin: String, modifier: Modifier = Modifier) {
    Text(metin.uppercase(Locale.forLanguageTag("tr-TR")), modifier, style = sys(10, FontWeight.Bold, tracking = 0.8), color = DS.Colors.onSurfaceVariant)
}

/** `confirmationDialog` seçeneği. */
internal class OnaySecenegi(val baslik: String, val yikici: Boolean = false, val eylem: () -> Unit)

/**
 * `.confirmationDialog(baslik, titleVisibility: .visible) { … } message: { … }` — ortada kart: başlık, ileti, seçenekler, "Vazgeç".
 * Pencerelerin içinden açılır (alt sayfanın üstünde); tablette de ortada ve en çok 420 dp.
 */
@Composable
internal fun IslemOnayi(
    acik: Boolean,
    baslik: String,
    secenekler: List<OnaySecenegi>,
    kapat: () -> Unit,
    mesaj: String? = null,
    vazgecMetni: String = "Vazgeç",
) {
    if (!acik) return
    Dialog(onDismissRequest = kapat, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = kapat)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DS.Colors.surfaceContainerHigh)
                    .border(1.dp, DS.Colors.hairlineStrong, RoundedCornerShape(18.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(baslik, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
                if (mesaj != null) Text(mesaj, style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                secenekler.forEach { s ->
                    OnayDugmesi(s.baslik, if (s.yikici) DS.Colors.danger else DS.Colors.cyan400) {
                        // Önce eylem (bekleyen seçimi okuyabilsin), sonra kapat.
                        s.eylem()
                        kapat()
                    }
                }
                OnayDugmesi(vazgecMetni, DS.Colors.onSurface, kalin = true, eylem = kapat)
            }
        }
    }
}

@Composable
private fun OnayDugmesi(baslik: String, renk: Color, kalin: Boolean = false, eylem: () -> Unit) {
    val sekil = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(sekil)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, DS.Colors.hairline, sekil)
            .plainClickable(onClick = eylem)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(baslik, style = sys(15, if (kalin) FontWeight.SemiBold else FontWeight.Normal), color = renk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Pencerelerin alt düğme çifti: "Vazgeç" (ikincil) + birincil eylem (marka gradyanı ya da tehlike rengi, çalışırken gösterge).
 * Etkin değilken %40 sönük (Swift `YetkiliEylem.sonukOpaklik`).
 */
@Composable
internal fun IkiliDugme(
    kaydetMetni: String,
    etkin: Boolean,
    calisiyor: Boolean,
    vazgec: () -> Unit,
    kaydet: () -> Unit,
    modifier: Modifier = Modifier,
    tehlike: Boolean = false,
    vazgecMetni: String = "Vazgeç",
    yukseklik: Dp = 46.dp,
) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(onClick = vazgec, modifier = Modifier.weight(1f), height = yukseklik) { ButtonLabel(vazgecMetni) }
        BirincilDugme(kaydetMetni, etkin && !calisiyor, calisiyor, kaydet, Modifier.weight(1f), tehlike, yukseklik)
    }
}

/** Swift'teki `Button { … } label: { HStack { ProgressView; Text } .frame(height: 46).background(gradyan) }.buttonStyle(PressableStyle())`. */
@Composable
internal fun BirincilDugme(
    metin: String,
    etkin: Boolean,
    calisiyor: Boolean,
    eylem: () -> Unit,
    modifier: Modifier = Modifier,
    tehlike: Boolean = false,
    yukseklik: Dp = 46.dp,
) {
    val sekil = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = yukseklik)
            .alpha(if (etkin || calisiyor) 1f else YetkiliEylem.SONUK_OPAKLIK)
            .pressable(enabled = etkin, onClick = eylem)
            .clip(sekil)
            .background(if (tehlike) androidx.compose.ui.graphics.SolidColor(DS.Colors.danger) else DS.Gradients.primaryGradient, sekil)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (calisiyor) IslemIlerleme(renk = Color.White, boyut = 16.dp)
        Text(metin, style = sys(15, FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 520)
@Composable
private fun IslemBilesenleriPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            var s by remember { mutableStateOf(0) }
            IslemSegmenti(listOf("%", "TL", "Hedef toplam"), s, { s = it })
            var h by remember { mutableStateOf("a") }
            IslemMenusu(listOf("" to "Hekim seçin", "a" to "Dt. Selin Demir", "b" to "Dt. Deneme Hekim"), h, { h = it })
            IslemTarihi("Tedavi Tarihi", LocalDate.of(2026, 10, 24), {})
            var n by remember { mutableStateOf(2) }
            IslemAdimi("×$n", n, 1..99, { n = it })
            AlanBasligi("Eklenecek kalemlerin hekimi *")
            IkiliDugme("Kaydet", etkin = true, calisiyor = false, vazgec = {}, kaydet = {})
            IkiliDugme("Sil", etkin = true, calisiyor = true, vazgec = {}, kaydet = {}, tehlike = true)
        }
    }
}
