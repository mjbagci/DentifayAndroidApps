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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.Yetki
import com.dentifay.app.data.Appointment
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.data.MockData
import com.dentifay.app.hasta.HastaBasligi
import com.dentifay.app.hasta.HastaSayfasiView
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.CoverHost
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.TagChip
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.lowerTr
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.openSystemUrl
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.YetkiliButon
import com.dentifay.app.ui.YetkiliEylem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Randevu detayı — hasta, saat, hekim, tedavi ve notlar; altta randevunun durumuna göre bir sonraki adım (Hasta geldi →
 * Tedaviye al → Tamamla). Durum değişiklikleri web ile aynı uca yazılır (`PATCH /appointments/:id`); iptal ve "gelmedi" onay
 * ister. Hastaya SMS sorusu SMS otomasyonunun kipine göre (`active-kinds` `modes`): "Otomatik" → soru yok, bilgi satırı;
 * "Sor" → ön seçimsiz soru (M-E3.5). Durum eylemleri oturumun yetenekleriyle (`Yetki.randevuEylemi`) — yetkisiz eylem sönük.
 */
@Composable
fun AppointmentDetailSheet(appointment: Appointment, onDismiss: () -> Unit) {
    val dataManager = LocalDataManager.current
    val auth = LocalAuthManager.current
    val context = LocalContext.current
    val onizleme = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<StatusAction?>(null) }
    /** "Hasta kartı" artık yerli hasta sayfasını açar (M-E8.3) — tarayıcı değil. */
    var hastaSayfasi by remember { mutableStateOf<HastaBasligi?>(null) }
    var misafirUyarisi by remember { mutableStateOf(false) }
    /** Misafirden hasta kaydı açıldı (M-E8.13) — kart artık kayıtlı hastayı açar. */
    var yeniHastaId by remember { mutableStateOf<String?>(null) }
    var kayitAciliyor by remember { mutableStateOf(false) }
    /** `null` → okunamadı ya da henüz gelmedi: "Sor" sayılır (kip bilinmiyorsa sormak güvenli taraftır). */
    var smsKipleri by remember { mutableStateOf<SmsKipleri?>(null) }

    LaunchedEffect(Unit) {
        if (onizleme) return@LaunchedEffect
        smsKipleri = try {
            ApiClient.fetchSmsKinds()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    val statusColor = statusColor(appointment.statusCode)
    val caps = auth.yetenekler

    // Yalnız rakam ve + (boşluk, parantez, tire atılır).
    val dialDigits = appointment.phone.filter { it.isDigit() || it == '+' }
    val telURL = if (dialDigits.length >= 7) "tel:$dialDigits" else null
    val smsURL = if (dialDigits.length >= 7) "sms:$dialDigits" else null

    fun smsKip(action: StatusAction): RandevuSms.Kip =
        RandevuSms.kip(durum = action.target, yanit = smsKipleri, telefonVar = appointment.phone.isNotEmpty())

    fun perform(action: StatusAction, notify: Boolean?) {
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

    /** Onay gerektiren geçiş ya da SMS kipi olan tür → pencere; değilse doğrudan yazılır (`notifyPatient` YOK). */
    fun request(action: StatusAction) {
        Haptics.light()
        if (action.needsConfirmation || smsKip(action) != RandevuSms.Kip.yok) confirming = action else perform(action, notify = null)
    }

    fun hastaKaydiAc() {
        if (kayitAciliyor) return
        kayitAciliyor = true
        scope.launch {
            try {
                val id = ApiClient.hastaKaydiOlustur(randevuId = appointment.apiId)
                Haptics.success()
                yeniHastaId = id
                errorText = null
                dataManager.refreshAppointments()
                hastaSayfasi = HastaBasligi(id = id, ad = appointment.patientName, telefon = appointment.phone.ifEmpty { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                errorText = e.message?.takeIf { it.isNotBlank() } ?: VARSAYILAN_HATA
            } finally {
                kayitAciliyor = false
            }
        }
    }

    // Misafir randevuda (hasta kaydı yok) kart sönük; dokununca "Hasta kaydı yok" (M-E8.3).
    val hastaId = yeniHastaId ?: (if (appointment.isGuest) "" else appointment.patientId)
    val kayitli = hastaId.isNotEmpty()

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
                appointment = appointment,
                statusColor = statusColor,
                onCall = { telURL?.let { openSystemUrl(context, it) } }, callEnabled = telURL != null,
                onMessage = { smsURL?.let { openSystemUrl(context, it) } }, messageEnabled = smsURL != null,
                kayitli = kayitli,
                onPatientCard = {
                    if (kayitli) {
                        hastaSayfasi = HastaBasligi(id = hastaId, ad = appointment.patientName, telefon = appointment.phone.ifEmpty { null })
                    } else {
                        misafirUyarisi = true
                    }
                },
                // Misafir randevu: "Hasta kaydı oluştur" (`patient.create`; yetkisizse sönük — M-E7.4).
                hastaKaydiSor = appointment.isGuest && yeniHastaId == null && appointment.apiId.isNotEmpty(),
                kayitAciliyor = kayitAciliyor,
                onHastaKaydi = { hastaKaydiAc() },
            )
            TimeCard(appointment, statusColor)
            DetailsGrid(appointment)
            NotesSection(appointment)
        }

        BottomBar(
            appointment = appointment,
            isUpdating = isUpdating,
            errorText = errorText,
            izinli = { eylemIzinli(caps, it) },
            onRequest = { request(it) },
            onYetkisiz = {
                Haptics.light()
                errorText = Yetki.EYLEM_YETKISIZ_METNI
            },
            onClose = onDismiss,
        )
    }

    // Durum onayı + SMS sorusu (iOS `.sheet` · `presentationDetents([.height(330)])`).
    DfySheet(isPresented = confirming != null, onDismiss = { confirming = null }, containerColor = DS.Colors.surfaceContainer) { dismiss ->
        val action = confirming ?: return@DfySheet
        DurumOnayPenceresi(
            action = action,
            kip = smsKip(action),
            satir = "${appointment.patientName} · ${appointment.time}",
            onConfirm = { notify ->
                perform(action, notify)
                dismiss()
            },
            onCancel = dismiss,
        )
    }

    DfyAlert(
        isPresented = misafirUyarisi,
        title = "Hasta kaydı yok",
        message = "Bu randevunun hasta kaydı yok. Randevu ayrıntısından \"Hasta kaydı oluştur\" ile açabilirsiniz.",
        confirmText = "Tamam",
        onConfirm = { misafirUyarisi = false },
        onDismiss = { misafirUyarisi = false },
    )

    // Yerli hasta sayfası (iOS `.sheet { NavigationStack { HastaSayfasiView(hasta:) } }`) — sayfanın kendi üst çubuğu (ad + geri)
    // var, tutamak çizilmez. Sayfanın tam ekran akışları (medya görüntüleyici …) bu pencerenin içinde açılsın diye yerel kapak
    // katmanı (`CoverHost`) — kök katman sayfaların altında kalırdı.
    DfySheet(
        isPresented = hastaSayfasi != null,
        onDismiss = { hastaSayfasi = null },
        containerColor = DS.Colors.background,
        showDragHandle = false,
    ) { kapat ->
        val hasta = hastaSayfasi ?: return@DfySheet
        Box(Modifier.fillMaxSize()) {
            CoverHost { HastaSayfasiView(hasta = hasta, onDismiss = kapat) }
        }
    }
}

private const val VARSAYILAN_HATA = "Durum kaydedilemedi. Lütfen tekrar deneyin."

/** Eylemin yetenekleri oturumda var mı (M-E7.4) — karar `Yetki`den (sunucunun kapısıyla aynı küme). */
internal fun eylemIzinli(caps: Set<String>, action: StatusAction): Boolean = Yetki.randevuEylemi(action.target).all { it in caps }

private fun statusColor(code: String): Color = when (code) {
    "CONFIRMED", "COMPLETED" -> DS.Colors.success
    "ARRIVED", "IN_TREATMENT" -> DS.Colors.purple400
    "CANCELLED" -> DS.Colors.danger
    "NO_SHOW", "RESCHEDULED" -> DS.Colors.warning
    else -> DS.Colors.cyan400
}

private fun message(error: Exception): String = when (error) {
    is ApiException.ServerError -> error.serverMessage?.takeIf { it.isNotEmpty() } ?: VARSAYILAN_HATA
    is ApiException.Kodlu -> error.cumle?.takeIf { it.isNotEmpty() } ?: VARSAYILAN_HATA
    is ApiException.Unauthorized -> "Oturumun süresi doldu; yeniden giriş yapın."
    is ApiException.NetworkError -> "Sunucuya ulaşılamadı. Bağlantınızı kontrol edin."
    else -> VARSAYILAN_HATA
}

// MARK: - Hasta

@Composable
private fun PatientHeader(
    appointment: Appointment,
    statusColor: Color,
    onCall: () -> Unit, callEnabled: Boolean,
    onMessage: () -> Unit, messageEnabled: Boolean,
    kayitli: Boolean,
    onPatientCard: () -> Unit,
    hastaKaydiSor: Boolean,
    kayitAciliyor: Boolean,
    onHastaKaydi: () -> Unit,
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
                Text(appointment.patientInitials, style = sys(20, FontWeight.Bold), color = statusColor, maxLines = 1)
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
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            ContactButton(
                "person.text.rectangle", "Hasta kartı", enabled = true, action = onPatientCard,
                modifier = Modifier.weight(1f).alpha(if (kayitli) 1f else YetkiliEylem.SONUK_OPAKLIK),
            )
        }

        if (hastaKaydiSor) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
            Text(
                "Fatura, tedavi planı ve ödeme için önce hasta kaydı oluşturun.",
                Modifier.fillMaxWidth(), style = sys(12), color = DS.Colors.onSurfaceVariant,
            )
            YetkiliButon(listOf("patient.create"), eylem = onHastaKaydi, modifier = Modifier.fillMaxWidth()) { mod ->
                Row(
                    mod
                        .fillMaxWidth()
                        .height(40.dp)
                        .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (kayitAciliyor) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
                    } else {
                        // SF "person.badge.plus" web ikon setinde yok → Material karşılığı.
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, Modifier.size(17.dp), tint = DS.Colors.cyan400)
                    }
                    Text(
                        "Hasta kaydı oluştur", style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
            .semantics { role = Role.Button }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DfyIcon(icon, size = 17.dp, tint = color)
        Text(label, style = sys(12, FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    style = sys(14, FontWeight.SemiBold).monoDigits(), color = DS.Colors.cyan400, maxLines = 1,
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
            style = sys(12, FontWeight.SemiBold), color = statusColor, maxLines = 1,
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
                        Text(cell.first, style = sys(12, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        get() = when (this) {
            cancel -> "Randevu iptal edilsin mi?"
            noShow -> "Hasta gelmedi olarak işaretlensin mi?"
            else -> "$title?"
        }
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
    izinli: (StatusAction) -> Boolean,
    onRequest: (StatusAction) -> Unit,
    onYetkisiz: () -> Unit,
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
                            Text("Diğer", style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1)
                            DfyIcon("chevron.up", size = 11.dp, tint = DS.Colors.onSurface)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = DS.Colors.surfaceContainerHigh,
                        ) {
                            others.forEach { action ->
                                val tint = if (action == StatusAction.cancel) DS.Colors.danger else DS.Colors.onSurface
                                // Yetkisiz satır sönük çizilir; dokununca işlem yapılmaz, kısa uyarı (M-E7.4).
                                val izin = izinli(action)
                                DropdownMenuItem(
                                    text = { Text(action.title, style = sys(15), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { DfyIcon(action.icon, size = 17.dp, tint = tint) },
                                    modifier = Modifier.alpha(if (izin) 1f else YetkiliEylem.SONUK_OPAKLIK),
                                    onClick = {
                                        menuOpen = false
                                        if (izin) onRequest(action) else onYetkisiz()
                                    },
                                )
                            }
                        }
                    }
                }

                if (primary != null) {
                    Box(Modifier.weight(1f)) {
                        YetkiliButon(
                            Yetki.randevuEylemi(primary.target),
                            eylem = { if (!isUpdating) onRequest(primary) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { mod ->
                            Row(
                                mod
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .alpha(if (isUpdating) 0.85f else 1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(DS.Gradients.primaryGradient)
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isUpdating) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    DfyIcon(primary.icon, size = 15.dp, tint = Color.White)
                                    Text(
                                        primary.title, style = sys(16, FontWeight.SemiBold), color = Color.White,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Onay penceresi

/** Durum onayı + SMS sorusu (Ek MO3.5 / MO3.6). "Sor" kipinde ön seçim YOK ve seçim yapılmadan onay düğmesi kapalı. */
@Composable
private fun DurumOnayPenceresi(
    action: StatusAction,
    kip: RandevuSms.Kip,
    satir: String,
    onConfirm: (Boolean?) -> Unit,
    onCancel: () -> Unit,
) {
    var cevap by remember { mutableStateOf<Boolean?>(null) }
    val onayAcik = RandevuSms.onayAcik(kip, cevap)
    Column(
        Modifier
            .fillMaxWidth()
            .background(DS.Colors.surfaceContainer)
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(action.confirmTitle, style = sys(18, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(satir, style = sys(13), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when (kip) {
            is RandevuSms.Kip.otomatik -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DfyIcon("message", size = 13.dp, tint = DS.Colors.onSurfaceVariant)
                Text(
                    RandevuSms.otomatikMetni(kip.tur), Modifier.weight(1f),
                    style = sys(13, FontWeight.Medium), color = DS.Colors.onSurfaceVariant,
                )
            }
            is RandevuSms.Kip.sor -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(RandevuSms.soruMetni(kip.tur), style = sys(14, FontWeight.Medium), color = DS.Colors.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmsSecenegi("Gönder", secili = cevap == true) { cevap = true; Haptics.selection() }
                    SmsSecenegi("Gönderme", secili = cevap == false) { cevap = false; Haptics.selection() }
                }
                if (cevap == null) {
                    Text(RandevuSms.secimsizMetni, style = sys(12), color = DS.Colors.outline)
                }
            }
            RandevuSms.Kip.yok -> Unit
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f)) { ButtonLabel("Vazgeç") }
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .alpha(if (onayAcik) 1f else 0.45f)
                    .pressable(enabled = onayAcik) { onConfirm(RandevuSms.notifyPatient(kip, cevap)) }
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (action == StatusAction.cancel) Modifier.background(DS.Colors.danger)
                        else Modifier.background(DS.Gradients.primaryGradient)
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    action.title, style = sys(15, FontWeight.SemiBold), color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SmsSecenegi(baslik: String, secili: Boolean, onClick: () -> Unit) {
    val renk = if (secili) DS.Colors.cyan400 else DS.Colors.onSurface
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (secili) 0.1f else 0.04f))
            .border(1.dp, if (secili) DS.Colors.cyan400.copy(alpha = 0.6f) else DS.Colors.hairlineStrong, CircleShape)
            .plainClickable(onClick = onClick)
            .semantics {
                selected = secili
                role = Role.RadioButton
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DfyIcon(if (secili) "largecircle.fill.circle" else "circle", size = 14.dp, tint = renk)
        Text(baslik, style = sys(14, FontWeight.SemiBold), color = renk, maxLines = 1)
    }
}

// MARK: - Önizleme

private fun ornekRandevu(misafir: Boolean = false): Appointment {
    val sample = MockData.appointments[2]
    return Appointment(
        patientName = sample.patientName, treatment = sample.treatment, time = sample.time, room = sample.room,
        accentColor = sample.accentColor, icon = sample.icon, note = sample.note, patientId = if (misafir) "" else sample.patientId,
        doctorName = sample.doctorName, clinicalNotes = sample.clinicalNotes, agency = sample.agency,
        institution = sample.institution, appointmentStatus = "Planlandı", startHour = 11, durationMinutes = 60,
        apiId = "a1", statusCode = "SCHEDULED", phone = "+90 532 123 45 67", reason = "Diş ağrısı", endTime = "12:00",
        isGuest = misafir,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AppointmentDetailSheetPreview() {
    PreviewEnv { AppointmentDetailSheet(appointment = ornekRandevu(), onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AppointmentDetailSheetMisafirPreview() {
    PreviewEnv { AppointmentDetailSheet(appointment = ornekRandevu(misafir = true), onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390)
@Composable
private fun DurumOnayPenceresiPreview() {
    PreviewEnv {
        Column {
            DurumOnayPenceresi(StatusAction.cancel, RandevuSms.Kip.sor("APPOINTMENT_CANCELLED"), "Can Aydin · 11:00", {}, {})
            DurumOnayPenceresi(StatusAction.noShow, RandevuSms.Kip.otomatik("APPOINTMENT_NO_SHOW"), "Can Aydin · 11:00", {}, {})
        }
    }
}
