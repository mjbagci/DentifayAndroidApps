package com.dentifay.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.AppConfig
import com.dentifay.app.data.Appointment
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.data.MockData
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.TagChip
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.lowerTr
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.openInBrowser
import com.dentifay.app.theme.openSystemUrl
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Randevu detayı — hasta, saat, hekim, tedavi ve notlar; altta randevunun durumuna göre
 * bir sonraki adım (Hasta geldi → Tedaviye al → Tamamla). Durum değişiklikleri web ile aynı
 * uca yazılır (`PATCH /appointments/:id`); iptal ve "gelmedi" onay ister.
 */
@Composable
fun AppointmentDetailSheet(appointment: Appointment, onDismiss: () -> Unit) {
    val dataManager = LocalDataManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<StatusAction?>(null) }

    val statusColor = statusColor(appointment.statusCode)

    // Yalnız rakam ve + (boşluk, parantez, tire atılır).
    val dialDigits = appointment.phone.filter { it.isDigit() || it == '+' }
    val telURL = if (dialDigits.length >= 7) "tel:$dialDigits" else null
    val smsURL = if (dialDigits.length >= 7) "sms:$dialDigits" else null

    fun perform(action: StatusAction, notify: Boolean) {
        if (appointment.apiId.isEmpty() || isUpdating) return
        isUpdating = true
        errorText = null
        scope.launch {
            try {
                ApiClient.updateAppointmentStatus(appointment.apiId, action.target, notify)
                Haptics.success()
                dataManager.refreshAppointments()
                isUpdating = false
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                errorText = message(e)
                isUpdating = false
            }
        }
    }

    fun request(action: StatusAction) {
        Haptics.light()
        if (action.needsConfirmation) confirming = action else perform(action, notify = true)
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.surfaceContainer)) {
        // Tutamak
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 12.dp)
                .size(40.dp, 5.dp)
                .background(DS.Colors.outlineVariant, CircleShape)
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PatientHeader(
                appointment, statusColor,
                onCall = { telURL?.let { openSystemUrl(context, it) } }, callEnabled = telURL != null,
                onMessage = { smsURL?.let { openSystemUrl(context, it) } }, messageEnabled = smsURL != null,
                onPatientCard = { openInBrowser(context, AppConfig.webBaseURL + "/patients/" + appointment.patientId) },
                patientCardEnabled = !appointment.isGuest && appointment.patientId.isNotEmpty(),
            )
            TimeCard(appointment, statusColor)
            DetailsGrid(appointment)
            NotesSection(appointment)
        }

        BottomBar(
            appointment = appointment,
            isUpdating = isUpdating,
            errorText = errorText,
            onRequest = { request(it) },
            onClose = onDismiss,
        )
    }

    // `.confirmationDialog` — iptal / gelmedi onayı; telefon varsa SMS seçeneği.
    DfySheet(isPresented = confirming != null, onDismiss = { confirming = null }, containerColor = DS.Colors.surfaceContainerHigh) { dismiss ->
        val action = confirming ?: return@DfySheet
        ConfirmationContent(
            action = action,
            appointment = appointment,
            onChoose = { notify -> perform(action, notify); dismiss() },
            onCancel = dismiss,
        )
    }
}

private fun statusColor(code: String): Color = when (code) {
    "CONFIRMED", "COMPLETED" -> DS.Colors.success
    "ARRIVED", "IN_TREATMENT" -> DS.Colors.purple400
    "CANCELLED" -> DS.Colors.danger
    "NO_SHOW", "RESCHEDULED" -> DS.Colors.warning
    else -> DS.Colors.cyan400
}

private fun message(error: Exception): String = when (error) {
    is ApiException.ServerError -> error.serverMessage?.takeIf { it.isNotEmpty() } ?: "Durum kaydedilemedi. Lütfen tekrar deneyin."
    is ApiException.Unauthorized -> "Oturumun süresi doldu; yeniden giriş yapın."
    is ApiException.NetworkError -> "Sunucuya ulaşılamadı. Bağlantınızı kontrol edin."
    else -> "Durum kaydedilemedi. Lütfen tekrar deneyin."
}

// MARK: - Hasta

