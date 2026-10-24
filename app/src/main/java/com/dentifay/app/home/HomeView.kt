package com.dentifay.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.HekimSecici
import com.dentifay.app.core.Unvan
import com.dentifay.app.data.Appointment
import com.dentifay.app.data.Doctor
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.notifications.NotificationsStore
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.NumberTicker
import com.dentifay.app.theme.TurkceYerel
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.blockEntrance
import com.dentifay.app.theme.horizontalGradient
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val trLocale: Locale = Locale.forLanguageTag("tr-TR")
private val GUN_ADI: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", trLocale)

private val slotHeight = 52.dp
private val timeColumnWidth = 50.dp

// Takvim penceresi dinamik: varsayılan 08:00–19:00, daha erken başlayan / geç biten randevu varsa GENİŞLER — yoksa
// erken/geç kartlar başlığın üstüne taşar.
private const val defaultStartHour = 8
private const val defaultEndHour = 19

/** Gün şeridi çip ölçüleri (Swift `.frame(width:height:)`) — sabit oldukları için konumlar ölçmeden hesaplanır. */
private val CIP_GENISLIGI = 48.dp
private val BUGUN_CIP_GENISLIGI = 56.dp
private val CIP_ARALIGI = 8.dp

/** Gün programının tablet tavanı (çizelge pencereyi kullanır, ortalanır). */
private val CIZELGE_GENISLIGI = 1040.dp

/**
 * Ana sayfa: profil başlığı, gün şeridi, hekim seçici ve günün programı (zaman ızgarası). Hekim listesi SEÇİLİ günün takvim
 * sütunlarıdır (o gün kapalı olanlar soluk + "Bu tarihte çalışmıyor"); seçim dizinle değil `apiUserId` ile tutulur — gün
 * değişip liste yeniden gelince seçim kaymaz (M-E1.3.7).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeView(
    onShowNotifications: () -> Unit,
    selectedAppointment: Appointment?,
    onSelectAppointment: (Appointment?) -> Unit,
) {
    val authManager = LocalAuthManager.current
    val dataManager = LocalDataManager.current
    val pencere = LocalPencere.current
    val onizleme = LocalInspectionMode.current
    val notifications = remember { NotificationsStore.shared }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    /** Seçili hekim `apiUserId` ile — açılışta önceden yüklenen listeden (gün listesi gelince `korunanSecim` ile doğrulanır). */
    var selectedDoctorId by remember { mutableStateOf(HekimSecici.korunanSecim(null, dataManager.doctors, authManager.currentUser?.id)) }
    /** Seçili günün hekim listesi kaç kez geldi — randevular o günün listesi geldikten sonra istenir (eski günün seçimiyle değil). */
    var hekimYuklemesi by remember { mutableIntStateOf(0) }

    // Swift `.task` + `.onChange(of: selectedDate)`: hekim sütunları SEÇİLİ güne göredir → gün değişince liste yeniden yüklenir,
    // seçim korunur. Yeni gün eski günün yüklemesini iptal eder (son seçilen gün kazanır).
    LaunchedEffect(selectedDate) {
        if (onizleme) return@LaunchedEffect
        dataManager.fetchDoctors(selectedDate)
        selectedDoctorId = HekimSecici.korunanSecim(selectedDoctorId, dataManager.doctors, authManager.currentUser?.id)
        hekimYuklemesi += 1
    }
    // Randevular: gün listesi gelince ve hekim değişince (`.onChange(of: selectedDoctorId)`); yeni istek eskisini iptal eder.
    LaunchedEffect(hekimYuklemesi, selectedDoctorId) {
        if (hekimYuklemesi == 0) return@LaunchedEffect
        dataManager.fetchAppointments(selectedDate, selectedDoctorId)
    }
    LaunchedEffect(Unit) {
        if (!onizleme) notifications.refreshCounts()
    }

    val doctorName = authManager.currentUser?.let { Unvan.oturumAdi(it.name, it.role) } ?: "Hekim"

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                // iPhone'da çentik doğal boşluk verir; Android'in ince durum çubuğunda Stok sayfasıyla aynı 8 dp.
                .padding(top = 8.dp)
                // Telefonda alt çubuk; geniş pencerede yan ray — altta yalnız sistem çubuğu boşluğu.
                .then(if (pencere.genis) Modifier.navigationBarsPadding().padding(bottom = 32.dp) else Modifier.padding(bottom = 120.dp)),
        ) {
            Column(Modifier.icerikGenisligi(CIZELGE_GENISLIGI), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileHeader(
                    doctorName = doctorName,
                    clinicName = authManager.organizationName,
                    onNotificationTap = onShowNotifications,
                    modifier = Modifier.padding(horizontal = 16.dp).appear(0.0),
                )
                CompactDateStrip(
                    selectedDate = selectedDate,
                    onSelectDate = { selectedDate = it },
                    onOpenPicker = { showDatePicker = true },
                    modifier = Modifier.appear(0.06),
                )
                DoctorSelector(
                    doctors = dataManager.doctors,
                    selectedDoctorId = selectedDoctorId,
                    onSelect = { selectedDoctorId = it },
                    modifier = Modifier.appear(0.12),
                )
                DayCalendarSection(
                    appointments = dataManager.appointments,
                    isLoading = dataManager.isLoadingAppointments,
                    isViewingToday = selectedDate == LocalDate.now(),
                    onSelectAppointment = onSelectAppointment,
                    modifier = Modifier.appear(0.18),
                )
            }
        }
    }

    DfySheet(isPresented = showDatePicker, onDismiss = { showDatePicker = false }, containerColor = DS.Colors.background) { dismiss ->
        DatePickerSheet(selectedDate = selectedDate, onDateChange = { selectedDate = it }, onDone = dismiss)
    }
}

