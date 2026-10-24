package com.dentifay.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
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
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.blockEntrance
import com.dentifay.app.theme.horizontalGradient
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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

private val slotHeight = 52.dp
private val timeColumnWidth = 50.dp

// Takvim penceresi dinamik: varsayılan 08:00–19:00, daha erken başlayan / geç biten
// randevu varsa GENİŞLER — yoksa erken/geç kartlar başlığın üstüne taşar.
private const val defaultStartHour = 8
private const val defaultEndHour = 19

/** Ana sayfa: profil başlığı, gün şeridi, hekim seçici ve günün programı (zaman ızgarası). */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeView(
    onShowNotifications: () -> Unit,
    selectedAppointment: Appointment?,
    onSelectAppointment: (Appointment?) -> Unit,
) {
    val authManager = LocalAuthManager.current
    val dataManager = LocalDataManager.current
    val notifications = remember { NotificationsStore.shared }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDoctorIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun fetchAppointments() {
        val doctorUserId = dataManager.doctors.getOrNull(selectedDoctorIndex)?.apiUserId
        dataManager.fetchAppointments(selectedDate, doctorUserId)
    }

    LaunchedEffect(Unit) {
        dataManager.fetchDoctors()
        fetchAppointments()
        notifications.refreshCounts()
    }
    // `.onChange(of: selectedDate)` / `.onChange(of: selectedDoctorIndex)` — ilk değer hariç.
    LaunchedEffect(Unit) {
        snapshotFlow { selectedDate to selectedDoctorIndex }.drop(1).collect {
            scope.launch { fetchAppointments() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                // iPhone'da çentik doğal boşluk verir; Android'in ince durum çubuğunda Stok sayfasıyla aynı 8 dp.
                .padding(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileHeader(
                doctorName = authManager.currentUser?.name ?: "Doctor",
                clinicName = "Dentifay",
                showNotificationBadge = notifications.unreadTotal > 0,
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
                selectedIndex = selectedDoctorIndex,
                onSelect = { selectedDoctorIndex = it },
                modifier = Modifier.appear(0.12),
            )
            DayCalendarSection(
                appointments = dataManager.appointments,
                isLoading = dataManager.isLoadingAppointments,
                selectedDate = selectedDate,
                onSelectAppointment = onSelectAppointment,
                modifier = Modifier.appear(0.18),
            )
        }
    }

    DfySheet(isPresented = showDatePicker, onDismiss = { showDatePicker = false }, containerColor = DS.Colors.background) { dismiss ->
        DatePickerSheet(selectedDate = selectedDate, onDateChange = { selectedDate = it }, onDone = dismiss)
    }
}

// MARK: - Ay günleri modeli

private data class DayChip(val id: String, val label: String, val dayNumber: Int, val date: LocalDate, val isToday: Boolean)

private fun chipId(date: LocalDate) = "${date.year}-${date.monthValue}-${date.dayOfMonth}"

private fun monthDays(displayedMonth: LocalDate): List<DayChip> {
    val today = LocalDate.now()
    val ym = YearMonth.from(displayedMonth)
    val fmt = DateTimeFormatter.ofPattern("EEE", trLocale)
    return (1..ym.lengthOfMonth()).map { day ->
        val date = ym.atDay(day)
        val name = date.format(fmt).upperTr()
        val isToday = date == today
        DayChip(chipId(date), if (isToday) "BUGÜN" else name.take(3), day, date, isToday)
    }
}

// MARK: - Kompakt tarih şeridi

@Composable
private fun CompactDateStrip(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var isTodayOffScreen by remember { mutableStateOf(false) }
    val chipCoords = remember { HashMap<String, LayoutCoordinates>() }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val today = LocalDate.now()
    val days = remember(YearMonth.from(selectedDate), today) { monthDays(selectedDate) }
    val isTodayInDisplayedMonth = YearMonth.from(today) == YearMonth.from(selectedDate)
    val isTodayFarFromSelected = abs(ChronoUnit.DAYS.between(today, selectedDate)) >= 3
    val shouldShowPinnedToday = !isTodayInDisplayedMonth || isTodayOffScreen || isTodayFarFromSelected

    /** Çipi şeridin ortasına kaydırır (`proxy.scrollTo(id, anchor: .center)`). */
    fun scrollToChip(id: String) {
        val c = chipCoords[id] ?: return
        val v = viewport ?: return
        if (!c.isAttached || !v.isAttached) return
        val delta = c.positionInWindow().x - v.positionInWindow().x + c.size.width / 2f - v.size.width / 2f
        val target = (scrollState.value + delta).roundToInt().coerceIn(0, scrollState.maxValue)
        scope.launch { scrollState.animateScrollTo(target, Motion.default()) }
    }

    LaunchedEffect(Unit) {
        delay(100)
        scrollToChip(chipId(LocalDate.now()))
    }
    LaunchedEffect(Unit) {
        snapshotFlow { selectedDate }.drop(1).collect { d ->
            withFrameNanos { }
            scrollToChip(chipId(d))
        }
    }

    val stripPadding by animateDpAsState(if (shouldShowPinnedToday) 8.dp else 16.dp, Motion.easeInOut(150), label = "stripPad")

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = shouldShowPinnedToday,
            enter = fadeIn(Motion.easeInOut(150)) + expandHorizontally(Motion.easeInOut(150)),
            exit = fadeOut(Motion.easeInOut(150)) + shrinkHorizontally(Motion.easeInOut(150)),
        ) {
            PinnedTodayButton(Modifier.padding(start = 16.dp, end = 4.dp)) { onSelectDate(LocalDate.now()) }
        }

        Box(Modifier.weight(1f).onGloballyPositioned { viewport = it }) {
            Row(
                Modifier.horizontalScroll(scrollState).padding(horizontal = stripPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                days.forEach { chip ->
                    key(chip.id) {
                        DayChipView(
                            chip = chip,
                            isSelected = chip.date == selectedDate,
                            onClick = { onSelectDate(chip.date) },
                            modifier = Modifier.onGloballyPositioned { c ->
                                chipCoords[chip.id] = c
                                if (chip.isToday) {
                                    val v = viewport
                                    if (v != null && v.isAttached && c.isAttached) {
                                        val minX = with(density) { (c.positionInWindow().x - v.positionInWindow().x).toDp() }
                                        val threshold = if (isTodayOffScreen) 72.dp else 16.dp
                                        val shouldPin = minX < threshold
                                        if (shouldPin != isTodayOffScreen) isTodayOffScreen = shouldPin
                                    }
                                }
                            },
                        )
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
                .plainClickable(onClick = onOpenPicker),
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
            .size(56.dp, 58.dp)
            .clip(shape)
            .background(DS.Colors.cyan400.copy(alpha = 0.12f))
            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.3f), shape)
            .plainClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text("BUGÜN", style = sys(9, FontWeight.Bold, tracking = 0.6), color = DS.Colors.cyan400)
        Text("${LocalDate.now().dayOfMonth}", style = sys(18, FontWeight.Bold), color = DS.Colors.cyan400)
    }
}

@Composable
private fun DayChipView(chip: DayChip, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        modifier
            .size(if (chip.isToday) 56.dp else 48.dp, 58.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .plainClickable {
                Haptics.selection()
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text(chip.label, style = sys(if (chip.isToday) 9 else 10, FontWeight.Bold, tracking = 0.6), color = fg, maxLines = 1)
        Text("${chip.dayNumber}", style = sys(18, FontWeight.Bold), color = fg)
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
    Column(Modifier.fillMaxWidth().background(DS.Colors.background)) {
        Box(Modifier.fillMaxWidth().height(44.dp).background(DS.Colors.surfaceContainer)) {
            Text("Tarih Seçin", Modifier.align(Alignment.Center), style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(
                "Tamam",
                Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).plainClickable(onClick = onDone),
                style = sys(17, FontWeight.SemiBold), color = DS.Colors.cyan400,
            )
        }
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

// MARK: - Hekim seçici

@Composable
private fun DoctorSelector(doctors: List<Doctor>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Aralık, beşinci hekim kenarda yarım görünecek kadar dar: satırın kaydığı anlaşılır.
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        doctors.forEachIndexed { index, doctor ->
            key(doctor.id) {
                DoctorAvatar(
                    doctor, isSelected = selectedIndex == index,
                    modifier = Modifier.plainClickable {
                        Haptics.selection()
                        onSelect(index)
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
            Text(doctor.initials, style = sys(17, FontWeight.SemiBold), color = doctor.colorAccent)
        }
        Text(
            shortName(doctor.name),
            style = sys(12, if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
            color = nameColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Dt. Ayşe Demir" → "Dt. Ayşe" (dar çipte kesilmesin). */
private fun shortName(name: String): String {
    val parts = name.split(" ").filter { it.isNotEmpty() }
    if (!(parts.size > 2 || (parts.size == 2 && !parts[0].endsWith(".")))) return name
    if (parts[0].endsWith(".")) return "${parts[0]} ${parts[1]}"
    return parts[0]
}

// MARK: - Çakışma yerleşimi

private data class ColumnLayout(val column: Int, val totalColumns: Int)

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
    selectedDate: LocalDate,
    onSelectAppointment: (Appointment?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val calendarStartHour = min(defaultStartHour, appointments.minOfOrNull { it.startHour } ?: defaultStartHour)
    val calendarEndHour = max(
        defaultEndHour,
        appointments.maxOfOrNull { ceil((it.startHour * 60 + it.startMinute + it.durationMinutes) / 60.0).toInt() } ?: defaultEndHour,
    )
    val totalSlots = max(1, (calendarEndHour - calendarStartHour) * 2)
    val layouts = remember(appointments) { columnLayouts(appointments) }

    fun yPosition(apt: Appointment): Dp = slotHeight * (((apt.startHour - calendarStartHour) * 60 + apt.startMinute) / 30f)
    fun blockHeight(apt: Appointment): Dp = slotHeight * (apt.durationMinutes / 30f)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Günün programı", style = sys(16, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Spacer(Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(14.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
            } else {
                val count = appointments.count { !it.isBreak }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberTicker(value = count, style = sys(10, FontWeight.Bold))
                    Text("RANDEVU", style = sys(10, FontWeight.Bold, tracking = 1), color = DS.Colors.cyan400)
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
                    .height(slotHeight * totalSlots),
            ) {
                val availableWidth = maxWidth - timeColumnWidth - 14.dp

                TimeGrid(totalSlots, calendarStartHour)

                appointments.forEachIndexed { index, appointment ->
                    key(appointment.id) {
                        val layout = layouts[appointment.id]
                        val totalCols = layout?.totalColumns ?: 1
                        val col = layout?.column ?: 0
                        val colWidth = availableWidth / totalCols
                        val blockW = if (appointment.isBreak) availableWidth else colWidth - 2.dp
                        val blockX = timeColumnWidth + 14.dp + (if (appointment.isBreak) 0.dp else colWidth * col)

                        Box(
                            Modifier
                                .offset(x = blockX, y = yPosition(appointment))
                                .size(blockW, blockHeight(appointment))
                                .blockEntrance(0.2 + min(index, 9) * 0.05, key = appointment.id),
                        ) {
                            if (appointment.isBreak) {
                                BreakBlock()
                            } else {
                                CalendarBlock(
                                    appointment,
                                    Modifier.plainClickable {
                                        Haptics.light()
                                        onSelectAppointment(appointment)
                                    },
                                )
                            }
                        }
                    }
                }

                // Canlı "şimdi" çizgisi — yalnız bugün görüntülenirken ve saat pencere içindeyse.
                if (selectedDate == LocalDate.now()) {
                    val mins = rememberNowMinutes()
                    if (mins >= calendarStartHour * 60 && mins <= calendarEndHour * 60) {
                        NowIndicator(mins, Modifier.offset(y = slotHeight * ((mins - calendarStartHour * 60) / 30f)))
                    }
                }
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

@Composable
private fun EmptyStateView() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DfyIcon("calendar.badge.checkmark", size = 48.dp, tint = DS.Colors.outline.copy(alpha = 0.5f))
        Text("Bu gün için randevu bulunamadı", style = sys(16, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
        Text("Farklı bir tarih veya doktor seçin", style = sys(13), color = DS.Colors.outline, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TimeGrid(totalSlots: Int, calendarStartHour: Int) {
    Column(Modifier.fillMaxWidth()) {
        repeat(totalSlots) { slotIndex ->
            Row(Modifier.fillMaxWidth().height(slotHeight), verticalAlignment = Alignment.Top) {
                val totalMinutes = calendarStartHour * 60 + slotIndex * 30
                Text(
                    String.format(Locale.US, "%02d:%02d", totalMinutes / 60, totalMinutes % 60),
                    Modifier.width(timeColumnWidth).offset(y = (-7).dp),
                    style = sys(11, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
                    color = DS.Colors.outline.copy(alpha = 0.7f),
                    textAlign = TextAlign.End,
                )
                Box(
                    Modifier
                        .padding(start = 10.dp)
                        .weight(1f)
                        .height(0.5.dp)
                        .background(Color.White.copy(alpha = if (slotIndex % 2 == 0) 0.06f else 0.03f))
                )
            }
        }
    }
}

@Composable
private fun CalendarBlock(appointment: Appointment, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(appointment.accentColor.copy(alpha = 0.08f))
            .border(0.5.dp, appointment.accentColor.copy(alpha = 0.15f), shape),
    ) {
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(appointment.accentColor)
        )
        CardScrollContent(appointment, Modifier.weight(1f).fillMaxHeight())
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
            Text("MOLA / YEMEK", style = sys(11, FontWeight.Bold, tracking = 1), color = DS.Colors.outline)
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

/** Kısa kartta taşan notu görmek için içerik parmakla kaydırılır, bırakınca yaylı geri döner. */
@Composable
private fun CardScrollContent(appointment: Appointment, modifier: Modifier = Modifier) {
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(appointment.id) {
                var total = 0f
                val release = {
                    total = 0f
                    scope.launch { dragOffset.animateTo(0f, Motion.spring(0.3, 0.8)) }
                    Unit
                }
                detectDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = release,
                    onDragCancel = release,
                ) { change, drag ->
                    change.consume()
                    total += drag.y
                    scope.launch { dragOffset.snapTo(total * 0.6f) }
                }
            },
    ) {
        val wide = maxWidth > 170.dp
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = dragOffset.value }
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DfyIcon(appointment.icon, size = 10.dp, tint = appointment.accentColor)
                Text(
                    appointment.patientName,
                    modifier = if (wide) Modifier.weight(1f) else Modifier,
                    style = sys(12, FontWeight.Bold), color = DS.Colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Durum yazıyla da görünsün (Onaylandı / Geldi renkleri birbirine yakın).
                if (wide) {
                    Spacer(Modifier.width(4.dp))
                    Text(appointment.appointmentStatus, style = sys(10, FontWeight.SemiBold), color = appointment.accentColor, maxLines = 1)
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
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun HomeViewPreview() {
    PreviewEnv {
        HomeView(onShowNotifications = {}, selectedAppointment = null, onSelectAppointment = {})
    }
}