@Composable
private fun PatientHeader(
    appointment: Appointment,
    statusColor: Color,
    onCall: () -> Unit, callEnabled: Boolean,
    onMessage: () -> Unit, messageEnabled: Boolean,
    onPatientCard: () -> Unit, patientCardEnabled: Boolean,
) {
    Column(Modifier.fillMaxWidth().glassCard(16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(statusColor.copy(alpha = 0.12f), CircleShape)
                    .border(1.5.dp, statusColor.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(appointment.patientInitials, style = sys(20, FontWeight.Bold), color = statusColor)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appointment.patientName, style = sys(21, FontWeight.SemiBold), color = DS.Colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (appointment.isGuest) TagChip(text = "Misafir", tint = DS.Colors.metal)
                    Text(
                        appointment.phone.ifEmpty { "Telefon kayıtlı değil" },
                        style = sys(14).monoDigits(), color = DS.Colors.onSurfaceVariant,
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ContactButton("phone", "Ara", callEnabled, onCall, Modifier.weight(1f))
            ContactDivider()
            ContactButton("message", "Mesaj", messageEnabled, onMessage, Modifier.weight(1f))
            ContactDivider()
            ContactButton("person.text.rectangle", "Hasta kartı", patientCardEnabled, onPatientCard, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ContactDivider() {
    Box(Modifier.size(1.dp, 34.dp).background(DS.Colors.hairline))
}

@Composable
private fun ContactButton(icon: String, label: String, enabled: Boolean, action: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (enabled) DS.Colors.onSurface else DS.Colors.outline.copy(alpha = 0.6f)
    Column(
        modifier
            .plainClickable(enabled = enabled) {
                Haptics.light()
                action()
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DfyIcon(icon, size = 17.dp, tint = color)
        Text(label, style = sys(12, FontWeight.SemiBold), color = color)
    }
}

// MARK: - Saat · hekim · durum

@Composable
private fun TimeCard(appointment: Appointment, statusColor: Color) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DS.Colors.cyan400.copy(alpha = 0.05f))
            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.14f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DfyIcon("clock", size = 12.dp, tint = DS.Colors.cyan400)
                Text(
                    if (appointment.endTime.isEmpty()) appointment.time else "${appointment.time} – ${appointment.endTime}",
                    style = sys(14, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400,
                )
            }
            Text(
                if (appointment.room.isEmpty()) appointment.doctorName else "${appointment.doctorName} · ${appointment.room}",
                style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            appointment.appointmentStatus,
            style = sys(12, FontWeight.SemiBold), color = statusColor,
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.12f), CircleShape)
                .border(1.dp, statusColor.copy(alpha = 0.32f), CircleShape)
                .padding(horizontal = 11.dp, vertical = 5.dp),
        )
    }
}

// MARK: - Ayrıntılar

@Composable
private fun DetailsGrid(appointment: Appointment) {
    val cells = listOf(
        "İşlem" to appointment.treatment,
        "Şikâyet / neden" to appointment.reason,
        "Sağlık ajansı" to appointment.agency,
        "Anlaşmalı kurum" to appointment.institution,
    ).filter { it.second.isNotEmpty() }
    if (cells.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { cell ->
                    Column(
                        Modifier
                            .weight(1f)
                            .glassCard(12.dp)
                            .padding(13.dp)
                            .heightIn(min = 44.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(cell.first, style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
                        Text(cell.second, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Notlar

@Composable
private fun NotesSection(appointment: Appointment) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notlar", style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
        Text(
            appointment.clinicalNotes.ifEmpty { "Bu randevuya not girilmemiş." },
            style = sys(14).copy(lineHeight = 20.sp),
            color = if (appointment.clinicalNotes.isEmpty()) DS.Colors.outline else DS.Colors.onSurface.copy(alpha = 0.9f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.dp, DS.Colors.hairline, shape)
                .padding(14.dp),
        )
    }
}

// MARK: - Durum işlemleri

enum class StatusAction {
    confirm, arrive, startTreatment, complete, noShow, cancel;

    val id: String get() = name

    val target: String
        get() = when (this) {
            confirm -> "CONFIRMED"
            arrive -> "ARRIVED"
            startTreatment -> "IN_TREATMENT"
            complete -> "COMPLETED"
            noShow -> "NO_SHOW"
            cancel -> "CANCELLED"
        }

    val title: String
        get() = when (this) {
            confirm -> "Onaylandı olarak işaretle"
            arrive -> "Hasta geldi"
            startTreatment -> "Tedaviye al"
            complete -> "Tedaviyi tamamla"
            noShow -> "Gelmedi"
            cancel -> "Randevuyu iptal et"
        }

    val icon: String
        get() = when (this) {
            confirm -> "checkmark.circle"
            arrive -> "person.crop.circle.badge.checkmark"
            startTreatment -> "cross.case"
            complete -> "checkmark.seal"
            noShow -> "person.crop.circle.badge.xmark"
            cancel -> "xmark.circle"
        }

    val needsConfirmation: Boolean get() = this == noShow || this == cancel

    val confirmTitle: String
        get() = if (this == cancel) "Randevu iptal edilsin mi?" else "Hasta gelmedi olarak işaretlensin mi?"
}

private fun isClosed(code: String) = code in listOf("COMPLETED", "CANCELLED", "NO_SHOW")

private fun primaryAction(code: String): StatusAction? = when (code) {
    "SCHEDULED", "CONFIRMED", "RESCHEDULED" -> StatusAction.arrive
    "ARRIVED" -> StatusAction.startTreatment
    "IN_TREATMENT" -> StatusAction.complete
    else -> null
}

private fun otherActions(code: String): List<StatusAction> = when (code) {
    "SCHEDULED", "RESCHEDULED" -> listOf(StatusAction.confirm, StatusAction.noShow, StatusAction.cancel)
    "CONFIRMED" -> listOf(StatusAction.noShow, StatusAction.cancel)
    "ARRIVED" -> listOf(StatusAction.complete, StatusAction.cancel)
    else -> emptyList()
}

@Composable
private fun BottomBar(
    appointment: Appointment,
    isUpdating: Boolean,
    errorText: String?,
    onRequest: (StatusAction) -> Unit,
    onClose: () -> Unit,
) {
    val closed = isClosed(appointment.statusCode)
    val primary = primaryAction(appointment.statusCode)
    val others = otherActions(appointment.statusCode)
    // Solma sırasında son hata metni görünmeye devam etsin.
    val lastError = remember { arrayOf("") }
    if (errorText != null) lastError[0] = errorText

    Column(
        Modifier
            .fillMaxWidth()
            .background(DS.Colors.surfaceContainer)
            .drawBehind { drawRect(DS.Colors.hairline, size = Size(size.width, 1.dp.toPx())) }
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedVisibility(errorText != null, enter = fadeIn(Motion.easeOut(200)), exit = fadeOut(Motion.easeOut(200))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(DS.Colors.danger.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DfyIcon("exclamationmark.triangle.fill", size = 13.dp, tint = DS.Colors.danger)
                Text(lastError[0], Modifier.weight(1f), style = sys(13, FontWeight.Medium), color = DS.Colors.danger)
            }
        }

        if (closed || appointment.apiId.isEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (closed) {
                    Text(
                        "Bu randevu kapandı: ${appointment.appointmentStatus.lowerTr()}.",
                        Modifier.weight(1f), style = sys(13), color = DS.Colors.onSurfaceVariant,
                    )
                }
                SecondaryButton(onClick = onClose, modifier = if (closed) Modifier.width(120.dp) else Modifier.weight(1f)) {
                    ButtonLabel("Kapat")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (others.isNotEmpty()) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .size(104.dp, 50.dp)
                                .alpha(if (isUpdating) 0.45f else 1f)
                                .clip(shape)
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, DS.Colors.hairlineStrong, shape)
                                .plainClickable(enabled = !isUpdating) { menuOpen = true },
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Diğer", style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface)
                            DfyIcon("chevron.up", size = 11.dp, tint = DS.Colors.onSurface)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = DS.Colors.surfaceContainerHigh,
                        ) {
                            others.forEach { action ->
                                val tint = if (action == StatusAction.cancel) DS.Colors.danger else DS.Colors.onSurface
                                DropdownMenuItem(
                                    text = { Text(action.title, style = sys(15), color = tint) },
                                    leadingIcon = { DfyIcon(action.icon, size = 17.dp, tint = tint) },
                                    onClick = {
                                        menuOpen = false
                                        onRequest(action)
                                    },
                                )
                            }
                        }
                    }
                }

                if (primary != null) {
                    Row(
                        Modifier
                            .weight(1f)
                            .height(50.dp)
                            .pressable(enabled = !isUpdating) { onRequest(primary) }
                            .alpha(if (isUpdating) 0.85f else 1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(DS.Gradients.primaryGradient),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            DfyIcon(primary.icon, size = 15.dp, tint = Color.White)
                            Text(primary.title, style = sys(16, FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** iOS onay sayfasının (`confirmationDialog`) karşılığı: başlık, mesaj, yıkıcı seçenekler ve Vazgeç. */
@Composable
private fun ConfirmationContent(action: StatusAction, appointment: Appointment, onChoose: (notify: Boolean) -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(action.confirmTitle, style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
        Text("${appointment.patientName} · ${appointment.time}", style = sys(13), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        if (appointment.phone.isEmpty()) {
            ConfirmOption(action.title, DS.Colors.danger) { onChoose(false) }
        } else {
            ConfirmOption("${action.title} · hastaya SMS gönder", DS.Colors.danger) { onChoose(true) }
            ConfirmOption("${action.title} · SMS gönderme", DS.Colors.danger) { onChoose(false) }
        }
        ConfirmOption("Vazgeç", DS.Colors.onSurface, bold = true, onClick = onCancel)
    }
}

@Composable
private fun ConfirmOption(title: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, DS.Colors.hairline, shape)
            .plainClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = sys(16, if (bold) FontWeight.SemiBold else FontWeight.Normal), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AppointmentDetailSheetPreview() {
    PreviewEnv {
        val sample = MockData.appointments[2]
        AppointmentDetailSheet(
            appointment = Appointment(
                patientName = sample.patientName, treatment = sample.treatment, time = sample.time, room = sample.room,
                accentColor = sample.accentColor, icon = sample.icon, note = sample.note, patientId = sample.patientId,
                doctorName = sample.doctorName, clinicalNotes = sample.clinicalNotes, agency = sample.agency,
                institution = sample.institution, appointmentStatus = "Planlandı", startHour = 11, durationMinutes = 60,
                apiId = "a1", statusCode = "SCHEDULED", phone = "+90 532 123 45 67", reason = "Diş ağrısı", endTime = "12:00",
            ),
            onDismiss = {},
        )
    }
}