// MARK: - Ay günleri modeli

private data class DayChip(val id: String, val label: String, val dayNumber: Int, val date: LocalDate, val isToday: Boolean)

private fun chipId(date: LocalDate) = "${date.year}-${date.monthValue}-${date.dayOfMonth}"

private fun monthDays(displayedMonth: LocalDate, today: LocalDate): List<DayChip> {
    val ym = YearMonth.from(displayedMonth)
    return (1..ym.lengthOfMonth()).map { day ->
        val date = ym.atDay(day)
        val name = date.format(GUN_ADI).upperTr()
        val isToday = date == today
        DayChip(chipId(date), if (isToday) "BUGÜN" else name.take(3), day, date, isToday)
    }
}

// MARK: - Kompakt tarih şeridi

/**
 * Ayın günleri yatay şeritte; bugün ekrandan çıkınca / uzak bir gün seçiliyken solda sabit "BUGÜN". Bugünün çipinin
 * konumu kaydırma değerinden HESAPLANIR (çip genişlikleri sabit) — kaydırmada yerleşim izlenmez, yeniden oluşturma
 * yalnız eşik geçilince olur.
 */
@Composable
private fun CompactDateStrip(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val today = LocalDate.now()
    val days = remember(YearMonth.from(selectedDate), today) { monthDays(selectedDate, today) }
    val todayIndex = remember(days) { days.indexOfFirst { it.isToday } }
    var isTodayOffScreen by remember { mutableStateOf(false) }
    var viewportPx by remember { mutableIntStateOf(0) }

    val isTodayInDisplayedMonth = YearMonth.from(today) == YearMonth.from(selectedDate)
    val isTodayFarFromSelected = abs(ChronoUnit.DAYS.between(today, selectedDate)) >= 3
    val shouldShowPinnedToday = !isTodayInDisplayedMonth || isTodayOffScreen || isTodayFarFromSelected
    val padding = rememberUpdatedState(if (shouldShowPinnedToday) 8.dp else 16.dp)
    val stripPadding by animateDpAsState(padding.value, Motion.easeInOut(150), label = "stripPad")

    /** Çipin şerit içeriğindeki sol kenarı (iç boşluk dahil). */
    fun chipX(index: Int): Dp =
        padding.value + (CIP_GENISLIGI + CIP_ARALIGI) * index +
            (if (todayIndex in 0 until index) BUGUN_CIP_GENISLIGI - CIP_GENISLIGI else 0.dp)

    fun chipW(index: Int): Dp = if (index == todayIndex) BUGUN_CIP_GENISLIGI else CIP_GENISLIGI

    /** Çipi şeridin ortasına kaydırır (`proxy.scrollTo(id, anchor: .center)`). */
    suspend fun centerChip(index: Int) {
        if (index < 0 || viewportPx == 0) return
        val merkez = with(density) { (chipX(index) + chipW(index) / 2).toPx() }
        val hedef = (merkez - viewportPx / 2f).roundToInt().coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(hedef, Motion.default())
    }

    // Bugünün çipinin görünür sol kenarı (Swift `TodayMinXKey`; çip yoksa 100) — eşik 16 / sabitliyken 72 (titreme olmasın).
    LaunchedEffect(todayIndex, density) {
        snapshotFlow {
            if (todayIndex < 0) 100f else chipX(todayIndex).value - scrollState.value / density.density
        }.collect { minX ->
            val threshold = if (isTodayOffScreen) 72f else 16f
            val shouldPin = minX < threshold
            if (shouldPin != isTodayOffScreen) isTodayOffScreen = shouldPin
        }
    }

    // İlk görünüşte bugün ortalanır (`onAppear` + 0,1 sn); sonra seçilen gün (`onChange(of: selectedDate)`).
    var oncekiTarih by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(selectedDate) {
        val ilk = oncekiTarih == null
        oncekiTarih = selectedDate
        if (ilk) {
            delay(100)
            centerChip(todayIndex)
        } else {
            withFrameNanos { }   // ay değiştiyse yeni çipler yerleşsin
            centerChip(days.indexOfFirst { it.date == selectedDate })
        }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = shouldShowPinnedToday,
            enter = fadeIn(Motion.easeInOut(150)) + expandHorizontally(Motion.easeInOut(150)),
            exit = fadeOut(Motion.easeInOut(150)) + shrinkHorizontally(Motion.easeInOut(150)),
        ) {
            PinnedTodayButton(Modifier.padding(start = 16.dp, end = 4.dp)) { onSelectDate(LocalDate.now()) }
        }

        Box(Modifier.weight(1f).onSizeChanged { viewportPx = it.width }) {
            Row(
                Modifier.horizontalScroll(scrollState).padding(horizontal = stripPadding),
                horizontalArrangement = Arrangement.spacedBy(CIP_ARALIGI),
            ) {
                days.forEach { chip ->
                    key(chip.id) {
                        DayChipView(chip = chip, isSelected = chip.date == selectedDate, onClick = { onSelectDate(chip.date) })
                    }
                }
            }
        }

        Box(
            Modifier
                .padding(start = 4.dp, end = 16.dp)
                .size(48.dp, 58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DS.Colors.surfaceContainerHigh)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                .plainClickable(onClick = onOpenPicker)
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            DfyIcon("calendar", size = 18.dp, tint = DS.Colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinnedTodayButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .size(BUGUN_CIP_GENISLIGI, 58.dp)
            .clip(shape)
            .background(DS.Colors.cyan400.copy(alpha = 0.12f))
            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.3f), shape)
            .plainClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text("BUGÜN", style = sys(9, FontWeight.Bold, tracking = 0.6), color = DS.Colors.cyan400, maxLines = 1)
        Text("${LocalDate.now().dayOfMonth}", style = sys(18, FontWeight.Bold), color = DS.Colors.cyan400, maxLines = 1)
    }
}

@Composable
private fun DayChipView(chip: DayChip, isSelected: Boolean, onClick: () -> Unit) {
    val spec = Motion.spring<Color>(0.35, 0.7)
    val fg by animateColorAsState(
        when {
            isSelected -> Color.White
            chip.isToday -> DS.Colors.cyan400
            else -> DS.Colors.onSurfaceVariant
        }, spec, label = "chipFg",
    )
    val bg by animateColorAsState(
        when {
            isSelected -> DS.Colors.cyan400.copy(alpha = 0.18f)
            chip.isToday -> DS.Colors.cyan400.copy(alpha = 0.07f)
            else -> Color.White.copy(alpha = 0.035f)
        }, spec, label = "chipBg",
    )
    val stroke by animateColorAsState(
        when {
            isSelected -> DS.Colors.cyan400.copy(alpha = 0.6f)
            chip.isToday -> DS.Colors.cyan400.copy(alpha = 0.28f)
            else -> DS.Colors.hairline
        }, spec, label = "chipStroke",
    )
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .size(if (chip.isToday) BUGUN_CIP_GENISLIGI else CIP_GENISLIGI, 58.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .plainClickable {
                Haptics.selection()
                onClick()
            }
            .semantics { selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text(chip.label, style = sys(if (chip.isToday) 9 else 10, FontWeight.Bold, tracking = 0.6), color = fg, maxLines = 1)
        Text("${chip.dayNumber}", style = sys(18, FontWeight.Bold), color = fg, maxLines = 1)
    }
}

// MARK: - Tarih seçici sayfası

@Composable
private fun DatePickerSheet(selectedDate: LocalDate, onDateChange: (LocalDate) -> Unit, onDone: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    // Swift bağlaması gibi: seçim anında ana ekrana yansır.
    LaunchedEffect(state.selectedDateMillis) {
        val millis = state.selectedDateMillis ?: return@LaunchedEffect
        val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        if (d != selectedDate) onDateChange(d)
    }
    Column(
        Modifier.fillMaxWidth().background(DS.Colors.background).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp).background(DS.Colors.surfaceContainer)) {
            Text("Tarih Seçin", Modifier.align(Alignment.Center), style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(
                "Tamam",
                Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).plainClickable(onClick = onDone),
                style = sys(17, FontWeight.SemiBold), color = DS.Colors.cyan400,
            )
        }
        TurkceYerel {
            DatePicker(
                state = state,
                modifier = Modifier.padding(horizontal = 8.dp),
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = DS.Colors.background,
                    weekdayContentColor = DS.Colors.onSurfaceVariant,
                    subheadContentColor = DS.Colors.onSurfaceVariant,
                    navigationContentColor = DS.Colors.cyan400,
                    yearContentColor = DS.Colors.onSurface,
                    currentYearContentColor = DS.Colors.cyan400,
                    selectedYearContainerColor = DS.Colors.cyan400,
                    selectedYearContentColor = Color.White,
                    dayContentColor = DS.Colors.onSurface,
                    selectedDayContainerColor = DS.Colors.cyan400,
                    selectedDayContentColor = Color.White,
                    todayContentColor = DS.Colors.cyan400,
                    todayDateBorderColor = DS.Colors.cyan400,
                ),
            )
        }
    }
}

// MARK: - Hekim seçici

@Composable
private fun DoctorSelector(doctors: List<Doctor>, selectedDoctorId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    // Aralık, beşinci hekim kenarda yarım görünecek kadar dar: satırın kaydığı anlaşılır.
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        doctors.forEach { doctor ->
            key(doctor.apiUserId.ifEmpty { doctor.id }) {
                DoctorAvatar(
                    doctor, isSelected = selectedDoctorId == doctor.apiUserId,
                    modifier = Modifier
                        .alpha(if (doctor.kapali) 0.5f else 1f)
                        .plainClickable {
                            Haptics.selection()
                            onSelect(doctor.apiUserId)
                        },
                )
            }
        }
    }
}

@Composable
private fun DoctorAvatar(doctor: Doctor, isSelected: Boolean, modifier: Modifier = Modifier) {
    val spec = Motion.spring<Color>(0.4, 0.7)
    val fill by animateColorAsState(doctor.colorAccent.copy(alpha = if (isSelected) 0.18f else 0.10f), spec, label = "docFill")
    val stroke by animateColorAsState(if (isSelected) doctor.colorAccent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.07f), spec, label = "docStroke")
    val strokeWidth by animateDpAsState(if (isSelected) 2.dp else 1.dp, Motion.spring(0.4, 0.7), label = "docStrokeW")
    val nameColor by animateColorAsState(if (isSelected) DS.Colors.onSurface else DS.Colors.onSurfaceVariant, spec, label = "docName")

    Column(
        modifier.width(76.dp).semantics(mergeDescendants = true) { selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(54.dp).background(fill, CircleShape).border(strokeWidth, stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(doctor.initials, style = sys(17, FontWeight.SemiBold), color = doctor.colorAccent, maxLines = 1)
        }
        Text(
            shortName(doctor.name),
            style = sys(12, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
            color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (doctor.kapali) {
            Text(
                "Bu tarihte çalışmıyor",
                style = sys(9.5, FontWeight.Medium), color = DS.Colors.outline,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Unvan + ilk ad (dar çipte kesilmesin): "Dt. Ayşe Demir" → "Dt. Ayşe" · "Uzm. Dt. Ayşe Demir" → "Uzm. Dt. Ayşe".
 * Unvan bileşik olabilir; ayrımı `Unvan.unvanAyir` yapar (ad hiçbir zaman düşmez).
 */
internal fun shortName(name: String): String {
    val (unvan, ad) = Unvan.unvanAyir(name)
    val ilk = ad.split(" ").firstOrNull { it.isNotEmpty() } ?: return name
    return if (unvan.isEmpty()) ilk else "$unvan $ilk"
}

// MARK: - Çakışma yerleşimi

private data class ColumnLayout(val column: Int, val totalColumns: Int)

/** Günün çizelge penceresi (saat aralığı) + çakışan randevuların sütunları — randevu listesi değişince bir kez kurulur. */
private class CizelgeDuzeni(val startHour: Int, val endHour: Int, val layouts: Map<String, ColumnLayout>) {
    val totalSlots: Int get() = max(1, (endHour - startHour) * 2)
}

private fun cizelgeDuzeni(appointments: List<Appointment>): CizelgeDuzeni {
    val startHour = min(defaultStartHour, appointments.minOfOrNull { it.startHour } ?: defaultStartHour)
    val endHour = max(
        defaultEndHour,
        appointments.maxOfOrNull { ceil((it.startHour * 60 + it.startMinute + it.durationMinutes) / 60.0).toInt() } ?: defaultEndHour,
    )
    return CizelgeDuzeni(startHour, endHour, columnLayouts(appointments))
}

private fun columnLayouts(appointments: List<Appointment>): Map<String, ColumnLayout> {
    val nonBreak = appointments.filter { !it.isBreak }
    val sorted = nonBreak.sortedBy { it.startHour * 60 + it.startMinute }

    val columnEnds = mutableListOf<Int>()
    val assignments = mutableListOf<Pair<Appointment, Int>>()

    for (apt in sorted) {
        val start = apt.startHour * 60 + apt.startMinute
        var col = -1
        for (i in columnEnds.indices) {
            if (columnEnds[i] <= start) {
                col = i
                columnEnds[i] = start + apt.durationMinutes
                break
            }
        }
        if (col == -1) {
            col = columnEnds.size
            columnEnds.add(start + apt.durationMinutes)
        }
        assignments.add(apt to col)
    }

    val result = HashMap<String, ColumnLayout>()
    for ((apt, col) in assignments) {
        val aptStart = apt.startHour * 60 + apt.startMinute
        val aptEnd = aptStart + apt.durationMinutes
        val overlappingCols = assignments.filter { (other, _) ->
            val otherStart = other.startHour * 60 + other.startMinute
            val otherEnd = otherStart + other.durationMinutes
            aptStart < otherEnd && otherStart < aptEnd
        }.map { it.second }
        val maxCol = (overlappingCols.maxOrNull() ?: 0) + 1
        result[apt.id] = ColumnLayout(col, maxCol)
    }
    return result
}

// MARK: - Günün programı

@Composable
private fun DayCalendarSection(
    appointments: List<Appointment>,
    isLoading: Boolean,
    isViewingToday: Boolean,
    onSelectAppointment: (Appointment?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duzen = remember(appointments) { cizelgeDuzeni(appointments) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Günün programı", Modifier.weight(1f), style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(14.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
            } else {
                val count = appointments.count { !it.isBreak }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberTicker(value = count, style = sys(10, FontWeight.Bold))
                    Text("RANDEVU", style = sys(10, FontWeight.Bold, tracking = 1), color = DS.Colors.cyan400, maxLines = 1)
                }
            }
        }

        if (appointments.isEmpty() && !isLoading) {
            EmptyStateView()
        } else {
            BoxWithConstraints(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(slotHeight * duzen.totalSlots)
                    // Çizelge kendi katmanında: sayfadaki başka çizim değişiklikleri (şerit, sayaç) onu yeniden kaydettirmez.
                    .graphicsLayer { },
            ) {
                val availableWidth = maxWidth - timeColumnWidth - 14.dp

                TimeGrid(duzen.totalSlots, duzen.startHour, Modifier.matchParentSize())

                appointments.forEachIndexed { index, appointment ->
                    key(appointment.id) {
                        val layout = duzen.layouts[appointment.id]
                        val totalCols = layout?.totalColumns ?: 1
                        val col = layout?.column ?: 0
                        val colWidth = availableWidth / totalCols
                        val blockW = if (appointment.isBreak) availableWidth else colWidth - 2.dp
                        val blockX = timeColumnWidth + 14.dp + (if (appointment.isBreak) 0.dp else colWidth * col)
                        val blockY = slotHeight * (((appointment.startHour - duzen.startHour) * 60 + appointment.startMinute) / 30f)

                        Box(
                            Modifier
                                .offset(x = blockX, y = blockY)
                                .size(blockW, slotHeight * (appointment.durationMinutes / 30f))
                                .blockEntrance(0.2 + min(index, 9) * 0.05, key = appointment.id),
                        ) {
                            if (appointment.isBreak) {
                                BreakBlock()
                            } else {
                                CalendarBlock(
                                    appointment,
                                    // Durum yazısı yalnız geniş kartta (Swift `geo.size.width > 170`; 4 dp vurgu çubuğu hariç).
                                    wide = blockW - 4.dp > 170.dp,
                                    onTap = {
                                        Haptics.light()
                                        onSelectAppointment(appointment)
                                    },
                                )
                            }
                        }
                    }
                }

                // Canlı "şimdi" çizgisi — yalnız bugün görüntülenirken ve saat pencere içindeyse.
                if (isViewingToday) NowIndicatorLayer(duzen.startHour, duzen.endHour)
            }
        }
    }
}

/** `TimelineView(.everyMinute)` — dakika başında güncellenen gün içi dakika. */
@Composable
private fun rememberNowMinutes(): Int {
    fun now(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
    var minutes by remember { mutableIntStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = LocalTime.now()
            delay(max(1L, (60 - t.second) * 1000L - t.nano / 1_000_000))
            minutes = now()
        }
    }
    return minutes
}

/** Dakikada bir yalnız bu katman yeniden çizilir (çizelgenin geri kalanı etkilenmez). */
@Composable
private fun NowIndicatorLayer(startHour: Int, endHour: Int) {
    val mins = rememberNowMinutes()
    if (mins >= startHour * 60 && mins <= endHour * 60) {
        NowIndicator(
            mins,
            Modifier.offset { IntOffset(0, (slotHeight.toPx() * ((mins - startHour * 60) / 30f)).roundToInt()) },
        )
    }
}

@Composable
private fun EmptyStateView() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DfyIcon("calendar.badge.checkmark", size = 48.dp, tint = DS.Colors.outline.copy(alpha = 0.5f))
        Text("Bu gün için randevu bulunamadı", style = sys(16, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        Text("Farklı bir tarih veya doktor seçin", style = sys(13), color = DS.Colors.outline, textAlign = TextAlign.Center)
    }
}

/**
 * Saat ızgarası — tek çizim: yarım saatlik çizgiler + saat etiketleri (her dilim için ayrı satır/metin düğümü yerine).
 * Etiketler boyut değişene dek önbellekte.
 */
@Composable
private fun TimeGrid(totalSlots: Int, calendarStartHour: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labels = remember(totalSlots, calendarStartHour) {
        List(totalSlots) { slotIndex ->
            val totalMinutes = calendarStartHour * 60 + slotIndex * 30
            String.format(Locale.US, "%02d:%02d", totalMinutes / 60, totalMinutes % 60)
        }
    }
    val style = remember { sys(11, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace, color = DS.Colors.outline.copy(alpha = 0.7f)) }
    Spacer(
        modifier.drawWithCache {
            val layouts = labels.map { measurer.measure(it, style) }
            val slotPx = slotHeight.toPx()
            val columnPx = timeColumnWidth.toPx()
            val lineStart = columnPx + 10.dp.toPx()
            val lineHeight = 0.5.dp.toPx()
            val labelLift = 7.dp.toPx()
            val strong = Color.White.copy(alpha = 0.06f)
            val faint = Color.White.copy(alpha = 0.03f)
            onDrawBehind {
                layouts.forEachIndexed { slotIndex, layout ->
                    val y = slotIndex * slotPx
                    drawText(layout, topLeft = Offset(columnPx - layout.size.width, y - labelLift))
                    drawRect(
                        if (slotIndex % 2 == 0) strong else faint,
                        topLeft = Offset(lineStart, y),
                        size = Size(max(0f, size.width - lineStart), lineHeight),
                    )
                }
            }
        }
    )
}

@Composable
private fun CalendarBlock(appointment: Appointment, wide: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(appointment.accentColor.copy(alpha = 0.08f))
            .border(0.5.dp, appointment.accentColor.copy(alpha = 0.15f), shape)
            .plainClickable(onClick = onTap)
            .semantics { role = Role.Button },
    ) {
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(appointment.accentColor)
        )
        CardScrollContent(appointment, wide, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun BreakBlock() {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(DS.Colors.surfaceContainerHigh.copy(alpha = 0.5f))
            .border(0.5.dp, Color.White.copy(alpha = 0.04f), shape)
            .padding(start = 14.dp, top = 10.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DfyIcon("cup.and.saucer.fill", size = 11.dp, tint = DS.Colors.outline)
            Text("MOLA / YEMEK", style = sys(11, FontWeight.Bold, tracking = 1), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NowIndicator(minutes: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60),
            Modifier.width(timeColumnWidth),
            style = sys(10, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace),
            color = DS.Colors.cyan400,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Box(Modifier.padding(start = 10.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(horizontalGradient(DS.Colors.cyan400.copy(alpha = 0.9f), DS.Colors.cyan400.copy(alpha = 0.05f)))
            )
            Box(Modifier.size(8.dp).background(DS.Colors.cyan400, CircleShape))
        }
    }
}

// MARK: - Kart içeriği (sürüklenince kayan)

/** Kart içi kaydırma fırlatmaz: kalan hız sayfaya geçer (iç içe kaydırma). */
private object FirlatmaYok : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = initialVelocity
}

/**
 * Kısa kartta taşan notu görmek için içerik parmakla kaydırılır, bırakınca yaylı geri döner (Swift `@GestureState`).
 * Android'de iç içe kaydırmayla: kart içeriği sonuna gelince sürükleme sayfayı kaydırır — kartın üstünden başlayan
 * sürükleme sayfayı KİLİTLEMEZ.
 */
@Composable
private fun CardScrollContent(appointment: Appointment, wide: Boolean, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (!dragging && scroll.value != 0) scroll.animateScrollTo(0, Motion.spring(0.3, 0.8))
    }
    Column(
        modifier
            .clipToBounds()
            .verticalScroll(scroll, flingBehavior = FirlatmaYok)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DfyIcon(appointment.icon, size = 10.dp, tint = appointment.accentColor)
            Spacer(Modifier.width(5.dp))
            // Ad önce sığdırılır (Swift `layoutPriority(1)`); durum kalan yere.
            Text(
                appointment.patientName,
                style = sys(12, FontWeight.Bold), color = DS.Colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Durum yazıyla da görünsün (Onaylandı / Geldi renkleri birbirine yakın).
            if (wide) {
                Spacer(Modifier.width(4.dp))
                Text(
                    appointment.appointmentStatus, Modifier.weight(1f),
                    style = sys(10, FontWeight.SemiBold), color = appointment.accentColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                )
            }
        }

        Text(
            if (appointment.room.isEmpty()) appointment.treatment else "${appointment.treatment} · ${appointment.room}",
            style = sys(10), color = DS.Colors.onSurfaceVariant,
        )

        if (appointment.note.isNotEmpty()) {
            Text(appointment.note, style = sys(10), color = DS.Colors.outline)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun HomeViewPreview() {
    PreviewEnv {
        HomeView(onShowNotifications = {}, selectedAppointment = null, onSelectAppointment = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun HomeViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        HomeView(onShowNotifications = {}, selectedAppointment = null, onSelectAppointment = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun HomeViewTabletPortraitPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) {
        HomeView(onShowNotifications = {}, selectedAppointment = null, onSelectAppointment = {})
    }
}
